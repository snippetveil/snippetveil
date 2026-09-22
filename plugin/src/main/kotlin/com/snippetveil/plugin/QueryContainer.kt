package com.snippetveil.plugin

import com.intellij.lang.Language
import com.intellij.lang.LanguageNamesValidation
import com.intellij.lang.LanguageParserDefinitions
import com.intellij.lang.ParserDefinition
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiPackage
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtilCore
import com.snippetveil.core.CommentVerdict
import com.snippetveil.core.LiteralKind
import com.snippetveil.core.SourceLanguage
import com.snippetveil.core.SymbolEvidence
import com.snippetveil.core.SymbolOrigin
import com.snippetveil.core.SymbolRole

/**
 * **A query written in a persistence query language** — JPQL, HQL or Spring Data QL — read name by
 * name, so that each name takes **the placeholder that name already has** elsewhere in the snippet.
 *
 * `@Query("SELECT c FROM Customer c WHERE c.merchantRef = :ref")` over a project entity comes out as
 * `SELECT local2 FROM Type1 local2 WHERE local2.field3 = :local4`, and `Type1` is the placeholder the
 * entity class two lines up got, because it is the same symbol: an entity name resolves to the entity
 * class and a persistent property to its field, so they key through [JavaPlanBuilder.evidenceOf] —
 * the existing key rule — and nothing new is minted for them. This half never touches `table`, `col`
 * or `schema`.
 *
 * **The trigger is binding, not position.** A fragment decomposes when **every identifier position in
 * it resolves** — to a project symbol, to a library symbol, or to a declaration the fragment itself
 * makes. If any position does not, the answer is `null` and the whole literal is one redacted literal,
 * exactly as before. All or nothing per fragment; never partial. Nothing here guesses from an
 * annotation's name or a string's shape: binding is a fact the platform reports, through the
 * references the query language's own plugin contributes.
 *
 * **What does not happen, stated so it is not built:** no data source is consulted, nothing resolves
 * outside the fragment, and no name in a query ever becomes an unknown-namespace placeholder. A
 * position either resolves or its fragment falls back, so no [SymbolOrigin.UNRESOLVED] evidence is
 * ever produced here.
 *
 * **Read through the platform's own interfaces, and never through the query plugins' types.** Those
 * plugins ship in some editions and not in others, and nothing shipped here may link against them.
 * What this reads — a token's type against the language's [ParserDefinition], a keyword through the
 * language's names validator, a reference and what it resolves to — is what every language offers.
 */
internal object QueryContainer : InjectedContainer {

    override fun read(fragment: InjectedFragment): InjectedReading? {
        val file = fragment.file
        val language = file.language
        if (language.id !in QUERY_LANGUAGES) return null

        // A query the language's own parser could not read is one whose positions nothing can vouch
        // for: an error element's words are neither keywords nor names anything resolved.
        if (PsiTreeUtil.hasErrorElements(file)) return null

        val grammar = LanguageParserDefinitions.INSTANCE.forLanguage(language) ?: return null
        val host = fragment.hosts?.firstOrNull() ?: return null
        val reader = Reader(file.project, fragment, grammar, host)

        var leaf: PsiElement? = PsiTreeUtil.firstChild(file)
        while (leaf != null) {
            if (!reader.read(leaf)) return null
            leaf = PsiTreeUtil.nextLeaf(leaf)
        }
        return InjectedReading(reader.names, reader.literals, reader.comments)
    }

