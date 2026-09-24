package com.snippetveil.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * **The two Oracle formats this product reads** — the `|`-ruled grid its text output draws, and the
 * SQL Monitor XML report.
 *
 * The refusals are `OracleRefusalTest`'s and the drop rule is `OracleBindTest`'s. What is asserted
 * here is the reading: that each format anonymizes end to end, that cells are read by **count** and
 * never by offset, that a ragged grid is an accepted output rather than something to re-pad, and that
 * the outline under the plan is parsed hint by hint rather than replaced whole.
 */
class OraclePlanTest {

    /**
     * **A grid text plan anonymizes end to end** — from the plan-hash anchor the cursor variant is
     * pasted from, and from the grid's own rule, which are the two admitted openings.
     *
     * The measurements are half the assertion rather than a bonus: a plan is pasted *for* its costs,
     * cardinalities and timings, and a reader that moved one would have destroyed the reason it was
     * sent.
     */
    @Test
    fun `a grid text plan anonymizes end to end, from either of its two openings`() {
        for ((opening, plan) in listOf("the plan-hash anchor" to ORACLE_GRID_PLAN, "the grid" to ORACLE_GRID_ROWS_ONLY)) {
            val text = anonymizedText(plan)

            for (name in ORACLE_NAMES) {
                assertFalse(
                    Regex("""\b${Regex.escape(name)}\b""").containsMatchIn(text),
                    "the reading from $opening left `$name` on the clipboard:\n$text",
                )
            }
            for (measurement in listOf("136", "00:00:01", "4   (0)")) {
                assertTrue(measurement in text, "the reading from $opening moved `$measurement`:\n$text")
            }
            assertTrue("TABLE ACCESS BY INDEX ROWID" in text, "an operation did not survive its shape:\n$text")
            assertTrue(
                Regex("""\| table\d+""").containsMatchIn(text),
                "the reading from $opening replaced no relation at all:\n$text",
            )
            assertTrue(
                Regex("""\| idx\d+""").containsMatchIn(text),
                "the index scanned did not come back as an access path:\n$text",
            )
        }
    }

    /**
     * **The plan hash is masked**, for the reason every identifying value in this product is masked:
     * a receiver holding a candidate statement could confirm it by matching the hash, which undoes
     * the rule that has the statement redacted.
     *
     * It is also the line the cursor variant is **anchored** at, so the two claims are asserted
     * together: the line is what admits the paste, and the number on it does not leave.
     */
    @Test
    fun `the plan-hash anchor line reads and its number is masked`() {
        val text = anonymizedText(ORACLE_GRID_PLAN)

        assertFalse("2709293936" in text, "the plan hash was left on the clipboard:\n$text")
        assertTrue(
            Regex("""Plan hash value: str\d+""").containsMatchIn(text),
            "the anchor line did not come back with its number masked:\n$text",
        )
    }

    /**
     * **A wide-character name round-trips, which is what proves cells are read by count.**
     *
     * The name is four characters wide and eight columns wide, so every separator after it sits where
     * a character count says it does not. A reader slicing by offset takes half the name and leaves
     * the rest on the clipboard; a reader counting separators takes the whole of it. The assertion is
     * the whole name being gone, not merely something having changed.
     */
    @Test
    fun `a wide-character name is read whole, by count rather than by offset`() {
        val result = anonymize(
            planIn(ORACLE_GRID_WIDE_NAME),
            AnonymizationSettings(keepComments = true),
            LedgerSnapshot.EMPTY,
        )

        result.assertShared("a name is one symbol however wide its characters print", "訪問記録")
        assertFalse("訪問" in result.text, "half the wide name was left on the clipboard:\n${result.text}")
        assertTrue(
            Regex("""\| table\d+ +\|""").containsMatchIn(result.text),
            "the wide name did not come back as one placeholder in its own cell:\n${result.text}",
        )

        // And the whole grid comes back, character for character — which is what says the cells were
        // cut where the separators are and nowhere else.
        val back = deanonymize(
            result.text,
            Sidecar.EMPTY.recording(RecordedInvocation(Instant.now(), result.mapping)),
            LedgerSnapshot.EMPTY + result.delta,
        )

        assertEquals(ORACLE_GRID_WIDE_NAME, back.text, "the wide-character grid did not round-trip")
        assertEquals(emptyList<Unrestored>(), back.unrestored, "the reversal left a placeholder behind")
    }

