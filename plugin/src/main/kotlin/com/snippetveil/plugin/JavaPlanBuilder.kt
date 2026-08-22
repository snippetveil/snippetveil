package com.snippetveil.plugin

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.JdkOrderEntry
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiBreakStatement
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiContinueStatement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiJavaCodeReferenceElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiLabeledStatement
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiPackage
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiRecordComponent
import com.intellij.psi.PsiTypeParameter
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtilCore
import com.snippetveil.core.CommentOccurrence
import com.snippetveil.core.LiteralOccurrence
import com.snippetveil.core.Occurrence
import com.snippetveil.core.SnippetPlan
import com.snippetveil.core.SymbolEvidence
import com.snippetveil.core.SymbolOccurrence
import com.snippetveil.core.SymbolOrigin
import com.snippetveil.core.SymbolRole

/**
 * Describes a Java snippet truthfully, and decides nothing about it.
 *
 * Runs inside a non-blocking read action on a background thread. It allocates no placeholder,
 * mutates no PSI and touches no ledger, which is what makes a cancel-and-restart free: the platform
 * can throw this walk away half-finished as often as a write action happens, and the only cost is
 * the walk itself.
 */
internal object JavaPlanBuilder : PlanBuilder {

    override fun build(request: SnippetRequest): SnippetPlan {
        val file = request.file
        val fragments = fragmentsOf(file, request.selections)

        val text = fragments.joinToString(FRAGMENT_SEPARATOR) { file.text.substring(it.range.startOffset, it.range.endOffset) }
        val occurrences = (symbolsIn(request.project, file, fragments) + literalsAndCommentsIn(file, fragments))
            .sortedBy { it.start }

        return SnippetPlan(text, occurrences, rootPackageOf(file))
    }

    /**
     * The ranges actually analysed: the selection snapped outward to whole-token boundaries, or the
     * whole file when there is no selection.
     *
     * **Snapping closes the last fail-open leak, and it is safe rather than merely convenient.** A
     * selection cutting mid-identifier would otherwise emit raw domain text — `chantReference = x;`
     * out of `merchantReference` — because the fragment the rules never saw is copied verbatim.
     * Extending outward can only add text that some rule already governs: an identifier becomes a
     * placeholder or a preserved library name, a literal becomes a literal. **There is no path where
     * snapping emits un-anonymized project text.**
     *
     * Rejected on the way here: rewriting only the overlapping fragment, which splices half a
     * placeholder into half a name; and dropping the fragment, which silently deletes characters the
     * user selected and reads as a bug.
     */
    private fun fragmentsOf(file: PsiFile, selections: List<TextRange>): List<Fragment> {
        val snapped = selections
            .map { TextRange(snapStart(file, it.startOffset), snapEnd(file, it.endOffset)) }
            .ifEmpty { listOf(TextRange(0, file.textLength)) }
            .sortedBy { it.startOffset }

        // Two carets whose snapped ranges now touch are one fragment: the separator between them
        // would otherwise insert a newline into the middle of a token boundary that snapping just
        // widened.
        val merged = mutableListOf<TextRange>()
        for (range in snapped) {
            val previous = merged.lastOrNull()
            if (previous != null && range.startOffset <= previous.endOffset) {
                merged[merged.lastIndex] = previous.union(range)
            } else {
                merged += range
            }
        }

        var planStart = 0
        return merged.map { range ->
            Fragment(range, planStart).also { planStart += range.length + FRAGMENT_SEPARATOR.length }
        }
    }

    /**
     * The start of the token [offset] falls inside, or [offset] itself when it already sits on a
     * boundary. Whitespace is the one leaf that may be split: half a run of spaces is still spaces.
     */
    private fun snapStart(file: PsiFile, offset: Int): Int {
        val leaf = file.findElementAt(offset) ?: return offset
        if (leaf is PsiWhiteSpace) return offset
        return minOf(offset, leaf.textRange.startOffset)
    }

    /** The end of the token [offset] falls inside; see [snapStart]. */
    private fun snapEnd(file: PsiFile, offset: Int): Int {
        if (offset <= 0 || offset >= file.textLength) return offset.coerceIn(0, file.textLength)
        val leaf = file.findElementAt(offset - 1) ?: return offset
        if (leaf is PsiWhiteSpace) return offset
        return maxOf(offset, leaf.textRange.endOffset)
    }

