package com.snippetveil.plugin

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiJavaCodeReferenceElement
import com.intellij.psi.util.PsiTreeUtil
import com.snippetveil.core.AnonymizationSettings
import com.snippetveil.core.CodeContainer
import com.snippetveil.core.CommentOccurrence
import com.snippetveil.core.LedgerSnapshot
import com.snippetveil.core.anonymize

/**
 * **The two things about a comment that only a real Java parser can say**, read off real Java:
 * whether its body is code somebody commented out — parsed at the comment's own position — and
 * which words in it are resolvable references rather than prose.
 *
 * What is done with either answer is `:core`'s business and is tested there against plan literals,
 * at millisecond speed. What cannot be tested there is whether the plan told the truth about a real
 * file, which is what is here.
 */
class CommentEvidenceTest : JavaSnippetTestCase() {

    // ------------------------------------------------------------------ The parse verdict

    /**
     * **The verdict is a parse, not a guess.** The ticket's own two examples, either side of the
     * line: an assignment somebody commented out parses, and is kept; a TODO does not, and is not.
     */
    fun `test a commented-out statement is code and a TODO is prose`() {
        assertTrue(isKept("// this.customer.setOrder(order);"))
        assertFalse(isKept("// TODO: fix this"))
    }

    /** More of both sides, because a rule with one fixture on each side is one fixture from a coincidence. */
    fun `test the verdict holds either side of the line`() {
        assertTrue(isKept("// int retries = 3;"))
        assertTrue(isKept("/* if (amount > 0) { audit(amount); } */"))
        assertTrue(isKept("// audit(amount); // and the old reason why"))

        assertFalse(isKept("// reconcile against the merchant ledger"))
        assertFalse(isKept("/* the ledger is authoritative */"))
        assertFalse(isKept("// see PaymentBatch#settle for why this is not a loop"))
    }

    /**
     * **The same bare-name prose is prose in both languages.** Kotlin parses `retry on timeout` as an
     * infix call of three names and Java does not parse it at all, and the comment is stripped in
     * both: the guard that calls a parse with no code signal prose sits beside the vacuous-parse
     * guard and is stated for every language. Every Java body that parses holds a signal — a
     * statement or member ends in `;` or `}` — which is what makes the guard a no-op here.
     */
    fun `test a body of bare names is prose in Java as in Kotlin`() {
        assertFalse(isKept("// retry on timeout"))
        assertFalse(isKept("// TODO"))
        assertFalse(isKept("// fix later"))

        assertTrue(isKept("// retry(onTimeout);"))
        assertTrue(isKept("// amount = 1;"))
    }

    /**
     * **Prose that spells a Kotlin keyword construct is prose in Java too.** Kotlin reads
     * `value in range` as a containment check and Java does not read it at all, and both strip it:
     * a body is code only when its parse holds a code signal, and these hold none. Every Java body
     * that parses holds one — a statement or member ends in `;` or `}` — so the rule is a no-op here,
     * and `return result;` is kept by its `;`.
     */
    fun `test prose that spells a keyword construct is prose in Java as in Kotlin`() {
        val prose = listOf(
            "// value in range", "// it is fine", "// done as planned", "// merchant in arrears",
            "// return later", "// throw away", "// return result",
        )
        for (comment in prose) {
            assertFalse("`$comment` was kept as code", isKept(comment))
        }
        assertTrue(isKept("// return amount;"))
        assertTrue(isKept("// throw new IllegalStateException(\"x\");"))
    }

    /**
     * **The parse self-check, both arms, at every position.** A known-unparseable body yields at
     * least one error element and no throw; a known-good body yields none.
     *
     * The good bodies are chosen so that each parses **only** at its own position — a method is not a
     * statement, a statement is not a member, and a class is neither — so this fails if a comment is
     * parsed anywhere but where it is written. And each good body names something that resolves only
     * from where the comment sits — `audit` in the class, `Positions` in the file's package — so it
     * fails too if the context element is wrong. That is the point of it: a parse with the wrong
     * context strips everything, and passes every prose test there is.
     */
    fun `test the parse is observable at member, statement and file position`() {
        val file = myFixture.addFileToProject(
            "probe/Positions.java",
            """
            package probe;

            // class Vip extends Positions {}
            // TODO: fix this
            class Positions {
                // public void charge() { audit(); }
                // TODO: fix this
                void audit() {
                    // this.audit();
                    // TODO: fix this
                }
            }
            """.trimIndent(),
        )
        val comments = PsiTreeUtil.findChildrenOfType(file, PsiComment::class.java).toList()
        val expected = listOf(
            CommentPosition.FILE to 0, CommentPosition.FILE to null,
            CommentPosition.MEMBER to 0, CommentPosition.MEMBER to null,
            CommentPosition.STATEMENT to 0, CommentPosition.STATEMENT to null,
        )
        assertEquals(expected.size, comments.size)

        for ((comment, arm) in comments.zip(expected)) {
            val (position, errors) = arm
            assertEquals(comment.text, position, positionOf(comment))

            val parsed = JavaCommentParser.parse(comment, bodyOf(comment), position)
            val found = PsiTreeUtil.findChildrenOfType(parsed, PsiErrorElement::class.java).size
            if (errors == 0) {
                assertEquals("`${comment.text}` did not parse at $position", 0, found)
                // And the name in it resolves, which it does only from the comment's own place.
                val unresolved = PsiTreeUtil.findChildrenOfType(parsed, PsiJavaCodeReferenceElement::class.java)
                    .filter { it.resolve() == null }
                    .map { it.text }
                assertEquals("`${comment.text}` was parsed without its context", emptyList<String>(), unresolved)
            } else {
                assertTrue("`${comment.text}` parsed at $position", found > 0)
            }
        }
    }

