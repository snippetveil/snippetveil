package com.snippetveil.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **A placeholder means the same thing on Tuesday that it meant on Monday.**
 *
 * The deciding argument is fidelity rather than privacy. The common workflow is a *conversation* —
 * paste a method, the AI asks to see the collaborator, paste that too — and fresh-per-invocation
 * numbering makes **the second paste contradict the first**: `CustomerService` arrives as `Type3`
 * while `Type1` has already been handed to `OrderRepo`, and the model then reasons confidently about
 * types that do not exist. That is a *plausible* artifact producing confident wrong answers, which
 * is the prohibited class of failure.
 *
 * These are the single-invocation-shaped statements of the rule — what one call does with a ledger
 * handed to it, and what it hands back. The statements that only exist over a *sequence* are in
 * [LedgerHistoryTest], and they are there because this class cannot express them: a second paste
 * contradicting the first has no single-invocation form, by construction.
 */
class LedgerStabilityTest {

    /**
     * The whole point, in one test: a symbol the ledger has already named keeps that name, and the
     * invocation allocates nothing for it.
     */
    @Test
    fun `a symbol already in the ledger keeps the placeholder it was given`() {
        val payment = qualified("Payment", SymbolRole.TYPE, "class:com.acme.Payment")
        val ledger = LedgerSnapshot(mapOf(payment.key to "Type1"), nextNumber = 2)

        val result = anonymize(planOf("Payment p;", payment), AnonymizationSettings.DEFAULTS, ledger)

        result.assertRendersAs(STABILITY, "Payment", "Type1")
        assertEquals(emptyMap<String, String>(), result.delta.placeholders)
        assertEquals(2, result.delta.nextNumber, "nothing was allocated, so nothing moved")
    }

    /**
     * **Stability outranks allocation ordering.** A symbol already in the ledger keeps its number
     * wherever it appears in this selection; first-occurrence order now decides only which of the
     * *newcomers* takes the next number.
     */
    @Test
    fun `a known symbol keeps its number however late in the snippet it appears`() {
        val payment = qualified("Payment", SymbolRole.TYPE, "class:com.acme.Payment")
        val refund = qualified("Refund", SymbolRole.TYPE, "class:com.acme.Refund")
        val ledger = LedgerSnapshot(mapOf(payment.key to "Type9"), nextNumber = 10)

        val result = anonymize(planOf("Refund r; Payment p;", refund, payment), AnonymizationSettings.DEFAULTS, ledger)

        result.assertRendersAs(STABILITY, "Payment", "Type9")
        result.assertRendersAs(STABILITY, "Refund", "Type10")
        assertEquals("Type10 r; Type9 p;", result.text)
    }

    /**
     * **Only a qualified key is written down.** A local is identified by where it is written, which
     * is stable for exactly as long as the file is not edited — so it renders like anything else and
     * leaves no trace in the delta.
     */
    @Test
    fun `a symbol with no qualified key is allocated and not written down`() {
        val payment = qualified("Payment", SymbolRole.TYPE, "class:com.acme.Payment")
        val local = symbol("draft", SymbolRole.LOCAL, SymbolOrigin.IN_CONTENT, key = "local:file@17")

        val result = anonymize(
            planOf("Payment draft;", payment, local),
            AnonymizationSettings.DEFAULTS,
            LedgerSnapshot.EMPTY,
        )

        assertEquals("Type1 local2;", result.text)
        assertEquals(mapOf(payment.key to "Type1"), result.delta.placeholders)
        assertFalse(local.key in result.delta.placeholders, "a positional key must not be written down")
    }

