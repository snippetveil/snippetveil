package com.snippetveil.plugin

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType

/**
 * **What one invocation is over** — a snippet cut from an editor, or an execution plan pasted from a
 * console.
 *
 * It exists because the two invocations differ in **what they are about** and in nothing else. The
 * threading, the fail-closed guarantee, the ledger's commit point, the preview and the balloon are
 * all shared, and every one of them would have grown a second copy if the plan had arrived as a
 * second path. So the differences are collected here, where a reader can see the whole list of them
 * at once: a title, a button, whether the pane highlights anything, whether the comments tick has a
 * population, and which numbers there are to say.
 *
 * **A count with no population is absent rather than zero**, which is the rule that shapes both
 * strips below. A plan resolves nothing, so it has no unresolved names; it holds no comments, so
 * nothing is stripped from one. Printing `0 unknown` beside a plan would be a measurement of
 * something that cannot happen, and a reader has no way to tell that from a measurement that came
 * back zero this time.
 *
 * @param previewTitle the dialog's title while it is the reduction surface
 * @param reviewTitle and once it is the read-only re-open, over an invocation that has already left
 * @param copyButton what the commit button reads. It names what is being copied rather than saying
 *   `OK`, because the button *is* the moment the text leaves.
 * @param fileType what the code pane is opened as. **A plan is plain, for every format**: there is
 *   no honest highlighting for text a database engine printed, and syntax colouring that guessed at
 *   one would decorate the pane with claims about a grammar nothing here parsed.
 * @param offersComments whether the keep-comments tick is offered. **Absent, never greyed out**, for
 *   the invocation that has no comments to keep — a greyed box suggests a state that is reachable.
 */
internal enum class Subject(
    val previewTitle: String,
    val reviewTitle: String,
    val copyButton: String,
    val fileType: FileType,
    val offersComments: Boolean,
) {

    SNIPPET(
        previewTitle = "Anonymize with Preview",
        reviewTitle = "Anonymized Snippet",
        copyButton = "Copy Anonymized",
        fileType = JavaFileType.INSTANCE,
        offersComments = true,
    ),

    PLAN(
        previewTitle = "Anonymize Execution Plan",
        reviewTitle = "Anonymized Execution Plan",
        copyButton = "Copy Anonymized Plan",
        fileType = PlainTextFileType.INSTANCE,
        offersComments = false,
    ),
    ;

    /**
     * **The counts strip under the preview's panes** — see [stripOf], which is where the snippet's
     * own conditional clause is argued.
     */
    fun strip(analysis: Analysis): String {
        val counts = analysis.result.counts
        return when (this) {
            SNIPPET -> {
                val strip = "${counts.replaced} renamed · ${counts.unknown} unknown · ${counts.preserved} preserved"
                if (analysis.plan.selectionExpanded) "$strip · selection expanded to whole tokens" else strip
            }

            PLAN -> "${counts.replaced} renamed · ${counts.preserved} preserved"
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
                "${counts.replaced} names replaced · ${counts.unknown} unknown · ${counts.preserved} preserved"

            PLAN -> "${counts.replaced} names replaced · ${counts.preserved} preserved"
        }
    }
}
