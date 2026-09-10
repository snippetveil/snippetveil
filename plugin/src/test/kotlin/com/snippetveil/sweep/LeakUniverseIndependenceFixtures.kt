package com.snippetveil.sweep

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.snippetveil.core.AnonymizationResult
import com.snippetveil.core.SnippetPlan
import com.snippetveil.plugin.JavaPlanBuilder
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.asJava.KotlinAsJavaSupport
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.resolve.DescriptorUtils

/**
 * **The red path of `LeakUniverseIndependenceTest`, one fixture per edge of its rule** — baked in
 * rather than observed once. This one: a universe read off the anonymiser's own output, which can
 * only ever agree with it.
 *
 * Each class in this file crosses exactly one edge of the rule, and is checked alone, so that every
 * edge has been seen to fail by itself: a rule that failed as a whole on one fixture would say
 * nothing about the edges that fixture happened not to cross. They are named outside the pattern the
 * rule's own import matches, so the real check never reads them, and nothing calls them — the types
 * they name are never loaded.
 */
internal class ReadsTheAnonymisersOutput {

    fun fromPlan(plan: SnippetPlan): Set<String> = setOf(plan.toString())

    fun fromResult(result: AnonymizationResult): Set<String> = setOf(result.text)
}

/** The anonymiser's walk itself, asked for a spelling. */
internal class AsksThePlanBuilder {

    val builder: JavaPlanBuilder? = null
}

/** A light class — the element the anonymiser keys a Kotlin declaration through. */
internal class ReadsALightClass {

    val support: KotlinAsJavaSupport? = null
}

/** An analysis session, which is resolution by another name. */
internal class OpensAnAnalysisSession {

    val session: KaSession? = null
}

/**
 * The Kotlin reference machinery, reached through the utility the plan builder reaches it through.
 * Typed `Any?` so that only the call crosses the edge, and the reference type does not.
 */
internal class FollowsAKotlinReference {

    fun referenceOf(expression: KtSimpleNameExpression): Any? = expression.mainReference
}

/** The compiler's own resolver. */
internal class AsksTheResolver {

    val resolver: DescriptorUtils? = null
}

/** A reference, held — the thing a walk of declaration text never needs. */
internal class HoldsAReference {

    val reference: PsiReference? = null
}

/**
 * Asking an element what it refers to — the anonymiser's question — through the call alone, with no
 * reference type in the signature for the other edges to see.
 */
internal class ResolvesToDecideASpelling {

    fun refersToSomething(element: PsiElement): Boolean = element.reference != null
}
