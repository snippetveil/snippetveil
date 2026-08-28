import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.specs.Specs
import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.SignPluginTask
import org.objectweb.asm.ClassReader
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        // ASM reads the class files in the built distribution. It is a dependency of the *build*,
        // never of the plugin: `assertNothingThirdPartyIsShipped` below would fail if it were.
        classpath("org.ow2.asm:asm:9.10.1")
    }
}

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

// The platform to build against, named by `platformProfile` rather than given as a coordinate — so
// that CI's two-version test matrix can say `floor` and `latest` and know nothing else. Which
// product and version each name means, and why there are two, is gradle.properties' business.
fun platformProperty(name: String) = providers.gradleProperty(name).orNull
    ?: error("$name is not set. gradle.properties defines it; see the platformProfile block there.")

val platformProfile = platformProperty("platformProfile")
val (platformType, platformVersion) = when (platformProfile) {
    "floor" -> platformProperty("platformFloorType") to platformProperty("platformFloorVersion")
    "latest" -> platformProperty("platformLatestType") to platformProperty("platformLatestVersion")
    else -> error("platformProfile is '$platformProfile'; it has to be 'floor' or 'latest'.")
}

dependencies {
    // A plain project dependency, not a plugin module: core.jar stays a separate jar in the
    // distribution's lib/, which is the directory `scanDistributionForBannedReferences` walks.
    implementation(project(":core"))

    intellijPlatform {
        // Compiled against the compatibility floor by default, so an API newer than 2024.1 cannot
        // be used by accident. Raising the floor later is free; lowering it is unverified work.
        // The `latest` profile compiles against a recent IDE instead, which is a behaviour check
        // and not a compatibility one: the floor leg of the matrix is what keeps the API honest.
        create(platformType, platformVersion)
        bundledPlugin("com.intellij.java")

        pluginVerifier()

        // The fixture-based tests below drive a real IDE core: `Platform` is the fixture itself,
        // `Plugin.Java` is the Java PSI and index the anonymizer resolves against. Both are
        // test-scope, so neither reaches the distribution — `assertNothingThirdPartyIsShipped`
        // fails the build if that ever stops being true.
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)
    }

    // Test-scope only, and it stays that way: `assertNothingThirdPartyIsShipped` below fails the
    // build the moment anything reaches the shipped runtime classpath.
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // The IntelliJ fixture base classes are JUnit 3 `TestCase`s and there is no JUnit 5 equivalent,
    // so the vintage engine runs them alongside the Jupiter tests rather than the build having to
    // pick one. `junit:junit` is named explicitly rather than taken transitively: the version the
    // fixtures run under is not something to inherit by accident.
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine")

    // The plain library, not archunit-junit5: the rules are ordinary @Test methods, so nothing here
    // has to agree with the platform's own bundled test engines about which JUnit is in charge.
    testImplementation("com.tngtech.archunit:archunit:1.5.0")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)

    // Nothing here ships a Kotlin stdlib — the platform provides one, and at the 241 floor that is
    // 1.9.x. This pin narrows the gap rather than closing it: 2.0 is the oldest level this compiler
    // still accepts, so a stdlib symbol introduced in 2.0 would still compile. What actually catches
    // that is verifyPlugin, which resolves every reference against IC-241 itself.
    compilerOptions {
        apiVersion = org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0
    }
}

// ---------------------------------------------------------------------------------------------
// The listing copy
//
// **The Marketplace `<description>` and the README opening are the same strings, not two texts
// saying the same thing.** The reasoning is drift: two differently-worded statements of the same
// claim invite *which one is true*, and on a plugin whose entire moat is trust that question is
// more expensive than the tailoring it buys.
//
// README.md is the one copy. The block between its two `listing copy` markers is rendered to HTML
// here and patched into the descriptor, so the descriptor cannot be edited into a second version of
// itself — `plugin.xml` carries no `<description>` at all. `assertTheListingCopyIsTheReadme` below
// reads the description back out of the built distribution and asserts it is still that block.
//
// Consequence, by construction rather than discipline: **the Approval Guidelines now govern the
// README too** — no third-party brand references, no marketing adjectives, no unverifiable claims,
// English first, HTTPS links only. The strictest surface wins automatically.
// ---------------------------------------------------------------------------------------------

/**
 * Every jar in the built distribution's `lib/`.
 *
 * Spelled once because three rules below walk the same zip looking for different files inside it,
 * and a narrowing applied to one copy of this pattern would quietly stop the other two reading a
 * directory without any of them going red. `scanDistributionForBannedReferences` keeps a pattern of
 * its own on purpose: it matches everything in `lib/` rather than only the jars, because a file in
 * there that is *not* an archive is a hole in that scan rather than a file to skip.
 */
val libraryJarEntry = Regex("""[^/]+/lib/.+\.jar""")

/**
 * The markers README.md fences the shared block with.
 *
 * Spelled once and read by both the renderer below and the assertion at the bottom of this file,
 * because a rename that touched only one of them would leave a check reading an empty block — and a
 * check that cannot fail is worse than no check, since it reads as a guarantee.
 *
 * The *extraction* around them is written twice, once here at configuration time and once inside
 * that task, because a task action may not call back into the script without dragging the script
 * into the configuration cache. Both spellings refuse an empty block for that reason.
 */
val listingCopyStart = "<!-- listing copy -->"
val listingCopyEnd = "<!-- listing copy end -->"

/**
 * The shared block, as Markdown.
 *
 * Read through `providers.fileContents` rather than with `File.readText`, so that Gradle records
 * README.md as an input of the *configuration* and an edit to the copy re-patches the descriptor
 * instead of hitting a stale configuration cache.
 */
val listingCopyMarkdown: String = run {
    val readme = providers.fileContents(layout.settingsDirectory.file("README.md")).asText.get()
    val start = readme.indexOf(listingCopyStart)
    val end = readme.indexOf(listingCopyEnd)
    check(start >= 0 && end > start) {
        "README.md must fence the listing copy between `$listingCopyStart` and `$listingCopyEnd`."
    }
    readme.substring(start + listingCopyStart.length, end).trim()
}

/**
 * Renders the shared block to the HTML the Marketplace shows.
 *
 * **Deliberately tiny, and deliberately fail-closed.** It knows the four constructs the block is
 * written in — paragraph, `###` heading, `-` list, and the `**bold**` / `*italic*` / `` `code` ``
 * spans — and throws on anything else rather than passing it through as literal text. A renderer
 * that shrugged at an unknown construct would ship a stray asterisk to every IDE that shows the
 * listing, and the listing is the one surface nobody reviews after the first upload.
 *
 * A Markdown library would be a build dependency to avoid one screenful of code, on a build whose
 * dependencies are themselves part of the claim.
 */
fun markdownToHtml(markdown: String): String {
    fun inline(text: String): String {
        val rendered = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace(Regex("""`([^`]+)`"""), "<code>$1</code>")
            .replace(Regex("""\*\*([^*]+)\*\*"""), "<b>$1</b>")
            .replace(Regex("""\*([^*]+)\*"""), "<i>$1</i>")

        val unconsumed = Regex("""[`*_\[\]]""").find(rendered)
        check(unconsumed == null) {
            "The listing copy uses a construct this renderer does not know, at " +
                "'${rendered.substring(maxOf(0, unconsumed!!.range.first - 30), unconsumed.range.last + 1)}'. " +
                "Teach the renderer or change the copy; do not let it ship as literal text."
        }
        return rendered
    }

    return markdown.trim().split(Regex("""\n[ \t]*\n""")).joinToString("\n") { block ->
        val lines = block.trim().lines()
        when {
            lines.first().startsWith("### ") -> {
                check(lines.size == 1) { "A heading is one line; this one is ${lines.size}: $block" }
                "<h3>${inline(lines.first().removePrefix("### "))}</h3>"
            }

            lines.first().startsWith("- ") -> {
                val items = mutableListOf<StringBuilder>()
                lines.forEach { line ->
                    if (line.startsWith("- ")) {
                        items += StringBuilder(line.removePrefix("- "))
                    } else {
                        // A wrapped continuation of the item above. Indentation is what says so, and
                        // an unindented line here means the block is not the list it looked like.
                        check(line.startsWith("  ")) { "A list item's continuation must be indented: $line" }
                        items.last().append(' ').append(line.trim())
                    }
                }
                items.joinToString("\n", "<ul>\n", "\n</ul>") { "<li>${inline(it.toString())}</li>" }
            }

            else -> {
                // Nothing that looks like another construct may reach the paragraph branch: a `>`
                // quote or a numbered list rendered as a run-on paragraph is exactly the silent
                // pass-through this renderer exists to refuse.
                lines.forEach { line ->
                    check(!Regex("""^([#>|]|\d+\.|- )""").containsMatchIn(line)) {
                        "The listing copy uses a block construct this renderer does not know: $line"
                    }
                }
                "<p>${inline(lines.joinToString(" ") { it.trim() })}</p>"
            }
        }
    }
}

/** The descriptor's `<description>`, and the only place it is produced. */
val listingCopyHtml: String = markdownToHtml(listingCopyMarkdown)

