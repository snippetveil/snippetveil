package com.snippetveil.sweep

import java.nio.file.Files
import java.nio.file.Path

/**
 * **What the trace sweep writes**, as a pure function of what it found — so that every claim the
 * report makes about itself can be asserted without a trace to sweep. Where it may be written is
 * [sweepReportPath]'s, exactly as for the other halves; where its input may be read from is
 * [traceSweepFile].
 *
 * **The report contains the leak by construction**, like the source half's: its rows are tokens out
 * of real traces — production output, a colleague's paste — printed beside the output lines they
 * survived on. So it says so before it says anything else, and the console never sees a row.
 *
 * @param startedAt when the sweep ran, as text, so that rendering stays a function of its arguments
 * @param traceFile the file of traces the sweep read
 * @param project the project the traces were resolved against
 * @param swept what the pass made of every trace in the file
 */
internal class TraceSweepReport(
    private val startedAt: String,
    private val traceFile: String,
    private val project: String,
    private val swept: TraceSwept,
) {

    fun render(): String = buildString {
        appendLine("SnippetVeil trace sweep — $startedAt")
        appendLine()
        appendLine("!! This file lists tokens out of REAL STACK TRACES read from $traceFile.")
        appendLine("!! A trace carries real identifiers, and so does every row below.")
        append(doNotPasteThis())
        appendLine()
        appendLine("Trace file            : $traceFile")
        appendLine("Resolved against      : $project")
        appendLine()

        // **The denominator, every line of it printed even at zero.** A report over a file nothing
        // was read out of looks exactly like a clean one unless it says how much it looked at.
        appendLine("Traces read           : ${swept.traces}  (every trace in the file, the refused ones included)")
        appendLine("Traces refused        : ${swept.refusedCount}")
        TraceRefusal.entries.forEach { reason ->
            val lines = swept.refused[reason].orEmpty()
            val where = if (lines.isEmpty()) "" else "  (file line ${lines.joinToString(", ")})"
            appendLine("  ${reason.label.padEnd(20)}: ${lines.size}$where")
        }
        appendLine("Traces that threw     : ${swept.failures.size}")
        appendLine("Frames                : ${swept.frames.total}")
        appendLine("  ${"project-owned".padEnd(20)}: ${swept.frames.projectOwned}")
        appendLine("  ${"library-owned".padEnd(20)}: ${swept.frames.libraryOwned}")
        appendLine("  ${"Unknown".padEnd(20)}: ${swept.frames.unknown}")
        appendLine("  ${"partial Unknown".padEnd(20)}: ${swept.frames.partialUnknown}")
        appendLine()
        appendLine("A frame is classified by its class. Unknown is a class nothing resolved; partial")
        appendLine("Unknown is a class whose longest prefix resolved and whose remainder was replaced")
        appendLine("whole. A refused trace is a finding in itself when the trace was a real one: the")
        appendLine("grammar did not admit a shape somebody actually pasted.")
        appendLine()

        val triage = swept.findings.filter { it.survivors.isNotEmpty() }
        appendLine("Traces with oracle hits: ${triage.size}")
        appendLine()
        appendLine("Every hit below is a SUSPECT, and this list gates nothing — the instrument is read,")
        appendLine("not gated. A hit is a token of the trace's own, split on `.`, `${'$'}` and `/`, that the")
        appendLine("JDK and the libraries do not declare, and that reached the anonymized output. The")
        appendLine("top-level package segment — `com`, `org` — passes through by a positional rule and")
        appendLine("is reported rather than subtracted; adjudicate it once and read past it.")

        triage.forEach { trace ->
            appendLine()
            appendLine("── trace ${trace.ordinal}, file line ${trace.line} ".padEnd(96, '─'))
            trace.survivors.forEach { survivor ->
                appendLine("  L${survivor.line}  ${survivor.name}")
                appendLine("        ${survivor.text}")
            }
        }

        if (swept.failures.isEmpty()) return@buildString

        appendLine()
        appendLine("── traces that threw ".padEnd(96, '─'))
        appendLine("  The action's core path threw on these. That is a finding too, and it earns a")
        appendLine("  synthetic fixture reproducing the shape, like any other.")
        swept.failures.forEach { failure ->
            appendLine()
            appendLine("  trace ${failure.ordinal}, file line ${failure.line}")
            appendLine("        ${failure.summary}")
        }
    }
}

