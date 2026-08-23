package com.snippetveil.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * **`deanonymize(anonymize(x)) == x`, adopted as a test of the reversal contract and of nothing
 * else.**
 *
 * ## The warning, recorded here because it is the intuitive default and reads far stronger than it is
 *
 * **Round-trip is blind to leaks.** It is a self-consistency check between two of our own functions,
 * so **it passes when both are wrong in mirror-image ways.** If the anonymiser misses a symbol, that
 * symbol sits in the output verbatim — and de-anonymise leaves it there too, because it is not a
 * minted token. Round-trip: green.
 *
 * That is a verbatim-passthrough leak sailing straight through the check that looks like it should
 * catch everything. **Round-trip is not evidence of concealment**, and nothing in this file may ever
 * be cited as if it were. What actually holds the concealment claim is elsewhere: the fail-closed
 * rules and their tests, and the counts.
 *
 * ## The identity has to be stated precisely, because it does not hold in the default configuration
 *
 * Comments are stripped by default. Information is **destroyed**, and no reversal restores destroyed
 * information — so the identity holds **with comment retention on, within one invocation**, and the
 * default path is lossy. That the default is deliberately lossy is asserted here too, so that nobody
 * later "fixes" the round-trip test by making comment-stripping reversible.
 */
class RoundTripTest {

    /**
     * The identity, in the configuration it holds in: comments kept, one invocation, the sidecar
     * holding what that invocation named.
     *
     * The snippet is deliberately mixed — a type, a member, a local, a literal and a comment — because
     * each of those reaches the output by a different route, and a round trip over types alone would
     * be green over an engine that dropped literals on the floor.
     */
    @Test
    fun `a snippet survives being anonymized and de-anonymized with comments kept`() {
        val original = SNIPPET

        val result = anonymize(planFor(original), AnonymizationSettings(keepComments = true), LedgerSnapshot.EMPTY)
        val back = deanonymize(result.text, recording(result), LedgerSnapshot.EMPTY + result.delta)

        assertNotEquals(original, result.text, "the snippet came back unchanged, so this asserts nothing")
        assertEquals(original, back.text)
        assertEquals(emptyList<Unrestored>(), back.unrestored, "the reversal left a placeholder behind")
    }

    /**
     * **The default path is lossy, and that is asserted rather than left as a caveat.**
     *
     * The comment is gone from the output and no reversal puts it back — reversal maps minted tokens
     * to names, and a stripped comment left no token. A later change that made this test go green
     * would be a change that made comment-stripping reversible, which is a change this product must
     * not make: prose is the largest single domain leak there is, and a reversible strip is a strip
     * that leaves the prose on the clipboard.
     */
    @Test
    fun `the default path is lossy and the round trip does not close`() {
        val result = anonymize(planFor(SNIPPET), AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)
        val back = deanonymize(result.text, recording(result), LedgerSnapshot.EMPTY + result.delta)

        assertNotEquals(SNIPPET, back.text, "comment stripping became reversible, which it must never be")
        assertTrue(COMMENT !in back.text, "a stripped comment came back, so information was not destroyed")

        // And everything that was *not* destroyed did come back: the loss is the comment and only the
        // comment, which is what makes the strip a disclosed loss rather than a broken reversal.
        assertEquals(withoutTheComment(SNIPPET), back.text)
    }

    /**
     * **The leak the round trip cannot see, demonstrated rather than described.**
     *
     * A symbol the anonymiser missed reaches the output verbatim; the reversal leaves it there,
     * because it is not a minted token; and the identity closes. This test passes over a snippet with
     * a real name still in it, which is exactly the point being recorded — the green tick above is
     * compatible with a total failure of concealment.
     */
    @Test
    fun `the round trip closes over a snippet the anonymiser leaked`() {
        // The plan reports one symbol and says nothing about the other, which is what a missed symbol
        // looks like from the engine's side: no occurrence, no rule, no rename.
        val text = "Payment p = merchantLedger.settle();"
        val plan = planOf(text, symbol("Payment", SymbolRole.TYPE, SymbolOrigin.IN_CONTENT, key = "class:com.acme.Payment"))

        val result = anonymize(plan, AnonymizationSettings(keepComments = true), LedgerSnapshot.EMPTY)
        val back = deanonymize(result.text, recording(result), LedgerSnapshot.EMPTY + result.delta)

        assertTrue("merchantLedger" in result.text, "the fixture no longer demonstrates a leak")
        assertEquals(text, back.text, "the identity holds over a leaked snippet, which is the whole warning")
    }

    /** The window this invocation would have been recorded into. */
    private fun recording(result: AnonymizationResult): Sidecar =
        Sidecar.EMPTY.recording(RecordedInvocation(Instant.now(), result.mapping))
}

/**
 * A snippet naming one of each thing that reaches the output by a different route.
 *
 * `merchantLedger` is a local and `"acme-live"` is a literal, so neither is in the persistent mapping
 * and both come back out of the sidecar alone — which is the half of the round trip the mapping
 * cannot carry.
 */
private val SNIPPET = """
    class Payment {
        $COMMENT
        String settle() {
            String merchantLedger = "acme-live";
            return merchantLedger;
        }
    }
""".trimIndent()

private const val COMMENT = "// settle against the merchant ledger"

private const val LITERAL = "\"acme-live\""

/** The snippet as the default path leaves it: the comment gone, and the line it was alone on with it. */
private fun withoutTheComment(snippet: String): String =
    snippet.lines().filterNot { it.trim() == COMMENT }.joinToString("\n")

/**
 * The plan a builder would report for [text] — every symbol found by name, and the comment and the
 * literal placed by searching for them.
 *
 * Written out here rather than taken from the Java builder because `:core` has no IDE: what is under
 * test is the pair of engine functions, and the plan is their input.
 */
private fun planFor(text: String): SnippetPlan {
    val payment = symbol("Payment", SymbolRole.TYPE, SymbolOrigin.IN_CONTENT, key = "class:com.acme.Payment", keyIsQualified = true)
    val settle = symbol("settle", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT, key = "method:class:com.acme.Payment#settle", keyIsQualified = true)
    val local = symbol("merchantLedger", SymbolRole.LOCAL, SymbolOrigin.IN_CONTENT, key = "local:file@40")

    val comment = text.indexOf(COMMENT)
    val literal = text.indexOf(LITERAL)

    val plan = planOf(text, payment, settle, local)
    return SnippetPlan(
        text,
        (
            plan.occurrences +
                CommentOccurrence(comment, comment + COMMENT.length, CommentVerdict.PROSE) +
                // The quotes are the delimiters and the replacement lands inside them, so the
                // content range is the literal's own range less one character at each end.
                LiteralOccurrence(
                    literal,
                    literal + LITERAL.length,
                    LiteralKind.STRING,
                    literal + 1,
                    literal + LITERAL.length - 1,
                )
            ).sortedBy { it.start },
        plan.rootPackage,
    )
}
