package com.snippetveil.sweep

import java.nio.file.Path

/**
 * **The most sensitive file this project can produce**, and the reason it is written the way it is.
 *
 * The sweep's failure report *contains the leak by construction*: it is a list of real identifiers
 * from a real proprietary codebase, printed next to the lines they survived on. The natural instinct
 * on reading one is to paste it into an issue to ask about it, and that instinct is what the header
 * exists to interrupt — see [render].
 *
 * Everything here is a pure function of data, so what the report says can be asserted without a
 * codebase to sweep. What it may not say — and where it may not be written — is [sweepReportPath].
 *
 * @param startedAt when the sweep ran, as text. Passed in rather than read from a clock, so that
 *   rendering stays a function of its arguments.
 * @param targetProject the path the sweep was pointed at
 * @param filesSwept how many Java files were anonymized
 * @param universe how the project-owned name set was arrived at — the check's own coverage, which is
 *   reported rather than assumed for the same reason the trust checks report theirs
 * @param findings one entry per file swept, in the order they were swept. Files with nothing to
 *   triage are dropped by [render] rather than by the caller, so that [filesSwept] and the rows can
 *   never disagree about what was looked at.
 * @param failures the files the anonymiser threw on. **A throw is a conclusion, not an outage** —
 *   it is the shape nobody thought of, arriving as a stack trace instead of as a surviving name —
 *   so it is reported beside the findings rather than ending the run. A codebase of thousands of
 *   files has one PSI edge case in it somewhere, and an instrument nobody can finish running is one
 *   nobody runs.
 */
internal class SweepReport(
    private val startedAt: String,
    private val targetProject: String,
    private val filesSwept: Int,
    private val universe: UniverseSize,
    private val findings: List<FileFindings>,
    private val failures: List<SweepFailure> = emptyList(),
) {

    /**
     * The report, whole.
     *
     * The header is four things in a fixed order, and each of them is load-bearing:
     *
     * 1. **What this file is** — real identifiers from a real proprietary codebase.
     * 2. **What not to do with it** — never paste it, screenshot it, or attach it to an issue.
     * 3. **What a finding earns** — a *synthetic* fixture reproducing its shape. Real code in,
     *    findings out; the code itself never moves.
     * 4. **What a row is worth** — a suspect. The oracle is blunt on purpose and throws on genuine
     *    collisions with preserved library members, so a report that presented rows as verdicts would
     *    be one whose first false positive discredits the whole instrument.
     */
    fun render(): String = buildString {
        appendLine("SnippetVeil corpus sweep — $startedAt")
        appendLine()
        appendLine("!! This file lists REAL IDENTIFIERS read out of $targetProject.")
        appendLine("!! It is the single most sensitive file this project can produce.")
        appendLine("!! DO NOT PASTE it into an issue, a pull request, a chat or a screenshot.")
        appendLine("!! A bug found here earns a SYNTHETIC fixture reproducing its shape — never the")
        appendLine("!! real code that revealed it. Real code in, findings out, code never moves.")
        appendLine()
        appendLine("Target project  : $targetProject")
        appendLine("Files swept     : $filesSwept")
        appendLine("Name universe   : ${universe.owned} project-owned name(s)")
        appendLine("                  ${universe.declared} declared in the project's own sources,")
        appendLine("                  less ${universe.sharedWithLibraries} the JDK or a library also declares,")
        appendLine("                  less ${universe.topLevelSegments} top-level package segment(s) the engine passes through.")
        appendLine()

        val triage = findings.filter { it.survivors.isNotEmpty() }
        if (triage.isEmpty() && failures.isEmpty()) {
            appendLine("No project-owned name survived into any output.")
            appendLine()
            appendLine("That is a result, not a pass. The oracle cannot see a project name that collides")
            appendLine("exactly with a library-declared one, and it says nothing at all about whether the")
            appendLine("output is still answerable — see the known limits in CONTRIBUTING.md.")
            return@buildString
        }

        appendLine("Files with findings      : ${triage.size}")
        appendLine("Distinct names surviving : ${triage.sumOf { it.survivors.size }}")

        appendLine("Files that could not be swept : ${failures.size}")
        appendLine()
        appendLine("Every row below is a SUSPECT, not a verdict. The oracle is blunt on purpose: it")
        appendLine("flags a project-declared name that reached the output as text, and a genuine")
        appendLine("collision with a preserved library member looks exactly the same to it. A human")
        appendLine("adjudicates each row — a false positive costs a minute, and a false negative is the")
        appendLine("product's core promise failing silently.")

        triage.forEach { file ->
            appendLine()
            appendLine("── ${file.path} ".padEnd(96, '─'))
            file.survivors.forEach { survivor ->
                appendLine("  L${survivor.line}  ${survivor.name}")
                appendLine("        ${survivor.text}")
            }
        }

        if (failures.isEmpty()) return@buildString

        appendLine()
        appendLine("── files that could not be swept ".padEnd(96, '─'))
        appendLine("  The anonymiser threw on these. That is a finding too — a shape nobody thought of,")
        appendLine("  arriving as a stack trace rather than as a surviving name. It earns a synthetic")
        appendLine("  fixture reproducing the shape, like any other.")
        failures.forEach { failure ->
            appendLine()
            appendLine("  ${failure.path}")
            appendLine("        ${failure.summary}")
        }
    }
}

