package com.snippetveil.sweep

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **The fixture-provenance trap, asserted** — the entire licence for committing real captures.
 *
 * The all-synthetic fixture rule is relaxed here because its reason — committing a real artifact
 * risks committing a leak — is absent for a capture taken against a throwaway schema in a container.
 * *Only commit captures from the generator*, left as a documented requirement, is precisely the
 * category a self-asserting harness exists to replace, and it is a requirement that **fails in the
 * green direction**. This is what replaces it, and **if it is ever removed the relaxation goes with
 * it.**
 */
class PlanProvenanceTest {

    /**
     * **Every identifier in every committed plan fixture is drawn from the generator's fixed schema
     * vocabulary**, or is a spelling the engine's own printer writes.
     *
     * It goes red the day somebody drops in a capture from a real database.
     */
    @Test
    fun `every identifier in a committed fixture is from the generator's vocabulary`() {
        val foreign = COMMITTED_PLAN_CAPTURES.associate { it.name to PlanProvenance.foreignIn(it.text) }
            .filterValues { it.isNotEmpty() }

        assertTrue(
            foreign.isEmpty(),
            "these committed fixtures carry identifiers the generator's schema does not hold, and " +
                "that the engine's printer does not write:\n" +
                foreign.entries.joinToString("\n") { "  ${it.key}: ${it.value.joinToString(" ")}" },
        )
    }

    /**
     * **The trap proves it can fail before it reports that nothing failed.**
     *
     * A red path that is never exercised decays into a check that always passes, and a provenance
     * trap that had quietly stopped asserting would leave the relaxation standing on nothing. The
     * planted identifier is the shape the trap exists for: a name from a real database, sitting in
     * the slot a generated capture would have put a generated one.
     */
    @Test
    fun `the trap goes red on a planted foreign identifier`() {
        val clean = COMMITTED_PLAN_CAPTURES.first { it.name == "postgres-json" }
        val planted = clean.text.replace("\"visits\"", "\"merchant_ledger\"")

        assertFalse(planted == clean.text, "the plant changed nothing, so this test asserts nothing")
        assertEquals(
            emptyList<String>(),
            PlanProvenance.foreignIn(clean.text),
            "the unplanted fixture is itself a finding, so a red below would say nothing",
        )
        assertEquals(
            listOf("merchant_ledger"),
            PlanProvenance.foreignIn(planted),
            "a name from somebody's real database passed the trap",
        )
    }

    /**
     * **A foreign name is a finding wherever the document put it** — in a slot the engine delimited
     * as readily as in a bare one.
     *
     * The reading is engine-neutral text splitting, so a JSON value and a bare word in a text plan
     * reach the trap the same way; this is the assertion that neither is walked past.
     */
    @Test
    fun `a foreign name is found inside a delimited slot`() {
        val planted = """[{"Plan": {"Node Type": "Seq Scan", "Relation Name": "Ledger"}}]"""

        assertEquals(listOf("Ledger"), PlanProvenance.foreignIn(planted))
    }

    /**
     * **The trap's one blind spot, asserted rather than left to be discovered.**
     *
     * A chrome spelling is allowed wherever it appears, delimiters included, so a real database's
     * relation genuinely called `Message` passes. The oracle next door does not have that hole — it
     * keeps a delimited spelling whatever it is spelled — and the trap cannot copy it: the splitting
     * is engine-neutral, so **every quoted value in a JSON or XML plan comes back delimited**, and a
     * trap that refused chrome inside delimiters would flag `Seq` and `Scan` in every structured
     * capture ever committed.
     *
     * What narrows the hole is the schema half: the generator names its own keyword-collision
     * relations — `Sort`, `Hash`, `Filter` — so the case the named assertion is about is carried
     * there rather than here.
     */
    @Test
    fun `a relation named after a printer word passes, which is the trap's stated blind spot`() {
        val collides = """[{"Plan": {"Node Type": "Seq Scan", "Relation Name": "Message"}}]"""
        val keywordNamed = """[{"Plan": {"Node Type": "Seq Scan", "Relation Name": "Sort"}}]"""

        assertEquals(emptyList<String>(), PlanProvenance.foreignIn(collides), "the blind spot has closed")
        assertTrue(
            "Sort" in PlanProvenance.SCHEMA_VOCABULARY && "Sort" !in PlanProvenance.PRINTER_SPELLINGS,
            "`Sort` is no longer the generator's relation, so the named assertion's fixture passes " +
                "this trap on the chrome half — which is the blind spot rather than the schema",
        )
        assertEquals(emptyList<String>(), PlanProvenance.foreignIn(keywordNamed))
    }

    /**
     * **An identifier the engine generated is not somebody's schema**, and it is recognised by its
     * shape rather than by a list — the list would have to be per capture, and a trap that went red
     * on the next capture from the same generator is a trap that gets deleted.
     */
    @Test
    fun `a generated digest is allowed and a chosen name of the same length is not`() {
        assertTrue(PlanProvenance.isGenerated("ab1c9f2e"), "a hex digest was read as a chosen name")
        assertTrue(PlanProvenance.isGenerated("ws837zynp1zv"), "a SQL id was read as a chosen name")
        assertFalse(PlanProvenance.isGenerated("merchant_ledger"), "a chosen name was read as generated")
        assertFalse(PlanProvenance.isGenerated("orders2024"), "a chosen name with a year was read as generated")
        assertFalse(PlanProvenance.isGenerated("Expr100"), "a short spelling was read as generated")
    }

    /**
     * **The generator's vocabulary and the engine's chrome are two lists, and the trap needs both.**
     *
     * Asserted so that a future shortcut — folding the chrome into the schema vocabulary, which
     * would make the trap shorter and pass just as often — is visible as the change it is: after it,
     * every engine word would be a name the generator is claimed to have chosen.
     */
    @Test
    fun `the generator's schema and the engine's chrome do not overlap`() {
        val both = PlanProvenance.SCHEMA_VOCABULARY.intersect(PlanProvenance.PRINTER_SPELLINGS)

        assertTrue(both.isEmpty(), "these spellings are claimed as both the generator's and the engine's: $both")
    }

    /**
     * **What the trap cannot do, asserted so that nobody reads it as more than it is.**
     *
     * It certifies that a fixture carries no foreign identifier. It does **not** certify that a
     * fixture came from a capture: a hand-written text using only the generator's vocabulary passes,
     * and **every fixture committed here today is exactly that.** The registry records the
     * provenance of each row and the report prints the split; neither is something the trap can
     * check, and saying so here is cheaper than somebody rediscovering it.
     */
    @Test
    fun `the trap passes a hand-written text that uses only the generator's vocabulary`() {
        val handWritten = """[{"Plan": {"Node Type": "Seq Scan", "Relation Name": "visits"}}]"""

        assertEquals(emptyList<String>(), PlanProvenance.foreignIn(handWritten))
        assertTrue(
            COMMITTED_PLAN_CAPTURES.all { it.origin == PlanCaptureOrigin.WRITTEN_FROM_DOCUMENTATION },
            "a committed fixture claims to be corpus-lifted; the trap cannot check that claim, so " +
                "this test is the place that says the claim was made",
        )
    }
}
