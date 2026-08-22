plugins {
    // For the lifecycle `check` task the hardening check below hangs off, and nothing else. The
    // root project builds no code.
    base
}

/**
 * Fails if a GitHub Actions workflow uses an action it has not pinned to a commit SHA, or does not
 * say what token it runs with.
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

        // `- uses: owner/repo/path@ref # v1.2.3`, as a step or as a bare mapping key. The trailing
        // comment is optional to *match* and required to *pass* — see below.
        val usesStep = Regex("""^\s*(?:-\s+)?uses:\s*(\S+)(?:\s*#\s*(.*?))?\s*$""")

        // A workflow's own `permissions:`, at column 0. A job-level one is indented and does not
        // match, which is the point: a job that asks for more is fine, a file that asks for nothing
        // and inherits the repository default is not.
        val declaredPermissions = Regex("""(?m)^permissions:""")

        /** Every hardening rule this project's workflows have to clear, over one file's text. */
        fun violationsIn(name: String, text: String): List<String> {
            val violations = mutableListOf<String>()

            if (!declaredPermissions.containsMatchIn(text)) {
                violations += "$name declares no top-level `permissions:`, so its jobs run with " +
                    "whatever the repository happens to hand out"
            }

            text.lines().forEachIndexed { index, line ->
                val (reference, version) = usesStep.matchEntire(line)?.destructured ?: return@forEachIndexed
                val where = "$name:${index + 1}"
                when {
                    !commitSha.matches(reference.substringAfterLast('@', "")) ->
                        violations += "$where uses $reference, which is a tag or a branch: whoever " +
                            "controls that repository can repoint it"

                    version.isBlank() ->
                        violations += "$where pins $reference but names no version, so the pin " +
                            "cannot be read by anyone deciding whether to trust it"
                }
            }

            return violations
        }

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

        val files = workflows.asFile.listFiles().orEmpty()
            .filter { it.isFile && (it.name.endsWith(".yml") || it.name.endsWith(".yaml")) }
            .sortedBy { it.name }

        // A check that found nothing to check is not a pass.
        check(files.isNotEmpty()) { "No workflows were found in ${workflows.asFile}. Nothing was checked." }

        val violations = files.flatMap { violationsIn(it.name, it.readText()) }

        report.get().asFile.also { it.parentFile.mkdirs() }.writeText(
            buildString {
                appendLine("Workflows checked: ${files.size}")
                appendLine("Every `uses:` must be pinned to a commit SHA and name its version,")
                appendLine("and every workflow must declare its own top-level `permissions:`.")
                appendLine()
                files.forEach { appendLine(".github/workflows/${it.name}") }
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

tasks.named("check") {
    dependsOn(assertWorkflowsAreHardened)
}
