package com.snippetveil.plugin.kotlin

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtilCore
import com.snippetveil.core.Occurrence
import com.snippetveil.core.SnippetPlan
import com.snippetveil.core.SourceLanguage
import com.snippetveil.core.SymbolEvidence
import com.snippetveil.core.SymbolOccurrence
import com.snippetveil.core.SymbolOrigin
import com.snippetveil.core.SymbolRole
import com.snippetveil.plugin.FRAGMENT_SEPARATOR
import com.snippetveil.plugin.Fragment
import com.snippetveil.plugin.PlanBuilder
import com.snippetveil.plugin.SnippetRequest
import com.snippetveil.plugin.SymbolFacts
import com.snippetveil.plugin.SymbolKeys
import com.snippetveil.plugin.fragmentsOf
import com.snippetveil.plugin.snapEnd
import com.snippetveil.plugin.snapStart
import org.jetbrains.kotlin.asJava.getRepresentativeLightMethod
import org.jetbrains.kotlin.asJava.classes.KtLightClassForFacade
import org.jetbrains.kotlin.asJava.elements.KtLightElement
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassLikeDeclaration
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtDestructuringDeclarationEntry
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtLabelReferenceExpression
import org.jetbrains.kotlin.psi.KtLabeledExpression
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.KtTypeParameter
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject

/**
 * Describes a Kotlin snippet truthfully, and decides nothing about it.
 *
 * ### Why this exists at all, rather than Java's walk learning Kotlin
 *
 * [com.snippetveil.plugin.JavaPlanBuilder] iterates `PsiIdentifier` leaves. Kotlin's identifiers are
 * [KtTokens.IDENTIFIER], and — the part that settles it — **a light identifier aliases the Kotlin
 * source token**: the light accessor `getMerchantRef` reports the range of the property's own
 * `merchantRef`. A walk driven off light classes therefore reports ranges that are not the ranges in
 * the file, and the splice lands in the wrong place.
 *
 * > **Light classes are a bridge, not a walker.**
 *
 * The bridge direction is the valuable one, and it is already settled elsewhere:
 * [KotlinSymbolKeys.ledgerKeyOf] is the one key rule for both languages, and this walk **calls it**
 * rather than deriving a key of its own. Ranges come from this walk; keys come from that rule; the
 * two never swap jobs, and there is no second spelling of either to keep in step.
 *
 * ### Identifiers, and a stated limit
 *
 * **This walk reports identifiers.** Kotlin's literals and comments are not walked here — a string
 * template with interpolation is a container with a snapping rule of its own, and deciding that is a
 * separate piece of work. Nothing is registered against this builder, so no `.kt` file reaches it
 * from a user's IDE: `com.snippetveil-withKotlin.xml` declares no `languageSupport`, and the source
 * file gate keeps returning its stated refusal on every `.kt` file.
 *
 * ### Threading
 *
 * The Analysis API this resolution runs on requires a read action, forbids the EDT and forbids write
 * actions, and dumb mode is *restricted analysis* whose results are documented as possibly incomplete
 * — which makes `inSmartMode` load-bearing for Kotlin in exactly the way `IndexNotReadyException`
 * makes it load-bearing for Java. The shape the invocation already has satisfies all of it: EDT
 * capture, then `ReadAction.nonBlocking().inSmartMode().expireWith(project)`.
 *
 * **No analysis session outlives the walk, and there is none to leak.** Resolution goes through the
 * Kotlin plugin's own reference layer, which opens and closes its session inside each `resolve()`
 * call and hands back plain PSI — so this walk opens no analysis block of its own and holds no
 * `KaSession` or `KaSymbol`. The plan could not carry one in any case: [SnippetPlan] lives in
 * `:core`, which has no IntelliJ dependency at all, and `core does not reach for the IDE` asserts it
 * over shipped bytecode. **A walk that later reaches for `analyze { }` directly owes an assertion
 * this one does not**: the test beside this only shows that what comes back is readable once the read
 * action has ended, which a leak-free walk and a careful leaky one both satisfy. A concurrent write
 * action cancels and restarts this walk, and the restart is free: it allocates nothing, mutates
 * nothing and touches no ledger. It only describes.
 */
