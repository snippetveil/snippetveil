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
            topLevelPackageSegments = emptySet(),
        )

        assertEquals(listOf("MerchantLedger"), oracle.survivorsIn("Builder b; MerchantLedger m;").map { it.name })
    }

    /**
     * `com` out of `com.acme.billing` is passed through by a stated rule in the engine, so it
     * survives into every single file's output. Reporting it would bury the report under one hit per
     * file, forever.
     */
    @Test
    fun `a top-level package segment the engine passes through is not part of the universe`() {
        val oracle = LeakOracle.over(
            declaredInProjectSources = setOf("com", "acme", "billing"),
            declaredByLibraries = emptySet(),
            topLevelPackageSegments = setOf("com"),
        )

        assertEquals(listOf("acme"), oracle.survivorsIn("package com.acme;").map { it.name })
    }

    /** A check that has nothing to check is not a check. */
    @Test
    fun `an oracle whose universe came out empty refuses to be built`() {
        assertThrows(IllegalStateException::class.java) {
            LeakOracle.over(
                declaredInProjectSources = setOf("Builder"),
                declaredByLibraries = setOf("Builder"),
                topLevelPackageSegments = emptySet(),
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
     * The three input sets overlap in practice, so the size a report quotes cannot be arrived at by
     * subtracting their sizes: `Builder` is here both as a library-declared name and as a top-level
     * package segment, and a subtraction would report one name too few.
     */
    @Test
    fun `the universe size counts a name subtracted twice once`() {
        val oracle = LeakOracle.over(
            declaredInProjectSources = setOf("MerchantLedger", "Builder"),
            declaredByLibraries = setOf("Builder"),
            topLevelPackageSegments = setOf("Builder"),
        )

        assertEquals(1, oracle.size)
    }

    private fun oracleOver(vararg names: String) = LeakOracle.over(
        declaredInProjectSources = names.toSet(),
        declaredByLibraries = emptySet(),
        topLevelPackageSegments = emptySet(),
    )
}
