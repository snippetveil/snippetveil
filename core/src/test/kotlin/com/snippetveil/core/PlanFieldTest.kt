package com.snippetveil.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **The treatment classes, one test each** — the classes every format's field inventory is written
 * against, asserted where they are wired to PostgreSQL's text format and directly where that format
 * has no field for one yet.
 *
 * The three that are asserted directly — the deployment identifier, the engine fact and the
 * whole-field mask — are not speculative: they are the classes the JSON, YAML and XML inventories
 * are written in, and each ships with the argument that decides it. Asserting them over a
 * hand-assembled plan is the honest way to state a rule this format has no printer for.
 */
class PlanFieldTest {

    /**
     * **Echoed query text is one redacted literal and is never parsed.**
     *
     * It is the user's own SQL, and parsing it would need exactly the dialect grammar this container
     * refuses to carry — the same grammar the ruling on injected SQL refused to claim about text
     * nothing parsed. One mask, whole, and nothing read out of it.
     */
    @Test
    fun `echoed query text is one redacted literal and is never parsed`() {
        val plan = """
            |Foreign Scan on billing.invoices  (cost=100.00..197.75 rows=3 width=4)
            |  Remote SQL: SELECT merchant_ref FROM billing.invoices WHERE state = 'settled'
            """.trimMargin()

        val onTheField = planIn(plan).occurrences.filterIsInstance<PlanOccurrence>()
            .filter { it.start > plan.indexOf("Remote SQL:") }

        assertEquals(1, onTheField.size, "the echoed query was read in pieces: ${onTheField.size} occurrences")
        assertTrue(onTheField.single().disposition is PlanDisposition.Mask, "the echoed query was not masked")

        val text = anonymizedText(plan)
        assertFalse("merchant_ref" in text, "a name was read out of the echoed query:\n$text")
        assertFalse("settled" in text, "a value was read out of the echoed query:\n$text")
        assertTrue(
            Regex("""Remote SQL: str\d+$""", RegexOption.MULTILINE).containsMatchIn(text),
            "the echoed query did not come out as one redacted literal:\n$text",
        )
    }

    /**
     * **A parameter value is a literal** — exactly one literal, or **one redacted literal** if it is
     * not exactly one.
     *
     * The second half is where the safety is. A value that does not read as one literal is a slot
     * this reader does not understand, and understanding it partially is how the part nobody looked
     * at leaves the machine.
     */
    @Test
    fun `a parameter value that is one literal is a literal, and anything else is redacted whole`() {
        val plan = """
            |Seq Scan on visits  (cost=0.00..1.00 rows=1 width=4)
            |  Query Parameters: $1 = 'acme-holdings', $2 = 42, $3 = 'x'::merchant_tier, $4 = NULL
            """.trimMargin()

        val text = anonymizedText(plan)

        assertFalse("acme-holdings" in text, "a parameter value reached the clipboard:\n$text")
        assertTrue("$2 = 42" in text, "a numeric parameter was not preserved as printed:\n$text")
        assertTrue("$4 = NULL" in text, "a null parameter was not preserved as written:\n$text")
        assertFalse("merchant_tier" in text, "a value that is not one literal was read in pieces:\n$text")
        assertTrue(
            Regex("""\$3 = str\d+""").containsMatchIn(text),
            "a parameter value that is not exactly one literal was not redacted whole:\n$text",
        )
    }

    /**
     * **A parameter list whose shape this reader does not recognise is one redacted literal, whole.**
     *
     * The rule's teeth are here rather than in the happy path. A walk that looked for the parts it
     * knew and stepped over the rest would emit *nothing* for a list from a release that prints one
     * differently — and emitting nothing means the values reach the clipboard verbatim, which is the
     * residual running toward preservation in the one field whose rule is *or one redacted literal*.
     */
    @Test
    fun `a parameter list whose shape is unrecognised is redacted whole`() {
        val text = anonymizedText(
            """
            |Seq Scan on visits  (cost=0.00..1.00 rows=1 width=4)
            |  Query Parameters: 'acme-holdings', 42
            """.trimMargin(),
        )

        assertFalse("acme-holdings" in text, "a parameter list nothing recognised was passed through:\n$text")
        assertTrue(
            Regex("""Query Parameters: str\d+$""", RegexOption.MULTILINE).containsMatchIn(text),
            "the unrecognised parameter list was not redacted whole:\n$text",
        )
    }

