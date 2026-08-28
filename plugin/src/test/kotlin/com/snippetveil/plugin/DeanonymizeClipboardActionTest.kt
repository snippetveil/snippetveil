package com.snippetveil.plugin

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.util.Disposer
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.UIUtil
import com.intellij.testFramework.TestActionEvent
import com.snippetveil.core.LedgerDelta
import com.snippetveil.core.deanonymize
import javax.swing.table.TableModel

/**
 * **The other direction, end to end: a reply in, the project's own names out.**
 *
 * The reversal contract itself is `:core`'s and is tested there, over values and without an IDE.
 * What can only be said here is what the *action* does — where it is offered, what it does to the
 * clipboard, and what it says afterwards — and the round trip that matters is the one that goes
 * through the real anonymization first: a test that hand-wrote a placeholder into a fixture would
 * assert against a table it invented rather than against the one the product wrote.
 */
class DeanonymizeClipboardActionTest : JavaSnippetTestCase() {

    /**
     * **The whole feature, over the real thing.** Copy a selection anonymized, wrap what came back in
     * the prose an AI would put round it, and reverse — and the snippet in the middle is the snippet
     * that was selected, character for character.
     */
    fun `test a reply built out of a real anonymized snippet comes back as the original`() {
        assertTheHarnessResolves()
        myFixture.configureByText(REVERSAL_LEDGER, REVERSAL_SNIPPET)

        invokeCopyAnonymized()
        val anonymized = clipboard()
        val clipboardUnderTest = FakeClipboard("Have a look at this:\n$anonymized\nThe loop is the problem.")

        invokeDeanonymize(clipboardUnderTest)

        assertEquals(
            "Have a look at this:\n${selectionIn(REVERSAL_SNIPPET)}\nThe loop is the problem.",
            clipboardUnderTest.text,
        )
    }

    /**
     * **The prose around the snippet is not touched, and neither is anything the model invented.** The
     * reversal replaces words it minted and nothing else, so the reply comes back as the model wrote
     * it apart from the names.
     */
    fun `test a placeholder inside a sentence is replaced and the sentence is not`() {
        assertTheHarnessResolves()
        myFixture.configureByText(REVERSAL_LEDGER, REVERSAL_SNIPPET)
        invokeCopyAnonymized()

        val clipboardUnderTest = FakeClipboard("`method1` should validate before it returns.")
        invokeDeanonymize(clipboardUnderTest)

        assertEquals("`settle` should validate before it returns.", clipboardUnderTest.text)
    }

    /**
     * **The balloon, and the two numbers on it.** Every number every time, including the zero: a
     * count that appeared only when it fired would make its absence unreadable, and *not restored* is
     * exactly the figure a user would otherwise assume was zero.
     */
    fun `test the balloon reports what was restored and what was not`() {
        assertTheHarnessResolves()
        myFixture.configureByText(REVERSAL_LEDGER, REVERSAL_SNIPPET)
        invokeCopyAnonymized()

        invokeDeanonymize(FakeClipboard("`method1` and `local9`"))

        val balloon = notifications.single()
        assertEquals("Clipboard de-anonymized", balloon.title)
        assertEquals("1 placeholder restored · 1 not restored", balloon.content)
        assertEquals(NotificationType.INFORMATION, balloon.type)
    }

    /**
     * **`Show details` is offered only when there is something to list.** A number is worth stating
     * at zero; an action offering to list nothing is a dead end dressed as an offer.
     */
    fun `test Show details is offered only when something was not restored`() {
        assertTheHarnessResolves()
        myFixture.configureByText(REVERSAL_LEDGER, REVERSAL_SNIPPET)
        invokeCopyAnonymized()

        invokeDeanonymize(FakeClipboard("`method1` and `local9`"))
        assertEquals(listOf("Show details"), notifications.single().actions.map { it.templatePresentation.text })

        invokeDeanonymize(FakeClipboard("`method1` on its own"))
        assertEmpty(notifications.single().actions)
    }

    /**
     * **The two buckets, over the store rather than over a value.** The counter is the test: a number
     * this project burnt and has since forgotten is *gone*, and a number it has not reached yet was
     * never ours.
     */
    fun `test the details list splits what is gone from what was never ours`() {
        // A commit with no rows in it, which is what an invocation of nothing but ephemeral symbols
        // leaves behind: the counter moved and no name was written down. So `local4` is a number this
        // project burnt and cannot name, and `local40` is one it has not reached.
        PlaceholderLedger.getInstance().commit(project, LedgerDelta(emptyMap(), nextNumber = 10))

        // Through the dialog the balloon opens rather than through its model, so that what is
        // asserted is the list a user is actually shown — columns, order and wording included.
        val rows = tableIn(UnrestoredDialog(project, reversalOf("local4 and local40").unrestored))

        assertEquals(listOf("Placeholder", "Why"), (0 until rows.columnCount).map { rows.getColumnName(it) })
        assertEquals(
            listOf(
                listOf("local4", "beyond the recent-history window"),
                listOf("local40", "not from this project"),
            ),
            (0 until rows.rowCount).map { row -> (0 until rows.columnCount).map { rows.getValueAt(row, it) } },
        )
    }

    /**
     * The table inside a dialog, reached through the panel the dialog actually builds — so that a
     * column the layout never adds cannot be asserted as though a user could see it.
     */
    private fun tableIn(dialog: UnrestoredDialog): TableModel =
        Disposer.register(testRootDisposable) { dialog.close(0) }.let {
            UIUtil.findComponentOfType(dialog.createCenterPanel(), JBTable::class.java)!!.model
        }

