package com.snippetveil.plugin.kotlin

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.snippetveil.core.AnonymizationSettings
import com.snippetveil.core.CodeContainer
import com.snippetveil.core.CommentOccurrence
import com.snippetveil.core.LedgerSnapshot
import com.snippetveil.core.anonymize
import com.snippetveil.plugin.SnippetRequest
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtNameReferenceExpression

/**
 * **Kotlin comment prose is stripped by default**, read off real Kotlin — every kind of comment, and
 * the whole of one however the selection cut it — **and what decides that it is prose is Kotlin's own
 * parse, at the comment's position.** What happens to commented-out code that parses is
 * `KotlinCommentedOutCodeTest`'s.
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
     * included, and a TODO is prose in both.
     */
    fun `test commented-out Kotlin is code and a TODO is prose`() {
        assertTrue(isKept("// val total = 3"))
        assertTrue(isKept("/* if (amount > 0) audit(amount) */"))
        assertTrue(isKept("// fun pay() {}"))
        assertFalse(isKept("// TODO: fix this"))
        assertFalse(isKept("// this is where the payout breaks"))
        assertFalse(isKept("//"))
    }

    /**
     * **Prose that parses is prose.** Kotlin reads a bare word as an expression and three of them as
     * an infix call, so short prose parses — as nothing but names, with no code signal in it. It is
     * stripped exactly as the same comment is in Java. A body with a signal in it — a call with
     * parentheses, an assignment, a member access, a literal — is code.
     */
    fun `test a body of bare names is prose and a body with a code signal is code`() {
        for (prose in listOf("// retry on timeout", "// TODO", "// fix later", "// Deprecated", "/* settle the payout */")) {
            assertFalse("`$prose` was kept as code", isKept(prose))
        }
        for (code in listOf("// retry(onTimeout)", "// x = 1", "// foo.bar()", "// TODO()", "// \"late\"")) {
            assertTrue("`$code` was not kept as code", isKept(code))
        }
    }

    /**
     * **A body is code only when its parse holds a code signal** — a `(` `)` `{` `}` `[` `]` `=` `.`
     * `;` `::` `->` `+=` `-=` `==` `!=` `?.` `<=` or `>=`, a literal, or one of the keywords `val`
     * `var` `fun` `class` `object` `interface` `import` `if` `when` `for` `while` `try`. Prose that
     * happens to spell a keyword construct — `value in range`, `it is fine`, `done as planned` —
     * holds none, and is stripped as its Java twin is. The tokens are matched whole, so an operator
     * off the list is no signal even where it holds a listed character: `*=` is no `=`, and
     * `// x *= y` and `// a ?: b` are stripped. A literal on its own — a number, a character, `true`,
     * `null` — is a signal.
     *
     * **`return` and `throw` are deliberately not signals, and the cost is pinned here so it stays
     * visible:** `// return result` is a real commented-out line, and it is stripped with the prose
     * it cannot be told from. The same line with anything more in it — a call, a `;` — is kept.
     */
    fun `test a body is code only when it holds a code signal`() {
        val prose = listOf(
            "// value in range", "// it is fine", "// done as planned", "// merchant in arrears",
            "// retry on timeout", "// TODO", "// return later", "// throw away", "// return result",
            "// we import data", "// x *= y", "// a ?: b",
        )
        for (comment in prose) {
            assertFalse("`$comment` was kept as code", isKept(comment))
        }
        val code = listOf(
            "// return total(items)", "// throw IllegalStateException(\"x\")", "// return result;",
            "// retry(onTimeout)", "// x = 1", "// foo.bar()", "// TODO()", "// \"late\"",
            "// 42", "// .5", "// 'x'", "// true", "// null",
            "// x += y", "// x -= y", "// a == b", "// a != b", "// foo?.bar", "// a <= b",
            "// a >= b",
        )
        for (comment in code) {
            assertTrue("`$comment` was not kept as code", isKept(comment))
        }

        val result = kotlinResultFor(
            "com/acme/ledger/Ledger.kt",
            """
            class Ledger {
                fun settle(merchant: Ledger) {
                    // merchant in arrears
                    // return later
                    // merchant.settle(merchant)
                    // merchant += merchant
                    // merchant -= merchant
                    // merchant == merchant
                    // merchant != merchant
                    // merchant?.settle
                    // merchant <= merchant
                    // merchant >= merchant
                }
            }
            """.trimIndent(),
        )
        for (word in listOf("arrears", "later", "merchant", "settle")) {
            assertFalse("`$word` survived: ${result.text}", word in result.text)
        }
        for (operator in listOf(" += ", " -= ", " == ", " != ", "?.", " <= ", " >= ")) {
            assertTrue("`$operator` lost: ${result.text}", operator in result.text)
        }
        assertEquals(2, result.comments.stripped)
        assertEquals(8, result.comments.anonymized)
    }

    /**
     * **A body that parses only by running into the code around it is not code.** Parsed in place, a
     * body could borrow from its neighbours: a dangling `=` takes the next line as its value, a brace
     * opened and never closed takes the rest of the file, and a body after a dangling `+` or between
     * two arguments is only the rest of an expression the line before it started. What is kept is
     * what parses as something of its own, where it is written.
     */
    fun `test a body that only parses by running into its neighbours is prose`() {
        assertFalse("a body ran across the comment's edge", isKept("// val total =\n        audit(amount)"))
        assertFalse("a body broke the parse of the file after it", isKept("// fun nested() {"))
        assertFalse("a body ran on from the line before it", isKept("// .toString()"))
        assertFalse("a body parsed only by finishing the line before it", isKept("val total = amount + // 2\n        amount"))
        assertFalse("a body parsed only as an argument", isKept("audit(amount, // x,\n        x, foo)"))

        val enumEntry = kotlinResultFor(
            "com/acme/ledger/Tier.kt",
            """
            enum class Tier {
                GOLD,
                // PLATINUM,
                SILVER,
            }
            """.trimIndent(),
        )
        assertEquals("a commented-out enum entry was kept: ${enumEntry.text}", 1, enumEntry.comments.stripped)
    }

    /**
     * **The parse self-check, both arms, at every position.** A known-unparseable body yields at least
     * one error element and no throw; a known-good body yields none.
     *
     * The good bodies are chosen so that each parses **only** at its own position — a statement is
     * not a member, and neither is a file's top level — so this fails if a comment is parsed anywhere
     * but where it is written. And each good body names something that resolves only from where the
     * comment sits — `Positions` in the file's package, `audit` in the class — so it fails too if the
     * context is wrong: a parse with no context strips nothing it should not, keeps everything as
     * `Unknown`, and passes every prose test there is.
     */
    fun `test the parse is observable at member, statement and file position`() {
        val file = myFixture.addFileToProject(
            "probe/Positions.kt",
            """
            package probe

            // fun charge() = Positions().audit()
            // TODO: fix this
            open class Positions {
                // fun charge() { audit() }
                // audit()
                fun audit() {
                    // this.audit()
                    // TODO: fix this
                }
            }
            """.trimIndent(),
        )
        val comments = PsiTreeUtil.findChildrenOfType(file, PsiComment::class.java).toList()
        val good = listOf(true, false, true, false, true, false)
        assertEquals(good.size, comments.size)

        for ((comment, isGood) in comments.zip(good)) {
            val parsed = PlatformKotlinCommentParser.parse(comment, uncommentedTextOf(comment))
            val errors = PsiTreeUtil.findChildrenOfType(parsed, PsiErrorElement::class.java)
                .filter { comment.textRange.contains(it.textRange) }
            if (isGood) {
                assertEquals("`${comment.text}` did not parse where it is written: ${errors.map { it.errorDescription }}", 0, errors.size)
                // And the names in it resolve, which they do only from the comment's own place.
                val unresolved = PsiTreeUtil.findChildrenOfType(parsed, KtNameReferenceExpression::class.java)
                    .filter { comment.textRange.contains(it.textRange) && it.mainReference.resolve() == null }
                    .map { it.text }
                assertEquals("`${comment.text}` was parsed without its context", emptyList<String>(), unresolved)
            } else {
                assertTrue("`${comment.text}` parsed where it is written", errors.isNotEmpty())
            }
        }
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

    /**
     * Whether [comment], written inside a function body where most of them are, is kept as code: a
     * comment whose body parsed is decomposed into its parts, so the plan holds no occurrence of it
     * in live code — only, at most, a comment nested in it.
     */
    private fun isKept(comment: String): Boolean {
        val file = myFixture.addFileToProject(
            "probe/Probe" + probe++ + ".kt",
            """
            package probe

            class Ledger {
                fun audit(amount: Int, x: Int, foo: Ledger) {
                    $comment
                }
            }
            """.trimIndent(),
        )
        val plan = KotlinPlanBuilder.build(SnippetRequest(project, file, emptyList()))
        return plan.occurrences.none { it is CommentOccurrence && it.container == CodeContainer.LiveCode }
    }

    /** Each probe needs a file of its own; a fixture cannot hold two files under one path. */
    private var probe = 0
}
