package com.snippetveil.plugin

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.snippetveil.core.AnonymizationResult
import com.snippetveil.core.AnonymizationSettings
import com.snippetveil.core.LedgerSnapshot
import com.snippetveil.core.SnippetPlan
import com.snippetveil.core.anonymize
import com.snippetveil.core.isStill
import java.awt.datatransfer.StringSelection
import java.util.concurrent.Callable

/**
 * **Copy Anonymized** — select Java code, invoke, and the anonymized text is on the clipboard.
 *
 * ### Threading
 *
 * ```
 * EDT   capture the file and the selected ranges, commitAllDocuments()
 *  ↓
 * BGT   a cancellable background task, then
 *       ReadAction.nonBlocking { JavaPlanBuilder.build() }.inSmartMode(project).expireWith(project)
 *  ↓       -> SnippetPlan, then the pure anonymize() over it
 * EDT   clipboard, then the balloon
 * ```
 *
 * The whole-file case is what rules out doing this on the EDT: a synchronous walk over a
 * 5,000-line file with cross-file resolution is a freeze, and platform freeze reports go to
 * JetBrains with this plugin's name on them.
 *
 * A concurrent write action cancels and restarts the plan build, and **that restart is free by
 * construction** — the walk allocates nothing, mutates nothing and touches no ledger, so there is
 * no half-done state to unwind.
 *
 * ### Not `DumbAware`, deliberately
 *
 * Resolution is index-dependent, and `PsiAugmentProvider`s may be skipped in dumb mode — so during
 * indexing the answer would be quietly wrong rather than unavailable. Not implementing `DumbAware`
 * hands that to the platform, which greys the item out and explains itself with its own tooltip.
 *
 * @param plans the analysis, injectable for exactly one reason: the fail-closed guarantee can only
 *   be tested by making the analysis throw.
 */
class CopyAnonymizedAction internal constructor(private val plans: PlanBuilder) : AnAction() {

    /** The constructor the platform uses; `plugin.xml` names this class and nothing else. */
    constructor() : this(JavaPlanBuilder)

    /**
     * Nothing in [update] touches the UI hierarchy, and the checks it does make — a PSI file's type
     * — are the kind that should never run on the EDT.
     */
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    /**
     * **Anonymization is unavailable outside Java files: no menu item, and the clipboard is never
     * touched.**
     *
     * This closes a fail-open that no semantic rule owns. A user who has internalised *"SnippetVeil
     * protects me"* will eventually select `application.properties` and reach for it. The principle
     * is *anything it cannot anonymize, it does not offer* — which teaches the boundary at the
     * moment it matters, and asks nobody to read a warning.
     *
     * Rejected: visible but refusing with a message, because a dead menu item that explains itself
     * is still a dead menu item; and visible and copying verbatim, because a privacy tool silently
     * returning an un-anonymized config file is the worst outcome on the table.
     */
    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible =
            event.project != null &&
            event.getData(CommonDataKeys.EDITOR) != null &&
            event.getData(CommonDataKeys.PSI_FILE).isAnonymizable()
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return
        val file = event.getData(CommonDataKeys.PSI_FILE)?.takeIf { it.isAnonymizable() } ?: return