    /**
     * **A reply with none of ours in it is left alone, and the balloon says so.**
     *
     * Writing an identical string back would be a no-op the user cannot tell from a reversal that
     * worked, which is the one reading this must not allow: *"I ran it, so these are real names."*
     */
    fun `test a reply with no placeholders in it leaves the clipboard untouched`() {
        val clipboardUnderTest = FakeClipboard("Hoist the lookup out of the loop and use a HashMap.")

        invokeDeanonymize(clipboardUnderTest)

        assertEquals("Hoist the lookup out of the loop and use a HashMap.", clipboardUnderTest.text)
        assertFalse("the clipboard was rewritten with an identical string", clipboardUnderTest.written)
        assertEquals("No SnippetVeil placeholders found — clipboard unchanged.", notifications.single().content)
    }

    /** A clipboard holding an image, a file list or nothing at all is the same answer, said the same way. */
    fun `test a clipboard holding no text at all is reported as nothing found`() {
        invokeDeanonymize(FakeClipboard(null))

        assertEquals("No SnippetVeil placeholders found — clipboard unchanged.", notifications.single().content)
    }

    /**
     * **The mirror of the anonymization failure, and the safe half of it.** When a copy fails the
     * clipboard may hold the raw snippet; when this fails it still holds the *anonymized* reply —
     * unreadable, and nothing has leaked. The clipboard fact is stated either way, because the user's
     * next keystroke depends on it either way.
     */
    fun `test a failed write says the clipboard was not changed`() {
        assertTheHarnessResolves()
        myFixture.configureByText(REVERSAL_LEDGER, REVERSAL_SNIPPET)
        invokeCopyAnonymized()

        invokeDeanonymize(FakeClipboard("`method1` here", failWrite = true))

        val balloon = notifications.single()
        assertEquals("De-anonymization failed — your clipboard was not changed.", balloon.content)
        assertEquals(NotificationType.ERROR, balloon.type)
    }

    /**
     * **Available on every file type**, which is the opposite of the gate the two anonymizing actions
     * carry — and from the same premise. This reads no source: a reply is pasted into a scratch
     * buffer, a Markdown file or a commit message, and gating it to `.java` would make it actively
     * wrong on the file a user actually has open.
     */
    fun `test the action is offered on a file that is not Java`() {
        myFixture.configureByText("notes.md", "The reply goes here.")

        val presentation = invokeDeanonymize(FakeClipboard("nothing of ours"))

        assertTrue("the action is not offered outside Java files", presentation.isEnabledAndVisible)
    }

    /**
     * **The submenu in a Markdown editor: both reversals are offered and the anonymizing actions are
     * not.**
     *
     * This is the arrangement's whole point, and neither half of it is provable from the registration
     * alone. `plugin.xml` puts all four in one group, so *which* of them a user actually sees is
     * decided by four `update` methods — and the case that matters is the file type the two
     * reversals exist for and the two anonymizers refuse.
     *
     * Over an explicit context rather than the editor component's own, so that what is under test is
     * the gating rather than what a light fixture happens to publish into a `DataContext`.
     */
    fun `test the submenu in a Markdown editor offers the reversals and not the anonymizers`() {
        myFixture.configureByText("reply.md", "The AI said something about `method1`.")

        val offered = offeredInSubmenu()

        assertTrue("the reversal is not offered outside Java", "SnippetVeil.DeanonymizeClipboard" in offered)
        assertTrue("the pasting reversal is not offered outside Java", "SnippetVeil.DeanonymizeClipboardAndPaste" in offered)
        assertFalse("Copy Anonymized was offered on a Markdown file", "SnippetVeil.CopyAnonymized" in offered)
        assertFalse("the preview was offered on a Markdown file", "SnippetVeil.AnonymizeWithPreview" in offered)
    }

    /** And in a Java editor all four are offered, which is what stops the check above being vacuous. */
    fun `test the submenu in a Java editor offers all four`() {
        assertTheHarnessResolves()
        myFixture.configureByText(REVERSAL_LEDGER, REVERSAL_SNIPPET)

        assertEquals(
            listOf(
                "SnippetVeil.CopyAnonymized",
                "SnippetVeil.AnonymizeWithPreview",
                "SnippetVeil.DeanonymizeClipboard",
                "SnippetVeil.DeanonymizeClipboardAndPaste",
            ),
            offeredInSubmenu(),
        )
    }

    /**
     * The ids of the submenu's children whose own `update` leaves them enabled and visible, against
     * the file currently open in the fixture.
     */
    private fun offeredInSubmenu(): List<String> {
        val manager = ActionManager.getInstance()
        val menu = manager.getAction("SnippetVeil.Menu") as ActionGroup
        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.EDITOR, myFixture.editor)
            .add(CommonDataKeys.PSI_FILE, myFixture.file)
            .build()

        return menu.getChildren(null)
            .filter { child ->
                val event = TestActionEvent.createTestEvent(child, context)
                child.update(event)
                event.presentation.isEnabledAndVisible
            }
            .mapNotNull { manager.getId(it) }
    }

    /** And with no editor open at all, which is where a reply pasted into a commit message leaves the IDE. */
    fun `test the action is offered with no editor open`() {
        val action = DeanonymizeClipboardAction(FakeClipboard(null))
        val event = TestActionEvent.createTestEvent(action, SimpleDataContext.getProjectContext(project))

        action.update(event)

        assertTrue("the action is not offered without an editor", event.presentation.isEnabledAndVisible)
    }

    /** The reversal the action just ran, re-run over the same tables so its rows can be read. */
    private fun reversalOf(reply: String) = deanonymize(
        reply,
        PlaceholderSidecar.getInstance(project).window(),
        PlaceholderLedger.getInstance().snapshotOf(project),
    )
}
