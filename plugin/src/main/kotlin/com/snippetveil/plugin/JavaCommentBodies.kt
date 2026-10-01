package com.snippetveil.plugin

import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.util.Key
import com.intellij.psi.ElementManipulators
import com.intellij.psi.JavaCodeFragmentFactory
import com.intellij.psi.JavaDocTokenType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiCodeBlock
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.impl.source.resolve.FileContextUtil
import com.intellij.psi.javadoc.PsiDocComment
import com.intellij.psi.javadoc.PsiDocToken
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtil
import com.snippetveil.core.SymbolEvidence
import com.snippetveil.core.SymbolOrigin

/*
 * **What a Java comment's body is, and whether it is code** — read at the comment's own position.
 *
 * > A comment whose body parses as code is anonymized and kept, on the fast path, with no tick. A
 * > comment whose body does not parse is stripped.
 *
 * > The body is parsed as a code fragment at the comment's own PSI position, with that position as
 * > the context element: member position parses members, statement position parses statements, file
 * > position parses a file.
 *
 * **One mechanism, not two.** `this.customer` resolves only against the enclosing class, so a
 * fragment needs a context element to *resolve* anyway — and the same context element is what makes
 * the *parse* correct. A commented-out method or import does not parse as a code block and does
 * parse where it is written; parsing everything as a block, which is what the verdict used to do,
 * called both prose.
 */

/** **Where a comment sits in Java's grammar**, which is what its body is parsed as. */
internal enum class CommentPosition {

    /** Inside a class body, among its fields and methods: the body is parsed as members. */
    MEMBER,

    /** Inside a block, among its statements: the body is parsed as statements. */
    STATEMENT,

    /** At the top of a file, among its imports and classes: the body is parsed as a file. */
    FILE,
}

/**
 * **Parses one comment's body at its position, with the comment as the context element** — the seam
 * a test replaces with a parse that throws.
 *
 * A parse that fails is **observable, never an exception**: an unparseable body comes back as a tree
 * carrying error elements, and that tree is the verdict. A parse that *throws* is not a verdict at
 * all — it cannot tell an unparseable body from a broken platform — so nothing here or in its caller
 * catches one. It fails the invocation closed like every other throw in anonymization: the clipboard
 * untouched, the mapping uncommitted.
 */
internal fun interface CommentParser {
    fun parse(comment: PsiComment, body: String, position: CommentPosition): PsiFile
}

/**
 * **The platform's own parsers, one per position.** A code-block fragment for statements and a
 * member fragment for members, both with the comment as context — so a name in the body resolves
 * from exactly where the comment is written, and a local declared above it is in scope.
 *
 * A file has no fragment of its own, so it is an ordinary Java file whose context is set to the
 * comment: `extends Invoice` in a commented-out top-level class then resolves through the real
 * file's package and imports rather than through an empty default package. It is parsed at the real
 * file's language level, which is what decides whether a top-level field parses at all.
 */
internal object JavaCommentParser : CommentParser {

    override fun parse(comment: PsiComment, body: String, position: CommentPosition): PsiFile {
        val project = comment.project
        return when (position) {
            CommentPosition.STATEMENT ->
                JavaCodeFragmentFactory.getInstance(project).createCodeBlockCodeFragment(body, comment, false)

            CommentPosition.MEMBER ->
                JavaCodeFragmentFactory.getInstance(project).createMemberCodeFragment(body, comment, false)

            CommentPosition.FILE ->
                PsiFileFactory.getInstance(project).createFileFromText(FILE_FRAGMENT_NAME, JavaLanguage.INSTANCE, body).also {
                    it.putUserData(PsiUtil.FILE_LANGUAGE_LEVEL_KEY, PsiUtil.getLanguageLevel(comment))
                    it.putUserData(FileContextUtil.INJECTED_IN_ELEMENT, SmartPointerManager.createPointer<PsiElement>(comment))
                }
        }
    }
}

