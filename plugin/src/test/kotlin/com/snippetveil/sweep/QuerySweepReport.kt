package com.snippetveil.sweep

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * **What the query sweep reports, and what it holds the release on** — both here, and both pure
 * functions of the counts, so that either can be asserted without a corpus to sweep.
 *
 * ### Every rate is reported and gates nothing
 *
 * Four rates are here because four decisions asked for them: the unprojectable-position share, the
 * identifier-keyword share, the bind-parameter share, and whether two spellings of one table ever
 * occur. Each is a cost the public copy of this product carries, and this is the only instrument
 * pointed at real fragments — which makes it look like the place to **judge** them. It is the place to
 * **measure** them.
 *
 * **A rate threshold is refused**, and it is refused on the denominator rather than on taste: the
 * literal-level denominator these rates are over is the wrong one, the right one — *files a developer
 * would actually paste* — cannot be measured without telemetry this product refuses to collect, and a
 * number written over the wrong denominator gets argued down the first time it fails. So no number
 * below is compared against anything, here or anywhere else in this harness.
 *
 * ### The pass condition is an exact assertion
 *
 * **Zero host-range violations across every swept corpus.** A single occurrence whose host range does
 * not map back to its identifier holds the release: it is a design failure, not a tuning problem. See
 * [assertNoHostRangeViolation].
 *
 * ### And it reports its own denominator
 *
 * An instrument that sweeps a corpus, finds **zero injected fragments** and reports zero failures is a
 * fail-green harness one level out — every assertion below it holds vacuously. So a run that read no
 * query fragment is a **failed run rather than a clean one**, and that is not hypothetical: a sweep
 * pointed at a well-known sample application got zero hosts with SQL, and that was the finding. See
 * [assertTheRunReadAQuery].
 */
