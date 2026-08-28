package com.snippetveil.plugin

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.testFramework.TestActionEvent

/**
 * **The one-shot: the reversal and the paste as a single gesture.**
 *
 * The reversal itself is `:core`'s and is tested there; what is only assertable here is the half
 * this action adds — *when it writes to the document, when it refuses to, and what it leaves on the
 * clipboard either way*. Those three are the whole of the decision this action implements, and each
 * one is a claim about a document rather than about a string.
 *
 * **The round trips go through the real anonymization first**, for the reason
 * [DeanonymizeClipboardActionTest] does: a hand-written placeholder would assert against a table the
 * test invented rather than the one the product wrote.
 */
class DeanonymizeClipboardAndPasteActionTest : JavaSnippetTestCase() {

    /**
     * **The whole feature, over the real thing.** Copy a selection anonymized, and paste it back into
     * a different file — and what lands at the caret is the snippet that was selected, character for
     * character, with the text around the caret untouched.
     *
     * The destination is Markdown deliberately. The workflow this action shortens ends wherever the
     * user right-clicked, and gating the insert to `.java` would make the item wrong on exactly the
     * scratch buffer the reply was headed for.
     */
    fun `test an anonymized snippet is restored and inserted at the caret`() {
        val anonymized = anonymizedSnippet()

        myFixture.configureByText("notes.md", "Before\n<caret>\nAfter")
        invokeDeanonymizeAndPaste(FakeClipboard(anonymized))

        assertEquals("Before\n${selectionIn(REVERSAL_SNIPPET)}\nAfter", myFixture.editor.document.text)
    }

    /** A selection is replaced rather than pushed aside, which is what every other paste in the IDE does. */
    fun `test a selection is replaced by the restored text`() {
        val anonymized = anonymizedSnippet()

        myFixture.configureByText("notes.md", "Before <selection>the old text</selection> After")
        invokeDeanonymizeAndPaste(FakeClipboard(anonymized))

        assertEquals("Before ${selectionIn(REVERSAL_SNIPPET)} After", myFixture.editor.document.text)
    }

    /**
     * **The caret ends after what was inserted**, so that typing continues where the reply ended
     * rather than in front of it.
     */
    fun `test the caret lands after the inserted text`() {
        val anonymized = anonymizedSnippet()

        myFixture.configureByText("notes.md", "Before\n<caret>\nAfter")
        invokeDeanonymizeAndPaste(FakeClipboard(anonymized))

        assertEquals("Before\n".length + selectionIn(REVERSAL_SNIPPET).length, myFixture.editor.caretModel.offset)
    }

    /**
     * **The clipboard is left holding the anonymized reply**, which is the decision this action was
     * filed on rather than an omission.
     *
     * Writing the restored text back would destroy the thing the user may still want to quote into
     * the chat, and the insert has already delivered the value the clipboard copy would carry. So the
     * action is purely additive: a document gains text and nothing is taken away.
     */
    fun `test the clipboard still holds the anonymized reply afterwards`() {
        val anonymized = anonymizedSnippet()

        myFixture.configureByText("notes.md", "<caret>")
        val clipboardUnderTest = FakeClipboard(anonymized)
        invokeDeanonymizeAndPaste(clipboardUnderTest)

        assertFalse("the clipboard was written by an action that only pastes", clipboardUnderTest.written)
        assertEquals(anonymized, clipboardUnderTest.text)
    }

    /**
     * **One undo step, not two.** The reversal and the insert are one intention, and the whole point
     * of the item is that the user performed one gesture — so undo has to answer the gesture rather
     * than the implementation.
     */
    fun `test the insert is a single undo step`() {
        val anonymized = anonymizedSnippet()

        myFixture.configureByText("notes.md", "Before\n<caret>\nAfter")
        invokeDeanonymizeAndPaste(FakeClipboard(anonymized))
        assertTrue(
            "nothing was inserted, so the undo assertion below would be vacuous",
            myFixture.editor.document.text != "Before\n\nAfter",
        )

        myFixture.performEditorAction(IdeActions.ACTION_UNDO)

        assertEquals("Before\n\nAfter", myFixture.editor.document.text)
    }

