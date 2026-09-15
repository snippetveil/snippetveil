package com.snippetveil.plugin

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.snippetveil.core.Reversal
import com.snippetveil.core.Unrestored
import com.snippetveil.core.UnrestoredReason
import java.lang.reflect.Modifier

/**
 * **The rule every balloon already follows, checked in one place.**
 *
 * The balloons that report something other than success were written one at a time, each with its
 * own reasoning. The rule they share was kept only by discipline and review:
 *
 * - **The clipboard clause is required wherever the clipboard was not written.** On a surface where
 *   it cannot honestly be written, the true statement for that surface replaces it, and it is never
 *   dropped.
 * - **An error has a report link, and nothing else does.** The level says whose fault it is, and the
 *   link says what to do next. An error means SnippetVeil broke, so a report is the next step. A
 *   warning describes a plugin working correctly, and a report link there asks for bug reports about
 *   a product that works.
 *
 * [familyBreaches] is the rule. The roster raises every balloon [SnippetVeilNotifications] declares,
 * so a balloon added later cannot leave the family unnoticed. The negative fixtures show the rule
 * failing, because a check that is never seen failing can decay into one that always passes.
 */
class BalloonFamilyTest : JavaSnippetTestCase() {

    fun `test every balloon this object can raise obeys the family rule`() {
        val breaches = roster().flatMap { row ->
            dropEarlierBalloons()
            row.raise()
            familyBreaches(Raised.of(row.name, notifications.single(), row.footprint))
        }

        assertEquals(emptyList<String>(), breaches)
    }

    /**
     * **A balloon added to the object without a row fails here**, so the rule above covers what the
     * object can raise and not only the balloons that existed when the roster was written.
     */
    fun `test the roster raises every balloon the object declares`() {
        val declared = SnippetVeilNotifications::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
            .map { it.name }
            .toSortedSet()

        assertEquals(declared, roster().map { it.name }.toSortedSet())
    }

    fun `test the rule is red on a message that did not write the clipboard and does not say so`() {
        val silent = Raised("fixture", "Paste failed.", NotificationType.ERROR, listOf(REPORT_LINK), Footprint.CLIPBOARD_NOT_WRITTEN)

        assertEquals(listOf("fixture: the clipboard was not written, and the message does not say so"), familyBreaches(silent))
    }

    fun `test the rule is red on an error-level message with no report link`() {
        val unreportable = Raised("fixture", "Paste failed — your clipboard was not changed.", NotificationType.ERROR, emptyList(), Footprint.CLIPBOARD_NOT_WRITTEN)

        assertEquals(listOf("fixture: an error has no report link"), familyBreaches(unreportable))
    }

    fun `test the rule is red on a warning-level message that carries a report link`() {
        val misfiled = Raised("fixture", "Kotlin support is not available — your clipboard was not changed.", NotificationType.WARNING, listOf(REPORT_LINK), Footprint.CLIPBOARD_NOT_WRITTEN)

        assertEquals(listOf("fixture: a WARNING carries a report link"), familyBreaches(misfiled))
    }

    /**
     * Every balloon, raised directly, with what its invocation wrote.
     *
     * The footprint is stated per row rather than observed, because several of these arms can be
     * reached only by a failure that the action tests already drive one at a time. A function with two
     * shapes of balloon has a row for each.
     */
    private fun roster(): List<Row> {
        val failure = IllegalStateException("fixture failure")
        val restored = Reversal("settle", restored = listOf("method1"), unrestored = emptyList())
        val partial = Reversal("settle local9", restored = listOf("method1"), unrestored = listOf(Unrestored("local9", UnrestoredReason.EVICTED)))
        return listOf(
            Row("copied", Footprint.WROTE_CLIPBOARD) { SnippetVeilNotifications.copied(project, analysisOf(REVERSAL_SNIPPET)) },
            Row("installed", Footprint.NO_OPERATION) { SnippetVeilNotifications.installed(project) },
            Row("mappingSaved", Footprint.SAVED_MAPPING) { SnippetVeilNotifications.mappingSaved(project) },
            Row("exportFailed", Footprint.MAPPING_NOT_SAVED) { SnippetVeilNotifications.exportFailed(project, failure) },
            Row("failed", Footprint.CLIPBOARD_NOT_WRITTEN) { SnippetVeilNotifications.failed(project, failure) },
            Row("deanonymized", Footprint.WROTE_CLIPBOARD) { SnippetVeilNotifications.deanonymized(project, restored) },
            Row("deanonymized", Footprint.WROTE_CLIPBOARD) { SnippetVeilNotifications.deanonymized(project, partial) },
            Row("nothingToRestore", Footprint.CLIPBOARD_NOT_WRITTEN) { SnippetVeilNotifications.nothingToRestore(project) },
            Row("pasted", Footprint.CLIPBOARD_NOT_WRITTEN) { SnippetVeilNotifications.pasted(project, restored) },
            Row("nothingToPaste", Footprint.NOTHING_PASTED) { SnippetVeilNotifications.nothingToPaste(project) },
            Row("pasteRefused", Footprint.CLIPBOARD_NOT_WRITTEN) { SnippetVeilNotifications.pasteRefused(project, partial) },
            Row("pasteFailed", Footprint.CLIPBOARD_NOT_WRITTEN) { SnippetVeilNotifications.pasteFailed(project, failure) },
            Row("insertFailed", Footprint.CLIPBOARD_NOT_WRITTEN) { SnippetVeilNotifications.insertFailed(project, failure) },
            Row("kotlinUnavailable", Footprint.CLIPBOARD_NOT_WRITTEN) { SnippetVeilNotifications.kotlinUnavailable(project, Unavailable.PLUGIN_NOT_RUNNING) },
            Row("kotlinUnavailable", Footprint.CLIPBOARD_NOT_WRITTEN) { SnippetVeilNotifications.kotlinUnavailable(project, Unavailable.PATH_NOT_ACTIVATED) },
            Row("reversalFailed", Footprint.CLIPBOARD_NOT_WRITTEN) { SnippetVeilNotifications.reversalFailed(project, failure) },
        )
    }