    /**
     * **An identifying number is masked, and two printings of one share a token.**
     *
     * PostgreSQL's query id is a hash of the statement, so preserving it would let a receiver
     * **confirm a guessed query** — which defeats the rule that redacts the query text rather than
     * stating a gap beside it. Sharing is what keeps the mask from costing more than it buys: one
     * hash at two nodes still reads as one hash at two nodes.
     */
    @Test
    fun `an identifying hash is masked, and two occurrences of it share one token`() {
        val text = anonymizedText(
            """
            |Nested Loop  (cost=0.29..16.97 rows=2 width=68)
            |  Query Identifier: 8034093347371450093
            |  ->  Seq Scan on visits  (cost=0.00..1.00 rows=1 width=4)
            |        Query Identifier: 8034093347371450093
            """.trimMargin(),
        )

        val masks = Regex("""Query Identifier: (str\d+)""").findAll(text).map { it.groupValues[1] }.toList()

        assertFalse("8034093347371450093" in text, "the query hash reached the clipboard:\n$text")
        assertEquals(2, masks.size, "the identifier was not masked at both nodes:\n$text")
        assertEquals(1, masks.toSet().size, "one hash printed twice took two placeholders:\n$text")
    }

    /**
     * **A deployment identifier is masked, and it does not join name identity.**
     *
     * Hostnames, database users and service names are precisely the organization-identifying strings
     * this product exists to replace. They are also **not names**: a database user spelled like a
     * schema is not that schema, and a placeholder shared between the two would assert a
     * relationship that does not exist — in the one direction a reader would believe.
     */
    @Test
    fun `a hostname is masked and does not share a placeholder with a schema of the same spelling`() {
        val text = "Seq Scan on billing.invoices  (cost=0.00..1.00 rows=1 width=4)\n  Server: billing"
        val schema = text.indexOf("billing")
        val host = text.lastIndexOf("billing")

        val result = anonymize(
            SnippetPlan(
                text,
                listOf(
                    PlanOccurrence(
                        schema,
                        schema + "billing".length,
                        PlanDisposition.Anonymize(SymbolRole.SCHEMA, PlanKeys.named(SymbolRole.SCHEMA, "billing")),
                    ),
                ) + PlanTreatments.deployment(PlanSlot(text, host, text.length)),
            ),
            AnonymizationSettings.DEFAULTS,
            LedgerSnapshot.EMPTY,
        )

        val schemaPlaceholder = result.names.single { it.kind == MappedKind.SCHEMA }.placeholder
        val hostPlaceholder = result.names.single { it.kind == MappedKind.LITERAL }.placeholder

        assertFalse("Server: billing" in result.text, "the hostname reached the clipboard:\n${result.text}")
        assertNotEquals(schemaPlaceholder, hostPlaceholder, "a hostname joined the identity of a schema")
        assertTrue(
            hostPlaceholder.orEmpty().startsWith("str"),
            "a masked value did not draw on the redacted-literal family: $hostPlaceholder",
        )
    }

    /**
     * **An engine fact is preserved behind a shape check, and an off-shape value is masked rather
     * than refused.**
     *
     * Once a slot is known to be engine-owned, masking a surprise leaks nothing — while refusing
     * would break every plan from a vendor release that changed a version string's shape, which is a
     * compatibility event this container is expected to survive.
     */
    @Test
    fun `a version matching its shape is preserved and an off-shape value is masked`() {
        assertEquals("16.2", factIn("16.2"), "a version matching its shape did not survive")
        assertTrue(
            Regex("""^str\d+$""").matches(factIn("16beta1 (Debian)")),
            "an off-shape engine fact was not masked: ${factIn("16beta1 (Debian)")}",
        )
    }

