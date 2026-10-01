package com.snippetveil.sweep

import com.intellij.openapi.project.Project
import com.snippetveil.core.AnonymizationSettings
import com.snippetveil.core.SnippetPlan
import com.snippetveil.core.StackTrace
import com.snippetveil.core.SymbolOccurrence
import com.snippetveil.plugin.JavaSnippetTestCase
import com.snippetveil.plugin.TracePlanBuilder
import java.nio.file.Files

/**
 * **The trace sweep's loop, proved on synthetic input** — the pass the instrument runs over a file of
 * real traces, run here over a file of made-up ones, so that what it counts and what it reports can be
 * asserted in `check`, in every cell.
 *
 * **Every trace here is synthetic.** The instrument exists for the traces nobody can commit; this is
 * the proof that it reads them, and it reads a file built in a temporary directory outside the
 * repository, the way the instrument's own input has to be.
 *
 * **The leak is manufactured by a route that drops a rename**, because the shipped route makes none:
 * a sweep that could not see this one would be blind to it on the day the resolver regressed.
 */
class TracePassTest : JavaSnippetTestCase() {

    /**
     * **The acceptance demonstration**: one project name deliberately left unanonymized, and the
     * report lists it — under the trace it is in, with the token and the output line it survived on.
     */
    fun `test a project name deliberately left unanonymized is listed under its trace with its line`() {
        addPayoutsProject()

        val swept = pass(route = ::leavingPayoutLedger).over(tracesInTheFile())

        val first = swept.findings.single { it.ordinal == 1 }
        assertEquals("the trace is not named by the file line it starts on", 1, first.line)
        val hit = first.survivors.single { it.name == "PayoutLedger" }
        assertEquals("the hit is not on the output line it survived on", 2, hit.line)
        assertTrue("the hit's line does not carry the token: ${hit.text}", "PayoutLedger" in hit.text)

        val rendered = TraceSweepReport("now", "traces.txt", "project", swept).render()
        assertTrue(rendered, "L2  PayoutLedger" in rendered.substringAfter("── trace 1, file line 1 "))
    }

    /**
     * **The shipped route leaves nothing of the project's but the top-level package segment**, which
     * the engine passes through by a positional rule and the oracle reports rather than subtracts.
     * Asserted on the same file, so that the hit above is the dropped rename and nothing else.
     */
    fun `test the shipped route leaves only the top-level package segment for triage`() {
        addPayoutsProject()

        val swept = pass().over(tracesInTheFile())

        assertEquals(
            "the shipped route left something of the project's: ${swept.findings.map { t -> t.survivors.map { "${it.name}: ${it.text}" } }}",
            listOf(1 to setOf("com")),
            swept.findings.map { trace -> trace.ordinal to trace.survivors.map { it.name }.toSet() },
        )
        assertEquals("a trace threw: ${swept.failures.map { it.summary }}", 0, swept.failures.size)
    }

    /**
     * **The report's denominator, counted rather than assumed**: four traces read, two refused — each
     * under its own reason, at the line it starts on — and every frame of the two that were read
     * classified, the partial `Unknown` apart from the whole one.
     */
    fun `test the pass counts traces read, refusals by reason and frames by classification`() {
        addPayoutsProject()

        val swept = pass().over(tracesInTheFile())

        assertEquals(4, swept.traces)
        assertEquals(mapOf(TraceRefusal.NOT_A_TRACE to listOf(9), TraceRefusal.COROUTINE_DUMP to listOf(11)), swept.refused)
        assertEquals(FrameCounts(projectOwned = 2, libraryOwned = 3, unknown = 1, partialUnknown = 1), swept.frames)
    }

    /**
     * **A trace with nothing in it but what the JDK declares has nothing to look for**, and it is read
     * and counted like any other rather than thrown on — the oracle refuses an empty universe, and a
     * pass that let it would lose every trace after the first one that was all JDK.
     */
    fun `test a trace of nothing but JDK names is read and is not a failure`() {
        val swept = pass().over(listOf(TraceEntry(1, "java.lang.IllegalStateException\n\tat java.lang.Thread.run(Thread.java:840)")))

        assertEquals(0, swept.failures.size)
        assertEquals(FrameCounts(libraryOwned = 1), swept.frames)
        assertEmpty(swept.findings)
    }

