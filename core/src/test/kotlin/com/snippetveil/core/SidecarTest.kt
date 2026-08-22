package com.snippetveil.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * **What stays decodable, and what stops being decodable when.**
 *
 * The sidecar is the answer to a hole the persistent mapping leaves by design: only symbols with a
 * qualified key are written down there, and an AI's reply talks about locals, parameters and
 * literals constantly. These are the statements about one window — what a recording puts in it, what
 * a bound takes out, and what a lookup answers on either side of the horizon. The statements that
 * only exist over a *run* of invocations, where the two cuts interact, are in [SidecarHorizonTest].
 *
 * Every eviction here is asserted through [Sidecar.originalOf] rather than by counting rows, because
 * *decodes to nothing* is the behaviour the design promises and a row count is an implementation
 * detail that happens to correlate with it.
 */
class SidecarTest {

    /**
     * The hole the sidecar fills: an AI reply talks about locals, parameters and literals constantly
     * — *"the `local2` here is null before you validate"* — and none of those is in the persistent
     * mapping.
     */
    @Test
    fun `an invocation's ephemeral placeholders are decodable afterwards`() {
        val sidecar = Sidecar.EMPTY.recording(
            RecordedInvocation(Instant.EPOCH, mapOf("local2" to "draft", "str3" to "acme-live")),
        )

        assertEquals("draft", sidecar.originalOf("local2"))
        assertEquals("acme-live", sidecar.originalOf("str3"))
    }

    /**
     * **FIFO by invocation**: the window slides, and what falls off the back is the oldest paste
     * rather than the least recently mentioned. Recency of *use* is not observable here — nothing
     * tells this store that a reply was decoded — so the only honest order is the order they were
     * sent in.
     */
    @Test
    fun `the oldest invocation falls off the end once the bound is passed`() {
        val bound = SidecarBound(invocations = 3, age = Duration.ofDays(30))

        val sidecar = (1..4).fold(Sidecar.EMPTY) { window, paste -> window.recording(pasteNumber(paste), bound) }

        assertEquals(3, sidecar.invocations.size)
        assertNull(sidecar.originalOf("local1"), "the fourth paste did not evict the first")
        assertEquals("draft2", sidecar.originalOf("local2"))
        assertEquals("draft4", sidecar.originalOf("local4"))
    }

    /** ~50 invocations, which is the spec's default and is asserted as behaviour rather than as a constant. */
    @Test
    fun `the default window is fifty invocations deep`() {
        val sidecar = (1..51).fold(Sidecar.EMPTY) { window, paste -> window.recording(pasteNumber(paste)) }

        assertNull(sidecar.originalOf("local1"), "a 51st paste did not evict the first")
        assertEquals("draft2", sidecar.originalOf("local2"))
        assertEquals(50, sidecar.invocations.size)
    }

    /**
     * **The age cap, on the write side.** The count bound is no bound at all for someone who pastes
     * twice a month, and this is the one durable place a redacted literal's text comes to rest.
     */
    @Test
    fun `an invocation older than the age cap is evicted however few have followed it`() {
        val bound = SidecarBound(invocations = 50, age = Duration.ofDays(30))
        val today = Instant.EPOCH.plus(Duration.ofDays(400))

        val sidecar = Sidecar.EMPTY
            .recording(RecordedInvocation(today.minus(Duration.ofDays(31)), mapOf("str1" to "acme-live")), bound)
            .recording(RecordedInvocation(today, mapOf("str2" to "acme-test")), bound)

        assertNull(sidecar.originalOf("str1"), "a paste past the age cap is still on file")
        assertEquals("acme-test", sidecar.originalOf("str2"))
    }

    /**
     * **And on the read side**, which is the half that makes the cap a fact rather than a
     * housekeeping side effect: a window nobody has added to for a year would otherwise go on
     * answering — and go on holding a year of literal text — for exactly as long as nobody pasted.
     */
    @Test
    fun `a window nobody has added to still forgets past the age cap`() {
        val sent = Instant.EPOCH.plus(Duration.ofDays(400))
        val sidecar = Sidecar.EMPTY.recording(RecordedInvocation(sent, mapOf("str1" to "acme-live")))

        assertEquals("acme-live", sidecar.bounded(sent.plus(Duration.ofDays(29))).originalOf("str1"))
        assertNull(
            sidecar.bounded(sent.plus(Duration.ofDays(31))).originalOf("str1"),
            "a read past the age cap answered out of an entry the cap had expired",
        )
    }

    /**
     * **Eviction degrades to under-recovery and never to a wrong answer.** A placeholder the window
     * has forgotten decodes to nothing, which a reversal renders by leaving the word alone — an
     * inspectable gap in the reply rather than a name the reader has no way to doubt.
     */
    @Test
    fun `a placeholder past the horizon decodes to nothing rather than to something else`() {
        val bound = SidecarBound(invocations = 1, age = Duration.ofDays(30))

        val sidecar = Sidecar.EMPTY
            .recording(RecordedInvocation(Instant.EPOCH, mapOf("local1" to "draft")), bound)
            .recording(RecordedInvocation(Instant.EPOCH.plusSeconds(1), mapOf("local2" to "total")), bound)

        assertNull(sidecar.originalOf("local1"))
        assertEquals("total", sidecar.originalOf("local2"))
    }

    /**
     * **The seam, in one test: the table an invocation hands over holds exactly what the mapping
     * refuses to keep.**
     *
     * The delta carries the one qualified key and nothing else — a local, a type parameter, an
     * anonymous class's member and a literal are all allocated, all burnt, and none of them written
     * down. Every one of them is in the table, so every one of them is still decodable while the
     * sidecar remembers this paste. The two halves are stated together deliberately: the reason the
     * sidecar is worth having is precisely the gap the delta leaves, and a test that only asserted
     * one half would go green over the day the other stopped being true.
     */
    @Test
    fun `the table an invocation hands over holds the symbols the mapping refuses`() {
        val payment = qualified("Payment", SymbolRole.TYPE, "class:com.acme.Payment")
        val local = symbol("draft", SymbolRole.LOCAL, SymbolOrigin.IN_CONTENT, key = "local:file@17")
        val request = symbol("REQ", SymbolRole.TYPE_PARAMETER, SymbolOrigin.IN_CONTENT, key = "typeparam:file@3")
        val member = symbol("state", SymbolRole.FIELD, SymbolOrigin.IN_CONTENT, key = "field:class:file@40#state")
        val plan = planOf("""REQ draft state Payment "acme-live";""", request, local, member, payment)
            .withLiteral(""""acme-live"""")

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals(
            mapOf(payment.key to "Type4"),
            result.delta.placeholders,
            "the durable mapping kept something it has no stable key for",
        )

        val sidecar = Sidecar.EMPTY.recording(RecordedInvocation(Instant.EPOCH, result.mapping))
        assertEquals("REQ", sidecar.originalOf("T1"))
        assertEquals("draft", sidecar.originalOf("local2"))
        assertEquals("state", sidecar.originalOf("field3"))
        assertEquals("Payment", sidecar.originalOf("Type4"))
        assertEquals("acme-live", sidecar.originalOf("str5"))
    }

    /** The [paste]-th invocation of a run, a second apart and naming one local of its own. */
    private fun pasteNumber(paste: Int) =
        RecordedInvocation(Instant.EPOCH.plusSeconds(paste.toLong()), mapOf("local$paste" to "draft$paste"))
}
