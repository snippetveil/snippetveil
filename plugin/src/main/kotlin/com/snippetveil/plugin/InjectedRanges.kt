package com.snippetveil.plugin

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLanguageInjectionHost
import com.snippetveil.core.SourceLanguage
import com.snippetveil.core.SymbolEvidence

/*
 * **Where a name read inside an injected fragment lies in the host file** — the mapping both
 * containers stand on, and the assertion that proves it.
 *
 * Nothing here reads a fragment's grammar and nothing here decides a placeholder. A container reads
 * the injected document and says which of its ranges are names ([InjectedName]); this file says where
 * each of those ranges lies in the host, or that one of them lies nowhere and the fragment falls back.
 *
 * **Per shred, never through the single-range host projection.** The platform lays an injected
 * document over its hosts as *shreds* — one per host literal, each with a range inside its host — and
 * it also offers `InjectedLanguageManager.injectedToHost(PsiElement, TextRange)`, which answers with
 * one range whatever it is asked. Across a shred boundary that one range is the **union**, and it
 * silently swallows the host text between the shreds: a placeholder written into it eats the
 * `" + tableVar + "` between two concatenated literals. That is a corruption rather than a leak, and
 * it is invisible — the result is a valid Java string literal and a well-formed SQL fragment, so a
 * parse check on either side passes. Only the relationship between the range and the host is wrong,
 * and every assertion that reads output is indifferent to it by definition. So that API is called
 * nowhere in shipped code, and `ShippedCodeArchitectureTest` says so over bytecode.
 *
 * **Escapes ride the same rule.** The injected document is laid out by plain offset arithmetic that
 * never consults an escaper: each shred contributes its prefix, the host text it carries — escapes
 * and all, `\"` as two characters — and its suffix. Which host offsets are offsets *of the text the
 * fragment means* is a question only the host literal's own [com.intellij.psi.LiteralTextEscaper] can
 * answer, so it is asked about every range, both ways. A range whose end falls inside an escape is
 * not an offset of that text at all, and Kotlin's escaper refuses such a range outright: that is the
 * unprojectable case arriving as a refusal rather than as a wrong number, and it is taken as one. The
 * fragment falls back; nothing here works around it.
 */

/**
 * **One injected fragment**: the document the platform injected, and the shreds it is laid over, in
 * document order.
 *
 * Obtained through [injectedInto], which is the platform's injected-file query — the one that hands
 * the shreds over with the file, so that every range below is computed per shred.
 *
 * **Every range of the injected document is an offset into [file]**, which is what a container reading
 * the fragment's PSI holds: an element's `textRange` there.
 */
internal class InjectedFragment(val file: PsiFile, val shreds: List<PsiLanguageInjectionHost.Shred>) {

    /** Every host literal this fragment is laid over, each once, in document order; `null` if one is gone. */
    val hosts: List<PsiLanguageInjectionHost>? get() =
        shreds.map { it.host ?: return null }.distinct()

    /**
     * Where [range] of the injected document lies in the host file, or `null` when it is
     * **unprojectable**: when it does not lie wholly inside the host text of one shred, or when the
     * host literal's own escaper refuses it.
     *
     * A range reaching into a shred's prefix or suffix is unprojectable, and that is the second
     * measured shape rather than a corner case: a non-constant interpolation is substituted into the
     * injected document as placeholder text — `missingValue` for a Java concatenation operand or a
     * Kotlin `${…}` entry, the variable's own name for Kotlin's `$tableVar` — and that text is a
     * prefix. It sits exactly where a table name goes, and it has no host counterpart at all.
     */
    fun hostRangeOf(range: TextRange): TextRange? {
        val part = parts?.firstOrNull { it.inFile.contains(range) } ?: return null
        val local = range.shiftRight(part.inHost.startOffset - part.inFile.startOffset)
        if (!part.escaperAccepts(local)) return null
        return local.shiftRight(part.host.textRange.startOffset)
    }

