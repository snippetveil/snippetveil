package com.snippetveil.plugin

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.snippetveil.core.Reversal
import com.snippetveil.core.Unrestored
import com.snippetveil.core.fidelityNotices

/**
 * Everything SnippetVeil ever says out loud.
 *
 * All of them are balloons in a group of their own, so that a user who does not want them can turn
 * off exactly these and nothing else. Two are about a copy leaving, two are about a reply coming
 * back, one is about a mapping written to a file, and each of the three directions says its own
 * failure — because what a failure leaves behind differs by direction, and that is the half of the
 * message a user acts on.
 *
 * [installed] is the exception to the shape of every other one here: it reports no operation,
 * because there has not been one yet.
 */
internal object SnippetVeilNotifications {

    /** The group id, matching the `notificationGroup` extension in plugin.xml. */
    private const val GROUP = "SnippetVeil"

    private val LOG = Logger.getInstance(SnippetVeilNotifications::class.java)

    /**
     * States what happened, in mechanism, and makes no claim about what it means — **and then, on a
     * line of its own, what the output gives no sign of.**
     *
     * *"14 names replaced · 3 unknown · 22 preserved"* is a count of an operation. There is no "safe
     * to paste", no "sanitized", no adjective at all — a category claim is the one thing a user would
     * act on, and it is the one thing this tool is not in a position to make. Zero gets the same
     * balloon with a zero in it, which is a truthful and useful reading: it is the moment someone
     * discovers the snippet they were worried about contains nothing of theirs. Every number is shown
     * every time for that reason, rather than a number appearing only when it fires.
     *
     * **The fidelity notices follow the opposite rule, and the two rules are not in tension.** A
     * count that appeared only when it fired would make its absence unreadable; a *notice* that
     * fired on every invocation is one nobody reads on the invocation where it matters. So a clean
     * snippet says nothing at all below the counts — see [fidelityNotices].
     *
     * **They are here rather than only in the preview**, because `Copy Anonymized` is the fast path
     * and has no dialog: a disclosure only the preview carried would never fire for the users who
     * never open the preview, and the disclosure rule would collapse for exactly the people it was
     * written for. Both losses are invisible in the output — the text that comes back is clean,
     * compiles and reads as ordinary code — so saying so at the point of use is the only place it
     * can be said.
     *
     * **The comment notice replaces the old strip clause rather than joining it.** *"2 comments
     * stripped, 1 of them commented-out code"* already contains that number, and a balloon that said
     * it twice would read as a bug.
     *
     * **The unknown count is here at information level, and the level is the decision.** Under
     * fail-closed an `Unknown` *was* anonymized — it is a quality risk, never a privacy one — so
     * styling it as a warning would train the user to read our alarm as *"this might have leaked"*,
     * which is precisely the inversion this product cannot afford. It is a displayable measure of
     * how much of the snippet the IDE could not vouch for, and that is all it claims to be.
     */
    fun copied(project: Project, analysis: Analysis) {
        val counts = analysis.result.counts
        val lines = listOf("${counts.replaced} names replaced · ${counts.unknown} unknown · ${counts.preserved} preserved") +
            analysis.result.fidelityNotices()
        group().createNotification(
            "Anonymized snippet copied",
            // A line each, because the counts are one sentence about the operation and each notice is
            // a sentence about the snippet. Run together behind a separator they read as a fourth
            // number, which is the one thing a disclosure must not look like.
            lines.joinToString("<br>"),
            NotificationType.INFORMATION,
        ).addAction(showMapping(project, analysis)).notify(project)
    }

    /**
     * **The way back to the table for the invocation that took the fast path**, which is the one the
     * balloon exists for: `Copy Anonymized` shows no dialog, so this is where the rows are.
     *
     * It reopens the preview [read-only][PreviewDialog.forReview] — the same object re-rendered,
     * not a second code path. Read-only is required rather than a simplification: by the time this
     * is clickable the delta is committed and the text has already left, so offering a reduction
     * here would offer to change something that is gone.
     *
     * The action holds this invocation for as long as the balloon lives, the original snippet
     * included. That is not a new exposure: the file it was cut from is open in the editor beside
     * it, and the mapping it produced is already on disk.
     */
    private fun showMapping(project: Project, analysis: Analysis) =
        NotificationAction.createSimple("Show mapping") { PreviewDialog.forReview(project, analysis).show() }

