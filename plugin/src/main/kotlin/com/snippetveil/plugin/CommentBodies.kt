package com.snippetveil.plugin

import com.intellij.psi.PsiComment

/*
 * **What a comment's body is** — the half of a comment's verdict that is about delimiters rather
 * than about a language.
 *
 * Both walks share this rather than each carrying a copy, on the rule `SnippetFragments.kt` states:
 * neither could differ here without the difference being a bug. The delimiters and the leading
 * asterisk are spelled the same in Java and in Kotlin, and which parser the body is then handed to
 * is the one thing that does differ — so that stays in each walk.
 */

/**
 * The text inside a comment's delimiters, with the leading asterisks taken off the front of each
 * line — which is what a reader of a javadoc or KDoc block sees, and therefore what there is to
 * parse.
 *
 * **The asterisks come off a block comment only.** A line comment has no such convention, so an
 * asterisk at the front of one is text somebody wrote: `// * total = 3;` is a bullet in a list,
 * and reading the doc-comment line prefix off it would turn a line of prose into a statement that
 * parses. The verdict is meant to be exact, and that is a way for it not to be.
 *
 * The closing delimiter is removed if it is there and not assumed to be: a block comment in red
 * code runs to the end of the file, and the body is then everything after the opening.
 */
internal fun commentBodyOf(comment: PsiComment): String {
    val text = comment.text
    if (!text.startsWith(BLOCK_COMMENT_OPENING)) return text.removePrefix(LINE_COMMENT_OPENING)

    return text.removePrefix(BLOCK_COMMENT_OPENING)
        .removeSuffix(BLOCK_COMMENT_CLOSING)
        .lineSequence()
        .joinToString("\n") { it.trimStart().removePrefix(DOC_LINE_PREFIX) }
}

// What opens and closes a comment, and the asterisk a block comment's continuation lines are
// written with. Read only to find the body a parser is handed — never to decide anything about
// what the body says.
private const val LINE_COMMENT_OPENING = "//"
private const val BLOCK_COMMENT_OPENING = "/*"
private const val BLOCK_COMMENT_CLOSING = "*/"
private const val DOC_LINE_PREFIX = "*"