    /** **A throw is a finding, not an outage**: recorded under its trace, and the traces after it are still read. */
    fun `test a trace the core path throws on is recorded and the rest are still read`() {
        addPayoutsProject()

        val swept = pass(route = { project, trace ->
            if ("PayoutRejected" in trace.text) error("a shape nobody thought of") else TracePlanBuilder.build(project, trace)
        }).over(tracesInTheFile())

        assertEquals(listOf(1), swept.failures.map { it.line })
        assertTrue(swept.failures.single().summary, "a shape nobody thought of" in swept.failures.single().summary)
        assertEquals("the trace after the throw was not read", FrameCounts(libraryOwned = 1), swept.frames)
    }

    private fun pass(route: (Project, StackTrace) -> SnippetPlan = TracePlanBuilder::build) =
        TracePass(project, AnonymizationSettings.DEFAULTS, route)

    /** The synthetic file, written outside the repository and read back the way the instrument reads one. */
    private fun tracesInTheFile(): List<TraceEntry> {
        val file = Files.createTempDirectory("trace-sweep").resolve("traces.txt")
        Files.writeString(file, TRACE_FILE)
        return tracesIn(Files.readString(file))
    }

    /** [TracePlanBuilder], less every rename of `PayoutLedger` — the missing plan item, put there on purpose. */
    private fun leavingPayoutLedger(project: Project, trace: StackTrace): SnippetPlan {
        val plan = TracePlanBuilder.build(project, trace)
        return SnippetPlan(
            plan.text,
            plan.occurrences.filterNot { it is SymbolOccurrence && it.text == "PayoutLedger" },
            rootFromOwnedTypes = plan.rootFromOwnedTypes,
        )
    }

    /** A public class with a nested one, and the exception the trace's header names. `PayoutGhost` is never declared. */
    private fun addPayoutsProject() {
        myFixture.addFileToProject(
            "com/acme/payouts/PayoutLedger.java",
            """
            package com.acme.payouts;

            public class PayoutLedger {
                public void settle() {}
                public static class Batch {
                    void post() {}
                }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "com/acme/payouts/PayoutRejected.java",
            "package com.acme.payouts;\n\npublic class PayoutRejected extends RuntimeException {}",
        )
    }
}

/**
 * **A synthetic trace file**: a trace over the fixture project — two project frames, a generated class
 * whose owner resolves, an unresolved class, a library frame and a JDK frame behind a module prefix —
 * then a log line, a `DebugProbes` dump, and a trace of nothing but the JDK's names.
 */
private val TRACE_FILE = listOf(
    "Exception in thread \"main\" com.acme.payouts.PayoutRejected: payout 7731 refused",
    "\tat com.acme.payouts.PayoutLedger.settle(PayoutLedger.java:42)",
    "\tat com.acme.payouts.PayoutLedger\$Batch.post(PayoutLedger.java:88)",
    "\tat com.acme.payouts.PayoutLedger\$1.run(PayoutLedger.java:44)",
    "\tat com.acme.payouts.PayoutGhost.haunt(PayoutGhost.java:9)",
    "\tat org.junit.Assert.fail(Assert.java:89)",
    "\tat java.base/java.lang.Thread.run(Thread.java:840)",
    "---",
    "2026-09-30 12:00:01 ERROR c.a.p.PayoutLedger - settlement failed",
    "---",
    "Coroutines dump 2026/09/30 12:00:01",
    "",
    "Coroutine \"coroutine#2\":DeferredCoroutine{Active}@1b68b9a4, state: SUSPENDED",
    "\tat com.acme.payouts.PayoutLedger.settle(PayoutLedger.java:42)",
    "---",
    "java.lang.IllegalStateException",
    "\tat java.lang.Thread.run(Thread.java:840)",
    "",
).joinToString("\n")
