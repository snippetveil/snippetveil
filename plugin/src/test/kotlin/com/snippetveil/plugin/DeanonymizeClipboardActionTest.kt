package com.snippetveil.plugin

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.testFramework.TestActionEvent
import com.snippetveil.core.LedgerDelta
import com.snippetveil.core.deanonymize

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
        myFixture.configureByText(LEDGER, SNIPPET)

        invokeCopyAnonymized()
        val anonymized = clipboard()
        val clipboardUnderTest = FakeClipboard("Have a look at this:\n$anonymized\nThe loop is the problem.")

        invokeDeanonymize(clipboardUnderTest)

        assertEquals(
            "Have a look at this:\n${selectionOf(SNIPPET)}\nThe loop is the problem.",
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
        myFixture.configureByText(LEDGER, SNIPPET)
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
        myFixture.configureByText(LEDGER, SNIPPET)
        invokeCopyAnonymized()

        invokeDeanonymize(FakeClipboard("`method1` and `local9`"))

        val balloon = notifications.single()
        assertEquals("Clipboard de-anonymized", balloon.title)
        assertEquals("1 placeholders restored · 1 not restored", balloon.content)
        assertEquals(NotificationType.INFORMATION, balloon.type)
    }

    /**
     * **`Show details` is offered only when there is something to list.** A number is worth stating
     * at zero; an action offering to list nothing is a dead end dressed as an offer.
     */
    fun `test Show details is offered only when something was not restored`() {
        assertTheHarnessResolves()
        myFixture.configureByText(LEDGER, SNIPPET)
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
        // leaves behind: the counter moved and no name was written down.
        PlaceholderLedger.getInstance().commit(project, LedgerDelta(emptyMap(), nextNumber = 10))

        invokeDeanonymize(FakeClipboard("local4 and local40"))

        val rows = UnrestoredTableModel(reversalOf("local4 and local40").unrestored)
        assertEquals("local4", rows.getValueAt(0, 0))
        assertEquals("beyond the recent-history window", rows.getValueAt(0, 1))
        assertEquals("local40", rows.getValueAt(1, 0))
        assertEquals("not from this project", rows.getValueAt(1, 1))
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
        myFixture.configureByText(LEDGER, SNIPPET)
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

/**
 * A clipboard a test owns, and can make fail.
 *
 * [written] rather than a comparison against [text], because *"the clipboard was not rewritten"* and
 * *"the clipboard was rewritten with the same string"* are different behaviours that leave the same
 * string behind, and it is the first of the two that is the guarantee.
 */
private class FakeClipboard(var text: String?, private val failWrite: Boolean = false) : Clipboard {

    var written: Boolean = false
        private set

    override fun read(): String? = text

    override fun write(text: String) {
        if (failWrite) throw IllegalStateException("the system clipboard is owned by another process")
        this.text = text
        written = true
    }
}

private const val LEDGER = "Ledger.java"

/**
 * A snippet with no comment in it, deliberately: comments are stripped by default and no reversal
 * restores destroyed information, so a fixture with one would make the round trip assert the
 * strip rather than the reversal. That the default path is lossy is asserted where it belongs, in
 * `:core`'s round-trip test.
 */
private val SNIPPET = """
    class Ledger {
        <selection>int settle(int amount) {
            int owed = amount;
            return owed;
        }</selection>
    }
""".trimIndent()

/** What `<selection>` marks, which is what the copy took and therefore what the reversal must return. */
private fun selectionOf(text: String): String =
    text.substringAfter("<selection>").substringBefore("</selection>")
