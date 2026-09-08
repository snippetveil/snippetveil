package com.snippetveil.plugin

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiBreakStatement
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiContinueStatement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementFactory
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiImportStaticReferenceElement
import com.intellij.psi.PsiJavaCodeReferenceElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiNameValuePair
import com.intellij.psi.PsiPackage
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiRecordComponent
import com.intellij.psi.javadoc.PsiDocComment
import com.intellij.psi.javadoc.PsiDocTagValue
import com.intellij.psi.util.JavaPsiRecordUtil
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtilCore
import com.intellij.util.IncorrectOperationException
import com.snippetveil.core.CommentOccurrence
import com.snippetveil.core.CommentVerdict
import com.snippetveil.core.LiteralKind
import com.snippetveil.core.LiteralOccurrence
import com.snippetveil.core.LiteralReference
import com.snippetveil.core.Occurrence
import com.snippetveil.core.SnippetPlan
import com.snippetveil.core.SourceLanguage
import com.snippetveil.core.SymbolEvidence
import com.snippetveil.core.SymbolOccurrence

/**
 * Describes a Java snippet truthfully, and decides nothing about it.
 *
 * Runs inside a non-blocking read action on a background thread. It allocates no placeholder,
 * mutates no PSI and touches no ledger, which is what makes a cancel-and-restart free: the platform
 * can throw this walk away half-finished as often as a write action happens, and the only cost is
 * the walk itself.
 *
 * **Two of its three halves are not here, and that is deliberate.** Which text is analysed and where
 * it lands in the plan is in `SnippetFragments.kt`; what the platform says about one declared symbol
 * — its key, its role, where its declaring file lives — is in [SymbolFacts]. Both are shared with
 * `KotlinPlanBuilder`, because a Kotlin declaration reaches the ledger as the light element a Java
 * reference to it resolves to: the two walks have to agree on a key character for character, and a
 * second copy of these formats is that agreement waiting to be edited out of existence. What is left
 * here is the part that is genuinely about Java's grammar.
 */
internal object JavaPlanBuilder : PlanBuilder {

    override fun build(request: SnippetRequest): SnippetPlan {
        val file = request.file
        val snapped = request.selections.map {
            TextRange(snapStart(file, it.startOffset, ::tokenOf), snapEnd(file, it.endOffset, ::tokenOf))
        }
        val fragments = fragmentsOf(file, snapped)

        val text = fragments.joinToString(FRAGMENT_SEPARATOR) { file.text.substring(it.range.startOffset, it.range.endOffset) }
        val occurrences = (symbolsIn(request.project, file, fragments) + literalsAndCommentsIn(request.project, file, fragments))
            .sortedBy { it.start }

        return SnippetPlan(
            text,
            occurrences,
            rootPackageOf(file),
            // A range moved iff the two lists differ, which is the claim itself rather than a
            // proxy for it. Snapping is the only thing between them, and the whole-file case
            // reaches neither list: no selection was cut, so nothing was extended.
            selectionExpanded = snapped != request.selections,
        )
    }

    /**
     * **What one token is, in Java: the leaf itself.** See [TokenOf].
     *
     * Java's token classes are the identifier, the string literal and the text block, and the
     * platform gives each of them a single leaf — a text block included, delimiters, newlines and
     * all. So there is nothing to widen, and a snap onto a leaf boundary is already a snap onto a
     * token boundary. Stated rather than defaulted, because *which classes those are* is a statement
     * about a language, and the language's own walk is where such a statement belongs.
     */
    private fun tokenOf(leaf: PsiElement): PsiElement = leaf

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
                        language = LANGUAGE,
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
                    listOf(CommentOccurrence(start, end, verdictOf(project, element), LANGUAGE)) +
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
                            language = LANGUAGE,
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
                    language = LANGUAGE,
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
        // engine fails it closed. Why, and what it is keyed on, is [SymbolFacts.unresolvedEvidence].
        val symbol = declaration?.let(::declaredSymbolOf) ?: return SymbolFacts.unresolvedEvidence(writtenName)