/**
 * The Marketplace channel this version belongs on, taken from its semver pre-release suffix:
 * `1.0.0` is `default`, `1.1.0-beta.1` would be `beta`.
 *
 * A plain `String` computed while the build configures, rather than a `Provider`: the version is a
 * constant in gradle.properties that a human edits in the pull request that earns it, so there is
 * nothing here for a provider to defer — and a `project.version` read from inside a provider would
 * be a `Project` reference at execution time, which the configuration cache refuses.
 */
val marketplaceChannel: String =
    version.toString().substringAfter('-', "").substringBefore('.').ifEmpty { "default" }

// ---------------------------------------------------------------------------------------------
// The changelog
//
// **CHANGELOG.md is at the repository root and the plugin that reads it is applied here**, which
// looks backwards for about one second. The reason is the same one that puts
// `assertNoRoadmapIsPublished` in this file: the change notes are a property of the *distribution*,
// which is `:plugin`'s to build, and the rule that reads them opens the built zip rather than the
// checked-in descriptor. A changelog applied at the root would put the parser one project away
// from the only thing that consumes it.
//
// Task names are not namespaced by the project they are registered in when Gradle is invoked from
// the root, so `./gradlew getChangelog --unreleased` and `./gradlew patchChangelog` both work from
// where a person would type them.

changelog {
    // The repository root, not this subproject. `layout.settingsDirectory` is the settings file's
    // directory — the one place that means "the repository" without a `../` in it.
    path = layout.settingsDirectory.file("CHANGELOG.md").asFile.absolutePath

    // The Marketplace rejects placeholder change notes, and an empty `### Fixed` under a release
    // that fixed nothing is a placeholder that a generator wrote rather than a person. No groups
    // means the section is a plain list of what actually changed, which is what a reader wants and
    // what an approver is checking for.
    groups = emptyList()

    // Every heading link resolves to a comparison on GitHub, so a reader can go from a line in the
    // changelog to the commits behind it without being asked to take the line on faith.
    repositoryUrl = "https://github.com/snippetveil/snippetveil"
}

