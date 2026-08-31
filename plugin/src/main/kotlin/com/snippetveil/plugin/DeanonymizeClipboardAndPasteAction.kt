package com.snippetveil.plugin

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project

/**
 * **De-anonymize Clipboard and Paste** — the reply comes back as your real names *and* lands where
 * the caret is, in one invocation.
 *
 * The workflow it shortens is the real one: copy the reply out of the chat, come back to the IDE,
 * right-click where the code should go. At that point the reversal and the paste are one intention
 * split across two gestures, and this is the item that joins them.
 *
 * ### It is not [DeanonymizeClipboardAction] with a paste bolted on
 *
 * The sibling is clipboard in, clipboard out, and **every property it has follows from that**.
 * Attaching a document write breaks the premise rather than extending it, so none of the sibling's
 * decisions transfer unexamined — and the three that do not are the whole of this class:
 *
 * 1. **It needs an editor**, where the sibling needs only a project. See [update].
 * 2. **It refuses to write anything it could not fully restore.** See [restoreIntoDocument].
 * 3. **It leaves the clipboard alone.** See [restoreIntoDocument].
 *
 * ### `DumbAware`, carried over unchanged
 *
 * The reversal resolves nothing — the tables were written when the snippet was sent, and decoding
 * one is a lookup — and an insert resolves nothing either. There is no answer indexing could
 * improve, so there is nothing to grey out while it runs.
 *
 * @param clipboard the clipboard, injectable for the reason the sibling's is: *"the clipboard is
 *   left holding the anonymized reply"* and *"a failed read says nothing was inserted"* are the two
 *   guarantees here, and neither is assertable against a system clipboard a test cannot make fail.
 */
class DeanonymizeClipboardAndPasteAction internal constructor(private val clipboard: Clipboard) : AnAction(), DumbAware {

    /** The constructor the platform uses; `plugin.xml` names this class and nothing else. */
    constructor() : this(SystemClipboard)

    /** Nothing in [update] touches the UI hierarchy; it asks for a project and a writable document. */
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    /**
     * **Visible with a project, enabled only with somewhere to write** — greyed out rather than
     * hidden, and that is the placement decision rather than a default.
     *
     * The `SnippetVeil` submenu is one group registered into both the Tools menu and the editor
     * popup, and `ActionRegistrationTest` pins that identity: the two routes are the same item rather
     * than two registrations that can drift apart. The Tools menu is reachable with nothing open at
     * all, which is exactly the situation this action cannot serve — so something has to give.
     *
     * **Hiding it would make the two menus show different submenus**, which is the one thing the
     * one-group-two-parents shape exists to prevent; keeping it out of the Tools registration would
     * buy the same outcome by splitting the group, and pay for it with the invariant. Disabled says
     * *this item exists and needs an editor*, which is both the true sentence and the discoverable
     * one — a user who opens Tools with no editor learns the item is there.
     *
     * **The gate is a writable document and not a file type**, which is the one thing it does share
     * with the sibling and for the sibling's reason: this reads no source. A reply is pasted into a
     * scratch buffer, a Markdown file or a commit message, and a `.java` gate would make the item
     * wrong on the file the user actually has open.
     *
     * The group is still never empty where it is shown, because [DeanonymizeClipboardAction] beside
     * it needs only a project.
     */
    override fun update(event: AnActionEvent) {
        event.presentation.isVisible = event.project != null
        event.presentation.isEnabled = event.project != null && event.getData(CommonDataKeys.EDITOR).acceptsAnInsert()
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return
        restoreIntoDocument(project, editor, clipboard)
    }
}