        return SymbolFacts.evidenceOf(project, symbol, declaredName)
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
     * declaring class, so they share a [SymbolKeys.keyOf] and a placeholder, and picking between them is not a
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
     * constructor's identifier *is* its class's name, a record accessor's identifier *is* its
     * component's, and so is a compact constructor's implicit parameter, in Java's grammar — so
     * reporting anything else would be reporting the wrong symbol. What the engine then does with
     * two occurrences of one symbol is the engine's business, and it does the same thing it does for
     * any other two.
     *
     * Three branches for two rules, because rule 5 arrives on more than one face. They read 5, 4, 5
     * rather than in order, and **the order is forced rather than chosen**: a parameter is not a
     * `PsiMethod`, so the `declaration !is PsiMethod` line would return it untouched if it came
     * first.
     */
    private fun declaredSymbolOf(declaration: PsiElement): PsiElement = when {
        // Rule 5 again, on a fourth face of the same symbol. See [compactConstructorComponentOf].
        declaration is PsiParameter -> compactConstructorComponentOf(declaration) ?: declaration

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
     * The record component [parameter] *is*, when it is a **compact** constructor's implicit
     * parameter — and `null` for every other parameter in Java, which is nearly all of them.
     *
     * **A fourth face of rule 5's one symbol, not a sixth forced-sharing rule.** A compact
     * constructor declares no parameter list; the JLS gives it one, named after the components. So
     * `merchantRef` in `Payment { if (merchantRef == null) … }` names the component in exactly the
     * way the accessor's identifier does, and reporting a parameter of its own emitted `param3`
     * inside a record that declares no such thing — a name the reader has to account for and the
     * output cannot explain, which is the *plausible* artifact rather than the obvious one.
     *
     * **The cut is compact versus explicit, and it is load-bearing.** An *explicit* canonical
     * constructor's parameter names are the author's to choose — nothing forces `Payment(String ref)`
     * to say `merchantRef` — so its parameters are ordinary independent symbols and must keep
     * renaming freely. Sharing them with the component would force an agreement Java does not, which
     * is the mirror-image error. Hence [JavaPsiRecordUtil.isCompactConstructor] as a gate rather than
     * as a shortcut, and hence that it is asked of the parameter's own declaration scope.
     *
     * **The component is then found by name, and inside a compact constructor that is identity
     * rather than resemblance**: the parameters are implicit, so *named after the components* is
     * what they are, not something they happen to be. The same lookup by name is what
     * [JavaPsiRecordUtil.getComponentForCanonicalConstructorParameter] does — it cannot be called
     * here, because it reaches the record class with [PsiTreeUtil.getParentOfType] and a compact
     * constructor's parameter is a light element whose `parent` is `null`, so it answers `null` for
     * exactly the shape this exists for. It is also no substitute for the gate: it asks nothing about
     * canonicality, and would match an explicit constructor's parameter — or any method parameter in
     * a record — that happened to be spelled like a component.
     *
     * **The exact identity link exists and is deliberately not used.** The platform synthesizes these
     * parameters from the components, and `LightCompactConstructorParameter` keeps the very
     * component it was made from — reachable as `LightRecordMember.getRecordComponent()`. It lives in
     * `com.intellij.psi.impl.light`, which nothing else in this plugin reaches into, and the name
     * lookup answers identically on every shape Java can spell. The one input that could part them
     * is red code declaring two components of one name, and there the two are already one symbol to
     * this walk: [SymbolKeys.keyOf] keys a component by its class and its name, so they shared a placeholder
     * before this function existed and still do.
     *
     * **The lookup is total by construction, and the caller's `?:` is not a described behaviour.** A
     * compact constructor with a parameter matching no component is not a shape source can produce,
     * valid or red. The fallback is there because this walk may neither throw nor drop an occurrence
     * — a name that reached neither would be copied through verbatim, which is the one failure this
     * action exists to prevent — and a placeholder of its own is the safe direction. It is
     * deliberately not asserted anywhere: a test would have to fake a PSI shape Java has no spelling
     * for.
     *
     * Identity rather than policy, like the two faces above it. Nothing in `:core` decides anything
     * new, because the engine already renders two occurrences of one symbol identically.
     */
    private fun compactConstructorComponentOf(parameter: PsiParameter): PsiRecordComponent? {
        val constructor = parameter.declarationScope as? PsiMethod ?: return null
        if (!JavaPsiRecordUtil.isCompactConstructor(constructor)) return null
        return constructor.containingClass?.recordComponents?.firstOrNull { it.name == parameter.name }
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

    /**
     * **The language every occurrence this walk reports is written in**, which is the one thing a
     * Java walk can state about a token without looking at it. See
     * [com.snippetveil.core.Occurrence.language].
     */
    private val LANGUAGE = SourceLanguage.JAVA

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
