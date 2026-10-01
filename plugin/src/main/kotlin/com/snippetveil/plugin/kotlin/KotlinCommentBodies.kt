package com.snippetveil.plugin.kotlin

import com.intellij.openapi.util.TextRange
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtilCore
import com.snippetveil.plugin.codeTokensIn
import com.snippetveil.plugin.isCodeIn
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.kdoc.lexer.KDocTokens
import org.jetbrains.kotlin.kdoc.psi.api.KDoc

/*
 * **What a Kotlin comment's body is, and whether it is code** — read at the comment's own position.
 *
 * > A comment whose body parses as code is anonymized and kept, on the fast path, with no tick. A
 * > comment whose body does not parse is stripped.
 *
 * > The body is parsed at the comment's own PSI position, with that position as the context
 * > element: member position parses members, statement position parses statements, file position
 * > parses a file.
 *
 * **Kotlin has no member-position fragment**, and that is what shapes everything here. Its code
 * fragments are a block, an expression and a type, so a commented-out `private val vipDiscount:
 * BigDecimal` would have to be parsed as a local — keyed as a local, rendered `local1`, and resolved
 * from wherever a block fragment's context happened to put it. A comment itself is no context at all
 * to Kotlin's resolution: it is not a Kotlin element, and a fragment given one resolves nothing.
 *
 * **So the body is parsed in place.** The file is parsed again with this one comment's delimiters
 * dropped, as a copy whose original is the file itself: the body then sits at exactly its own
 * position — among members, among statements, at the top of the file, or wherever it is — and is
 * parsed as whatever that position holds, by the parser that reads the file. The context element is
 * the position itself, and resolution answers from it: `this.customer` resolves against the
 * enclosing class, a local declared above the comment is in scope, an import line resolves like
 * one. A commented-out member is a member of its class and is keyed as one, by the same key rule as
 * a live one, because it is one.
 *
 * **One mechanism, not two, as in Java**: what makes the parse right is what makes the resolution
 * right.
 *
 * **Every offset in the copy is an offset in the file.** A delimiter is replaced character for
 * character, never removed, so a name read out of the copy lands on the comment with no translation.
 */

/**
 * **Parses [text] — [comment]'s file with [comment]'s delimiters dropped — as that file** — the seam
 * a test replaces with a parse that throws.
 *
 * A parse that fails is **observable, never an exception**: an unparseable body comes back as a tree
 * carrying error elements, and that tree is the verdict. A parse that *throws* is not a verdict at
 * all — it cannot tell an unparseable body from a broken platform — so nothing here or in its caller
 * catches one. It fails the invocation closed like every other throw in anonymization: the clipboard
 * untouched, the mapping uncommitted.
 */
internal fun interface KotlinCommentParser {
    fun parse(comment: PsiComment, text: String): PsiFile
}

/**
 * **The platform's own Kotlin parser**, reading the text as a copy of the file the comment is in.
 *
 * The copy's original is the file the user is looking at — never a copy of it, however deep the
 * comment is nested — which is what lets resolution answer from the real project: a name declared in
 * the file resolves to the declaration there, and the copy's own declarations stand where the file's
 * would. It keeps the file's name, which is what a top-level declaration's facade is named after.
 */
internal object PlatformKotlinCommentParser : KotlinCommentParser {

    override fun parse(comment: PsiComment, text: String): PsiFile {
        val original = comment.containingFile.originalFile
        val copy = PsiFileFactory.getInstance(comment.project).createFileFromText(original.name, KotlinFileType.INSTANCE, text)
        (copy as PsiFileImpl).originalFile = original
        return copy
    }
}

/**
 * **[comment]'s file, parsed again with the comment's body standing where it is written, or `null`
 * when the body is not code there.**
 *
 * Not code means one of two things. The first is the rule for every language — an error element in
 * the body, no code at all, or nothing but names: see [isCodeIn]. The second is what parsing in place
 * adds, and it is the same question asked of the edges: **the body parsed as something of its own.**
 *
 *  - **Nothing outside the comment parses differently.** A body that opens a brace it never closes
 *    is not an error where it is written; it is an error at the end of the file. So the copy's errors
 *    outside the comment are the file's, exactly.
 *  - **Nothing crosses the comment's edge.** `// val total =` above a live `compute()` parses cleanly
 *    — as one property, half of it commented out. A body is code only where everything in it is
 *    whole inside the comment, under something that holds the whole comment.
 */
internal fun parsedBodyOf(comment: PsiComment, parser: KotlinCommentParser): PsiFile? {
    val file = comment.containingFile
    val parsed = parser.parse(comment, uncommentedTextOf(comment))
    val range = comment.textRange
    val body = bodyRangeOf(comment)

    if (!isCodeIn(parsed, body)) return null
    if (errorsOutside(parsed, range) != errorsOutside(file, range)) return null
    if (codeTokensIn(parsed, body).any { crossesTheEdge(it, range) }) return null
    return parsed
}

