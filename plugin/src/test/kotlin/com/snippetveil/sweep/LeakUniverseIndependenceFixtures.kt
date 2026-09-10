package com.snippetveil.sweep

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.snippetveil.core.AnonymizationResult
import com.snippetveil.core.SnippetPlan

/**
 * The red path of `LeakUniverseIndependenceTest`, baked in rather than observed once.
 *
 * Each class here is a way the leak universe could stop being independent of the anonymiser, written
 * so that the rule has something to fail on: a rule nothing ever violates is a rule nobody has seen
 * work. Named outside the pattern the rule's own import matches, so that the real check never reads
 * them; they are imported one class at a time, which is the only way a rule gets pointed at code that
 * is meant to violate it. Nothing calls them.
 */

/** Deciding a spelling by resolving what a name refers to — the anonymiser's question, asked here. */
internal class ResolvesToDecideASpelling {

    fun spellingOf(element: PsiElement): String? = (element.reference?.resolve() as? PsiNamedElement)?.name
}

/** A universe read off the anonymiser's own output, which can only ever agree with it. */
internal class BuiltFromTheAnonymisersOutput {

    fun fromPlan(plan: SnippetPlan): Set<String> = setOf(plan.toString())

    fun fromResult(result: AnonymizationResult): Set<String> = setOf(result.text)
}
