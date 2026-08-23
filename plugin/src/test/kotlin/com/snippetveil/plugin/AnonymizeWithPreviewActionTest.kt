package com.snippetveil.plugin

import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.snippetveil.core.AnonymizationSettings
import com.snippetveil.core.LedgerDelta
import com.snippetveil.core.MintedName

/**
 * The preview end to end — the action, the real dialog object, and the clipboard.
 *
 * Three claims are asserted here and nowhere else, because all three are about the *invocation*
 * rather than about the widget: what the dialog shows is what the clipboard gets, a cancelled
 * preview burns nothing, and a reduction reaches the output. The dialog stands in the flow where the
 * modal window would, built and never shown.
 */
class AnonymizeWithPreviewActionTest : JavaSnippetTestCase() {

    /**
     * **The preview is a re-render, not a second path.**
     *
     * The same selection is copied twice against the same ledger — once by the fast action, once
     * through the preview — and the dialog's opening text is compared to both. It has to be
     * byte-identical to what `Copy Anonymized` would have put on the clipboard, or the dialog is
     * showing the user one thing and copying another, which is the one failure a preview cannot
     * have.
     *
     * The ledger is reset between the two, because it is the thing that makes two invocations of the
     * same selection differ legitimately: the second would carry on from the counter the first left.
     */
    fun `test the preview text is byte-identical to what Copy Anonymized would have copied`() {
        assertTheHarnessResolves()

        myFixture.configureByText(LEDGER_PATH, LEDGER)
        invokeCopyAnonymized()
        val fastPath = clipboard()

        PlaceholderLedger.getInstance().loadState(PlaceholderLedger.State())
        setClipboard(PREVIOUS_CLIPBOARD)

        var shown: String? = null
        myFixture.configureByText(LEDGER_PATH, LEDGER)
        invokeWithPreview { project, analysis -> opening(project, analysis).also { shown = it.result.text } }
        awaitBackgroundWork()

        assertEquals("the preview showed one text and the clipboard got another", shown, clipboard())
        assertEquals("the preview is a second path, not a re-render", fastPath, clipboard())
    }

    /**
     * **Cancel commits no delta and burns no number.** The dialog's whole reason for being modal is
     * that there is an unambiguous commit point, and this is the assertion that it is the button
     * rather than the opening: nothing about the clipboard, the mapping, the sidecar or the balloons
     * may move on the way out.
     *
     * `PlaceholderSidecar` is checked too, because it is the one holder written after the balloon —
     * a copy that never happened has nothing to be recovered from.
     */
    fun `test a cancelled preview leaves the clipboard, the mapping and the sidecar untouched`() {
        assertTheHarnessResolves()
        myFixture.configureByText(LEDGER_PATH, LEDGER)
        setClipboard(PREVIOUS_CLIPBOARD)

        val before = PlaceholderLedger.getInstance().snapshotOf(project)
        var cancelled = false
        invokeWithPreview { project, analysis ->
            opening(project, analysis)
            cancelled = true
            null
        }
        awaitEvents("the preview was never opened") { cancelled }

        assertEquals("a cancelled preview reached the clipboard", PREVIOUS_CLIPBOARD, clipboard())

        val after = PlaceholderLedger.getInstance().snapshotOf(project)
        assertEquals("a cancelled preview named a symbol", before.placeholders, after.placeholders)
        assertEquals("a cancelled preview burnt a number", before.nextNumber, after.nextNumber)
        assertNull("a cancelled preview reached the sidecar", PlaceholderSidecar.getInstance(project).originalOf("method1"))
        assertEmpty(notifications)
    }

    /**
     * **The reduction reaches the clipboard, and only this invocation's.** Keeping comments is the
     * larger of the two reductions the dialog offers, and the balloon reports what actually
     * happened: nothing was stripped, because nothing was.
     */
    fun `test keeping comments in the preview keeps them in the copy`() {
        assertTheHarnessResolves()
        myFixture.configureByText(
            LEDGER_PATH,
            """
            class Ledger {
                <selection>// reconcile against the merchant ledger
                void settle() {}</selection>
            }
            """.trimIndent(),
        )

        invokeWithPreview { _, analysis ->
            analysis.rendered(settings = AnonymizationSettings(keepComments = true))
        }
        awaitBackgroundWork()

        assertEquals("// reconcile against the merchant ledger\n    void method1() {}", clipboard())
        assertFalse(
            "the balloon claims a comment was stripped: " + notifications.single().content,
            notifications.single().content.contains("comment"),
        )
    }