    /**
     * **Nothing recognised means nothing written**, and this is the leak-adjacent half of the
     * decision.
     *
     * `De-anonymize Clipboard` can afford to no-op on a reply it recognises nothing in, because what
     * it would have written goes to the clipboard and the user reads it before it lands anywhere.
     * Here the equivalent no-op puts unrestored placeholders **into source code**, where they compile
     * as identifiers and read as names somebody chose. So the paste is refused and the balloon says
     * why.
     */
    fun `test a reply with no placeholders in it is not pasted`() {
        myFixture.configureByText("notes.md", "Before\n<caret>\nAfter")

        invokeDeanonymizeAndPaste(FakeClipboard("Hoist the lookup out of the loop and use a HashMap."))

        assertEquals("Before\n\nAfter", myFixture.editor.document.text)
        assertEquals("No SnippetVeil placeholders found — nothing was pasted.", notifications.single().content)
        assertEquals(NotificationType.INFORMATION, notifications.single().type)
    }

    /** A clipboard holding an image, a file list or nothing at all is the same answer, said the same way. */
    fun `test a clipboard holding no text at all is reported as nothing found`() {
        myFixture.configureByText("notes.md", "<caret>")

        invokeDeanonymizeAndPaste(FakeClipboard(null))

        assertEquals("No SnippetVeil placeholders found — nothing was pasted.", notifications.single().content)
    }

    /**
     * **A partial reversal is refused too, and that is the same rule rather than a stricter one.**
     *
     * Twelve of fifteen is the case where refusing costs the most and matters most: the three that
     * did not come back are the ones that would go into the file as live placeholders, and they are
     * the least likely to be noticed precisely because everything around them reads correctly.
     *
     * `Show details` is on it for the reason it is on the sibling's balloon — the two buckets are
     * what tells *the name is gone* from *this was never ours*, and here they are also what tells the
     * user whether a second attempt is worth anything.
     */
    fun `test a partial reversal is refused and the document is not touched`() {
        assertTheHarnessResolves()
        myFixture.configureByText(REVERSAL_LEDGER, REVERSAL_SNIPPET)
        invokeCopyAnonymized()

        myFixture.configureByText("notes.md", "Before\n<caret>\nAfter")
        invokeDeanonymizeAndPaste(FakeClipboard("`method1` and `local9`"))

        assertEquals("Before\n\nAfter", myFixture.editor.document.text)
        val balloon = notifications.single()
        assertEquals("Nothing pasted — 1 placeholder did not restore.", balloon.title)
        assertEquals("The clipboard still holds the anonymized reply.", balloon.content)
        assertEquals(NotificationType.WARNING, balloon.type)
        assertEquals(listOf("Show details"), balloon.actions.map { it.templatePresentation.text })
    }

    /** The noun agrees with the number here for the same reason it does on the sibling's balloon. */
    fun `test the refusal counts more than one placeholder in the plural`() {
        assertTheHarnessResolves()
        myFixture.configureByText(REVERSAL_LEDGER, REVERSAL_SNIPPET)
        invokeCopyAnonymized()

        myFixture.configureByText("notes.md", "<caret>")
        invokeDeanonymizeAndPaste(FakeClipboard("`method1`, `local9` and `local8`"))

        assertEquals("Nothing pasted — 2 placeholders did not restore.", notifications.single().title)
    }