    private class Row(val name: String, val footprint: Footprint, val raise: () -> Unit)
}

/** What the invocation behind a balloon wrote, which decides the clause the balloon owes. */
internal enum class Footprint(val owes: Regex?) {
    /** The copy and the clipboard reversal: the clipboard was written, so there is nothing to disclaim. */
    WROTE_CLIPBOARD(null),

    /** An invocation that could have written the clipboard and did not. The user's next paste depends on it. */
    CLIPBOARD_NOT_WRITTEN(Regex("""clipboard (was not changed|unchanged|still holds the anonymized reply)""", RegexOption.IGNORE_CASE)),

    /**
     * **The one known exception to the clipboard clause, and it is named rather than hidden.**
     *
     * `De-anonymize Clipboard and Paste` refusing a reply with nothing of ours in it never writes the
     * clipboard, and its balloon says *nothing was pasted* without saying the clipboard was left alone.
     * By the rule as #118 states it, that balloon owes the clipboard clause. #118 also forbids changing
     * any wording except the write-throw balloon's, and it asserts that every balloon already obeys the
     * rule, so the ticket contradicts itself here. The balloon is held to its document surface's
     * statement until that is settled, and the exception is recorded where the rule is.
     */
    NOTHING_PASTED(Regex("""nothing was pasted""", RegexOption.IGNORE_CASE)),

    /** The export, which has no clipboard to leave. It succeeded, so nothing was left undone. */
    SAVED_MAPPING(null),

    /** The export, failed. The true statement for its surface replaces the clipboard clause. */
    MAPPING_NOT_SAVED(Regex("""the mapping was not saved""", RegexOption.IGNORE_CASE)),

    /** The first-run notice, which reports no operation. */
    NO_OPERATION(null),
}

/** A balloon as the rule reads it: its whole text, its level, its links and the footprint behind it. */
internal data class Raised(
    val raisedBy: String,
    val text: String,
    val type: NotificationType,
    val links: List<String>,
    val footprint: Footprint,
) {
    companion object {
        fun of(raisedBy: String, balloon: Notification, footprint: Footprint) = Raised(
            raisedBy,
            listOf(balloon.title, balloon.content).filter { it.isNotEmpty() }.joinToString(" "),
            balloon.type,
            balloon.actions.map { it.templatePresentation.text },
            footprint,
        )
    }
}

internal const val REPORT_LINK = "Report an issue"

/** Every way [balloon] leaves the family, as sentences naming the balloon; empty when it obeys the rule. */
internal fun familyBreaches(balloon: Raised): List<String> = buildList {
    val owes = balloon.footprint.owes
    if (owes != null && !owes.containsMatchIn(balloon.text)) {
        add(
            when (balloon.footprint) {
                Footprint.MAPPING_NOT_SAVED -> "${balloon.raisedBy}: the mapping was not saved, and the message does not say so"
                else -> "${balloon.raisedBy}: the clipboard was not written, and the message does not say so"
            },
        )
    }
    val reportable = REPORT_LINK in balloon.links
    if (balloon.type == NotificationType.ERROR && !reportable) add("${balloon.raisedBy}: an error has no report link")
    if (balloon.type != NotificationType.ERROR && reportable) add("${balloon.raisedBy}: a ${balloon.type} carries a report link")
}
