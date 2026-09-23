package com.snippetveil.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **Every field in the inventory has a treatment class and a fixture exercising it.**
 *
 * That is the claim the field closure rests on. A closed field set is only as good as the rows in
 * it: a row nothing exercises is a row nobody has checked reads, and its first reader is then a user
 * whose plan refuses — or, worse, one whose plan is read under a treatment that was never run.
 *
 * So the fixtures here carry **every** label, and the test is two halves: the label is in the
 * fixture, and the fixture reads. A plan that reads is a plan in which every one of its fields went
 * through the treatment its row names without refusing.
 */
class PlanInventoryTest {

    /** **Every structured field takes a treatment class**, which is what having a row means. */
    @Test
    fun `every structured field has a treatment class`() {
        val inventory = flattenedFieldsOf(POSTGRES_QUERY_FIELDS)

        assertTrue(inventory.size > 100, "the inventory is suspiciously small: ${inventory.size} rows")
        assertEquals(emptyList<String>(), inventory.filterValues { it !is PlanTreatment }.keys.toList())
    }

    /** **Every structured field is written in a fixture, and the fixture reads.** */
    @Test
    fun `every structured field is exercised by a fixture that reads`() {
        val missing = flattenedFieldsOf(POSTGRES_QUERY_FIELDS).keys
            .filterNot { "<${xmlTagOf(it)}>" in EXHAUSTIVE_XML_PLAN }

        assertEquals(emptyList<String>(), missing, "inventory rows with no fixture exercising them")
        assertTrue(
            parsePlan(EXHAUSTIVE_XML_PLAN) is PlanReading.Read,
            "the fixture carrying every field does not read:\n${parsePlan(EXHAUSTIVE_XML_PLAN)}",
        )
    }

    /** **Every text field is written in a fixture, and the fixture reads.** */
    @Test
    fun `every text field is exercised by a fixture that reads`() {
        val rows = EXHAUSTIVE_TEXT_PLAN.lines().map { it.trim() }
        val missing = POSTGRES_TEXT_FIELDS.keys.filterNot { label ->
            rows.any { it.startsWith("$label:") || Regex("""${Regex.escape(label)} \d+:.*""").matches(it) }
        }

        assertEquals(emptyList<String>(), missing, "text inventory rows with no fixture exercising them")
        assertTrue(
            parsePlan(EXHAUSTIVE_TEXT_PLAN) is PlanReading.Read,
            "the fixture carrying every text field does not read",
        )
    }

    /**
     * **The fixture is read, and the treatments it runs are the ones its rows name** — asserted over
     * the output rather than over the reading, because *the field was walked* and *the field was
     * treated* are two different claims.
     *
     * A name comes out as a placeholder of its kind, a measurement comes out exactly as printed, an
     * engine fact survives its shape check, and a masked value comes out of the redacted-literal
     * family. Four claims, one per direction a treatment can go.
     */
    @Test
    fun `the fixture's names, measurements, facts and masks each come out as their class says`() {
        val text = anonymizedText(EXHAUSTIVE_XML_PLAN)

        assertTrue(
            Regex("""<Relation-Name>table\d+</Relation-Name>""").containsMatchIn(text),
            "a name did not come out as a placeholder of its kind:\n$text",
        )
        assertTrue("<Total-Cost>1</Total-Cost>" in text, "a measurement was not preserved as printed:\n$text")
        assertTrue("<Node-Type>Seq Scan</Node-Type>" in text, "an engine fact did not survive its shape:\n$text")
        assertTrue(
            Regex("""<Trigger-Name>str\d+</Trigger-Name>""").containsMatchIn(text),
            "a masked object identifier did not come out of the redacted-literal family:\n$text",
        )
    }

    /**
     * **The XML spelling table is a function**, so no two inventory labels collide on one tag.
     *
     * [xmlTagOf] is not invertible, and the whole reason one inventory can serve four formats is that
     * the known labels map onto distinct tags. Two that collided would leave one of them reachable
     * only by the other's treatment, silently.
     */
    @Test
    fun `no two inventory labels share one XML tag`() {
        val labels = flattenedFieldsOf(POSTGRES_QUERY_FIELDS).keys

        assertEquals(labels.size, POSTGRES_XML_LABELS.size, "two labels collided on one XML tag")
    }

    /**
     * **A row numbered by the engine is one inventory row, not a family of them.**
     *
     * A parallel text plan prints `Worker 0:`, `Worker 1:` and so on. They are one field wearing a
     * counter the engine put there, and an inventory that held a row per worker would refuse the
     * first plan with more workers than anybody wrote rows for.
     */
    @Test
    fun `a numbered text row reads as the row it is numbered from`() {
        assertEquals(PlanTreatment.Measured, treatmentOf("Worker 7"))
        assertEquals(null, treatmentOf("Worker Seven"), "a row nothing numbered was read as a numbered one")
    }
}
