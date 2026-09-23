package com.snippetveil.plugin

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.snippetveil.core.AnonymizationSettings
import com.snippetveil.core.PlanReading
import com.snippetveil.core.PlanRefusedForm
import com.snippetveil.core.parsePlan

/**
 * **Anonymize Execution Plan…** — copy an `EXPLAIN` plan out of a console, invoke, and the relation,
 * column, alias and index names are placeholders while every cost, row count and timing is exactly
 * as it was printed.
 *
 * ### It resolves nothing, so it is `DumbAware`
 *
 * The two anonymizing actions over source are deliberately **not** `DumbAware`: resolution is
 * index-dependent, and during indexing their answer would be quietly wrong rather than unavailable.
 * **There is no PSI anywhere in this one.** Text assigned the slot and text is all there was to
 * decide with — nothing resolves, nothing is looked up, and no index could improve the answer — so
 * there is nothing for the platform to grey out.
 *
 * ### Always enabled, and the clipboard is read on invoke
 *
 * **Enablement is a question about the context, never about the content.** No action in this product
 * reads the clipboard in `update`, and this one does not become the first: a menu item that greyed
 * itself out would be reporting what is on the clipboard to anyone who opened the menu, and it would
 * be doing it on the EDT, on every menu open, over a clipboard another process may own.
 *
 * So the refusal happens on invoke, where nothing has been read and nothing written — which is what
 * makes the clipboard clause in [SnippetVeilNotifications.planUnreadable] true by construction
 * rather than by inspection.
 *
 * ### Preview-first, with no fast path
 *
 * `Copy Anonymized` exists beside `Anonymize with Preview…` because preview-first taxes the common
 * case, and the original is in the editor behind the dialog either way. **None of that reasoning
 * survives here.** The input never passed through an editor, so the dialog is the only view of the
 * output there is before it reaches a chat; what needs vetting — a name a text-only reading did not
 * recognise — is visible only in the pane; and the controls live only there. See
 * [PreviewDialog.forPlan].
 *
 * @param clipboard the clipboard, injectable for the reason [DeanonymizeClipboardAction]'s is: *the
 *   refusal leaves the clipboard byte-identical* is the guarantee this action turns on, and it is not
 *   assertable against a system clipboard a test cannot watch.
 * @param previews the dialog, injectable so that *the preview opens before anything reaches the
 *   clipboard* can be asserted without a modal window.
 */
class AnonymizeExecutionPlanAction internal constructor(
    private val clipboard: Clipboard,
    private val previews: Previews,
) : AnAction(), DumbAware {

    /** The constructor the platform uses; `plugin.xml` names this class and nothing else. */
    constructor() : this(SystemClipboard, PlanPreviewDialogs)

    /** Nothing in [update] touches the UI hierarchy; it asks whether there is a project. */
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    /**
     * **A project, and nothing else** — no editor, no file, no file type, and above all not the
     * clipboard.
     *
     * The mapping and the sidecar are project-scoped, so a project is the one thing this genuinely
     * cannot do without. Everything past that is a fact about a paste, and this action has no
     * opinion about where the user was when they made it.
     */
    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.project != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        anonymizePlanOnClipboard(event.project ?: return, clipboard, previews)
    }
}

/**
 * The invocation, end to end: read the clipboard, analyse it off the EDT, and either refuse or show
 * the preview that copies it.
 *
 * ### Two calls across the boundary, and the plugin inspects nothing in between
 *
 * [parsePlan] hands back a finished plan and [anonymize][com.snippetveil.core.anonymize] takes it —
 * that is the whole of this side's work with it. Nothing here reads a character of the plan's text,
 * counts its occurrences or decides what any of them are: **the engine decides what the text is**,
 * which is the rule that put the parse there rather than here.
 *
 * The one thing this side does with the reading is choose which of three things happens next: the
 * preview, the general refusal, or the refusal that names the shape that arrived. **Which shape that
 * is arrives as an enumeration**, and this side renders it — the same division of labour the verdict
 * itself follows, and the reason the refusal still cannot quote: there is nothing in the verdict to
 * quote.
 *
 * ### Background, cancellable, and fail-closed
 *
 * A pasted plan can be thousands of lines, and the walk is linear over every one of them. That is
 * not a freeze worth risking on the EDT, so it runs in a background task with a cancel button, like
 * the analysis of a whole file — and **a throw anywhere in it leaves the clipboard byte-identical**,
 * because the only write there is sits inside [deliver], past the dialog.
 */