    /**
     * Every identifier inside the analysed ranges, with what is known about the symbol it names.
     *
     * A leaf walk rather than a visitor, because the unit of interest is the token: the snapped
     * ranges are token-aligned, so "inside the range" is a question with no partial answers.
     */
    private fun symbolsIn(project: Project, file: PsiFile, fragments: List<Fragment>): List<Occurrence> {
        val occurrences = mutableListOf<Occurrence>()
        for (fragment in fragments) {
            var leaf: PsiElement? = file.findElementAt(fragment.range.startOffset)
            while (leaf != null && leaf.textRange.startOffset < fragment.range.endOffset) {
                if (leaf is PsiIdentifier && fragment.range.contains(leaf.textRange)) {
                    evidenceFor(project, leaf)?.let { evidence ->
                        occurrences += SymbolOccurrence(
                            start = fragment.translate(leaf.textRange.startOffset),
                            end = fragment.translate(leaf.textRange.endOffset),
                            text = leaf.text,
                            symbol = evidence,
                        )
                    }
                }
                leaf = PsiTreeUtil.nextLeaf(leaf)
            }
        }
        return occurrences
    }

    /**
     * Literals and comments, whole, wherever they fall inside the analysed ranges.
     *
     * Nothing acts on these yet — the rules for literal redaction and comment stripping are their
     * own tickets. They are reported from the start because a plan is a description of the snippet
     * rather than a work list, and a description that omitted them would be a lie about what is in
     * the text the engine is handed.
     */
    private fun literalsAndCommentsIn(file: PsiFile, fragments: List<Fragment>): List<Occurrence> =
        // Typed at PsiElement explicitly: left to inference, Kotlin picks the nearest common
        // supertype of the two, which today is an `@Experimental` interface — and the Plugin
        // Verifier then reports this plugin as depending on API that can change under it.
        PsiTreeUtil.findChildrenOfAnyType<PsiElement>(file, PsiComment::class.java, PsiLiteralExpression::class.java)
            .mapNotNull { element ->
                val fragment = fragments.firstOrNull { it.range.contains(element.textRange) } ?: return@mapNotNull null
                val start = fragment.translate(element.textRange.startOffset)
                val end = fragment.translate(element.textRange.endOffset)
                if (element is PsiComment) CommentOccurrence(start, end) else LiteralOccurrence(start, end)
            }

    /**
     * What is known about the symbol [identifier] names, or `null` when this ticket's rules have
     * nothing to say about it.
     *
     * Three shapes: an identifier is part of a reference, which resolves; or the name of a
     * declaration, which *is* the symbol; or a label named by a `break` or `continue`, where the
     * reference hangs off the statement rather than off the identifier's own parent and has to be
     * asked for by name. Everything else — keywords, the segments of a package name — falls through.
     *
     * The label case is small and it is not optional. Without it a jump to a label the selection
     * declares reports as *unresolved* — which would fail the name closed into `Unknown3` while its
     * own declaration two lines up rendered as `local1`, and would put a name the builder never
     * asked the IDE about into the count the balloon shows.
     */
    private fun evidenceFor(project: Project, identifier: PsiIdentifier): SymbolEvidence? {
        val parent = identifier.parent
        val declaration = when {
            parent is PsiJavaCodeReferenceElement -> validResolutionOf(parent)
            parent is PsiNameIdentifierOwner && parent.nameIdentifier === identifier -> parent
            parent is PsiBreakStatement && parent.labelIdentifier === identifier -> parent.reference?.resolve()
            parent is PsiContinueStatement && parent.labelIdentifier === identifier -> parent.reference?.resolve()
            else -> null
        }

        // A package segment is a reference like any other and resolves like one. Renaming packages
        // is a separate ticket with a rule of its own — segment by segment, so that same-package and
        // different-package stay distinguishable — and until it lands, folding `java` in
        // `java.util.List` into the type namespace would be strictly worse than leaving it alone.
        //
        // **This is the one place a project-owned name still passes through**, and it is a stated
        // gap rather than a discovered one: the plugin description says package names are not
        // handled yet, and it is the only `return null` left in this function for exactly that
        // reason — every other unrecognised shape is concealed by [roleOf]'s fallback.
        if (declaration is PsiPackage) return null

        val declaredName = (declaration as? PsiNameIdentifierOwner)?.name ?: identifier.text

        // An identifier that resolved to nothing is reported as unresolved rather than dropped, and
        // the engine fails it closed into the `Unknown` namespace. Red or incomplete code is normal
        // rather than exceptional, and the snippet a developer is debugging is the likely one.
        //
        // Keyed on the text, which is the only thing there is to key an unresolved name on. Two
        // distinct symbols spelled alike therefore share a placeholder — the reverse mapping stays
        // well-defined, since `Unknown1` still stands for exactly one *name*, which is all a reader
        // can be handed back about a name that resolved to nothing.
        //
        // The role of a name that did not resolve is not knowable, and no rule reads this one: the
        // engine takes the namespace off the origin precisely because the role would be an
        // invention. It is filled in rather than made nullable so that every other role stays a fact.
        val symbol = declaration?.let(::declaredSymbolOf) ?: return SymbolEvidence(
            key = "unresolved:" + identifier.text,
            role = SymbolRole.TYPE,
            origin = SymbolOrigin.UNRESOLVED,
            declaredName = identifier.text,
        )

        return SymbolEvidence(
            key = keyOf(symbol),
            role = roleOf(symbol),
            origin = originOf(project, symbol),
            declaredName = (symbol as? PsiNameIdentifierOwner)?.name ?: declaredName,
            signature = (symbol as? PsiMethod)?.let { method ->
                method.parameterList.parameters.joinToString(",", "(", ")") { it.type.canonicalText }
            },
        )
    }