/**
 * Somewhere to put the reply: an editor, not in viewer mode, over a document that accepts writes.
 *
 * **`isViewer` as well as `isWritable`, because they are different refusals and only one of them is
 * about the document.** A console, and a diff or preview pane, hold a document that is perfectly
 * writable and a view that refuses typing — so a gate reading only `isWritable` enables the item
 * there and then mutates a pane the user believes is read-only. That is the platform's own pair of
 * conditions, spelled out here rather than taken from `EditorModificationUtil` so the gate this
 * action is asserted on is visible at the point it is decided.
 *
 * Named for the question it answers rather than for the fields it reads: *no editor at all* and
 * *an editor that will not take an insert* are one answer to `update` and two different situations,
 * and `isWritable` on a nullable receiver reads as though it were only about the second.
 */
private fun Editor?.acceptsAnInsert(): Boolean = this != null && !isViewer && document.isWritable

/**
 * The reversal and the insert, end to end: read the clipboard, decode it, and write it at the caret
 * **only if all of it came back**.
 *
 * ### Refusing a partial reversal is the decision this action turns on
 *
 * [restoreClipboard] can afford to be relaxed about what it did not decode, because what it writes
 * goes to the clipboard: the user sees it before it lands anywhere, and a placeholder that survived
 * is a placeholder they can read in the paste preview of their own eyes. **Here the same leniency
 * writes unrestored placeholders into source code**, where they compile as identifiers and read as
 * names somebody chose. Nobody re-reads a paste they asked for.
 *
 * So the write happens on one condition — everything the tables recognised, they resolved — and both
 * ways of failing that condition are reported instead of pasted:
 *
 * - **Nothing recognised** is the sibling's *nothing to restore*, and it is the sharper case here.
 *   A silent no-op reads as *"I ran it, so these are real names"* on the clipboard; in a file it
 *   reads that way about text that is now committed.
 * - **Partially recognised** — twelve of fifteen — is the case where refusing costs the most and
 *   matters most. The three that did not come back are the least likely to be noticed, precisely
 *   because everything around them reads correctly.
 *
 * The refusal is cheap to recover from, which is what makes it the right default: the clipboard is
 * untouched, so `De-anonymize Clipboard` and a `Ctrl+V` are still there for a user who has looked at
 * the details and wants the text anyway. The reverse is not true — there is no undo for having read
 * a placeholder as a name.
 *
 * **The check is exactly as complete as `Unrestored` is, and something escapes the count today.**
 * `deanonymize` counts an undecoded word as unrestored when it matches the minted shape, and that
 * shape is every namespace the engine mints from **by default** — derived from `SymbolRole.entries`
 * rather than written out, so no default namespace can be forgotten into it.
 *
 * **A stem the user typed in the preview is outside it, and cannot be brought inside.** A custom
 * stem is arbitrary text, so a pattern wide enough to recognise `theFilter7` would claim `sha256`
 * and `count2` out of the model's own prose and refuse most replies outright — and *do not guess at
 * names by shape* is the rule this product refuses to break everywhere else. So a renamed
 * placeholder that decodes in neither table is not reported here, and **this action writes it into
 * source** where a default-stemmed one would have been refused.
 *
 * It is bounded rather than open: a qualified key's rename is in the mapping for the life of the
 * project, so the gap is a renamed local, parameter, type parameter or label in a reply older than
 * the sidecar horizon — and every renamed placeholder after a `Reset Mappings…`, which clears both
 * stores at once.
 *
 * **This paragraph is a disclosure, not a design.** The tolerance it describes was argued when
 * under-recovery meant a clipboard the user reads before it lands anywhere, and this caller turns
 * the same word into a silent write into source; that argument does not carry here, and the
 * decision on what to do about it is snippetveil/snippetveil#74.
 *
 * ### The clipboard is not written, by either path
 *
 * The insert has already delivered the value a clipboard write would carry, and writing the restored
 * text back would destroy the anonymized reply the user may still want to quote into the chat. So
 * this action is purely additive: a document gains text, and nothing is taken away.
 *
 * ### On the EDT, and in one command
 *
 * The reversal is [restoreClipboard]'s two map lookups per word, with no PSI, no resolution and no
 * index — there is nothing here worth a thread hop. The insert is wrapped in a single
 * [WriteCommandAction] so that one gesture is one undo step: the whole point of the item is that the
 * user did this once, and undo has to answer the gesture rather than the implementation.
 *
 * ### The two failures are two messages, because only one of them can promise an untouched document
 *
 * A clipboard read that throws happens before anything is written, so its balloon can say *nothing
 * was inserted* and mean it. **A write that throws cannot.** The insert is a command rather than a
 * transaction: `replaceString` can succeed at the first caret and the throw arrive after, which
 * leaves text in the document while the message denies it. So that arm reports what it can vouch
 * for and says the reply *may* be partly inserted — the same restraint `exportFailed` shows about a
 * half-written file, and for the same reason: a message that must not contain a lie may not say the
 * one thing this code cannot check.
 *
 * **The balloon is outside the `try`,** for the reason the sibling's is: by the time it runs the
 * insert has happened, and a `try` wide enough to hold it would answer a failing balloon with a
 * report of a paste that did not occur.
 */