    /**
     * **A ragged grid is the accepted output, and nothing re-pads it.**
     *
     * Replacing a long name with a short placeholder shortens a cell, and the columns stop lining up.
     * That is allowed precisely because this format's admission rests on cells being read by count and
     * never by offset — so nothing that reads the plan is broken by it, and a human still reads the
     * tree. A golden rather than a structural assertion, because *no whitespace was invented* is a
     * claim about the exact characters on the clipboard.
     */
    @Test
    fun `a ragged grid is emitted as it fell out, with no padding invented`() {
        val text = anonymizedText(ORACLE_GRID_WIDE_NAME)

        assertEquals(
            """
            |Plan hash value: str1
            |
            |----------------------------------------------------------
            || Id  | Operation          | Name     | Rows  | Bytes |
            |----------------------------------------------------------
            ||   0 | SELECT STATEMENT   |          |     2 |   136 |
            ||   1 |  TABLE ACCESS FULL | table2 |     2 |    64 |
            |----------------------------------------------------------
            """.trimMargin(),
            text,
        )
    }

    /**
     * **The predicate section is read as the expression text it is** — names replaced, the literal
     * masked, and the engine's own words left exactly as printed.
     *
     * The alias the predicate names is the alias the outline names, which is the other half: one
     * symbol, one placeholder, wherever the plan writes it.
     */
    @Test
    fun `the predicate section replaces its names and masks its literal`() {
        val result = anonymize(planIn(ORACLE_GRID_PLAN), AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertFalse("'open'" in result.text, "a predicate's literal was left on the clipboard:\n${result.text}")
        assertTrue(
            Regex("""filter\("table\d+"\."col\d+"='str\d+'\)""").containsMatchIn(result.text),
            "the predicate did not come back with its names replaced inside their quotes:\n${result.text}",
        )
        assertTrue("access(" in result.text, "the section's own word did not survive:\n${result.text}")
        assertTrue("=42)" in result.text, "a number in a predicate moved:\n${result.text}")
        result.assertShared("an alias is one symbol wherever the plan writes it", "V")
    }

    /**
     * **An outline hint parses against its grammar** — the query block the optimizer invented
     * survives, the objects in it are replaced, and the optimizer version is preserved.
     *
     * The version is the point of reading the outline at all: which optimizer chose this plan is what
     * the section is read for, and a reader that masked every literal in it would have thrown that
     * away along with nothing.
     */
    @Test
    fun `an outline hint parses, keeping the engine's query block and its optimizer version`() {
        val text = anonymizedText(ORACLE_GRID_PLAN)

        assertTrue(
            """OPTIMIZER_FEATURES_ENABLE('19.1.0')""" in text,
            "the optimizer version did not survive the outline:\n$text",
        )
        assertTrue("""OUTLINE_LEAF(@"SEL${'$'}1")""" in text, "an invented query block did not survive:\n$text")
        assertTrue(
            Regex("""FULL\(@"SEL\${'$'}1" "table\d+"@"SEL\${'$'}1"\)""").containsMatchIn(text),
            "an object in a hint was not replaced inside its quotes:\n$text",
        )
        assertTrue(
            Regex("""\("table\d+"\."col\d+"\)""").containsMatchIn(text),
            "a dotted name in a hint was not read by position:\n$text",
        )
        assertTrue("BEGIN_OUTLINE_DATA" in text, "the outline's own words did not survive:\n$text")
    }

    /**
     * **An off-list hint becomes one redacted literal, and the plan still reads.**
     *
     * This is the one place in this product where a closed vocabulary's miss costs fidelity rather
     * than the input, and it is asserted as the pair it is: the unknown hint is replaced whole, and
     * every hint beside it still parses. Treating the whole outline as one literal was rejected — it
     * leaks nothing and stops the section lining up with the plan it describes.
     */
    @Test
    fun `an off-list hint is one redacted literal and does not refuse the plan`() {
        val text = anonymizedText(ORACLE_GRID_OFF_LIST_HINT)

        assertFalse("SPACESHIP_JOIN" in text, "an off-list hint was left on the clipboard:\n$text")
        assertTrue(
            Regex("""^ *str\d+$""", RegexOption.MULTILINE).containsMatchIn(text),
            "the off-list hint did not come back as one redacted literal:\n$text",
        )
        assertTrue("""OUTLINE_LEAF(@"SEL${'$'}1")""" in text, "the hints beside it stopped parsing:\n$text")
    }

    /**
     * **A SQL Monitor XML report anonymizes end to end** — the names replaced, the statement masked,
     * the deployment masked and the measurements exactly as printed.
     */
    @Test
    fun `a SQL Monitor XML report anonymizes end to end`() {
        val text = anonymizedText(ORACLE_MONITOR_XML)

        for (name in ORACLE_NAMES) {
            assertFalse(
                Regex("""\b${Regex.escape(name)}\b""").containsMatchIn(text),
                "the report reading left `$name` on the clipboard:\n$text",
            )
        }
        for (measurement in listOf("""cost="4"""", """cardinality="2"""", """bytes="136"""", ">1204<")) {
            assertTrue(measurement in text, "the report reading moved `$measurement`:\n$text")
        }
        assertTrue("""name="SELECT STATEMENT"""" in text, "an operation did not survive its shape:\n$text")
        assertTrue(
            Regex("""<sql_fulltext>str\d+</sql_fulltext>""").containsMatchIn(text),
            "the echoed statement was not masked whole:\n$text",
        )
        assertTrue(
            Regex("""owner="schema\d+"""").containsMatchIn(text),
            "the owning schema was not replaced:\n$text",
        )
    }

    /**
     * **An object typed an index is an access path and everything else is a rowset** — the value
     * discriminator, over one document carrying both.
     *
     * Both branches replace what they find, which is the rule under which reading a sibling's value is
     * admitted at all. Reading them all as rowsets was refused because it asserts a rowset where there
     * is an access path, and a reader mapping the reply back would look for a table that is not there.
     */
    @Test
    fun `an object typed an index takes an index placeholder and a table takes a table one`() {
        val kinds = namesIn(ORACLE_MONITOR_XML)

        assertEquals(SymbolRole.INDEX, kinds["VISITS_PKEY"], "an index was not read as an access path: $kinds")
        assertEquals(SymbolRole.TABLE, kinds["VISITS"], "a table was not read as a rowset: $kinds")
        assertEquals(SymbolRole.SCHEMA, kinds["APP_OWNER"], "the owner was not read as a schema: $kinds")
    }

    /**
     * **A plan pasted from a Windows terminal reads like any other, and its line endings come back
     * untouched.**
     *
     * Asserted over both formats, because they read lines in completely different ways — one is an
     * XML document where a newline is whitespace, and the other is a grid where a newline ends a row.
     */
    @Test
    fun `a plan whose lines end in a carriage return reads, and keeps them`() {
        for ((format, plan) in listOf("grid text" to ORACLE_GRID_PLAN, "SQL Monitor XML" to ORACLE_MONITOR_XML)) {
            val windows = plan.replace("\n", "\r\n")
            val text = anonymizedText(windows)

            assertEquals(
                windows.count { it == '\r' },
                text.count { it == '\r' },
                "the $format reading moved a line ending:\n$text",
            )
            assertFalse(
                Regex("""\bVISITS\b""").containsMatchIn(text),
                "the $format reading of a Windows paste left a relation on the clipboard:\n$text",
            )
        }
    }
}