    /**
     * What [reference] resolves to, or `null` when the resolution is one the language would reject.
     *
     * `resolve()` is not enough. It hands back `advancedResolve(false).element` whatever the resolve
     * result says about it, so a reference that only reaches its target by breaking a rule — an
     * inaccessible member, an instance member reached through a class name — comes back looking
     * exactly like a clean resolution. Under the old rules that meant a JDK or library origin and a
     * name preserved verbatim; under fail-closed it would mean a name vouched for on evidence the
     * compiler rejects.
     *
     * `isValidResult` is accessibility, static-scope correctness and applicability together, and it
     * is the first two that decide anything here: a call no overload accepts resolves to no element
     * at all, so applicability is a question that rarely gets asked with an answer in hand.
     *
     * `advancedResolve(false)` is the same resolution `PsiCall.resolveMethod()` and
     * `resolveConstructor()` perform for calls — they read the call's own reference — so there is
     * nothing further to ask on their behalf. A constructor is handled a step later, by
     * [declaredSymbolOf], because its identifier names its class rather than the constructor.
     */
    private fun validResolutionOf(reference: PsiJavaCodeReferenceElement): PsiElement? {
        val result = reference.advancedResolve(false)
        val element = result.element ?: return null

        // A package is not a member, and accessibility is a question about members. The platform
        // answers it for a package anyway, and answers it `false` for the root segment of a
        // qualified name — `java` in `java.util.List` — so gating on it here would fail the segment
        // closed and put `Unknown2.util.List` on the clipboard. Packages are left alone by the rule
        // below for reasons of their own; this only keeps them from being called unresolved.
        if (element is PsiPackage) return element

        return element.takeIf { result.isValidResult }
    }

    /**
     * The symbol an identifier actually names, which is not always the element resolution returns.
     *
     * The one case that differs today is a constructor: the identifier in `public Payment(...)` is
     * the class's name, spelled where a method's name would go. Reading it as a method would emit
     * `public method1(String param1)` inside `class Type1` — a method with no return type, which
     * does not read as anonymized, it reads as broken. The wider rule this belongs to, along with
     * the other forced-sharing cases, is its own ticket; this much is not policy but grammar.
     */
    private fun declaredSymbolOf(declaration: PsiElement): PsiElement =
        if (declaration is PsiMethod && declaration.isConstructor) declaration.containingClass ?: declaration
        else declaration

    /**
     * **The spine rule's evidence: anonymize a symbol iff its declaring file is project-owned.**
     *
     * Ownership is a `VirtualFile` question, and the platform facts that decide how it is asked are
     * worth stating, because three of them are traps:
     *  - [PsiUtilCore.getVirtualFile] rather than `containingFile.virtualFile`, because it handles
     *    in-memory copies and returns `null` for light elements instead of throwing.
     *  - `isInContent`, never `isInSource` — the latter includes *library sources*, so an attached
     *    source jar would classify as project code.
     *  - **Never `instanceof PsiCompiledElement`.** An attached source jar makes a library symbol
     *    resolve to source PSI, so compiled-ness says nothing at all about who owns the code.
     *
     * Generated sources are content, and therefore project-owned, which is the right answer: they
     * are generated *from* the project's own domain. `isInContent` is project-wide, so a
     * multi-module project needs no rule of its own.
     */
    private fun originOf(project: Project, symbol: PsiElement): SymbolOrigin {
        // Project-owned by construction: none of these can be declared anywhere but in the file
        // under analysis, so there is no file to classify and no index to ask.
        if (symbol is PsiLocalVariable || symbol is PsiParameter ||
            symbol is PsiTypeParameter || symbol is PsiLabeledStatement
        ) {
            return SymbolOrigin.IN_CONTENT
        }

        // A light element — an IDE-synthesized member with no file of its own. Fail closed: it was
        // synthesized from something, and in this codebase's Java the something is a project class.
        val virtualFile = PsiUtilCore.getVirtualFile(symbol) ?: return SymbolOrigin.IN_CONTENT

        val index = ProjectFileIndex.getInstance(project)
        return when {
            index.isInContent(virtualFile) -> SymbolOrigin.IN_CONTENT
            index.getOrderEntriesForFile(virtualFile).any { it is JdkOrderEntry } -> SymbolOrigin.JDK
            else -> SymbolOrigin.LIBRARY
        }
    }

