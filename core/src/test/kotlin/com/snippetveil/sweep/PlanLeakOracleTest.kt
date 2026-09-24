package com.snippetveil.sweep

import com.snippetveil.core.ORACLE
import com.snippetveil.core.POSTGRES
import com.snippetveil.core.SQLSERVER
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **The leak oracle over plan input, held to its rules without a corpus.**
 *
 * The instrument runs [PlanLeakOracle.proveTheRulesCanFail] before it opens anything, so that a
 * human reading a report knows the rules had just demonstrated their edges. This is the other half:
 * the rules are unit-tested in `check`, so a rule nobody has run the instrument for this month is
 * still a rule somebody is holding.
 */
class PlanLeakOracleTest {

    /**
     * **The builtin list is never subtracted, asserted directly.**
     *
     * It is the one rider row that can silently authorise a user's name: an engine prints a user's
     * `count` function exactly as it prints its own, so a user function shadowing a builtin is
     * preserved by design. **An oracle that subtracted the list would be green on exactly the defect
     * the list is known to be able to have** — so this is asserted against the universe itself,
     * rather than only through an output that happens to carry one.
     */
    @Test
    fun `the universe never subtracts the builtin list`() {
        val builtins = POSTGRES.builtins.take(5)
        val text = "Filter: (" + builtins.joinToString(" AND ") { "$it(x) > 1" } + ")"

        val universe = universeOf(text, POSTGRES).map { it.spelling }.toSet()

        assertTrue(
            builtins.all { it in universe },
            "the builtin list was subtracted from the universe: ${builtins.filterNot { it in universe }}",
        )
    }

    /** **The keyword and phrase rows *are* subtracted**, which is the other half of the same rule. */
    @Test
    fun `the universe subtracts keyword and phrase rows`() {
        val text = "Filter: ((status)::double precision = ANY (ARRAY[1]))"

        val universe = universeOf(text, POSTGRES).map { it.spelling }.toSet()

        assertFalse("ANY" in universe, "a keyword row survived into the universe")
        assertFalse("ARRAY" in universe, "a keyword row survived into the universe")
        assertFalse("double" in universe, "a phrase's word survived into the universe")
        assertFalse("precision" in universe, "a phrase's word survived into the universe")
        assertTrue("status" in universe, "the one name in the field was subtracted")
    }

    /**
     * **A token the builtin list saves is reported, annotated with the row that saved it.**
     *
     * That annotation is the number that makes the oracle's refusal to subtract the list
     * answerable: *this many spellings survived only because a builtin row saved them, and here is
     * the row.* Without the row, a reader counting preserves cannot tell which exposure they are
     * looking at.
     */
    @Test
    fun `a token the builtin list saves is annotated with the row that saved it`() {
        val oracle = PlanLeakOracle.over("Filter: (count(status) > 1)", POSTGRES)

        val note = oracle.survivorsIn("Filter: (count(col1) > 1)").single { it.spelling == "count" }.note

        assertEquals("count", (note as? PlanTriageNote.Builtin)?.row, "the row that saved it was lost")
    }

    /**
     * **The known recurring false positive is annotated and never removed.**
     *
     * A field label is in the universe because no rider row holds it, and it survives into every
     * output. Subtracting it would be a class of leak this instrument can never see again — a
     * relation genuinely named `Filter` among them — so the row is printed with a note saying which
     * class it is in, exactly as the source half keeps reporting `com`.
     */
    @Test
    fun `a spelling the printer writes is annotated rather than removed`() {
        val oracle = PlanLeakOracle.over("Seq Scan on invoices", POSTGRES)

        val survivors = oracle.survivorsIn("Seq Scan on table1").associate { it.spelling to it.note }

        assertEquals(PlanTriageNote.Printer, survivors["Scan"], "a printer word was not annotated")
        assertTrue("Seq" in survivors, "a printer word was removed from the report rather than annotated")
    }

