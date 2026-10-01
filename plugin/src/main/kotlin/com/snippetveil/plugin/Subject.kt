package com.snippetveil.plugin

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType

/**
 * **What one invocation is over** — a snippet cut from an editor, an execution plan pasted from a
 * console, or a stack trace pasted from anywhere.
 *
 * It exists because the invocations differ in **what they are about** and in nothing else. The
 * threading, the fail-closed guarantee, the ledger's commit point, the preview and the balloon are
 * all shared, and every one of them would have grown a second copy if the plan had arrived as a
 * second path. So the differences are collected here, where a reader can see the whole list of them
 * at once: a title, a button, whether the pane highlights anything, whether the comments tick has a
 * population, and which numbers there are to say.
 *
 * **A count with no population is absent rather than zero**, which is the rule that shapes the
 * strips below. A plan resolves nothing, so it has no unresolved names; it holds no comments, so
 * nothing is stripped from one. Printing `0 unknown` beside a plan would be a measurement of
 * something that cannot happen, and a reader has no way to tell that from a measurement that came
 * back zero this time.
 *
 * @param previewTitle the dialog's title while it is the reduction surface
 * @param reviewTitle and once it is the read-only re-open, over an invocation that has already left
 * @param copyButton what the commit button reads. It names what is being copied rather than saying
 *   `OK`, because the button *is* the moment the text leaves.
 * @param balloonTitle what the copy balloon is titled. It names what left, for the reason the button
 *   names what is being copied — *"Anonymized snippet copied"* over a plan would be a false sentence
 *   in the one message that reports the operation.
 * @param fileType what the code pane is opened as. **A plan is plain, for every format**: there is
 *   no honest highlighting for text a database engine printed, and syntax colouring that guessed at
 *   one would decorate the pane with claims about a grammar nothing here parsed.
 * @param offersComments whether the keep-comments tick is offered. **Absent, never greyed out**, for
 *   the invocation that has no comments to keep — a greyed box suggests a state that is reachable.
 * @param notesKotlinCause whether the preview names Kotlin being unavailable as a possible cause of
 *   its `unknown` count. Only for the invocation **the source-file gate never sees**: a snippet from
 *   a `.kt` whose support did not load is refused before it is read, so its preview cannot exist, and
 *   a plan resolves nothing and has no `unknown` to stand beside.
 */