internal class QuerySweepReport(
    private val startedAt: String,
    private val swept: List<QuerySwept>,
) {

    private val rates = QueryRates(swept)

    /**
     * The report, whole.
     *
     * The header is the leak report's header with one line changed, for the same reason that one has
     * it: **this file holds real identifiers out of a real codebase** — the table spellings it groups,
     * and the paths of the files a fragment fell back in. A corpus is somebody's checkout whether or
     * not it is a public one, and the instinct on reading a row is to paste it into an issue.
     */
    fun render(): String = buildString {
        appendLine("SnippetVeil query sweep — $startedAt")
        appendLine()
        appendLine("!! This file lists REAL IDENTIFIERS and REAL PATHS read out of the corpora below.")
        appendLine("!! DO NOT PASTE it into an issue, a pull request, a chat or a screenshot.")
        appendLine("!! A bug found here earns a SYNTHETIC fixture reproducing its shape — never the")
        appendLine("!! real code that revealed it. Real code in, findings out, code never moves.")
        appendLine()

        swept.forEach { appendCorpus(it) }

        appendRates()
        appendTriage()
        appendTableSpellings()
        appendViolations()
        appendFailures()
    }

    /** One corpus's own denominators, printed whether or not anything was found in it. */
    private fun StringBuilder.appendCorpus(corpus: QuerySwept) {
        appendLine("── ${corpus.corpus} ".padEnd(96, '─'))
        appendLine("  Java source files read     : ${corpus.files}")
        appendLine("  Injection hosts            : ${corpus.hosts}")
        appendLine("  Hosts with an injected file: ${corpus.hostsWithInjection}")
        appendLine("  Injected fragments         : ${corpus.fragments} ${byLanguage(corpus)}")
        appendLine("  Query fragments            : ${corpus.queryFragments}  <- the denominator")
        appendLine("  Decomposed                 : ${corpus.decomposed}")
        appendLine("  Names claimed              : ${corpus.claimedOccurrences} ${corpus.claimed}")
        appendLine("  Fell back                  : ${corpus.fallbacks.size}")
        appendLine("  Host-range violations      : ${corpus.violations.size}")
        appendLine("  Files that threw           : ${corpus.failures.size}")
        appendLine()
    }

    private fun byLanguage(corpus: QuerySwept): String =
        corpus.fragmentsByLanguage.entries.joinToString(prefix = "(", postfix = ")") { "${it.key}: ${it.value}" }

    /** **The four rates, each over the denominator it is over, and each gating nothing.** */
    private fun StringBuilder.appendRates() {
        appendLine("── rates ".padEnd(96, '─'))
        appendLine("  Every rate here is REPORTED AND GATES NOTHING. Each is a share of the ${rates.fragments} query")
        appendLine("  fragment(s) this run read — the literal-level denominator, not the one that would")
        appendLine("  matter, which is files a developer would paste, and which cannot be measured without")
        appendLine("  telemetry this product refuses to collect. A threshold written over the wrong")
        appendLine("  denominator gets argued down the first time it fails, so there is none.")
        appendLine()
        appendLine("  Unprojectable position : ${rates.share(rates.unprojectablePositions)}")
        appendLine("  Identifier keyword     : ${rates.share(rates.identifierKeywords)}")
        appendLine("  Bind parameter         : ${rates.share(rates.bindParameters)}")
        appendLine("  Two spellings of one table: ${if (rates.tablesWrittenTwoWays.isEmpty()) "never met" else "met"}")
        rates.tablesWrittenTwoWays.forEach { (folded, spellings) ->
            appendLine("      $folded is written ${spellings.joinToString(", ")}")
        }
        appendLine()
    }

    /** **The triage list**: every reason a fragment fell back, commonest first, with where to find one. */
    private fun StringBuilder.appendTriage() {
        val rows = swept.flatMap { it.fallbacks }
        appendLine("── fallback reasons ".padEnd(96, '─'))
        appendLine("  Each row is one fragment that stayed one redacted literal. That is the product")
        appendLine("  working as designed; the list is here to say WHAT it is declining, so that a shape")
        appendLine("  worth reading earns a rule and a synthetic fixture.")
        appendLine()
        if (rows.isEmpty()) {
            appendLine("  No query fragment fell back.")
            appendLine()
            return
        }
        rows.groupBy { it.reason to it.at }
            .entries
            // Commonest first, and ties in the order they were met: a stable sort over a map that
            // keeps insertion order, so two runs over one corpus render the same list.
            .sortedByDescending { it.value.size }
            .forEach { (group, hits) ->
                val (reason, at) = group
                appendLine("  ${hits.size}  $reason${at?.let { " on $it" }.orEmpty()}")
                hits.take(EXAMPLES).forEach { appendLine("        ${it.language}  ${it.path}") }
                if (hits.size > EXAMPLES) appendLine("        … and ${hits.size - EXAMPLES} more")
            }
        appendLine()
    }

    /**
     * **Every table name met, where one folds together with another** — the fourth measurement, and the
     * only one that is a set rather than a count. The product keys a SQL name by its spelling exactly
     * as written, so two spellings of one table are two placeholders; whether real code writes one
     * table two ways is what this says.
     */
    private fun StringBuilder.appendTableSpellings() {
        appendLine("── table spellings ".padEnd(96, '─'))
        val folded = rates.tableSpellings
        appendLine("  Distinct table spellings: ${folded.values.sumOf { it.size }} in ${folded.size} folded group(s).")
        if (rates.tablesWrittenTwoWays.isEmpty()) {
            appendLine("  No table was written two ways. That is a measurement, not a pass.")
        } else {
            appendLine("  ${rates.tablesWrittenTwoWays.size} group(s) hold more than one spelling; they are listed above.")
        }
        appendLine()
    }

    /** **The pass condition, in the report** — printed even when it is zero, because that is the claim. */
    private fun StringBuilder.appendViolations() {
        val violations = swept.flatMap { it.violations }
        appendLine("── host-range violations ".padEnd(96, '─'))
        appendLine("  ${violations.size}. Zero is the pass condition, and a single one holds the release:")
        appendLine("  an occurrence whose host range does not map back to its own identifier is a")
        appendLine("  placeholder written over host text that is not that name.")
        violations.forEach {
            appendLine()
            appendLine("  ${it.language}  ${it.path}")
            appendLine("        ${it.message}")
        }
        appendLine()
    }

    private fun StringBuilder.appendFailures() {
        val failures = swept.flatMap { it.failures }
        appendLine("── files that could not be read ".padEnd(96, '─'))
        appendLine("  ${failures.size}. A throw is a finding too — a shape nobody thought of, arriving as a")
        appendLine("  stack trace rather than as a row. It earns a synthetic fixture like any other.")
        failures.forEach {
            appendLine()
            appendLine("  ${it.path}")
            appendLine("        ${it.summary}")
        }
    }

    private companion object {

        /** How many places one triage row names. Enough to find the shape; not a dump of the corpus. */
        const val EXAMPLES = 5
    }
}