intellijPlatform {
    // The distribution is SnippetVeil, not "plugin" — the subproject name must not name the product.
    projectName = "SnippetVeil"

    pluginConfiguration {
        // Generated from README.md; see the listing-copy section above. Assigning it here is what
        // makes `plugin.xml` free to carry no `<description>` of its own.
        description = listingCopyHtml

        // The section for the version being built, or `Unreleased` while there is not one yet —
        // rendered to HTML, which is what the descriptor's `<change-notes>` takes.
        //
        // **Read at execution time, through a `provider`.** The changelog is a file on disk that a
        // release patches, and a value captured while the build was configuring would be the one
        // from before the patch. `withHeader(false)` drops the `## 1.0.0 - 2026-…` line, because
        // the Marketplace shows the version beside the notes already and a heading repeating it
        // reads as boilerplate.
        //
        // `assertNoRoadmapIsPublished` reads this back out of the built distribution: whatever
        // lands here is a published surface and is governed by the same rules as the README.
        changeNotes = provider {
            with(changelog) {
                renderItem(
                    (getOrNull(project.version.toString()) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }

        ideaVersion {
            sinceBuild = "241"

            // Never pinned: a new IDE major must not force a compatibility re-release. The Gradle
            // plugin still defaults this, so it is nulled explicitly rather than left out.
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        ides {
            recommended()
        }
    }

    publishing {
        // **The channel is derived from the version, and v1 ships nothing that derives anything
        // but `default`.**
        //
        // There is one Marketplace channel here, deliberately. A non-default channel is a separate
        // repository URL the user has to add to their IDE by hand, and every upload is manually
        // reviewed anyway — beta included — so a `beta` channel shortens no latency and reaches no
        // audience that has not already been told where to look. It is a distribution mechanism
        // with nobody on the other end.
        //
        // Pre-releases are GitHub Release assets instead: `build.yml` already uploads the
        // distribution zip, and a tester installs it with *Install Plugin from Disk*.
        //
        // These three lines stay anyway, because the alternative to deriving the channel is
        // remembering to set it — and the release that needs a channel is the one where somebody is
        // already doing something unusual. `1.1.0-beta.1` routes to `beta` on the day it is wanted,
        // and no version this project has ever built routes anywhere but `default`.
        channels = listOf(marketplaceChannel)
    }
}

// ---------------------------------------------------------------------------------------------
// Trust checks
//
// SnippetVeil's central claim is that it makes no network calls. The two tasks below make the
// claim checkable rather than asserted, and hang off Gradle rather than off a CI workflow — because
// a check that exists only in YAML cannot be run by the person reading the claim. Everything here
// is reproducible with a clone and `./gradlew`.
//
// The third layer, an ArchUnit test over the compiled sources, is wired into `check` by
// `plugin/src/test/kotlin/com/snippetveil/trust/ShippedCodeArchitectureTest.kt`.
// ---------------------------------------------------------------------------------------------

// Every check below registers its task under a string that repeats the `val` on the same line. That
// is a decision, not a leftover of the Gradle 9.6 migration off `by tasks.registering` — the root
// `build.gradle.kts` gives the reasoning, in the banner above `corpusSweepTask`.

/**
 * Walks every class in the built distribution's `lib/` and fails on a constant-pool reference to a
 * networking class, to a process-execution class, or to `Runtime.exec`.
 *
 * **This is the only check that covers bundled or generated code** — the vector behind the June 2026
 * removal of 15 clipboard-stealing "AI" plugins from the Marketplace. The ArchUnit rules read
 * classes compiled from this repository's sources; this one reads what is actually in the zip that
 * gets uploaded, whoever compiled it and from what.
 *
 * `buildPlugin` finalizes it, `publishPlugin` depends on it, and so does `check` — see the wiring
 * below each task. Three edges rather than one: `finalizedBy` alone orders the scan after
 * `buildPlugin` without promising it runs before everything else that depends on `buildPlugin`,
 * which is not good enough when the other thing is an upload.
 */
val scanDistributionForBannedReferences = tasks.register("scanDistributionForBannedReferences") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Fails if any class in the built distribution can reach the network or start a process."

    val distribution = tasks.named<Zip>("buildPlugin").flatMap { it.archiveFile }
    val report = layout.buildDirectory.file("reports/trust/banned-reference-scan.txt")

    inputs.file(distribution).withPropertyName("distribution")
    outputs.file(report).withPropertyName("report")

    doLast {
        // Networking, as the JVM spells it. Internal names (`java/net/URL`) and source names
        // (`java.net.URL`, as a reflective lookup would spell it) both match, because every constant
        // is normalized to the dotted form before it is tested; matching mid-string also pulls the
        // type out of a descriptor such as `(Ljava/net/URL;)V`.
        //
        // `java.nio.channels` is matched by class rather than by package because the package is
        // mostly file I/O; banning `FileChannel` would be noise, and noise is what teaches people to
        // suppress a check. ShippedCodeArchitectureTest states the same policy independently, over a
        // different set of inputs, and the `java.nio.channels` half is written there character for
        // character as it is here so that the two can be diffed by eye.
        val networkingClass =
            """(java\.net|javax\.net|java\.rmi|sun\.net|jdk\.internal\.net)\.[\w.$]+""" +
                """|java\.nio\.channels\.[\w.$]*(Socket|Datagram|Network)Channel[\w$]*"""

        // Process execution. A subprocess is a network call none of the patterns above can see:
        // `Runtime.getRuntime().exec("curl …")` reaches the network with no `java.net` reference in
        // the pool at all — the same shape of hole as a reflective lookup.
        //
        // `java.lang.Runtime` is deliberately absent. It is banned one method at a time further
        // down, so that `Runtime.getRuntime().availableProcessors()` stays legal; a rule that banned
        // the class outright would be the kind of noise that teaches people to suppress a check.
        //
        // Written character for character as it is in ShippedCodeArchitectureTest, which is why it
        // ends in an exact nested-class tail rather than an open `[\w$]*`: that layer matches whole
        // class names, so an open tail there would mean something different from what it means here.
        // Longest alternative first, so that a hit on `java.lang.ProcessBuilder` is reported as the
        // type it is rather than as the `java.lang.Process` prefix inside it.
        val processExecutionClass = """java\.lang\.(ProcessBuilder|ProcessHandle|Process)(\$[\w$]+)?"""

        val bannedClass = Regex("$networkingClass|$processExecutionClass")

        fun bannedNamesIn(text: String): List<String> =
            bannedClass.findAll(text.replace('/', '.')).map { it.value }.distinct().toList()

        /**
         * Every UTF-8 constant in the pool — tag 1, which is where a class file keeps all of its
         * text. Class references (tag 7), string literals (tag 8, which is how a reflective lookup
         * names its target), member descriptors (tag 12) and method types (tag 16) are all indirect
         * references to a tag 1 entry, and so are the bare `field_info` and `method_info`
         * descriptors and `Signature` attributes that no tag 7 entry reaches. Reading the one tag
         * they all point at is less code than chasing them and covers strictly more.
         *
         * Type names in a class file are ASCII; a multi-byte character decodes to something that
         * could not be part of one either way.
         */
        val CONSTANT_UTF8 = 1

        /**
         * A method reference: tag 10, or tag 11 for one whose owner is an interface.
         *
         * This is the structured half of the scan, and it exists for exactly one rule: telling
         * `Runtime.exec` from `Runtime.availableProcessors`, which needs the owner and the method
         * name resolved as a *pair*. Matching either alone is useless — the owner is legitimate, and
         * `exec` is far too generic a method name to ban by string.
         *
         * **It adds no coverage, and it is worth knowing why it is here anyway.** Every `exec`
         * overload returns a `Process`, so `(Ljava/lang/String;)Ljava/lang/Process;` is in the pool
         * as text and the flat walk already matches it — banning the process *types* closes the
         * `Runtime.exec` gap by itself, even for a call whose result is discarded. What this buys is
         * a report that names `java.lang.Runtime.exec` rather than only `java.lang.Process`, and
         * independence from a type ban that a later narrowing could quietly take exec down with.
         * CONTRIBUTING.md carries the argument.
         */
        val CONSTANT_METHODREF = 10
        val CONSTANT_INTERFACE_METHODREF = 11

        /**
         * Every entry in the constant pool, as the tag it carries and the offset its content starts
         * at — one byte past the tag. Both walks below need this loop and nothing else from ASM.
         */
        fun ClassReader.forEachConstant(action: (tag: Int, offset: Int) -> Unit) {
            for (index in 1 until itemCount) {
                val offset = getItem(index)
                if (offset == 0) continue // The unused second slot of a long or double constant.
                action(readByte(offset - 1), offset)
            }
        }

        /** Every banned class name that appears as text anywhere in [reader]'s constant pool. */
        fun bannedTypeReferencesIn(reader: ClassReader): Set<String> {
            val found = sortedSetOf<String>()
            reader.forEachConstant { tag, offset ->
                if (tag == CONSTANT_UTF8) {
                    val length = reader.readUnsignedShort(offset)
                    val constant = buildString(length) {
                        for (position in 0 until length) append(Char(reader.readByte(offset + 2 + position)))
                    }
                    found += bannedNamesIn(constant)
                }
            }
            return found
        }

        /**
         * Every `Runtime.exec` overload [reader] references. Overloads need no enumerating: the
         * descriptor sits in the second half of the name-and-type, and this reads only the first —
         * so all six spellings of `exec` collapse to one match.
         */
        fun runtimeExecCallsIn(reader: ClassReader): Set<String> {
            val names = CharArray(reader.maxStringLength)
            val found = sortedSetOf<String>()
            reader.forEachConstant { tag, offset ->
                if (tag == CONSTANT_METHODREF || tag == CONSTANT_INTERFACE_METHODREF) {
                    // A method reference is (class_index, name_and_type_index), and a name-and-type
                    // is (name_index, descriptor_index) — so the method's own name sits at the head
                    // of the entry the second half points at.
                    val owner = reader.readClass(offset, names)
                    val nameAndType = reader.getItem(reader.readUnsignedShort(offset + 2))
                    if (owner == "java/lang/Runtime" && reader.readUTF8(nameAndType, names) == "exec") {
                        found += "java.lang.Runtime.exec"
                    }
                }
            }
            return found
        }

        fun bannedReferencesIn(reader: ClassReader): Set<String> =
            bannedTypeReferencesIn(reader) + runtimeExecCallsIn(reader)

        // The scan proves it can fail before it reports that nothing failed. A check whose red path
        // is never exercised decays into a check that always passes — and the whole point of these
        // tasks is that the no-network claim is demonstrated rather than trusted. Every fixture below
        // is read from the running JDK's own image: class files are never encapsulated by the module
        // system, so all of them are always available.
        fun jdkClass(name: String): ClassReader =
            checkNotNull(Any::class.java.getResourceAsStream("/$name.class")) { "$name is missing from this JDK" }
                .use { ClassReader(it.readBytes()) }

        // The flat walk: one networking hit, one process hit, and nothing on a class that is neither.
        check(bannedTypeReferencesIn(jdkClass("java/net/URL")).isNotEmpty()) {
            "The scanner failed to flag java.net.URL. It cannot be trusted to flag anything."
        }
        check(bannedTypeReferencesIn(jdkClass("java/lang/ProcessBuilder")).isNotEmpty()) {
            "The scanner failed to flag java.lang.ProcessBuilder. It cannot be trusted to flag a process type."
        }
        check(bannedTypeReferencesIn(jdkClass("java/lang/Object")).isEmpty()) {
            "The scanner flagged java.lang.Object. It is matching more than networking and process execution."
        }

        // The structured walk needs its own pair, because the flat walk would carry both of the
        // process assertions above on its own. `java.lang.Runtime` calls `Runtime.exec` internally,
        // and `ForkJoinPool` is the JDK's own `Runtime.getRuntime().availableProcessors()` — the
        // exact call this scan must not fail on, in a class that also names `java.lang.Runtime` as a
        // type, so it fails if either walk starts banning the class rather than the method.
        check(runtimeExecCallsIn(jdkClass("java/lang/Runtime")).isNotEmpty()) {
            "The scanner failed to flag Runtime.exec inside java.lang.Runtime. Nothing reads tag 10."
        }
        check(bannedReferencesIn(jdkClass("java/util/concurrent/ForkJoinPool")).isEmpty()) {
            "The scanner flagged ForkJoinPool, which only calls Runtime.getRuntime().availableProcessors(). " +
                "java.lang.Runtime has to stay legal."
        }

        fun isArchive(name: String) = name.endsWith(".jar") || name.endsWith(".zip")

        val scanned = sortedSetOf<String>()
        val violations = mutableListOf<String>()
        // `.+` rather than `[^/]+`: the platform plugin nests jars under `lib/modules/`, and a
        // subdirectory the scan skipped silently would be the easiest possible place to hide a class.
        val libEntry = Regex("""[^/]+/lib/.+""")

        ZipFile(distribution.get().asFile).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory && libEntry.matches(it.name) }
                .forEach { entry ->
                    // Anything in lib/ that this task cannot read is a hole in the scan, not a file
                    // to skip quietly.
                    if (!isArchive(entry.name)) {
                        violations += "${entry.name} is in lib/ but is not an archive, so it was not scanned"
                        return@forEach
                    }
                    ZipInputStream(zip.getInputStream(entry)).use { jar ->
                        generateSequence { jar.nextEntry }
                            .filterNot { it.isDirectory }
                            .forEach { nested ->
                                val where = "${entry.name}!/${nested.name}"
                                when {
                                    nested.name.endsWith(".class") -> {
                                        scanned += where
                                        bannedReferencesIn(ClassReader(jar.readBytes()))
                                            .forEach { violations += "$where references $it" }
                                    }
                                    // The same rule one level down: a jar inside a jar is where code
                                    // hides from a scanner that only looks at the top level.
                                    isArchive(nested.name) ->
                                        violations += "$where is a nested archive, so it was not scanned"
                                }
                            }
                    }
                }
        }

        // A scan that found nothing to scan is not a pass.
        check(scanned.isNotEmpty()) { "No classes were found in ${distribution.get().asFile}/*/lib/. Nothing was scanned." }

        report.get().asFile.also { it.parentFile.mkdirs() }.writeText(
            buildString {
                appendLine("${distribution.get().asFile.name} — class files scanned: ${scanned.size}")
                appendLine("Nothing scanned may reference a class matching:")
                appendLine("  $bannedClass")
                appendLine("or call java.lang.Runtime.exec.")
                appendLine()
                scanned.forEach { appendLine(it) }
            }
        )

        if (violations.isNotEmpty()) {
            throw GradleException(
                "SnippetVeil makes no network calls and starts no subprocesses, but the built " +
                    "distribution says otherwise:\n" +
                    violations.joinToString("\n") { "  $it" }
            )
        }
    }
}

tasks.named<Zip>("buildPlugin") {
    finalizedBy(scanDistributionForBannedReferences)
}

// `publishPlugin` → `signPlugin` → `buildPlugin`, so the finalizer above is *ordered* before an
// upload but not *required* by it: Gradle promises only that a finalizer runs after the task it
// finalizes, not that it runs before that task's other dependents. A real gate needs the edge.
tasks.named("publishPlugin") {
    dependsOn(scanDistributionForBannedReferences)
}

/**
 * Fails if anything but `:core` reaches the shipped runtime classpath.
 *
 * This is how the inbound licence policy is enforced: by asserting emptiness rather than by
 * classifying licences, which is strictly stronger while it holds. CONTRIBUTING.md carries the
 * argument and the policy itself.
 */
val assertNothingThirdPartyIsShipped = tasks.register("assertNothingThirdPartyIsShipped") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Fails if the shipped runtime classpath contains anything but :core."

    val runtimeClasspath = configurations.named("runtimeClasspath")
    val shipped = runtimeClasspath.flatMap { it.incoming.artifacts.resolvedArtifacts }
    inputs.files(runtimeClasspath).withPropertyName("runtimeClasspath")

    doLast {
        val shippedComponents = shipped.get().map { it.id.componentIdentifier }

        // Not vacuous: core.jar has to actually be in there.
        check(shippedComponents.isNotEmpty()) { "The shipped runtime classpath is empty; :core is not being shipped." }

        val thirdParty = shippedComponents.filterNot { it is ProjectComponentIdentifier && it.projectPath == ":core" }
        if (thirdParty.isNotEmpty()) {
            throw GradleException(
                "The distribution must contain nothing but :core, but the runtime classpath also has:\n" +
                    thirdParty.joinToString("\n") { "  $it" } +
                    "\nSee the inbound dependency policy in CONTRIBUTING.md."
            )
        }
    }
}