    /**
     * A line comment has no continuation-asterisk convention, so an asterisk at the front of one is
     * text somebody wrote — a bullet in a list. Reading javadoc's line prefix off it would turn this
     * line of prose into a statement that parses, which is a way for an exact verdict not to be.
     */
    fun `test a line comment's leading asterisk is text and not a javadoc prefix`() {
        assertFalse(isKept("// * total = 3;"))
    }

    /**
     * An empty comment is not code. It parses to nothing at all, and calling it commented-out code
     * would be the one verdict here that is plainly false.
     */
    fun `test an empty comment is prose`() {
        assertFalse(isKept("//"))
        assertFalse(isKept("/* */"))
    }

    /**
     * Javadoc is read with its delimiters and leading asterisks dropped as tokens, which is what a
     * reader of it sees and therefore what there is to parse. It sits in front of a method, so it is
     * at member position: a commented-out member in it is kept, and prose or a tag is not.
     */
    fun `test javadoc is read as the text a reader of it sees`() {
        assertFalse(isKeptJavadoc("/**\n * Reconciles a batch against the ledger.\n */"))
        assertFalse(isKeptJavadoc("/**\n * @param amount the amount to settle\n */"))
        assertTrue(isKeptJavadoc("/**\n * private int total;\n */"))
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
     * **A selection that starts inside a javadoc block takes the whole block, and the block is then
     * stripped.** Javadoc is a tree whose leaves are its lines, so a leaf-level snap brought one
     * line in whole — the half the user never selected included — while the block it belongs to was
     * contained by no fragment, went unreported, and left verbatim under `0 comments stripped`.
     * Asserted on the clipboard text, because *the prose did not leave* is the claim.
     */
    fun `test a selection cutting into javadoc snaps to the block and strips it`() {
        val plan = planFor(
            "Ledger.java",
            """
            class Ledger {
                /**
                 * Settles the Acme merchant <selection>ledger against the Globex payout file.
                 */
                void settle() {}</selection>
            }
            """.trimIndent(),
        )
        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertTrue("the snap was not disclosed", plan.selectionExpanded)
        assertEquals(1, result.comments.stripped)
        for (word in listOf("Acme", "Globex", "ledger", "*/")) {
            assertFalse("`$word` survived the strip: ${result.text}", word in result.text)
        }
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

    /** Whether [comment], written inside a method body where most of them are, is kept as code. */
    private fun isKept(comment: String): Boolean = isKeptIn(
        """
        class Ledger {
            void audit(int amount) {
                $comment
            }
        }
        """.trimIndent(),
    )

    /** Whether a javadoc block, which has to sit in front of a declaration to be one, is kept as code. */
    private fun isKeptJavadoc(javadoc: String): Boolean = isKeptIn(
        """
        class Ledger {
        $javadoc
            void audit(int amount) {}
        }
        """.trimIndent(),
    )

    /**
     * Whether the one comment in [source] is kept: a comment whose body parsed is decomposed into its
     * parts, so the plan holds no occurrence of it in live code — only, at most, a comment nested in it.
     */
    private fun isKeptIn(source: String): Boolean {
        val file = myFixture.addFileToProject("probe/Probe" + probe++ + ".java", source)
        val plan = JavaPlanBuilder.build(SnippetRequest(project, file, emptyList()))
        return plan.occurrences.none { it is CommentOccurrence && it.container == CodeContainer.LiveCode }
    }

    /** Each probe needs a file of its own; a fixture cannot hold two files under one path. */
    private var probe = 0
}