/**
 * **The four rates, over the one denominator the sweep can honestly write down**: the query fragments
 * it read.
 *
 * A class of its own so that *what each rate is over* is stated once and read by the report and by the
 * tests alike. **Nothing here compares a rate to anything** — see [QuerySweepReport].
 */
internal class QueryRates(swept: List<QuerySwept>) {

    /** The denominator: every fragment a container reads, across every swept corpus. */
    val fragments: Int = swept.sumOf { it.queryFragments }

    /** Fragments that fell back because a part of what was read lies nowhere in the host. */
    val unprojectablePositions: Int = swept.count(QueryFallback.UNPROJECTABLE_POSITION)

    /** Fragments that fell back on an identifier-keyword no claimed position took. */
    val identifierKeywords: Int = swept.count(QueryFallback.IDENTIFIER_KEYWORD_OUTSIDE_A_CLAIM)

    /** Fragments that fell back on a bind parameter, in either spelling. */
    val bindParameters: Int = swept.count(QueryFallback.BIND_PARAMETER)

    /** Every table spelling met, grouped by [foldedSpellingOf], across every swept corpus. */
    val tableSpellings: Map<String, Set<String>> = swept.flatMap { it.tableSpellings.entries }
        .groupBy({ it.key }, { it.value })
        .mapValues { (_, spellings) -> spellings.flatten().toSortedSet() }
        .toSortedMap()

    /** The folded groups holding more than one spelling: one table written two ways, if any ever is. */
    val tablesWrittenTwoWays: Map<String, Set<String>> = tableSpellings.filterValues { it.size > 1 }

    /** [count] as a share of [fragments], spelled with what it is a share of. Compared to nothing. */
    fun share(count: Int): String {
        if (fragments == 0) return "$count of no query fragment at all, so there is no share to report"
        val percent = String.format(Locale.ROOT, "%.1f%%", 100.0 * count / fragments)
        return "$count of $fragments ($percent)"
    }

    private fun List<QuerySwept>.count(reason: QueryFallback): Int =
        sumOf { corpus -> corpus.fallbacks.count { it.reason == reason } }
}

/**
 * **Zero host-range violations across every swept corpus** — the pass condition, stated exactly and
 * failing the run.
 *
 * It is not a rate and it is not tunable. The identity it enforces is that an injected occurrence's
 * host range maps back to exactly that occurrence: one that does not is a placeholder about to be
 * written over host text that is not the name. That is a design failure, so the release waits.
 */
internal fun assertNoHostRangeViolation(swept: List<QuerySwept>) {
    val violations = swept.flatMap { it.violations }
    check(violations.isEmpty()) {
        "${violations.size} injected occurrence(s) have a host range that does not map back to the name " +
            "it was computed for. That is a placeholder written over host text that is not that name — a " +
            "design failure rather than a tuning problem, and the release waits on it. The report lists " +
            "each one."
    }
}

/**
 * **A run that read no query fragment is a failed run, not a clean one** — and so is a corpus that
 * yielded no injected file at all.
 *
 * Asserted **before** the pass condition, and that order is the whole point: zero violations out of zero
 * fragments is a harness reporting that it found nothing wrong with nothing, which is the fail-green
 * this instrument exists one level out from.
 *
 * The two messages are separate because the two causes are: a corpus whose hosts yielded no injected
 * file is usually a corpus opened the wrong way or an IDE without the plugin that injects, while a
 * corpus with injected fragments and no query among them is a corpus with no queries in it.
 */
