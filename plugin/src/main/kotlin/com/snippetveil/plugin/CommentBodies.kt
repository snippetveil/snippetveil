package com.snippetveil.plugin

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
 *  - **no code signal** — no token that prose does not produce. A body is code only when its parse
 *    holds at least one of: a token that is one of `(` `)` `{` `}` `[` `]` `=` `.` `;` `::` `->`; a
 *    literal — string, character, number, `true`, `false` or `null`; or one of the keywords `val`
 *    `var` `fun` `class` `object` `interface` `import` `if` `when` `for` `while` `try`. Everything
 *    else that parses is prose: `TODO`, `retry on timeout`, which Kotlin reads as an infix call of
 *    three names, and `value in range`, `it is fine` or `done as planned`, which it reads as an
 *    operator-keyword construct.
 *
 * **`return` and `throw` are deliberately not signals.** `// return later` and `// throw away` read
 * as prose and are stripped, and so, at a cost chosen knowingly, is a real commented-out line with no
 * signal in it, such as `// return result`: such a line does nothing on its own and is very rarely
 * the clue in a snippet. `// return result;` and `// return total(items)` carry one and are kept.
 *
 * **The third is a no-op for Java, and is here rather than in Kotlin's walk on purpose.** Every Java
 * body that parses holds a signal — a statement or member ends in `;` or `}` — so no Java body is
 * changed by it, and stating it once beside the vacuous-parse guard is what keeps the rule
 * language-neutral: the same Kotlin and Java comment, `// value in range`, is prose in both.
 */
internal fun isCodeIn(parsed: PsiFile, body: TextRange): Boolean {
    if (hasErrorIn(parsed, body)) return false

    val tokens = codeTokensIn(parsed, body)
    return tokens.isNotEmpty() && tokens.any(::isCodeSignal)
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

/** The tokens prose does not produce, matched whole: `==` and `?.` are tokens of their own and none of these. */
private val SIGNAL_TOKENS = setOf("(", ")", "{", "}", "[", "]", "=", ".", ";", "::", "->")

/** The keywords that open a declaration or a control construct. `return` and `throw` are not among them. */
private val SIGNAL_KEYWORDS = setOf(
    "val", "var", "fun", "class", "object", "interface", "import", "if", "when", "for", "while", "try",
)

/**
 * **Whether [token] is a code signal**: a listed token or keyword, or a literal. Matched on the leaf's
 * text, which is what keeps it language-neutral — a lexer emits each of these as a leaf of its own.
 *
 * A keyword the language treats as soft can be a name as well: Kotlin reads `we import data` as an
 * infix call of a function named `import`. A leaf that is the whole of a reference is a name, whatever
 * it is spelled, so the keyword counts only where it is not one — `import Ledger` is a keyword
 * construct and code, and `we import data` is prose.
 *
 * A literal is told by its first character: a string or character literal opens with its quote —
 * Kotlin splits a string into its quotes and parts, and the opening quote is a leaf of its own — and a
 * number with a digit, or a `.` and a digit; no name starts with either.
 */
private fun isCodeSignal(token: PsiElement): Boolean {
    val text = token.text
    return text in SIGNAL_TOKENS ||
        (text in SIGNAL_KEYWORDS && !isWholeReference(token)) ||
        text == "true" || text == "false" || text == "null" ||
        text.startsWith('"') || text.startsWith('\'') ||
        text.first().isDigit() ||
        (text.length > 1 && text[0] == '.' && text[1].isDigit())
}

/** Whether [token] is the whole of a reference — a name in use, such as the `on` of `retry on timeout`. */
private fun isWholeReference(token: PsiElement): Boolean {
    val reference = token.parent ?: return false
    return reference.textRange == token.textRange && reference.references.isNotEmpty()
}
