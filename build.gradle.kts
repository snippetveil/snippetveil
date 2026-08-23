plugins {
    // For the lifecycle `check` task the hardening check below hangs off, and nothing else. The
    // root project builds no code.
    base
}

/**
 * **The corpus sweep's task name, spelled once.**
 *
 * `assertTheSweepIsNeverRunInCi` below guards this string, and `plugin/build.gradle.kts` registers a
 * task under it. They read the same `val` rather than two string literals, because a rename that
 * touched only one of them would leave a check guarding a task that does not exist — and a check
 * that cannot fail is worse than no check, since it reads as a guarantee.
 */
val corpusSweepTask by extra("corpusSweep")

/**
 * Fails if a GitHub Actions workflow uses an action it has not pinned to a commit SHA, pins one
 * without naming the version the SHA is, or does not say what token it runs with.
 *
 * This is a Gradle task for the same reason the trust checks are: **a check that exists only in
 * YAML cannot be run by the person reading the claim.** Two of the hardening rules in
 * `.github/workflows/` are otherwise nothing but a habit — and a habit is what a workflow added in
 * a hurry six months from now will not have. `./gradlew check` goes red on a tag-pinned action on
 * any machine, with no CI account and no permission from anyone.
 *
 * What it does not check is what the permissions actually *are*: "minimal" is a judgement about
 * what a job does, and a rule that guessed at it would be the kind of noise that teaches people to
 * suppress a check. Declaring them is the mechanical half, and it is the half that is checkable.
 */