tasks.named("check") {
    dependsOn(assertNothingThirdPartyIsShipped)

    // `finalizedBy` above gates the release path; this puts the same scan on the path a contributor
    // and a pull request actually run. Without it, the one check that covers bundled code would be
    // the one check nobody runs before pushing.
    dependsOn(scanDistributionForBannedReferences)
}


/**
 * Fails if the description in the built distribution is not the listing-copy block in README.md,
 * word for word — and if that block breaks one of the Approval Guidelines it is now under.
 *
 * **The identity is generated, so this check exists to catch the generator being bypassed**, not to
 * re-verify an assignment two lines apart. The failure it is written against is the ordinary one: a
 * `<description>` put back into `plugin.xml` and the patch quietly unwired, leaving a listing that
 * reads differently from the README while both look authoritative. So it reads the description out
 * of the zip that gets uploaded, the same input `scanDistributionForBannedReferences` reads, and
 * compares it against README.md by a *second, independent* derivation: tags stripped on one side,
 * Markdown markers stripped on the other, both reduced to a stream of words. A renderer that
 * silently dropped a bullet fails here too.
 *
 * The three guideline rules it can actually decide are checked on the same text, because this is
 * where the shipped description is already open: no banned phrase, no third-party brand, and
 * HTTPS-only links. `assertNoBannedPhraseAppearsOnAnySurface` in the root build covers the phrase
 * ban across every other surface; the rest of the guidelines are judgement, and a check that guessed
 * at them would be the kind of noise that teaches people to suppress a check.
 */
val assertTheListingCopyIsTheReadme = tasks.register("assertTheListingCopyIsTheReadme") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Fails if the shipped description is not the listing-copy block in README.md."

    val distribution = tasks.named<Zip>("buildPlugin").flatMap { it.archiveFile }
    val readme = layout.settingsDirectory.file("README.md")
    val report = layout.buildDirectory.file("reports/trust/listing-copy.txt")

    // Bound here rather than read inside the action: a task action that reaches back into the script
    // for a top-level property drags the script into the configuration cache with it.
    val startMarker = listingCopyStart
    val endMarker = listingCopyEnd
    val libraryJar = libraryJarEntry

    /**
     * The headings the block carries, in the order it carries them.
     *
     * **This is the fold rule, asserted.** The value proposition and *How it works* are above both
     * negative lists; the negative lists ship full, in this order, below them. Every alternative
     * ordering silently re-cuts a closed decision, so the order is pinned rather than left to
     * whoever next edits the copy.
     */
    val headings = listOf(
        "How it works",
        "No network",
        "What SnippetVeil does not hide",
        "What it does not preserve",
        "Non-goals",
        "Source",
    )

    /**
     * Names this copy would plausibly reach for and may not use.
     *
     * **Not a trademark database, and it does not pretend to be one.** The guideline is "no
     * third-party brand references"; what is checkable is a short list of the names a sentence about
     * pasting Java into an AI chat would actually be tempted by — the assistants, and the editors
     * that are not the one this plugin ships to. IDEA, Android Studio and the JetBrains Marketplace
     * are the host platform rather than a third party, and are absent for that reason.
     */
    val thirdPartyBrands = listOf(
        "ChatGPT", "OpenAI", "Claude", "Anthropic", "Copilot", "Gemini", "Bard", "Llama",
        "Cursor", "VS Code", "Visual Studio", "Eclipse", "NetBeans", "Vim",
        "Spring", "Lombok", "Hibernate", "Jackson", "Guava",
    )

    // The listing is the one surface the phrase ban exists for, so it is checked here as well as in
    // the root build's sweep over the repository — on the shipped bytes rather than on a file that
    // is asserted, elsewhere, to be the same as them.
    //
    // **The list itself comes from the root build**, the way the corpus sweep's task name does. Two
    // lists would drift, and the direction they would drift in is the bad one: the strictest surface
    // checked against the laxest rule, with both checks green.
    @Suppress("UNCHECKED_CAST")
    val bannedPhrases = rootProject.extra["bannedPhrases"] as List<String>

    inputs.file(distribution).withPropertyName("distribution")
    inputs.file(readme).withPropertyName("readme")
    inputs.property("headings", headings)
    inputs.property("thirdPartyBrands", thirdPartyBrands)
    inputs.property("bannedPhrases", bannedPhrases)
    outputs.file(report).withPropertyName("report")

    doLast {
        /**
         * Every word of an HTML fragment, tags and entities resolved away.
         *
         * **A block tag is whitespace and an inline tag is not**, and the distinction is
         * load-bearing rather than tidiness: `<b>Copy Anonymized</b>.` is one word ending in a full
         * stop, exactly as `**Copy Anonymized**.` is on the Markdown side. Collapsing every tag to a
         * space would put the stop in a word of its own and report a drift on copy that had not
         * moved — and a check that cries wolf is a check people learn to re-run until it is green.
         *
         * A tag this rule cannot classify throws, rather than being guessed at in either direction.
         */
        fun wordsOfHtml(html: String): List<String> {
            val text = html
                .replace(Regex("""</?(p|h[1-6]|ul|ol|li|br|blockquote|pre)\b[^>]*>"""), " ")
                .replace(Regex("""</?(b|i|em|strong|code|a|u|span)\b[^>]*>"""), "")
            check("<" !in text.replace("&lt;", "")) {
                "The description uses an HTML tag this check cannot classify as block or inline: " +
                    Regex("""<[^>]+>""").find(text)?.value
            }
            return text
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .split(Regex("""\s+"""))
                .filter { it.isNotBlank() }
        }

        /** Every word of a Markdown fragment, markers stripped away. */
        fun wordsOfMarkdown(markdown: String): List<String> =
            markdown.replace(Regex("""(?m)^###\s+"""), "")
                .replace(Regex("""(?m)^-\s+"""), "")
                .replace(Regex("""[*`]"""), "")
                .split(Regex("""\s+"""))
                .filter { it.isNotBlank() }

        // Both derivations prove they can agree, and prove they can disagree, before either is
        // pointed at the real thing. A comparison whose red path is never exercised decays into a
        // check that always passes.
        val sampleHtml = "<p>One <b>two</b>. &amp; <code>three</code></p><ul><li>four</li></ul>"
        val sampleMarkdown = "One **two**. & `three`\n\n- four"
        val sampleWords = listOf("One", "two.", "&", "three", "four")

        check(wordsOfHtml(sampleHtml) == sampleWords) {
            "The HTML side read ${wordsOfHtml(sampleHtml)} out of a fragment whose words are $sampleWords."
        }
        check(wordsOfMarkdown(sampleMarkdown) == sampleWords) {
            "The Markdown side read ${wordsOfMarkdown(sampleMarkdown)} out of a block whose words are $sampleWords."
        }
        check(wordsOfHtml(sampleHtml.replace("four", "five")) != wordsOfMarkdown(sampleMarkdown)) {
            "The comparison called two different texts equal. It is checking nothing."
        }

        val readmeText = readme.asFile.readText()
        val start = readmeText.indexOf(startMarker)
        val end = readmeText.indexOf(endMarker)
        check(start >= 0 && end > start) {
            "README.md must fence the listing copy between `$startMarker` and `$endMarker`."
        }
        val block = readmeText.substring(start + startMarker.length, end).trim()
        check(block.isNotEmpty()) { "The listing-copy block in README.md is empty. Nothing was checked." }

        // The descriptor as it ships: inside the plugin jar, inside the zip that gets uploaded.
        val descriptors = mutableMapOf<String, String>()
        ZipFile(distribution.get().asFile).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory && libraryJar.matches(it.name) }
                .forEach { entry ->
                    ZipInputStream(zip.getInputStream(entry)).use { jar ->
                        generateSequence { jar.nextEntry }
                            .filter { it.name == "META-INF/plugin.xml" }
                            .forEach { descriptors["${entry.name}!/${it.name}"] = jar.readBytes().decodeToString() }
                    }
                }
        }

        check(descriptors.size == 1) {
            "The distribution has ${descriptors.size} plugin descriptors: ${descriptors.keys}. " +
                "Exactly one of them is the listing, and this check cannot tell which."
        }
        val (where, descriptor) = descriptors.entries.single()

        val shipped = Regex("""<description><!\[CDATA\[(.*?)]]></description>""", RegexOption.DOT_MATCHES_ALL)
            .find(descriptor)?.groupValues?.get(1)
            ?: throw GradleException("$where carries no <description>. The listing copy is not being patched in.")

        val shippedWords = wordsOfHtml(shipped)
        val readmeWords = wordsOfMarkdown(block)

        if (shippedWords != readmeWords) {
            val at = shippedWords.zip(readmeWords).indexOfFirst { (a, b) -> a != b }
                .let { if (it >= 0) it else minOf(shippedWords.size, readmeWords.size) }
            throw GradleException(
                "The Marketplace description and the README opening are the same strings, or they are " +
                    "two texts inviting the question of which one is true. They have drifted, at word " +
                    "${at + 1}:\n" +
                    "  shipped: ${shippedWords.drop(maxOf(0, at - 6)).take(12).joinToString(" ")}\n" +
                    "  README:  ${readmeWords.drop(maxOf(0, at - 6)).take(12).joinToString(" ")}\n" +
                    "README.md is the one copy; $where is generated from it."
            )
        }

        val violations = mutableListOf<String>()

        val shippedHeadings = Regex("""<h3>(.*?)</h3>""").findAll(shipped).map { it.groupValues[1] }.toList()
        if (shippedHeadings != headings) {
            violations += "the headings are $shippedHeadings, and the fold rule pins them to $headings"
        }

        // The value proposition is above the fold by being first: the negative lists follow it,
        // full and verbatim, and nothing is allowed to overtake it.
        if (!shipped.trimStart().startsWith("<p>")) {
            violations += "the description does not open with the value proposition"
        }

        // The Marketplace rejects a description shorter than this, and a listing that reached the
        // floor by accident would mean the copy had collapsed to a sentence.
        val text = shippedWords.joinToString(" ")
        if (text.length < 40) violations += "the description is ${text.length} characters; 40 is the floor"

        Regex("""\bhttp://\S+""").findAll(shipped).forEach {
            violations += "${it.value} is not HTTPS"
        }

        // Word by word and rejoined on `\s+`, character for character as the root build matches it:
        // the two rules read one list, so they had better read it the same way.
        bannedPhrases.filter { phrase ->
            val pattern = phrase.split(" ").joinToString("""\s+""") { Regex.escape(it) }
            Regex("""\b$pattern\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)
        }.forEach {
            violations += "\"$it\" is banned from every surface: it is a claim about an adversary's " +
                "capability, and the copy states the mechanism rather than the category"
        }

        thirdPartyBrands.filter { Regex("""\b${Regex.escape(it)}\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) }
            .forEach { violations += "\"$it\" is a third-party brand reference" }

        report.get().asFile.also { it.parentFile.mkdirs() }.writeText(
            buildString {
                appendLine("$where — description: ${shippedWords.size} words, ${text.length} characters")
                appendLine("Identical, word for word, to the listing-copy block in README.md.")
                appendLine()
                appendLine("Headings, in order:")
                shippedHeadings.forEach { appendLine("  $it") }
                appendLine()
                appendLine("Checked for: ${bannedPhrases.size} banned phrases, ${thirdPartyBrands.size} third-party brands, HTTPS-only links.")
            }
        )

        if (violations.isNotEmpty()) {
            throw GradleException(
                "The shipped description breaks the Approval Guidelines the README is now under:\n" +
                    violations.joinToString("\n") { "  $it" }
            )
        }
    }
}

