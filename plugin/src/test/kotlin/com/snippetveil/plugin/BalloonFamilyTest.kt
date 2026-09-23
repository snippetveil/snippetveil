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
            familyBreaches(Balloon.of(row.function, notifications.single(), row.footprint))
        }

        assertEquals(emptyList<String>(), breaches)
    }

    /**
     * **A balloon added to the object without a row fails here**, so the rule above covers what the
     * object can raise and not only the balloons that existed when the roster was written.
     *
     * The check matches function names, so it cannot see a new arm inside a function that already has
     * a row. The one arm that is chosen by an enum is checked below, so a new [Unavailable] cause fails
     * here too.
     */
    fun `test the roster raises every balloon the object declares`() {
        val declared = SnippetVeilNotifications::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
            .map { it.name }
            .toSortedSet()

        assertEquals(declared, roster().map { it.function }.toSortedSet())
    }

    fun `test the roster raises the Kotlin-unavailable balloon for every cause`() {
        val raisedFor = roster().filter { it.function == "kotlinUnavailable" }.map { row ->
            dropEarlierBalloons()
            row.raise()
            notifications.single().actions.single().templatePresentation.text
        }

        assertEquals(listOf("Open Plugins", "Open Kotlin settings"), raisedFor)
        assertEquals(Unavailable.entries.size, raisedFor.size)
    }

    fun `test the rule is red on a message that did not write the clipboard and does not say so`() {
        val silent = fixture("Paste failed.", NotificationType.ERROR, listOf(REPORT_LINK), Footprint.CLIPBOARD_NOT_WRITTEN)

        assertEquals(listOf("fixture: the clipboard was not written, and the message does not say so"), familyBreaches(silent))
    }

    /** The replacement clause is held to the same rule as the clipboard clause it replaces. */
    fun `test the rule is red on a failed export that does not say the mapping was not saved`() {
        val silent = fixture("Mapping export failed.", NotificationType.ERROR, listOf(REPORT_LINK), Footprint.MAPPING_NOT_SAVED)

        assertEquals(listOf("fixture: the mapping was not saved, and the message does not say so"), familyBreaches(silent))
    }

    fun `test the rule is red on an error-level message with no report link`() {
        val unreportable = fixture(
            "Paste failed — your clipboard was not changed.",
            NotificationType.ERROR,
            emptyList(),
            Footprint.CLIPBOARD_NOT_WRITTEN,
        )

        assertEquals(listOf("fixture: an ERROR has no report link"), familyBreaches(unreportable))
    }

    fun `test the rule is red on a warning-level message that carries a report link`() {
        val misfiled = fixture(
            "Kotlin support is not available — your clipboard was not changed.",
            NotificationType.WARNING,
            listOf(REPORT_LINK),
            Footprint.CLIPBOARD_NOT_WRITTEN,
        )

        assertEquals(listOf("fixture: a WARNING carries a report link"), familyBreaches(misfiled))
    }

    private fun fixture(text: String, type: NotificationType, links: List<String>, footprint: Footprint) =
        Balloon("fixture", text, type, links, footprint)

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
        val partial = Reversal(
            "settle local9",
            restored = listOf("method1"),
            unrestored = listOf(Unrestored("local9", UnrestoredReason.EVICTED)),
        )
        val notify = SnippetVeilNotifications
        return listOf(
            Row("copied", Footprint.WROTE_CLIPBOARD) { notify.copied(project, analysisOf(REVERSAL_SNIPPET)) },
            Row("copied", Footprint.WROTE_CLIPBOARD) {
                notify.copied(project, analysisOf(REVERSAL_SNIPPET), Subject.PLAN)
            },
            Row("installed", Footprint.NO_OPERATION) { notify.installed(project) },
            Row("mappingSaved", Footprint.SAVED_MAPPING) { notify.mappingSaved(project) },
            Row("exportFailed", Footprint.MAPPING_NOT_SAVED) { notify.exportFailed(project, failure) },
            Row("failed", Footprint.CLIPBOARD_NOT_WRITTEN) { notify.failed(project, failure) },
            Row("deanonymized", Footprint.WROTE_CLIPBOARD) { notify.deanonymized(project, restored) },
            Row("deanonymized", Footprint.WROTE_CLIPBOARD) { notify.deanonymized(project, partial) },
            Row("nothingToRestore", Footprint.CLIPBOARD_NOT_WRITTEN) { notify.nothingToRestore(project) },
            Row("pasted", Footprint.CLIPBOARD_NOT_WRITTEN) { notify.pasted(project, restored) },
            Row("nothingToPaste", Footprint.NOTHING_PASTED) { notify.nothingToPaste(project) },
            Row("pasteRefused", Footprint.CLIPBOARD_NOT_WRITTEN) { notify.pasteRefused(project, partial) },
            Row("pasteFailed", Footprint.CLIPBOARD_NOT_WRITTEN) { notify.pasteFailed(project, failure) },
            Row("insertFailed", Footprint.CLIPBOARD_NOT_WRITTEN) { notify.insertFailed(project, failure) },
            Row("kotlinUnavailable", Footprint.CLIPBOARD_NOT_WRITTEN) {
                notify.kotlinUnavailable(project, Unavailable.PLUGIN_NOT_RUNNING)
            },
            Row("kotlinUnavailable", Footprint.CLIPBOARD_NOT_WRITTEN) {
                notify.kotlinUnavailable(project, Unavailable.PATH_NOT_ACTIVATED)
            },
            Row("reversalFailed", Footprint.CLIPBOARD_NOT_WRITTEN) { notify.reversalFailed(project, failure) },

            // The refusal of a paste that is not a plan: nothing was read and nothing written, so it
            // carries the clipboard clause — and it is a warning, so the rule holds it to having no
            // report link, which is where *a plugin refusing input correctly is not a defect* is
            // actually checked.
            Row("planUnreadable", Footprint.CLIPBOARD_NOT_WRITTEN) { notify.planUnreadable(project) },
        )
    }

    /** One balloon to raise: the [function] on [SnippetVeilNotifications] that raises it, and its footprint. */
    private class Row(val function: String, val footprint: Footprint, val raise: () -> Unit)
}