    /**
     * The range of the injected document that [hostRange] — a range of the host file — maps back to,
     * or `null` when it maps back to nothing.
     *
     * It maps back to nothing when it does not lie inside the host text of one shred — a span across a
     * shred boundary takes in host text that no shred carries, the `" + "` between two literals — or
     * when the host literal's escaper refuses it.
     */
    fun injectedRangeOf(hostRange: TextRange): TextRange? {
        for (part in parts ?: return null) {
            val hostStart = part.host.textRange.startOffset
            if (!part.inHost.shiftRight(hostStart).contains(hostRange)) continue
            val local = hostRange.shiftLeft(hostStart)
            if (!part.escaperAccepts(local)) return null
            return local.shiftRight(part.inFile.startOffset - part.inHost.startOffset)
        }
        return null
    }

    /**
     * **An injected occurrence's host range, mapped back into the injected document through the
     * literal's own escaper, is exactly that occurrence's range there** — or the run fails.
     *
     * A **range identity, never a text comparison.** An identifier written `cust\157mers` in a Java
     * host means `customers`, so its host span is wider than its spelling in the fragment and comparing
     * the two texts fires on a *correct* splice. Ranges round-trip; text does not.
     *
     * It catches the two shapes measured in real code: an identifier straddling a shred boundary, whose
     * union range takes in host text no shred carries and so maps back to nothing; and a name sitting in
     * substituted placeholder text, whose host range — whatever a wrong mapping made up for it — maps
     * back to something that is not the name, because nothing in the host is.
     *
     * Throws rather than falling back, and that is the difference between the two. Falling back is the
     * designed answer to a range the mapping *knows* it cannot project; a mismatch here means something
     * produced a range it believed and was wrong. The fail-closed guarantee takes an invocation that
     * gets here: the clipboard is left byte-identical and the user is told the operation failed.
     */
    fun assertMapsBack(injected: TextRange, host: TextRange) {
        val back = injectedRangeOf(host)
        check(back == injected) {
            "An injected occurrence at $injected in the injected document has the host range $host, which " +
                "maps back to ${back ?: "nothing"} rather than to itself. A placeholder written there would " +
                "overwrite host text that is not that name."
        }
    }

    /**
     * Each shred as a part of [file], or `null` when the file is not laid out the way this mapping
     * reads it. Both directions of the mapping read this one table, so it is checked against the file
     * rather than trusted — a layout the two directions agreed on wrongly would pass the identity: each shred's prefix, then the host text of its range inside the host, character for
     * character, then its suffix, and nothing after the last one.
     *
     * Checked rather than assumed, because a document built some other way is one where nothing here
     * can say which offsets are real — and saying nothing is what falls the fragment back.
     */
    private val parts: List<Part>? by lazy {
        val text = file.text
        var cursor = 0
        val parts = shreds.map { shred ->
            val host = shred.host ?: return@lazy null
            val start = cursor + shred.prefix.length
            val inFile = TextRange(start, start + shred.rangeInsideHost.length)
            if (inFile.endOffset > text.length) return@lazy null
            if (inFile.substring(text) != shred.rangeInsideHost.substring(host.text)) return@lazy null
            cursor = inFile.endOffset + shred.suffix.length
            Part(host, inFile, shred.rangeInsideHost)
        }
        parts.takeIf { cursor == text.length }
    }

    /**
     * One shred's host text: where it lies in [file] and where in its host literal, which are the same
     * characters.
     */
    private class Part(val host: PsiLanguageInjectionHost, val inFile: TextRange, val inHost: TextRange) {

        /**
         * The host offsets — relative to the host literal — at which a character of the decoded text
         * starts, and the offset its last one ends at: the only offsets a range of what the fragment
         * means can start or end at. `null` when the escaper refuses the shred as a whole.
         */
        private val boundaries: Set<Int>? by lazy {
            val escaper = host.createLiteralTextEscaper()
            val decoded = StringBuilder()
            if (!escaper.decode(inHost, decoded)) return@lazy null
            (0..decoded.length).map { escaper.getOffsetInHost(it, inHost) }.toSet()
        }

