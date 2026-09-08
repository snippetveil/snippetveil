package com.snippetveil.plugin

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace

/*
 * **Which text a walk looks at, and where that text lands in the plan** — the half of a plan build
 * that is about ranges rather than about a language.
 *
 * Both walks share this rather than each carrying a copy, because neither could differ here without
 * the difference being a bug: a snippet cut one way in Java and another way in Kotlin is a plan
 * whose offsets mean two things. **What one token is, is the one thing that does differ** — and it
 * arrives as [TokenOf], so nothing here resolves anything or names a language type.
 */

/**
 * The ranges actually analysed: the snapped selection, merged where two of them now touch, or the
 * whole file when there is no selection at all.
 *
 * Snapping itself happens in the walk rather than here, because whether it *moved* anything is a
 * fact the preview discloses and the walk is the last place both ends of the comparison exist.
 *
 * **Snapping closes the last fail-open leak, and it is safe rather than merely convenient.** A
 * selection cutting mid-identifier would otherwise emit raw domain text — `chantReference = x;` out
 * of `merchantReference` — because the fragment the rules never saw is copied verbatim. Extending
 * outward can only add text that some rule already governs: an identifier becomes a placeholder or a
 * preserved library name, a literal becomes a literal. **There is no path where snapping emits
 * un-anonymized project text.**
 *
 * Rejected on the way here: rewriting only the overlapping fragment, which splices half a
 * placeholder into half a name; and dropping the fragment, which silently deletes characters the
 * user selected and reads as a bug.
 *
 * **A container snaps as a whole, never to one of its parts**, and that is the same argument rather
 * than a second one. A selection cutting into a Kotlin string template snaps to the template, not to
 * the entry it cut: an entry-level snap would extend the rewritten range into the template while
 * leaving the rest of that template inside the copied range and **outside every rule** — precisely
 * the fail-open outward snapping exists to close. What is pulled in then meets a rule, because a
 * template is decomposed structurally and every chunk of it is replaced. See [TokenOf].
 */
internal fun fragmentsOf(file: PsiFile, snapped: List<TextRange>): List<Fragment> {
    val ranges = snapped
        .ifEmpty { listOf(TextRange(0, file.textLength)) }
        .sortedBy { it.startOffset }

    // Two carets whose snapped ranges now touch are one fragment: the separator between them would
    // otherwise insert a newline into the middle of a token boundary that snapping just widened.
    val merged = mutableListOf<TextRange>()
    for (range in ranges) {
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
 * **Which element a leaf belongs to for the purpose of snapping** — the language's answer to *what
 * is one token here*, supplied by the walk because only the walk knows the language.
 *
 * The token classes are **named by the language** rather than enumerated once for Java. In Java they
 * are the identifier, the string literal and the text block, and each of those is a single leaf, so
 * Java's answer is the leaf itself. In Kotlin they are the identifier, the string literal, the raw
 * string and the string template — and the last three are one PSI *tree* whose leaves are its
 * entries, so Kotlin's answer widens a leaf to the template containing it.
 *
 * Stated as a function rather than as a type test here, because a type test here would name
 * `org.jetbrains.kotlin.*` from a file the main descriptor loads. See [LanguageSupport].
 */
internal typealias TokenOf = (PsiElement) -> PsiElement

/**
 * **Every selected range, snapped outward to whole tokens** — the whole of what a walk does with a
 * selection before it walks anything.
 *
 * One function rather than the same `map` in both builders, on the same rule as everything else in
 * this file: a snippet cut one way in Java and another way in Kotlin is a plan whose offsets mean two
 * things, and two copies of the cut are how that starts. What the two walks differ in arrives as
 * [tokenOf].
 *
 * The result is compared against the selection it came from — that comparison is *whether snapping
 * fired*, which the preview discloses — so it is returned rather than folded into [fragmentsOf].
 */
internal fun snappedRangesOf(file: PsiFile, selections: List<TextRange>, tokenOf: TokenOf): List<TextRange> =
    selections.map { TextRange(snapStart(file, it.startOffset, tokenOf), snapEnd(file, it.endOffset, tokenOf)) }

/**
 * The start of the token [offset] falls inside, or [offset] itself when it already sits on a
 * boundary. Whitespace is the one leaf that may be split: half a run of spaces is still spaces.
 *
 * @param tokenOf what one token is in this language; see [TokenOf].
 */
private fun snapStart(file: PsiFile, offset: Int, tokenOf: TokenOf): Int {
    val leaf = file.findElementAt(offset) ?: return offset
    if (leaf is PsiWhiteSpace) return offset
    return minOf(offset, tokenOf(leaf).textRange.startOffset)
}

/** The end of the token [offset] falls inside; see [snapStart]. */
private fun snapEnd(file: PsiFile, offset: Int, tokenOf: TokenOf): Int {
    if (offset <= 0 || offset >= file.textLength) return offset.coerceIn(0, file.textLength)
    val leaf = file.findElementAt(offset - 1) ?: return offset
    if (leaf is PsiWhiteSpace) return offset
    return maxOf(offset, tokenOf(leaf).textRange.endOffset)
}

/** A separator that cannot merge two fragments into one token, which is all it has to be. */
internal const val FRAGMENT_SEPARATOR = "\n"

/** One analysed range, and where its text starts in the plan. */
internal class Fragment(val range: TextRange, val planStart: Int) {
    fun translate(fileOffset: Int): Int = planStart + (fileOffset - range.startOffset)
}
