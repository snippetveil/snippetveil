package com.snippetveil.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **What the engine makes of a plan** — the identity rules, the three dispositions, and the one
 * thing a plan is pasted for.
 *
 * The parse is asserted next door, in `ExecutionPlanTest`. This is the other half: a plan is a
 * [SnippetPlan] like any other by the time [anonymize] sees it, so what is under test here is that
 * the engine's own rules — one counter, one key, one row, one ledger — reach it unchanged.
 */
class PlanNamingTest {

    /**
     * **The names go and the numbers stay**, which is the whole bargain of anonymizing a plan: it is
     * pasted for its costs, its row estimates and its timings, and a tool that moved one of those
     * would have destroyed the reason it was sent.
     */
    @Test
    fun `a plan loses its names and keeps every number exactly as printed`() {
        val result = anonymize(planIn(JOIN), AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        for (measurement in MEASUREMENTS) {
            assertTrue(measurement in result.text, "`$measurement` did not survive verbatim:\n${result.text}")
        }
        for (name in listOf("visits", "invoices", "billing", "visits_pkey", "visit_id", "status")) {
            assertFalse(
                Regex("""\b${Regex.escape(name)}\b""").containsMatchIn(result.text),
                "`$name` is still in the anonymized plan:\n${result.text}",
            )
        }
    }

    /**
     * **An index and a relation in one plan take different kinds**, which is the whole of why `idx`
     * exists: folding an index into `table` would print `Index Scan using table5 on table2` and
     * assert two rowsets where there is one.
     */
    @Test
    fun `an index name and a relation name in one plan take different kinds`() {
        val rows = rowsIn(JOIN)

        assertEquals(MappedKind.INDEX, rows["visits_pkey"]?.kind)
        assertEquals(MappedKind.TABLE, rows["visits"]?.kind)
        assertNotEquals(rows["visits_pkey"]?.placeholder, rows["visits"]?.placeholder)
        assertTrue(
            rows["visits_pkey"]?.placeholder.orEmpty().startsWith("idx"),
            "an index rendered as ${rows["visits_pkey"]?.placeholder}",
        )
    }

    /**
     * **A qualified name is several symbols and not one.** Same-schema and different-schema is a
     * fact a reader needs, and one placeholder standing for `billing.invoices` would destroy it.
     */
    @Test
    fun `a qualified name yields distinct schema and table placeholders`() {
        val rows = rowsIn(JOIN)
        val schema = checkNotNull(rows["billing"]) { "the schema was not reported" }
        val table = checkNotNull(rows["invoices"]) { "the relation was not reported" }

        assertEquals(MappedKind.SCHEMA, schema.kind)
        assertEquals(MappedKind.TABLE, table.kind)
        assertNotEquals(schema.placeholder, table.placeholder)
        assertTrue(
            "${schema.placeholder}.${table.placeholder}" in anonymizedText(JOIN),
            "the qualified name did not come out as two placeholders:\n${anonymizedText(JOIN)}",
        )
    }

    /**
     * **`visits` and `visits_1` are different names, and nothing strips the suffix.** The suffix is
     * an engine's own way of telling two scans of one relation apart — but stripping it would be
     * text deciding identity by a per-engine rule, and a user whose relation is genuinely called
     * `visits_1` defeats it.
     */
    @Test
    fun `visits and visits_1 in one plan take different placeholders`() {
        val rows = rowsIn(
            """
            |Hash Join  (cost=1.05..2.10 rows=1 width=8)
            |  Hash Cond: (visits.id = visits_1.parent_id)
            |  ->  Seq Scan on visits  (cost=0.00..1.00 rows=1 width=4)
            |  ->  Seq Scan on visits visits_1  (cost=0.00..1.00 rows=1 width=4)
            """.trimMargin(),
        )

        assertNotEquals(
            rows["visits"]?.placeholder,
            rows["visits_1"]?.placeholder,
            "`visits` and `visits_1` were read as one name",
        )
    }

    /**
     * **An alias is the plan's own name, and every use of it is the same symbol.** The alias in
     * `Seq Scan on billing.invoices i` and the `i` in the filter below it have to agree, or the
     * anonymized plan says two different things about one rowset.
     */
    @Test
    fun `an alias and the qualifiers that use it share one placeholder`() {
        val text = anonymizedText(JOIN)
        val rows = rowsIn(JOIN)
        val alias = checkNotNull(rows["i"]?.placeholder) { "the alias was not reported" }

        assertEquals(
            2,
            Regex("""\b${Regex.escape(alias)}\b""").findAll(text).count(),
            "the alias and its use did not render alike:\n$text",
        )
    }

    /**
     * **Every plan key is ephemeral**, and that is asserted positively rather than left to the
     * absence of a row: the same plan pasted twice mints a different set of placeholders, because
     * nothing about it was written down and the counter moved on.
     *
     * A fidelity limit rather than a disclosure — the second paste is as anonymized as the first,
     * and what it costs is a conversation that can refer back to the first one's names.
     */
    @Test
    fun `two invocations over the same plan text mint different placeholders`() {
        val first = anonymize(planIn(JOIN), AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)
        val second = anonymize(planIn(JOIN), AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY + first.delta)

        assertTrue(first.mapping.isNotEmpty(), "the plan named nothing, so this asserts nothing")
        assertTrue(
            first.mapping.keys.intersect(second.mapping.keys).isEmpty(),
            "a placeholder came back on the second paste: ${first.mapping.keys.intersect(second.mapping.keys)}",
        )
        assertTrue(first.delta.placeholders.isEmpty(), "a plan name reached the persisted mapping")
        assertTrue(second.delta.placeholders.isEmpty(), "a plan name reached the persisted mapping")
    }

    /**
     * **A dropped occurrence advances no counter and writes no ledger row.**
     *
     * Nothing in the PostgreSQL text format drops, so this is the one place the third disposition is
     * exercised at all — and it is exercised because the alternative is an outcome that exists,
     * compiles, and has never been run. The sibling assertion below it is what makes the numbers
     * mean something: the same token anonymized *does* move the counter.
     */
    @Test
    fun `a dropped occurrence advances no counter, writes no row and leaves empty text`() {
        val text = "Seq Scan on visits  (cost=0.00..1.00 rows=1 width=4)"
        val at = text.indexOf("visits")
        val dropped = anonymize(
            SnippetPlan(text, listOf(PlanOccurrence(at, at + "visits".length, PlanDisposition.Drop))),
            AnonymizationSettings.DEFAULTS,
            LedgerSnapshot.EMPTY,
        )

        assertEquals("Seq Scan on   (cost=0.00..1.00 rows=1 width=4)", dropped.text)
        assertEquals(LedgerSnapshot.EMPTY.nextNumber, dropped.delta.nextNumber, "a dropped token drew a number")
        assertTrue(dropped.delta.placeholders.isEmpty(), "a dropped token was written down")
        assertTrue(dropped.names.isEmpty(), "a dropped token is a row in the table")
        assertEquals(0, dropped.counts.replaced)
        assertEquals(0, dropped.counts.preserved)

        val replaced = anonymize(
            SnippetPlan(
                text,
                listOf(
                    PlanOccurrence(
                        at,
                        at + "visits".length,
                        PlanDisposition.Anonymize(SymbolRole.TABLE, PlanKeys.named(SymbolRole.TABLE, "visits")),
                    ),
                ),
            ),
            AnonymizationSettings.DEFAULTS,
            LedgerSnapshot.EMPTY,
        )

        assertEquals("Seq Scan on table1  (cost=0.00..1.00 rows=1 width=4)", replaced.text)
        assertNotEquals(
            LedgerSnapshot.EMPTY.nextNumber,
            replaced.delta.nextNumber,
            "the counter does not move for a plan name either, so the drop assertion means nothing",
        )
    }

    /**
     * **The two numbers the plan's counts have a population for**, and what each is counting: a
     * distinct key on the replaced side, a distinct spelling on the preserved side.
     */
    @Test
    fun `the counts partition a plan into what was replaced and what was emitted as written`() {
        val counts = anonymize(planIn(JOIN), AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY).counts

        assertEquals(namesIn(JOIN).size, counts.replaced)
        assertEquals(preservedIn(JOIN).size, counts.preserved)
        assertEquals(0, counts.unknown, "a plan resolves nothing, so nothing in one can have failed to")
    }

    /**
     * **A preserved word is emitted exactly as written**, which is what the `preserved` number is a
     * claim about: `text` after a cast is the engine's word, and it comes out as itself.
     */
    @Test
    fun `a preserved token keeps its own text in the output`() {
        val text = anonymizedText(JOIN)

        assertTrue("::text" in text, "the cast's type name did not survive:\n$text")
    }

    /**
     * **A delimited name renders inside its delimiters**, and the delimiters survive: `"Customers"`
     * and `customers` name different relations in most dialects, so normalising one would rewrite
     * what the plan says.
     */
    @Test
    fun `a delimited name renders inside the delimiters`() {
        val quoted = anonymizedText("""Seq Scan on "Customers"  (cost=0.00..1.00 rows=1 width=4)""")

        assertEquals("""Seq Scan on "table1"  (cost=0.00..1.00 rows=1 width=4)""", quoted)
    }

    /**
     * **The per-invocation preserve reaches a plan row like any other keyed row.** It travels as a
     * key, so the tick the preview offers means the same thing here that it means over a snippet —
     * and the row stays in the table, because a row that vanished when ticked could not be unticked.
     */
    @Test
    fun `a ticked plan row is emitted as written and stays in the table`() {
        val plan = planIn(JOIN)
        val relation = checkNotNull(rowsIn(JOIN)["visits"]?.key) { "the relation has no key to tick" }
        val result = anonymize(
            plan,
            AnonymizationSettings(preservedSymbols = setOf(relation)),
            LedgerSnapshot.EMPTY,
        )

        assertTrue(
            Regex("""\bvisits\b""").containsMatchIn(result.text),
            "the ticked relation was replaced anyway:\n${result.text}",
        )
        assertEquals(
            null,
            result.names.single { it.original == "visits" }.placeholder,
            "a preserved row kept a placeholder",
        )
    }
}

/** The rows one plan produced, by the name each stands for. */
private fun rowsIn(text: String): Map<String, MappedName> =
    anonymize(planIn(text), AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)
        .names
        .associateBy { it.original }

/**
 * The parts of [JOIN] a plan is pasted for. Every one of them has to come back byte-identical — a
 * cost, a row estimate, a width, an actual time and a loop count.
 */
private val MEASUREMENTS = listOf(
    "(cost=0.29..16.97 rows=2 width=68)",
    "(actual time=0.028..0.031 rows=1 loops=1)",
    "(cost=0.00..8.65 rows=1 width=32)",
    "Planning Time: 0.123 ms",
    "Execution Time: 0.456 ms",
)