    /**
     * **The load-bearing detail: an unpersisted symbol burns a number rather than reusing one.**
     *
     * Asserted directly, because it is the one thing that keeps the invariant alive across time.
     * Anonymous members occupy the same `field` and `method` namespaces as persisted symbols;
     * without burning, `field44` could be an anonymous member today and a genuine persisted field
     * next month, and an old reply mentioning `field44` would decode to the wrong name.
     */
    @Test
    fun `an unpersisted symbol advances the shared counter`() {
        val anonymousMember = symbol(
            "state",
            SymbolRole.FIELD,
            SymbolOrigin.IN_CONTENT,
            key = "field:class:file@40#state",
        )

        val result = anonymize(planOf("state;", anonymousMember), AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("field1;", result.text)
        assertEquals(emptyMap<String, String>(), result.delta.placeholders)
        assertEquals(2, result.delta.nextNumber, "the number it used must not be handed out again")
    }

    /**
     * **A string literal has no key, so it is never written down** — and it burns a number like
     * anything else. This is right on its own merits beyond the keying argument: literal text is the
     * most directly sensitive content the product handles, and this keeps it out of the durable
     * on-disk artifact entirely.
     */
    @Test
    fun `a redacted literal is never written down and still burns its number`() {
        val plan = planOf("log(\"merchantId\");").withLiteral("\"merchantId\"")

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("log(\"str1\");", result.text)
        assertEquals(emptyMap<String, String>(), result.delta.placeholders)
        assertEquals(2, result.delta.nextNumber)
    }

    /**
     * **Renames are not tracked, and the old entry is kept.** The renamed class is a new key and
     * takes the next number; the entry naming what was actually sent stays exactly as it was.
     *
     * Deleting the stale key is the trap: an old reply saying *"fix `Type1.charge()`"* would then
     * decode to a name that did not exist when the snippet was sent — which reads correct and is
     * wrong.
     */
    @Test
    fun `renaming a class produces a new entry and retains the old one`() {
        val before = qualified("Payment", SymbolRole.TYPE, "class:com.acme.Payment")
        val after = qualified("Charge", SymbolRole.TYPE, "class:com.acme.Charge")

        val first = anonymize(planOf("Payment p;", before), AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)
        val ledger = LedgerSnapshot.EMPTY + first.delta
        val second = anonymize(planOf("Charge c;", after), AnonymizationSettings.DEFAULTS, ledger)

        val committed = ledger + second.delta

        assertEquals(
            mapOf(before.key to "Type1", after.key to "Type2"),
            committed.placeholders,
            "the old key is a record of what was sent, and is never rewritten or removed",
        )
    }

    /**
     * **The stated limit of stability, pinned rather than left to be discovered.**
     *
     * A placeholder the ledger already issued is emitted even when this snippet preserves a name
     * spelled the same way, so the word appears twice standing for two things. That is the right way
     * round — the alternative hands the symbol a fresh number and makes this paste contradict an
     * earlier one, which is the failure the whole design exists to prevent — but it is a real cost,
     * and a cost nothing asserts is one that turns into a surprise.
     */
    @Test
    fun `a placeholder already issued is kept even when the snippet preserves that very word`() {
        val payment = qualified("Payment", SymbolRole.TYPE, "class:com.acme.Payment")
        val vendor = symbol("Type1", SymbolRole.TYPE, SymbolOrigin.LIBRARY, key = "class:org.vendor.Type1")
        val ledger = LedgerSnapshot(mapOf(payment.key to "Type1"), nextNumber = 2)

        val result = anonymize(planOf("Type1 a; Payment b;", vendor, payment), AnonymizationSettings.DEFAULTS, ledger)

        assertEquals("Type1 a; Type1 b;", result.text)
    }

    /**
     * A chain's placeholder is handed out against **the root's** key, so it is the root's key that is
     * written down — and a chain whose root is identified by a position is not written down at all,
     * however qualified the overriding method is. Writing the overriding method's key instead would
     * store a name the next invocation never looks up.
     */
    @Test
    fun `an override chain is written down under the root's key, or not at all`() {
        val qualifiedRoot = qualified("run", SymbolRole.METHOD, "method:class:com.acme.Audited#run")
        val overriding = qualified(
            "settle",
            SymbolRole.METHOD,
            "method:class:com.acme.Payment#settle",
            overrideRoots = listOf(OverrideRoot(qualifiedRoot.key, SymbolOrigin.IN_CONTENT, keyIsQualified = true)),
        )
        val anonymousRoot = qualified(
            "handle",
            SymbolRole.METHOD,
            "method:class:com.acme.Payment#handle",
            overrideRoots = listOf(OverrideRoot("method:class:file@88#handle", SymbolOrigin.IN_CONTENT)),
        )

        val result = anonymize(
            planOf("settle handle", overriding, anonymousRoot),
            AnonymizationSettings.DEFAULTS,
            LedgerSnapshot.EMPTY,
        )

        assertEquals(mapOf(qualifiedRoot.key to "method1"), result.delta.placeholders)
        assertTrue(overriding.key !in result.delta.placeholders, "the chain is named by its root, not by this end of it")
        assertEquals(3, result.delta.nextNumber, "the positional chain still burnt its number")
    }
}

private const val STABILITY = "stable placeholders"

/** A symbol whose key the builder derived from a fully-qualified name, which is what gets written down. */
internal fun qualified(
    name: String,
    role: SymbolRole,
    key: String,
    overrideRoots: List<OverrideRoot> = emptyList(),
) = symbol(
    name = name,
    role = role,
    origin = SymbolOrigin.IN_CONTENT,
    key = key,
    overrideRoots = overrideRoots,
    keyIsQualified = true,
)