    /**
     * **Row counts, timings and buffers survive byte-identical — and nothing fires for them.**
     *
     * A measured field produces no occurrence at all, which is what makes the second half true by
     * construction rather than by a rule somebody keeps: there is nothing for a count or a notice to
     * be computed from. The numbers are a **disclosure**, stated and not closed — the recourse is at
     * the engine, and there is deliberately no mask toggle, no bucketing, no notice and no count.
     */
    @Test
    fun `measured quantities survive byte-identical, with no notice and no count`() {
        val bare = """
            |Seq Scan on visits  (cost=0.00..35.50 rows=2550 width=244)
            """.trimMargin()
        val measured = """
            |Seq Scan on visits  (cost=0.00..35.50 rows=2550 width=244) (actual time=0.028..0.031 rows=1 loops=1)
            |  Buffers: shared hit=3 read=12 dirtied=1
            |  I/O Timings: shared read=0.042
            |  Rows Removed by Filter: 17
            |Planning Time: 0.123 ms
            |Execution Time: 0.456 ms
            """.trimMargin()

        val result = anonymize(planIn(measured), AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        for (line in measured.lines().drop(1)) {
            assertTrue(line in result.text, "a measurement did not survive byte-identical: `$line`\n${result.text}")
        }
        assertEquals(emptyList<String>(), result.fidelityNotices(), "a measurement fired a fidelity notice")

        val bareCounts = anonymize(planIn(bare), AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY).counts
        assertEquals(bareCounts.replaced, result.counts.replaced, "a measurement was counted as a replaced name")
        assertEquals(bareCounts.preserved, result.counts.preserved, "a measurement was counted as a preserved name")
        assertTrue(result.names.none { it.kind == MappedKind.LITERAL }, "a measurement became a row in the table")
    }

    /**
     * **Every mask draws on the existing redacted-literal family, and no class has a family of its
     * own.**
     *
     * A family per class would put a class name — *hostname*, *queryhash* — into the output grammar
     * a model reads, forever, in exchange for nothing a `str` row in the preview does not already
     * say. Asserted over every placeholder a plan produces rather than over one, because the claim
     * is about the whole namespace.
     */
    @Test
    fun `masks draw on the existing redacted-literal family and no new family exists`() {
        val result = anonymize(
            planIn(
                """
                |Seq Scan on billing.invoices i  (cost=0.00..1.00 rows=1 width=4)
                |  Filter: ((i.state)::text = 'settled'::text)
                |  Query Identifier: 8034093347371450093
                |  Remote SQL: SELECT 1
                """.trimMargin(),
            ),
            AnonymizationSettings.DEFAULTS,
            LedgerSnapshot.EMPTY,
        )

        val families = result.names.mapNotNull { it.placeholder }.map { it.trimEnd { character -> character.isDigit() } }

        assertTrue("str" in families, "nothing was masked, so this asserts nothing")
        assertEquals(
            setOf("table", "col", "schema", "str"),
            families.toSet(),
            "a plan handed out a placeholder from a family that is not one of the four this product has",
        )
        for (row in result.names.filter { it.placeholder.orEmpty().startsWith("str") }) {
            assertEquals(MappedKind.LITERAL, row.kind, "a masked value was filed as something other than a literal")
            assertEquals(null, row.key, "a masked value carries a key, so the preview would offer a tick on it")
            assertEquals(Renaming.NONE, row.renaming, "a masked value was offered for renaming")
        }
    }
}

/** What the engine-fact class makes of [value] in a slot of its own, read back out of the output. */
private fun factIn(value: String): String {
    val text = "Server Version: $value"
    val slot = PlanSlot(text, text.indexOf(':') + 1, text.length)
    val result = anonymize(
        SnippetPlan(text, PlanTreatments.engineFact(slot, PlanShapes.VERSION)),
        AnonymizationSettings.DEFAULTS,
        LedgerSnapshot.EMPTY,
    )
    return result.text.substringAfter(": ")
}