tasks.named("check") {
    dependsOn(assertTheListingCopyIsTheReadme)
}

// An upload must not start while the listing is unverified, for the reason the banned-reference
// scan gets the same edge: `publishPlugin` reaches `buildPlugin` without passing through `check`.
tasks.named("publishPlugin") {
    dependsOn(assertTheListingCopyIsTheReadme)
}

/**
 * Fails if the archive `publishPlugin` would upload is not signed.
 *
 * **`signPlugin` skipping is silent, and `publishPlugin` uploads the unsigned zip when it does.**
 * That is not a bug in the platform plugin, it is `PublishPluginTask`'s documented convention:
 *
 * ```
 * archiveFile.convention(
 *     signPluginTaskProvider.map { it.didWork }.flatMap { signed ->
 *         when {
 *             signed -> signPluginTaskProvider.flatMap { it.signedArchiveFile }
 *             else -> buildPluginTaskProvider.flatMap { it.archiveFile }
 *         }
 *     }
 * )
 * ```
 *
 * The fallback exists so that a fork with no key can still run the pipeline, which is worth having.
 * The cost is that **the difference between a signed release and an unsigned one is one empty
 * environment variable, and nothing anywhere says which happened.** `SignPluginTask`'s `onlyIf`
 * treats an empty string as an absent key, so a secret that exists and holds nothing skips signing
 * and leaves a green build behind it.
 *
 * That is not hypothetical here: it is how v1.0.0's first release run behaved, and only the
 * Marketplace's *"upload the plugin once manually"* rule stopped an unsigned upload. Signing is
 * **not mandatory** — an unsigned plugin installs, with a warning — which is exactly why nothing
 * else in the toolchain objects. For a plugin whose pitch is trust, that warning is the product
 * failing on the one screen where it is being decided.
 *
 * So the rule is asserted rather than assumed, and it reads the bytes rather than the task state:
 *
 * 1. **The signed archive exists.** If `signPlugin` never ran, it does not.
 * 2. **It carries the signing block.** `PK Sig Block 42` is the magic the Marketplace ZIP Signer
 *    writes ahead of the central directory — the same shape as an APK signing block. Its presence
 *    is a fact about the file, not a claim about a task that may have skipped.
 * 3. **It is a signature of *this* build.** Signing appends a block and leaves the entries alone, so
 *    a signed archive's entry names and sizes must equal the unsigned one's. This is what stops a
 *    leftover `-signed.zip` from a previous version passing the first two rules on a machine that
 *    did not sign anything today.
 *
 * **This is the one trust check in this repository that cannot run from a clone**, and that is a
 * property of the thing being checked rather than a choice: a signature needs a key, and the key is
 * deliberately reachable only from the environment-gated release job. So it hangs off
 * `publishPlugin` alone and never off `check` — a `check` variant would be vacuous on every machine
 * that has no key, which is every machine except one. Its rules are still exercised on every run,
 * against the unsigned archive, which is the fixture that is always present.
 */
val assertThePluginWasSigned = tasks.register("assertThePluginWasSigned") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Fails if the archive publishPlugin would upload is not signed."

    val signedArchive = tasks.named<SignPluginTask>("signPlugin").flatMap { it.signedArchiveFile }
    val unsignedArchive = tasks.named<Zip>("buildPlugin").flatMap { it.archiveFile }
    val report = layout.buildDirectory.file("reports/trust/plugin-is-signed.txt")

    // `files` rather than `file`: when nothing has been signed the path resolves and the file does
    // not exist, and an input that refused to be declared until it did would be an input that
    // cannot express the very state this task exists to catch.
    inputs.files(signedArchive).withPropertyName("signedArchive")
    inputs.file(unsignedArchive).withPropertyName("unsignedArchive")
    outputs.file(report).withPropertyName("report")

    doLast {
        // What the Marketplace ZIP Signer writes ahead of the central directory. Matched as bytes
        // rather than decoded text, because the block around it is not text.
        val magic = "PK Sig Block 42".toByteArray(Charsets.US_ASCII)

        fun ByteArray.containsMagic(): Boolean {
            outer@ for (start in 0..size - magic.size) {
                for (offset in magic.indices) {
                    if (this[start + offset] != magic[offset]) continue@outer
                }
                return true
            }
            return false
        }

        /** Entry name to size, which signing must leave untouched. */
        fun entriesOf(file: File): Map<String, Long> =
            ZipFile(file).use { zip ->
                zip.entries().asSequence()
                    .filterNot { it.isDirectory }
                    .associate { it.name to it.size }
            }

        val unsigned = unsignedArchive.get().asFile
        val unsignedEntries = entriesOf(unsigned)

        /** Every reason [candidate] is not a signature of the archive built by this run. */
        fun violationsIn(candidate: File): List<String> {
            if (!candidate.exists()) {
                return listOf(
                    "${candidate.name} does not exist, so signPlugin did not run. publishPlugin " +
                        "falls back to the unsigned archive when it skips, which is an unsigned release"
                )
            }

            val violations = mutableListOf<String>()

            if (!candidate.readBytes().containsMagic()) {
                violations += "${candidate.name} carries no signing block"
            }

            val entries = entriesOf(candidate)
            if (entries != unsignedEntries) {
                val differing = (entries.keys + unsignedEntries.keys)
                    .filter { entries[it] != unsignedEntries[it] }
                    .sorted()
                violations += "${candidate.name} is not a signature of this build: it and " +
                    "${unsigned.name} differ on ${differing.size} entr" +
                    (if (differing.size == 1) "y" else "ies") +
                    " (${differing.take(3).joinToString(", ")}${if (differing.size > 3) ", …" else ""})"
            }

            return violations
        }

        // The rules prove they can fail before they report that nothing failed, and the fixture is
        // the one file guaranteed to be present: the archive `buildPlugin` just wrote. It has this
        // build's entries and no signing block, so it must fail on exactly one rule — which also
        // pins the entry comparison as the half that passed.
        val onUnsigned = violationsIn(unsigned)
        check(onUnsigned.size == 1 && onUnsigned.single().endsWith("carries no signing block")) {
            "The rules did not flag the unsigned archive on the signing block alone: $onUnsigned"
        }
        check(violationsIn(unsigned.resolveSibling("nothing-here.zip")).size == 1) {
            "The rules failed to flag a signed archive that does not exist."
        }

        val signed = signedArchive.get().asFile
        val violations = violationsIn(signed)

        report.get().asFile.also { it.parentFile.mkdirs() }.writeText(
            buildString {
                appendLine("Upload candidate: ${signed.name}")
                appendLine("Built archive:    ${unsigned.name} (${unsignedEntries.size} entries)")
                appendLine()
                appendLine("It must exist, carry the `PK Sig Block 42` signing block, and hold the")
                appendLine("same entries at the same sizes as the archive this build produced.")
                appendLine()
                appendLine("Not checked here: that the certificate is the one of record. That is a")
                appendLine("fact about a key in a password manager, not about anything in a clone.")
            }
        )

        if (violations.isNotEmpty()) {
            throw GradleException(
                "An unsigned plugin installs behind a warning, on the one screen where a plugin " +
                    "selling trust is being decided:\n" +
                    violations.joinToString("\n") { "  $it" }
            )
        }
    }
}

