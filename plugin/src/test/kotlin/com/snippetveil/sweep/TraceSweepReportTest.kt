package com.snippetveil.sweep

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * **What the trace sweep reads, where it may read it from, and what it reports** — asserted without a
 * trace to sweep, because a rule nobody could test without real traces would be a rule nobody tests.
 *
 * Every trace here is synthetic, and the names in it are made up.
 */
class TraceSweepReportTest {

    /**
     * **A file of traces is split on a line of three or more hyphens**, and each trace remembers the
     * line of the file it starts on — which is how a reader of the report finds it again. Blank lines
     * at a trace's edges are the file's, not the trace's; a blank line *inside* one is kept, because
     * a `DebugProbes` dump has one and has to arrive whole to be named as a dump.
     */
    @Test
    fun `a trace file is split on hyphen lines, and each trace knows the line it starts on`() {
        val file = listOf(
            "com.acme.Boom: x",
            "\tat com.acme.Ledger.settle(Ledger.java:1)",
            "",
            "---",
            "",
            "Coroutines dump 2026/09/30 12:00:01",
            "",
            "Coroutine \"coroutine#2\":DeferredCoroutine{Active}@1b68b9a4, state: SUSPENDED",
            "-----",
            "java.lang.IllegalStateException",
            "\tat java.lang.Thread.run(Thread.java:840)",
            "---",
            "",
        ).joinToString("\n")

        val traces = tracesIn(file)

        assertEquals(listOf(1, 6, 10), traces.map { it.line })
        assertEquals("com.acme.Boom: x\n\tat com.acme.Ledger.settle(Ledger.java:1)", traces[0].text)
        assertTrue("\n\nCoroutine " in traces[1].text) { "A blank line inside a trace was dropped:\n${traces[1].text}" }
        assertEquals("java.lang.IllegalStateException\n\tat java.lang.Thread.run(Thread.java:840)", traces[2].text)
    }

    /** A file with no separator is one trace, and a file of nothing is no trace at all. */
    @Test
    fun `a file with no separator is one trace, and an empty one is none`() {
        assertEquals(1, tracesIn("com.acme.Boom\n\tat com.acme.Ledger.settle(Ledger.java:1)\n").size)
        assertEquals(0, tracesIn("\n---\n\n").size)
    }

    @Test
    fun `a trace file outside the repository is read`(@TempDir root: Path) {
        val repository = Files.createDirectories(root.resolve("repo"))
        val traces = Files.writeString(Files.createDirectories(root.resolve("elsewhere")).resolve("traces.txt"), "x")

        assertEquals(traces.toAbsolutePath().normalize(), traceSweepFile(traces, repository))
    }

    /**
     * **The input stays outside the tree as surely as the output does.** A file somebody assembles
     * will otherwise get assembled in the repository — and a stack trace is the artifact people drop
     * into an issue without thinking.
     */
    @Test
    fun `a trace file inside the repository is refused`(@TempDir root: Path) {
        val repository = Files.createDirectories(root.resolve("repo"))
        val inside = Files.writeString(Files.createDirectories(repository.resolve("build")).resolve("traces.txt"), "x")

        val refused = assertThrows(IllegalStateException::class.java) { traceSweepFile(inside, repository) }

        assertTrue("inside this repository" in refused.message.orEmpty()) { refused.message.orEmpty() }
    }

    /** **Where it resolves, not where it is written**: a link from outside into the tree is refused too. */
    @Test
    fun `a trace file that resolves inside the repository through a link is refused`(@TempDir root: Path) {
        val repository = Files.createDirectories(root.resolve("repo"))
        val inside = Files.writeString(repository.resolve("traces.txt"), "x")
        val link = Files.createSymbolicLink(root.resolve("traces.txt"), inside)

        assertThrows(IllegalStateException::class.java) { traceSweepFile(link, repository) }
    }

