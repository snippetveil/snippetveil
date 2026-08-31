package com.snippetveil.plugin

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * **Anonymize with Preview…** — the same invocation as `Copy Anonymized`, stopped at a dialog.
 *
 * It exists for one reason, and it is not caution: **it is the only place a user can anonymize
 * less.** The per-item preserve — on an unresolved name, or on any name once the user has unlocked
 * the column — and keeping comments live only in [PreviewDialog] and only for one invocation, which
 * is what leaves the fast path with no reduction to read.
 *
 * **Preview-first — always opening the dialog — was rejected**: it taxes the common case to protect
 * against a risk that does not exist on that path. So there are two actions, and the fast one is the
 * one that copies without asking.
 *
 * Everything between the editor and the clipboard is [startAnonymizing] and [deliver], shared with
 * `Copy Anonymized`. The dialog sits between them and changes neither: what it hands back is an
 * [Analysis] like any other, delivered by the same line of code, so the ledger commits at the same
 * moment and under the same rule. **A cancelled preview reaches none of it** — no clipboard write,
 * therefore no commit, therefore no number burnt.
 *
 * @param plans the analysis, injectable so that the fail-closed guarantee can be made to fail
 * @param previews the dialog, injectable so that the two guarantees this action adds — the preview
 *   shows what the clipboard gets, and Cancel burns nothing — can be asserted without a modal window
 */
class AnonymizeWithPreviewAction internal constructor(
    private val plans: PlanBuilder,
    private val previews: Previews,
) : AnAction() {

    /** The constructor the platform uses; `plugin.xml` names this class and nothing else. */
    constructor() : this(JavaPlanBuilder, PreviewDialogs)

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) = offerOnlyOnJava(event)

    override fun actionPerformed(event: AnActionEvent) = startAnonymizing(event, plans) { project, analysis ->
        previews.confirm(project, analysis)?.let { deliver(project, it) }
    }
}
