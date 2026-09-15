package com.snippetveil.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * **The third bucket: a name the table holds, in a spelling SnippetVeil never sent.**
 *
 * > Strip a known accessor prefix, decapitalise, look up the remainder; a hit is this bucket.
 *
 * A model writes `setField1` where the declaration is a `val` and the mapping holds `field1`. Below
 * the counter and in neither table, it used to be reported *beyond the recent-history window* — the
 * one bucket whose whole job is to say *stop looking* — about a name sitting one row away.
 *
 * **Half of these are about what the bucket must not do**, for the reason half of `DeanonymizeTest`
 * is: the word is never restored, an ordinary accessor never reaches it, and neither of the other two
 * buckets is swallowed by it.
 */
class UnsentSpellingTest {

    /** A `val` has no setter, so PSI reports none and no row is written for one. */
    @Test
    fun `a setter written against a val is a known name in an unsent spelling`() {
        val reply = "Call `payment.setField1(x)` before settling."

        val reversal = deanonymize(reply, Sidecar.EMPTY, VAL_ROWS)

        assertEquals(listOf(Unrestored("setField1", UnrestoredReason.UNSENT_SPELLING)), reversal.unrestored)
    }

    /**
     * **The word is not restored**: nothing is manufactured, so the reply keeps `setField1`
     * byte-for-byte and the restored count does not include it.
     */
    @Test
    fun `a word in the unsent-spelling bucket is left exactly as the reply wrote it`() {
        val reply = "Call `payment.setField1(x)` once `payment.getField1()` is known."

        val reversal = deanonymize(reply, Sidecar.EMPTY, VAL_ROWS)

        assertEquals("Call `payment.setField1(x)` once `payment.getMerchantRef()` is known.", reversal.text)
        assertEquals(listOf("getField1"), reversal.restored)
    }

    /**
     * `val isSettled` is its own getter, so the row is `isField1` — and `getIsField1` is a guess at a
     * prefix no placeholder shape even recognises. The lookup is what finds it, not the shape.
     */
    @Test
    fun `a wrong-prefix guess at an is-accessor is a known name in an unsent spelling`() {
        val rows = LedgerSnapshot(
            mapOf(
                "field:class:com.acme.Payment#isSettled" to MintedName("field1", "isSettled"),
                "method:class:com.acme.Payment#isSettled" to MintedName("isField1", "isSettled"),
            ),
            nextNumber = 2,
        )

        val reversal = deanonymize("if (payment.getIsField1()) return", Sidecar.EMPTY, rows)

        assertEquals("if (payment.getIsField1()) return", reversal.text)
        assertEquals(listOf(Unrestored("getIsField1", UnrestoredReason.UNSENT_SPELLING)), reversal.unrestored)
    }

    /** A `@JvmSynthetic` property has no accessor Java can see, so the mapping holds the field alone. */
    @Test
    fun `an accessor of a JvmSynthetic member is a known name in an unsent spelling`() {
        val rows = LedgerSnapshot(mapOf(FIELD_KEY to MintedName("field1", "merchantRef")), nextNumber = 2)

        val reversal = deanonymize("payment.getField1()", Sidecar.EMPTY, rows)

        assertEquals(listOf(Unrestored("getField1", UnrestoredReason.UNSENT_SPELLING)), reversal.unrestored)
    }

    /** The strip asks the same tables the restore does, and the sidecar is one of them. */
    @Test
    fun `a spelling of a name only the sidecar holds is in the unsent-spelling bucket too`() {
        val window = Sidecar.EMPTY.recording(RecordedInvocation(Instant.now(), mapOf("local3" to "draft")))

        val reversal = deanonymize("getLocal3", window, LedgerSnapshot(emptyMap(), nextNumber = 4))

        assertEquals(listOf(Unrestored("getLocal3", UnrestoredReason.UNSENT_SPELLING)), reversal.unrestored)
    }

    /**
     * **An ordinary accessor still restores and never reaches this bucket.** A regression in the rows
     * would show up here as a bucket hit rather than as silence, which is why the unrestored list is
     * asserted empty rather than left unread.
     */
    @Test
    fun `an ordinary accessor with its row decodes and is not in the bucket`() {
        val reversal = deanonymize("payment.getField1()", Sidecar.EMPTY, VAL_ROWS)

        assertEquals("payment.getMerchantRef()", reversal.text)
        assertEquals(emptyList<Unrestored>(), reversal.unrestored, "the getter's row was not read, or the strip pre-empted it")
    }

    /**
     * **The strip cannot swallow either of the other two buckets.** A word that strips to nothing the
     * table knows is still evicted below the counter and still foreign above it.
     */
    @Test
    fun `a spelling that strips to nothing the table knows keeps its counter bucket`() {
        val reversal = deanonymize("setField3 and setField9", Sidecar.EMPTY, VAL_ROWS)

        assertEquals(
            listOf(
                Unrestored("setField3", UnrestoredReason.EVICTED),
                Unrestored("setField9", UnrestoredReason.FOREIGN),
            ),
            reversal.unrestored,
        )
    }

    /**
     * **The strip runs only after the table lookup fails.** `isField2` is a placeholder the mapping
     * holds, and it is also `is` in front of `field2`, which the mapping holds too — so a strip tried
     * first would report a name that decodes as one that cannot.
     */
    @Test
    fun `a word that decodes is restored even when it strips to another placeholder`() {
        val rows = LedgerSnapshot(
            mapOf(
                "field:class:com.acme.Payment#settled" to MintedName("field2", "settled"),
                "method:class:com.acme.Payment#isSettled" to MintedName("isField2", "isSettled"),
            ),
            nextNumber = 3,
        )

        val reversal = deanonymize("isField2", Sidecar.EMPTY, rows)

        assertEquals("isSettled", reversal.text)
        assertEquals(emptyList<Unrestored>(), reversal.unrestored)
    }

    /**
     * **No wider than an accessor.** A bare prefix strips to nothing, a lower-case remainder is not a
     * spelling [derivedAccessorPlaceholder] writes, and a class placeholder decapitalised is not the
     * class — so none of these is claimed out of the model's prose.
     */
    @Test
    fun `a word that is not a prefix in front of a capitalised known placeholder is not claimed`() {
        val rows = LedgerSnapshot(
            mapOf(FIELD_KEY to MintedName("field1", "merchantRef"), "class:com.acme.Payment" to MintedName("Type2", "Payment")),
            nextNumber = 3,
        )

        val reversal = deanonymize("get is set getfield1 getType2 settle", Sidecar.EMPTY, rows)

        assertEquals(emptyList<Unrestored>(), reversal.unrestored)
    }

    /** The details list's words, which state what the table holds and never what the model meant. */
    @Test
    fun `the bucket says the name is known and the spelling was never sent`() {
        assertEquals("a name this project knows, in a spelling SnippetVeil never sent", UnrestoredReason.UNSENT_SPELLING.message)
    }
}

private const val FIELD_KEY = "field:class:com.acme.Payment#merchantRef"

/** A `val merchantRef` as the mapping holds it: the field, its getter, and no setter. */
private val VAL_ROWS = LedgerSnapshot(
    mapOf(
        FIELD_KEY to MintedName("field1", "merchantRef"),
        "method:class:com.acme.Payment#getMerchantRef" to MintedName("getField1", "getMerchantRef"),
    ),
    nextNumber = 5,
)