/**
 * **[comment]'s body parsed as code at its own position, or `null` when it is not code.**
 *
 * Not code is read off the tree the parser returned — an error element in it, no code at all, or
 * no code signal — by [isCodeIn], which is the one statement of that rule for every language. The
 * fragment is the body and nothing else, so all of it is the body.
 */
internal fun parsedBodyOf(comment: PsiComment, parser: CommentParser): PsiFile? {
    val position = positionOf(comment)
    val parsed = parser.parse(comment, bodyOf(comment), position)
    if (!isCodeIn(parsed, parsed.textRange)) return null

    // Where this fragment sits inside the comment the walk met in the file — the comment itself, or
    // the one it is nested in, however deep. Read by [commentDeclarationEvidence].
    val outer = comment.containingFile.getUserData(READ_FROM)
    parsed.putUserData(
        READ_FROM,
        ReadFrom(
            comment = comment,
            position = position,
            anchor = outer?.anchor ?: comment,
            offset = if (outer == null) 0 else outer.offset + comment.textRange.startOffset,
        ),
    )
    return parsed
}

/**
 * **The body of [comment], as PSI exposes it: the comment's text with its delimiters, and a javadoc
 * block's leading asterisks, blanked out** — dropped as tokens, never stripped as text.
 *
 * Blanked rather than removed, so that **an offset into the body is an offset into the comment**:
 * everything read out of the parsed fragment lands back on the comment's own text with one addition,
 * and a leading asterisk costs the parser nothing, because to it a space is a space.
 *
 * A javadoc block is a tree of tokens, and its opening, its closing and each line's leading asterisk
 * are tokens of their own — so there is no textual pre-pass to get wrong. A line or block comment is
 * one token, and what PSI exposes as its content is its manipulator's value range. So an asterisk at
 * the front of a *line* comment is text somebody wrote — `// * total = 3;` is a bullet in a list —
 * and it stays where it is, as prose.
 */
internal fun bodyOf(comment: PsiComment): String {
    val text = StringBuilder(comment.text)
    val base = comment.textRange.startOffset

    fun blank(from: Int, to: Int) {
        for (offset in from until to) if (!text[offset].isWhitespace()) text.setCharAt(offset, ' ')
    }

    if (comment is PsiDocComment) {
        for (token in PsiTreeUtil.findChildrenOfType(comment, PsiDocToken::class.java)) {
            if (token.tokenType !in DOC_DELIMITERS) continue
            blank(token.textRange.startOffset - base, token.textRange.endOffset - base)
        }
    } else {
        val content = ElementManipulators.getValueTextRange(comment)
        blank(0, content.startOffset)
        blank(content.endOffset, text.length)
    }
    return text.toString()
}

/**
 * **Where [comment] sits: among members, among statements, or at the top of a file** — the nearest
 * of the three that encloses it.
 *
 * A comment in front of a declaration is bound *into* that declaration by the parser, so the walk goes
 * up rather than reading the parent: a line above a method belongs to the method, and the method is
 * among members. A class is a member position only **inside its braces** — a comment above a
 * top-level class belongs to the class and is at file position. Anywhere else — between two
 * arguments, inside an annotation — is the nearest enclosing block, class body or file, and the body
 * is then tried as whatever that position holds; a fragment of an expression is not a statement, and
 * does not parse as one.
 *
 * Inside a parsed fragment the fragment itself is a position: statements in a block fragment are
 * children of the fragment rather than of a block, so reaching the fragment is reaching the position
 * it was parsed at.
 */
internal fun positionOf(comment: PsiComment): CommentPosition {
    var child: PsiElement = comment
    var parent: PsiElement? = comment.parent
    while (parent != null) {
        when {
            parent is PsiCodeBlock -> return CommentPosition.STATEMENT
            parent is PsiClass && isInBody(parent, child) -> return CommentPosition.MEMBER
            parent is PsiFile -> return parent.getUserData(READ_FROM)?.position ?: CommentPosition.FILE
        }
        child = parent
        parent = parent.parent
    }
    return CommentPosition.FILE
}

/** Whether [child] is inside [owner]'s braces rather than in front of them. */
private fun isInBody(owner: PsiClass, child: PsiElement): Boolean {
    val brace = owner.lBrace ?: return false
    return child.textRange.startOffset >= brace.textRange.endOffset
}