    /**
     * **The reduction is per-invocation, and the next preview opens without it.** A tick that
     * survived one copy is precisely the forgotten reduction the settings rule exists to prevent —
     * so the next invocation is handed the maximally-anonymizing settings again, whatever the last
     * one did.
     */
    fun `test a reduction does not survive into the next invocation`() {
        assertTheHarnessResolves()
        val source = """
            class Ledger {
                <selection>// reconcile against the merchant ledger
                void settle() {}</selection>
            }
        """.trimIndent()

        myFixture.configureByText(LEDGER_PATH, source)
        invokeWithPreview { _, analysis -> analysis.rendered(settings = AnonymizationSettings(keepComments = true)) }
        awaitBackgroundWork()
        assertTrue("the first copy did not keep its comment", "reconcile" in clipboard())

        var opened: AnonymizationSettings? = null
        myFixture.configureByText(LEDGER_PATH, source)
        invokeWithPreview { project, analysis -> opening(project, analysis).also { opened = it.settings } }
        awaitBackgroundWork()

        val settings = checkNotNull(opened) { "the second preview never opened" }
        assertFalse("the second preview opened with the first one's reduction", settings.keepComments)
        assertEmpty(settings.preservedUnknowns)
        assertFalse("the second copy kept a comment nobody ticked: " + clipboard(), "reconcile" in clipboard())
    }

    /**
     * **Preserving an unresolved name emits it verbatim** — the one deliberate fail-open in the
     * product, bought to keep a snippet answerable when a typo'd call is hidden behind a placeholder.
     * It travels as a key rather than a name, which is what stops it reaching resolved symbols.
     */
    fun `test preserving an Unknown row in the preview emits that name verbatim`() {
        assertTheHarnessResolves()
        myFixture.configureByText(
            LEDGER_PATH,
            "class Ledger { <selection>void settle(MissingType m) { m.reconcile(); }</selection> }",
        )

        invokeWithPreview { _, analysis ->
            val unknown = analysis.result.names.first { it.original == "MissingType" }
            analysis.rendered(settings = AnonymizationSettings(preservedUnknowns = setOfNotNull(unknown.key)))
        }
        awaitBackgroundWork()

        assertTrue("the preserved name is not in the copy: " + clipboard(), "MissingType" in clipboard())
        assertFalse("the other unresolved name was preserved too: " + clipboard(), "reconcile" in clipboard())
    }

    /**
     * **The dialog widens the window in which another invocation can commit** — from milliseconds to
     * however long it is open — and what is copied is rendered against the ledger as it stands at the
     * moment of the copy, not the one the preview was opened against.
     *
     * Made deterministic rather than raced: an interloper commits while the dialog is "open", which
     * is exactly what a second invocation finishing during a long read would do.
     */
    fun `test an invocation whose ledger moved while the preview was open is re-rendered against it`() {
        assertTheHarnessResolves()
        myFixture.configureByText(LEDGER_PATH, "class Ledger { <selection>void settle() {}</selection> }")

        invokeWithPreview { _, analysis ->
            PlaceholderLedger.getInstance().commit(project, LedgerDelta(mapOf(INTERLOPER to MintedName("method1", "audit")), nextNumber = 2))
            analysis
        }
        awaitBackgroundWork()

        assertEquals("void method2() {}", clipboard())
    }

    /**
     * **A throw in the analysis never opens the preview**, and the clipboard is byte-identical. The
     * fail-closed guarantee is a property of the invocation rather than of the action that started
     * it, so it holds identically on this path.
     */
    fun `test a throw in the analysis opens no preview and leaves the clipboard untouched`() {
        assertTheHarnessResolves()
        myFixture.configureByText(LEDGER_PATH, LEDGER)
        setClipboard(PREVIOUS_CLIPBOARD)

        var opened = false
        invokeWithPreview({ error("the analysis fell over") }) { _, analysis -> analysis.also { opened = true } }
        awaitBackgroundWork()

        assertFalse("a failed analysis opened the preview", opened)
        assertEquals("The clipboard was changed by a failed invocation.", PREVIOUS_CLIPBOARD, clipboard())
        assertEquals(NotificationType.ERROR, notifications.single().type)
    }

    /** The Java-file gate is the same gate: anything it cannot anonymize, it does not offer. */
    fun `test the preview is absent outside Java files and present inside them`() {
        myFixture.configureByText("application.properties", "merchant.id=acme-payments")
        assertFalse(invokeWithPreview { _, analysis -> analysis }.isEnabledAndVisible)

        myFixture.configureByText(LEDGER_PATH, LEDGER)
        assertTrue(invokeWithPreview { _, analysis -> analysis }.isEnabledAndVisible)
        awaitBackgroundWork()
    }

    /**
     * The real dialog, opened and disposed, standing where the modal window stands. Its opening state
     * is the thing under test on the paths that use it — a stub returning the analysis it was handed
     * would assert that the *stub* re-renders nothing.
     */
    private fun opening(project: Project, analysis: Analysis): Analysis {
        val dialog = PreviewDialog.forCopy(project, analysis)
        try {
            return dialog.analysis
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }
}

private const val LEDGER_PATH = "Ledger.java"

private const val PREVIOUS_CLIPBOARD = "the raw snippet the user copied a minute ago"

/** A symbol another invocation named while the dialog was open. */
private const val INTERLOPER = "method:class:com.other.Interloper#run"

private val LEDGER = """
    class Ledger {
        <selection>String describe(int amount) {
            return String.valueOf(amount);
        }</selection>
    }
""".trimIndent()
