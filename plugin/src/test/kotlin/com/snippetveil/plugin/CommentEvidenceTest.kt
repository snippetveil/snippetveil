package com.snippetveil.plugin

import com.snippetveil.core.AnonymizationSettings
import com.snippetveil.core.CommentOccurrence
import com.snippetveil.core.CommentVerdict
import com.snippetveil.core.LedgerSnapshot
import com.snippetveil.core.anonymize

/**
 * **The two things about a comment that only a real Java parser can say**, read off real Java:
 * whether its body is code somebody commented out, and which words in it are resolvable references
 * rather than prose.
 *
 * What is done with either answer is `:core`'s business and is tested there against plan literals,
 * at millisecond speed. What cannot be tested there is whether the plan told the truth about a real
 * file, which is what is here.
 */
class CommentEvidenceTest : JavaSnippetTestCase() {

    // ------------------------------------------------------------------ The parse verdict

    /**
     * **The verdict is a parse, not a guess.** The ticket's own two examples, either side of the
     * line: an assignment somebody commented out parses as a code block, and a TODO does not.
     *
     * This is the split that makes the strip count actionable — and it matters more than it looks.
     * The one question every variant in the naming experiment answered at a full 9/9 was *"find the
     * commented-out assignment"*: the ground-truth bug **was** a comment, and a reviewer called that
     * line the single most useful surviving clue.
     */
    fun `test a commented-out statement is code and a TODO is prose`() {
        assertEquals(CommentVerdict.CODE, verdictOf("// this.customer.setOrder(order);"))
        assertEquals(CommentVerdict.PROSE, verdictOf("// TODO: fix this"))
    }

    /** More of both sides, because a rule with one fixture on each side is one fixture from a coincidence. */
    fun `test the verdict holds either side of the line`() {
        assertEquals(CommentVerdict.CODE, verdictOf("// int retries = 3;"))
        assertEquals(CommentVerdict.CODE, verdictOf("/* if (amount > 0) { audit(amount); } */"))
        assertEquals(CommentVerdict.CODE, verdictOf("// audit(amount); // and the old reason why"))

        assertEquals(CommentVerdict.PROSE, verdictOf("// reconcile against the merchant ledger"))
        assertEquals(CommentVerdict.PROSE, verdictOf("/* the ledger is authoritative */"))
        assertEquals(CommentVerdict.PROSE, verdictOf("// see PaymentBatch#settle for why this is not a loop"))
    }

    /**
     * **The stated limit, on a fixture rather than only in a doc comment.**
     *
     * The verdict is a code *block* parse, so a commented-out local declaration is code and a
     * commented-out *method* is prose — a method declaration is not a statement, and inside a block
     * it does not parse. Widening the rule means trying the body against every context Java has, and
     * each context added is another way for a line of prose to parse by accident. The limit is
     * asserted here so that a later widening is a decision somebody makes rather than one that
     * happens.
     */
    fun `test the verdict is a code block and a commented-out method is therefore prose`() {
        assertEquals(CommentVerdict.CODE, verdictOf("// private String merchantRef;"))
        assertEquals(CommentVerdict.PROSE, verdictOf("// void pay(int amount) {}"))
    }

    /**
     * A line comment has no continuation-asterisk convention, so an asterisk at the front of one is
     * text somebody wrote — a bullet in a list. Reading javadoc's line prefix off it would turn this
     * line of prose into a statement that parses, which is a way for an exact verdict not to be.
     */
    fun `test a line comment's leading asterisk is text and not a javadoc prefix`() {
        assertEquals(CommentVerdict.PROSE, verdictOf("// * total = 3;"))
    }

    /**
     * An empty comment is prose. `{}` parses, so a rule that only asked the parser would call an
     * empty comment commented-out code — the one verdict here that is plainly false.
     */
    fun `test an empty comment is prose`() {
        assertEquals(CommentVerdict.PROSE, verdictOf("//"))
        assertEquals(CommentVerdict.PROSE, verdictOf("/* */"))
    }

    /**
     * Javadoc is read with its leading asterisks taken off, which is what a reader of it sees and
     * therefore what there is to parse. Both sides again, because a javadoc block holding
     * commented-out code is exactly as real as a line comment holding it.
     */
    fun `test javadoc is read as the text a reader of it sees`() {
        assertEquals(CommentVerdict.PROSE, javadocVerdictOf("/**\n * Reconciles a batch against the ledger.\n */"))
        assertEquals(CommentVerdict.PROSE, javadocVerdictOf("/**\n * @param amount the amount to settle\n */"))
        assertEquals(CommentVerdict.CODE, javadocVerdictOf("/**\n * this.customer.setOrder(order);\n */"))
    }

    // ------------------------------------------------------------------ Javadoc's resolvable half