        // On the EDT, and only here: read the editor's state, then make the PSI agree with the
        // document that state was read from. Everything after this point works from plain offsets,
        // so a caret moving under the analysis cannot be observed by it.
        val selections = selectedRangesOf(editor)
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        anonymizeAndCopy(SnippetRequest(project, file, selections))
    }

    /**
     * **Fail closed.** A throw anywhere in the analysis leaves the clipboard byte-identical and the
     * ledger uncommitted; the clipboard write is the single point at which this invocation has
     * happened at all.
     *
     * Fail-open is disqualified outright, and so is the tempting middle: degrading to a
     * conservative all-identifiers mode manufactures a broken snippet the user may paste anyway,
     * and hides the defect from the one channel that would have reported it.
     */
    private fun anonymizeAndCopy(request: SnippetRequest) {
        val project = request.project

        // A background task rather than a bare pooled submission, so that a long analysis — the
        // whole-file case over a large file — shows up in the status bar with a cancel button
        // instead of appearing to do nothing. **Never a modal block:** the editor stays live
        // throughout, which is the half of this that is not negotiable.
        object : Task.Backgroundable(project, "Anonymizing snippet\u2026", true) {
            override fun run(indicator: ProgressIndicator) {
                val result = try {
                    // Synchronously *on this task's thread*, which is not the EDT — so the read
                    // action gets the platform's own cancellation semantics without a second thread
                    // to hand the result between. `wrapProgress` is what wires the cancel button to
                    // it; `inSmartMode` waits out indexing; `expireWith` drops it if the project
                    // closes underneath.
                    ReadAction.nonBlocking(Callable { analyse(request) })
                        .inSmartMode(project)
                        .expireWith(project)
                        .wrapProgress(indicator)
                        .executeSynchronously()
                } catch (cancelled: ProcessCanceledException) {
                    // Not failure: a concurrent write action restarts the read action, and the
                    // cancel button is the user saying so on purpose. Saying anything here would
                    // report a routine keystroke as an error.
                    throw cancelled
                } catch (failure: Throwable) {
                    SnippetVeilNotifications.failed(project, failure)
                    return
                }

                ApplicationManager.getApplication().invokeLater(
                    { deliver(project, result) },
                    ModalityState.defaultModalityState(),
                    project.disposed,
                )
            }
        }.queue()
    }

    /**
     * The plan build and the engine, both on the background thread — the engine is pure, so there is
     * nothing to gain by moving it and a freeze to lose.
     *
     * **The ledger goes in as a snapshot and comes back as a delta that nothing has applied yet.**
     * Read here, on the background thread, and committed at exactly one moment — [deliver], when the
     * snippet reaches the clipboard. Nothing allocates during rendering, so an analysis that is
     * cancelled or that throws burns no number and leaves the mapping exactly as it was.
     *
     * **The defaults, plus the one setting that can only anonymize more.** Every reduction the design
     * authorises — the per-item preserve on an unresolved name, keeping comments — is per-invocation
     * and lives only in the preview dialog, which is its own ticket. That is what makes
     * `Copy Anonymized` the maximally-anonymizing path **by construction** rather than by discipline:
     * there is no reduction for it to read, so there is nothing that can have been left on.
     *
     * [InternalLibrarySettings] is the exception that proves it. It is read here because it is the
     * one persistent setting in the product, and it may be read here because nothing in it can take
     * the output back past what a library-preserving spine rule already produced.
     */
    private fun analyse(request: SnippetRequest): Analysis {
        val settings = AnonymizationSettings(
            internalLibraries = InternalLibrarySettings.of(request.project).policy,
        )

        // The ledger is read **before** the plan is built rather than after, so that the sequence is
        // the one [deliver] re-checks: take the ledger, do the work, confirm nothing moved. Reading
        // it afterwards would make the snapshot fresher and change nothing else — the re-check
        // covers whatever window there is.
        val ledger = PlaceholderLedger.getInstance().snapshotOf(request.project)
        val plan = plans.build(request)

        return Analysis(plan, settings, ledger, anonymize(plan, settings, ledger))
    }

    /**
     * The clipboard first, the ledger second, the balloon third, the sidecar last — and the failure
     * modes told apart.
     *
     * A clipboard write that throws leaves the clipboard as it was, so it is reported as a failure
     * like any other **and the delta is dropped**: the snippet was never sent, so nothing in it was
     * ever named. A balloon that throws is not reported that way: by then the copy has happened, and
     * claiming *"your clipboard was not changed"* would be a lie in the one message that must not
     * contain one.
     *
     * **The commit sits between the two, and that ordering is the rule rather than an accident.**
     * The clipboard write is the single moment at which this invocation has happened at all, so it
     * is the single moment the mapping may move. Committing before it would record names for a
     * snippet that never reached the clipboard; committing after the balloon would leave the numbers
     * on a paste the user already has, if the balloon threw.
     *
     * The delta is committed unconditionally, empty map or not — see [PlaceholderLedger.commit].
     *
     * **The sidecar goes last, after the balloon, and that is the same rule read from the other
     * end.** It is recorded under the same condition as the commit — the snippet reached the
     * clipboard — but it is the only step here whose loss the design already has an answer for:
     * a window missing a paste is under-recovery, while a balloon suppressed by a failing cache
     * write would leave the user with no account of a copy that did happen. It is a **second**
     * holder rather than a second field of the first for the reason it is last: one is data and one
     * is cache, and they part company on what losing them costs — see [PlaceholderSidecar].
     *
     * ### Two invocations at once, and why the ledger is read a second time here
     *
     * `queue()` does not serialize anything: invoke twice over a large file and two analyses run
     * side by side. Both read the same ledger, both are handed the same next number, and both would
     * commit — putting one placeholder on two different symbols, which is the one thing the whole
     * design forbids.
     *
     * So the ledger is read again here and the analysis is **re-run against it if it moved**. This
     * is the point at which that is worth anything, because the EDT is single-threaded: read,
     * re-run, write, commit is atomic against every other invocation without a lock existing
     * anywhere. The re-run is [anonymize], which is pure and works off the plan already built — the
     * expensive half, the PSI walk, is not repeated and never leaves the background thread. On the
     * ordinary path nothing is re-run at all.
     */
    private fun deliver(project: Project, analysis: Analysis) {
        val ledger = PlaceholderLedger.getInstance()
        val latest = ledger.snapshotOf(project)
        val result = when {
            analysis.ledger.isStill(latest) -> analysis.result
            else -> anonymize(analysis.plan, analysis.settings, latest)
        }

        try {
            CopyPasteManager.getInstance().setContents(StringSelection(result.text))
        } catch (failure: Throwable) {
            SnippetVeilNotifications.failed(project, failure)
            return
        }
        ledger.commit(project, result.delta)
        SnippetVeilNotifications.copied(project, result.counts, result.comments)

        // The mapping keeps the qualified keys; **the sidecar keeps this invocation whole** — its
        // locals, parameters, anonymous-class members and the text of every redacted literal, none
        // of which has a key that could be written down. Recorded only because the clipboard write
        // happened, and recorded after the balloon because it is the step that may be lost.
        PlaceholderSidecar.getInstance(project).record(result.mapping)
    }
}

