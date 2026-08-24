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
        // block one. Two narrowings, and both are load-bearing rather than tidiness:
        //
        // The wrapper rather than the word "gradle", which appears in prose throughout both files.
        // And **not a comment line**, because both files discuss `./gradlew` invocations in their
        // own prose and CONTRIBUTING.md prints the sweep's command line for a human to copy. A rule
        // that read comments would fail this build over a sentence somebody quoted, and noise is
        // what teaches people to suppress a check — and it would let the coverage assertion below
        // be satisfied by prose alone, which is the worse half.
        val gradleInvocation = Regex("""^(?!\s*#).*\./gradlew\b.*$""", RegexOption.MULTILINE)

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

        // A comment is prose, not an invocation — in both directions. It must not be flagged, and it
        // must not be counted towards the coverage assertion below, or a file whose only mention of
        // Gradle is a sentence would read as a file that was checked.
        val commented = "      # run: ./gradlew ${forbidden.first()} -PsweepProject=/somewhere\n"

        check(violationsIn("fixture", commented).isEmpty()) {
            "The rule flagged a comment. Quoting the sweep's command line would fail this build."
        }
        check(inspect("fixture", commented).second == 0) {
            "The rule counted a comment as a Gradle invocation, so its own coverage check can pass on prose."
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


// ---------------------------------------------------------------------------------------------
// The copy rules
//
// Two claims about what SnippetVeil says about itself, checked the same way everything else here is
// checked — as a Gradle task, so that `./gradlew check` decides them on any machine.
//
// The listing itself is checked in `plugin/build.gradle.kts`, against the description in the built
// distribution rather than against a file that is asserted to be the same as it. These two cover
// every other surface: the documents in this repository, and the strings the plugin puts on screen.
// ---------------------------------------------------------------------------------------------

/**
 * Every phrase banned from every surface — listing, README, UI strings, docs.
 *
 * **Each one is a claim about an adversary's capability, and none of them is ours to make.** Copy
 * here states the mechanism and never the category: *"14 names replaced"* is a count of an
 * operation, *"safe to paste"* is a verdict on an attacker we have never met.
 *
 * The inflections are listed rather than stemmed, because the two directions are not symmetrical.
 * *obfuscation* and *sanitizing* are the same claim as the words the decision named, so they are
 * here; **`guarantee` is deliberately absent while `guaranteed` is present**, because a document
 * that says *"the real guarantee is 100%-open-source-from-the-first-commit"* is describing what
 * holds the claim up, and banning that sentence would be the kind of noise that teaches people to
 * suppress a check.
 *
 * **An `extra`, for the reason [corpusSweepTask] is one.** `:plugin` checks the shipped description
 * against this same list, and two lists that drifted would leave the strictest surface checked
 * against the laxest rule — with both checks green.
 */
val bannedPhrases by extra(listOf(
    "safe to paste",
    "paste with confidence",
    "untraceable",
    "cannot be traced back to your company",
    "provably",
    "guaranteed",
    "sanitize", "sanitizes", "sanitized", "sanitizing", "sanitization",
    "sanitise", "sanitises", "sanitised", "sanitising", "sanitisation",
    "obfuscate", "obfuscates", "obfuscated", "obfuscating", "obfuscation",
))

/**
 * Fails if a banned phrase appears in a document in this repository or in a string the plugin shows.
 *
 * **Two kinds of surface, read two different ways.** A Markdown file is read whole: all of it is
 * prose somebody may quote. A Kotlin file is read for its *string literals only* — the comments in
 * this codebase discuss the ban, and several of them quote the banned phrases in order to explain
 * why they are banned. A rule that read those would fail this build over its own rationale.
 *
 * Telling the two apart needs a scanner rather than a regular expression, because a line comment
 * stripped by a pattern takes the `//` out of `https://` with it. The one below is a state machine
 * over the four things a Kotlin file is made of, and it is exercised against a fixture carrying each
 * of them before it is pointed at anything real.
 *
 * What it does not check is the build scripts — this one names every banned phrase in order to ban
 * it — or test sources, which are not a surface anybody reads the product through.
 */
val assertNoBannedPhraseAppearsOnAnySurface by tasks.registering {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Fails if a banned marketing phrase appears in a document or a UI string."

    val documents = fileTree(layout.projectDirectory) {
        include("**/*.md")
        exclude("**/build/**", "**/.gradle/**", "**/.git/**", "**/.idea/**", "**/.intellijPlatform/**")
    }
    val sources = fileTree(layout.projectDirectory) {
        include("*/src/main/kotlin/**/*.kt")
    }

    // The third kind of surface: **the descriptor's own strings are UI**. `text=` is a menu item,
    // `description=` is the tooltip under it, `displayName=` is a settings page — all of them shown
    // to a user, none of them reachable from a Kotlin source or a Markdown file. The `<description>`
    // element is not here, and cannot be: it is generated from README.md, and `:plugin` checks the
    // rendered result against this same list.
    val descriptor = layout.projectDirectory.file("plugin/src/main/resources/META-INF/plugin.xml")
    val report = layout.buildDirectory.file("reports/trust/banned-phrases.txt")
    val banned = bannedPhrases
    val root = layout.projectDirectory.asFile

    inputs.files(documents).withPropertyName("documents")
    inputs.files(sources).withPropertyName("sources")
    inputs.file(descriptor).withPropertyName("descriptor")
    inputs.property("banned", banned)
    outputs.file(report).withPropertyName("report")

    doLast {
        /**
         * Every string literal in a Kotlin file: the contents of every `"…"` and `"""…"""`, and
         * nothing that is a comment, a character literal or code.
         */
        fun stringLiteralsIn(source: String): List<String> {
            val literals = mutableListOf<String>()
            val literal = StringBuilder()
            var index = 0
            var depth = 0 // Kotlin block comments nest.

            fun at(text: String) = source.startsWith(text, index)

            while (index < source.length) {
                when {
                    depth > 0 -> when {
                        at("/*") -> { depth++; index += 2 }
                        at("*/") -> { depth--; index += 2 }
                        else -> index++
                    }
                    at("/*") -> { depth = 1; index += 2 }
                    at("//") -> index = source.indexOf('\n', index).let { if (it < 0) source.length else it }
                    at("\"\"\"") -> {
                        val end = source.indexOf("\"\"\"", index + 3)
                        val close = if (end < 0) source.length else end
                        literals += source.substring(index + 3, close)
                        index = close + 3
                    }
                    source[index] == '"' -> {
                        literal.setLength(0)
                        index++
                        while (index < source.length && source[index] != '"') {
                            if (source[index] == '\\') index++ // An escape; whatever follows is content.
                            if (index < source.length) literal.append(source[index])
                            index++
                        }
                        literals += literal.toString()
                        index++
                    }
                    source[index] == '\'' -> {
                        index++
                        while (index < source.length && source[index] != '\'') {
                            if (source[index] == '\\') index++
                            index++
                        }
                        index++
                    }
                    else -> index++
                }
            }
            return literals
        }

        /**
         * Every banned phrase in a piece of text, matched whole-word and case-insensitively.
         *
         * The words of a phrase are escaped one at a time and rejoined on `\s+`, so that a phrase
         * broken across a line wrap is still the phrase. Escaping it whole would quote the
         * separator along with the words and match nothing but a single-word entry — which is a
         * rule that reads as a guarantee and checks half a list.
         */
        fun bannedPhrasesIn(text: String): List<String> = banned.filter { phrase ->
            val pattern = phrase.split(" ").joinToString("""\s+""") { Regex.escape(it) }
            Regex("""\b$pattern\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)
        }

        // The scanner proves it can tell the four constructs apart before it is trusted to exempt a
        // comment. A rule that read a comment as code would fail this build over its own rationale;
        // one that read code as a comment would exempt the surface it exists to check.
        val fixture = """
            // "safe to paste" — a line comment quoting the ban, and a https://example.com/ in it.
            /* "untraceable" in a block comment, /* nested, */ still a comment. */
            val url = "https://example.com/a"
            val shown = "14 names replaced"
            val raw = ${"\"\"\""}multi
            line${"\"\"\""}
            val quote = '"'
        """.trimIndent()

        val fixtureLiterals = stringLiteralsIn(fixture)
        check(fixtureLiterals == listOf("https://example.com/a", "14 names replaced", "multi\nline")) {
            "The scanner read $fixtureLiterals out of the fixture. It is not telling Kotlin's " +
                "comments, strings and character literals apart, so what it exempts is unknown."
        }
        check(bannedPhrasesIn(fixtureLiterals.joinToString(" ")).isEmpty()) {
            "The scanner flagged a comment. The ban's own rationale would fail this build."
        }
        check(bannedPhrasesIn("this output is Sanitized.") == listOf("sanitized")) {
            "The rule missed `Sanitized`. It is matching neither case-insensitively nor at all."
        }
        check(bannedPhrasesIn("it is safe\n            to paste") == listOf("safe to paste")) {
            "The rule missed a multi-word phrase across a line wrap, which is how prose is written."
        }
        check(bannedPhrasesIn("no words of the list are here").isEmpty()) {
            "The rule flagged clean text. It is matching more than the list."
        }
        check(bannedPhrasesIn("the real guarantee is the source").isEmpty()) {
            "The rule flagged `guarantee`. Only `guaranteed` is banned; see the list."
        }

        /**
         * A descriptor with its comments taken out.
         *
         * Everything that is left is either markup or a string a user is shown, and a phrase in the
         * markup is a phrase in an id or a class name — which is not a sentence anybody reads. The
         * comments go for the reason Kotlin's do: this descriptor explains the copy rules it is
         * subject to, and a rule that read its own rationale would fail the build over it.
         */
        fun descriptorStrings(xml: String): String = xml.replace(Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL), " ")

        check(bannedPhrasesIn(descriptorStrings("""<!-- no "safe to paste" here --><a text="fine"/>""")).isEmpty()) {
            "The rule read an XML comment. The descriptor's own rationale would fail this build."
        }
        check(bannedPhrasesIn(descriptorStrings("""<a text="Paste  with confidence"/>""")).isNotEmpty()) {
            "The rule missed an attribute value, which is where every menu item in the descriptor lives."
        }

        val violations = mutableListOf<String>()
        var surfaces = 0

        surfaces++
        bannedPhrasesIn(descriptorStrings(descriptor.asFile.readText())).forEach {
            violations += "${descriptor.asFile.relativeTo(root)} shows a string saying \"$it\""
        }

        documents.files.sortedBy { it.path }.forEach { file ->
            surfaces++
            bannedPhrasesIn(file.readText()).forEach {
                violations += "${file.relativeTo(root)} says \"$it\""
            }
        }

        sources.files.sortedBy { it.path }.forEach { file ->
            surfaces++
            stringLiteralsIn(file.readText()).forEach { text ->
                bannedPhrasesIn(text).forEach {
                    violations += "${file.relativeTo(root)} shows a string saying \"$it\""
                }
            }
        }

        // A sweep that found nothing to sweep is not a pass.
        check(surfaces > 0) { "No documents and no sources were found under $root. Nothing was checked." }

        report.get().asFile.also { it.parentFile.mkdirs() }.writeText(
            buildString {
                appendLine(
                    "Surfaces read: $surfaces (${documents.files.size} documents, ${sources.files.size} sources, " +
                        "and the plugin descriptor)"
                )
                appendLine("None of them may say any of: ${banned.joinToString(", ")}")
                appendLine()
                appendLine("Documents are read whole; Kotlin sources are read for their string literals only,")
                appendLine("and the descriptor for everything its comments do not cover.")
                appendLine("The Marketplace description is checked separately, in :plugin:assertTheListingCopyIsTheReadme.")
            }
        )

        if (violations.isNotEmpty()) {
            throw GradleException(
                "Each of these is a claim about an adversary's capability, and copy here states the " +
                    "mechanism rather than the category:\n" +
                    violations.joinToString("\n") { "  $it" }
            )
        }
    }
}

/**
 * **The four secrets are the plugin's identity, and this is what keeps `build.yml` away from them.**
 *
 * They live in a GitHub Environment named `marketplace` rather than in repository secrets, and the
 * reason is not the obvious one. Fork pull requests never receive secrets, so the naive vector was
 * already closed. The live one is not: **a compromised third-party action in `build.yml` runs with
 * access to every *repository* secret and to none of the *environment* ones** — and `build.yml` is
 * the file that runs on every pull request, against code nobody has merged. Scoping takes the
 * signing key out of that pipeline's blast radius entirely.
 *
 * A scoping like that is a property of a settings page and a YAML key together, which means it is
 * exactly the kind of thing that is true on the day it is set up and quietly false a year later.
 * So it is a Gradle task, for the reason the other trust checks are: **a check that exists only in
 * YAML cannot be run by the person reading the claim.** `./gradlew check` goes red on any machine
 * the moment a workflow reaches for the key outside the gated job.
 *
 * Three rules, and the third is the one that makes the first two sound:
 *
 *  1. **Only `release.yml` may name any of the four at all.** Every other workflow — `build.yml`
 *     first among them — is checked for the name and must not have it.
 *  2. **A job that names one must declare `environment: marketplace`.** The environment is what
 *     holds the secrets and what carries the required reviewer; a job without the key gets neither.
 *  3. **No workflow may reach a secret by anything but its name.** `toJSON(secrets)` and
 *     `secrets[…]` hand out the whole context, and a name-based scan is only sound while names are
 *     the only way through. Without this rule the first two are a search that can be stepped around
 *     in one line.
 *
 * What it does not check is the GitHub side: that the environment exists, that it holds these four
 * and not others, that the reviewer is set. None of that is in the repository and none of it is
 * checkable from a clone. The half that is written down is the half this reads.
 */
val assertOnlyTheGatedJobCanReachTheSigningKey by tasks.registering {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Fails if a workflow outside the environment-gated release job can reach a signing secret."

    val workflows = layout.projectDirectory.dir(".github/workflows")
    val report = layout.buildDirectory.file("reports/trust/signing-secrets.txt")

    // The plugin's identity: three that sign and one that uploads. Spelled here and read from
    // `release.yml` by exactly these names — the IntelliJ Platform Gradle plugin's signing and
    // publishing extensions take them from the environment under these spellings and no others.
    val secrets = listOf("CERTIFICATE_CHAIN", "PRIVATE_KEY", "PRIVATE_KEY_PASSWORD", "PUBLISH_TOKEN")

    // The Environment the four live in. A job that names a secret must name this.
    val environment = "marketplace"

    // The one file allowed to name them, because it is the only one whose job is gated.
    val gatedWorkflow = "release.yml"

    inputs.dir(workflows).withPropertyName("workflows")
    inputs.property("secrets", secrets)
    inputs.property("environment", environment)
    outputs.file(report).withPropertyName("report")

    doLast {
        // `${{ secrets.NAME }}`, in any of the spacings GitHub accepts.
        fun namedSecret(secret: String) = Regex("""\$\{\{\s*secrets\.$secret\s*\}\}""")

        // Every way of reaching the secrets context that is not a name. `secrets[...]` indexes it
        // with an expression, `toJSON(secrets)` serialises the lot into one string, and either one
        // makes a scan for names blind. Matched on the context word itself rather than on the two
        // syntaxes, so a third spelling is caught by default instead of being missed by default.
        val secretsWithoutAName = Regex("""secrets\s*\[|\btoJSON\s*\(\s*secrets\s*\)""")

        // `environment: marketplace`, as a scalar or as the `name:` of a mapping. Both are the same
        // declaration to GitHub, and a rule that read only one of them would be a rule a rewrite
        // turns off silently.
        val declaresEnvironment = Regex(
            """(?m)^\s*environment:\s*(?:$environment\s*$|\s*$\s*^\s*name:\s*$environment\s*$)"""
        )

        /** Every violation in one workflow's text, and the number of secret references it read. */
        fun inspect(name: String, text: String): Pair<List<String>, Int> {
            val violations = mutableListOf<String>()

            if (secretsWithoutAName.containsMatchIn(text)) {
                violations += "$name reaches the secrets context without naming a secret, which " +
                    "is a way past every rule below"
            }

            val named = secrets.filter { namedSecret(it).containsMatchIn(text) }

            when {
                named.isEmpty() -> Unit

                name != gatedWorkflow ->
                    named.forEach {
                        violations += "$name names $it, and only $gatedWorkflow may: a workflow " +
                            "that runs on a pull request must have no path to the signing key"
                    }

                !declaresEnvironment.containsMatchIn(text) ->
                    violations += "$name names ${named.joinToString(", ")} but declares no " +
                        "`environment: $environment`, so the secrets are repository-wide and the " +
                        "required reviewer never runs"
            }

            return violations to named.size
        }

        fun violationsIn(name: String, text: String) = inspect(name, text).first

        // The rules prove they can fail before they report that nothing failed. A red path that is
        // never exercised decays into a check that always passes.
        val gated = """
            jobs:
              release:
                environment: $environment
                steps:
                  - env:
                      PRIVATE_KEY: ${'$'}{{ secrets.PRIVATE_KEY }}
        """.trimIndent()

        check(violationsIn(gatedWorkflow, gated).isEmpty()) {
            "The rules flagged a gated release job that breaks none of them: " +
                violationsIn(gatedWorkflow, gated)
        }
        check(violationsIn(gatedWorkflow, gated.replace("    environment: $environment\n", "")).size == 1) {
            "The rules failed to flag a job that names a secret with no environment. The gate is not being checked."
        }
        check(violationsIn(gatedWorkflow, gated.replace("environment: $environment", "environment:\n      name: $environment")).isEmpty()) {
            "The rules failed to read `environment:` written as a mapping, which is the same declaration."
        }
        check(violationsIn("build.yml", gated).size == 1) {
            "The rules failed to flag a workflow other than $gatedWorkflow naming a secret."
        }
        check(violationsIn(gatedWorkflow, gated + "\n                      ALL: ${'$'}{{ toJSON(secrets) }}").size == 1) {
            "The rules failed to flag toJSON(secrets), which hands out every secret without naming one."
        }
        check(violationsIn(gatedWorkflow, gated + "\n                      ALL: ${'$'}{{ secrets[inputs.which] }}").size == 1) {
            "The rules failed to flag an indexed secrets lookup, which is a name-based scan's blind spot."
        }
        check(inspect(gatedWorkflow, gated).second == 1) {
            "The rules read no secret out of a fixture that has one. They are not counting coverage."
        }

        val files = workflows.asFile.listFiles().orEmpty()
            .filter { it.isFile && (it.name.endsWith(".yml") || it.name.endsWith(".yaml")) }
            .sortedBy { it.name }

        check(files.isNotEmpty()) { "No workflows were found in ${workflows.asFile}. Nothing was checked." }

        // The gated workflow has to be one of the files actually read. Renaming it without renaming
        // it here would leave every rule pointed at a file that no longer exists, and the whole
        // check would pass by finding nothing — which is the one failure a green build hides.
        check(files.any { it.name == gatedWorkflow }) {
            "$gatedWorkflow is not in ${workflows.asFile}. These rules are named after a file that is not there."
        }

        val inspected = files.map { it to inspect(it.name, it.readText()) }
        val violations = inspected.flatMap { (_, result) -> result.first }
        val secretsRead = inspected.sumOf { (_, result) -> result.second }

        // A check that read no secret reference is not a pass. `release.yml` passes all four to
        // `publishPlugin`, so anything short of four means the rules stopped matching the shape the
        // file is written in rather than that the file stopped naming them.
        //
        // **Counted in the gated workflow alone, and asserted after the violations above have had
        // their say.** A total across every file would move the moment another workflow reached for
        // a secret — which is precisely the thing being caught — and this coverage assertion would
        // fire first and report a miscount where the real answer is a violation.
        val secretsReadInGatedWorkflow = inspected
            .single { (file, _) -> file.name == gatedWorkflow }
            .let { (_, result) -> result.second }

        report.get().asFile.also { it.parentFile.mkdirs() }.writeText(
            buildString {
                appendLine("Workflows checked: ${files.size}; secret references read: $secretsRead")
                appendLine("The four: ${secrets.joinToString(", ")}")
                appendLine()
                appendLine("Only $gatedWorkflow may name them, its job must declare `environment: $environment`,")
                appendLine("and no workflow may reach the secrets context without naming a secret.")
                appendLine()
                inspected.forEach { (file, result) ->
                    appendLine(".github/workflows/${file.name} — ${result.second} of the four")
                }
                appendLine()
                appendLine("Not checked here, because none of it is in the repository: that the")
                appendLine("$environment environment exists, holds these four, and carries a required reviewer.")
            }
        )

        if (violations.isNotEmpty()) {
            throw GradleException(
                "The signing key is the plugin's identity, and the pipeline that runs unmerged code " +
                    "has no path to it:\n" +
                    violations.joinToString("\n") { "  $it" }
            )
        }

        check(secretsReadInGatedWorkflow == secrets.size) {
            "$secretsReadInGatedWorkflow of ${secrets.size} signing secrets were read out of " +
                "$gatedWorkflow, which passes all four to publishPlugin. The rules read the wrong thing."
        }
    }
}

tasks.named("check") {
    dependsOn(assertWorkflowsAreHardened)
    dependsOn(assertTheSweepIsNeverRunInCi)
    dependsOn(assertNoBannedPhraseAppearsOnAnySurface)
    dependsOn(assertOnlyTheGatedJobCanReachTheSigningKey)
}