    /**
     * **The only thing this plugin ever says unprompted**, and it says what to do rather than what
     * it is.
     *
     * *"SnippetVeil is installed"* on its own is an announcement about us; the sentence that follows
     * is the whole point, and it is an instruction short enough to act on from memory a week later —
     * select, right-click, the item's own name. The surface is otherwise invisible by design (see
     * [FirstRunNotice]), so this one balloon is carrying the discoverability of the entire product
     * and cannot afford to spend its second line on a description.
     *
     * **`Open settings` rather than a link to documentation**, because the page it opens is where
     * the two questions a new user actually has get answered — where the mapping is kept, and how to
     * put a keyboard shortcut on the action they were just told about. A documentation link would
     * also be a web page opened by a plugin that promises not to fetch one; the action here reaches
     * nothing outside the IDE.
     */
    fun installed(project: Project) {
        group().createNotification(
            "SnippetVeil is installed",
            "Select Java code, then right-click \u2192 <b>Copy Anonymized</b>.",
            NotificationType.INFORMATION,
        ).addAction(openSettings(project)).notify(project)
    }

    /** Opens the settings page, and expires the balloon: a second click has nothing left to open. */
    private fun openSettings(project: Project) =
        NotificationAction.createSimpleExpiring("Open settings") { SnippetVeilConfigurable.openFor(project) }

    /**
     * **What was saved, and what the thing that was saved *is*** — one line, because the second
     * sentence is not a detail.
     *
     * The file is a reversal key in plaintext, sitting wherever the user pointed the chooser, which
     * is very likely `~/Downloads` — outside the storage location this product chose so carefully to
     * keep the mapping out of git and out of cloud sync. *"Mapping saved"* alone would report a
     * successful write and say nothing about what the user now has; the clause is what makes it a
     * file they think about again.
     *
     * It states what the file does rather than warning about it, for the same reason the copy
     * balloon states a count and makes no claim about safety: *"be careful with this"* is an
     * instruction the tool is in no position to give, and *"it reverses the anonymized snippet"* is
     * the fact the instruction would have been derived from.
     *
     * Nothing is said when the user cancels the chooser. A balloon there would be the tool
     * announcing its own no-op, and the cancel is already visible — the dialog closed.
     */
    fun mappingSaved(project: Project) {
        group().createNotification(
            "Mapping saved. This file reverses the anonymized snippet.",
            NotificationType.INFORMATION,
        ).notify(project)
    }

    /**
     * **The third failure, and the one with nothing at stake on the clipboard.**
     *
     * [failed] and [reversalFailed] both state what the clipboard holds, because the user's next
     * keystroke is a paste either way. Nothing here has touched the clipboard, so the fact worth
     * stating is the one about the export: the mapping did not get saved, and an invocation whose
     * ephemeral half falls past the horizon is not decodable later after all.
     *
     * **It does not claim that no file was written**, which is the sentence that first suggests
     * itself and is not reliably true: a write that fails partway leaves a truncated file behind.
     * A message that must not contain a lie may not say the one thing this code cannot check.
     */
    fun exportFailed(project: Project?, failure: Throwable) = report(
        project,
        failure,
        logged = "SnippetVeil could not write the mapping file.",
        said = "Mapping export failed — the mapping was not saved.",
    )

    /**
     * **The sharp edge of the whole design: when this fires, the clipboard is not empty.**
     *
     * It holds whatever was there before, and the user's next keystroke is a paste into an AI chat
     * they believe contains anonymized code. Silent failure does not degrade to nothing — it
     * degrades to pasting the previous thing, quite possibly the raw snippet they copied normally a
     * minute ago.
     *
     * So the message states the clipboard fact rather than only the failure, and **the second clause
     * is load-bearing**: without it a user reads "failed", shrugs, and pastes. It is the balloon's
     * whole text rather than a subtitle under a title, because the title is the part that gets read
     * and the clipboard fact is not a detail.
     */
    fun failed(project: Project?, failure: Throwable) = report(
        project,
        failure,
        logged = "SnippetVeil could not anonymize the selection; the clipboard was left untouched.",
        said = "Anonymization failed — your clipboard was not changed.",
    )

