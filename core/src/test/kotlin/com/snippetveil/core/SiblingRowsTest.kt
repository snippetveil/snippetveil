package com.snippetveil.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **A row for every accessor the plan reported, snippet or no snippet** — the engine's half of it,
 * against plan literals.
 *
 * > Record a row for every spelling by which PSI says the declaration can be written — snippet or no
 * > snippet.
 *
 * The defect these close is not under-recovery. A field anonymized with its getter outside the
 * selection left the mapping holding `field1 = merchantRef` and nothing else, so a reply writing
 * `getField1()` — a name the model derives by itself — decoded to nothing and was reported *beyond
 * the recent-history window*: a false statement about a name the product was holding one row away.
 *
 * What the builder reads off PSI is its own business and is asserted over real PSI in the plugin.
 * These assert what the engine does once a sibling is reported: a row rendered from the placeholder
 * the field already has, no number, and no key ever rewritten.
 */
class SiblingRowsTest {

    @Test
    fun `a field anonymized without its accessors writes a row for each accessor the plan reported`() {
        val result = anonymize(planOf("merchantRef = x;", merchantRef(GETTER, SETTER)), AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("field1 = x;", result.text)
        assertEquals(
            mapOf(
                FIELD_KEY to MintedName("field1", "merchantRef"),
                GETTER.key to MintedName("getField1", "getMerchantRef"),
                SETTER.key to MintedName("setField1", "setMerchantRef"),
            ),
            result.delta.placeholders,
            ROWS,
        )
    }

    /** The field's other accessors are its siblings too, when it is an accessor that reached the output. */
    @Test
    fun `an accessor spliced alone writes rows for its field and for the field's other accessors`() {
        val getter = accessor("getMerchantRef", "get", siblings = listOf(GETTER, SETTER))

        val result = anonymize(planOf("getMerchantRef();", getter), AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("getField1();", result.text)
        assertEquals(
            mapOf(
                FIELD_KEY to MintedName("field1", "merchantRef"),
                GETTER.key to MintedName("getField1", "getMerchantRef"),
                SETTER.key to MintedName("setField1", "setMerchantRef"),
            ),
            result.delta.placeholders,
            ROWS,
        )
    }

    /** Kotlin's `val isSettled`: the getter PSI reports is `isSettled`, and its row renders under `is`. */
    @Test
    fun `an is-accessor's row keeps the prefix its own name was written with`() {
        val field = symbol(
            "isSettled",
            SymbolRole.FIELD,
            SymbolOrigin.IN_CONTENT,
            key = "field:class:com.acme.Payment#isSettled",
            keyIsQualified = true,
            siblingAccessors = listOf(
                symbol(
                    "isSettled",
                    SymbolRole.METHOD,
                    SymbolOrigin.IN_CONTENT,
                    key = "method:class:com.acme.Payment#isSettled",
                    keyIsQualified = true,
                    accessor = AccessorEvidence("field:class:com.acme.Payment#isSettled", "isSettled", "is", fieldKeyIsQualified = true),
                ),
            ),
        )

        val result = anonymize(planOf("isSettled", field), AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals(MintedName("isField1", "isSettled"), result.delta.placeholders["method:class:com.acme.Payment#isSettled"], ROWS)
    }

    /**
     * **The counter does not move.** Two invocations that differ only in how many siblings were
     * reported hand out the same numbers, render the same text and leave the counter in one place.
     */
    @Test
    fun `sibling rows allocate no number`() {
        val text = "merchantRef = refund;"
        val refund = qualified("refund", SymbolRole.FIELD, "field:class:com.acme.Payment#refund")

        val bare = anonymize(planOf(text, merchantRef(), refund), AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)
        val eager = anonymize(planOf(text, merchantRef(GETTER, SETTER), refund), AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals(bare.text, eager.text, COUNTER)
        assertEquals(bare.delta.nextNumber, eager.delta.nextNumber, COUNTER)
        assertEquals(bare.delta.placeholders, eager.delta.placeholders - GETTER.key - SETTER.key, COUNTER)
        assertEquals(setOf(GETTER.key, SETTER.key), eager.delta.placeholders.keys - bare.delta.placeholders.keys, COUNTER)
    }

    /**
     * **No migration, and none is possible.** A mapping written before sibling rows existed keeps every
     * row it had, pointing at the same name, and gains its sibling rows the next time the declaration
     * is anonymized — like any other row.
     */
    @Test
    fun `a mapping written before sibling rows keeps every row and gains them on the next paste`() {
        val payment = qualified("Payment", SymbolRole.TYPE, "class:com.acme.Payment")
        val before = LedgerSnapshot(
            mapOf(payment.key to MintedName("Type1", "Payment"), FIELD_KEY to MintedName("field2", "merchantRef")),
            nextNumber = 3,
        )

        val result = anonymize(planOf("Payment merchantRef;", payment, merchantRef(GETTER, SETTER)), AnonymizationSettings.DEFAULTS, before)
        val after = before + result.delta

        assertEquals("Type1 field2;", result.text)
        assertEquals(3, result.delta.nextNumber, COUNTER)
        assertEquals(
            mapOf(GETTER.key to MintedName("getField2", "getMerchantRef"), SETTER.key to MintedName("setField2", "setMerchantRef")),
            result.delta.placeholders,
            "only the sibling rows are new, and they render from the placeholder the field already had",
        )
        before.placeholders.forEach { (key, row) -> assertEquals(row, after.placeholders[key], "$key was rewritten") }
    }

    /** A key the mapping already holds is a name already sent, and a sibling row never replaces it. */
    @Test
    fun `a sibling key the mapping already holds is never rewritten`() {
        val before = LedgerSnapshot(
            mapOf(FIELD_KEY to MintedName("field3", "merchantRef"), GETTER.key to MintedName("method7", "getMerchantRef")),
            nextNumber = 8,
        )

        val result = anonymize(planOf("merchantRef", merchantRef(GETTER, SETTER)), AnonymizationSettings.DEFAULTS, before)

        assertFalse(GETTER.key in result.delta.placeholders, "the getter's row was rewritten: ${result.delta.placeholders}")
        assertEquals(MintedName("method7", "getMerchantRef"), (before + result.delta).placeholders[GETTER.key])
        assertEquals(MintedName("setField3", "setMerchantRef"), result.delta.placeholders[SETTER.key])
    }

    /** The word that reached the output is the word written down, and a renamed field's siblings carry it. */
    @Test
    fun `a renamed field's sibling rows carry the renamed stem`() {
        val settings = AnonymizationSettings(renamedStems = mapOf(FIELD_KEY to "merchant"))

        val result = anonymize(planOf("merchantRef", merchantRef(GETTER, SETTER)), settings, LedgerSnapshot.EMPTY)

        assertEquals(MintedName("getMerchant1", "getMerchantRef"), result.delta.placeholders[GETTER.key], ROWS)
        assertEquals(MintedName("setMerchant1", "setMerchantRef"), result.delta.placeholders[SETTER.key], ROWS)
        assertTrue(
            result.delta.mintedStems.containsAll(setOf("merchant", "getMerchant", "setMerchant")),
            "a derived word is a word of its own, and is recorded as one: ${result.delta.mintedStems}",
        )
    }

    /** Injectivity outranks eagerness: a name that already means something in the output is not a row. */
    @Test
    fun `a sibling whose placeholder collides with a surviving name gets no row`() {
        val library = symbol("getField1", SymbolRole.METHOD, SymbolOrigin.LIBRARY, key = "method:class:org.vendor.Ctx#getField1")

        val result = anonymize(planOf("merchantRef = getField1();", merchantRef(GETTER, SETTER), library), AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("field1 = getField1();", result.text)
        assertFalse(GETTER.key in result.delta.placeholders, "getField1 is a library name in this output")
        assertEquals(MintedName("setField1", "setMerchantRef"), result.delta.placeholders[SETTER.key])
    }

    /** Only a qualified key is written down, and a sibling is held to the same rule as anything else. */
    @Test
    fun `a sibling whose key is not qualified gets no row`() {
        val positional = symbol(
            "getMerchantRef",
            SymbolRole.METHOD,
            SymbolOrigin.IN_CONTENT,
            key = "method:class:file@40#getMerchantRef",
            accessor = AccessorEvidence(FIELD_KEY, "merchantRef", "get", fieldKeyIsQualified = true),
        )

        val result = anonymize(planOf("merchantRef", merchantRef(positional)), AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals(setOf(FIELD_KEY), result.delta.placeholders.keys)
    }

    /** A field that kept its own name renders nothing a sibling could derive from. */
    @Test
    fun `a field that was not replaced writes no sibling rows`() {
        val preserved = AnonymizationSettings(preservedSymbols = setOf(FIELD_KEY))

        val result = anonymize(planOf("merchantRef", merchantRef(GETTER, SETTER)), preserved, LedgerSnapshot.EMPTY)

        assertEquals("merchantRef", result.text)
        assertEquals(emptyMap<String, MintedName>(), result.delta.placeholders)
    }

    /** An accessor Java forbids from being renamed keeps its name wherever it is written, so it has no placeholder to record. */
    @Test
    fun `a sibling whose name is constrained gets no row`() {
        val constrained = accessor(
            "getMerchantRef",
            "get",
            overrideRoots = listOf(OverrideRoot("method:class:org.vendor.HasRef#getMerchantRef", SymbolOrigin.LIBRARY, keyIsQualified = true)),
        )

        val result = anonymize(planOf("merchantRef", merchantRef(constrained)), AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals(setOf(FIELD_KEY), result.delta.placeholders.keys)
    }

    /** A sibling's row is filed under the key its placeholder would be handed out against, which is the chain's root. */
    @Test
    fun `a sibling that overrides is filed under its chain's root`() {
        val root = "method:class:com.acme.HasRef#getMerchantRef"
        val overriding = accessor(
            "getMerchantRef",
            "get",
            overrideRoots = listOf(OverrideRoot(root, SymbolOrigin.IN_CONTENT, keyIsQualified = true)),
        )

        val result = anonymize(planOf("merchantRef", merchantRef(overriding)), AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals(MintedName("getField1", "getMerchantRef"), result.delta.placeholders[root])
        assertFalse(overriding.key in result.delta.placeholders, "filed under the overriding end rather than the root")
    }

    /** The table explains the words in the output, and a sibling row is not one of them; nor does it count. */
    @Test
    fun `a sibling row is not a row of the table and is not counted`() {
        val result = anonymize(planOf("merchantRef", merchantRef(GETTER, SETTER)), AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals(mapOf("field1" to "merchantRef"), result.mapping)
        assertEquals(Triple(1, 0, 0), result.counts.let { Triple(it.replaced, it.unknown, it.preserved) })
    }

    /**
     * **The defect, end to end in `:core`.** A reply writing an accessor the snippet never showed is
     * restored from the mapping — and the same reply against the mapping as it stood before is the
     * defect this ticket was filed over. That control used to read *beyond the recent-history window*;
     * it now reads as a known name in a spelling never sent, which is still unrestored, and is the
     * shape a regression in the rows would take.
     */
    @Test
    fun `a reply naming an accessor the snippet never showed is restored rather than called evicted`() {
        val reply = "Null-check `payment.getField1()` first."
        val nobodyRemembers = Sidecar(emptyList())

        val eager = anonymize(planOf("merchantRef", merchantRef(GETTER, SETTER)), AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)
        val restored = deanonymize(reply, nobodyRemembers, LedgerSnapshot.EMPTY + eager.delta)

        assertEquals("Null-check `payment.getMerchantRef()` first.", restored.text)
        assertEquals(emptyList<Unrestored>(), restored.unrestored)

        // The control: no sibling reported, and the reply is the defect verbatim.
        val spliced = anonymize(planOf("merchantRef", merchantRef()), AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)
        val gone = deanonymize(reply, nobodyRemembers, LedgerSnapshot.EMPTY + spliced.delta)

        assertEquals("Null-check `payment.getField1()` first.", gone.text)
        assertEquals(listOf(Unrestored("getField1", UnrestoredReason.UNSENT_SPELLING)), gone.unrestored)
    }
}

private const val ROWS = "a row for every reported accessor, snippet or no snippet"
private const val COUNTER = "rows, not numbers"

private const val FIELD_KEY = "field:class:com.acme.Payment#merchantRef"

/** An accessor of `merchantRef` on `com.acme.Payment`, as a builder describes one. */
private fun accessor(
    name: String,
    prefix: String,
    overrideRoots: List<OverrideRoot> = emptyList(),
    siblings: List<SymbolEvidence> = emptyList(),
) = symbol(
    name,
    SymbolRole.METHOD,
    SymbolOrigin.IN_CONTENT,
    key = "method:class:com.acme.Payment#$name",
    overrideRoots = overrideRoots,
    accessor = AccessorEvidence(FIELD_KEY, "merchantRef", prefix, fieldKeyIsQualified = true),
    keyIsQualified = true,
    siblingAccessors = siblings,
)

private val GETTER = accessor("getMerchantRef", "get")
private val SETTER = accessor("setMerchantRef", "set")

/** The field, carrying whichever of its accessors the plan reported. */
private fun merchantRef(vararg siblings: SymbolEvidence) = symbol(
    "merchantRef",
    SymbolRole.FIELD,
    SymbolOrigin.IN_CONTENT,
    key = FIELD_KEY,
    keyIsQualified = true,
    siblingAccessors = siblings.toList(),
)