/**
 * **What is known about a symbol declared inside a commented-out fragment**, or `null` for a symbol
 * declared anywhere else — which is every symbol a name resolves to, except these.
 *
 * Such a declaration lives in a throwaway file with no place in the project, and the ordinary
 * description would read that as *not project content* and keep its name. It is the user's own code
 * by construction — it is written in their file — so it is reported [SymbolOrigin.IN_CONTENT].
 *
 * **It is keyed as the declaration it would be if it were uncommented**, because it is at that
 * position: a commented-out method is a method of the class whose body it sits in, and takes the
 * placeholder a live method of that name takes; a commented-out top-level class is a class of the
 * file's package. Anything declared deeper — a local, a parameter, a label — is keyed by where it is
 * written, inside the comment the walk met in the file, which is the key a local always gets.
 */
internal fun commentDeclarationEvidence(symbol: PsiElement, declaredName: String): SymbolEvidence? {
    val read = symbol.containingFile?.getUserData(READ_FROM) ?: return null
    val name = (symbol as? PsiNameIdentifierOwner)?.name ?: declaredName
    val atTop = symbol.parent === symbol.containingFile

    val qualifiedKey: String? = when {
        !atTop -> null
        read.position == CommentPosition.MEMBER -> memberKeyIn(PsiTreeUtil.getContextOfType(read.comment, PsiClass::class.java, false), symbol, name)
        read.position == CommentPosition.FILE && symbol is PsiClass -> {
            val packageName = (read.anchor.containingFile as? PsiJavaFile)?.packageName.orEmpty()
            SymbolKeys.classifierKeyOf(if (packageName.isEmpty()) name else "$packageName.$name")
        }
        else -> null
    }

    return SymbolEvidence(
        key = qualifiedKey ?: SymbolKeys.fragmentLocalKeyOf(read.anchor, read.offset + symbol.textOffset),
        role = SymbolFacts.roleOf(symbol),
        origin = SymbolOrigin.IN_CONTENT,
        declaredName = name,
        keyIsQualified = qualifiedKey != null,
    )
}

/**
 * The key [symbol] would have as a member of [owner], or `null` when [owner] has no qualified key to
 * be a member of — an anonymous or local class, whose members are keyed by position like a local.
 */
private fun memberKeyIn(owner: PsiClass?, symbol: PsiElement, name: String): String? {
    if (owner == null || !SymbolKeys.keyIsQualified(owner)) return null
    return when (symbol) {
        is PsiMethod -> SymbolKeys.memberKeyOf(SymbolKeys.METHOD, SymbolKeys.keyOf(owner), name)
        is PsiField -> SymbolKeys.memberKeyOf(SymbolKeys.FIELD, SymbolKeys.keyOf(owner), name)
        is PsiClass -> owner.qualifiedName?.let { SymbolKeys.classifierKeyOf("$it.$name") }
        else -> null
    }
}

/**
 * **Which comment a parsed fragment was read from, and where it sits inside the one the walk met in
 * the file.**
 *
 * @param comment the comment whose body this fragment is
 * @param position the position the body was parsed at, which a comment nested in it inherits
 * @param anchor the comment in the file itself — [comment], or the one it is nested in however deep
 * @param offset where this fragment starts inside [anchor]'s text
 */
private class ReadFrom(val comment: PsiComment, val position: CommentPosition, val anchor: PsiComment, val offset: Int)

private val READ_FROM = Key.create<ReadFrom>("snippetveil.commentFragment.readFrom")

/** The tokens a javadoc block is delimited with, which are dropped from its body. */
private val DOC_DELIMITERS = setOf(
    JavaDocTokenType.DOC_COMMENT_START,
    JavaDocTokenType.DOC_COMMENT_END,
    JavaDocTokenType.DOC_COMMENT_LEADING_ASTERISKS,
)

/** The name a file-position fragment is parsed under; it names no file anybody has. */
private const val FILE_FRAGMENT_NAME = "_.java"
