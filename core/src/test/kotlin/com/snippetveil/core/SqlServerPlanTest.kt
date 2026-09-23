package com.snippetveil.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **The two SQL Server formats this product reads** — the Showplan XML document whose content is in
 * its attributes, and the `SHOWPLAN_TEXT` plan rowset whose every name is bracketed.
 *
 * The refusals are `SqlServerRefusalTest`'s. What is asserted here is the reading: that each format
 * anonymizes end to end, that the structural discriminator takes both of its branches, that a name
 * carrying a closing bracket and a comma comes back as one name, and that the fields this engine is
 * read *for* — a trace flag, a declared data type, a conversion's length, a row size, a plan size —
 * come out exactly as the engine printed them.
 */
class SqlServerPlanTest {

    /**
     * **A Showplan XML document anonymizes end to end** — every name replaced, every measurement
     * exactly as SQL Server printed it.
     *
     * The measurements are half the assertion rather than a bonus: a plan is pasted *for* its costs
     * and row estimates, and a reader that moved one would have destroyed the reason it was sent.
     */
    @Test
    fun `a Showplan XML document anonymizes end to end`() {
        val text = anonymizedText(SQLSERVER_XML_PLAN)

        for (name in SQLSERVER_NAMES) {
            assertFalse(
                Regex("""\b${Regex.escape(name)}\b""").containsMatchIn(text),
                "the Showplan XML reading left `$name` on the clipboard:\n$text",
            )
        }
        for (measurement in listOf("0.0065704", "0.003125", "1000", "24")) {
            assertTrue(measurement in text, "the Showplan XML reading moved `$measurement`:\n$text")
        }
        assertTrue("""PhysicalOp="Index Seek"""" in text, "an engine fact did not survive its shape:\n$text")
        assertTrue(
            Regex("""Table="\[table\d+]"""").containsMatchIn(text),
            "the Showplan XML reading replaced no relation at all:\n$text",
        )
        assertTrue(
            Regex("""StatementText="str\d+"""").containsMatchIn(text),
            "the echoed statement was not masked whole:\n$text",
        )
    }

    /**
     * **A column element carrying a table is a column; one without it is a masked literal** — both
     * branches of the structural discriminator, asserted over one document that carries both.
     *
     * This is the claim that makes reading a field's siblings admissible at all. Neither branch
     * preserves, so the shape a user can influence — *did the optimizer attach a table to this
     * reference* — never decides that something of theirs may be emitted as written.
     */
    @Test
    fun `a column with a table takes a column placeholder and one without takes a redacted literal`() {
        val text = anonymizedText(SQLSERVER_XML_PLAN)

        assertTrue(
            Regex("""Table="\[table\d+]" Alias="\[table\d+]" Column="col\d+"""").containsMatchIn(text),
            "a column of a table did not take a column placeholder:\n$text",
        )
        assertTrue(
            Regex("""<ColumnReference Column="str\d+" />""").containsMatchIn(text),
            "a computed column did not take a redacted literal:\n$text",
        )
        assertFalse("Expr1002" in text, "a computed column's name was left on the clipboard:\n$text")
    }

    /**
     * **A `SHOWPLAN_TEXT` plan rowset anonymizes** — with or without the header row above it, which
     * are the two shapes the grid copies.
     *
     * The engine's own sentence survives whole, and that is the other half of the claim: nothing
     * outside a bracket is reported at all, so `Inner Join`, `SEEK:` and `ORDERED FORWARD` come back
     * untouched without a single one of them appearing on a word list.
     */
    @Test
    fun `a SHOWPLAN_TEXT plan rowset anonymizes, with and without its header`() {
        for ((shape, plan) in listOf("with a header" to SQLSERVER_TEXT_PLAN, "rows only" to SQLSERVER_TEXT_ROWS_ONLY)) {
            val text = anonymizedText(plan)

            for (name in SQLSERVER_NAMES) {
                assertFalse(
                    Regex("""\b${Regex.escape(name)}\b""").containsMatchIn(text),
                    "the reading $shape left `$name` on the clipboard:\n$text",
                )
            }
            for (written in listOf("Inner Join", "OUTER REFERENCES:", "SEEK:", "ORDERED FORWARD", "(42)")) {
                assertTrue(written in text, "the reading $shape moved the engine's own `$written`:\n$text")
            }
            assertTrue(
                Regex("""OBJECT:\(\[schema\d+]\.\[schema\d+]\.\[table\d+]\.\[idx\d+] AS \[table\d+]\)""")
                    .containsMatchIn(text),
                "an object reference was not read by position:\n$text",
            )
            assertTrue(Regex("""='str\d+'""").containsMatchIn(text), "a literal was not masked:\n$text")
        }
    }

    /**
     * **A name carrying a closing bracket and a comma is one name, recovered exactly.**
     *
     * The engine writes `Odd]Table, Two` as `[Odd]]Table, Two]`, and a reader that took the first
     * `]` for the end would report a table called `Odd` and then try to place `Table, Two]` — which
     * is a name forging a cell. Two claims: the name this product **keys** the table under is the
     * recovered spelling with its doubling undone, and both printings of it come back as one
     * placeholder written inside the brackets the engine drew.
     */
    @Test
    fun `a name with a closing bracket and a comma in it round-trips as one name`() {
        val keys = planIn(SQLSERVER_TEXT_AWKWARD_NAME).occurrences
            .filterIsInstance<PlanOccurrence>()
            .mapNotNull { (it.disposition as? PlanDisposition.Anonymize)?.key }

        assertTrue(
            PlanKeys.named(SymbolRole.TABLE, "Odd]Table, Two") in keys,
            "the doubled closing bracket was not undone when the name was keyed: $keys",
        )

        val result = anonymize(planIn(SQLSERVER_TEXT_AWKWARD_NAME), AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        result.assertShared("a bracketed name is one symbol however the engine doubled it", "Odd]]Table, Two")
        assertFalse("Odd" in result.text, "the awkward name was left on the clipboard:\n${result.text}")
        assertTrue(
            Regex("""OBJECT:\(\[schema\d+]\.\[schema\d+]\.\[table\d+] AS \[table\d+]\)""")
                .containsMatchIn(result.text),
            "the recovered name did not come back inside its own brackets:\n${result.text}",
        )
    }

    /**
     * **A column whose name carries a tab is one name**, and the tab is the grid's own cell
     * separator.
     *
     * The bracket is what delimits, so a separator inside a name is just a character — which is the
     * property the whole format rests on, stated over the one case where a name holds the client's
     * own punctuation. A reader that leaned on the separator would cut this name in half and report
     * two columns where there is one.
     */
    @Test
    fun `a column whose name carries a tab is one name`() {
        val result = anonymize(
            planIn(SQLSERVER_TEXT_TABBED_COLUMN),
            AnonymizationSettings.DEFAULTS,
            LedgerSnapshot.EMPTY,
        )

        result.assertShared("a tab inside a bracketed name does not end it", "Od\td")
        assertFalse("Od\td" in result.text, "the tabbed column name was left on the clipboard:\n${result.text}")
        assertTrue(
            Regex("""\[col\d+] as \[table\d+]\.\[col\d+]='str\d+'""").containsMatchIn(result.text),
            "the tabbed column did not come back as one placeholder inside its brackets:\n${result.text}",
        )
    }

    /**
     * **An alias declared on one row is the same symbol wherever the plan uses it.**
     *
     * A plan draws its tree parent-first, so a parent's `OUTER REFERENCES:([v].[Id])` names an alias
     * the row *below* it declares. A reader that keyed each name where it met it would hand out two
     * placeholders for one alias, which is exactly what the two passes over one set of declarations
     * exist to prevent.
     */
    @Test
    fun `an alias used above the row that declares it is one name`() {
        val result = anonymize(planIn(SQLSERVER_TEXT_PLAN), AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        result.assertShared("an alias is one symbol wherever the plan writes it", "v")
    }

    /** **Trace flag values are preserved behind the shape check**, which is what a plan is read for. */
    @Test
    fun `a trace flag's number and scope are preserved`() {
        val preserved = preservedIn(SQLSERVER_XML_PLAN)

        assertTrue("4199" in preserved, "a trace flag's number was not preserved: $preserved")
        assertTrue("Global" in preserved, "a trace flag's scope was not preserved: $preserved")
        assertTrue("""TraceFlag Value="4199" Scope="Global"""" in anonymizedText(SQLSERVER_XML_PLAN))
    }

    /**
     * **A parameter's declared data type and a conversion's declared length are preserved.**
     *
     * Both describe the **slot** rather than the value in it: the optimizer emits the same declared
     * type for every auto-parameterized value of that parameter, and the length is the target
     * column's width, constant across every value it ever holds. An implicit conversion is a top-tier
     * reason to read a plan at all, and these two fields are the whole of what explains one.
     */
    @Test
    fun `a declared data type and a conversion's declared length are preserved`() {
        val preserved = preservedIn(EXHAUSTIVE_SQLSERVER_XML_PLAN)

        assertTrue("varchar(8000)" in preserved, "a parameter's declared data type was not preserved: $preserved")
        assertTrue("numeric" in preserved, "a conversion's data type was not preserved: $preserved")

        val text = anonymizedText(EXHAUSTIVE_SQLSERVER_XML_PLAN)
        assertTrue("""Length="9"""" in text, "a conversion's declared length was not preserved:\n$text")
        assertTrue("""ParameterDataType="varchar(8000)"""" in text, "the declared type moved:\n$text")
    }

    /**
     * **The average row size and the cached plan size stay preserved, and are reported as nothing at
     * all.**
     *
     * They are magnitudes about the table's rows and about the plan, with no value of anybody's in
     * them — so they take the measured treatment, which preserves the number *and produces no
     * occurrence*. Asserting the second half is what keeps a later rule from sweeping them into a
     * mask: a field that reports nothing is a field no counting, notice or masking rule can reach.
     */
    @Test
    fun `the average row size and the cached plan size are preserved as measured quantities`() {
        val plan = planIn(SQLSERVER_XML_PLAN)

        for ((attribute, value) in listOf("AvgRowSize" to "36", "CachedPlanSize" to "24")) {
            assertTrue("""$attribute="$value"""" in anonymizedText(SQLSERVER_XML_PLAN), "$attribute moved")
            assertEquals(
                emptyList<Occurrence>(),
                plan.occurrencesOver("""$attribute="$value""", attribute.length + 2, value.length),
                "$attribute was reported rather than read straight past",
            )
        }
    }

    /**
     * **A plan pasted from a Windows terminal reads like any other, and its line endings come back
     * untouched.**
     *
     * Asserted over both formats, because they read lines in completely different ways — one is an
     * XML document where a newline is whitespace, and the other is a rowset where a newline ends a
     * row.
     */
    @Test
    fun `a plan whose lines end in a carriage return reads, and keeps them`() {
        for ((format, plan) in listOf("Showplan XML" to SQLSERVER_XML_PLAN, "SHOWPLAN_TEXT" to SQLSERVER_TEXT_PLAN)) {
            val windows = plan.replace("\n", "\r\n")
            val text = anonymizedText(windows)

            assertEquals(
                windows.count { it == '\r' },
                text.count { it == '\r' },
                "the $format reading moved a line ending:\n$text",
            )
            assertFalse(
                Regex("""\bVisits\b""").containsMatchIn(text),
                "the $format reading of a Windows paste left a relation on the clipboard:\n$text",
            )
        }
    }
}

/**
 * Every occurrence this plan reports over the value written [offset] characters into the first
 * writing of [written], which is [length] characters long.
 *
 * It is how *the field reports nothing at all* is asserted from the outside: a measured field is not
 * an occurrence with a preserving disposition, it is **no occurrence**, and only a question about
 * ranges can tell the two apart.
 */
private fun SnippetPlan.occurrencesOver(written: String, offset: Int, length: Int): List<Occurrence> {
    val at = text.indexOf(written) + offset
    return occurrences.filter { it.start < at + length && at < it.end }
}
