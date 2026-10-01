package com.snippetveil.plugin

import com.intellij.lang.LanguageNamesValidation
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil

/*
 * **Whether a comment's parsed body is code** — the half of a comment's verdict that is about the
 * tree a parser handed back rather than about which language's parser it was.
 *
 * > A comment whose body parses as code is anonymized and kept, on the fast path, with no tick. A
 * > comment whose body does not parse is stripped.
 *
 * Each language parses a body its own way and at its own position — see `JavaCommentBodies.kt` and
 * `kotlin/KotlinCommentBodies.kt`. What counts as *parsed* is decided here, once, for both, so that a
 * rule stated language-neutrally is one function rather than two that agree today.
 */

/**
 * **Whether [parsed] holds code over [body]** — the range of [parsed] a comment's body occupies,
 * which is the whole of a fragment parsed from the body alone and a slice of a file parsed with the
 * body in place.
 *
 * Not code means one of three things, and all three are read off the tree:
 *  - **an error element in the body** — the body does not parse where it is written;
 *  - **no code token at all** — an empty `//`, a body of whitespace, or a body that is nothing but a
 *    nested comment. A vacuous parse is not a parse, and an empty comment must never count as a
 *    comment anonymized;
 *  - **nothing but names** — every code token a bare reference: `TODO`, or `retry on timeout`, which
 *    Kotlin reads as an infix call of three names. That is the vacuous parse's second way of saying
 *    nothing. A body that does anything more than name things — a call with parentheses, an
 *    assignment, a declaration, a member access through `.`, a literal, a keyword — is code.
 *
 * **The third is a no-op for Java, and is here rather than in Kotlin's walk on purpose.** Java's
 * grammar rejects a body of bare names at every position — a statement needs its `;` — so no Java
 * body reaches it, and stating it once beside the guard it extends is what keeps the rule
 * language-neutral: the same Kotlin and Java comment, `// retry on timeout`, is prose in both.
 */
internal fun isCodeIn(parsed: PsiFile, body: TextRange): Boolean {
    if (hasErrorIn(parsed, body)) return false

    val tokens = codeTokensIn(parsed, body)
    return tokens.isNotEmpty() && !tokens.all(::isBareName)
}

/**
 * Whether [parsed] carries an error element inside [body] — looked for only where the body is, so
 * that a body parsed in place in a whole file costs the body and not the file.
 */
private fun hasErrorIn(parsed: PsiFile, body: TextRange): Boolean {
    var found = false
    parsed.accept(
        object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                if (!element.textRange.intersects(body)) return
                if (element is PsiErrorElement && body.contains(element.textRange)) {
                    found = true
                    stopWalking()
                    return
                }
                super.visitElement(element)
            }
        },
    )
    return found
}

/**
 * **The code tokens of [parsed] inside [body]**: every leaf there that is not whitespace and not
 * part of a comment nested in the body. A nested comment meets the verdict on its own terms, so
 * nothing in it makes the body around it code.
 */
internal fun codeTokensIn(parsed: PsiFile, body: TextRange): List<PsiElement> {
    val tokens = mutableListOf<PsiElement>()
    var leaf: PsiElement? = parsed.findElementAt(body.startOffset)
    while (leaf != null && leaf.textRange.startOffset < body.endOffset) {
        val isCode = leaf.textLength > 0 &&
            leaf !is PsiWhiteSpace &&
            PsiTreeUtil.getParentOfType(leaf, PsiComment::class.java, false) == null
        if (isCode && body.contains(leaf.textRange)) tokens += leaf
        leaf = PsiTreeUtil.nextLeaf(leaf)
    }
    return tokens
}

/**
 * **Whether [token] is a bare reference**: a name, by its own language's reckoning, that is the whole
 * of a reference — `retry`, or the `on` of `retry on timeout`, which is the reference to the infix
 * function the call names.
 *
 * Both halves, because each lets through what the other catches. A keyword the language treats as
 * soft — Kotlin's `import`, `private` — passes as a name and is no reference, so `import Ledger` is a
 * keyword construct and code; and an operator such as `=` can be the whole of a reference and is no
 * name, so `x = y` is an assignment and code.
 */
private fun isBareName(token: PsiElement): Boolean {
    val reference = token.parent ?: return false
    return reference.textRange == token.textRange &&
        reference.references.isNotEmpty() &&
        LanguageNamesValidation.INSTANCE.forLanguage(token.language).isIdentifier(token.text, token.project)
}
