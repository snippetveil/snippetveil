package com.snippetveil.plugin

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.snippetveil.core.NameCounts

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
     * States what happened, in mechanism, and makes no claim about what it means.
     *
     * *"14 names replaced · 22 preserved"* is a count of an operation. There is no "safe to paste",
     * no "sanitized", no adjective at all — a category claim is the one thing a user would act on,
     * and it is the one thing this tool is not in a position to make. Zero replacements gets the
     * same balloon with a zero in it, which is a truthful and useful reading: it is the moment
     * someone discovers the snippet they were worried about contains nothing of theirs.
     */
    fun copied(project: Project, counts: NameCounts) {
        group().createNotification(
            "Anonymized snippet copied",
            "${counts.replaced} names replaced · ${counts.preserved} preserved",
            NotificationType.INFORMATION,
        ).notify(project)
    }

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