    @Test
    fun `a trace file that does not exist is refused`(@TempDir root: Path) {
        assertThrows(IllegalStateException::class.java) { traceSweepFile(root.resolve("missing.txt"), root.resolve("repo")) }
    }

    @Test
    fun `the report says what it is before it says anything else`() {
        val head = report().render().lineSequence().take(10).joinToString("\n").lowercase()

        assertTrue("real" in head && "trace" in head) { "The report does not say what it contains:\n$head" }
        assertTrue("do not paste" in head) { "The report does not say not to paste it:\n$head" }
        assertTrue("synthetic" in head) { "The report does not say findings become synthetic fixtures:\n$head" }
    }

    /**
     * **The report states its own denominator**: traces read, traces refused by reason, and frames by
     * classification with the partial `Unknown`s apart from the whole ones.
     */
    @Test
    fun `the report states traces read, refused by reason, and frames by classification`() {
        val rendered = report().render()

        assertTrue("Traces read           : 7" in rendered) { rendered }
        assertTrue("Traces refused        : 3" in rendered) { rendered }
        assertTrue("not a trace         : 2  (file line 9, 30)" in rendered) { rendered }
        assertTrue("DebugProbes dump    : 1  (file line 20)" in rendered) { rendered }
        assertTrue("Frames                : 40" in rendered) { rendered }
        assertTrue("project-owned       : 11" in rendered) { rendered }
        assertTrue("library-owned       : 17" in rendered) { rendered }
        assertTrue("Unknown             : 8" in rendered) { rendered }
        assertTrue("partial Unknown     : 4" in rendered) { rendered }
    }

    /** A zero is a line like any other: a reason nobody hit is still a reason the reader is told about. */
    @Test
    fun `a refusal reason with no trace under it is still stated`() {
        val rendered = report(refused = emptyMap()).render()

        assertTrue("Traces refused        : 0" in rendered) { rendered }
        assertTrue("not a trace         : 0" in rendered) { rendered }
        assertTrue("DebugProbes dump    : 0" in rendered) { rendered }
    }

    /** **Per trace, with the token and the line** — where the trace starts in the file, and where in the output the token is. */
    @Test
    fun `oracle hits are listed per trace with the token and the line`() {
        val rendered = report().render()

        val section = rendered.substringAfter("── trace 2, file line 12 ")
        assertTrue("L3  Ledger" in section) { rendered }
        assertTrue("\tat com.pkg1.Ledger.method2(Type3.java:4)" in section) { rendered }
        assertTrue("── trace 5, file line 41 " in rendered) { rendered }
        assertTrue("L1  acme" in rendered.substringAfter("── trace 5, file line 41 ")) { rendered }
    }

    /** **Read, not gated**: a run full of hits renders a report and nothing about it throws. */
    @Test
    fun `hits never fail the run on their own`() {
        val many = (1..50).map { TraceFindings(it, it * 10, listOf(Survivor("Ledger", 1, "Ledger"))) }

        val rendered = report(findings = many).render()

        assertTrue("gates nothing" in rendered) { rendered }
    }

    private fun report(
        refused: Map<TraceRefusal, List<Int>> = mapOf(TraceRefusal.NOT_A_TRACE to listOf(9, 30), TraceRefusal.COROUTINE_DUMP to listOf(20)),
        findings: List<TraceFindings> = listOf(
            TraceFindings(2, 12, listOf(Survivor("Ledger", 3, "\tat com.pkg1.Ledger.method2(Type3.java:4)"))),
            TraceFindings(5, 41, listOf(Survivor("acme", 1, "acme.pkg1.Type2: str"))),
        ),
    ) = TraceSweepReport(
        startedAt = "2026-10-01T12:00:00",
        traceFile = "/home/me/traces.txt",
        project = "/home/me/acme",
        swept = TraceSwept(
            traces = 7,
            refused = refused,
            frames = FrameCounts(projectOwned = 11, libraryOwned = 17, unknown = 8, partialUnknown = 4),
            findings = findings,
            failures = emptyList(),
        ),
    )
}