// The edge that matters, and the reason this is a task rather than a habit: `publishPlugin` reads
// `signPlugin.didWork` and quietly uploads the unsigned archive when it is false.
tasks.named("publishPlugin") {
    dependsOn(assertThePluginWasSigned)
}


/**
 * Fails if `demo/` is a Gradle subproject, or if anything from it reaches the distribution.
 *
 * **`demo/` is loose source, and that is a constraint rather than laziness.** The build is fixed at
 * `:core` + `:plugin` and the release asserts that the shipped runtime classpath holds `:core` and
 * nothing else; a third subproject perturbs both. So the sample the Marketplace screenshots are shot
 * from is a directory of `.java` files opened as its own IDEA project, and never on the
 * distribution's path.
 *
 * Two layers, because the two ways it could stop being true are unrelated. `settings.gradle.kts`
 * says whether Gradle knows about it. The zip says whether anything from it is being uploaded — and
 * the second is checked against the demo's own root package, read out of the demo sources rather
 * than written down here, so that renaming the sample cannot leave the rule guarding a name nothing
 * uses any more.
 */
val assertTheDemoIsNotShipped = tasks.register("assertTheDemoIsNotShipped") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Fails if demo/ is a Gradle subproject or appears in the built distribution."

    val distribution = tasks.named<Zip>("buildPlugin").flatMap { it.archiveFile }
    val settings = layout.settingsDirectory.file("settings.gradle.kts")
    val demo = layout.settingsDirectory.dir("demo")
    val demoSources = fileTree(demo) { include("**/*.java") }
    val demoBuildFiles = fileTree(demo) {
        include("**/build.gradle", "**/build.gradle.kts", "**/settings.gradle", "**/settings.gradle.kts", "**/pom.xml")
    }
    val report = layout.buildDirectory.file("reports/trust/demo-is-not-shipped.txt")
    val demoName = demo.asFile.name

    inputs.file(distribution).withPropertyName("distribution")
    inputs.file(settings).withPropertyName("settings")
    inputs.files(demoSources).withPropertyName("demoSources")
    inputs.files(demoBuildFiles).withPropertyName("demoBuildFiles")
    outputs.file(report).withPropertyName("report")

    doLast {
        val sources = demoSources.files.sortedBy { it.path }

        // A rule that passes because the thing it guards is missing is not a pass: the screenshots
        // are reproducible only while the project they were shot from is here.
        check(sources.isNotEmpty()) {
            "There are no Java sources under ${demo.asFile}. The screenshots have nothing to be shot from."
        }

        val violations = mutableListOf<String>()

        demoBuildFiles.files.sortedBy { it.path }.forEach {
            violations += "${it.name} is in $demoName/, which makes it a build rather than loose source"
        }

        // `include(":core", ":plugin")` and nothing else. Read as text rather than off the project
        // model, so that the rule states what settings.gradle.kts says rather than what Gradle made
        // of it — the file is the thing a reader checks.
        val includes = Regex("""(?m)^\s*include\s*\(.*$""").findAll(settings.asFile.readText()).map { it.value }.toList()
        check(includes.isNotEmpty()) {
            "No `include(` line was read out of ${settings.asFile}. The subproject rule is checking nothing."
        }
        fun includesNaming(lines: List<String>) = lines.filter { demoName in it }

        check(includesNaming(listOf("""include(":core", ":plugin", ":$demoName")""")).isNotEmpty()) {
            "The rule failed to flag an include line naming the sample. Nothing is being guarded."
        }
        check(includesNaming(listOf("""include(":core", ":plugin")""")).isEmpty()) {
            "The rule flagged the include line this build actually has. It is matching everything."
        }

        includesNaming(includes).forEach {
            violations += "settings.gradle.kts includes the sample as a subproject: ${it.trim()}"
        }

        /**
         * The demo's own root package, taken from its sources.
         *
         * Two segments — `com.harborlight` rather than `com` — because the first alone is every Java
         * package in the distribution, and the pair is the part that is the sample's.
         */
        val rootPackages = sources.mapNotNull {
            Regex("""(?m)^package\s+([\w.]+)\s*;""").find(it.readText())?.groupValues?.get(1)
        }.map { it.split(".").take(2).joinToString(".") }.distinct()

        check(rootPackages.size == 1) {
            "The sample declares ${rootPackages.size} root packages ($rootPackages). This rule needs one to look for."
        }
        val rootPackage = rootPackages.single()

        // Every path in the zip, at both levels: the distribution's own entries and the entries of
        // the jars inside it. A `.java` file added to the plugin's resources by accident lands in the
        // second, which is the level a top-level listing cannot see.
        val paths = mutableListOf<String>()
        ZipFile(distribution.get().asFile).use { zip ->
            zip.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
                paths += entry.name
                if (entry.name.endsWith(".jar")) {
                    ZipInputStream(zip.getInputStream(entry)).use { jar ->
                        generateSequence { jar.nextEntry }
                            .filterNot { it.isDirectory }
                            .forEach { paths += "${entry.name}!/${it.name}" }
                    }
                }
            }
        }

        check(paths.isNotEmpty()) { "The distribution is empty. Nothing was checked." }

        val packagePath = rootPackage.replace('.', '/')

        fun sampleIn(entries: List<String>) =
            entries.filter { "/$demoName/" in it || it.startsWith("$demoName/") || packagePath in it }

        // The rule is shown to flag the sample at both levels of the zip, and to leave the plugin's
        // own classes alone — a path rule that matched everything would report a violation nobody
        // could act on, and one that matched nothing would be a permanent green.
        check(sampleIn(listOf("SnippetVeil/lib/plugin.jar!/$packagePath/billing/Invoice.class")).size == 1) {
            "The rule did not flag the sample's own package inside a jar, which is where it would land."
        }
        check(sampleIn(listOf("SnippetVeil/$demoName/Invoice.java", "$demoName/Invoice.java")).size == 2) {
            "The rule did not flag a $demoName/ path in the distribution."
        }
        check(sampleIn(listOf("SnippetVeil/lib/plugin.jar!/com/snippetveil/plugin/CopyAnonymizedAction.class")).isEmpty()) {
            "The rule flagged the plugin's own class. It is matching more than the sample."
        }

        sampleIn(paths).forEach { violations += "$it is in the distribution, and it is the sample" }

        report.get().asFile.also { it.parentFile.mkdirs() }.writeText(
            buildString {
                appendLine("$demoName/ — ${sources.size} Java sources, root package $rootPackage")
                appendLine("Not a Gradle subproject: ${includes.joinToString("; ") { it.trim() }}")
                appendLine("Not in the distribution: ${paths.size} entries read, at both levels of the zip.")
            }
        )

        if (violations.isNotEmpty()) {
            throw GradleException(
                "$demoName/ is loose source, opened as its own IDEA project, and never on the " +
                    "distribution's path:\n" + violations.joinToString("\n") { "  $it" }
            )
        }
    }
}

/**
 * Fails if either plugin icon is missing from the distribution, or is the wrong sort of image.
 *
 * **`pluginIcon_dark.svg` is mandatory here, and the platform documentation calling it optional is
 * the reason to say so.** The mark is grey bars with two of them recoloured — the substitution drawn
 * literally — so at 40 px *the distinction is the accent contrast*. One light-tuned file on a dark
 * IDE collapses into a generic "lines of code" glyph, which is the most crowded shape on the
 * Marketplace. That is what the two-file rule and the differing-accents rule below are for.
 *
 * The geometry the icons are drawn to, since the failure message sends people here: a 40 × 40
 * viewBox with 2 px of transparent padding, all content inside the middle 36 × 36. Light is
 * `#6C707E` with a `#7B61FF` accent; dark is `#9DA0A8` with a `#9B84FF` accent.
 *
 * Neither may carry text. Best practice forbids a logo that merely repeats the plugin name, and a
 * `<text>` element is the mechanical half of that rule — the half a check can decide.
 */