    /** A spelling nothing saved carries no annotation, so the annotated ones can be counted. */
    @Test
    fun `a survivor no rule saved carries no annotation`() {
        val oracle = PlanLeakOracle.over("Seq Scan on invoices", POSTGRES)

        val survivor = oracle.survivorsIn("Seq Scan on invoices").single { it.spelling == "invoices" }

        assertEquals(null, survivor.note)
    }

    /**
     * **A delimited spelling is a name whatever it is spelled**, so the keyword subtraction must not
     * reach it — `"Scan"` in a plan is somebody's relation, not the printer's word.
     */
    @Test
    fun `a delimited keyword survives into the universe`() {
        val universe = universeOf("""Index Cond: ("Scan" = 1)""", POSTGRES)

        val scan = universe.single { it.spelling == "Scan" }

        assertTrue(scan.delimited, "a delimited spelling was read as a bare one")
        assertTrue("Cond" in universe.map { it.spelling }, "a bare word no rider row holds was subtracted")
    }

    /** A bracketed name with the closing bracket doubled is one spelling, recovered. */
    @Test
    fun `a doubled closing delimiter is recovered into one spelling`() {
        val universe = universeOf("""  |--Index Seek(OBJECT:([Vis]]its]))""", SQLSERVER)

        assertTrue("Vis]its" in universe.map { it.spelling }, "the doubled bracket was not recovered")
    }

    /**
     * **Nothing starts inside a number.** A hex digest's `x` opened a spelling in every capture that
     * printed a query hash before this rule; it is the same reading the product's own lexer gives a
     * token that opens on a digit.
     */
    @Test
    fun `a number contributes no spelling`() {
        val universe = universeOf("""QueryHash="0x9A1B2C3D" Version="1.552" Memory: 25kB""", SQLSERVER)

        assertTrue(
            universe.none { it.spelling.startsWith("x9A") || it.spelling == "kB" },
            "a spelling was read out of the middle of a number: ${universe.map { it.spelling }}",
        )
    }

    /**
     * **A quoted attribute value is split and never taken whole**, which is what keeps a universe
     * over an XML plan from holding a member that is a sentence or a URL.
     */
    @Test
    fun `a quoted sentence is split rather than held whole`() {
        val universe = universeOf("""StatementText="SELECT v.Id FROM shop.Visits AS v"""", SQLSERVER)
            .map { it.spelling }

        assertFalse(universe.any { ' ' in it }, "a sentence was held whole: $universe")
        assertTrue("Visits" in universe, "a name inside the sentence was not reached")
    }

    /** An empty universe is a failed reading rather than a clean one. */
    @Test
    fun `a text with no identifier in it refuses to build an oracle`() {
        val thrown = runCatching { PlanLeakOracle.over("1 2 3", POSTGRES) }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException, "an empty universe was accepted, so every capture reports clean")
    }

    /**
     * **The self-proof runs for every engine that has a rider**, and it asserts something in each.
     *
     * A self-proof that stopped asserting anything is itself the failure it exists to prevent, so
     * the count comes back and is checked. **A rider holding no bare-word row proves fewer edges,
     * and that is the rider rather than the proof**: SQL Server brackets every identifier, so there
     * is no keyword subtraction and no builtin exposure there to demonstrate.
     */
    @Test
    fun `the oracle proves its rules can fail for every rider`() {
        for (rider in listOf(POSTGRES, ORACLE)) {
            assertTrue(
                PlanLeakOracle.proveTheRulesCanFail(rider) >= EVERY_EDGE,
                "the self-proof made fewer assertions than a rider with every kind of row has edges",
            )
        }
        assertEquals(
            BARE_EDGES,
            PlanLeakOracle.proveTheRulesCanFail(SQLSERVER),
            "a rider with no bare-word row proved a keyword or builtin edge it does not have",
        )
    }

    private companion object {

        /** The edges a rider with keyword rows and builtin rows demonstrates. */
        const val EVERY_EDGE = 7

        /** The edges left where a rider holds neither — every identifier is delimited. */
        const val BARE_EDGES = 4
    }
}