    /**
     * **Javadoc is not uniformly prose.** `{@link …}`, `@see` and `@param` targets are *resolvable
     * references* — a `PsiDocTagValue` resolving to a declared symbol — so when comments are kept
     * they rename through the PSI graph like any other reference.
     *
     * **And the prose around them is not touched.** `merchantRef` in the first line is the same word
     * as the `@param` target and is left exactly as it was written, because nothing resolved there.
     * Rewriting identifiers inside prose was rejected: it is regex by another name, and it
     * under-delivers anyway, since `merchant ledger` as two lowercase words never matches
     * `merchantLedger`. The incoherence this leaves between the prose and the code is the reason the
     * strip is the default, and it is why keeping comments is a per-invocation reduction rather than
     * a setting.
     */
    fun `test with comments kept javadoc tag targets rename and the prose does not`() {
        assertTheHarnessResolves()
        myFixture.addFileToProject(
            "Payment.java",
            "public class Payment { public void pay(int amount) {} public int total; }",
        )
        val plan = planFor(
            "Ledger.java",
            """
            class Ledger {
                /**
                 * Reconciles the merchantRef against {@link Payment#pay(int)}.
                 *
                 * @param merchantRef the merchant reference
                 * @see Payment#total
                 */
                void post(String merchantRef) {}
            }
            """.trimIndent(),
        )

        val result = anonymize(plan, AnonymizationSettings(keepComments = true), LedgerSnapshot.EMPTY)

        assertEquals(
            """
            class Type1 {
                /**
                 * Reconciles the merchantRef against {@link Type2#method3(int)}.
                 *
                 * @param param4 the merchant reference
                 * @see Type2#field5
                 */
                void method6(String param4) {}
            }
            """.trimIndent(),
            result.text,
        )
    }

    /**
     * A type parameter's `@param` target is a tag value like any other, and the angle brackets around
     * it are not part of the name — a rewrite that ate them would leave a javadoc tag that no longer
     * names anything.
     */
    fun `test a type parameter's param target renames inside its brackets`() {
        assertTheHarnessResolves()
        val plan = planFor(
            "Ledger.java",
            """
            class Ledger {
                /**
                 * @param <REQ> the request type
                 */
                <REQ> void post(REQ request) {}
            }
            """.trimIndent(),
        )

        val result = anonymize(plan, AnonymizationSettings(keepComments = true), LedgerSnapshot.EMPTY)

        assertEquals(
            """
            class Type1 {
                /**
                 * @param <T2> the request type
                 */
                <T2> void method3(T2 param4) {}
            }
            """.trimIndent(),
            result.text,
        )
    }

    /**
     * The same javadoc under the default, which is the case that actually ships: the block goes, the
     * names inside it go with it, and neither is counted as a name on the clipboard.
     */
    fun `test a stripped javadoc takes its references with it`() {
        assertTheHarnessResolves()
        myFixture.addFileToProject(
            "Payment.java",
            "public class Payment { public void pay(int amount) {} public int total; }",
        )
        val plan = planFor(
            "Ledger.java",
            """
            class Ledger {
                /**
                 * Reconciles the merchantRef against {@link Payment#pay(int)}.
                 *
                 * @param merchantRef the merchant reference
                 */
                void post(String merchantRef) {}
            }
            """.trimIndent(),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals(
            """
            class Type1 {
                void method2(String param3) {}
            }
            """.trimIndent(),
            result.text,
        )
        assertEquals(1, result.comments.prose)
        assertFalse("no name from the javadoc may be in the mapping: " + result.mapping, result.mapping.containsValue("pay"))
    }

    /**
     * One occurrence per reference, never two: the class half of `{@link Payment#pay}` is an ordinary
     * `PsiJavaCodeReferenceElement` that the identifier walk already reports, and two occurrences over
     * one range would be two edits over one range.
     */
    fun `test a javadoc reference is reported once`() {
        assertTheHarnessResolves()
        myFixture.addFileToProject("Payment.java", "public class Payment { public void pay(int amount) {} }")
        val plan = planFor(
            "Ledger.java",
            """
            class Ledger {
                /** {@link Payment#pay(int)} */
                void post() {}
            }
            """.trimIndent(),
        )

        val ranges = plan.symbols().map { it.start to it.end }
        assertEquals("two occurrences claim one range: $ranges", ranges.size, ranges.toSet().size)
        assertTrue(
            "the overlapping pairs are the bug this asserts against: $ranges",
            ranges.sortedBy { it.first }.zipWithNext().all { (first, second) -> first.second <= second.first },
        )
    }

    /** The verdict for a comment written inside a method body, which is where most of them are. */
    private fun verdictOf(comment: String): CommentVerdict = verdictIn(
        """
        class Ledger {
            void audit(int amount) {
                $comment
            }
        }
        """.trimIndent(),
    )

    /** The verdict for a javadoc block, which has to sit in front of a declaration to be one. */
    private fun javadocVerdictOf(javadoc: String): CommentVerdict = verdictIn(
        """
        class Ledger {
        $javadoc
            void audit(int amount) {}
        }
        """.trimIndent(),
    )

    private fun verdictIn(source: String): CommentVerdict {
        val file = myFixture.addFileToProject("probe/Probe" + probe++ + ".java", source)
        val plan = JavaPlanBuilder.build(SnippetRequest(project, file, emptyList()))
        return plan.occurrences.filterIsInstance<CommentOccurrence>().first().verdict
    }

    /** Each probe needs a file of its own; a fixture cannot hold two files under one path. */
    private var probe = 0
}
