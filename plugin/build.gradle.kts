import org.gradle.api.artifacts.component.ProjectComponentIdentifier
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
}

dependencies {
    // A plain project dependency, not a plugin module: core.jar stays a separate jar in the
    // distribution's lib/, which is the directory `scanDistributionForNetworkReferences` walks.
    implementation(project(":core"))

    intellijPlatform {
        // Compiled against the compatibility floor itself, so an API newer than 2024.1 cannot be
        // used by accident. Raising the floor later is free; lowering it is unverified work.
        intellijIdeaCommunity("2024.1.7")
        bundledPlugin("com.intellij.java")

        pluginVerifier()
    }

    // Test-scope only, and it stays that way: `assertNothingThirdPartyIsShipped` below fails the
    // build the moment anything reaches the shipped runtime classpath.
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

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

intellijPlatform {
    // The distribution is SnippetVeil, not "plugin" — the subproject name must not name the product.
    projectName = "SnippetVeil"

    pluginConfiguration {
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

/**
 * Walks every class in the built distribution's `lib/` and fails on a constant-pool reference to a
 * networking class.
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
val scanDistributionForNetworkReferences by tasks.registering {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Fails if any class in the built distribution references a networking class."

    val distribution = tasks.named<Zip>("buildPlugin").flatMap { it.archiveFile }
    val report = layout.buildDirectory.file("reports/trust/no-network-scan.txt")

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
        val networkingClass = Regex(
            """(java\.net|javax\.net|java\.rmi|sun\.net|jdk\.internal\.net)\.[\w.$]+""" +
                """|java\.nio\.channels\.[\w.$]*(Socket|Datagram|Network)Channel[\w$]*"""
        )

        fun networkingClassesIn(constant: String): List<String> =
            networkingClass.findAll(constant.replace('/', '.')).map { it.value }.distinct().toList()

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

        fun networkReferencesIn(classBytes: ByteArray): Set<String> {
            val reader = ClassReader(classBytes)
            val found = sortedSetOf<String>()
            for (index in 1 until reader.itemCount) {
                val offset = reader.getItem(index)
                if (offset == 0) continue // The unused second slot of a long or double constant.
                if (reader.readByte(offset - 1) != CONSTANT_UTF8) continue
                val length = reader.readUnsignedShort(offset)
                val constant = buildString(length) {
                    for (position in 0 until length) append(Char(reader.readByte(offset + 2 + position)))
                }
                found += networkingClassesIn(constant)
            }
            return found
        }

        // The scan proves it can fail before it reports that nothing failed. A check whose red path
        // is never exercised decays into a check that always passes — and the whole point of these
        // tasks is that the no-network claim is demonstrated rather than trusted. `java.net.URL` and
        // `java.lang.Object` are read from the running JDK's own image: class files are never
        // encapsulated by the module system, so both are always available.
        fun readJdkClass(name: String): ByteArray =
            checkNotNull(Any::class.java.getResourceAsStream("/$name.class")) { "$name is missing from this JDK" }
                .use { it.readBytes() }

        check(networkReferencesIn(readJdkClass("java/net/URL")).isNotEmpty()) {
            "The scanner failed to flag java.net.URL. It cannot be trusted to flag anything."
        }
        check(networkReferencesIn(readJdkClass("java/lang/Object")).isEmpty()) {
            "The scanner flagged java.lang.Object. It is matching more than networking."
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
                                        networkReferencesIn(jar.readBytes())
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
                appendLine("  ${networkingClass.pattern}")
                appendLine()
                scanned.forEach { appendLine(it) }
            }
        )

        if (violations.isNotEmpty()) {
            throw GradleException(
                "SnippetVeil makes no network calls, but the built distribution says otherwise:\n" +
                    violations.joinToString("\n") { "  $it" }
            )
        }
    }
}

tasks.named<Zip>("buildPlugin") {
    finalizedBy(scanDistributionForNetworkReferences)
}

// `publishPlugin` → `signPlugin` → `buildPlugin`, so the finalizer above is *ordered* before an
// upload but not *required* by it: Gradle promises only that a finalizer runs after the task it
// finalizes, not that it runs before that task's other dependents. A real gate needs the edge.
tasks.named("publishPlugin") {
    dependsOn(scanDistributionForNetworkReferences)
}

/**
 * Fails if anything but `:core` reaches the shipped runtime classpath.
 *
 * This is how the inbound licence policy is enforced: by asserting emptiness rather than by
 * classifying licences, which is strictly stronger while it holds. CONTRIBUTING.md carries the
 * argument and the policy itself.
 */
val assertNothingThirdPartyIsShipped by tasks.registering {
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
    dependsOn(scanDistributionForNetworkReferences)
}