internal fun assertTheRunReadAQuery(swept: List<QuerySwept>) {
    check(swept.isNotEmpty()) { "No corpus was swept, so this run measured nothing at all." }

    swept.forEach { corpus ->
        check(corpus.hostsWithInjection > 0) {
            "${corpus.corpus} yielded no injected file from any of its ${corpus.hosts} injection host(s) " +
                "in ${corpus.files} file(s). A run that reports zero is a failed run rather than a clean " +
                "one: nothing below it could have found anything. Check that the corpus is opened by its " +
                "`.ipr` and that the IDE running this has the plugins that inject."
        }
        check(corpus.queryFragments > 0) {
            "${corpus.corpus} holds ${corpus.fragments} injected fragment(s) and no query among them, so " +
                "no container read anything here. That is a finding about this corpus — a well-known " +
                "sample application once came back exactly like this — and a failed run rather than a " +
                "clean one."
        }
    }
}

/**
 * **The coverage floor, and it is a floor rather than a threshold**: if the containers decompose no
 * fragment in any swept corpus, the release waits — not on tuning, but because there is nothing to
 * describe. Any non-zero result is a copy problem, not a release problem.
 *
 * The other two are the harness asserting it is measuring at all: a run with no claimed name has
 * nothing under its rates, and a run with no fallback has never seen the half of the behaviour every
 * one of those rates is about.
 */
internal fun assertTheContainersReadRealFragments(swept: List<QuerySwept>) {
    check(swept.sumOf { it.decomposed } > 0) {
        "No fragment was decomposed in any swept corpus. The release waits on it — not on tuning, but " +
            "because there is nothing to describe. Any non-zero result is a copy problem rather than a " +
            "release problem."
    }
    check(swept.sumOf { it.claimedOccurrences } > 0) {
        "Fragments were decomposed and not one name was claimed in them, so every rate in this report " +
            "stands over nothing the containers actually read."
    }
    check(swept.any { it.fallbacks.isNotEmpty() }) {
        "Not one fragment fell back in any swept corpus. Every rate in this report is a rate of falling " +
            "back, so a run that never saw one has not seen the half of the behaviour it is measuring."
    }
}

/**
 * Everything the run stands or falls on, **in the order it has to be asked in**: the denominator first,
 * then the pass condition, then the floor. Written once here rather than by each caller, because the
 * order is the claim.
 */
internal fun assertTheRunHolds(swept: List<QuerySwept>) {
    assertTheRunReadAQuery(swept)
    assertNoHostRangeViolation(swept)
    assertTheContainersReadRealFragments(swept)
}

/**
 * **A corpus is opened by its `.ipr` file, never by its directory**, and this is what says so rather
 * than a sentence in a README.
 *
 * Opening the directory of a corpus project yields zero content roots and reports no source files,
 * **silently** — which is a sweep that reads nothing and, without [assertTheRunReadAQuery] behind it,
 * a sweep that passes. It has cost a session once already, so it is a refusal rather than a note.
 *
 * The corpus is refused inside this repository for the reason the report is: what goes in and what
 * comes out both stay outside the tree, because a file being *inside* the repository is what makes
 * pasting it feel safe.
 *
 * @param path what the command line named
 * @param repository this repository's root, which the corpus may not be inside
 * @return the corpus's own `.ipr`, absolute and normalized
 */
internal fun queryCorpusFile(path: Path, repository: Path): Path {
    val corpus = path.toAbsolutePath().normalize()

    check(Files.exists(corpus)) { "$corpus does not exist, so there is no corpus to sweep." }
    check(Files.isRegularFile(corpus) && corpus.fileName.toString().endsWith(IPR)) {
        "$corpus is not a `$IPR` file. A corpus project is opened by its `$IPR`, never by its " +
            "directory: a directory-opened project comes up with zero content roots and reports no " +
            "source file at all, silently, and what is then swept is nothing."
    }
    check(!corpus.startsWith(repository.toAbsolutePath().normalize())) {
        "$corpus is inside this repository ($repository). A corpus is somebody's real code, and it " +
            "stays outside this tree — not merely gitignored, because a file being inside the " +
            "repository is what makes pasting it feel safe."
    }
    return corpus
}

/** What a corpus project is opened by. */
private const val IPR = ".ipr"
