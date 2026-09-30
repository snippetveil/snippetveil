package com.snippetveil.plugin

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.snippetveil.core.AnonymizationSettings
import com.snippetveil.core.StackTrace
import com.snippetveil.core.TraceReading
import com.snippetveil.core.parseTrace
import java.util.concurrent.Callable

/**
 * **Anonymize Stack Trace…** — copy a Java exception trace, invoke, and every name in it that belongs
 * to the project is the placeholder the user's snippets already carry: a frame reads
 * `Type1.method1`, which is the point. The trace and the code it came from can be sent side by side
 * and still line up.
 *
 * ### Not `DumbAware`, unlike the plan action
 *
 * It resolves every class name through the index, and during indexing that answer would be quietly
 * wrong rather than unavailable — the reason the two actions over source are not `DumbAware` either.
 * While indexing runs, the platform greys this item out with its own tooltip.
 *
 * ### Always enabled, and the clipboard is read on invoke
 *
 * **Enablement is a question about the context, never about the content**, exactly as for
 * [AnonymizeExecutionPlanAction]: an item that greyed itself out over a clipboard that is not a trace
 * would be reporting what is on the clipboard to anybody who opened the menu. So it reads the
 * clipboard once, on the EDT, when invoked — and refuses there, where nothing has been written.
 *
 * ### Preview-first, with no fast path
 *
 * The trace never passed through an editor, so the dialog is the only view of the output there is
 * before it reaches a chat, and the per-item *Preserve* on an `Unknown` row lives only there. See
 * [PreviewDialog.forTrace].
 *
 * @param clipboard the clipboard, injectable because *the refusal leaves the clipboard byte-identical*
 *   is not assertable against a system clipboard a test cannot watch
 * @param previews the dialog, injectable so that *the preview opens before anything reaches the
 *   clipboard* can be asserted without a modal window
 */
class AnonymizeStackTraceAction internal constructor(
    private val clipboard: Clipboard,
    private val previews: Previews,
) : AnAction() {

    /** The constructor the platform uses; `plugin.xml` names this class and nothing else. */
    constructor() : this(SystemClipboard, TracePreviewDialogs)

    /** Nothing in [update] touches the UI hierarchy; it asks whether there is a project. */
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    /** **A project, and nothing else** — above all not the clipboard. */
    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.project != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        anonymizeTraceOnClipboard(event.project ?: return, clipboard, previews)
    }
}

/**
 * The invocation, end to end: read the clipboard, parse it and resolve it off the EDT, and either
 * refuse or show the preview that copies it.
 *
 * **Two calls across the boundary.** [parseTrace] decides whether the text is a trace and where its
 * names are; [TracePlanBuilder] resolves those names into evidence; `anonymize` renders. Nothing here
 * reads a character of the trace itself.
 *
 * **Fail closed.** A throw anywhere leaves the clipboard byte-identical, because the only write there
 * is sits inside [deliver], past the dialog — and a cancelled dialog reaches neither the clipboard nor
 * the ledger.
 */
internal fun anonymizeTraceOnClipboard(project: Project, clipboard: Clipboard, previews: Previews) {
    // On the EDT, and only here — the one moment this action touches the clipboard before the copy.
    val pasted = try {
        clipboard.read().orEmpty()
    } catch (failure: Throwable) {
        SnippetVeilNotifications.failed(project, failure)
        return
    }

    object : Task.Backgroundable(project, "Anonymizing stack trace…", true) {
        override fun run(indicator: ProgressIndicator) {
            val analysis = try {
                when (val reading = parseTrace(pasted)) {
                    TraceReading.NotATrace -> null
                    // In smart mode, because resolution is index-dependent; cancellable and
                    // restartable like the snippet's analysis, and for the same reasons.
                    is TraceReading.Read -> ReadAction.nonBlocking(Callable { analyseTrace(project, reading.trace) })
                        .inSmartMode(project)
                        .expireWith(project)
                        .wrapProgress(indicator)
                        .executeSynchronously()
                }
            } catch (cancelled: ProcessCanceledException) {
                throw cancelled
            } catch (failure: Throwable) {
                SnippetVeilNotifications.failed(project, failure)
                return
            }

            ApplicationManager.getApplication().invokeLater(
                {
                    if (analysis == null) {
                        SnippetVeilNotifications.traceUnreadable(project)
                    } else {
                        previews.confirm(project, analysis)?.let { deliver(project, it, Subject.TRACE) }
                    }
                },
                ModalityState.defaultModalityState(),
                project.disposed,
            )
        }
    }.queue()
}

/**
 * The resolution and the engine, on the background thread.
 *
 * **The settings a snippet gets**: the defaults, plus the internal-library prefixes — the one
 * persistent setting, which can only anonymize more, and which is a policy over packages that a trace
 * has plenty of. Every reduction is per-invocation and lives in the preview.
 *
 * **The ledger goes in as a snapshot and comes back as a delta nothing has applied**, so a refused
 * parse, a cancelled preview or a throw burns no number.
 */
private fun analyseTrace(project: Project, trace: StackTrace): Analysis {
    val settings = AnonymizationSettings(internalLibraries = InternalLibrarySettings.of(project).policy)
    val ledger = PlaceholderLedger.getInstance().snapshotOf(project)
    return Analysis.of(TracePlanBuilder.build(project, trace), settings, ledger)
}
