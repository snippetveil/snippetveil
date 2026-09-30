package com.snippetveil.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * **A remainder is replaced whole, never offered for preserve, and never shown** — what the engine
 * does with the part of a JVM binary name left over after the longest prefix the platform resolved.
 *
 * The plugin decides where the prefix ends and reports the rest as [SymbolEvidence.remainder]. What
 * is asserted here is everything that follows from that one fact, against plan literals with no IDE
 * behind them: the rendering, the count, the preserve that is not offered, and the text that reaches
 * the sidecar and nothing else.
 */
class RemainderTest {

    @Test
    fun `a remainder renders as an Unknown after the resolved prefix's placeholder`() {
        val result = anonymize(frame(), AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("\tat com.acme.Type1\$Unknown2.invokeSuspend(Type1.java:42)", result.text)
    }

    @Test
    fun `a method remainder renders as an Unknown after the method's placeholder`() {
        val text = "\tat com.acme.Ledger.charge\$suspendImpl(Ledger.java:42)"
        val method = SymbolEvidence("method:class:com.acme.Ledger#charge", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT, "charge", packageName = "com.acme", keyIsQualified = true)
        val plan = SnippetPlan(
            text,
            listOf(
                at(text, "Ledger", LEDGER),
                at(text, "charge", method),
                at(text, "suspendImpl", remainder("suspendImpl")),
                fileAt(text, "Ledger.java"),
            ),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("\tat com.acme.Type1.method2\$Unknown3(Type1.java:42)", result.text)
    }

    /** **One `Unknown`, not two**: the prefix resolved, so the frame's only unknown is what was left. */
    @Test
    fun `a partially resolved name contributes exactly one to the unknown count`() {
        val result = anonymize(frame(), AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals(1, result.counts.unknown)
        assertEquals(1, result.counts.replaced)
    }

    /**
     * **A remainder gets no *Preserve*; a whole unknown keeps it.** Asserted on the result's own rows,
     * and asserted again through the engine: a preserve sent for a remainder's key anyway changes no
     * character of the output, because the dialog is what offers the tick and the engine is what
     * enforces it.
     */
    @Test
    fun `a remainder is not preservable and a whole unknown is`() {
        val (plan, ghost) = frameWithAGhost()

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        val unknowns = result.unknowns.associateBy { it.key }
        assertFalse(unknowns.getValue(REMAINDER_KEY).preservable, "the remainder is offered a preserve")
        assertTrue(unknowns.getValue(ghost.key).preservable, "the whole unknown lost its preserve")

        val rows = result.names.filter { it.kind == MappedKind.UNKNOWN }.associateBy { it.key }
        assertFalse(rows.getValue(REMAINDER_KEY).preservable, "the remainder's row is offered a preserve")
        assertTrue(rows.getValue(ghost.key).preservable, "the whole unknown's row lost its preserve")

        val ticked = anonymize(plan, AnonymizationSettings(preservedSymbols = setOf(REMAINDER_KEY, ghost.key)), LedgerSnapshot.EMPTY)
        assertFalse("charge" in ticked.text, "a preserve sent for the remainder reached the output:\n${ticked.text}")
        assertTrue("com.acme.Ghost" in ticked.text, "the whole unknown's preserve did not reach the output:\n${ticked.text}")
    }

    /**
     * **The remainder's text reaches the sidecar and nothing else** — not the output, not the rows the
     * preview shows, not the unknowns list. The mapping is what a reply decodes against, and it is the
     * one place the text has to be.
     */
    @Test
    fun `no part of a remainder appears in the output or in the rows the preview shows`() {
        val result = anonymize(frame(), AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)
        val shown = result.names.flatMap { listOfNotNull(it.original, it.placeholder, it.kind.label) } +
            result.unknowns.flatMap { listOfNotNull(it.name, it.placeholder) } +
            result.flattened.flatMap { it.placeholders }

        for (part in listOf("charge\$1", "charge")) {
            assertFalse(part in result.text, "`$part` reached the output:\n${result.text}")
            assertTrue(shown.none { part in it }, "`$part` reached the preview's rows: $shown")
        }
        assertEquals("charge\$1", result.mapping["Unknown2"], "the remainder does not decode")
    }

    @Test
    fun `the partially resolved frame reverses to what was anonymized`() {
        val result = anonymize(frame(), AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)
        val sidecar = Sidecar(emptyList()).recording(RecordedInvocation(Instant.now(), result.mapping))

        val reversal = deanonymize(result.text, sidecar, LedgerSnapshot.EMPTY + result.delta)

        assertEquals(frame().text, reversal.text)
        assertEquals(emptyList<Unrestored>(), reversal.unrestored)
    }

    /**
     * **The export is a reversal key**, so a remainder's row carries what it stood for there — the one
     * place outside the sidecar a person can read it back from, and only because they asked for it.
     */
    @Test
    fun `an exported mapping carries a remainder's text so the trace can be read back by hand`() {
        val csv = anonymize(frame(), AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY).mappingCsv()

        assertTrue("Unknown2,charge\$1,Unknown\r\n" in csv, csv)
    }

    /** Two remainders are two generated names, never *the same name* flattened. */
    @Test
    fun `remainders are never reported as flattened names`() {
        val text = "\tat com.acme.Ledger\$1.run(Ledger.java:4)\n\tat com.acme.Ledger\$2.run(Ledger.java:5)"
        val plan = SnippetPlan(
            text,
            listOf(
                at(text, "Ledger", LEDGER),
                at(text, "1.run", remainder("1"), written = "1"),
                fileAt(text, "Ledger.java"),
                at(text, "Ledger\$2", LEDGER, written = "Ledger"),
                at(text, "2.run", remainder("2"), written = "2"),
                fileAt(text, "Ledger.java", ordinal = 1),
            ).sortedBy { it.start },
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals(emptyList<FlattenedName>(), result.flattened)
    }

    /** `com.acme.Ledger$charge$1.invokeSuspend(Ledger.java:42)`, with `charge$1` left over. */
    private fun frame(): SnippetPlan {
        val text = "\tat com.acme.Ledger\$charge\$1.invokeSuspend(Ledger.java:42)"
        return SnippetPlan(
            text,
            listOf(
                at(text, "Ledger", LEDGER),
                at(text, "charge\$1", remainder("charge\$1")),
                fileAt(text, "Ledger.java"),
            ),
        )
    }

    /** [frame], and a second frame whose class did not resolve at all. */
    private fun frameWithAGhost(): Pair<SnippetPlan, SymbolEvidence> {
        val text = "\tat com.acme.Ledger\$charge\$1.invokeSuspend(Ledger.java:42)\n\tat com.acme.Ghost.haunt(Ghost.java:9)"
        val ghost = SymbolEvidence("unresolved:com.acme.Ghost", SymbolRole.TYPE, SymbolOrigin.UNRESOLVED, "com.acme.Ghost")
        val plan = SnippetPlan(
            text,
            listOf(
                at(text, "Ledger", LEDGER),
                at(text, "charge\$1", remainder("charge\$1")),
                fileAt(text, "Ledger.java"),
                at(text, "com.acme.Ghost", ghost, ordinal = 0),
            ),
        )
        return plan to ghost
    }

    private fun remainder(text: String) =
        SymbolEvidence(REMAINDER_KEY.takeIf { text == "charge\$1" } ?: "remainder:$text", SymbolRole.TYPE, SymbolOrigin.UNRESOLVED, text, remainder = true)

    /**
     * The [ordinal]th occurrence of [token] in [text], covering [written] — the token itself by
     * default, or the part of it a test needs to tell apart from a like-spelled neighbour.
     */
    private fun at(text: String, token: String, symbol: SymbolEvidence, written: String = token, ordinal: Int = 0): SymbolOccurrence {
        val start = generateSequence(text.indexOf(token)) { text.indexOf(token, it + 1) }.drop(ordinal).first()
        return SymbolOccurrence(start, start + written.length, written, symbol, SourceLanguage.JAVA)
    }

    /** The [ordinal]th file name [written] in [text], named after [LEDGER] with its extension. */
    private fun fileAt(text: String, written: String, ordinal: Int = 0): SymbolOccurrence {
        val start = generateSequence(text.indexOf("($written")) { text.indexOf("($written", it + 1) }.drop(ordinal).first() + 1
        return SymbolOccurrence(start, start + written.length, written, LEDGER, SourceLanguage.JAVA, suffix = "." + written.substringAfter('.'))
    }
}

private const val REMAINDER_KEY = "remainder:charge\$1"

private val LEDGER = SymbolEvidence(
    key = "class:com.acme.Ledger",
    role = SymbolRole.TYPE,
    origin = SymbolOrigin.IN_CONTENT,
    declaredName = "Ledger",
    qualifiedName = "com.acme.Ledger",
    packageName = "com.acme",
    keyIsQualified = true,
)
