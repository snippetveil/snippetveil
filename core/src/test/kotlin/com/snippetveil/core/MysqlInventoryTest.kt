package com.snippetveil.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **Every row of MySQL's inventories, and every sentence of its template set, has a fixture
 * exercising it.**
 *
 * It is the same claim `PlanInventoryTest` makes about PostgreSQL's rows, for the same reason: a
 * closed field set is only as good as the rows in it, and **a row nothing exercises is a row nobody
 * has checked reads.** Its first reader would otherwise be a user whose plan refuses — or, worse, one
 * whose plan is read under a treatment that was never run.
 *
 * The template set is held to it too, and that is not a formality: a template naming a field the
 * inventory does not hold would refuse every plan it matched, and a template whose spelling nothing
 * ever instantiates is a sentence this product only *believes* MySQL prints.
 */
class MysqlInventoryTest {

    /** **Every version 2 field is written in a fixture, and the fixture reads.** */
    @Test
    fun `every version 2 field is exercised by a fixture that reads`() {
        val missing = flattenedFieldsOf(MYSQL_V2_QUERY_FIELDS).keys
            .filterNot { "\"$it\":" in EXHAUSTIVE_MYSQL_V2_PLAN }

        assertEquals(emptyList<String>(), missing, "version 2 inventory rows with no fixture exercising them")
        assertTrue(
            parsePlan(EXHAUSTIVE_MYSQL_V2_PLAN) is PlanReading.Read,
            "the fixture carrying every version 2 field does not read: ${parsePlan(EXHAUSTIVE_MYSQL_V2_PLAN)}",
        )
    }

    /** **Every version 1 field is written in a fixture, and the fixture reads.** */
    @Test
    fun `every version 1 field is exercised by a fixture that reads`() {
        val missing = flattenedFieldsOf(MYSQL_V1_QUERY_FIELDS).keys
            .filterNot { "\"$it\":" in EXHAUSTIVE_MYSQL_V1_PLAN }

        assertEquals(emptyList<String>(), missing, "version 1 inventory rows with no fixture exercising them")
        assertTrue(
            parsePlan(EXHAUSTIVE_MYSQL_V1_PLAN) is PlanReading.Read,
            "the fixture carrying every version 1 field does not read: ${parsePlan(EXHAUSTIVE_MYSQL_V1_PLAN)}",
        )
    }

    /**
     * **Every template names only fields the inventory holds.**
     *
     * A template naming a field with no row would refuse every line it produced — the cross-check
     * would match and the part would then have no treatment to be read under — which is a whole node
     * shape this product silently could not read.
     */
    @Test
    fun `every template fills in a field the inventory holds`() {
        val fields = flattenedFieldsOf(MYSQL_V2_QUERY_FIELDS)
        val unknown = MYSQL_V2_OPERATIONS
            .flatMap { it.parts }
            .filterIsInstance<PlanTemplatePart.Field>()
            .map { it.label }
            .filterNot { it in fields }

        assertEquals(emptyList<String>(), unknown.distinct(), "templates filling in fields no row covers")
    }

    /**
     * **Every template is instantiated by the exhaustive fixture**, so no sentence in the set is one
     * this product merely believes MySQL prints.
     */
    @Test
    fun `every template is exercised by the exhaustive fixture`() {
        val missing = MYSQL_V2_OPERATIONS.filterNot { template ->
            val opening = template.parts.first()
            opening is PlanTemplatePart.Written && "\"operation\": \"${opening.text}" in EXHAUSTIVE_MYSQL_V2_PLAN
        }

        assertEquals(
            emptyList<String>(),
            missing.map { (it.parts.first() as? PlanTemplatePart.Written)?.text ?: "" },
            "templates with no fixture exercising them",
        )
    }

    /**
     * **The exhaustive fixtures' names, measurements, facts and masks each come out as their row
     * says** — asserted over the output rather than over the reading, because *the field was walked*
     * and *the field was treated* are two different claims.
     */
    @Test
    fun `the exhaustive fixtures' names, measurements, facts and masks come out as their rows say`() {
        val v2 = anonymizedText(EXHAUSTIVE_MYSQL_V2_PLAN)
        val v1 = anonymizedText(EXHAUSTIVE_MYSQL_V1_PLAN)

        assertTrue(
            Regex(""""table_name": "table\d+"""").containsMatchIn(v2),
            "a name did not come out as a placeholder of its kind:\n$v2",
        )
        assertTrue("\"heap_usage_bytes\": 4096" in v2, "a measurement was not preserved as printed:\n$v2")
        assertTrue("\"join_algorithm\": \"nested_loop\"" in v2, "an engine fact did not survive its shape:\n$v2")
        assertTrue(
            Regex(""""hash_condition": "str\d+"""").containsMatchIn(v2),
            "a rendered condition was not masked whole:\n$v2",
        )
        assertTrue("\"access_type\": \"ref\"" in v1, "an unescaped enum did not survive:\n$v1")
        assertTrue(
            Regex(""""partitions": \[\s*"str\d+"""").containsMatchIn(v1),
            "a deployment object was not masked:\n$v1",
        )
    }
}