/**
 * One file the anonymiser threw on.
 *
 * @param path the file, relative to the swept project
 * @param summary the exception's type and message. Kept out of the console for the reason every
 *   other row is: a message can name the symbol it choked on.
 */
internal class SweepFailure(val path: String, val summary: String)

/** Every project-owned name that reached one file's output. */
internal class FileFindings(val path: String, val survivors: List<Survivor>)

/**
 * How the project-owned name set was arrived at — the oracle's own coverage, reported rather than
 * assumed.
 *
 * @param owned the size of the universe the oracle actually tested against
 * @param declared how many names the declaration walk read out of the project's own sources
 * @param sharedWithLibraries how many of those the JDK or a library also declares, and which were
 *   therefore subtracted. A large number here is the instrument telling a reader how much of the
 *   project it is structurally blind to.
 * @param topLevelSegments how many top-level package segments were subtracted
 */
internal class UniverseSize(
    val owned: Int,
    val declared: Int,
    val sharedWithLibraries: Int,
    val topLevelSegments: Int,
)

/**
 * Where the report is allowed to go — **outside the repository tree entirely, and outside the swept
 * project's too.**
 *
 * `.gitignore` stops `git add`. It does not stop a paste, an attachment or a screenshot, and **a
 * file being *inside* the repository is exactly what makes those feel safe.** So the location is not
 * a convention that a later `build/reports/` default could quietly undo: it is checked here, on
 * every run, against every tree the sweep touched.
 *
 * The swept project is named too, and for the mirror-image reason: a leak report written into the
 * proprietary codebase it was read from is one commit away from that codebase's own history.
 *
 * @param reportDirectory where the caller wants the report
 * @param fileName the report's own name
 * @param forbidden the trees it may not land in, each under the name the failure should call it
 * @return the absolute, normalized path to write
 */
internal fun sweepReportPath(reportDirectory: Path, fileName: String, forbidden: Map<String, Path>): Path {
    val report = reportDirectory.toAbsolutePath().normalize().resolve(fileName)

    // Asked twice, of the path as written and of the path as the filesystem resolves it. Neither
    // alone is enough: `/tmp` is a symlink to `/private/tmp` on macOS, so a link is a way past the
    // first, and a directory that does not exist yet cannot be resolved at all.
    forbidden.forEach { (name, tree) ->
        check(!report.startsWith(tree.toAbsolutePath().normalize()) && !settled(report).startsWith(settled(tree))) {
            "The sweep report would be written inside $name ($tree), and it is a list of real " +
                "identifiers from a real codebase. .gitignore stops `git add`; it does not stop a " +
                "paste or a screenshot, and a file being inside a repository is what makes those " +
                "feel safe. Point -PsweepReportDir somewhere outside every tree the sweep touches."
        }
    }
    return report
}

/**
 * [path] as the filesystem actually sees it — symlinks resolved where they can be, absolute and
 * normalized where they cannot.
 *
 * The fallback is not a formality: the report directory does not exist yet on a first run, and
 * `/tmp` is a symlink to `/private/tmp` on macOS — so a containment test written on either half
 * alone answers the wrong question on one platform or the other.
 */
private fun settled(path: Path): Path = generateSequence(path.toAbsolutePath().normalize()) { it.parent }
    .firstOrNull { it.toFile().exists() }
    ?.let { existing ->
        runCatching { existing.toRealPath() }.getOrNull()
            ?.resolve(existing.relativize(path.toAbsolutePath().normalize()))
    }
    ?: path.toAbsolutePath().normalize()