    /**
     * **A failure states both facts**, because after this action there are two places the user's next
     * move depends on: the document did not get the text, and the clipboard still holds the reply
     * they can paste by hand.
     */
    fun `test a failed read says nothing was inserted and the clipboard was not changed`() {
        myFixture.configureByText("notes.md", "Before\n<caret>\nAfter")

        invokeDeanonymizeAndPaste(FakeClipboard("`method1` here", failRead = true))

        assertEquals("Before\n\nAfter", myFixture.editor.document.text)
        val balloon = notifications.single()
        assertEquals("Paste failed — nothing was inserted and your clipboard was not changed.", balloon.content)
        assertEquals(NotificationType.ERROR, balloon.type)
    }

    /**
     * **Greyed out with no editor, and visible anyway** — which is the placement decision, and the
     * reason the submenu can stay one group with two parents.
     *
     * The Tools menu is reachable with nothing open, and this item cannot work there. Hiding it would
     * make the Tools menu and the editor popup show different submenus and cost the identity that
     * `ActionRegistrationTest` pins; keeping it out of the Tools registration would cost the same
     * thing more expensively. Disabled says *this item exists and needs an editor*, which is the true
     * sentence and the discoverable one.
     */
    fun `test the action is visible but disabled with no editor open`() {
        val action = DeanonymizeClipboardAndPasteAction(FakeClipboard(null))
        val event = TestActionEvent.createTestEvent(action, SimpleDataContext.getProjectContext(project))

        action.update(event)

        assertTrue("the item disappeared from the Tools menu instead of greying out", event.presentation.isVisible)
        assertFalse("the item was offered with nowhere to paste into", event.presentation.isEnabled)
    }

    /**
     * **A read-only document greys it out too**, and for the same reason rather than a second one:
     * the gate is *somewhere to write*, and a document that refuses writes is not one.
     */
    fun `test the action is disabled on a read-only document`() {
        myFixture.configureByText("notes.md", "<caret>")
        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.setReadOnly(true) }

        val presentation = invokeDeanonymizeAndPaste(FakeClipboard("`method1` here"))

