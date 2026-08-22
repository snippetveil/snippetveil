package com.snippetveil.plugin

import com.intellij.lang.java.beans.PropertyKind
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.JdkOrderEntry
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiBreakStatement
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiContinueStatement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementFactory
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiImportStaticReferenceElement
import com.intellij.psi.PsiJavaCodeReferenceElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiLabeledStatement
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiNameValuePair
import com.intellij.psi.PsiPackage
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiQualifiedNamedElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiRecordComponent
import com.intellij.psi.PsiTypeParameter
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.javadoc.PsiDocComment
import com.intellij.psi.javadoc.PsiDocTagValue
import com.intellij.psi.util.JavaPsiRecordUtil
import com.intellij.psi.util.PropertyUtilBase
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtilCore
import com.intellij.util.IncorrectOperationException
import com.snippetveil.core.AccessorEvidence
import com.snippetveil.core.CommentOccurrence
import com.snippetveil.core.CommentVerdict
import com.snippetveil.core.LiteralKind
import com.snippetveil.core.LiteralOccurrence
import com.snippetveil.core.LiteralReference
import com.snippetveil.core.Occurrence
import com.snippetveil.core.OverrideRoot
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
        val occurrences = (symbolsIn(request.project, file, fragments) + literalsAndCommentsIn(request.project, file, fragments))
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
                    occurrences += SymbolOccurrence(
                        start = fragment.translate(leaf.textRange.startOffset),
                        end = fragment.translate(leaf.textRange.endOffset),
                        text = leaf.text,
                        symbol = evidenceFor(project, leaf),
                    )
                }
                leaf = PsiTreeUtil.nextLeaf(leaf)
            }
        }
        return occurrences
    }

    /**
     * Literals and comments, whole, wherever they fall inside the analysed ranges — and, for each,
     * the references it carries: a literal's own, and the javadoc tag targets a comment holds.
     *
     * A comment is reported with the verdict a Java parser reached about its body, and with nothing
     * else said about it: whether it is stripped is [com.snippetveil.core.anonymize]'s decision, and
     * it is the same decision for a line comment and for javadoc.
     *
     * **The delimiters are read here rather than in the engine**, which is what lets the engine
     * preserve a literal's syntactic form without knowing how any of them are spelled: it rewrites
     * the content and nothing else, so a text block stays a text block and an escaped literal stays
     * escaped. Reading a delimiter is reading *form*; the prohibition is on deciding a literal's
     * rewrite from its *content*.
     */
    private fun literalsAndCommentsIn(project: Project, file: PsiFile, fragments: List<Fragment>): List<Occurrence> =
        // Typed at PsiElement explicitly: left to inference, Kotlin picks the nearest common
        // supertype of the two, which today is an `@Experimental` interface — and the Plugin
        // Verifier then reports this plugin as depending on API that can change under it.
        PsiTreeUtil.findChildrenOfAnyType<PsiElement>(file, PsiComment::class.java, PsiLiteralExpression::class.java)
            .flatMap { element ->
                val fragment = fragments.firstOrNull { it.range.contains(element.textRange) }
                    ?: return@flatMap emptyList()
                val start = fragment.translate(element.textRange.startOffset)
                val end = fragment.translate(element.textRange.endOffset)

                if (element is PsiComment) {
                    listOf(CommentOccurrence(start, end, verdictOf(project, element))) +
                        docReferencesIn(project, file, element, fragment)
                } else {
                    val literal = element as PsiLiteralExpression
                    val kind = kindOf(literal)
                    val content = contentRangeOf(kind, literal.text)
                    listOf(
                        LiteralOccurrence(
                            start = start,
                            end = end,
                            kind = kind,
                            contentStart = start + content.startOffset,
                            contentEnd = start + content.endOffset,
                            references = referencesIn(project, literal, fragment),
                        ),
                    )
                }
            }

    /**
     * **What a Java parser makes of one comment's body: a code block, or not.**
     *
     * Commented-out code is not prose and it separates exactly — `// this.customer.setOrder(order);`
     * parses, `// TODO: fix this` does not — and the parser is what says so. This is evidence in the
     * same sense a literal's type is: a fact obtained from the platform, reported without a judgment
     * attached, and read by a rule that lives on the other side of the seam.
     *
     * **A code block and nothing wider**, and that is a stated limit rather than an oversight.
     * `// private String merchantRef;` parses, because inside a block it reads as a local
     * declaration; `// void pay() {}` does not, because a method declaration is not a statement — so
     * a commented-out *method* is counted as prose. Widening the rule means trying the body against
     * every context Java has, and each context added is another way for a line of prose to parse by
     * accident. A verdict that is exact about a narrow question beats one that guesses at a broad
     * one, and the count it feeds is a disclosure rather than a gate.
     *
     * An empty body is prose. `{}` parses, and calling an empty comment *commented-out code* would be
     * the one verdict here that is plainly false.
     *
     * A parse that the platform refuses outright is prose for the same reason a failed parse is: the
     * question was *does this parse*, and the answer was no. It is not an anonymization failure, so
     * it does not fail the invocation closed.
     */
    private fun verdictOf(project: Project, comment: PsiComment): CommentVerdict {
        val body = bodyOf(comment)
        if (body.isBlank()) return CommentVerdict.PROSE

        return try {
            // The closing brace goes on a line of its own, because a body ending in a line comment
            // would otherwise swallow it.
            val block = PsiElementFactory.getInstance(project).createCodeBlockFromText("{" + body + "\n}", null)
            if (PsiTreeUtil.findChildOfType(block, PsiErrorElement::class.java) == null) {
                CommentVerdict.CODE
            } else {
                CommentVerdict.PROSE
            }
        } catch (refused: IncorrectOperationException) {
            CommentVerdict.PROSE
        }
    }

    /**
     * The text inside a comment's delimiters, with the leading asterisks taken off the front of each
     * line — which is what a reader of a javadoc block sees, and therefore what there is to parse.
     *
     * **The asterisks come off a block comment only.** A line comment has no such convention, so an
     * asterisk at the front of one is text somebody wrote: `// * total = 3;` is a bullet in a list,
     * and reading javadoc's line prefix off it would turn a line of prose into a statement that
     * parses. The verdict is meant to be exact, and that is a way for it not to be.
     *
     * The closing delimiter is removed if it is there and not assumed to be: a block comment in red
     * code runs to the end of the file, and the body is then everything after the opening.
     */
    private fun bodyOf(comment: PsiComment): String {
        val text = comment.text
        if (!text.startsWith(BLOCK_COMMENT_OPENING)) return text.removePrefix(LINE_COMMENT_OPENING)

        return text.removePrefix(BLOCK_COMMENT_OPENING)
            .removeSuffix(BLOCK_COMMENT_CLOSING)
            .lineSequence()
            .joinToString("\n") { it.trimStart().removePrefix(JAVADOC_LINE_PREFIX) }
    }

    /**
     * The symbols a javadoc block names through **resolvable references**: the `#member` half of
     * `{@link …}` and `@see`, and an `@param` target.
     *
     * **Javadoc is not uniformly prose, and this is the part that is not.** A `PsiDocTagValue`
     * resolves to a declared symbol exactly as an identifier does, so when a comment is kept these
     * rename through the PSI graph like any other reference — and when it is stripped they go with
     * it, because the engine drops everything a stripped comment covers.
     *
     * **The prose around them is never touched**, and that is the whole shape of the decision:
     * rewriting identifiers inside prose is regex by another name, and it under-delivers anyway,
     * since `merchant ledger` as two lowercase words never matches `merchantLedger`. What is reported
     * here is what resolved; a word that merely looks like a name resolves to nothing and is not
     * reported at all.
     *
     * A reference the identifier walk already covers is not reported a second time: the class half of
     * `{@link Payment#pay}` is an ordinary `PsiJavaCodeReferenceElement` with a `PsiIdentifier` under
     * it, and two occurrences over one range would be two edits over one range.
     */
    private fun docReferencesIn(
        project: Project,
        file: PsiFile,
        comment: PsiComment,
        fragment: Fragment,
    ): List<Occurrence> {
        if (comment !is PsiDocComment) return emptyList()

        return PsiTreeUtil.findChildrenOfType(comment, PsiDocTagValue::class.java)
            .flatMap { value -> value.references.asIterable() }
            .mapNotNull { reference ->
                val range = rangeOf(reference) ?: return@mapNotNull null
                if (file.findElementAt(range.startOffset) is PsiIdentifier) return@mapNotNull null

                val written = range.substring(file.text)
                SymbolOccurrence(
                    start = fragment.translate(range.startOffset),
                    end = fragment.translate(range.endOffset),
                    text = written,
                    symbol = evidenceOf(project, reference.resolve(), written),
                )
            }
            .sortedBy { it.start }
    }

    /**
     * What a literal is, in Java's grammar — **read off its type, never off its text.**
     *
     * The text says less than it appears to. Java translates unicode escapes before it tokenizes
     * anything, so `\u0022merchantRef\u0022` is a string literal whose text starts with a backslash;
     * and a literal in red code has no closing delimiter and no value at all. A rule that classified
     * by the opening character would call both of those a number, which is the one kind preserved
     * verbatim. That is a leak, and the type is the fact that closes it: the platform reports
     * `java.lang.String` for all three.
     *
     * The text is consulted for exactly one thing — telling a text block from a string — and that is
     * a question about *form*, which is the same thing the delimiters answer in [contentRangeOf].
     *
     * A literal whose type the platform cannot state at all is treated as a string, which is the
     * fail-closed direction: preserving a number is a nicety, and emitting an unrecognised literal
     * verbatim is the failure this action exists to prevent.
     */
    private fun kindOf(literal: PsiLiteralExpression): LiteralKind =
        when (literal.type?.canonicalText) {
            CHAR_TYPE -> LiteralKind.CHARACTER
            BOOLEAN_TYPE -> LiteralKind.BOOLEAN
            NULL_TYPE -> LiteralKind.NULL
            in NUMERIC_TYPES -> LiteralKind.NUMBER
            else -> if (literal.text.startsWith(TEXT_BLOCK_DELIMITER)) LiteralKind.TEXT_BLOCK else LiteralKind.STRING
        }

    /**
     * Where a literal's own text starts and ends inside its delimiters, relative to the literal.
     *
     * A literal with no delimiters — a number, a boolean, `null` — is all content, which is the
     * truth about it and reaches no rule that acts. **So is one whose delimiters are not written the
     * way the language usually writes them** — `\u0022` is the case that exists. The whole of it is
     * replaced, which emits a snippet that does not compile and leaks nothing. That is refusal-class
     * and therefore accepted, and the alternative — a second spelling of every delimiter, then a
     * third — is a list with nowhere to stop.
     *
     * **The closing delimiter is required to be there rather than assumed**, because a literal in
     * red code frequently has no closing anything: `"merchantRef` runs to the end of the line and is
     * a token like any other. Its content is then everything after the opening quote, and the whole
     * of it is replaced — the fail-closed direction, and the direction a rule that assumed a closing
     * quote would have got backwards by one character.
     */
    private fun contentRangeOf(kind: LiteralKind, text: String): TextRange {
        val delimiter = when (kind) {
            LiteralKind.TEXT_BLOCK -> TEXT_BLOCK_DELIMITER
            LiteralKind.STRING -> STRING_DELIMITER
            LiteralKind.CHARACTER -> CHARACTER_DELIMITER
            LiteralKind.NUMBER, LiteralKind.BOOLEAN, LiteralKind.NULL -> null
        }

        if (delimiter == null || !text.startsWith(delimiter)) return TextRange(0, text.length)

        // A text block's content starts after the line terminator that Java requires the opening
        // delimiter to be followed by, so that replacing it leaves a text block that is still one.
        val opening = if (kind == LiteralKind.TEXT_BLOCK) {
            text.indexOf('\n').takeIf { it >= 0 }?.plus(1) ?: delimiter.length
        } else {
            delimiter.length
        }

        val closing = if (text.length >= opening + delimiter.length && text.endsWith(delimiter)) {
            delimiter.length
        } else {
            0
        }

        return TextRange(opening, maxOf(opening, text.length - closing))
    }

    /**
     * The references a literal carries, in document order, each over the part of the literal it
     * names.
     *
     * These are contributed per-framework by `PsiReferenceContributor`, and **that is where the
     * editions part company**: core Java contributes class-name references — `Class.forName` is one
     * — while the JPA and Spring ones are Ultimate-only. So the same snippet anonymizes differently
     * in Community and Ultimate. **Accepted because it runs in the safe direction**: Community
     * anonymizes *more*, never less, so no install leaks more than another, and the degraded case
     * is a `"str1"` a reader can see rather than a wrong answer they cannot.
     *
     * Rejected: normalizing up, by resolving the common JPA and Spring attributes ourselves keyed by
     * annotation FQN plus attribute name. Legal, and unbounded: it puts this project in the business
     * of tracking framework annotation schemas.
     *
     * A reference that resolves to nothing is reported like any other, carrying `UNRESOLVED`
     * evidence. Dropping it here would be a judgment, and what the engine makes of it — nothing: it
     * covers no range and creates no gap, and the text decides — belongs where it can be tested
     * against a plan literal. There is usually one: the reflection contributor puts a reference over
     * the whole of `"com.acme.billing.Payment"` alongside the four that resolve.
     */
    private fun referencesIn(project: Project, literal: PsiLiteralExpression, fragment: Fragment): List<LiteralReference> =
        literal.references
            .mapNotNull { reference ->
                val range = rangeOf(reference) ?: return@mapNotNull null
                LiteralReference(
                    start = fragment.translate(range.startOffset),
                    end = fragment.translate(range.endOffset),
                    symbol = evidenceOf(project, reference.resolve(), range.substring(literal.containingFile.text)),
                )
            }
            .sortedBy { it.start }

    /**
     * Where [reference] sits in the file, or `null` when it claims a range its own element does not
     * contain — which nothing is expected to do, and which is not a shape to guess the meaning of.
     */
    private fun rangeOf(reference: PsiReference): TextRange? {
        val element = reference.element.textRange
        val range = reference.rangeInElement.shiftRight(element.startOffset)
        return range.takeIf { element.contains(it) && !it.isEmpty }
    }

    /**
     * What is known about the symbol [identifier] names.
     *
     * Four shapes: an identifier is part of a reference, which resolves — and a package segment is
     * such a reference like any other; or the name of a declaration, which *is* the symbol; or an
     * annotation attribute name, whose reference hangs off the enclosing `PsiNameValuePair`; or a
     * label named by a `break` or `continue`, where the reference hangs off the statement rather
     * than off the identifier's own parent and has to be asked for by name. Everything else —
     * keywords, punctuation — falls through.
     *
     * **Two of those four are shapes a plain identifier walk misses entirely**, and neither is an
     * optimisation. Without the label case, a jump to a label the selection declares reports as
     * *unresolved* — failing the name closed into `Unknown3` while its own declaration two lines up
     * rendered as `local1`, and putting a name the builder never asked the IDE about into the count
     * the balloon shows. Without the name-value-pair case, `action` in `@AuditLogged(action = …)`
     * is not part of any reference element and is not a declaration either, so it is copied through
     * verbatim — and annotation attribute names on project annotations are domain vocabulary.
     */
    private fun evidenceFor(project: Project, identifier: PsiIdentifier): SymbolEvidence {
        val parent = identifier.parent
        val declaration = when {
            parent is PsiJavaCodeReferenceElement -> validResolutionOf(parent)
            parent is PsiNameIdentifierOwner && parent.nameIdentifier === identifier -> parent
            parent is PsiNameValuePair && parent.nameIdentifier === identifier -> parent.reference?.resolve()
            parent is PsiBreakStatement && parent.labelIdentifier === identifier -> parent.reference?.resolve()
            parent is PsiContinueStatement && parent.labelIdentifier === identifier -> parent.reference?.resolve()
            else -> null
        }

        return evidenceOf(project, declaration, identifier.text)
    }

    /**
     * What is known about the symbol [declaration] names, for something written as [writtenName].
     *
     * Asked of an identifier and of a reference inside a literal alike, because a reference into a
     * literal names a symbol in exactly the way an identifier does — which is the whole of what
     * *renames in lockstep with the symbols those references name* means.
     */
    private fun evidenceOf(project: Project, declaration: PsiElement?, writtenName: String): SymbolEvidence {
        val declaredName = (declaration as? PsiNameIdentifierOwner)?.name ?: writtenName

        // A name that resolved to nothing is reported as unresolved rather than dropped, and the
        // engine fails it closed. Red or incomplete code is normal rather than exceptional, and the
        // snippet a developer is debugging is the likely one.
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
            key = "unresolved:" + writtenName,
            role = SymbolRole.TYPE,
            origin = SymbolOrigin.UNRESOLVED,
            declaredName = writtenName,
        )

        return SymbolEvidence(
            key = keyOf(symbol),
            role = roleOf(symbol),
            origin = originOf(project, symbol),
            declaredName = (symbol as? PsiNameIdentifierOwner)?.name ?: declaredName,
            qualifiedName = (symbol as? PsiQualifiedNamedElement)?.qualifiedName,
            packageName = packageNameOf(symbol),
            signature = (symbol as? PsiMethod)?.let { method ->
                method.parameterList.parameters.joinToString(",", "(", ")") { it.type.canonicalText }
            },
            overrideRoots = (symbol as? PsiMethod)?.let { overrideRootsOf(project, it) }.orEmpty(),
            accessor = (symbol as? PsiMethod)?.let(::accessorEvidenceOf),
            keyIsQualified = keyIsQualified(symbol),
        )
    }

    /**
     * The roots of [method]'s override chain: the methods it overrides that override nothing
     * themselves. Empty when it overrides nothing, which is most methods.
     *
     * `findDeepestSuperMethods()` rather than `findSuperMethods()`, because both rules that read
     * this are statements about the whole chain rather than about one link: a chain reaching a
     * framework type is name-constrained however many project classes sit between, and a chain keyed
     * by anything but its root splits an interface from its implementation.
     *
     * Reported as evidence and judged nowhere near here — the origins come back as the plain facts
     * they are, and whether a JDK root means *keep this name* is the engine's call.
     */
    private fun overrideRootsOf(project: Project, method: PsiMethod): List<OverrideRoot> =
        method.findDeepestSuperMethods().map {
            OverrideRoot(keyOf(it), originOf(project, it), packageNameOf(it), keyIsQualified(it))
        }

    /**
     * The field [method] reads or writes, when it is a JavaBeans accessor of one — and `null`
     * otherwise, which is the answer for the overwhelming majority of methods.
     *
     * **Matched on the name and the arity rather than on the body**, and that is the case this
     * exists for rather than a shortcut: with Lombok the accessor has no body, no declaration and no
     * `TextRange` at all. A body-reading match would find every accessor except the ones that need
     * it most.
     *
     * `getPropertyNameAndKind` is what keeps fluent accessors out. `merchantId()` is not a JavaBeans
     * accessor, so nothing is reported for it, and the engine leaves it an ordinary method —
     * deliberately, because nothing in Java forces a fluent accessor's name to track its field's.
     *
     * The field is looked up on the declaring class alone, never up the hierarchy: a superclass's
     * field is a symbol of that superclass, and an accessor deriving from a name it does not declare
     * would tie two placeholders together on a resemblance rather than on a rule.
     */
    private fun accessorEvidenceOf(method: PsiMethod): AccessorEvidence? {
        val owner = method.containingClass ?: return null
        val property = PropertyUtilBase.getPropertyNameAndKind(method.name) ?: return null
        val parameters = if (property.second == PropertyKind.SETTER) 1 else 0
        if (method.parameterList.parametersCount != parameters) return null

        val field = owner.findFieldByName(property.first, false) ?: return null
        return AccessorEvidence(keyOf(field), property.second.prefix, keyIsQualified(field))
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
     * `isValidResult` is accessibility, static-scope correctness and applicability together, and all
     * three earn their place: a call whose argument is red code has no applicable overload, and
     * failing that call closed alongside its argument is the decision *red code spreads, and
     * fail-closed spreads with it*.
     *
     * **A static import is the one shape with no call for applicability to be about**, and it was
     * failing closed for exactly that reason: `import static org.junit.Assert.assertTrue;` names two
     * overloads, so `advancedResolve` reports no single element and a third-party name came out as
     * `Unknown` *inside an import line* — a snippet that then reads as broken rather than as
     * anonymized. So that shape, and only that shape, is allowed to read its candidates directly.
     * Widening it to every reference would undo the rule above, which is the whole of why the
     * fallback is spelled with a type test.
     *
     * `advancedResolve(false)` is the same resolution `PsiCall.resolveMethod()` and
     * `resolveConstructor()` perform for calls — they read the call's own reference — so there is
     * nothing further to ask on their behalf. A constructor is handled a step later, by
     * [declaredSymbolOf], because its identifier names its class rather than the constructor.
     */
    private fun validResolutionOf(reference: PsiJavaCodeReferenceElement): PsiElement? {
        val result = reference.advancedResolve(false)
        val element = result.element
            ?: return if (reference is PsiImportStaticReferenceElement) oneSymbolOf(reference) else null

        // A package is not a member, and accessibility is a question about members. The platform
        // answers it for a package anyway, and answers it `false` for the root segment of a
        // qualified name — `java` in `java.util.List` — so gating on it here would fail the segment
        // closed and put `Unknown2.util.List` on the clipboard.
        if (element is PsiPackage) return element

        return element.takeIf { result.isValidResult }
    }

    /**
     * The one declared symbol a static import's candidates name, or `null` when they name more
     * than one.
     *
     * Overloads are the case this exists for and the only case it admits: they share a name and a
     * declaring class, so they share a [keyOf] and a placeholder, and picking between them is not a
     * choice that can be made wrongly. Anything else — two members of the same name reached through
     * an on-demand import, a reference the IDE genuinely cannot pin down — comes back `null` and
     * fails closed, because there the candidates are different symbols and the first one is a guess.
     *
     * Accessibility and static-scope correctness are still asked. Applicability is not, and cannot
     * be: an import names a member, never a call.
     */
    private fun oneSymbolOf(reference: PsiImportStaticReferenceElement): PsiElement? {
        val methods = reference.multiResolve(false)
            .filter { it.isAccessible && it.isStaticsScopeCorrect }
            .mapNotNull { it.element as? PsiMethod }
        val first = methods.firstOrNull() ?: return null

        val owner = first.containingClass?.qualifiedName ?: return null
        val agree = methods.all { it.name == first.name && it.containingClass?.qualifiedName == owner }
        return first.takeIf { agree }
    }

    /**
     * The symbol an identifier actually names, which is not always the element resolution returns.
     *
     * **Two of the five forced-sharing rules live here rather than in the engine, and that is not an
     * exception to the rule that judgments do not cross this seam** — neither is a judgment. A
     * constructor's identifier *is* its class's name and a record accessor's identifier *is* its
     * component's, in Java's grammar, so reporting anything else would be reporting the wrong
     * symbol. What the engine then does with two occurrences of one symbol is the engine's business,
     * and it does the same thing it does for any other two.
     */
    private fun declaredSymbolOf(declaration: PsiElement): PsiElement = when {
        declaration !is PsiMethod -> declaration

        // Rule 4 — a constructor's identifier is its class's name.
        declaration.isConstructor -> declaration.containingClass ?: declaration

        // Rule 5 — a record accessor's identifier is its component's name. `merchantRef()` is not a
        // method that happens to be named after a field; the component, the implicit field and the
        // accessor are one declared symbol with three PSI faces, and Java forces all three to agree.
        // Naming the component as the symbol is what makes the accessor render as `field1` rather
        // than as rule 3's `getField1()` — records carry no `get` prefix, so the derivation that
        // keeps a Lombok accessor coherent would be actively wrong here.
        else -> JavaPsiRecordUtil.getRecordComponentForAccessor(declaration) ?: declaration
    }

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
        // A package declares nothing and lives in no file, so the question is asked of the
        // directories behind it instead. Left to fall through, the light-element branch below would
        // read the missing file as *fail closed* and rename `util` in `java.util.List`.
        if (symbol is PsiPackage) return packageOriginOf(project, symbol)

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
     * Where a package lives, asked of **every directory the package has**, because that is the only
     * file-level thing a `PsiPackage` has: it is not declared anywhere, and
     * [PsiUtilCore.getVirtualFile] answers `null` for it.
     *
     * Any directory in project content makes the whole package the project's, and that is the
     * fail-closed direction rather than a tie-break. A package split across a source root and a jar
     * — the project's own `com.acme` alongside a shaded `com.acme` from a dependency — is a package
     * the project contributes domain-named subpackages and classes to, and the segment naming it is
     * the project's word.
     *
     * A package with no directories at all — a package prefix, and nothing else in practice — is
     * reported as the project's for the same reason: an unclassifiable name is not one the spine
     * rule may preserve. **Not [SymbolOrigin.UNRESOLVED]**, which would be a false claim rather than
     * a safe one: the reference resolved, and *the IDE could not resolve this* is a sentence the
     * balloon shows a user and the preview lets them act on.
     */
    private fun packageOriginOf(project: Project, symbol: PsiPackage): SymbolOrigin {
        val index = ProjectFileIndex.getInstance(project)
        val directories = symbol.directories.map { it.virtualFile }
        return when {
            directories.any { index.isInContent(it) } -> SymbolOrigin.IN_CONTENT
            directories.isEmpty() -> SymbolOrigin.IN_CONTENT
            directories.any { file -> index.getOrderEntriesForFile(file).any { it is JdkOrderEntry } } ->
                SymbolOrigin.JDK
            else -> SymbolOrigin.LIBRARY
        }
    }

    /**
     * **The package a symbol belongs to** — `com.acme.billing` for the class
     * `com.acme.billing.Payment`, for its `merchantRef` field, and for the package segment written
     * `billing`, which belongs to itself.
     *
     * A fact about where the declaration sits, reported like every other fact: whether a package
     * prefix makes a library symbol the company's own is a policy, and it is applied in the engine
     * where a test can reach it without an IDE.
     *
     * Read off the **top-level** class's qualified name rather than off the symbol's own, because a
     * nested class's qualified name carries its owners — `com.acme.billing.Payment.Status` would
     * otherwise report a package of `com.acme.billing.Payment`, and a prefix rule reading it would
     * be matching a class name against a package one. The containing file is the fallback, which is
     * what answers for an anonymous or local class and for a member of one.
     */
    private fun packageNameOf(symbol: PsiElement): String? {
        // A package is not declared in a package; it *is* one. Every rule that reads this field is
        // asking which package a name is part of, and a package is part of itself.
        if (symbol is PsiPackage) return symbol.qualifiedName.takeIf { it.isNotEmpty() }

        val owner = symbol as? PsiClass ?: (symbol as? PsiMember)?.containingClass
        val topLevel = owner?.let { generateSequence(it) { nested -> nested.containingClass }.last() }
        topLevel?.qualifiedName?.substringBeforeLast('.', "")?.takeIf { it.isNotEmpty() }?.let { return it }

        return (symbol.containingFile as? PsiJavaFile)?.packageName?.takeIf { it.isNotEmpty() }
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
     *
     * **Anonymous and local class members inherit that fallback through their owner**, and that is
     * the point of routing every member key through [memberKeyOf]. `PsiClass.getQualifiedName()` is
     * `null` inside one, so an owner keyed by name alone collapsed the `state` fields of two
     * different anonymous classes onto one placeholder — two unrelated symbols rendered as one name,
     * which is precisely what the injectivity invariant forbids.
     *
     * **A Lombok light member is keyed by `(owner FQN, kind, name)` and nothing else**, which is
     * what these three branches already say: a light member has no `TextRange` to anchor on, so a
     * key that reached for one would have nothing to read.
     */
    private fun keyOf(symbol: PsiElement): String = when (symbol) {
        // A package is keyed by the whole qualified name and not by the segment that ends it, which
        // is what makes `com.acme.billing` and `org.acme.billing` two symbols and what makes two
        // types in one package share a placeholder for it.
        is PsiPackage -> "package:" + symbol.qualifiedName

        is PsiClass -> "class:" + (symbol.qualifiedName ?: anchorOf(symbol))
        is PsiMethod -> memberKeyOf("method", symbol.containingClass, symbol.name)
        is PsiField -> memberKeyOf("field", symbol.containingClass, symbol.name)

        // Keyed as the field it compiles to, which is rule 5 stated as identity: one declared symbol
        // wearing three PSI faces reaches one key from whichever face the walk arrives at.
        is PsiRecordComponent -> memberKeyOf("field", symbol.containingClass, symbol.name)

        else -> "local:" + anchorOf(symbol)
    }

    /**
     * A member's key: what kind of member it is, whose it is, and what it is called.
     *
     * The owner's key is [keyOf] again rather than a qualified name, which is the whole of what
     * makes an anonymous class's members work: the owner has no qualified name, so it falls through
     * to an anchor, and the member's key inherits that. A `null` owner is the light member with no
     * class at all, and it gets a name rather than an exception.
     */
    private fun memberKeyOf(kind: String, owner: PsiClass?, name: String): String =
        kind + ":" + (owner?.let(::keyOf) ?: "<none>") + "#" + name

    /**
     * **Whether [keyOf] derived this symbol's key from a fully-qualified name**, which is the fact the
     * engine reads when it decides what may be written down. See [SymbolEvidence.keyIsQualified].
     *
     * It answers the branches of [keyOf] one for one, and that is the point of writing it as a `when`
     * over the same shapes rather than as a test on the resulting string: the two are one decision
     * made twice, and a rule that parsed the key back out would go quietly wrong the day a key format
     * changed. A member inherits the answer from its owner, exactly as its key inherits the owner's,
     * so an anonymous class's field is positional however ordinary the field itself looks.
     *
     * Everything not named here — a local, a parameter, a label, a type parameter — is keyed on where
     * it is written, and so is a class with no qualified name and every member of one. A name that
     * did not resolve never reaches this at all: it is keyed on its own text, and [evidenceOf] returns
     * before it gets here.
     */
    private fun keyIsQualified(symbol: PsiElement): Boolean = when (symbol) {
        is PsiPackage -> true
        is PsiClass -> symbol.qualifiedName != null
        is PsiMethod -> symbol.containingClass?.let(::keyIsQualified) == true
        is PsiField -> symbol.containingClass?.let(::keyIsQualified) == true
        is PsiRecordComponent -> symbol.containingClass?.let(::keyIsQualified) == true
        else -> false
    }

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
        // Before PsiClass, which a type parameter is one of. Its own kind: `<T>` carries no domain
        // and `<REQ extends MerchantRequest>` does, and preserving by name length is inspecting the
        // text — so both are anonymized, and the prefix is what says which was which.
        is PsiTypeParameter -> SymbolRole.TYPE_PARAMETER
        // An annotation type before a plain one: `@interface` is a class declaration and reads as
        // nothing of the kind.
        is PsiClass -> if (symbol.isAnnotationType) SymbolRole.ANNOTATION else SymbolRole.TYPE

        // One segment of a package name. Never the whole name: the engine renames these one at a
        // time so that same-package and different-package survive the rename.
        is PsiPackage -> SymbolRole.PACKAGE

        // An annotation type's member is a method in the bytecode and an attribute everywhere it is
        // written, and what is written is what a reader has to map back. Asked of the declaring
        // type rather than of `PsiAnnotationMethod`, which every *compiled* method implements.
        is PsiMethod -> if (symbol.containingClass?.isAnnotationType == true) {
            SymbolRole.ATTRIBUTE
        } else {
            SymbolRole.METHOD
        }

        is PsiField -> SymbolRole.FIELD

        // A record component is compiled to a private final field of the same name, and Java forces
        // the component, that field and the accessor to agree. Naming it a field here is the reading
        // that stays true when the ticket for forced sharing collapses all three onto one symbol.
        is PsiRecordComponent -> SymbolRole.FIELD

        is PsiParameter -> SymbolRole.PARAMETER
        is PsiLocalVariable -> SymbolRole.LOCAL

        // A label is not a variable, and `break local7;` reads as though it were.
        is PsiLabeledStatement -> SymbolRole.LABEL
        else -> SymbolRole.FIELD
    }

    /**
     * The analysed file's root package — `com.acme` out of `com.acme.web.PaymentController`.
     *
     * A *fact about the file*, and the one the internal-library rule is derived from: a library
     * symbol under this prefix is the company's own code arriving as a jar. Facts belong on the
     * plan — the builder must not pre-judge, so the prefix match itself stays in the engine where it
     * is testable against a plan literal with no IDE involved.
     */
    private fun rootPackageOf(file: PsiFile): String? =
        (file as? PsiJavaFile)?.packageName
            ?.takeIf { it.isNotEmpty() }
            ?.split('.')
            ?.let { segments -> segments.take(2).joinToString(".") }

    /** A separator that cannot merge two fragments into one token, which is all it has to be. */
    private const val FRAGMENT_SEPARATOR = "\n"

    // What opens and closes a comment, and the asterisk a block comment's continuation lines are
    // written with. Read only to find the body a parser is handed — never to decide anything about
    // what the body says.
    private const val LINE_COMMENT_OPENING = "//"
    private const val BLOCK_COMMENT_OPENING = "/*"
    private const val BLOCK_COMMENT_CLOSING = "*/"
    private const val JAVADOC_LINE_PREFIX = "*"

    /** What opens and closes a text block, and the one thing that tells one from a string literal. */
    private const val TEXT_BLOCK_DELIMITER = "\"\"\""

    private const val STRING_DELIMITER = "\""

    private const val CHARACTER_DELIMITER = "'"

    // The types a literal expression reports, which is what [kindOf] reads it as. Anything that is
    // not one of these is a string of some spelling — including the two shapes that report no value
    // at all: a literal in red code with no closing delimiter, and one whose delimiters are written
    // as unicode escapes.
    private const val CHAR_TYPE = "char"
    private const val BOOLEAN_TYPE = "boolean"
    private const val NULL_TYPE = "null"
    private val NUMERIC_TYPES = setOf("byte", "short", "int", "long", "float", "double")
}

/** One analysed range, and where its text starts in the plan. */
private class Fragment(val range: TextRange, val planStart: Int) {
    fun translate(fileOffset: Int): Int = planStart + (fileOffset - range.startOffset)
}
