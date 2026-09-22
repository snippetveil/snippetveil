package com.snippetveil.sweep

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.snippetveil.core.SymbolRole
import com.snippetveil.plugin.InjectedFragment
import com.snippetveil.plugin.InjectedReading
import com.snippetveil.plugin.project

/**
 * **One pass of the query containers over a project's injected fragments** — the query sweep's loop,
 * apart from the corpus it is pointed at, so that what it makes of a fragment can be asserted over a
 * fixture. [QuerySweep] is everything around it: opening the corpus, finding the files, writing the
 * report, and the assertions the run stands or falls on.
 *
 * **It measures the seam the product has.** A fragment is found the way `JavaPlanBuilder` finds one —
 * the literals of a Java file that are injection hosts, the platform's injected-file query, one fragment
 * per host and the whole of each host's text covered — and read by a [FragmentReader]. A fragment
 * injected into a Kotlin or an XML host is not measured here, because nothing decomposes one today, and
 * counting it would put a denominator under this report that the product never had.
 *
 * **Nothing here decides what a rate means and nothing here gates on one.** The pass counts; the
 * report says what the counts are over; see [QuerySweepReport].
 *
 * @param reader what the fragments are read by. [QuerySweep] hands it the containers, with the reason a
 *   SQL fragment fell back read off `SqlContainer.decide`. A fixture hands in its own, which is what
 *   lets the harness be watched finding a decomposition, a fallback and a host-range violation without
 *   a corpus to point it at.
 * @param hostRangeOf the mapping every read fragment is projected through — [InjectedFragment.hostRangeOf],
 *   and nothing else outside a fixture. A parameter for the reason the shipped projection has one: a
 *   test hands in the union projection and watches the pass count the violation it produces.
 */