internal object KotlinPlanBuilder : PlanBuilder {

    override fun build(request: SnippetRequest): SnippetPlan {
        val file = request.file
        val snapped = request.selections.map { TextRange(snapStart(file, it.startOffset), snapEnd(file, it.endOffset)) }
        val fragments = fragmentsOf(file, snapped)

        val text = fragments.joinToString(FRAGMENT_SEPARATOR) { file.text.substring(it.range.startOffset, it.range.endOffset) }

        return SnippetPlan(
            text,
            symbolsIn(request.project, file, fragments),
            rootPackageOf(file),
            // A range moved iff the two lists differ, which is the claim itself rather than a proxy
            // for it. Snapping is the only thing between them, and the whole-file case reaches
            // neither list: no selection was cut, so nothing was extended.
            selectionExpanded = snapped != request.selections,
        )
    }

    /**
     * Every Kotlin identifier inside the analysed ranges, with what is known about the symbol it
     * names — **and the ranges are the token's own**, which is the whole reason this walk exists.
     *
     * A leaf walk rather than a visitor, for the reason the Java walk is one: the unit of interest is
     * the token, and the snapped ranges are token-aligned, so *inside the range* is a question with
     * no partial answers.
     */
    private fun symbolsIn(project: Project, file: PsiFile, fragments: List<Fragment>): List<Occurrence> {
        val occurrences = mutableListOf<Occurrence>()
        for (fragment in fragments) {
            var leaf: PsiElement? = file.findElementAt(fragment.range.startOffset)
            while (leaf != null && leaf.textRange.startOffset < fragment.range.endOffset) {
                if (PsiUtilCore.getElementType(leaf) == KtTokens.IDENTIFIER && fragment.range.contains(leaf.textRange)) {
                    occurrences += SymbolOccurrence(
                        start = fragment.translate(leaf.textRange.startOffset),
                        end = fragment.translate(leaf.textRange.endOffset),
                        text = leaf.text,
                        symbol = evidenceFor(project, leaf),
                        language = LANGUAGE,
                    )
                }
                leaf = PsiTreeUtil.nextLeaf(leaf)
            }
        }
        return occurrences
    }

    /**
     * What is known about the symbol [identifier] names.
     *
     * Three shapes. A **label** is asked first and separately, because a label reference is the one
     * place where what a name means is not what its own reference says — see [labelTargetOf]. The
     * name of a **declaration** *is* the symbol. Everything else is part of a reference, and the
     * reference resolves: a named argument, a package segment, a type, a call, an import — all of
     * them arrive here as one question with one answer.
     *
     * **Ownership is resolved, never assumed, and that is a Kotlin-specific obligation.** The Java
     * walk may treat a `PsiParameter` as project-owned by construction, because Java has no syntax
     * that names a parameter of a callee. Kotlin does: the token `merchantRef` in
     * `charge(merchantRef = x)` names a **parameter of the callee**, which may be in a library, the
     * JDK or another module. A builder that shortcut it to project-owned would rename library
     * parameter names and break every call it touches — a plausible artifact, which is the class this
     * product refuses outright. So nothing here is short-circuited on its PSI type; it is resolved,
     * and the declaring file answers.
     */
    private fun evidenceFor(project: Project, identifier: PsiElement): SymbolEvidence {
        val parent = identifier.parent
        val declaration = when {
            parent is KtLabelReferenceExpression -> labelTargetOf(parent)
            parent is KtNamedDeclaration && parent.nameIdentifier === identifier -> parent
            parent is KtElement -> parent.mainReference?.resolve()
            else -> null
        }

        return evidenceOf(project, declaration, identifier.text)
    }

