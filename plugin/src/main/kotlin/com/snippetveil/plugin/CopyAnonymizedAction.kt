package com.snippetveil.plugin

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile

/**
 * **Copy Anonymized** — select Java code, invoke, and the anonymized text is on the clipboard.
 *
 * The fast path, and **the maximally-anonymizing one by construction**: it reads no reduction, so
 * there is nothing that can have been left on. Every reduction the design authorises is
 * per-invocation and lives in [PreviewDialog], which is the other action.
 *
 * What it does between the editor and the clipboard is in [startAnonymizing] and [deliver], written
 * once and shared with the preview — the threading, the fail-closed guarantee and the ledger's
 * commit point are properties of an invocation rather than of this menu item.
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

    override fun update(event: AnActionEvent) = offerOnlyOnJava(event)

    override fun actionPerformed(event: AnActionEvent) = startAnonymizing(event, plans, ::deliver)
}

/**
 * A Java source file, and nothing else — not a decompiled `.class` (which is also a [PsiJavaFile],
 * read-only, and owned by whoever shipped the jar).
 */
internal fun PsiFile?.isAnonymizable(): Boolean =
    this is PsiJavaFile && fileType == JavaFileType.INSTANCE