internal class QueryPass(
    private val project: Project,
    private val reader: FragmentReader,
    private val hostRangeOf: (InjectedFragment) -> (TextRange) -> TextRange? = { fragment -> fragment::hostRangeOf },
) {

    /**
     * [files], each read for injected fragments, in the order given.
     *
     * @param corpus the corpus these files came out of, as the report names it
     * @param files the Java sources to read — what [QuerySweep] finds in one corpus
     * @param pathOf how a file is named in the report
     * @param progress told how many files have been read, after each one
     */
    fun over(
        corpus: String,
        files: List<VirtualFile>,
        pathOf: (VirtualFile) -> String,
        progress: (Int) -> Unit = {},
    ): QuerySwept {
        val tally = Tally(corpus, files.size)

        files.forEachIndexed { index, file ->
            val path = pathOf(file)
            // **A throw is a finding, not an outage**, exactly as it is in the leak half: a corpus of
            // thousands of files has one PSI edge case in it somewhere, and an instrument nobody can
            // finish running is one nobody runs. A host-range violation is not this — it is caught
            // where it happens and counted as the thing the run fails on.
            try {
                smartly(project) {
                    val psi = PsiManager.getInstance(project).findFile(file)
                        ?: error("The IDE handed over no PSI for this file, so nothing in it could be read.")
                    read(psi, path, tally)
                }
            } catch (wiring: SweepWiringFailure) {
                // **Not a finding, so not a row.** A wiring failure says this run is not measuring what
                // it says it measures, and the one place that must not end up is a line in a report
                // beside the shapes it was supposed to find.
                throw wiring
            } catch (failure: Throwable) {
                tally.failures += SweepFailure(path, "${failure::class.java.name}: ${failure.message}")
            }
            progress(index + 1)
        }
        return tally.swept()
    }

    /**
     * Every fragment injected into one file's literals, each read once however many hosts it is laid
     * over.
     *
     * **A literal, and never any other host.** A Java comment is an injection host too — a
     * `// language=SQL` comment is one itself — and `JavaPlanBuilder` reaches none of them: what a
     * decomposition stands in for is a *literal* occurrence. Counting the others would put hosts in
     * this report's coverage that the product was never going to read.
     */
    private fun read(file: PsiFile, path: String, tally: Tally) {
        val seen = mutableSetOf<FragmentIdentity>()
        val literals = PsiTreeUtil.findChildrenOfType(file, PsiLiteralExpression::class.java)
        for (host in literals.filterIsInstance<PsiLanguageInjectionHost>()) {
            val fragments = InjectedFragment.injectedInto(host)
            tally.hosts++
            if (fragments.isNotEmpty()) tally.hostsWithInjection++
            for (fragment in fragments) {
                // A fragment laid over a concatenation is injected into each of its hosts, and the
                // walk meets it once per host. Counted once: it is one fragment, and a denominator
                // that counted it twice would make every rate depend on how a query was written.
                if (seen.add(identityOf(fragment))) read(fragment, path, tally)
            }
        }
    }

    /**
     * What one fragment is, in this order:
     *
     *  1. **Is it a query at all** — the reader says, and a fragment it does not read is outside every
     *     rate below rather than a fallback. A `// language=RegExp` literal is not a query that fell
     *     back; it is not a query.
     *  2. **Is the whole of it here to decompose** — the host conditions `JavaPlanBuilder` applies
     *     before it asks a container anything. Asked second only because the first question is what
     *     says whether this fragment is in the denominator at all.
     *  3. **What the reader made of it** — the names it read, or why it fell back.
     *  4. **Does every part of it project onto its host** — and whether any host range fails to map
     *     back to the name it was computed for, which is what the run fails on.
     */
    private fun read(fragment: InjectedFragment, path: String, tally: Tally) {
        val language = fragment.file.language.id
        tally.fragmentsByLanguage.merge(language, 1, Int::plus)

        val verdict = reader.read(fragment)
        if (verdict is FragmentVerdict.NotAQuery) return
        tally.queryFragments++

        val hosts = fragment.hosts
        if (hosts == null) {
            tally.fellBack(QueryFallback.HOST_IS_GONE, null, path, language)
            return
        }
        if (hosts.any { InjectedFragment.injectedInto(it).size != 1 || !fragment.coversAllOf(it) }) {
            tally.fellBack(QueryFallback.PART_OF_A_LITERAL, null, path, language)
            return
        }

        if (verdict is FragmentVerdict.FellBack) {
            tally.fellBack(verdict.reason, verdict.at, path, language)
            return
        }

        val reading = (verdict as FragmentVerdict.Decomposed).reading
        val projected = try {
            fragment.project(reading, hostRangeOf(fragment))
        } catch (mismatch: IllegalStateException) {
            // **The one thing this instrument is pointed at.** The range identity throws rather than
            // falling back, so it arrives here as an exception; anything else that threw is a failure
            // row like any other, and is rethrown to the file's own guard above.
            if (RANGE_IDENTITY !in mismatch.message.orEmpty()) throw mismatch
            tally.violations += HostRangeViolation(path, language, mismatch.message.orEmpty())
            return
        }
        if (projected == null) {
            tally.fellBack(QueryFallback.UNPROJECTABLE_POSITION, null, path, language)
            return
        }

        tally.decomposed++
        for (name in projected.names) {
            tally.claimed.merge(name.injected.symbol.role, 1, Int::plus)
            if (name.injected.symbol.role != SymbolRole.TABLE) continue
            // **The spelling as written, delimiters and case included** — which is the whole of a SQL
            // name's identity, because nothing resolves one to a declaration. Two spellings that fold
            // together are two placeholders for what may be one table, and whether that ever happens
            // is the fourth thing this instrument was asked to measure.
            val spelling = name.injected.token.substring(fragment.file.text)
            tally.tableSpellings.getOrPut(foldedSpellingOf(spelling)) { sortedSetOf() } += spelling
        }
    }

    /** One corpus's counts, while they are still being added to. */
    private class Tally(val corpus: String, val files: Int) {
        var hosts = 0
        var hostsWithInjection = 0
        var queryFragments = 0
        var decomposed = 0
        val fragmentsByLanguage = sortedMapOf<String, Int>()
        val claimed = sortedMapOf<SymbolRole, Int>()
        val fallbacks = mutableListOf<QueryFallbackRow>()
        val tableSpellings = sortedMapOf<String, MutableSet<String>>()
        val violations = mutableListOf<HostRangeViolation>()
        val failures = mutableListOf<SweepFailure>()

        fun fellBack(reason: QueryFallback, at: String?, path: String, language: String) {
            fallbacks += QueryFallbackRow(reason, at, path, language)
        }

        fun swept() = QuerySwept(
            corpus = corpus,
            files = files,
            hosts = hosts,
            hostsWithInjection = hostsWithInjection,
            fragmentsByLanguage = fragmentsByLanguage,
            queryFragments = queryFragments,
            decomposed = decomposed,
            claimed = claimed,
            fallbacks = fallbacks,
            tableSpellings = tableSpellings,
            violations = violations,
            failures = failures,
        )
    }

    private companion object {

        /**
         * The wording the range identity fails with — `InjectedFragment.assertMapsBack`, which states
         * it with `check`, so there is no type to catch it by. Pinned by a fixture that provokes a real
         * violation through the union projection and watches this pass count it, so a reworded refusal
         * goes red here rather than turning every violation into a failure row.
         */
        const val RANGE_IDENTITY = "maps back to"
    }
}

