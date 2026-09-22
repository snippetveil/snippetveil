package com.snippetveil.plugin

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.util.TextRange
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLanguageInjectionHost
import com.snippetveil.core.CommentVerdict
import com.snippetveil.core.LiteralKind
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
     * Whether this fragment's shreds carry **every character of [host]'s own text** — everything
     * inside its delimiters.
     *
     * A decomposition stands in for the host's literal occurrence, and what the container does not
     * report goes out as written. So host text that no shred carries would go out as written too,
     * read by nothing: an injector that injected into part of a literal leaves the rest of that literal
     * to no rule at all. Such a fragment falls back.
     */
    fun coversAllOf(host: PsiLanguageInjectionHost): Boolean {
        val text = ElementManipulators.getValueTextRange(host)
        val carried = shreds.filter { it.host == host }.map { it.rangeInsideHost }
        return (text.startOffset until text.endOffset).all { offset -> carried.any { it.contains(offset) } }
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

/**
 * **A string a container read in an injected document** — a literal of the fragment's own language,
 * which becomes a literal occurrence of its own rather than text the fragment copies through.
 *
 * @param token the whole literal, delimiters included
 * @param content the literal's own text inside its delimiters — the only part a placeholder replaces
 * @param kind what the literal is; see [com.snippetveil.core.LiteralKind]
 * @param language the language the plan tags it with
 */
internal class InjectedLiteral(
    val token: TextRange,
    val content: TextRange,
    val kind: LiteralKind,
    val language: SourceLanguage,
)

/**
 * **A comment a container read in an injected document** — a comment like any other, so it goes
 * through the ordinary strip default and is counted where every stripped comment is.
 *
 * @param range the whole comment, delimiters included
 * @param verdict what the fragment's own language makes of the comment's body
 * @param language the language the plan tags it with
 */
internal class InjectedComment(
    val range: TextRange,
    val verdict: CommentVerdict,
    val language: SourceLanguage,
)

/**
 * **Everything a container read in one fragment** that is not copied through as written: its names,
 * and the strings and comments it holds.
 *
 * Strings and comments are here because *copied through as written* is exactly what must not happen
 * to them. A string inside a query is literal text in the query's own language, and a comment is
 * prose; neither is a keyword or an operator, so a container that left one out would be vouching for
 * domain text it never looked at.
 */
internal class InjectedReading(
    val names: List<InjectedName>,
    val literals: List<InjectedLiteral> = emptyList(),
    val comments: List<InjectedComment> = emptyList(),
)

/** One [InjectedName], together with where its token and its name lie in the host file. */
internal class ProjectedName(val injected: InjectedName, val token: TextRange, val name: TextRange)

/** One [InjectedLiteral], together with where its token and its content lie in the host file. */
internal class ProjectedLiteral(val injected: InjectedLiteral, val token: TextRange, val content: TextRange)

/** One [InjectedComment], together with where it lies in the host file. */
internal class ProjectedComment(val injected: InjectedComment, val range: TextRange)

/** An [InjectedReading] with every part of it placed in the host file. */
internal class ProjectedReading(
    val names: List<ProjectedName>,
    val literals: List<ProjectedLiteral>,
    val comments: List<ProjectedComment>,
)

/**
 * **A reader of injected documents** — what the two containers are, and what this file is built for.
 *
 * It answers with what one fragment holds, or `null` when it cannot say what the fragment is, which
 * falls the fragment back.
 *
 * **What it does not report goes out as the host wrote it**, so answering is vouching for every other
 * character of the fragment. A container that cannot say a token is safe to keep — a keyword, an
 * operator — answers `null` for the whole fragment rather than leaving the token out. That is why a
 * string and a comment are part of the answer rather than left to be copied: neither is safe to keep.
 */
internal fun interface InjectedContainer {
    fun read(fragment: InjectedFragment): InjectedReading?
}

/**
 * **Every container this IDE has**: [QueryContainer], which reads through platform interfaces and is
 * always here, then whatever is registered on `com.snippetveil.injectedContainer` — the SQL container,
 * where the database plugin is.
 *
 * Each container reads only the languages it knows and answers `null` for every other, so the first
 * answer is the only one there can be. A fragment none of them answers for falls back.
 *
 * The registrations are read here, per fragment, rather than held: a container registered from an
 * optional descriptor is one whose classes may not link, and asking the extension point is what keeps
 * this file from naming them.
 */
internal object RegisteredContainers : InjectedContainer {

    override fun read(fragment: InjectedFragment): InjectedReading? =
        (sequenceOf<InjectedContainer>(QueryContainer) + INJECTED_CONTAINERS.extensionList.asSequence())
            .firstNotNullOfOrNull { it.read(fragment) }

    /** Where a container that may not link is registered from its own descriptor. */
    internal val INJECTED_CONTAINERS = ExtensionPointName<InjectedContainer>("com.snippetveil.injectedContainer")
}

/**
 * Every one of [names], with where it lies in the host file — or `null` when any of them is
 * unprojectable, which falls **the whole fragment** back. See the [InjectedReading] overload, which
 * this is the names-only case of.
 */
internal fun InjectedFragment.project(
    names: List<InjectedName>,
    hostRangeOf: (TextRange) -> TextRange? = this::hostRangeOf,
): List<ProjectedName>? = project(InjectedReading(names), hostRangeOf)?.names

/**
 * Every part of [reading], with where it lies in the host file — or `null` when any of them is
 * unprojectable, which falls **the whole fragment** back.
 *
 * All or nothing: a fragment decomposed in part would leave its other part to a rule nobody stated.
 * Falling back is not building something and then unbuilding it — the fragment produces exactly what
 * it produces today, one redacted-literal occurrence per host literal.
 *
 * The range identity is asserted over every range projected — a name's token and name, a string's
 * token and content, a comment — before any of them is handed back, so no injected occurrence reaches
 * a plan without it.
 *
 * @param hostRangeOf the mapping — [InjectedFragment.hostRangeOf], and nothing else in shipped code. A
 *   parameter so that a test can hand in the union projection and watch the assertion refuse it.
 */
internal fun InjectedFragment.project(
    reading: InjectedReading,
    hostRangeOf: (TextRange) -> TextRange? = this::hostRangeOf,
): ProjectedReading? {
    val names = reading.names.map { name ->
        ProjectedName(
            injected = name,
            token = hostRangeOf(name.token) ?: return null,
            name = hostRangeOf(name.name) ?: return null,
        )
    }
    val literals = reading.literals.map { literal ->
        ProjectedLiteral(
            injected = literal,
            token = hostRangeOf(literal.token) ?: return null,
            content = hostRangeOf(literal.content) ?: return null,
        )
    }
    val comments = reading.comments.map { comment ->
        ProjectedComment(comment, hostRangeOf(comment.range) ?: return null)
    }

    for (name in names) {
        assertMapsBack(name.injected.token, name.token)
        assertMapsBack(name.injected.name, name.name)
    }
    for (literal in literals) {
        assertMapsBack(literal.injected.token, literal.token)
        assertMapsBack(literal.injected.content, literal.content)
    }
    for (comment in comments) assertMapsBack(comment.injected.range, comment.range)

    return ProjectedReading(names, literals, comments)
}