    /**
     * **What came back, counted the same way the copy was: every number, every time.**
     *
     * *"12 placeholders restored · 3 not restored"* is a count of an operation, and the second number
     * is on it even when it is zero for the reason the first is — a number that appeared only when it
     * fired would make its absence unreadable, and *not restored* is precisely the figure a user
     * would otherwise assume was zero.
     *
     * **`Show details` is the exception, and it follows the notices' rule rather than the counts'.**
     * A number is a fact worth stating at zero; an action offering to list nothing is a dead end
     * dressed as an offer. So it appears only when there is something in the list.
     *
     * The noun agrees with the number, which is not fussiness: *"1 placeholders restored"* reads as a
     * bug in the tool, and a tool selling carefulness cannot afford to look careless in the one
     * sentence it says about a thing it just did to the user's clipboard.
     */
    fun deanonymized(project: Project, reversal: Reversal) {
        val restored = reversal.restored.size
        val balloon = group().createNotification(
            "Clipboard de-anonymized",
            "$restored placeholder${if (restored == 1) "" else "s"} restored · ${reversal.unrestored.size} not restored",
            NotificationType.INFORMATION,
        )
        if (reversal.unrestored.isNotEmpty()) balloon.addAction(showDetails(project, reversal.unrestored))
        balloon.notify(project)
    }

    /**
     * The list of what did not come back, **read-only and per bucket**.
     *
     * Read-only because there is nothing to do to a placeholder that decoded to nothing: the two
     * answers are *the name is gone* and *this was never ours*, and neither is an action. What the
     * list is for is telling those two apart on the rows the user is actually holding.
     */
    private fun showDetails(project: Project, unrestored: List<Unrestored>) =
        NotificationAction.createSimple("Show details") { UnrestoredDialog(project, unrestored).show() }

    /**
     * **A reply with none of ours in it, said out loud rather than left to silence.**
     *
     * The clipboard is deliberately not rewritten — an identical string written back is
     * indistinguishable from a reversal that worked, and *"I ran it, so these must be real names"* is
     * the one reading this product cannot afford. So the message carries the clipboard fact in the
     * title, exactly as [failed] does, and for the same reason: it is what the user's next keystroke
     * depends on.
     */
    fun nothingToRestore(project: Project) {
        group().createNotification(
            "No SnippetVeil placeholders found — clipboard unchanged.",
            NotificationType.INFORMATION,
        ).notify(project)
    }

    /**
     * **The mirror of [failed], and the safe half of it.**
     *
     * When a copy fails the clipboard holds whatever was there before, and the next paste may be the
     * raw snippet — which is why that message is the sharpest sentence in the product. When a
     * reversal fails the clipboard still holds the *anonymized* reply: unreadable, and nothing of
     * the project's has gone anywhere. The clipboard fact is stated anyway, because the user's next
     * keystroke depends on it either way and a message that only stated it when the news was bad
     * would be a message nobody could read at a glance.
     */
    fun reversalFailed(project: Project?, failure: Throwable) = report(
        project,
        failure,
        logged = "SnippetVeil could not de-anonymize the clipboard; it was left untouched.",
        said = "De-anonymization failed — your clipboard was not changed.",
    )

    /**
     * **What both failures do, which is the same thing over a different sentence.**
     *
     * The mechanism is shared and the *words* are not, which is the right way round: each caller's
     * message is argued where the caller is, because what makes those two sentences correct is a
     * different fact about the clipboard in each case. A helper that also chose the words would put
     * the argument somewhere neither caller could be read against.
     *
     * @param logged what goes in the IDE log, where a stack trace is useful
     * @param said the balloon's whole text — a title and no subtitle, because the title is the part
     *   that gets read and the clipboard fact is not a detail
     */
    private fun report(project: Project?, failure: Throwable, logged: String, said: String) {
        LOG.warn(logged, failure)
        group().createNotification(said, NotificationType.ERROR).addAction(REPORT).notify(project)
    }

    private fun group(): NotificationGroup =
        NotificationGroupManager.getInstance().getNotificationGroup(GROUP)

    /**
     * Opens the issue tracker in the user's browser. Not a network call by this plugin — the
     * platform hands a URL to the desktop, and nothing here opens a socket. The trust checks would
     * fail the build if it did.
     */
    private val REPORT = NotificationAction.createSimpleExpiring("Report an issue") {
        BrowserUtil.browse("https://github.com/snippetveil/snippetveil/issues/new")
    }
}