val assertWorkflowsAreHardened by tasks.registering {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Fails if a workflow uses an unpinned action or declares no permissions."

    val workflows = layout.projectDirectory.dir(".github/workflows")
    val report = layout.buildDirectory.file("reports/trust/workflow-hardening.txt")

    inputs.dir(workflows).withPropertyName("workflows")
    outputs.file(report).withPropertyName("report")

    doLast {
        // A commit SHA, which is the only reference GitHub resolves to exactly one tree. A tag is
        // mutable and can be repointed by whoever controls the action's repository.
        val commitSha = Regex("""^[0-9a-f]{40}$""")

        // `- uses: owner/repo/path@ref # v1.2.3`, as a step or as a bare mapping key. Written
        // tightly on purpose: a permissive pattern would "read" a quoted or folded value and check
        // the wrong string, which is worse than not reading it, because `usesAnything` below turns
        // a line this cannot read into a violation. A local (`./…`) or Docker action would be
        // flagged too — extending the rules to one is a deliberate act, not a silent skip.
        //
        // The trailing comment is optional to *match* and required to *pass* — see below.
        val usesStep = Regex("""^\s*(?:-\s+)?uses:\s*([\w.-]+/[\w./-]+@[\w.-]+)(?:\s+#\s*(\S.*?))?\s*$""")

        // Anything that names an action at all. A line this matches and `usesStep` does not is a
        // `uses:` written in a shape the rules cannot read — a hole in the check rather than a line
        // to skip quietly, in the same way a file in `lib/` that the distribution scan cannot open
        // is a violation rather than a skip.
        val usesAnything = Regex("""^\s*(?:-\s+)?uses:.*""")

        // A workflow's own `permissions:`, at column 0. A job-level one is indented and does not
        // match, which is the point: a job that asks for more is fine, a file that asks for nothing
        // and inherits the repository default is not.
        val declaredPermissions = Regex("""(?m)^permissions:""")

        /**
         * Every hardening rule this project's workflows have to clear, over one file's text, and
         * the number of `uses:` lines the rules actually read — which is the check's own coverage
         * and is asserted below rather than assumed.
         */
        fun inspect(name: String, text: String): Pair<List<String>, Int> {
            val violations = mutableListOf<String>()
            var actionsRead = 0

            if (!declaredPermissions.containsMatchIn(text)) {
                violations += "$name declares no top-level `permissions:`, so its jobs run with " +
                    "whatever the repository happens to hand out"
            }

            text.lines().forEachIndexed { index, line ->
                if (!usesAnything.matches(line)) return@forEachIndexed
                val where = "$name:${index + 1}"

                val (reference, version) = usesStep.matchEntire(line)?.destructured ?: run {
                    violations += "$where names an action in a shape these rules cannot read, so " +
                        "it was not checked"
                    return@forEachIndexed
                }

                actionsRead++
                when {
                    !commitSha.matches(reference.substringAfterLast('@', "")) ->
                        violations += "$where uses $reference, which is a tag or a branch: whoever " +
                            "controls that repository can repoint it"

                    version.isBlank() ->
                        violations += "$where pins $reference but names no version, so the pin " +
                            "cannot be read by anyone deciding whether to trust it"
                }
            }

            return violations to actionsRead
        }

        fun violationsIn(name: String, text: String) = inspect(name, text).first

        // The rules prove they can fail before they report that nothing failed. A red path that is
        // never exercised decays into a check that always passes, which is the failure mode the
        // trust checks in plugin/build.gradle.kts are written against too.
        val hardened = """
            permissions:
              contents: read
            jobs:
              a:
                steps:
                  - uses: actions/checkout@0123456789abcdef0123456789abcdef01234567 # v7.0.1
        """.trimIndent()

        check(violationsIn("fixture", hardened).isEmpty()) {
            "The rules flagged a workflow that breaks none of them: ${violationsIn("fixture", hardened)}"
        }
        check(violationsIn("fixture", hardened.replace(Regex("""@[0-9a-f]{40}"""), "@v7")).size == 1) {
            "The rules failed to flag a tag-pinned action. Pinning is not being checked."
        }
        check(violationsIn("fixture", hardened.replace(" # v7.0.1", "")).size == 1) {
            "The rules failed to flag a SHA pin with no version comment."
        }
        check(violationsIn("fixture", hardened.substringAfter("contents: read\n")).size == 1) {
            "The rules failed to flag a workflow with no top-level permissions."
        }
        check(violationsIn("fixture", hardened.replace("uses: actions", "uses: \"actions")).size == 1) {
            "The rules skipped a `uses:` they could not parse instead of flagging it."
        }
        check(inspect("fixture", hardened).second == 1) {
            "The rules read no action out of a fixture that has one. They are not counting coverage."
        }

        val files = workflows.asFile.listFiles().orEmpty()
            .filter { it.isFile && (it.name.endsWith(".yml") || it.name.endsWith(".yaml")) }
            .sortedBy { it.name }

        // A check that found nothing to check is not a pass.
        check(files.isNotEmpty()) { "No workflows were found in ${workflows.asFile}. Nothing was checked." }

        val inspected = files.map { it to inspect(it.name, it.readText()) }
        val violations = inspected.flatMap { (_, result) -> result.first }
        val actionsRead = inspected.sumOf { (_, result) -> result.second }

        // A check that read no action is not a pass, however many files it opened. Every workflow
        // here uses at least one action, so a zero means the rules stopped matching the shape these
        // files are written in — the one failure a green build would otherwise hide completely.
        check(actionsRead > 0) {
            "No `uses:` was read out of ${files.size} workflow(s). The pin rule checked nothing."
        }

        report.get().asFile.also { it.parentFile.mkdirs() }.writeText(
            buildString {
                appendLine("Workflows checked: ${files.size}; actions read: $actionsRead")
                appendLine("Every `uses:` must be pinned to a commit SHA and name its version,")
                appendLine("and every workflow must declare its own top-level `permissions:`.")
                appendLine()
                inspected.forEach { (file, result) ->
                    appendLine(".github/workflows/${file.name} — ${result.second} action(s)")
                }
            }
        )

        if (violations.isNotEmpty()) {
            throw GradleException(
                "A repository whose pitch is \"audit me\" has no implicit write-all and no mutable " +
                    "action reference anywhere, but:\n" +
                    violations.joinToString("\n") { "  $it" }
            )
        }
    }
}

/**
 * Fails if any workflow asks Gradle to run the corpus sweep.
 *
 * **The sweep is never run in CI, and this is what says so rather than a convention.** It opens a
 * real proprietary codebase by path and writes a list of the real identifiers it found surviving —
 * so a CI run of it is either a no-op on a runner that has no such checkout, or a leak onto a
 * machine nobody chose. Neither is a thing to leave to habit.
 *
 * The rule is readable because of a rule the workflows already follow: **thin CI over thick
 * Gradle** — every check CI runs is a Gradle task, invoked from a `./gradlew` line. So *what CI
 * runs* is a list this task can extract and test, and a step that named the sweep would be caught
 * here on any machine, with no CI account and no permission from anyone.
 *
 * It is one of two layers. This one says *CI never asks*; the sweep task's own guard says *and it
 * would refuse if asked*, which is what covers a wiring nobody wrote into a workflow at all — a
 * `dependsOn` from `check`, or a shell script on some other runner.
 *
 * What it does **not** check is the ordinary Gradle task graph, and that is deliberate rather than
 * an omission: the configuration cache means a `whenReady` listener does not run on a cache hit, so
 * a graph assertion written here would be a check that quietly stops checking on the second run.
 */
