package com.snippetveil.plugin

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.snippetveil.core.SnippetPlan

/**
 * Everything one invocation needs, captured on the EDT before anything can move underneath it.
 *
 * The selection is carried as plain document offsets rather than as a live editor, so that the
 * analysis running on a background thread cannot observe a caret that has since moved. Whether
 * those offsets are still meaningful is the platform's problem and it has an answer: a concurrent
 * write action cancels the read action outright.
 *
 * @param selections the raw selected ranges, **unsnapped**, and empty when there is no selection at
 *   all — which means the whole file. Snapping needs a committed PSI tree, so it happens where the
 *   tree is guaranteed to exist, as the first thing the plan builder does.
 */
internal class SnippetRequest(
    val project: Project,
    val file: PsiFile,
    val selections: List<TextRange>,
)

/**
 * Turns a captured request into the pure description the engine works from.
 *
 * An interface with exactly one production implementation, because the alternative is worse: the
 * fail-closed guarantee is *"a throw anywhere in the analysis leaves the clipboard byte-identical"*,
 * and the only honest way to test that is to make the analysis throw. A seam a test can substitute
 * at is what turns that guarantee from a claim into an assertion.
 */
internal fun interface PlanBuilder {
    fun build(request: SnippetRequest): SnippetPlan
}