/**
 * **The same fragment however many hosts it is laid over**: its shreds, each as the host it lies in and
 * the range it takes of that host.
 *
 * Identity by shreds rather than by the injected file, because the platform is free to hand out a file
 * instance per enumeration — and a walk that told two of those apart would count a concatenated query
 * once per literal it is written over.
 */
private typealias FragmentIdentity = List<Pair<PsiLanguageInjectionHost?, TextRange>>

private fun identityOf(fragment: InjectedFragment): FragmentIdentity =
    fragment.shreds.map { it.host to it.rangeInsideHost }

/**
 * **A wiring failure**: the run is not measuring what it says it measures — a container that is not
 * registered, an IDE that is not the one the instrument needs.
 *
 * Its own type because [QueryPass] turns every other throw into a row in the report, which is right for
 * a shape nobody thought of and exactly wrong for this: a report that listed *the container is not
 * registered* beside its findings would read as a corpus that decomposed nothing.
 */
internal class SweepWiringFailure(message: String) : Exception(message)

/**
 * **A SQL name as a dialect that folds unquoted names would see it**: delimiters off, case folded.
 *
 * Not a rule the product has, and deliberately not one: the product keys a SQL name by its spelling
 * exactly as written, because a delimited identifier is case-sensitive where a bare one is folded. This
 * is the *question* about that decision — do two spellings of one table ever occur in real code — asked
 * in the only way it can be asked from the text alone.
 */
internal fun foldedSpellingOf(spelling: String): String = spelling.trim('"', '`', '[', ']', '\'').lowercase()

/** **A reader of injected fragments**: what one is, when it is a query, and why it fell back when it did. */
internal fun interface FragmentReader {
    fun read(fragment: InjectedFragment): FragmentVerdict
}

/** What a [FragmentReader] made of one fragment. */
internal sealed class FragmentVerdict {

    /**
     * No container reads this language, so the fragment is **not in the denominator** — a regular
     * expression or an HTML template injected into a Java literal is not a query that fell back.
     */
    object NotAQuery : FragmentVerdict()

    /** Every token vouched for: what the container read out of the fragment. */
    class Decomposed(val reading: InjectedReading) : FragmentVerdict()

    /**
     * The fragment falls back.
     *
     * @param at what it fell back on, as the plugin names it — a token's element type, and `null` where
     *   the reason is about the fragment rather than about one token. Grouped by in the triage list.
     */
    class FellBack(val reason: QueryFallback, val at: String? = null) : FragmentVerdict()
}