    /**
     * One pass over one fragment's tokens, each answered in the order the rules have to be asked in.
     * [read] answers `false` for a token nothing can vouch for, which falls the fragment back.
     */
    private class Reader(
        val project: Project,
        val fragment: InjectedFragment,
        val grammar: ParserDefinition,
        val host: PsiElement,
    ) {
        val names = mutableListOf<InjectedName>()
        val literals = mutableListOf<InjectedLiteral>()
        val comments = mutableListOf<InjectedComment>()

        private val language: Language = fragment.file.language

        /**
         * **What one token is, in this order**:
         *
         *  1. Whitespace, which carries nothing.
         *  2. A comment, which is reported as one — see [verdictOf].
         *  3. **A claimed position** — a token a reference sits exactly over. Claimed before anything
         *     is read off the token itself, because the reference is what knows the token is a name:
         *     a property called `type` or `value` is a name wherever the lexer happens to call those
         *     words keywords.
         *  4. A keyword, as the language's own names validator says.
         *  5. A literal of the language, as its [ParserDefinition] says. **A delimited one is a string
         *     and is always reported**, whatever it holds — `'555-12-3456'` bears no letter and is still
         *     the most directly sensitive text in the query. An undelimited one bearing no letter is a
         *     number, which is shape and is kept; an undelimited one bearing a letter is reported whole,
         *     the fail-closed direction.
         *  6. **A name the fragment declares** — an alias — which no reference sits over, because it is
         *     the thing the references point at.
         *  7. What bears no letter or digit — punctuation and operators — which carries no word.
         *
         * Anything else is a word nothing vouched for, and the fragment falls back.
         */
        fun read(leaf: PsiElement): Boolean {
            val type = PsiUtilCore.getElementType(leaf)
            if (leaf is PsiWhiteSpace || grammar.whitespaceTokens.contains(type)) return true

            if (leaf is PsiComment || grammar.commentTokens.contains(type)) {
                comments += InjectedComment(leaf.textRange, verdictOf(leaf.text), LANGUAGE)
                return true
            }

            val over = referencesOver(leaf)
            if (over.isNotEmpty()) return claimed(leaf, over)

            if (isKeyword(leaf.text)) return true

            if (grammar.stringLiteralElements.contains(type)) {
                if (!isDelimited(leaf.text) && leaf.text.none(Char::isLetter)) return true
                literals += InjectedLiteral(leaf.textRange, contentOf(leaf), LiteralKind.STRING, LANGUAGE)
                return true
            }

            if (leaf.text.any(Char::isLetter)) {
                val declaration = declarationNamedBy(leaf) ?: return false
                names += InjectedName(leaf.textRange, leaf.textRange, fragmentLocal(declaration, leaf.text), LANGUAGE)
                return true
            }

            return leaf.text.none(Char::isLetterOrDigit)
        }

        /**
         * A token at least one reference sits over. A reference sitting over it **exactly** is what
         * makes it a name, and every such reference has to resolve to one symbol; one that only
         * reaches across it from a wider range says nothing about which symbol this token is, so a
         * word under such a reference alone is a word nothing vouched for.
         *
         * A claimed token bearing no letter — the `1` of `?1` — has no name to write a placeholder
         * into, and is kept once it has resolved.
         */
        private fun claimed(leaf: PsiElement, over: List<Pair<TextRange, PsiReference>>): Boolean {
            val exact = over.filter { (range, _) -> range == leaf.textRange }.map { it.second }
            if (exact.isEmpty()) return leaf.text.none(Char::isLetter)

            val bindings = exact.map { bindingOf(it, leaf.text) ?: return false }
            val symbol = bindings.first()
            if (bindings.any { it.key != symbol.key }) return false

            if (leaf.text.any(Char::isLetter)) names += InjectedName(leaf.textRange, leaf.textRange, symbol, LANGUAGE)
            return true
        }

        /**
         * **What a reference binds to**, or `null` when it has not resolved in the sense the trigger
         * means — which falls the fragment back.
         *
         *  - A declaration **the fragment itself makes** — an alias, a bind parameter — is
         *    fragment-local: see [fragmentLocal].
         *  - A Java declaration — a class, a member, a package — is the symbol, keyed by the existing
         *    rule, **wherever it lives**: a library class named in a query is preserved exactly as the
         *    same name in Java is.
         *  - Anything else is followed **one navigation hop** to its declaration, which is how a
         *    persistent property reaches the field it is. One hop, not a chain: what it lands on is
         *    taken as it is, and it has to be a **project** declaration. Landing anywhere else — a
         *    library class's field, or not a declaration at all — is a position that has not resolved.
         *
         * **Either way the symbol has to be named what the query calls it.** A property of an entity
         * whose persistence is on its getters can land on `getMerchantRef()`; its placeholder would then
         * be the getter's, and a reply reversed through it would restore `getMerchantRef` where the query
         * said `merchantRef`. A symbol that answers to another name is not the name at this position, so
         * the position has not resolved.
         */
        private fun bindingOf(reference: PsiReference, written: String): SymbolEvidence? {
            val target = reference.resolve() ?: return null
            if (target.containingFile == fragment.file) return fragmentLocal(target, written)
            if (isJavaDeclaration(target)) return javaSymbol(target, written)

            val landed = target.navigationElement
            if (landed === target || !isJavaDeclaration(landed)) return null
            return javaSymbol(landed, written)?.takeIf { it.origin == SymbolOrigin.IN_CONTENT }
        }

        /** [declaration]'s evidence by the existing key rule, when the symbol is named [written]. */
        private fun javaSymbol(declaration: PsiElement, written: String): SymbolEvidence? =
            JavaPlanBuilder.evidenceOf(project, declaration, written).takeIf { it.declaredName == written }

        /**
         * **A name the fragment declares, as the query says it**: a [LOCAL][SymbolRole.LOCAL], keyed by
         * where the declaration sits in this fragment and never written down.
         *
         * A bind parameter takes `local` and never `param`: `:ref` is a name the query itself
         * introduces and nothing outside the query declares, and `param` asserts a callable's
         * parameter, which it is not. An alias is fragment-local for the same reason.
         *
         * The declaration is identified through its navigation element, because that is the one
         * element every reference to it and the declaration's own name agree on: the platform may hand
         * a reference a synthetic target standing for the declaration, and that target navigates to it.
         */
        private fun fragmentLocal(declaration: PsiElement, written: String): SymbolEvidence {
            val anchor = declaration.navigationElement.textRange.startOffset
            return SymbolEvidence(
                key = SymbolKeys.fragmentLocalKeyOf(host, anchor),
                role = SymbolRole.LOCAL,
                origin = SymbolFacts.originOfFile(project, PsiUtilCore.getVirtualFile(host)),
                declaredName = (declaration as? PsiNamedElement)?.name ?: written,
            )
        }

        /**
         * The declaration [leaf] is the name of, when the fragment declares one there: the nearest
         * named element above it, inside the fragment, whose name is exactly this token.
         */
        private fun declarationNamedBy(leaf: PsiElement): PsiElement? {
            var at = leaf.parent
            while (at != null && at != fragment.file) {
                if (at is PsiNamedElement && at.name == leaf.text) {
                    val identifier = (at as? PsiNameIdentifierOwner)?.nameIdentifier
                    if (identifier == null || identifier.textRange.contains(leaf.textRange)) return at
                }
                at = at.parent
            }
            return null
        }

        /**
         * Every reference sitting over [leaf], with its range in the fragment — asked of the leaf and of
         * every element above it inside the fragment, because a reference hangs off whichever element
         * the language chose.
         */
        private fun referencesOver(leaf: PsiElement): List<Pair<TextRange, PsiReference>> {
            val over = mutableListOf<Pair<TextRange, PsiReference>>()
            var at: PsiElement? = leaf
            while (at != null && at != fragment.file) {
                for (reference in at.references) {
                    val range = reference.rangeInElement.shiftRight(at.textRange.startOffset)
                    if (range.contains(leaf.textRange)) over += range to reference
                }
                at = at.parent
            }
            return over
        }

        private fun isKeyword(text: String): Boolean =
            LanguageNamesValidation.INSTANCE.forLanguage(language).isKeyword(text, project)

        /**
         * **What the fragment's own language makes of a comment's body**: code, or not. The same
         * question the Java and Kotlin walks ask of their comments, put to the parser of the language
         * the comment is written in.
         */
        private fun verdictOf(comment: String): CommentVerdict {
            val body = comment.removePrefix(BLOCK_COMMENT_OPENING).removeSuffix(BLOCK_COMMENT_CLOSING)
            if (body.isBlank()) return CommentVerdict.PROSE
            val parsed = PsiFileFactory.getInstance(project).createFileFromText("comment", language, body)
            return if (PsiTreeUtil.findChildOfType(parsed, PsiErrorElement::class.java) == null) {
                CommentVerdict.CODE
            } else {
                CommentVerdict.PROSE
            }
        }
    }