/**
 * What one pass made of a file of traces.
 *
 * @param traces every trace the file was split into, refused ones included
 * @param refused the file line each refused trace starts on, by the reason it was refused
 * @param frames every frame of every trace that was read, by how its class was classified
 * @param findings the traces with something to triage, in file order
 * @param failures the traces the core path threw on
 */
internal class TraceSwept(
    val traces: Int,
    val refused: Map<TraceRefusal, List<Int>>,
    val frames: FrameCounts,
    val findings: List<TraceFindings>,
    val failures: List<TraceFailure>,
) {
    /** How many traces were refused, whatever the reason. */
    val refusedCount: Int get() = refused.values.sumOf { it.size }
}

/** Why a trace in the file was refused — the action's two refusals, in the report's words. */
internal enum class TraceRefusal(val label: String) {
    NOT_A_TRACE("not a trace"),
    COROUTINE_DUMP("DebugProbes dump"),
}

/**
 * Frames by how their class was classified. Four buckets that do not overlap, so that they add up to
 * [total] and a reader can check that they do.
 *
 * @param partialUnknown a class whose longest prefix resolved and whose remainder is one `Unknown` —
 *   counted apart from [unknown], where nothing resolved at all
 */
internal data class FrameCounts(
    val projectOwned: Int = 0,
    val libraryOwned: Int = 0,
    val unknown: Int = 0,
    val partialUnknown: Int = 0,
) {
    val total: Int get() = projectOwned + libraryOwned + unknown + partialUnknown

    operator fun plus(other: FrameCounts) = FrameCounts(
        projectOwned + other.projectOwned,
        libraryOwned + other.libraryOwned,
        unknown + other.unknown,
        partialUnknown + other.partialUnknown,
    )
}

/**
 * Every token of one trace that reached its output.
 *
 * @param ordinal the trace's ordinal in the file, from 1
 * @param line the line of the file the trace starts on
 */
internal class TraceFindings(val ordinal: Int, val line: Int, val survivors: List<Survivor>)

/** One trace the core path threw on, named by where it is rather than by what it says. */
internal class TraceFailure(val ordinal: Int, val line: Int, val summary: String)

/**
 * One trace out of a trace file.
 *
 * @param line the 1-based line of the file the trace starts on
 * @param text the trace, without the blank lines around it
 */
internal class TraceEntry(val line: Int, val text: String)

/**
 * **The traces in a trace file**: separated by a line of three or more hyphens, with the blank lines
 * at each one's edges dropped.
 *
 * A separator rather than a blank line, because a `DebugProbes` dump has a blank line inside it and
 * has to arrive whole to be refused *as a dump*; split on blank lines, it would be counted as a dump
 * and a not-a-trace. A hyphen line is not in the trace grammar's vocabulary, so it can never be part
 * of a trace either.
 */
internal fun tracesIn(file: String): List<TraceEntry> {
    val traces = mutableListOf<TraceEntry>()
    val current = mutableListOf<IndexedValue<String>>()

    fun close() {
        val kept = current.dropWhile { it.value.isBlank() }.dropLastWhile { it.value.isBlank() }
        if (kept.isNotEmpty()) traces += TraceEntry(kept.first().index + 1, kept.joinToString("\n") { it.value })
        current.clear()
    }

    for (line in file.split('\n').withIndex()) {
        if (SEPARATOR.matches(line.value.removeSuffix("\r"))) close() else current += line
    }
    close()
    return traces
}

/** What separates one trace from the next in a trace file. */
private val SEPARATOR = Regex("""-{3,}\s*""")

/**
 * **The trace file, refused if it resolves inside this repository.**
 *
 * The other halves point at a codebase; this one's input is a file somebody assembles, and a file
 * somebody assembles will otherwise get assembled *in* the repository — where a stack trace, the
 * artifact people drop into an issue without thinking, is one `git add` from public history. So the
 * input lives outside the tree, alongside the report, and this is what says so.
 *
 * Asked of the path as written and of the path as the filesystem resolves it, as [sweepReportPath]
 * asks it, because a link is a way past either alone.
 *
 * @param path what the command line named
 * @param repository this repository's root
 * @return the file, absolute and normalized
 */
internal fun traceSweepFile(path: Path, repository: Path): Path {
    val file = path.toAbsolutePath().normalize()

    check(Files.isRegularFile(file)) { "$file is not a file, so there are no traces to sweep." }
    check(!resolvesInside(file, repository)) {
        "$file is inside this repository ($repository). A trace file is real traces, and it stays " +
            "outside this tree — not merely gitignored, because a file being inside the repository is " +
            "what makes pasting it feel safe. Keep it beside the report, outside every checkout."
    }
    return file
}