/**
 * **Why a query fragment was not decomposed** — the triage vocabulary, which is the SQL container's own
 * reasons plus the ones that belong to the seam rather than to a container.
 *
 * The SQL container's entries are spelled exactly as `SqlFallback` spells them, and
 * `QuerySweepReportTest` holds that they are all here: a reason the container can give and this list
 * lacks would arrive as a crash in the middle of a sweep, or worse, as a row nobody could read.
 */
internal enum class QueryFallback {
    /** The platform injected something other than SQL into a host the SQL container was asked about. */
    NOT_SQL,

    /** The fragment has a parse error, so no position in it is one the grammar decided. */
    DOES_NOT_PARSE,

    /** A bind parameter, `?` or `:name`: unprojectable in either spelling. */
    BIND_PARAMETER,

    /** The name of a reference whose composite projects onto none of `table`, `col` and `schema`. */
    UNPROJECTABLE_REFERENCE,

    /** An identifier-keyword no claimed position took. */
    IDENTIFIER_KEYWORD_OUTSIDE_A_CLAIM,

    /** Any other token nothing claimed or kept: a string, a comment, an alias, an unrecognised type. */
    UNCLAIMED_TOKEN,

    /** A query-language fragment the query container did not read. It keeps no reason; this is that. */
    NOTHING_WAS_READ,

    /** A host the fragment is laid over is gone, so where the fragment lies cannot be said at all. */
    HOST_IS_GONE,

    /**
     * The fragment is laid over part of a literal, or over a literal something else is also injected
     * into — so what it does not report would go out as written, read by nothing.
     */
    PART_OF_A_LITERAL,

    /**
     * A part of what the container read lies nowhere in the host: across a shred boundary, or in the
     * placeholder text a non-constant operand was substituted by. The whole fragment falls back.
     */
    UNPROJECTABLE_POSITION,
}

/** One fragment that fell back: why, on what, and where it was. */
internal class QueryFallbackRow(
    val reason: QueryFallback,
    val at: String?,
    val path: String,
    val language: String,
)

/**
 * **One occurrence whose host range did not map back to itself** — the thing the run fails on.
 *
 * @param message the identity's own refusal, which names ranges and no identifier
 */
internal class HostRangeViolation(val path: String, val language: String, val message: String)

/**
 * **What one pass over one corpus found**, and every denominator each of it is over.
 *
 * @param corpus the corpus, as it was named on the command line
 * @param files how many source files were read
 * @param hosts how many injection hosts were found in them
 * @param hostsWithInjection how many of those the platform injected anything into — the count a sweep
 *   of a well-known sample application once came back zero on, which was the finding
 * @param fragmentsByLanguage every injected fragment found, by the language the platform injected
 * @param queryFragments how many of those a container reads — **the denominator of every rate here**
 * @param decomposed how many of those the container read and the mapping projected
 * @param claimed the names read out of them, by role
 * @param fallbacks one row per query fragment that was not decomposed
 * @param tableSpellings every spelling of a table name met, grouped by [foldedSpellingOf]
 * @param violations the host ranges that did not map back. **Zero, or the run fails.**
 * @param failures the files that threw, reported beside the counts rather than ending the run
 */
internal class QuerySwept(
    val corpus: String,
    val files: Int,
    val hosts: Int,
    val hostsWithInjection: Int,
    val fragmentsByLanguage: Map<String, Int>,
    val queryFragments: Int,
    val decomposed: Int,
    val claimed: Map<SymbolRole, Int>,
    val fallbacks: List<QueryFallbackRow>,
    val tableSpellings: Map<String, Set<String>>,
    val violations: List<HostRangeViolation>,
    val failures: List<SweepFailure>,
) {

    /** Every name the containers claimed in this corpus. */
    val claimedOccurrences: Int get() = claimed.values.sum()

    /** Every injected fragment found, whatever language it is in. */
    val fragments: Int get() = fragmentsByLanguage.values.sum()
}