/**
 * The selected ranges, or empty when nothing is selected.
 *
 * `blockSelectionStarts`/`Ends` covers every caret and column selection in one pair of arrays, and
 * reports a caret with no selection as an empty range — which is why the empty ones are dropped
 * rather than kept as zero-width fragments. **No selection at all means the whole file**, and the
 * plan builder is where that is turned into a range.
 *
 * Multiple carets produce multiple fragments and **one symbol table**: a multi-caret copy
 * concatenates its fragments, and the same symbol has to render identically in all of them or the
 * clipboard contradicts itself.
 */
internal fun selectedRangesOf(editor: Editor): List<TextRange> {
    val model = editor.selectionModel
    val starts = model.blockSelectionStarts
    val ends = model.blockSelectionEnds
    return starts.indices
        .filter { ends[it] > starts[it] }
        .map { TextRange(starts[it], ends[it]) }
}

/**
 * A Java source file, and nothing else — not a decompiled `.class` (which is also a [PsiJavaFile],
 * read-only, and owned by whoever shipped the jar).
 */
internal fun PsiFile?.isAnonymizable(): Boolean =
    this is PsiJavaFile && fileType == JavaFileType.INSTANCE

/**
 * One invocation's work — what the engine produced, and everything it would take to produce it
 * again.
 *
 * The inputs are carried alongside the output rather than discarded because the ledger this ran
 * against may have moved by the time the result reaches the clipboard; see
 * [CopyAnonymizedAction.deliver]. They are all values: the plan is immutable, the settings are, and
 * the snapshot is — so nothing here can be observed changing between the two threads that touch it.
 */
private class Analysis(
    val plan: SnippetPlan,
    val settings: AnonymizationSettings,
    val ledger: LedgerSnapshot,
    val result: AnonymizationResult,
)
