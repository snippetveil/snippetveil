package com.snippetveil.plugin

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace

/*
 * **Which text a walk looks at, and where that text lands in the plan** — the half of a plan build
 * that is about ranges rather than about a language.
 *
 * Both walks share this rather than each carrying a copy, because neither could differ here without
 * the difference being a bug: snapping reads leaves and whitespace, which every language's PSI has,
 * and a snippet cut one way in Java and another way in Kotlin is a plan whose offsets mean two
 * things. Nothing here resolves anything or names a language type.
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
 * The start of the token [offset] falls inside, or [offset] itself when it already sits on a
 * boundary. Whitespace is the one leaf that may be split: half a run of spaces is still spaces.
 */
internal fun snapStart(file: PsiFile, offset: Int): Int {
    val leaf = file.findElementAt(offset) ?: return offset
    if (leaf is PsiWhiteSpace) return offset
    return minOf(offset, leaf.textRange.startOffset)
}

/** The end of the token [offset] falls inside; see [snapStart]. */
internal fun snapEnd(file: PsiFile, offset: Int): Int {
    if (offset <= 0 || offset >= file.textLength) return offset.coerceIn(0, file.textLength)
    val leaf = file.findElementAt(offset - 1) ?: return offset
    if (leaf is PsiWhiteSpace) return offset
    return maxOf(offset, leaf.textRange.endOffset)
}

/** A separator that cannot merge two fragments into one token, which is all it has to be. */
internal const val FRAGMENT_SEPARATOR = "\n"

/** One analysed range, and where its text starts in the plan. */
internal class Fragment(val range: TextRange, val planStart: Int) {
    fun translate(fileOffset: Int): Int = planStart + (fileOffset - range.startOffset)
}
