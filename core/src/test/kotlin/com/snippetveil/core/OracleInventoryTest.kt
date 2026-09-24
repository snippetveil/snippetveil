package com.snippetveil.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **Every row of Oracle's inventories has a fixture exercising it, and the value discriminator's rule
 * is enforced rather than remembered.**
 *
 * The first claim is `PlanInventoryTest`'s, `MysqlInventoryTest`'s and `SqlServerInventoryTest`'s, for
 * the same reason: a closed field set is only as good as the rows in it, and **a row nothing exercises
 * is a row nobody has checked reads.** It matters more here than anywhere, because these rows were
 * written from a reading of the engine's output rather than from a capture taken in this repository —
 * so the least this product can do is run every one of them.
 *
 * The second is this engine's own. Reading a field's *value* to decide how to read another is admitted
 * only where no branch preserves what it was given unchecked, and that is asserted here by watching a
 * discriminator with a measuring branch fail to be built at all.
 */
class OracleInventoryTest {

    /** **Every SQL Monitor field is written in a fixture, and the fixture reads.** */
    @Test
    fun `every SQL Monitor field is exercised by a fixture that reads`() {
        val missing = flattenedFieldsOf(ORACLE_MONITOR_FIELDS).keys
            .filterNot { it == XML_CHARACTER_DATA || it.writtenIn(ORACLE_MONITOR_XML) }

        assertEquals(emptyList<String>(), missing, "inventory rows with no fixture exercising them")
        assertTrue(
            parsePlan(ORACLE_MONITOR_XML) is PlanReading.Read,
            "the fixture carrying every field does not read: ${parsePlan(ORACLE_MONITOR_XML)}",
        )
    }

    /**
     * **Every grid column is written in a fixture, and the fixtures read.**
     *
     * The columns are this format's field set, so an unexercised column is exactly what an
     * unexercised field is: a row whose first reader would be a user whose plan refuses. The two
     * fixtures together are what cover them, because one grid cannot carry both the estimated columns
     * and the actual ones.
     */
    @Test
    fun `every grid column is exercised by a fixture that reads`() {
        val written = listOf(ORACLE_GRID_PLAN, ORACLE_GRID_ACTUALS)
        val missing = ORACLE_GRID_COLUMNS.keys.filterNot { column ->
            written.any { Regex("""\|\s*${Regex.escape(column)}\s*\|""").containsMatchIn(it) }
        }

        assertEquals(emptyList<String>(), missing, "grid columns with no fixture exercising them")
        for (plan in written) {
            assertTrue(parsePlan(plan) is PlanReading.Read, "a grid fixture does not read: ${parsePlan(plan)}")
        }
    }

    /**
     * **A grid whose header carries a column no row holds refuses** — the field closure, spelled for a
     * format whose fields are columns.
     *
     * The parallel and partition columns are the live case rather than an invented one: they have no
     * row here deliberately, because no capture of them was taken, so a parallel plan refuses until a
     * capture and a row follow.
     */
    @Test
    fun `a grid carrying a column no row holds refuses`() {
        val parallel = ORACLE_GRID_PLAN
            .replace("| Cost (%CPU)|", "| PQ Distrib |")
            .replace("|     4   (0)|", "| HASH       |")
            .replace("|     1   (0)|", "| HASH       |")
            .replace("|     3   (0)|", "| HASH       |")
            .replace("|     2   (0)|", "| HASH       |")

        assertEquals(PlanReading.Unreadable, parsePlan(parallel), "a column with no row was read")
    }

    /**
     * **A value discriminator whose branch preserves what it was given unchecked cannot be built.**
     *
     * The rule is that a branch may replace what it finds, or preserve only what it has first checked
     * against a closed shape of its own — and a rule enforced in a constructor is one a later row
     * cannot forget. A measurement is the branch that fails: it preserves whatever it is handed,
     * unread, which is the document deciding by a value that something of the user's may be emitted as
     * written.
     */
    @Test
    fun `a value discriminator with an unchecked branch is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            PlanTreatment.Typed(
                by = "dty",
                branches = mapOf("2" to PlanTreatment.Measured),
                otherwise = PlanTreatment.BoundValue,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlanTreatment.Typed(
                by = "dty",
                branches = mapOf("2" to PlanTreatment.BoundNumber),
                otherwise = PlanTreatment.Expression,
            )
        }

        PlanTreatment.Typed(
            by = "dty",
            branches = mapOf("2" to PlanTreatment.BoundNumber),
            otherwise = PlanTreatment.BoundValue,
        )
    }

    /**
     * **A declared type that does not parse against the closed grammar is masked whole**, rather than
     * being edited — and so is one whose **words** are not this engine's.
     *
     * The first is what makes the strip a decomposition: the reader knows which characters are the
     * type and which are the width, and a value it cannot take apart is replaced instead of having a
     * substring taken out of it. The second is what keeps a type **somebody wrote** — an object type,
     * a collection — from being emitted as written, since that spelling is a schema object's name like
     * any other.
     */
    @Test
    fun `a declared type outside the grammar or outside the vocabulary is masked whole`() {
        val shapes = mapOf(
            "a value that does not take apart" to """dtystr="DATE @ 'utc'"""",
            "a type somebody wrote" to """dtystr="ACME_ADDRESS_T"""",
        )

        for ((printed, written) in shapes) {
            val text = anonymizedText(ORACLE_MONITOR_XML.replace("""dtystr="DATE"""", written))

            assertTrue(
                Regex("""dtystr="str\d+"""").containsMatchIn(text),
                "$printed was not masked whole:\n$text",
            )
            assertFalse("ACME_ADDRESS_T" in text, "a user-written type was left on the clipboard:\n$text")
        }
    }
}

/**
 * Whether this inventory label is written in [document] — as an element, or as an attribute.
 *
 * The element spellings are checked with the character that ends the tag name, so that `plan` is not
 * found by `plan_hash` and a row can only be reported as exercised by a fixture that actually writes
 * it.
 */
private fun String.writtenIn(document: String): Boolean =
    "<$this " in document || "<$this>" in document || "<$this/" in document || "$this=\"" in document