        /**
         * Whether the escaper accepts [local] as a range of the text it decodes: both ends on a
         * character boundary, and the span itself decoded without refusal.
         *
         * Both are asked because the two escapers refuse differently. Java's decodes a span starting in
         * the middle of `\157` without complaint, as `157…`; Kotlin's refuses a span cutting into an
         * escape at either end. The boundary set is what makes the answer the same for both.
         */
        fun escaperAccepts(local: TextRange): Boolean {
            val boundaries = boundaries ?: return false
            if (local.startOffset !in boundaries || local.endOffset !in boundaries) return false
            return host.createLiteralTextEscaper().decode(local, StringBuilder())
        }
    }

    companion object {

        /**
         * Every fragment injected into [host], each with its shreds — through the platform's
         * injected-file query, which is what makes the mapping per shred.
         */
        fun injectedInto(host: PsiLanguageInjectionHost): List<InjectedFragment> {
            val found = mutableListOf<InjectedFragment>()
            InjectedLanguageManager.getInstance(host.project).enumerate(host) { file, shreds ->
                found += InjectedFragment(file, shreds.toList())
            }
            return found
        }
    }
}

/**
 * **A name a container read in an injected document**, as ranges of that document.
 *
 * @param token the whole token, delimiters included
 * @param name the part of it a placeholder is written into — inside the delimiters of a delimited SQL
 *   identifier; see [com.snippetveil.core.SymbolOccurrence.nameStart]
 * @param symbol what the container knows about the symbol the name names
 * @param language the language the name is written in
 */
internal class InjectedName(
    val token: TextRange,
    val name: TextRange,
    val symbol: SymbolEvidence,
    val language: SourceLanguage,
)

/** One [InjectedName], together with where its token and its name lie in the host file. */
internal class ProjectedName(val injected: InjectedName, val token: TextRange, val name: TextRange)

/**
 * **A reader of injected documents** — what the two containers are, and what this file is built for.
 *
 * It answers with the names one fragment holds, or `null` when it cannot say what the fragment is,
 * which falls the fragment back.
 *
 * **What it does not name goes out as the host wrote it**, so answering is vouching for every other
 * character of the fragment. A container that cannot say a token is safe to keep — a keyword, an
 * operator — answers `null` for the whole fragment rather than leaving the token out. None ships yet: the mapping comes first, because a container built on
 * a wrong mapping is not a container with a bug in it — it is a corruption engine, and the corruption
 * is invisible.
 */
internal fun interface InjectedContainer {
    fun namesIn(fragment: InjectedFragment): List<InjectedName>?
}

/**
 * Every one of [names], with where it lies in the host file — or `null` when any of them is
 * unprojectable, which falls **the whole fragment** back.
 *
 * All or nothing: a fragment decomposed in part would leave its other part to a rule nobody stated.
 * Falling back is not building something and then unbuilding it — the fragment produces exactly what
 * it produces today, one redacted-literal occurrence per host literal.
 *
 * The range identity is asserted over every name projected, token and name range alike, before any of
 * them is handed back, so no injected occurrence reaches a plan without it.
 *
 * @param hostRangeOf the mapping — [InjectedFragment.hostRangeOf], and nothing else in shipped code. A
 *   parameter so that a test can hand in the union projection and watch the assertion refuse it.
 */
internal fun InjectedFragment.project(
    names: List<InjectedName>,
    hostRangeOf: (TextRange) -> TextRange? = this::hostRangeOf,
): List<ProjectedName>? {
    val projected = names.map { name ->
        ProjectedName(
            injected = name,
            token = hostRangeOf(name.token) ?: return null,
            name = hostRangeOf(name.name) ?: return null,
        )
    }
    for (name in projected) {
        assertMapsBack(name.injected.token, name.token)
        assertMapsBack(name.injected.name, name.name)
    }
    return projected
}
