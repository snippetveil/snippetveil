package com.snippetveil.plugin

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.snippetveil.core.fidelityNotices

/**
 * The two things SnippetVeil ever says out loud.
 *
 * Both are balloons in a group of their own, so that a user who does not want them can turn off
 * exactly these and nothing else.
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
    fun failed(project: Project?, failure: Throwable) {
        LOG.warn("SnippetVeil could not anonymize the selection; the clipboard was left untouched.", failure)
        group().createNotification(
            "Anonymization failed — your clipboard was not changed.",
            NotificationType.ERROR,
        ).addAction(REPORT).notify(project)
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
