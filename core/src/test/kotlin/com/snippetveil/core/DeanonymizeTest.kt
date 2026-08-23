package com.snippetveil.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * **The reversal contract, stated over replies rather than over snippets.**
 *
 * > Match only the exact placeholder strings SnippetVeil minted, on word boundaries,
 * > case-sensitively. Everything else in the reply is left verbatim.
 *
 * Half of these are about what is **not** restored, and that is the right balance rather than an
 * accident: the failures worth writing down here are all the same failure, which is a token the model
 * invented being absorbed into a name the user then believes. Under-recovery is visible and costs a
 * hop; over-recovery is invisible and manufactures text the AI never wrote.
 */
class DeanonymizeTest {

    /**
     * The thing itself: a reply comes back holding placeholders, and the names go back in place.
     *
     * The reply mixes a mapped type and method with an ephemeral local, because that mix is the
     * ordinary case — an AI's answer talks about a class and about the variable inside it in one
     * sentence, and only one of the two is in the durable mapping.
     */
    @Test
    fun `placeholders in the reply are replaced in place`() {
        val reversal = deanonymize(
            "In `Type1.method2()` the `local3` is null before you validate it.",
            sidecarOf("local3" to "draft"),
            mappingOf(2, "Type1" to "Payment", "method2" to "settle"),
        )

        assertEquals("In `Payment.settle()` the `draft` is null before you validate it.", reversal.text)
        assertEquals(listOf("Type1", "method2", "local3"), reversal.restored)
        assertEquals(emptyList<Unrestored>(), reversal.unrestored)
    }

    /**
     * **Case-sensitively.** `type1` is not `Type1`, and it is not reported as an unrestored
     * placeholder either — nothing this engine mints is spelled that way, so it is not a word we have
     * any claim on.
     */
    @Test
    fun `a wrong-cased placeholder is not restored and is not ours`() {
        val reversal = deanonymize("the type1 variable", sidecarOf(), mappingOf(9, "Type1" to "Payment"))

        assertEquals("the type1 variable", reversal.text)
        assertEquals(emptyList<String>(), reversal.restored)
        assertEquals(emptyList<Unrestored>(), reversal.unrestored)
        assertFalse(reversal.found, "a lower-cased word was claimed as a placeholder")
    }

    /**
     * **On word boundaries.** `Type1Test` is a name the model made up out of one of ours, and
     * absorbing the prefix would produce `PaymentTest` — a class that may not exist, in a reply that
     * never mentioned it.
     */
    @Test
    fun `a compound built out of a placeholder is not absorbed`() {
        val reversal = deanonymize(
            "add a Type1Test and a getType1 helper",
            sidecarOf(),
            mappingOf(9, "Type1" to "Payment"),
        )

        assertEquals("add a Type1Test and a getType1 helper", reversal.text)
        assertEquals(emptyList<String>(), reversal.restored)
    }

    /**
     * **The one reversal behaviour that would quietly mislead**, asserted directly: a token the AI
     * invented is left exactly as written.
     *
     * `local9` is placeholder-shaped and above this project's counter, so it is *reported* — the user
     * is told there is a word here they cannot read — and it is not *decoded*, because nothing has
     * ever stood for it. Reporting and restoring are different answers and this is the case that
     * separates them.
     */
    @Test
    fun `a token the model invented is reported and never absorbed`() {
        val reversal = deanonymize("rename local9 to something clearer", sidecarOf(), mappingOf(4))

        assertEquals("rename local9 to something clearer", reversal.text)
        assertEquals(listOf("local9"), reversal.unrestored.map { it.placeholder })
        assertEquals(UnrestoredReason.FOREIGN, reversal.unrestored.single().reason)
    }

