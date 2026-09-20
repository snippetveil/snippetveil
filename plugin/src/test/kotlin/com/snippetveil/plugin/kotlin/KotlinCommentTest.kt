package com.snippetveil.plugin.kotlin

import com.snippetveil.core.AnonymizationSettings
import com.snippetveil.core.CommentOccurrence
import com.snippetveil.core.CommentVerdict
import com.snippetveil.core.LedgerSnapshot
import com.snippetveil.core.anonymize

/**
 * **A Kotlin comment is stripped by default**, read off real Kotlin — every kind of one, and the
 * whole of one however the selection cut it.
 *
 * What the engine does with a reported comment is `:core`'s business and is tested there against
 * plan literals. What cannot be tested there is whether the Kotlin walk **reports** one, and for
 * two releases it reported none: the engine strips the comments it is told about, so every comment
 * in a `.kt` selection went out verbatim, under a balloon with no comment count to show. Each test
 * here names a domain word the comment carries and asserts on the clipboard text, because *the
 * prose did not leave* is the claim and an occurrence count is only a proxy for it.
 */
internal class KotlinCommentTest : KotlinSnippetTestCase() {

    /**
     * Line, block, KDoc and trailing — **every spelling of a comment, gone from the output, and
     * counted.** The count is asserted as well as the text because it is what the balloon shows: a
     * strip the user is not told about is a loss they cannot act on.
     */
    fun `test every kind of comment is stripped by default and counted`() {
        val result = kotlinResultFor(
            "com/acme/ledger/Ledger.kt",
            """
            package com.acme.ledger

            /**
             * Settles the Acme ledger against the payout file.
             */
            class Ledger(val merchantRef: String) {
                // Globex sends these twice
                /* Initech never reconciles */
                fun settle(): String = merchantRef // Umbrella asked for this
            }
            """.trimIndent(),
        )

        for (word in listOf("Acme", "Globex", "Initech", "Umbrella", "//", "/*")) {
            assertFalse("`$word` survived the strip: ${result.text}", word in result.text)
        }
        assertEquals(4, result.comments.stripped)
    }

    /**
     * **A selection that starts inside a KDoc block takes the whole block, and the block is then
     * stripped.** KDoc is a tree, so a leaf-level snap would bring in one line of it, leave the
     * block contained by no fragment and therefore unreported — and send the line out verbatim,
     * including the half of it the user never selected. See `KotlinPlanBuilder.tokenOf`.
     */
    fun `test a selection cutting into KDoc snaps to the block and strips it`() {
        val plan = kotlinPlanFor(
            "com/acme/ledger/Ledger.kt",
            """
            package com.acme.ledger

            class Ledger {
                /**
                 * Settles the Acme merchant <selection>ledger against the Globex payout file.
                 */
                fun settle() {}</selection>
            }
            """.trimIndent(),
        )
        val result = kotlinResultFor(plan)

        assertTrue("the snap was not disclosed", plan.selectionExpanded)
        assertEquals(1, plan.occurrences.filterIsInstance<CommentOccurrence>().size)
        for (word in listOf("Acme", "Globex", "ledger", "*/")) {
            assertFalse("`$word` survived the strip: ${result.text}", word in result.text)
        }
    }

    /**
     * **The verdict is Kotlin's parse, not Java's** — commented-out Kotlin is code, a local function
     * included, which Java's block rule cannot say about a method. And a TODO is prose in both.
     */
    fun `test commented-out Kotlin is code and a TODO is prose`() {
        assertEquals(CommentVerdict.CODE, verdictOf("// val total = 3"))
        assertEquals(CommentVerdict.CODE, verdictOf("/* if (amount > 0) audit(amount) */"))
        assertEquals(CommentVerdict.CODE, verdictOf("// fun pay() {}"))
        assertEquals(CommentVerdict.PROSE, verdictOf("// TODO: fix this"))
        assertEquals(CommentVerdict.PROSE, verdictOf("// this is where the payout breaks"))
        assertEquals(CommentVerdict.PROSE, verdictOf("//"))
    }

    /**
     * **The stated limit, pinned rather than left to be discovered.** Kotlin reads a bare word as an
     * expression and three of them as an infix call, so short prose parses — and the balloon's
     * *commented-out code* count over-counts on it. The verdict gates nothing: all of these are
     * stripped like any other comment. A body that closes the wrapper and opens a declaration of its
     * own parses cleanly too, and is prose, because what it parsed as is not a block.
     */
    fun `test short prose that happens to parse is counted as code, and a second declaration is not`() {
        assertEquals(CommentVerdict.CODE, verdictOf("// Deprecated"))
        assertEquals(CommentVerdict.CODE, verdictOf("// retry on timeout"))
        assertEquals(CommentVerdict.PROSE, verdictOf("// } fun other() {"))
    }

    /**
     * **With comments kept, a KDoc link renames with the symbol it names and the prose does not** —
     * the same shape as javadoc's tag targets. The links are identifier leaves, so the identifier
     * walk reports them and nothing here reports them twice; this is what holds that claim.
     */
    fun `test with comments kept a KDoc link renames and the prose does not`() {
        val plan = kotlinPlanFor(
            "com/acme/ledger/Ledger.kt",
            """
            package com.acme.ledger

            /** Settles the payout. See [merchantRef] and [Ledger.settle]. */
            class Ledger(val merchantRef: String) {
                fun settle(): String = merchantRef
            }
            """.trimIndent(),
        )
        val kept = anonymize(plan, AnonymizationSettings(keepComments = true), LedgerSnapshot.EMPTY).text

        assertTrue("the prose was rewritten: $kept", "Settles the payout. See [" in kept)
        assertFalse("a link kept the name it links to: $kept", "merchantRef" in kept || "Ledger" in kept || "settle" in kept)
    }

    /** The verdict the production walk reports for [comment], written above a declaration. */
    private fun verdictOf(comment: String): CommentVerdict =
        kotlinPlanFor("com/acme/ledger/Ledger.kt", "package com.acme.ledger\n\n$comment\nclass Ledger\n")
            .occurrences.filterIsInstance<CommentOccurrence>().single().verdict
}