/**
 * **The range of the file [comment]'s body occupies**: the comment, less its opening and closing
 * delimiters.
 *
 * Read off PSI rather than off the text: a line or block comment is one token, and its body is what
 * its manipulator exposes as its value; a KDoc block is a tree, and its opening and closing are tokens
 * of their own. A block comment in red code has no closing delimiter, and its body then runs to the
 * end of it.
 */
internal fun bodyRangeOf(comment: PsiComment): TextRange {
    val start = comment.textRange.startOffset
    if (comment !is KDoc) return ElementManipulators.getValueTextRange(comment).shiftRight(start)

    val opening = delimitersOf(comment).firstOrNull { it.type == Delimiter.OPENING }?.range?.endOffset ?: start
    val closing = delimitersOf(comment).firstOrNull { it.type == Delimiter.CLOSING }?.range?.startOffset ?: comment.textRange.endOffset
    return TextRange(opening, maxOf(opening, closing))
}

/**
 * **The text of [comment]'s file with [comment]'s delimiters dropped** — as tokens, never as text:
 * each delimiter token is overwritten where it stands, character for character, so the body is left
 * exactly where it was.
 *
 * What a delimiter is overwritten *with* is what puts the body on lines of its own. The opening
 * becomes a `;`, so that nothing written before the comment on its line runs on into the body — `val
 * total = 1 // + 2` is not one expression. The closing becomes a line break, so that the body runs on
 * into nothing written after it. A KDoc block's leading asterisks are blanked, because to a reader of
 * KDoc they are margin.
 */
internal fun uncommentedTextOf(comment: PsiComment): String {
    val copy = StringBuilder(comment.containingFile.text)
    for (delimiter in delimitersOf(comment)) {
        val range = delimiter.range
        for (offset in range.startOffset until range.endOffset) copy.setCharAt(offset, ' ')
        when (delimiter.type) {
            Delimiter.OPENING -> copy.setCharAt(range.startOffset, ';')
            Delimiter.CLOSING -> copy.setCharAt(range.startOffset, '\n')
            Delimiter.MARGIN -> Unit
        }
    }
    return copy.toString()
}

/**
 * **[comment]'s delimiter tokens**, at their offsets in the file — every one non-empty.
 *
 * A KDoc block names its own: its opening, its closing and each line's leading asterisk are tokens of
 * the tree. A line or block comment is a single token, and its delimiters are what lies outside its
 * value range — an asterisk at the front of a line inside one is text somebody wrote, and stays.
 */
private fun delimitersOf(comment: PsiComment): List<CommentDelimiter> {
    if (comment is KDoc) {
        val found = mutableListOf<CommentDelimiter>()
        var leaf: PsiElement? = PsiTreeUtil.firstChild(comment)
        while (leaf != null && comment.textRange.contains(leaf.textRange)) {
            val type = when (PsiUtilCore.getElementType(leaf)) {
                KDocTokens.START -> Delimiter.OPENING
                KDocTokens.END -> Delimiter.CLOSING
                KDocTokens.LEADING_ASTERISK -> Delimiter.MARGIN
                else -> null
            }
            if (type != null && leaf.textLength > 0) found += CommentDelimiter(leaf.textRange, type)
            leaf = PsiTreeUtil.nextLeaf(leaf)
        }
        return found
    }

    val range = comment.textRange
    val body = bodyRangeOf(comment)
    return listOfNotNull(
        TextRange(range.startOffset, body.startOffset).takeUnless { it.isEmpty }?.let { CommentDelimiter(it, Delimiter.OPENING) },
        TextRange(body.endOffset, range.endOffset).takeUnless { it.isEmpty }?.let { CommentDelimiter(it, Delimiter.CLOSING) },
    )
}

/** One delimiter token of a comment, and which of the three it is. */
private class CommentDelimiter(val range: TextRange, val type: Delimiter)

private enum class Delimiter { OPENING, CLOSING, MARGIN }

/**
 * The error elements of [file] that lie outside [comment], by where they are and what they say — the
 * same in a copy whose body parsed as something of its own as in the file it was copied from, because
 * every offset in the one is an offset in the other.
 */
private fun errorsOutside(file: PsiFile, comment: TextRange): List<Pair<Int, String>> =
    PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java)
        .filterNot { comment.contains(it.textRange) }
        .map { it.textRange.startOffset to it.errorDescription }

/**
 * Whether [token] belongs to something that runs across [comment]'s edge — whose nearest ancestor
 * reaching outside the comment does not hold all of it.
 */
private fun crossesTheEdge(token: PsiElement, comment: TextRange): Boolean {
    var element: PsiElement? = token
    while (element != null && element !is PsiFile) {
        val range = element.textRange
        if (!comment.contains(range)) return !range.contains(comment)
        element = element.parent
    }
    return false
}