val assertBothPluginIconsShip = tasks.register("assertBothPluginIconsShip") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Fails if either plugin icon is missing from the distribution or is a wordmark."

    val distribution = tasks.named<Zip>("buildPlugin").flatMap { it.archiveFile }
    val report = layout.buildDirectory.file("reports/trust/plugin-icons.txt")
    val required = listOf("META-INF/pluginIcon.svg", "META-INF/pluginIcon_dark.svg")
    val libraryJar = libraryJarEntry

    inputs.file(distribution).withPropertyName("distribution")
    inputs.property("required", required)
    outputs.file(report).withPropertyName("report")

    doLast {
        val icons = mutableMapOf<String, String>()
        val shipped = mutableListOf<String>()
        ZipFile(distribution.get().asFile).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory && libraryJar.matches(it.name) }
                .forEach { entry ->
                    ZipInputStream(zip.getInputStream(entry)).use { jar ->
                        generateSequence { jar.nextEntry }
                            .filterNot { it.isDirectory }
                            .forEach { nested ->
                                shipped += "${entry.name}!/${nested.name}"
                                if (nested.name in required) icons[nested.name] = jar.readBytes().decodeToString()
                            }
                    }
                }
        }

        check(shipped.isNotEmpty()) { "The distribution's jars are empty. Nothing was checked." }

        /** Everything wrong with one icon. */
        fun problemsWith(name: String, svg: String): List<String> = buildList {
            if (!Regex("""viewBox\s*=\s*"0 0 40 40"""").containsMatchIn(svg)) {
                add("$name is not drawn on a 40x40 viewBox")
            }
            if (Regex("""<text\b""").containsMatchIn(svg)) {
                add("$name carries text, and a logo may not merely repeat the plugin name")
            }
            if (Regex("""<image\b""").containsMatchIn(svg)) {
                add("$name embeds a raster, which does not scale to the sizes the IDE asks for")
            }
        }

        fun coloursIn(svg: String) = Regex("""#[0-9A-Fa-f]{6}""").findAll(svg).map { it.value.uppercase() }.toSet()

        // Every rule is run against something it must flag, and against something it must not,
        // before any of them is pointed at the real mark. Four rules that have never gone red are
        // four rules a typo turns into a permanent pass.
        val fixture = """<svg viewBox="0 0 40 40"><rect fill="#6C707E" x="2" y="2" width="8" height="4"/></svg>"""

        check(problemsWith("fixture", fixture).isEmpty()) {
            "The rules flagged a well-formed icon: ${problemsWith("fixture", fixture)}"
        }
        check(problemsWith("fixture", fixture.replace("0 0 40 40", "0 0 32 32")).size == 1) {
            "The viewBox rule did not flag a 32x32 icon, so the size is not being checked."
        }
        check(problemsWith("fixture", fixture.replace("<rect", "<text")).size == 1) {
            "The wordmark rule did not flag a <text> element. Nothing stops the mark becoming a wordmark."
        }
        check(problemsWith("fixture", fixture.replace("<rect", "<image")).size == 1) {
            "The raster rule did not flag an <image> element."
        }
        check(
            Regex("""\.(png|jpg|jpeg|gif|webp|bmp|svg|ico)$""", RegexOption.IGNORE_CASE)
                .containsMatchIn("a/b/Screenshot 2026-08-23.PNG")
        ) {
            "The picture rule does not match a capitalised extension, which is what a screenshot has."
        }
        check(coloursIn("""a #6c707E b #7B61FF""") == setOf("#6C707E", "#7B61FF")) {
            "The colour reader is case-sensitive or is not reading hex colours at all, so two icons " +
                "spelling one colour differently would read as contrasting."
        }

        val violations = mutableListOf<String>()

        required.filterNot { it in icons }.forEach {
            violations += "$it is not in the distribution"
        }

        icons.forEach { (name, svg) -> violations += problemsWith(name, svg) }

        // **The mark is the only picture that ships, and this is what says so.** The near miss it
        // guards is an ordinary one: the Marketplace screenshots are 1280x800 PNGs that have to live
        // somewhere while they are being cut, and `plugin/src/main/resources/` is a directory a
        // screenshot lands in without anybody deciding it should — from there it is in the jar, in
        // the distribution, and on every user's disk, and nothing else here would have noticed.
        //
        // A picture this project genuinely wants to ship goes in `required` above, deliberately.
        val pictures = Regex("""\.(png|jpg|jpeg|gif|webp|bmp|svg|ico)$""", RegexOption.IGNORE_CASE)
        shipped.filter { pictures.containsMatchIn(it) && it.substringAfter("!/") !in required }
            .forEach { violations += "$it is an image in the distribution, and it is not the mark" }

        if (icons.size == required.size) {
            val (light, dark) = required.map { icons.getValue(it) }

            // A dark variant that is a copy of the light one is the failure the two-file rule exists
            // to prevent, and it is the one a file listing cannot see.
            if (light == dark) {
                violations += "pluginIcon_dark.svg is byte-identical to pluginIcon.svg, so the dark IDE gets the light mark"
            }

            val shared = coloursIn(light) intersect coloursIn(dark)
            if (shared.isNotEmpty()) {
                violations += "the two icons share the colours $shared; at 40 px the distinction is the contrast"
            }
        }

        report.get().asFile.also { it.parentFile.mkdirs() }.writeText(
            buildString {
                appendLine("Icons in the distribution: ${icons.keys.sorted().joinToString(", ")}")
                appendLine("Files read: ${shipped.size}, of which images: ${shipped.count { pictures.containsMatchIn(it) }}")
                icons.toSortedMap().forEach { (name, svg) ->
                    val colours = Regex("""#[0-9A-Fa-f]{6}""").findAll(svg).map { it.value }.distinct().toList()
                    appendLine("  $name — ${svg.toByteArray().size} bytes, colours ${colours.joinToString(", ")}")
                }
            }
        )

        if (violations.isNotEmpty()) {
            throw GradleException(
                "The Marketplace mark is a 40x40 SVG pair, and the dark variant is mandatory rather " +
                    "than optional:\n" + violations.joinToString("\n") { "  $it" }
            )
        }
    }
}

tasks.named("check") {
    dependsOn(assertTheDemoIsNotShipped)
    dependsOn(assertBothPluginIconsShip)
}

/**
 * Fails if a roadmap appears in the README, the listing copy, or the change notes.
 *
 * **A live commit history is evidence of maintenance; a roadmap is a promise about it.** The
 * discipline is never to make a claim a counterexample can kill, and a solo hobby v1 that misses a
 * published roadmap item inflicts precisely that wound on a product whose entire position is trust.
 *
 * Status is not a roadmap and is not touched here: *"the plugin is not yet published"* is a fact
 * about today, and *"work in progress is tracked in this repository's issues"* points at a list that
 * is already public and already qualified. What is banned is the sentence that commits.
 *
 * **The change notes are read out of the built distribution, not out of `plugin.xml`**, and that is
 * the whole reason this task lives in `:plugin` rather than beside the other repository-wide rules.
 * The descriptor is patched at build time — the description above is set from README.md in Gradle
 * and appears nowhere in the checked-in file — so a rule that read the source descriptor would be
 * blind to change notes written the same way, which is the way this build has just established.
 *
 * There are no change notes yet, in either place. The rule is not vacuous meanwhile, because the
 * README always exists; CHANGELOG.md is declared as an input so that adding one invalidates this
 * task rather than leaving it `UP-TO-DATE` over a file it has never read.
 */