    /**
     * **Sidecar first, then the mapping** — asserted the only way the order is observable, which is
     * over two tables that disagree.
     *
     * They cannot disagree in the product: a number is burnt when it is handed out, so one placeholder
     * stands for one symbol in the project's whole history. That is exactly why the order has to be
     * pinned by a test rather than by a run — nothing in ordinary use would ever show it.
     */
    @Test
    fun `the sidecar is consulted before the mapping`() {
        val reversal = deanonymize(
            "Type1",
            sidecarOf("Type1" to "FromTheSidecar"),
            mappingOf(2, "Type1" to "FromTheMapping"),
        )

        assertEquals("FromTheSidecar", reversal.text)
    }

    /**
     * **The two buckets, and the counter is the whole test.** Below it the number was handed out here
     * and the name is gone; at or above it this project never minted it.
     *
     * They are different facts prompting different actions: one says *stop looking*, the other says
     * *this is not a name*. Collapsed into a single count the user hunts for a mapping that either
     * never existed or is provably gone.
     */
    @Test
    fun `an unrestored placeholder is bucketed by which side of the counter it falls on`() {
        val reversal = deanonymize("str5 and str90 and str9", sidecarOf(), mappingOf(9))

        assertEquals(
            listOf("str5" to UnrestoredReason.EVICTED, "str90" to UnrestoredReason.FOREIGN, "str9" to UnrestoredReason.FOREIGN),
            reversal.unrestored.map { it.placeholder to it.reason },
            "the counter stands at 9, so 5 was minted here, and 9 and 90 have not been reached",
        )
    }

    /** The messages the details list shows, which are the ticket's own words for the two facts. */
    @Test
    fun `each bucket says which of the two things happened`() {
        assertEquals("beyond the recent-history window", UnrestoredReason.EVICTED.message)
        assertEquals("not from this project", UnrestoredReason.FOREIGN.message)
    }

    /**
     * **A reply with none of ours in it is not a reversal that found zero** — it is a reply the caller
     * must leave alone, and [Reversal.found] is what says so. Writing an identical string back would
     * be indistinguishable from a reversal that worked.
     */
    @Test
    fun `a reply with nothing of ours in it is untouched and reports nothing found`() {
        val reply = "Your loop is O(n^2); hoist the lookup out of it and use a HashMap."

        val reversal = deanonymize(reply, sidecarOf("local3" to "draft"), mappingOf(9, "Type1" to "Payment"))

        assertEquals(reply, reversal.text)
        assertFalse(reversal.found, "a reply with nothing of ours in it reported something found")
    }

    /**
     * **Placeholders that decoded to nothing are still *found*.** A reply full of words the user
     * cannot read is the opposite of a clean clipboard, and reporting it as *nothing found* would tell
     * them the one thing that is not true of it.
     */
    @Test
    fun `a reply whose placeholders all fell past the horizon still counts as found`() {
        val reversal = deanonymize("local4 is null", sidecarOf(), mappingOf(9))

        assertTrue(reversal.found, "an unrestorable placeholder is still a placeholder")
        assertEquals(emptyList<String>(), reversal.restored)
    }