internal fun restoreIntoDocument(project: Project, editor: Editor, clipboard: Clipboard = SystemClipboard) {
    val reply = try {
        clipboard.read()
    } catch (failure: Throwable) {
        SnippetVeilNotifications.pasteFailed(project, failure)
        return
    }

    val reversal = reversalFor(project, reply.orEmpty())

    if (!reversal.found) {
        SnippetVeilNotifications.nothingToPaste(project)
        return
    }
    if (reversal.unrestored.isNotEmpty()) {
        SnippetVeilNotifications.pasteRefused(project, reversal)
        return
    }

    try {
        insertAtEveryCaret(project, editor, reversal.text)
    } catch (failure: Throwable) {
        SnippetVeilNotifications.insertFailed(project, failure)
        return
    }
    SnippetVeilNotifications.pasted(project, reversal)
}

/**
 * Inserts [text] at **every** caret, replacing each caret's selection — which is what every other
 * paste in the IDE does, and therefore the only behaviour that would not be a surprise.
 *
 * **Every caret rather than the primary one, and the multi-caret case is the whole reason to say
 * so.** Handling only `primaryCaret` would insert once and leave the other carets' selections
 * standing, which does not read as a restricted feature — it reads as a paste that half worked, on
 * the one item in this plugin that writes into source. There is no reversal-specific argument for
 * treating a second caret differently from the first: the restored text is one string, and each
 * caret is one place the user asked for it.
 *
 * The caret ends after what was inserted so that typing continues where the reply ended, and the
 * selection is dropped because leaving the inserted text selected would make the next keystroke
 * delete it.
 *
 * **One command, so one gesture is one undo step.** The reversal changed no document, so the insert
 * is the entire footprint of the invocation and a single [WriteCommandAction] covers it — including
 * every caret, which is the half that would otherwise decompose into N undo steps. It is stated
 * here rather than left to a bare `replaceString`, because a document write outside a command is
 * undoable in ways the platform does not group.
 *
 * **A `Throwable` arm and no `ProcessCanceledException` arm**, which is the rule this file inherits
 * rather than a gap: `exportMapping` records why the cancellation arm belongs only to code that runs
 * under an indicator or in a read action, and *"the write is a write"* is as true of a write command
 * as it is of a file save.
 */
private fun insertAtEveryCaret(project: Project, editor: Editor, text: String) {
    WriteCommandAction.writeCommandAction(project)
        .withName("De-anonymize Clipboard and Paste")
        .run<Throwable> {
            // Top to bottom, which is safe because carets track the edits in front of them: each
            // replacement shifts the carets below it, and asking one for its offset afterwards gets
            // the moved offset rather than a stale one.
            editor.caretModel.runForEachCaret { caret ->
                val start = if (caret.hasSelection()) caret.selectionStart else caret.offset
                val end = if (caret.hasSelection()) caret.selectionEnd else caret.offset

                editor.document.replaceString(start, end, text)
                caret.removeSelection()
                caret.moveToOffset(start + text.length)
            }
        }
}