val assertTheSweepIsNeverRunInCi by tasks.registering {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Fails if a workflow asks Gradle to run the corpus sweep."

    val workflows = layout.projectDirectory.dir(".github/workflows")
    val report = layout.buildDirectory.file("reports/trust/corpus-sweep-is-not-in-ci.txt")

    // Named here rather than spelled twice: see the `corpusSweepTask` extra above. The two property
    // names are belt and braces — the sweep cannot run without its task being named, so the task
    // name is the load-bearing half.
    val forbidden = listOf(corpusSweepTask, "sweepProject", "sweepReportDir")

    inputs.dir(workflows).withPropertyName("workflows")
    inputs.property("forbidden", forbidden)
    outputs.file(report).withPropertyName("report")

    doLast {
        // Every line that hands work to the Gradle wrapper — a `run:` step, or a line inside a
        // block one. The wrapper rather than the word "gradle", which appears in prose in both
        // files: a rule that read a comment would fail the build over a sentence, and noise is what
        // teaches people to suppress a check.
        val gradleInvocation = Regex("""^.*\./gradlew\b.*$""", RegexOption.MULTILINE)

        /**
         * Every Gradle invocation in one file's text that names something [forbidden], and the
         * number of invocations the rule read — which is this check's own coverage, asserted below
         * rather than assumed.
         */
        fun inspect(name: String, text: String): Pair<List<String>, Int> {
            val invocations = gradleInvocation.findAll(text).map { it.value }.toList()
            val violations = invocations.flatMap { line ->
                forbidden.filter { it in line }.map {
                    "$name runs Gradle with `$it` in it, which is the corpus sweep: ${line.trim()}"
                }
            }
            return violations to invocations.size
        }

        fun violationsIn(name: String, text: String) = inspect(name, text).first

        // The rule proves it can fail before it reports that nothing failed, and proves it is
        // reading anything at all. A red path that is never exercised decays into a check that
        // always passes — the same rule every other check in this build follows.
        val clean = "      - name: check\n        run: ./gradlew check -PplatformProfile=floor\n"

        check(violationsIn("fixture", clean).isEmpty()) {
            "The rule flagged a workflow that runs nothing forbidden: ${violationsIn("fixture", clean)}"
        }
        check(inspect("fixture", clean).second == 1) {
            "The rule read no Gradle invocation out of a fixture that has one. It is checking nothing."
        }
        forbidden.forEach { name ->
            check(violationsIn("fixture", clean.replace("check -P", "$name -P")).isNotEmpty()) {
                "The rule failed to flag a workflow that runs `$name`. The sweep is not being guarded."
            }
        }

        val files = workflows.asFile.listFiles().orEmpty()
            .filter { it.isFile && (it.name.endsWith(".yml") || it.name.endsWith(".yaml")) }
            .sortedBy { it.name }

        check(files.isNotEmpty()) { "No workflows were found in ${workflows.asFile}. Nothing was checked." }

        val inspected = files.map { it to inspect(it.name, it.readText()) }
        val violations = inspected.flatMap { (_, result) -> result.first }
        val invocations = inspected.sumOf { (_, result) -> result.second }

        // A check that read no Gradle invocation is not a pass, however many files it opened. Every
        // workflow here runs Gradle at least once, so a zero means the rule stopped matching the
        // shape these files are written in.
        check(invocations > 0) {
            "No `./gradlew` line was read out of ${files.size} workflow(s). The sweep is not being guarded."
        }

        report.get().asFile.also { it.parentFile.mkdirs() }.writeText(
            buildString {
                appendLine("Workflows checked: ${files.size}; Gradle invocations read: $invocations")
                appendLine("None of them may name any of: ${forbidden.joinToString(", ")}")
                appendLine()
                inspected.forEach { (file, result) ->
                    appendLine(".github/workflows/${file.name} — ${result.second} Gradle invocation(s)")
                }
            }
        )

        if (violations.isNotEmpty()) {
            throw GradleException(
                "The corpus sweep reads a real proprietary codebase and writes the real identifiers " +
                    "it found surviving. It is run by a human, deliberately, and never by CI:\n" +
                    violations.joinToString("\n") { "  $it" }
            )
        }
    }
}

tasks.named("check") {
    dependsOn(assertWorkflowsAreHardened)
    dependsOn(assertTheSweepIsNeverRunInCi)
}