    /**
     * The identity of a declared symbol, as a string the engine can compare and a later ticket can
     * persist.
     *
     * A method's key deliberately omits its signature, which is how overloads collapse to one
     * placeholder: they share a name in source, so they share a placeholder. The signature is still
     * reported as evidence, so a rule that needs to tell them apart can, without this changing.
     *
     * Anything with no qualified name — an anonymous class, a local, a parameter, a label — is keyed
     * on where it is written. That key is stable for exactly as long as the file is not edited,
     * which is exactly as long as one invocation lasts.
     */
    private fun keyOf(symbol: PsiElement): String = when (symbol) {
        is PsiClass -> "class:" + (symbol.qualifiedName ?: anchorOf(symbol))
        is PsiMethod -> "method:" + ownerKeyOf(symbol.containingClass) + "#" + symbol.name
        is PsiField -> "field:" + ownerKeyOf(symbol.containingClass) + "#" + symbol.name
        else -> "local:" + anchorOf(symbol)
    }

    private fun ownerKeyOf(owner: PsiClass?): String = owner?.let(::keyOf) ?: "<none>"

    private fun anchorOf(symbol: PsiElement): String =
        (PsiUtilCore.getVirtualFile(symbol)?.url ?: "<light>") + "@" + symbol.textOffset

    /**
     * What a symbol is. **Total, on purpose.**
     *
     * This used to return `null` for a shape it did not recognise, and the caller dropped the
     * occurrence — which copied the name through verbatim. That is precisely the fail-open this
     * action exists to prevent, and it was not hypothetical: a record component is a named,
     * project-owned symbol that is a `PsiVariable` and **not** a `PsiField`, so
     * `record Payment(String merchantRef)` put `merchantRef` on the clipboard intact.
     *
     * Hence the `else`, and hence that it conceals. **The role is cosmetic and a missing placeholder
     * is a leak** — the role only decides how cheaply a human maps the AI's reply back onto real
     * code, whereas a name that never became a placeholder is the whole failure. `FIELD` is the
     * fallback because every other named thing a `PsiIdentifier` resolves to in Java is a member of
     * a type, and `field1` is the least surprising way to read one.
     */
    private fun roleOf(symbol: PsiElement): SymbolRole = when (symbol) {
        // Before PsiClass: a type parameter is one, and a type by any reading of what it names.
        is PsiTypeParameter -> SymbolRole.TYPE
        is PsiClass -> SymbolRole.TYPE
        is PsiMethod -> SymbolRole.METHOD
        is PsiField -> SymbolRole.FIELD

        // A record component is compiled to a private final field of the same name, and Java forces
        // the component, that field and the accessor to agree. Naming it a field here is the reading
        // that stays true when the ticket for forced sharing collapses all three onto one symbol.
        is PsiRecordComponent -> SymbolRole.FIELD

        is PsiParameter -> SymbolRole.PARAMETER
        is PsiLocalVariable, is PsiLabeledStatement -> SymbolRole.LOCAL
        else -> SymbolRole.FIELD
    }

    /**
     * The analysed file's root package — `com.acme` out of `com.acme.web.PaymentController`.
     *
     * Nothing reads it yet. It is here because it is a *fact about the file* that the ticket
     * treating internal-org libraries as project code needs, and facts belong on the plan: the
     * builder must not pre-judge, so the prefix match itself stays in the engine where it is
     * testable against a plan literal with no IDE involved.
     */
    private fun rootPackageOf(file: PsiFile): String? =
        (file as? PsiJavaFile)?.packageName
            ?.takeIf { it.isNotEmpty() }
            ?.split('.')
            ?.let { segments -> segments.take(2).joinToString(".") }

    /** A separator that cannot merge two fragments into one token, which is all it has to be. */
    private const val FRAGMENT_SEPARATOR = "\n"
}

/** One analysed range, and where its text starts in the plan. */
private class Fragment(val range: TextRange, val planStart: Int) {
    fun translate(fileOffset: Int): Int = planStart + (fileOffset - range.startOffset)
}