    /**
     * The symbol a label names, which is **never the label reference's own resolution alone**.
     *
     * Three cases, and they are three different symbols wearing one syntax:
     *  - A **declared** label — `outer@` — resolves to nothing, because it is the declaration. Its
     *    symbol is the expression it labels, which is exactly what `break@outer` resolves to, so the
     *    declaration and every jump to it reach one key.
     *  - A **callee-named** label — `return@forEach`, `this@apply` — resolves to the lambda, and the
     *    lambda is in the analysed file. Taking that answer would classify the token as project-owned
     *    and rename it, while the call it is named after — `forEach`, a library function — is
     *    preserved beside it. The label's spelling is not the author's to choose: Kotlin gives a
     *    lambda an implicit label named after the function it is passed to. So the symbol is the
     *    **callee**, and the token renames in lockstep with it or not at all.
     *  - Anything else a label resolves to is already the symbol — `this@Ledger` resolves to the
     *    class, which is the thing it names.
     *
     * The fallback when a callee cannot be resolved is the lambda itself: a placeholder of its own is
     * the safe direction, and dropping the occurrence is the one thing a walk may never do.
     */
    private fun labelTargetOf(label: KtLabelReferenceExpression): PsiElement? {
        val labelled = label.parent?.parent as? KtLabeledExpression
        if (labelled != null && labelled.getTargetLabel() === label) return labelled.baseExpression

        val resolved = label.mainReference.resolve()
        return (resolved as? KtFunctionLiteral)?.let { calleeOf(it) ?: it } ?: resolved
    }

    /** The function [literal] is passed to, which is what its implicit label is named after. */
    private fun calleeOf(literal: KtFunctionLiteral): PsiElement? {
        val lambda = literal.parent as? KtLambdaExpression ?: return null
        val call = when (val argument = lambda.parent) {
            is KtLambdaArgument -> argument.parent
            is KtValueArgument -> argument.parent?.parent
            else -> null
        } as? KtCallExpression ?: return null

        return (call.calleeExpression as? KtElement)?.mainReference?.resolve()
    }

    /**
     * What is known about the symbol [declaration] names, for something written as [writtenName].
     *
     * A declaration reached from Kotlin that is **not** a Kotlin one — `java.util.regex.Pattern`, a
     * method on it, a package — is handed to [SymbolFacts] unchanged. It is the same symbol the Java
     * walk would report, described the same way, and describing it twice is how two walks stop
     * agreeing on a key.
     */
    private fun evidenceOf(project: Project, declaration: PsiElement?, writtenName: String): SymbolEvidence {
        // A name that resolved to nothing is reported as unresolved rather than dropped, and the
        // engine fails it closed. Red or incomplete code is normal rather than exceptional, and the
        // snippet a developer is debugging is the likely one.
        val symbol = declaration ?: return SymbolFacts.unresolvedEvidence(writtenName)
        if (symbol !is KtElement) return SymbolFacts.evidenceOf(project, symbol, declaredNameOf(symbol, writtenName))

        val ledgerKey = (symbol as? KtNamedDeclaration)?.let(KotlinSymbolKeys::ledgerKeyOf)
            ?: LedgerKey(SymbolKeys.localKeyOf(symbol), keyIsQualified = false)

        return SymbolEvidence(
            key = ledgerKey.key,
            role = roleOf(symbol),
            origin = ownershipOf(project, symbol),
            declaredName = declaredNameOf(symbol, writtenName),
            qualifiedName = qualifiedNameOf(symbol),
            packageName = packageNameOf(symbol),
            signature = lightMethodOf(symbol)?.let(SymbolFacts::signatureOf),
            overrideRoots = lightMethodOf(symbol)
                ?.let { method -> SymbolFacts.overrideRootsOf(method) { ownershipOf(project, it) } }
                .orEmpty(),
            accessor = lightMethodOf(symbol)?.let(SymbolFacts::accessorEvidenceOf),
            keyIsQualified = ledgerKey.keyIsQualified,
        )
    }

    /**
     * **The light method a function compiles to**, which is what the three method-shaped fields of
     * the evidence — the signature, the override chain and the accessor pair — are read off.
     *
     * **A function only, and a property deliberately not.** A property's representative light method
     * is its *getter*, and a property is keyed as the **field** it compiles to — so describing it
     * through the getter would attach one symbol's override chain and accessor pair to a different
     * symbol's key. The Java walk reports those fields for the accessor itself, which is the symbol
     * they are about, and it still does: a Java file calling `getMerchantRef()` gets them there.
     */
    private fun lightMethodOf(symbol: PsiElement): PsiMethod? =
        (symbol as? KtNamedFunction)?.getRepresentativeLightMethod()

