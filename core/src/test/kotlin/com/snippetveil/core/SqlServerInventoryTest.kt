package com.snippetveil.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **Every row of SQL Server's inventory has a fixture exercising it, and the discriminator's rule is
 * enforced rather than remembered.**
 *
 * The first claim is `PlanInventoryTest`'s and `MysqlInventoryTest`'s, for the same reason: a closed
 * field set is only as good as the rows in it, and **a row nothing exercises is a row nobody has
 * checked reads.** Its first reader would otherwise be a user whose plan refuses — or, worse, one
 * whose plan is read under a treatment that was never run.
 *
 * The second is this engine's own. Reading a field's *siblings* to decide how to read it is admitted
 * only where every branch replaces what it finds, and that is asserted here by watching a
 * discriminator with a preserving branch fail to be built at all.
 */
class SqlServerInventoryTest {

    /** **Every Showplan XML field is written in a fixture, and the fixture reads.** */
    @Test
    fun `every Showplan XML field is exercised by a fixture that reads`() {
        val missing = flattenedFieldsOf(SQLSERVER_SHOWPLAN_FIELDS).keys
            .filterNot { it.writtenIn(EXHAUSTIVE_SQLSERVER_XML_PLAN) }

        assertEquals(emptyList<String>(), missing, "inventory rows with no fixture exercising them")
        assertTrue(
            parsePlan(EXHAUSTIVE_SQLSERVER_XML_PLAN) is PlanReading.Read,
            "the fixture carrying every field does not read: ${parsePlan(EXHAUSTIVE_SQLSERVER_XML_PLAN)}",
        )
    }

    /**
     * **The fixture's names, measurements, facts and masks each come out as their row says** —
     * asserted over the output rather than over the reading, because *the field was walked* and *the
     * field was treated* are two different claims.
     */
    @Test
    fun `the fixture's names, measurements, facts and masks come out as their rows say`() {
        val text = anonymizedText(EXHAUSTIVE_SQLSERVER_XML_PLAN)

        assertTrue(
            Regex("""Table="\[table\d+]"""").containsMatchIn(text),
            "a bracketed name did not come out as a placeholder inside its brackets:\n$text",
        )
        assertTrue("""EstimateIO="0.003125"""" in text, "a measurement was not preserved as printed:\n$text")
        assertTrue("""PhysicalOp="Nested Loops"""" in text, "an engine fact did not survive its shape:\n$text")
        assertTrue(
            Regex("""QueryHash="str\d+"""").containsMatchIn(text),
            "a plan hash did not come out of the redacted-literal family:\n$text",
        )
    }

    /**
     * **A discriminator whose branches do not both anonymize cannot be built.**
     *
     * The rule is that reading a sibling's presence is admissible **only when every branch replaces
     * what it finds**, and a rule enforced in a constructor is one a later row cannot forget. The
     * assertion is the failure: a branch that preserves — an engine fact, a measurement — is refused
     * where two anonymizing branches are accepted.
     */
    @Test
    fun `a discriminator with a preserving branch is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            PlanTreatment.Discriminated(
                by = "Table",
                present = PlanTreatment.Name(SymbolRole.COLUMN),
                absent = PlanTreatment.Fact(PlanShapes.ENGINE_ENUM),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlanTreatment.Discriminated(
                by = "Table",
                present = PlanTreatment.Measured,
                absent = PlanTreatment.Deployment,
            )
        }

        PlanTreatment.Discriminated(
            by = "Table",
            present = PlanTreatment.Name(SymbolRole.COLUMN),
            absent = PlanTreatment.Deployment,
        )
    }
}

/**
 * Whether this inventory label is written in [document] — as an element, or as an attribute.
 *
 * The element spellings are checked with the character that ends the tag name, so that `Top` is not
 * found by `TopExpression` and a row can only be reported as exercised by a fixture that actually
 * writes it.
 */
private fun String.writtenIn(document: String): Boolean =
    "<$this " in document || "<$this>" in document || "<$this/" in document || "$this=\"" in document