val assertNoRoadmapIsPublished = tasks.register("assertNoRoadmapIsPublished") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Fails if the README, the listing copy or the change notes promise future work."

    val distribution = tasks.named<Zip>("buildPlugin").flatMap { it.archiveFile }
    val readme = layout.settingsDirectory.file("README.md")
    val changelog = layout.settingsDirectory.file("CHANGELOG.md")
    val report = layout.buildDirectory.file("reports/trust/no-roadmap.txt")
    val libraryJar = libraryJarEntry

    val promises = listOf(
        "roadmap",
        "coming soon",
        "in a future release",
        "in a future version",
        "in an upcoming release",
        "we plan to",
        "we intend to",
        "will be added",
        "will be supported",
        "is planned",
        "are planned",
    )

    inputs.file(distribution).withPropertyName("distribution")
    inputs.file(readme).withPropertyName("readme")

    // `files` rather than `file`: CHANGELOG.md does not exist, and an input that refused to be
    // declared until it did would be an input nobody remembers to declare on the day it arrives.
    inputs.files(changelog).withPropertyName("changelog")
    inputs.property("promises", promises)
    outputs.file(report).withPropertyName("report")

    doLast {
        // Word by word and rejoined on `\s+`, for the reason the phrase ban is: a phrase escaped
        // whole quotes its own separator and then matches only where the prose happens not to wrap.
        fun promisesIn(text: String): List<String> = promises.filter { promise ->
            val pattern = promise.split(" ").joinToString("""\s+""") { Regex.escape(it) }
            Regex("""\b$pattern\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)
        }

        check(promisesIn("Java support is planned for the next release.").isNotEmpty()) {
            "The rule failed to flag a promise about future work. Nothing is being guarded."
        }
        check(promisesIn("A roadmap\nis a promise").isNotEmpty()) {
            "The rule missed a phrase across a line wrap, which is how prose is written."
        }
        check(promisesIn("The plugin is not yet published to the JetBrains Marketplace.").isEmpty()) {
            "The rule flagged a statement of status. Saying what today is must stay legal."
        }

        // The descriptor as it ships, for the change notes. Same walk as the two rules above; the
        // entry pattern is the one `libraryJarEntry` spells, so the three cannot drift apart on the
        // one thing that would silently narrow all of them.
        val descriptors = mutableMapOf<String, String>()
        ZipFile(distribution.get().asFile).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory && libraryJar.matches(it.name) }
                .forEach { entry ->
                    ZipInputStream(zip.getInputStream(entry)).use { jar ->
                        generateSequence { jar.nextEntry }
                            .filter { it.name == "META-INF/plugin.xml" }
                            .forEach { descriptors["${entry.name}!/${it.name}"] = jar.readBytes().decodeToString() }
                    }
                }
        }
        check(descriptors.size == 1) {
            "The distribution has ${descriptors.size} plugin descriptors: ${descriptors.keys}."
        }
        val (where, descriptor) = descriptors.entries.single()

        val changeNotes = Regex("""<change-notes>(.*?)</change-notes>""", RegexOption.DOT_MATCHES_ALL)
            .find(descriptor)?.groupValues?.get(1)

        // The listing copy is the block inside README.md, so reading the README covers both.
        val surfaces = buildMap {
            put("README.md", readme.asFile.readText())
            if (changelog.asFile.exists()) put("CHANGELOG.md", changelog.asFile.readText())
            if (changeNotes != null) put("$where <change-notes>", changeNotes)
        }

        val violations = surfaces.flatMap { (name, text) ->
            promisesIn(text).map { "$name promises \"$it\"" }
        }

        report.get().asFile.also { it.parentFile.mkdirs() }.writeText(
            buildString {
                appendLine("Surfaces read: ${surfaces.keys.joinToString(", ")}")
                appendLine("None of them may promise: ${promises.joinToString(", ")}")
                appendLine()
                appendLine("The listing copy is the block inside README.md, so reading the README covers both.")
                if (changeNotes == null) {
                    appendLine("The shipped descriptor carries no change notes yet; this reads them the day it does.")
                }
            }
        )

        if (violations.isNotEmpty()) {
            throw GradleException(
                "A live commit history is evidence of maintenance; a roadmap is a promise about it, " +
                    "and a missed one wounds the only thing this product sells:\n" +
                    violations.joinToString("\n") { "  $it" }
            )
        }
    }
}

tasks.named("check") {
    dependsOn(assertNoRoadmapIsPublished)
}

// ---------------------------------------------------------------------------------------------
// The corpus sweep
//
// A local instrument, not a test and not a merge gate: it runs the anonymizer whole-file over a
// real codebase and writes a triage list of suspected leaks. The oracle behind it is deliberately
// blunt and false-positive-prone, so it can be neither green nor red — a human runs it, reads it,
// and what comes out is a conclusion. See `com.snippetveil.sweep.CorpusSweep` and CONTRIBUTING.md.
//
// It is registered through `intellijPlatformTesting.testIde` rather than as a plain `Test` task
// because a plain one gets none of the platform wiring: the IntelliJ Platform Gradle plugin
// configures `test` by name and every `TestIdeTask` by type, and nothing else.
// ---------------------------------------------------------------------------------------------

/**
 * **The sweep's test class, spelled once.**
 *
 * Two filters read it and they mean opposite things: `corpusSweep` includes it, and `test` excludes
 * it so that the merge gate never runs it. Two string literals would let a rename break the second
 * silently — the sweep would quietly rejoin `check` — which is the same argument the root build
 * makes for sharing the task name, and it is `assertTheSweepIsExcludedFromTheMergeGate` below that
 * stops this string from going stale.
 */
val corpusSweepClass = "com.snippetveil.sweep.CorpusSweep"

/** The target codebase. **Absent means skipped**, so public CI cannot demand it. */
val sweepProject = providers.gradleProperty("sweepProject")

/** Where the report goes. Defaulted by the sweep itself, and refused if it lands in either tree. */
val sweepReportDirectory = providers.gradleProperty("sweepReportDir")

intellijPlatformTesting {
    // The name comes from the root build, which is where `assertTheSweepIsNeverRunInCi` guards it.
    // One spelling, so that a rename cannot leave that check guarding a task nobody registers.
    testIde.register(rootProject.extra["corpusSweepTask"] as String) {
        // Declared again rather than inherited: test-framework dependencies are added to the `test`
        // task's own configuration, and a custom test task gets its own.
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)

        task {
            // Read out of the script here, so that the specs below close over plain values. A spec
            // that reaches back to a script-level property carries a reference to the build script
            // itself, which the configuration cache cannot serialize.
            val target = sweepProject.orNull
            val reportDirectory = sweepReportDirectory.orNull
            val repository = rootProject.projectDir.absolutePath

            group = LifecycleBasePlugin.VERIFICATION_GROUP
            description = "Runs the anonymizer over a real codebase named by -PsweepProject and writes a triage list."

            // Appended rather than assigned: the classpath the platform plugin built puts the IDE
            // and this plugin's own jars in a deliberate order, and this adds the test-scope
            // libraries — the test engines — that a custom test task is not given.
            testClassesDirs += sourceSets["test"].output.classesDirs
            classpath += sourceSets["test"].runtimeClasspath
            useJUnitPlatform()
            filter { includeTestsMatching(corpusSweepClass) }

            // Where the report may not go, handed in rather than guessed at by the process.
            systemProperty("snippetveil.sweep.repository", repository)
            target?.let { systemProperty("snippetveil.sweep.project", it) }
            reportDirectory?.let { systemProperty("snippetveil.sweep.reportDirectory", it) }

            // **Skipped, not failed**, when there is no codebase to point it at — so a contributor
            // without one is never blocked and public CI cannot demand it. The test class assumes
            // the same thing, for the case where somebody runs it straight from the IDE.
            onlyIf("-PsweepProject names the codebase to sweep; without it there is nothing to run") {
                target != null
            }

            // **The second layer of "never in CI".** The first says CI never asks — the workflows
            // are read by `assertTheSweepIsNeverRunInCi` in the root build. This one says the sweep
            // would refuse if asked, which is what covers the routes that check cannot see: a
            // `dependsOn` somebody adds, or a shell script on a runner that is not GitHub's.
            //
            // It sits behind the `onlyIf` above, so a mis-wiring that reached CI *without* a target
            // still merely skips. That is the harmless half; this catches the half that is not —
            // a real codebase actually being swept onto a machine nobody chose.
            doFirst {
                val ci = listOf("CI", "GITHUB_ACTIONS", "BUILD_NUMBER").filter { System.getenv(it) != null }
                check(ci.isEmpty()) {
                    "The corpus sweep reads a real proprietary codebase and writes the real " +
                        "identifiers it found surviving. It is run by a human, deliberately, on a " +
                        "machine that already holds that code — and $ci says this is CI."
                }
            }

            // An instrument is run to be read. A cached "up-to-date" would print a path to
            // yesterday's report and look like it had just swept.
            outputs.upToDateWhen(Specs.satisfyNone())
            testLogging { showStandardStreams = true }

            // A real codebase is tens of thousands of files' worth of PSI and index.
            maxHeapSize = "4g"
        }
    }
}

tasks.test {
    // **The sweep is not part of the merge gate**, and this is the line that says so: it lives in
    // the test source set, so without an exclusion `check` would run it — where it would skip, and
    // teach everyone reading the build that it is a test that happens to be skipped. It is not a
    // test. See `assertTheSweepIsNeverRunInCi` in the root build for the other half.
    filter { excludeTestsMatching(corpusSweepClass) }
}

/**
 * Fails if the class both filters above name is not the class that is actually there.
 *
 * **The exclusion above is the one rule in this build that goes quiet rather than red when it stops
 * being true.** A filter that matches nothing excludes nothing, and Gradle says so about neither —
 * so a renamed or moved sweep class would rejoin `check` with every build still green, which is the
 * failure mode every other check here is written against.
 *
 * Sharing one `val` between the two filters is half the answer: it keeps them agreeing with each
 * other. This is the other half, and it is the half that keeps them agreeing with the source.
 */
val assertTheSweepIsExcludedFromTheMergeGate = tasks.register("assertTheSweepIsExcludedFromTheMergeGate") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Fails if the corpus sweep class the test filters name is not the one in the source tree."

    // Read out of the script here, so that the action below closes over plain values: an action
    // that reached back to a script-level property would carry a reference to the build script
    // itself, which the configuration cache cannot serialize.
    val named = corpusSweepClass
    val simpleName = named.substringAfterLast('.')
    val source = layout.projectDirectory.file("src/test/kotlin/${named.replace('.', '/')}.kt")
    val declaration = Regex("""(?m)^\s*class\s+$simpleName\b""")

    inputs.file(source).withPropertyName("source")
    inputs.property("corpusSweepClass", named)

    doLast {
        val file = source.asFile
        check(file.isFile) {
            "`$named` is what `test` excludes and `corpusSweep` includes, but $file does not exist. " +
                "A filter that matches nothing excludes nothing, so the sweep would be back in the " +
                "merge gate with the build still green."
        }
        check(declaration.containsMatchIn(file.readText())) {
            "$file exists but declares no `class $simpleName`, so the filters naming `$named` match " +
                "nothing and the sweep is back in the merge gate."
        }
    }
}

tasks.named("check") {
    dependsOn(assertTheSweepIsExcludedFromTheMergeGate)
}
