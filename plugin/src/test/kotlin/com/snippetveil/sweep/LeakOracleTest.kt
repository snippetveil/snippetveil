package com.snippetveil.sweep

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The oracle's rules, over strings — no PSI, no project, no IDE.
 *
 * These run in `check` like any other test, and the instrument they belong to never does. That is
 * not a contradiction: **the sweep is the thing that opens a real codebase**, and what is asserted
 * here is the text rule it applies once it has one. A rule nobody could test without a proprietary
 * checkout would be a rule nobody tests.
 */
class LeakOracleTest {

    @Test
    fun `a project-owned name surviving into the output is a finding`() {
        val oracle = oracleOver("MerchantLedger")

        val survivors = oracle.survivorsIn("class Type1 { MerchantLedger field2; }")

        assertEquals(listOf("MerchantLedger"), survivors.map { it.name })
    }

    @Test
    fun `a finding carries the line it was found on, and the line`() {
        val oracle = oracleOver("MerchantLedger")

        val survivors = oracle.survivorsIn("class Type1 {\n\n    MerchantLedger field2;\n}")

        assertEquals(3, survivors.single().line)
        assertEquals("MerchantLedger field2;", survivors.single().text)
    }

    @Test
    fun `an output naming nothing the project owns is clean`() {
        val oracle = oracleOver("MerchantLedger")

        assertEquals(emptyList<String>(), oracle.survivorsIn("class Type1 { Type2 field2; }").map { it.name })
    }

    /**
     * **Whole identifiers, never substrings.** `merchantId` inside `merchantIdentifier` is a
     * different name, and a substring rule would report the second every time the first is owned —
     * which is the kind of noise that gets an instrument suppressed rather than read.
     */
    @Test
    fun `a name that is only a substring of a surviving identifier is not a finding`() {
        val oracle = oracleOver("merchantId")

        assertEquals(emptyList<String>(), oracle.survivorsIn("String merchantIdentifier = x.merchantIdx;").map { it.name })
    }

    /**
     * The output is read as text rather than as code, which is the whole point of a blunt oracle: a
     * name that survived inside a preserved literal has left the editor exactly as surely as one
     * that survived as an identifier.
     */
    @Test
    fun `a name surviving inside a literal is a finding like any other`() {
        val oracle = oracleOver("MerchantLedger")

        assertEquals(
            listOf("MerchantLedger"),
            oracle.survivorsIn("""log.warn("see MerchantLedger for the reconciliation");""").map { it.name },
        )
    }

    @Test
    fun `one row per distinct name, at its first occurrence`() {
        val oracle = oracleOver("MerchantLedger")

        val survivors = oracle.survivorsIn("MerchantLedger a;\nMerchantLedger b;")

        assertEquals(1, survivors.size)
        assertEquals(1, survivors.single().line)
    }

    /**
     * A library declares `Builder` too, and the anonymiser preserves library names on purpose. A
     * project class of the same name is a collision the oracle cannot tell from a leak, so it is
     * subtracted rather than reported — see [LeakOracle.over].
     */
    @Test
    fun `a name the libraries also declare is not part of the universe`() {
        val oracle = LeakOracle.over(
            declaredInProjectSources = setOf("MerchantLedger", "Builder"),
            declaredByLibraries = setOf("Builder"),
        )

        assertEquals(listOf("MerchantLedger"), oracle.survivorsIn("Builder b; MerchantLedger m;").map { it.name })
    }

    /**
     * **`com` is reported, and that is the rule rather than an oversight.**
     *
     * The engine passes a top-level package segment through by a positional rule of its own, so `com`
     * survives into every file's output and this oracle flags it in every file — a known recurring
     * false positive, adjudicated by a human and documented in CONTRIBUTING.md.
     *
     * Subtracting it would make the report shorter and the instrument blinder: **the point here is to
     * bias toward false positives rather than silently suppress possible leaks**, and every
     * subtraction is a class of leak this sweep can never see again. The library subtraction is the
     * only one, because it is the only one that was decided; a second is a product decision and needs
     * a ticket, not a maintainer's view on report length. This test is what stops one being added
     * quietly.
     */
    @Test
    fun `a top-level package segment is reported like any other declared name`() {
        val oracle = LeakOracle.over(
            declaredInProjectSources = setOf("com", "acme", "billing"),
            declaredByLibraries = emptySet(),
        )

        assertEquals(listOf("com", "acme"), oracle.survivorsIn("package com.acme;").map { it.name })
    }

    /** A check that has nothing to check is not a check. */
    @Test
    fun `an oracle whose universe came out empty refuses to be built`() {
        assertThrows(IllegalStateException::class.java) {
            LeakOracle.over(
                declaredInProjectSources = setOf("Builder"),
                declaredByLibraries = setOf("Builder"),
            )
        }
    }

    /**
     * The self-proof the instrument runs before it reports anything. Asserted here so that it is
     * compiled and exercised by `check`, and run there so that a human reading a report knows the
     * rules that produced it had just demonstrated they can fail.
     */
    @Test
    fun `the rules prove they can fail`() {
        LeakOracle.proveTheRulesCanFail()
    }

    @Test
    fun `the self-proof is not vacuous`() {
        assertTrue(LeakOracle.proveTheRulesCanFail() > 0) { "The self-proof asserted nothing." }
    }

    /**
     * The library set is not a subset of the declared set — the index answers for names the project
     * never declares — so the size a report quotes cannot be arrived at by subtracting the two
     * sizes. It is the oracle's own count or it is wrong.
     */
    @Test
    fun `the universe size counts what is left, not what was subtracted`() {
        val oracle = LeakOracle.over(
            declaredInProjectSources = setOf("MerchantLedger", "Builder"),
            declaredByLibraries = setOf("Builder", "ArrayList", "Runnable"),
        )

        assertEquals(1, oracle.size)
    }

    private fun oracleOver(vararg names: String) = LeakOracle.over(
        declaredInProjectSources = names.toSet(),
        declaredByLibraries = emptySet(),
    )
}
