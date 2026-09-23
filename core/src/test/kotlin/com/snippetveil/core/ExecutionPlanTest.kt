package com.snippetveil.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **What [parsePlan] reads, and what it refuses** — the container with no PSI anywhere in it.
 *
 * Every assertion here is about text in and a [SnippetPlan] out, which is the whole point of the
 * parse living in `:core`: there is no fixture, no platform and no index behind any of it.
 */
class ExecutionPlanTest {

    /**
     * **A plan pasted from its first line parses, and its text comes back verbatim.**
     *
     * The verbatim-text invariant is the one every surface downstream reads: the preview shows the
     * plan's own text with placeholders spliced into it, and the round trip is defined against it. A
     * parse that edited as it read would have taken that away before anything else ran.
     */
    @Test
    fun `a plan pasted from its first line parses, carrying its text verbatim`() {
        val plan = planIn(JOIN)

        assertEquals(JOIN, plan.text)
        assertTrue(plan.occurrences.isNotEmpty(), "nothing in the plan was reported as a name")
        assertTrue(
            plan.occurrences.all { it is PlanOccurrence },
            "something other than a plan token was reported: ${plan.occurrences.map { it::class.simpleName }}",
        )
    }

    /**
     * **A subtree pasted from the middle refuses, and so does a paste carrying the query.**
     *
     * Both are the mistake a user actually makes — a drag-select of the interesting branch, and a
     * copy that starts at the `EXPLAIN` — and recognition is anchored at the head precisely so that
     * neither is read as a plan whose first line happens to be missing.
     */
    @Test
    fun `a mid-tree paste and a query above the plan both refuse`() {
        val subtree = JOIN.lines().drop(1).joinToString("\n")
        assertRefused(subtree)
        assertRefused("SELECT v.id FROM visits v;\n$JOIN")
    }

    /**
     * **Recognition needs both literals**, which is what keeps it from being a scan for something
     * `EXPLAIN`-looking: prose beginning with a node label is not a plan, and a line that merely
     * mentions a cost is not one either.
     */
    @Test
    fun `text that only looks like a plan refuses`() {
        assertRefused("Sort out the merchant ledger before the invoice run")
        assertRefused("the query above  (cost=0.00..1.00 rows=1 width=4) is the one to look at")
        assertRefused("")
    }

    /**
     * **`psql`'s own table decoration is not `EXPLAIN`'s output**, so a paste carrying it does not
     * begin at the plan's first line. Stated as a test rather than only in prose because it is the
     * shape a user is most likely to arrive with, and refusing it is a decision rather than a gap.
     */
    @Test
    fun `a psql table around the plan refuses`() {
        assertRefused(
            """
            |                       QUERY PLAN
            |------------------------------------------------------
            | Seq Scan on visits  (cost=0.00..35.50 rows=2550 width=244)
            |(1 row)
            """.trimMargin(),
        )
    }

    /**
     * **Every token a plan reports is tagged [SourceLanguage.PLAN], and none is tagged SQL.**
     *
     * A plan's expression fields are the engine's *printer* language rather than its input language,
     * and tagging one of these SQL would assert that a SQL parser read it — which is exactly what
     * the ruling on injected SQL refused to claim about text nothing parsed.
     */
    @Test
    fun `plan tokens are tagged PLAN and never SQL`() {
        val languages = planIn(JOIN).occurrences.map { it.language }.toSet()

        assertEquals(setOf(SourceLanguage.PLAN), languages)
    }

    /**
     * **The verdict has nowhere to put the input**, asserted over the type rather than over one
     * message: with no `String` anywhere in it, *no refusal quotes the plan* is a property of the
     * shape instead of a discipline the message layer has to keep.
     */
    @Test
    fun `the refusal verdict carries no String field`() {
        val verdicts = PlanReading::class.java.declaredClasses
            .filter { it != PlanReading.Read::class.java }

        assertTrue(verdicts.isNotEmpty(), "there is no verdict type, so this asserts nothing")
        for (verdict in verdicts) {
            for (field in verdict.declaredFields) {
                assertNotEquals(
                    String::class.java,
                    field.type,
                    "${verdict.simpleName}.${field.name} could hold the input the refusal must not quote",
                )
            }
            for (constructor in verdict.declaredConstructors) {
                assertFalse(
                    constructor.parameterTypes.any { it == String::class.java },
                    "${verdict.simpleName} takes a String, so a refusal could be built around the input",
                )
            }
        }
    }

    /** The relation, its schema, its alias and the index are each reported, and each as itself. */
    @Test
    fun `a node line reports its index, its schema, its relation and its alias`() {
        val kinds = namesIn(JOIN)

        assertEquals(SymbolRole.INDEX, kinds["visits_pkey"])
        assertEquals(SymbolRole.TABLE, kinds["visits"])
        assertEquals(SymbolRole.SCHEMA, kinds["billing"])
        assertEquals(SymbolRole.TABLE, kinds["invoices"])
        assertEquals(SymbolRole.TABLE, kinds["v"])
        assertEquals(SymbolRole.COLUMN, kinds["visit_id"])
    }

