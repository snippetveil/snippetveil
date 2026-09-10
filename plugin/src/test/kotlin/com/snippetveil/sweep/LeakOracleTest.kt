package com.snippetveil.sweep

import com.snippetveil.sweep.Declaration.JavaMethod
import com.snippetveil.sweep.Declaration.JvmName
import com.snippetveil.sweep.Declaration.KotlinFacade
import com.snippetveil.sweep.Declaration.KotlinFunction
import com.snippetveil.sweep.Declaration.KotlinObject
import com.snippetveil.sweep.Declaration.KotlinProperty
import com.snippetveil.sweep.Declaration.Written
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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
            SourceSpellings.of(listOf(Written("MerchantLedger"), Written("Builder"))),
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
        val oracle = oracleOver("com", "acme", "billing")

        assertEquals(listOf("com", "acme"), oracle.survivorsIn("package com.acme;").map { it.name })
    }

    // ── The closure's rows, each in both directions where it has two ──────────────────────────────

    /** Kotlin declares `val body`; Java writes `getBody()`. `getBody` is declared nowhere. */
    @Test
    fun `a Kotlin property surviving under its Java accessors is a finding`() {
        val oracle = oracleOf(KotlinProperty("body", mutable = true))

        assertEquals(listOf("getBody"), oracle.survivorsIn("String s = payment.getBody();").map { it.name })
        assertEquals(listOf("setBody"), oracle.survivorsIn("payment.setBody(s);").map { it.name })
    }

    /** Java declares `String getBody()`; Kotlin writes `javaObj.body`. `body` is declared nowhere. */
    @Test
    fun `a Java getter surviving under its Kotlin property is a finding`() {
        val oracle = oracleOf(JavaMethod("getBody"))

        assertEquals(listOf("body"), oracle.survivorsIn("val s = javaObj.body").map { it.name })
        assertEquals(listOf("getBody"), oracle.survivorsIn("String s = javaObj.getBody();").map { it.name })
    }

    @Test
    fun `an is-prefixed name survives as itself in both directions`() {
        assertEquals(
            listOf("isSettled"),
            oracleOf(KotlinProperty("isSettled", mutable = false)).survivorsIn("if (p.isSettled()) {").map { it.name },
        )
        assertEquals(
            listOf("isSettled"),
            oracleOf(JavaMethod("isSettled")).survivorsIn("if (p.isSettled) {").map { it.name },
        )
    }

    @Test
    fun `a facade name surviving into the output is a finding`() {
        assertEquals(
            listOf("LedgerKt"),
            oracleOf(KotlinFacade("Ledger.kt", jvmName = null)).survivorsIn("LedgerKt.settle(1);").map { it.name },
        )
        assertEquals(
            listOf("Settlements"),
            oracleOf(KotlinFacade("Ledger.kt", jvmName = "Settlements")).survivorsIn("Settlements.settle(1);").map { it.name },
        )
    }

    @Test
    fun `a JvmName surviving into the output is a finding`() {
        val oracle = oracleOf(KotlinFunction("settle"), JvmName("settleNow", of = "settle"))

        assertEquals(listOf("settleNow"), oracle.survivorsIn("Type1.settleNow();").map { it.name })
    }

    @Test
    fun `a named companion surviving into the output is a finding`() {
        val oracle = oracleOf(Written("Registry"))

        assertEquals(listOf("Registry"), oracle.survivorsIn("Type1.Registry.lookup();").map { it.name })
    }

    /**
     * **The qualified spelling is its own finding**, and it is what still sees an object whose bare
     * name collides with a library's: `Builder` is subtracted, `Builder.INSTANCE` is not, and an
     * anonymised `Type1.INSTANCE` names nothing the project owns.
     */
    @Test
    fun `an object surviving through its instance is a finding, qualified`() {
        val oracle = LeakOracle.over(SourceSpellings.of(listOf(KotlinObject("Builder"))), declaredByLibraries = setOf("Builder"))

        assertEquals(listOf("Builder.INSTANCE"), oracle.survivorsIn("Builder.INSTANCE.run();").map { it.name })
        assertEquals(listOf("Builder.INSTANCE"), oracle.survivorsIn("Builder .INSTANCE.run();").map { it.name })
        assertEquals(emptyList<String>(), oracle.survivorsIn("Type1.INSTANCE.run();").map { it.name })
    }

    @Test
    fun `an internal function surviving under its mangled name is a finding`() {
        val oracle = oracleOf(KotlinFunction("settle", internalIn = "billing"))

        assertEquals(listOf("settle\$billing"), oracle.survivorsIn("type1.settle\$billing();").map { it.name })
    }

    /** `f$default` is the compiler's and nobody writes it, so it is not a spelling of `f`. */
    @Test
    fun `a bytecode-only mangling is not a finding`() {
        val oracle = oracleOf(KotlinFunction("settle", internalIn = "billing"), KotlinObject("Settlement"))

        assertEquals(emptyList<String>(), oracle.survivorsIn("method1\$default(); access\$method1(); Type2.DefaultImpls.x();").map { it.name })
    }

    @Test
    fun `names the language fixes are not findings`() {
        val oracle = oracleOf(KotlinProperty("merchantRef", mutable = false), KotlinObject("Settlement"))

        assertEquals(
            emptyList<String>(),
            oracle.survivorsIn("Type1.Companion.of(it.component1()); Type2.INSTANCE.copy(field3 = x)").map { it.name },
        )
    }

    /**
     * **The triage annotation.** A row the closure put in the universe says so, and says from what —
     * the new class of false positive is read past on that basis, the way `com` is.
     */
    @Test
    fun `a finding that entered the universe by closure says so, and a declared one does not`() {
        val oracle = oracleOf(KotlinProperty("body", mutable = false), Written("MerchantLedger"))

        val survivors = oracle.survivorsIn("MerchantLedger m = x.getBody();").associateBy { it.name }

        assertNull(survivors.getValue("MerchantLedger").derivation)
        assertNotNull(survivors.getValue("getBody").derivation)
        assertTrue("body" in survivors.getValue("getBody").derivation.orEmpty()) {
            "The annotation does not name the declaration it came from: ${survivors.getValue("getBody").derivation}"
        }
    }

    /** The library set only ever subtracts — derived spellings included. */
    @Test
    fun `a derived spelling the libraries also declare is subtracted like any other`() {
        val oracle = LeakOracle.over(
            SourceSpellings.of(listOf(KotlinProperty("body", mutable = false))),
            declaredByLibraries = setOf("getBody"),
        )

        assertEquals(listOf("body"), oracle.survivorsIn("x.getBody(); y.body").map { it.name })
    }

    // ── Refusals ─────────────────────────────────────────────────────────────────────────────────

    /** A check that has nothing to check is not a check. */
    @Test
    fun `an oracle whose universe came out empty refuses to be built`() {
        assertThrows(IllegalStateException::class.java) {
            LeakOracle.over(SourceSpellings.of(listOf(Written("Builder"))), declaredByLibraries = setOf("Builder"))
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
     * The library set is not a subset of the universe — the index answers for names the project
     * never declares — so the size a report quotes cannot be arrived at by subtracting the two
     * sizes. It is the oracle's own count or it is wrong.
     */
    @Test
    fun `the universe size counts what is left, not what was subtracted`() {
        val oracle = LeakOracle.over(
            SourceSpellings.of(listOf(Written("MerchantLedger"), Written("Builder"))),
            declaredByLibraries = setOf("Builder", "ArrayList", "Runnable"),
        )

        assertEquals(1, oracle.size)
    }

    private fun oracleOver(vararg names: String) = oracleOf(*names.map(::Written).toTypedArray())

    private fun oracleOf(vararg declarations: Declaration) =
        LeakOracle.over(SourceSpellings.of(declarations.toList()), declaredByLibraries = emptySet())
}