internal fun anonymizePlanOnClipboard(project: Project, clipboard: Clipboard, previews: Previews) {
    // On the EDT, and only here — the one moment this action touches the clipboard before the copy.
    // A clipboard another process owns throws, and that is reported as the failure it is: nothing
    // was read, so nothing was changed.
    val pasted = try {
        clipboard.read().orEmpty()
    } catch (failure: Throwable) {
        SnippetVeilNotifications.failed(project, failure)
        return
    }

    object : Task.Backgroundable(project, "Anonymizing execution plan…", true) {
        override fun run(indicator: ProgressIndicator) {
            val analysis = try {
                analysePlan(project, pasted)
            } catch (cancelled: ProcessCanceledException) {
                // The user pressed the cancel button, which is not a failure and is not reported.
                throw cancelled
            } catch (failure: Throwable) {
                SnippetVeilNotifications.failed(project, failure)
                return
            }

            ApplicationManager.getApplication().invokeLater(
                {
                    when (analysis) {
                        is PlanAnalysis.Read ->
                            previews.confirm(project, analysis.analysis)?.let { deliver(project, it, Subject.PLAN) }

                        PlanAnalysis.Unreadable -> SnippetVeilNotifications.planUnreadable(project)
                        is PlanAnalysis.Refused -> SnippetVeilNotifications.planRefused(project, analysis.form)
                    }
                },
                ModalityState.defaultModalityState(),
                project.disposed,
            )
        }
    }.queue()
}

/**
 * **What the background thread carries back to the EDT** — the finished analysis, or which of the
 * two refusals the engine returned.
 *
 * It mirrors `PlanReading` rather than collapsing it, and it carries no `String` for the same reason
 * that type does not: there is nowhere for a line of the user's plan to travel from. What this side
 * adds is only that the `Read` arm has already been through the engine.
 */
private sealed class PlanAnalysis {

    class Read(val analysis: Analysis) : PlanAnalysis()

    /** The text is not a plan this engine reads, and there is nothing to suggest instead. */
    object Unreadable : PlanAnalysis()

    /** A shape the engine recognises and cannot read soundly, named so the refusal can say which. */
    class Refused(val form: PlanRefusedForm) : PlanAnalysis()
}

/**
 * The parse and the engine, both on the background thread — and the verdict where the text is not a
 * plan this product reads.
 *
 * **The defaults, and not one setting more.** Every reduction the design authorises is
 * per-invocation and lives in the preview, which is the same rule the source actions follow. The
 * internal-library prefixes are not read here at all, and that is not an omission: they are a policy
 * over *packages*, and there is no package anywhere in a plan for one to claim.
 *
 * **The ledger goes in as a snapshot and comes back as a delta nothing has applied**, exactly as it
 * does for a snippet — so a refused parse, a cancelled preview or a throw burns no number. Nothing a
 * plan names is ever written into it: a plan key is unqualified by construction, so this invocation
 * draws numbers, burns them, and leaves the mapping as it was.
 */
private fun analysePlan(project: Project, pasted: String): PlanAnalysis = when (val reading = parsePlan(pasted)) {
    PlanReading.Unreadable -> PlanAnalysis.Unreadable

    is PlanReading.Refused -> PlanAnalysis.Refused(reading.form)

    is PlanReading.Read -> PlanAnalysis.Read(
        Analysis.of(
            reading.plan,
            AnonymizationSettings.DEFAULTS,
            PlaceholderLedger.getInstance().snapshotOf(project),
        ),
    )
}