internal enum class Subject(
    val previewTitle: String,
    val reviewTitle: String,
    val copyButton: String,
    val balloonTitle: String,
    val fileType: FileType,
    val offersComments: Boolean,
    val notesKotlinCause: Boolean,
) {

    SNIPPET(
        previewTitle = "Anonymize with Preview",
        reviewTitle = "Anonymized Snippet",
        copyButton = "Copy Anonymized",
        balloonTitle = "Anonymized snippet copied",
        fileType = JavaFileType.INSTANCE,
        offersComments = true,
        notesKotlinCause = false,
    ),

    PLAN(
        previewTitle = "Anonymize Execution Plan",
        reviewTitle = "Anonymized Execution Plan",
        copyButton = "Copy Anonymized Plan",
        balloonTitle = "Anonymized execution plan copied",
        fileType = PlainTextFileType.INSTANCE,
        offersComments = false,
        notesKotlinCause = false,
    ),

    /**
     * **A stack trace off the clipboard.** Plain, because a trace is the JVM's printout rather than
     * source; no comments tick, because a trace holds none — but, unlike a plan, it **resolves**, so
     * it has an `unknown` count and the `Preserve` on an `Unknown` row that comes with one.
     */
    TRACE(
        previewTitle = "Anonymize Stack Trace",
        reviewTitle = "Anonymized Stack Trace",
        copyButton = "Copy Anonymized Trace",
        balloonTitle = "Anonymized stack trace copied",
        fileType = PlainTextFileType.INSTANCE,
        offersComments = false,
        notesKotlinCause = true,
    ),
    ;

    /**
     * **The counts strip under the preview's panes, and the entries that are conditional.**
     *
     * `14 renamed · 3 unknown · 22 preserved` — every number this invocation *has* every time,
     * including the zeroes, because a number that appeared only when it fired would make its absence
     * unreadable. The two kept-comment entries below are the exception, and the reason is theirs: a
     * snippet with no commented-out code has no kept comment to count, and a zero said on every such
     * snippet is noise rather than a measurement. Preserved JDK and third-party symbols are here rather than in the table: their
     * preservation is deliberate and a declared non-goal, so each would be a row the user can do
     * nothing about.
     *
     * **The strip count is not one of them.** It became the comment fidelity notice, which carries
     * the same number and the split that makes it actionable — and a footer that said it twice would
     * read as a bug. The notices sit on their own lines below this one and follow the opposite rule:
     * nothing at all when the loss did not happen.
     *
     * *Selection expanded to whole tokens* is conditional too, and it is conditional
     * in the other direction: always-on it is noise, and conditional it is information. It fires only
     * when snapping actually moved an end of the selection — the copy then contains text the user did
     * not select, and the pane beside this line is where they can see what. It stays a clause of this
     * line rather than a third notice because it is a fact about how the snippet was *cut*, which the
     * pane beside it shows in full; the notices are about what is missing from that pane.
     *
     * **No count is split by the container that read its names, with one exception.** `(n from SQL)`
     * is refused rather than missing: it would appear for a user whose IDE decomposes queries and not
     * for one whose IDE does not, which makes its *absence* the only in-product signal that a query
     * went out whole — the availability tell this product declines to build.
     *
     * **The exception is `unknown`, split by kept comments** — `3 unknown (1 from comments)`.
     * Commented-out code is stale by construction, so its unresolved names would otherwise inflate
     * `unknown` on every paste from a file with commented-out history, and a number that is habitually
     * high stops being read. It is not the availability tell: a kept comment is in the pane for anyone
     * to see, and so is every `Unknown` it brought. The number is not wrong, so it is neither
     * suppressed nor merged — the split is a parenthetical on it, absent when its part is zero. See
     * [unknownOf].
     *
     * **`1 comment anonymized` is a count, shown only when it is not zero.** It is inventory the pane
     * shows — a kept comment is loudly visible — so it is not a notice and is never folded into the
     * stripped-comments one; and always on, `0 comments anonymized` would be noise on every snippet
     * with no commented-out code in it. See [anonymizedCommentsOf].
     *
     * **And an entry with no population is absent rather than zero**, which is the whole of what the
     * plan's strip does differently: a plan resolves nothing and holds no comments, so a zero there
     * would be a measurement of something that cannot happen.
     */
    fun strip(analysis: Analysis): String {
        val counts = analysis.result.counts
        return when (this) {
            SNIPPET -> {
                val strip = "${counts.replaced} renamed · ${unknownOf(analysis)} · ${counts.preserved} preserved" +
                    anonymizedCommentsOf(analysis)
                if (analysis.plan.selectionExpanded) "$strip · selection expanded to whole tokens" else strip
            }

            PLAN -> "${counts.replaced} renamed · ${counts.preserved} preserved"

            TRACE -> "${counts.replaced} renamed · ${unknownOf(analysis)} · ${counts.preserved} preserved"
        }
    }

    /**
     * **The same numbers on the balloon**, worded as the balloon words them: a count of an operation,
     * with no adjective anywhere in it.
     */
    fun balloon(analysis: Analysis): String {
        val counts = analysis.result.counts
        return when (this) {
            SNIPPET ->
                "${counts.replaced} names replaced · ${unknownOf(analysis)} · ${counts.preserved} preserved" +
                    anonymizedCommentsOf(analysis)

            PLAN -> "${counts.replaced} names replaced · ${counts.preserved} preserved"

            TRACE -> "${counts.replaced} names replaced · ${unknownOf(analysis)} · ${counts.preserved} preserved"
        }
    }

    /**
     * `3 unknown`, or `3 unknown (1 from comments)` when kept comments brought some of them — the same
     * words on the strip and on the balloon. A trace holds no comments, so its part is always zero and
     * its parenthetical never appears.
     */
    private fun unknownOf(analysis: Analysis): String {
        val counts = analysis.result.counts
        val fromComments = counts.unknownFromComments.takeIf { it > 0 }?.let { " ($it from comments)" }.orEmpty()
        return "${counts.unknown} unknown$fromComments"
    }

    /** ` · 1 comment anonymized`, or nothing at all when no comment was kept as code. */
    private fun anonymizedCommentsOf(analysis: Analysis): String {
        val kept = analysis.result.comments.anonymized
        return if (kept == 0) "" else " · $kept ${if (kept == 1) "comment" else "comments"} anonymized"
    }
}