/**
 * What the invocation behind a balloon wrote. That decides the statement the balloon must carry
 * ([requiredClause]), and the sentence a breach of it is reported in ([breach]).
 */
private enum class Footprint(val requiredClause: Regex?, val breach: String?) {
    /** The copy and the clipboard reversal: the clipboard was written, so there is nothing to disclaim. */
    WROTE_CLIPBOARD(null, null),

    /** An invocation that could have written the clipboard and did not. The user's next paste depends on it. */
    CLIPBOARD_NOT_WRITTEN(
        Regex("""clipboard (was not changed|unchanged|still holds the anonymized reply)""", RegexOption.IGNORE_CASE),
        "the clipboard was not written, and the message does not say so",
    ),

    /**
     * **The one balloon from a clipboard action that is held to a statement other than the clipboard
     * clause.** It is named here rather than hidden.
     *
     * `De-anonymize Clipboard and Paste`, refusing a reply with nothing of ours in it, does not write
     * the clipboard, and its balloon says *nothing was pasted* without mentioning the clipboard. Its
     * wording is out of scope for the change that introduced this rule, so it is held to its document
     * surface's statement instead, and the exception is recorded here where the rule is.
     */
    NOTHING_PASTED(
        Regex("""nothing was pasted""", RegexOption.IGNORE_CASE),
        "nothing was written into the document, and the message does not say so",
    ),

    /** The export, which has no clipboard to leave. It succeeded, so nothing was left undone. */
    SAVED_MAPPING(null, null),

    /** The export, failed. The true statement for its surface replaces the clipboard clause. */
    MAPPING_NOT_SAVED(
        Regex("""the mapping was not saved""", RegexOption.IGNORE_CASE),
        "the mapping was not saved, and the message does not say so",
    ),

    /** The first-run notice, which reports no operation. */
    NO_OPERATION(null, null),
}

/** A balloon as the rule reads it: its whole text, its level, its links and its footprint. */
private data class Balloon(
    val raisedBy: String,
    val text: String,
    val type: NotificationType,
    val links: List<String>,
    val footprint: Footprint,
) {
    companion object {
        fun of(raisedBy: String, notification: Notification, footprint: Footprint) = Balloon(
            raisedBy,
            listOf(notification.title, notification.content).filter { it.isNotEmpty() }.joinToString(" "),
            notification.type,
            notification.actions.map { it.templatePresentation.text },
            footprint,
        )
    }
}

private const val REPORT_LINK = "Report an issue"

/** Every way [balloon] leaves the family, as sentences naming who raised it; empty when it obeys the rule. */
private fun familyBreaches(balloon: Balloon): List<String> = buildList {
    val clause = balloon.footprint.requiredClause
    if (clause != null && !clause.containsMatchIn(balloon.text)) add("${balloon.raisedBy}: ${balloon.footprint.breach}")

    val reportable = REPORT_LINK in balloon.links
    if (balloon.type == NotificationType.ERROR && !reportable) add("${balloon.raisedBy}: an ERROR has no report link")
    if (balloon.type != NotificationType.ERROR && reportable) add("${balloon.raisedBy}: a ${balloon.type} carries a report link")
}
