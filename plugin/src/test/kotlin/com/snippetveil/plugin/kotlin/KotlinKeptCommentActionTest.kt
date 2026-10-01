package com.snippetveil.plugin.kotlin

import com.intellij.notification.NotificationType
import com.snippetveil.plugin.CopyAnonymizedAction
import com.snippetveil.plugin.PlaceholderLedger
import com.snippetveil.plugin.clipboard
import com.snippetveil.plugin.setClipboard

/**
 * **A kept Kotlin comment, end to end through `Copy Anonymized`** — counted as a Java one is, failing
 * closed as a Java one does, and keyed into the one mapping both languages share.
 */
internal class KotlinKeptCommentActionTest : KotlinSnippetTestCase() {

    /**
     * **`1 comment anonymized` and `unknown (N from comments)` count Kotlin comments exactly as they
     * count Java ones** — on the balloon, because `Copy Anonymized` has no dialog. `legacyAudit`
     * resolves nowhere and is written only in the commented-out line, so it is one of the two
     * unknowns and the one from comments; `missingHelper` is red in live code. The TODO is the one
     * comment stripped.
     */
    fun `test the balloon counts a kept Kotlin comment and splits the unknowns it brings`() {
        assertTheHarnessResolves()
        myFixture.configureByText(
            "Ledger.kt",
            """
            class Ledger {
                <selection>fun audit(amount: Long) {
                    // TODO: fix this
                    // legacyAudit(amount)
                    missingHelper(amount)
                }</selection>
            }
            """.trimIndent(),
        )

        invokeCopyAnonymized()

        assertEquals(
            "fun method1(param2: Long) {\n" +
                "        // Unknown3(param2)\n" +
                "        Unknown4(param2)\n" +
                "    }",
            clipboard(),
        )
        assertEquals(
            "2 names replaced · 2 unknown (1 from comments) · 1 preserved · 1 comment anonymized<br>1 comment stripped",
            notifications.single().content,
        )
    }

    /**
     * **A comment parse that throws fails the invocation closed**, in Kotlin as in Java. A parse that
     * fails is observable — a tree carrying error elements — and that is the only verdict there is; a
     * parse that *throws* cannot tell an unparseable body from a broken platform, so it is not caught
     * and called prose. The clipboard is untouched, and nothing is committed to the mapping.
     */
    fun `test a Kotlin comment parse that throws leaves the clipboard and the mapping untouched`() {
        assertTheHarnessResolves()
        myFixture.configureByText(
            "Ledger.kt",
            """
            class Ledger {
                <selection>fun settle() {
                    // TODO: fix this
                }</selection>
            }
            """.trimIndent(),
        )
        setClipboard(PREVIOUS_CLIPBOARD)
        val committed = PlaceholderLedger.getInstance().snapshotOf(project)

        invokeCopyAnonymized(
            CopyAnonymizedAction { request ->
                KotlinPlanBuilder.build(request) { _, _ -> error("the parser fell over") }
            },
        )

        assertEquals("The clipboard was changed by a failed invocation.", PREVIOUS_CLIPBOARD, clipboard())
        assertEquals(NotificationType.ERROR, notifications.single().type)
        val after = PlaceholderLedger.getInstance().snapshotOf(project)
        assertEquals("a failed invocation named a symbol", committed.placeholders, after.placeholders)
        assertEquals("a failed invocation burnt a number", committed.nextNumber, after.nextNumber)
    }

    /**
     * **One symbol, one placeholder — whichever language it was met in, and whether it was met in a
     * comment or in code.** A `.kt` selection names a Kotlin class and its method only inside a
     * commented-out line; a `.java` selection then names both in live code, and a third names them in
     * a commented-out Java line. The key belongs to the declaration, not to where the reference sat,
     * so all three agree.
     */
    fun `test a kt and a java file one after the other give a symbol met in a comment one placeholder`() {
        assertTheHarnessResolves()
        val kotlin = """
            class Ledger {
                fun settle() {}

                fun close() {
                    <selection>// Ledger().settle()</selection>
                }
            }
        """.trimIndent()

        myFixture.configureByText("Ledger.kt", kotlin)
        invokeCopyAnonymized()
        assertEquals("// Type1().method2()", clipboard())

        myFixture.configureByText(
            "Audit.java",
            "class Audit { <selection>void run(Ledger ledger) { ledger.settle(); }</selection> }",
        )
        invokeCopyAnonymized()
        assertEquals("void method3(Type1 param4) { param4.method2(); }", clipboard())

        myFixture.configureByText(
            "Review.java",
            "class Review { void run() {\n    <selection>// new Ledger().settle();</selection>\n} }",
        )
        invokeCopyAnonymized()
        assertEquals("// new Type1().method2();", clipboard())
    }

    private companion object {

        /** Something recognisable to leave on the clipboard, so that *untouched* is readable. */
        const val PREVIOUS_CLIPBOARD = "the text the user copied before reaching for SnippetVeil"
    }
}