    /** A redacted literal's text goes back inside its quotes, which is what it was replaced inside. */
    @Test
    fun `a literal placeholder is restored inside the quotes it was taken from`() {
        val reversal = deanonymize("""it fails on "str4"""", sidecarOf("str4" to "acme-live"), mappingOf(5))

        assertEquals("""it fails on "acme-live"""", reversal.text)
    }

    /**
     * **One left-to-right pass, so a restored name is never re-read.**
     *
     * The case that makes it matter is a literal, whose text is arbitrary content a person once
     * typed: here a string that happens to spell a placeholder. A second pass would decode a word
     * that only ever lived inside quotes, and hand back a name the snippet never contained.
     */
    @Test
    fun `a restored name is not itself decoded again`() {
        val reversal = deanonymize(
            "the literal str4 is the problem",
            sidecarOf("str4" to "Type1"),
            mappingOf(5, "Type1" to "Payment"),
        )

        assertEquals("the literal Type1 is the problem", reversal.text)
        assertEquals(listOf("str4"), reversal.restored)
    }

    /**
     * **Counted in distinct placeholders, in both directions.** *"12 restored"* is twelve names the
     * reader now has; a count of occurrences would report the same name twelve times and read as
     * twelve findings.
     */
    @Test
    fun `a placeholder written a dozen times is one restored placeholder`() {
        val reversal = deanonymize(
            "Type1 calls Type1 which calls Type1, and local8 twice: local8",
            sidecarOf(),
            mappingOf(2, "Type1" to "Payment"),
        )

        assertEquals(listOf("Type1"), reversal.restored)
        assertEquals(listOf("local8"), reversal.unrestored.map { it.placeholder })
        assertEquals("Payment calls Payment which calls Payment, and local8 twice: local8", reversal.text)
    }

    /**
     * **Every namespace the engine mints from is recognised**, including the derived accessor shape
     * `getField1` — which is not `prefix + number` and would fall through a recogniser written the
     * obvious way, leaving an unreadable word in the reply and a `0 not restored` beside it.
     */
    @Test
    fun `every minted namespace is recognised as a placeholder`() {
        val minted = SymbolRole.entries.map { it.placeholderPrefix + "3" } +
            listOf("Unknown3", "str3", "getField3", "isField3", "setField3")

        val reversal = deanonymize(minted.joinToString(" "), sidecarOf(), mappingOf(2))

        assertEquals(
            minted,
            reversal.unrestored.map { it.placeholder },
            "a namespace this engine mints from is not recognised as one",
        )
    }

    /**
     * **A mapping row that names no name is a row that knows nothing, not a row that decodes to
     * nothing.**
     *
     * Such a row exists only in a file written before the mapping kept names. Answering its blank at
     * face value would **delete the placeholder out of the reply** — the word simply gone, with
     * nothing in the output to show it ever happened — which is the one shape of wrong this design
     * refuses everywhere else. Left alone and reported, it is the same under-recovery as any other
     * placeholder the tables cannot answer for.
     */
    @Test
    fun `a mapping row with no name leaves the placeholder alone`() {
        val nameless = LedgerSnapshot(mapOf("class:com.acme.Payment" to MintedName("Type4", "")), nextNumber = 5)

        val reversal = deanonymize("Type4 is the one", sidecarOf(), nameless)

        assertEquals("Type4 is the one", reversal.text)
        assertEquals(listOf("Type4"), reversal.unrestored.map { it.placeholder })

        // Evicted rather than foreign, which is the closer of the two: the number *was* handed out
        // here, and what the user needs to hear is that the name is not coming back.
        assertEquals(UnrestoredReason.EVICTED, reversal.unrestored.single().reason)
    }

    /**
     * A leading digit makes the whole run one word, so nothing is restored out of the middle of a
     * token. The boundary is drawn round everything an identifier could contain rather than round
     * what one could *start* with, which is what makes that true.
     */
    @Test
    fun `a placeholder embedded in a larger token is not reached`() {
        val reversal = deanonymize("9Type1 x_Type1 Type1_x", sidecarOf(), mappingOf(2, "Type1" to "Payment"))

        assertEquals("9Type1 x_Type1 Type1_x", reversal.text)
        assertEquals(emptyList<String>(), reversal.restored)
    }
}

/** A window holding one invocation that named [table]. */
private fun sidecarOf(vararg table: Pair<String, String>): Sidecar =
    Sidecar.EMPTY.recording(RecordedInvocation(Instant.now(), table.toMap()))

/**
 * A mapping whose counter stands at [nextNumber], holding [rows] as `placeholder to name`.
 *
 * The keys are irrelevant to a reversal and are made up here from the placeholder, which is the
 * point: the mapping is read backwards, and what a row was filed under is the forward direction's
 * business.
 */
private fun mappingOf(nextNumber: Int, vararg rows: Pair<String, String>): LedgerSnapshot = LedgerSnapshot(
    rows.associate { (placeholder, original) -> "class:$original" to MintedName(placeholder, original) },
    nextNumber,
)
