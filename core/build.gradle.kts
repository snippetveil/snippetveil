plugins {
    id("org.jetbrains.kotlin.jvm")
}

// :core has ZERO runtime dependencies. The Kotlin stdlib is compileOnly here and is provided at
// runtime by the IntelliJ Platform, which bundles it — so core.jar ships depending on nothing.
dependencies {
    compileOnly(kotlin("stdlib"))

    testImplementation(kotlin("stdlib"))
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Property-based testing, for the one class of failure that cannot appear in a single-invocation
    // test: a second paste contradicting the first. See LedgerHistoryTest.
    //
    // **Licence checked rather than assumed**, against the allow-list in CONTRIBUTING.md: jqwik is
    // EPL-2.0, which that list allows as an unmodified dependency, and this is one — no fork, no
    // patch. It is also test scope, so it is not on the shipped runtime classpath and carries no
    // outbound obligation at all; `assertNothingThirdPartyIsShipped` holds that to be true rather
    // than taking it on trust.
    testImplementation("net.jqwik:jqwik:1.10.1")
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
// The corpus instrument's plan half
//
// A local instrument, not a test and not a merge gate: it runs the plan parser and the anonymizer
// over a corpus of real captures and writes the two zeros, three numbers that gate nothing, and a
// triage list. See `com.snippetveil.sweep.PlanSweep` and CONTRIBUTING.md.
//
// It lives in `:core` rather than in `:plugin`, where the other two halves are, for the reason the
// parse itself lives here: **nothing in it needs an IDE.** It is a string in and a verdict out, so
// it is a plain `Test` task with no platform behind it — which is also why it is seconds rather
// than minutes.
//
// **It carries no CI refusal of its own, and that is stated rather than forgotten.** The other two
// halves refuse to run where `CI` is set, because each opens somebody's real code and writes real
// identifiers onto a machine nobody chose. These captures are agent-generated in a container
// against throwaway schemas: there is no such hazard, and a guard copied across without its
// argument is a guard nobody can maintain. What keeps a corpus-scale run out of CI is that the run
// is **skipped, not failed** without a corpus, and that `assertTheSweepIsNeverRunInCi` in the root
// build reads every `./gradlew` line in `.github/workflows/`. The reason that rule covers this half
// is not secrecy either: a CI cell over the whole corpus would produce a number nobody reads
// attached to a list nobody triages.
// ---------------------------------------------------------------------------------------------

/**
 * **The plan half's test class, spelled once**, for the reason `:plugin` spells its two once:
 * `planSweep` includes it and `test` excludes it, and two literals would let a rename break the
 * second silently — the instrument would quietly rejoin `check`.
 */
val planSweepClass = "com.snippetveil.sweep.PlanSweep"

/** The corpora, by directory. **Absent means skipped**, so public CI cannot demand it. */
val planSweepCorpus = providers.gradleProperty("planSweepCorpus")

/** Where the report goes. Defaulted by the sweep itself, and refused if it lands in any swept tree. */
val planSweepReportDirectory = providers.gradleProperty("planSweepReportDir")

val planSweep = tasks.register<Test>(rootProject.extra["planSweepTask"] as String) {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Runs the plan parser over the corpora named by -PplanSweepCorpus and writes a triage list."

    // Read out of the script here, so that the specs below close over plain values. A spec that
    // reached back to a script-level property would carry a reference to the build script itself,
    // which the configuration cache cannot serialize.
    val corpus = planSweepCorpus.orNull
    val reportDirectory = planSweepReportDirectory.orNull
    val repository = rootProject.projectDir.absolutePath

    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching(planSweepClass) }

    // Where neither the corpus nor the report may be, handed in rather than guessed at by the
    // process. The corpus is checked against it too: what goes in stays outside this tree as surely
    // as what comes out — not for secrecy, but so that a regeneration that differs is a finding
    // about format drift rather than a new baseline.
    systemProperty("snippetveil.sweep.repository", repository)
    corpus?.let { systemProperty("snippetveil.plan.sweep.corpus", it) }
    reportDirectory?.let { systemProperty("snippetveil.plan.sweep.reportDirectory", it) }

    // **Skipped, not failed**, when there is no corpus to point it at — so a contributor without one
    // is never blocked and public CI cannot demand it. The test class assumes the same thing, for
    // the case where somebody runs it straight from the IDE.
    onlyIf("-PplanSweepCorpus names the corpora to sweep; without it there is nothing to run") {
        corpus != null
    }

    // An instrument is run to be read. A cached "up-to-date" would print a path to yesterday's
    // report and look like it had just swept.
    outputs.upToDateWhen(Specs.satisfyNone())
    testLogging { showStandardStreams = true }
}

tasks.test {
    useJUnitPlatform()

    // **The plan half is not part of the merge gate**, and this is the line that says so: it lives
    // in the test source set, so without an exclusion `check` would run it — where it would skip,
    // and teach everyone reading the build that it is a test that happens to be skipped. It is not
    // a test. What *does* run on every pull request is `PlanZerosTest`, over the committed subset.
    filter { excludeTestsMatching(planSweepClass) }
}

/**
 * Fails if the class both filters above name is not the class that is actually there.
 *
 * **The exclusion above is the one rule in this build that goes quiet rather than red when it stops
 * being true.** A filter that matches nothing excludes nothing, and Gradle says so about neither —
 * so a renamed or moved instrument class would rejoin `check` with every build still green. Sharing
 * one `val` between the two filters keeps them agreeing with each other; this keeps them agreeing
 * with the source tree. It is the same rule `assertTheSweepIsExcludedFromTheMergeGate` holds over
 * the two halves in `:plugin`.
 */
val assertThePlanSweepIsExcludedFromTheMergeGate =
    tasks.register("assertThePlanSweepIsExcludedFromTheMergeGate") {
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        description = "Fails if the plan instrument class the test filters name is not the one in the source tree."

        val source = layout.projectDirectory.file("src/test/kotlin/${planSweepClass.replace('.', '/')}.kt")
        val simpleName = planSweepClass.substringAfterLast('.')
        val named = planSweepClass

        inputs.file(source).withPropertyName("source")
        inputs.property("instrumentClass", named)

        doLast {
            val file = source.asFile
            check(file.isFile) {
                "`$named` is what `test` excludes and `planSweep` includes, but $file does not exist. " +
                    "A filter that matches nothing excludes nothing, so the instrument would be back " +
                    "in the merge gate with the build still green."
            }
            check(Regex("""(?m)^\s*class\s+$simpleName\b""").containsMatchIn(file.readText())) {
                "$file exists but declares no `class $simpleName`, so the filters naming `$named` match " +
                    "nothing and the instrument is back in the merge gate."
            }
        }
    }

tasks.named("check") {
    dependsOn(assertThePlanSweepIsExcludedFromTheMergeGate)
}