    /**
     * Where a string's own text lies inside its delimiters — read off the delimiters, which is reading
     * *form*, exactly as the Java walk reads its own. A token whose two ends are not the same
     * delimiter is all content, which replaces the whole of it: the fail-closed direction.
     */
    private fun contentOf(literal: PsiElement): TextRange {
        val range = literal.textRange
        return if (isDelimited(literal.text)) TextRange(range.startOffset + 1, range.endOffset - 1) else range
    }

    /** Whether [text] opens and closes on the same delimiter — a quote, in every query language read here. */
    private fun isDelimited(text: String): Boolean =
        text.length >= 2 && text.first() == text.last() && !text.first().isLetterOrDigit()

    /** A class, a member or a package: what [JavaPlanBuilder.evidenceOf] keys by the existing rule. */
    private fun isJavaDeclaration(element: PsiElement): Boolean = element is PsiPackage || element is PsiMember

    /**
     * **The languages this container reads**, by the ids their plugins register them under — JPQL,
     * HQL and Spring Data QL. A fragment in any other language is not a query this container can say
     * anything about, and falls back.
     *
     * Read by the corpus instrument's query half as well, so that *which fragments are queries at all*
     * — the denominator every rate it reports is over — is this list rather than a copy of it.
     */
    internal val QUERY_LANGUAGES = setOf("JPAQL", "HQL", "SpringDataQL")

    /**
     * The language a query's tokens are tagged with in the plan. A query names Java symbols, and each
     * is spelled exactly as the same symbol is in Java — the one rendering rule that depends on the
     * tag is Kotlin's, which no query name is written in.
     */
    private val LANGUAGE = SourceLanguage.JAVA

    private const val BLOCK_COMMENT_OPENING = "/*"
    private const val BLOCK_COMMENT_CLOSING = "*/"
}