    /**
     * What a symbol is, in **Kotlin's** grammar — which is not always what its light element is in
     * Java's.
     *
     * A property is a `field`, whichever accessors the compiler generates for it; an object
     * declaration is a type; an enum entry is a field, as it is in Java. A constructor parameter
     * written `val` declares a property and is reported as one, because that is what the token names.
     *
     * A label's target is an ordinary expression rather than a declaration — a `for` loop, a `while`,
     * a block — and it is the one thing that arrives here as a plain [KtExpression]. That is what the
     * last Kotlin branch reads, and it is why it can be as broad as it is.
     */
    private fun roleOf(symbol: PsiElement): SymbolRole = when {
        symbol is KtTypeParameter -> SymbolRole.TYPE_PARAMETER

        // Before KtClass, which an enum entry is one of. An entry is compiled to a static field of
        // its own enum, and `field1` is how a reader reads one.
        symbol is KtEnumEntry -> SymbolRole.FIELD
        symbol is KtClass -> if (symbol.isAnnotation()) SymbolRole.ANNOTATION else SymbolRole.TYPE

        // An object declaration and a type alias are both types in every sense a reader has.
        symbol is KtClassOrObject || symbol is KtTypeAlias -> SymbolRole.TYPE

        symbol is KtNamedFunction -> SymbolRole.METHOD
        symbol is KtProperty -> if (symbol.isLocal) SymbolRole.LOCAL else SymbolRole.FIELD
        symbol is KtDestructuringDeclarationEntry -> SymbolRole.LOCAL

        symbol is KtParameter -> when {
            // A member of an annotation type is a method in the bytecode and an attribute everywhere
            // it is written, and what is written is what a reader has to map back.
            (symbol.containingClassOrObject as? KtClass)?.isAnnotation() == true -> SymbolRole.ATTRIBUTE
            symbol.hasValOrVar() -> SymbolRole.FIELD
            symbol.isLoopParameter -> SymbolRole.LOCAL
            else -> SymbolRole.PARAMETER
        }

        // A labelled expression: `outer@ for (…)`. Not a declaration, and not a variable — `break
        // local7` reads as one, which is the whole of what the prefixes are for.
        symbol is KtExpression && symbol !is KtDeclaration -> SymbolRole.LABEL

        symbol is KtElement -> SymbolRole.METHOD
        else -> SymbolFacts.roleOf(symbol)
    }

    /**
     * The name a reverse mapping hands back for [symbol], or [writtenName] where the platform has no
     * name to give — a labelled `for` loop, a lambda.
     *
     * A synthetic name is refused rather than reported: the platform calls a lambda `<anonymous>`,
     * and a mapping table row saying a placeholder stands for `<anonymous>` is worse than one saying
     * it stands for the word actually written.
     */
    private fun declaredNameOf(symbol: PsiElement, writtenName: String): String =
        (symbol as? PsiNamedElement)?.name?.takeIf { it.isNotEmpty() && '<' !in it } ?: writtenName

    /**
     * The symbol's fully-qualified name, for a Kotlin declaration that has one.
     *
     * Classifiers only — a class, an object, an enum, a typealias — because those are the
     * declarations Java gives a qualified name to, and this field is read as the Java walk's
     * `PsiQualifiedNamedElement.qualifiedName` is. An enum entry is answered **before** the
     * classifier branch and answered `null`, for the reason [KotlinSymbolKeys.ledgerKeyOf] orders
     * its branches the same way: an entry is a `KtClass` in the grammar and a field on the JVM, and
     * a field has no qualified name.
     *
     * **A package does not reach this function**, and that is worth saying because the field matters
     * so much: [com.snippetveil.core.SymbolEvidence.qualifiedName] is what the top-level-segment rule
     * reads and nothing else reads it at all, so a package symbol arriving with a null one renames
     * the root segment silently and everywhere — measured on the spike, `org` survived 8,043 times in
     * Java output and not once in Kotlin until the field was populated. A package resolved from
     * Kotlin is a [com.intellij.psi.PsiPackage] rather than a [KtElement], so [evidenceOf] hands it
     * to [SymbolFacts] and it is described exactly as the Java walk describes it, qualified name
     * included. That the two walks agree on it *because they are one function* is stronger than a
     * second branch here would be, and it is asserted either way.
     */
    private fun qualifiedNameOf(symbol: PsiElement): String? = when (symbol) {
        is KtEnumEntry -> null
        is KtClassLikeDeclaration -> symbol.fqName?.asString()
        else -> null
    }

