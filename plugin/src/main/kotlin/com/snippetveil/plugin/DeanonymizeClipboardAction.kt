package com.snippetveil.plugin

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.snippetveil.core.Reversal
import com.snippetveil.core.deanonymize
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

/**
 * **De-anonymize Clipboard** — paste the AI's reply, invoke, and the placeholders are your real
 * names again.
 *
 * Clipboard in, clipboard out, with a balloon: **symmetric with `Copy Anonymized`**, and no dialog
 * on the happy path, so the two directions are one shape to learn rather than two.
 *
 * ### Two entry points, one action, and no file-type gate
 *
 * It is in the `SnippetVeil` submenu, which is a single group registered into **both** the Tools
 * menu and the editor popup — so the two routes are the same item rather than two registrations
 * that can drift apart. The Tools entry is the canonical one and the only one that works with no
 * editor open at all, which is where a reply pasted into a commit message or an external editor
 * leaves you.
 *
 * **Ungated, unlike the two anonymizing actions beside it.** This is invoked *after* pasting a
 * reply — into a scratch buffer, a Markdown file, a commit message — so a `.java` gate would make
 * the item wrong on the file the user actually has open. The Java gate is scoped to anonymization
 * for a reason that does not reach here: it exists because *anything SnippetVeil cannot anonymize
 * it does not offer*, and this reads no source at all. A reply is text.
 *
 * That asymmetry is also why the editor entry is a **submenu** rather than a flat item. Flat, an
 * ungated SnippetVeil item would appear in every editor popup in the IDE; under one named submenu
 * it costs a hover and leaves the global context menu the size it was.
 *
 * **It is still clipboard to clipboard from either route.** The editor it is invoked from is not
 * read and not written — the caret, the selection and the file type are all irrelevant to what this
 * does, and the only thing the editor contributes is a place to right-click.
 *
 * ### `DumbAware`, which is also the opposite of the other two
 *
 * `Copy Anonymized` is deliberately not `DumbAware` because resolution is index-dependent and would
 * be quietly wrong during indexing. Nothing here resolves anything: the tables were written when the
 * snippet was sent, and decoding one is a lookup. There is no answer indexing could improve, so
 * there is nothing to grey out.
 *
 * @param clipboard the clipboard, injectable for the reason [CopyAnonymizedAction]'s plan builder is:
 *   *"a reply with nothing of ours in it is left untouched"* and *"a failed write says so"* are the
 *   two guarantees here, and neither is assertable against a system clipboard a test cannot make fail.
 */
class DeanonymizeClipboardAction internal constructor(private val clipboard: Clipboard) : AnAction(), DumbAware {

    /** The constructor the platform uses; `plugin.xml` names this class and nothing else. */
    constructor() : this(SystemClipboard)

    /** Nothing in [update] touches the UI hierarchy; it asks whether there is a project. */
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    /**
     * **A project, and nothing else** — no editor, no file, no file type.
     *
     * The mapping and the sidecar are both project-scoped, so a project is the one thing a reversal
     * genuinely cannot do without. Everything past that is a fact about a reply, and this action has
     * no opinion about where the user pasted it.
     */
    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.project != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        restoreClipboard(event.project ?: return, clipboard)
    }
}

/**
 * The reversal, end to end: read the clipboard, decode it, write it back, say what happened.
 *
 * ### On the EDT, deliberately
 *
 * `Copy Anonymized` goes to a background task because a PSI walk with cross-file resolution over a
 * whole file is a freeze. **None of that is here.** This is two map lookups per word of a reply
 * somebody just pasted, with no PSI, no resolution and no index — and moving it off the EDT would
 * buy a thread hop and cost the thing that makes the action feel like a copy.
 *
 * ### Nothing found is a message, not a silence
 *
 * A reply the tables recognise nothing in is left **untouched**, and the balloon says so. Writing an
 * identical string back would be a no-op the user cannot distinguish from a reversal that worked,
 * which is the one reading this must not allow: *"I ran it, so these are real names"*.
 *
 * ### The failure mode is the mirror of the other one, and it is the safe half
 *
 * When `Copy Anonymized` fails, the clipboard holds whatever was there before and the next paste may
 * be the raw snippet — the sharp edge the whole design is built around. When **this** fails, the
 * clipboard still holds the anonymized reply: unreadable, and nothing has leaked. So the message
 * states the same clipboard fact for the same reason, and the fact it states happens to be reassuring
 * rather than alarming.
 *
 * **The two `catch`es are not one `catch` that happens to be written twice, and the balloon is
 * outside both.** A read that throws and a write that throws are both *the clipboard was not
 * changed*, which is why they report identically — but the balloon says the reversal **happened**,
 * and by the time it runs it has. A `try` wide enough to hold it would answer a failing balloon with
 * *"your clipboard was not changed"*, which would be a lie in the one message that must not contain
 * one. That is the same rule `deliver` follows on the way out.
 */
internal fun restoreClipboard(project: Project, clipboard: Clipboard = SystemClipboard) {
    val reply = try {
        clipboard.read()
    } catch (failure: Throwable) {
        SnippetVeilNotifications.reversalFailed(project, failure)
        return
    }

    val reversal = reversalFor(project, reply.orEmpty())

    if (!reversal.found) {
        SnippetVeilNotifications.nothingToRestore(project)
        return
    }

    try {
        clipboard.write(reversal.text)
    } catch (failure: Throwable) {
        SnippetVeilNotifications.reversalFailed(project, failure)
        return
    }
    SnippetVeilNotifications.deanonymized(project, reversal)
}

/**
 * **What this project's two stores make of [text]** — the one question both reversing actions ask,
 * asked in one place.
 *
 * Extracted when the second caller arrived rather than in advance. The duplicate was three lines
 * and looked harmless, but what it duplicated was *which stores a reversal consults and in what
 * order* — so a third store, or a change to the sidecar's horizon, would have been a two-site edit
 * where missing one site produces two actions that disagree about the same reply. The rest of the
 * two flows stays duplicated on purpose: they differ in what they do with the answer, and that is
 * the part worth reading twice.
 */
internal fun reversalFor(project: Project, text: String): Reversal = deanonymize(
    text,
    PlaceholderSidecar.getInstance(project).window(),
    PlaceholderLedger.getInstance().snapshotOf(project),
)

/**
 * The clipboard, or whatever a test puts in its place.
 *
 * The seam exists for the same reason [Previews] does: *"a reply with nothing of ours in it is left
 * untouched"* and *"a failed write says the clipboard was not changed"* are claims about this
 * action, and neither is assertable against a system clipboard a test cannot make fail.
 */
internal interface Clipboard {

    /** What is on it, or `null` when it holds nothing that is text. */
    fun read(): String?

    fun write(text: String)
}

/**
 * The real one.
 *
 * `getContents` returns `null` for a clipboard holding an image, a file list or nothing at all,
 * which is the same answer as *a reply with none of ours in it* and is reported the same way — there
 * is no failure here to tell apart from an empty one.
 */
internal object SystemClipboard : Clipboard {

    override fun read(): String? =
        CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor)

    override fun write(text: String) =
        CopyPasteManager.getInstance().setContents(StringSelection(text))
}