    /** `Bitmap Index Scan on …` is the one node whose `on` names an access path rather than a rowset. */
    @Test
    fun `a bitmap index scan names an index where every other node names a relation`() {
        val kinds = namesIn(
            """
            |Bitmap Heap Scan on visits  (cost=4.20..8.30 rows=2 width=36)
            |  Recheck Cond: (status = 'open')
            |  ->  Bitmap Index Scan on visits_status_idx  (cost=0.00..4.20 rows=2 width=0)
            """.trimMargin(),
        )

        assertEquals(SymbolRole.TABLE, kinds["visits"])
        assertEquals(SymbolRole.INDEX, kinds["visits_status_idx"])
        assertEquals(SymbolRole.COLUMN, kinds["status"])
    }

    /**
     * **The plan's own vocabulary is slotted and emitted as written** — which is what gives the
     * `preserved` half of the counts a population, and what keeps a cast's type name out of the
     * table.
     */
    @Test
    fun `a type after a cast and a function the engine printed are preserved`() {
        val preserved = preservedIn(
            """
            |Aggregate  (cost=1.00..1.01 rows=1 width=8)
            |  Output: count(v.id)
            |  ->  Seq Scan on visits v  (cost=0.00..1.00 rows=1 width=4)
            |        Filter: ((status)::text = 'open'::text)
            """.trimMargin(),
        )

        assertEquals(setOf("count", "text"), preserved)
    }

    /**
     * **A quoted name keeps its delimiters and reports the name inside them.** A delimited identifier
     * is case-sensitive where a bare one is folded, so the quotes are semantics rather than
     * decoration — and there is no PSI here to spell that any way but in text.
     */
    @Test
    fun `a delimited name reports the range inside its delimiters`() {
        val plan = planIn("""Seq Scan on "Customers"  (cost=0.00..1.00 rows=1 width=4)""")
        val name = plan.occurrences.filterIsInstance<PlanOccurrence>().single()

        assertEquals("\"Customers\"", plan.text.substring(name.start, name.end))
        assertEquals("Customers", plan.text.substring(name.nameStart, name.nameEnd))
    }

    /** A quiet field is counters rather than names, and nothing on it is reported. */
    @Test
    fun `the measurement fields report no names`() {
        val names = namesIn(JOIN).keys

        assertFalse("ms" in names, "a timing's unit was read as a name")
        assertFalse("Planning" in names, "a measurement field was read for names")
    }

    /** The same text twice is the same reading, because nothing here holds state between calls. */
    @Test
    fun `the parse is pure`() {
        val first = planIn(JOIN)
        val second = planIn(JOIN)

        assertEquals(first.text, second.text)
        assertEquals(
            first.occurrences.map { it.start to it.end },
            second.occurrences.map { it.start to it.end },
        )
        assertSame(SourceLanguage.PLAN, second.occurrences.first().language)
    }

    private fun assertRefused(text: String) {
        assertTrue(
            parsePlan(text) is PlanReading.Unreadable,
            "this was read as a plan, and is not one:\n$text",
        )
    }
}

/**
 * A plan with two relations, one of them schema-qualified and both aliased, an index, a cast, a
 * literal and the two timing fields — which is what a copied `EXPLAIN ANALYZE` looks like, and the
 * shape every rule in this file has something to say about.
 */
internal val JOIN = """
    |Nested Loop  (cost=0.29..16.97 rows=2 width=68) (actual time=0.028..0.031 rows=1 loops=1)
    |  ->  Index Scan using visits_pkey on visits v  (cost=0.29..8.30 rows=1 width=36)
    |        Index Cond: (id = 42)
    |        Filter: ((status)::text = 'open'::text)
    |  ->  Seq Scan on billing.invoices i  (cost=0.00..8.65 rows=1 width=32)
    |        Filter: (i.visit_id = v.id)
    |Planning Time: 0.123 ms
    |Execution Time: 0.456 ms
""".trimMargin()

/** The plan [parsePlan] read, or a failure saying the text was refused. */
internal fun planIn(text: String): SnippetPlan = when (val reading = parsePlan(text)) {
    is PlanReading.Read -> reading.plan
    PlanReading.Unreadable -> throw AssertionError("this was refused, and is a plan:\n$text")
}

/** The name written at each anonymized token, and the kind it was read as. */
internal fun namesIn(text: String): Map<String, SymbolRole> {
    val plan = planIn(text)
    return plan.occurrences.filterIsInstance<PlanOccurrence>()
        .mapNotNull { occurrence ->
            (occurrence.disposition as? PlanDisposition.Anonymize)?.let {
                plan.text.substring(occurrence.nameStart, occurrence.nameEnd) to it.kind
            }
        }
        .toMap()
}

/** Every token the plan slotted and chose to emit as written. */
internal fun preservedIn(text: String): Set<String> {
    val plan = planIn(text)
    return plan.occurrences.filterIsInstance<PlanOccurrence>()
        .filter { it.disposition == PlanDisposition.Preserve }
        .mapTo(mutableSetOf()) { plan.text.substring(it.start, it.end) }
}