    /**
     * The package a symbol belongs to, read off the file it is declared in — which is where Kotlin
     * puts it: a nested class, a top-level function and a member all belong to their file's package,
     * and Kotlin does not require that package to match the directory.
     *
     * A package answers this for itself and is not asked here, for the reason [qualifiedNameOf] gives:
     * it is not a [KtElement], so it never reaches this walk's own description at all.
     */
    private fun packageNameOf(symbol: PsiElement): String? =
        (symbol.containingFile as? KtFile)?.packageFqName?.asString()?.takeIf { it.isNotEmpty() }

    /**
     * The analysed file's root package — `com.acme` out of `com.acme.ledger.Ledger`.
     *
     * A fact about the file, and the one the internal-library rule is derived from; the prefix match
     * itself stays in the engine, where a test reaches it without an IDE.
     */
    private fun rootPackageOf(file: PsiFile): String? =
        (file as? KtFile)?.packageFqName?.asString()
            ?.takeIf { it.isNotEmpty() }
            ?.split('.')
            ?.let { segments -> segments.take(2).joinToString(".") }

    /**
     * **The language every occurrence this walk reports is written in.** See
     * [com.snippetveil.core.Occurrence.language].
     */
    private val LANGUAGE = SourceLanguage.KOTLIN
}

/**
 * **Where the file declaring [symbol] lives** — the spine rule's evidence, asked the way Kotlin
 * requires it to be asked.
 *
 * Two of the three branches are Kotlin-specific and neither is an optimisation:
 *
 *  - **A light element's ownership comes from its origin.** A light element is synthesized rather
 *    than declared, so the file to classify is the Kotlin file it was synthesized *from* —
 *    `kotlinOrigin`, or [KtLightClassForFacade.files]`.first()` for a file facade, which has no
 *    single origin declaration because it stands for the file. This is the same light-class query the
 *    key rule already uses, read for ownership instead of for identity. A facade classified through
 *    the standard path is a class whose declaring file the platform is under no obligation to report.
 *  - **A Kotlin declaration is classified by its own file, and never by what kind of declaration it
 *    is.** This is where *ownership is resolved, never assumed* is actually enforced: a `KtParameter`
 *    reached through a named argument can be a library's, and the file says so.
 *
 * Anything else is a Java declaration reached from Kotlin — a package among them, which is why there
 * is no package branch here — and it is classified exactly as the Java walk classifies it.
 *
 * Internal rather than private to [KotlinPlanBuilder] because the facade branch is a **required**
 * resolution path that no ordinary fixture can steer the walk into — a Kotlin file has no syntax that
 * names its own facade — so it is asserted directly. See `KotlinPlanBuilderTest`.
 */
internal fun ownershipOf(project: Project, symbol: PsiElement): SymbolOrigin = when {
    symbol is KtLightElement<*, *> || symbol is KtLightClassForFacade ->
        SymbolFacts.originOfFile(project, PsiUtilCore.getVirtualFile(kotlinOriginOf(symbol)))

    symbol is KtElement -> SymbolFacts.originOfFile(project, PsiUtilCore.getVirtualFile(symbol))

    else -> SymbolFacts.originOf(project, symbol)
}

/**
 * The Kotlin element a light element was made from, or `null` when the platform reports none — at
 * which point [SymbolFacts.originOfFile] fails the symbol closed, into the project's own code.
 *
 * A facade's `kotlinOrigin` is `null` by construction: it is not synthesized from one declaration, it
 * stands for a whole file — so its files are what answer, and the first of them is enough, because
 * every file behind one facade declares the same package.
 */
private fun kotlinOriginOf(symbol: PsiElement): PsiElement? =
    (symbol as? KtLightElement<*, *>)?.kotlinOrigin
        ?: (symbol as? KtLightClassForFacade)?.files?.firstOrNull()