        assertTrue("the item disappeared instead of greying out", presentation.isVisible)
        assertFalse("the item was offered over a document that cannot be written", presentation.isEnabled)
        assertEmpty(notifications)
    }

    /** And with a writable editor it is enabled, which is what stops the two checks above being vacuous. */
    fun `test the action is enabled in a writable editor of any file type`() {
        myFixture.configureByText("notes.md", "<caret>")

        val presentation = invokeDeanonymizeAndPaste(FakeClipboard("nothing of ours"))

        assertTrue("the action is not offered outside Java files", presentation.isEnabledAndVisible)
    }

    /**
     * **The balloon, and the two facts on it.** What came back, and — in place of the sibling's *not
     * restored* count, which is zero here by construction — the clipboard fact the user's next
     * keystroke depends on.
     */
    fun `test the balloon reports what was restored and that the clipboard is unchanged`() {
        assertTheHarnessResolves()
        myFixture.configureByText(REVERSAL_LEDGER, REVERSAL_SNIPPET)
        invokeCopyAnonymized()

        myFixture.configureByText("notes.md", "<caret>")
        invokeDeanonymizeAndPaste(FakeClipboard("`method1`"))

        val balloon = notifications.single()
        assertEquals("Reply pasted, de-anonymized", balloon.title)
        assertEquals("1 placeholder restored · clipboard unchanged", balloon.content)
        assertEquals(NotificationType.INFORMATION, balloon.type)
        assertEmpty(balloon.actions)
    }

    /** The noun agrees with the number on the way in as well as on the way back. */
    fun `test the balloon counts more than one placeholder in the plural`() {
        assertTheHarnessResolves()
        myFixture.configureByText(REVERSAL_LEDGER, REVERSAL_SNIPPET)
        invokeCopyAnonymized()

        myFixture.configureByText("notes.md", "<caret>")
        invokeDeanonymizeAndPaste(FakeClipboard("`method1` and `param2`"))

        assertEquals("2 placeholders restored · clipboard unchanged", notifications.single().content)
    }

    /**
     * **Every caret, not the primary one** — which is what every other paste in the IDE does.
     *
     * Inserting once and leaving the other carets alone would not read as a restricted feature; it
     * would read as a paste that half worked, on the one item in this plugin that writes into source.
     */
    fun `test the reply is inserted at every caret`() {
        assertTheHarnessResolves()
        myFixture.configureByText(REVERSAL_LEDGER, REVERSAL_SNIPPET)
        invokeCopyAnonymized()

        myFixture.configureByText("notes.md", "one <caret> two <caret> three")
        invokeDeanonymizeAndPaste(FakeClipboard("`method1`"))

        assertEquals("one `settle` two `settle` three", myFixture.editor.document.text)
    }

    /** And each caret's selection is replaced, rather than the primary one's alone. */
    fun `test every caret's selection is replaced`() {
        assertTheHarnessResolves()
        myFixture.configureByText(REVERSAL_LEDGER, REVERSAL_SNIPPET)
        invokeCopyAnonymized()

        // A caret marker inside each selection, because two bare <selection> pairs are read as one
        // selection spanning both — which would assert a single replacement and call it multi-caret.
        myFixture.configureByText("notes.md", "one <selection>X<caret></selection> two <selection>Y<caret></selection> three")
        invokeDeanonymizeAndPaste(FakeClipboard("`method1`"))

        assertEquals("one `settle` two `settle` three", myFixture.editor.document.text)
    }

    /**
     * **A viewer greys it out even though its document is writable**, and that is a second refusal
     * rather than a restatement of the first.
     *
     * A console, and a diff or preview pane, hold a perfectly writable document behind a view that
     * refuses typing. A gate reading only `isWritable` enables the item there and then writes into a
     * pane the user believes is read-only.
     */
    fun `test the action is disabled in a viewer editor whose document is writable`() {
        myFixture.configureByText("notes.md", "<caret>")
        (myFixture.editor as EditorEx).isViewer = true

        val presentation = invokeDeanonymizeAndPaste(FakeClipboard("`method1` here"))

        assertTrue("the document is writable, so this case is only about the viewer flag", myFixture.editor.document.isWritable)
        assertTrue("the item disappeared instead of greying out", presentation.isVisible)
        assertFalse("the item was offered over a viewer", presentation.isEnabled)
        assertEmpty(notifications)
    }

    /**
     * **A write that throws does not get to say the document is untouched.**
     *
     * The insert is a command rather than a transaction, so the honest message is that the reply
     * *may* be partly inserted — the restraint `exportFailed` shows about a half-written file.
     *
     * Driven through [restoreIntoDocument] rather than the action, deliberately: `update` refuses a
     * read-only document, so the only way to reach this arm through the action would be to remove the
     * gate that makes it unreachable.
     */
    fun `test a failed insert does not claim the document was left alone`() {
        assertTheHarnessResolves()
        myFixture.configureByText(REVERSAL_LEDGER, REVERSAL_SNIPPET)
        invokeCopyAnonymized()

        myFixture.configureByText("notes.md", "Before\n<caret>\nAfter")
        val editor = myFixture.editor
        WriteCommandAction.runWriteCommandAction(project) { editor.document.setReadOnly(true) }

        dropEarlierBalloons()
        restoreIntoDocument(project, editor, FakeClipboard("`method1`"))

        assertEquals("Before\n\nAfter", editor.document.text)
        val balloon = notifications.single()
        assertEquals("Paste failed — the reply may be partly inserted. Your clipboard was not changed.", balloon.content)
        assertEquals(NotificationType.ERROR, balloon.type)
    }

    /**
     * Copies [REVERSAL_SNIPPET] anonymized and hands back what landed on the clipboard — the reply an AI would
     * be quoting back, minus the prose, which is what the paste path is asserted over.
     */
    private fun anonymizedSnippet(): String {
        assertTheHarnessResolves()
        myFixture.configureByText(REVERSAL_LEDGER, REVERSAL_SNIPPET)
        invokeCopyAnonymized()
        return clipboard()
    }
}
