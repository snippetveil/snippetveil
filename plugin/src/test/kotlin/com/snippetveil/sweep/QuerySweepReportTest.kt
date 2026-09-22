package com.snippetveil.sweep

import com.snippetveil.core.SymbolRole
import com.snippetveil.plugin.sql.SqlFallback
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * **What the query sweep reports, what it refuses, and what it holds the release on** — all three
 * asserted here, without a corpus to sweep, because a rule nobody could test without one would be a
 * rule nobody tests.
 *
 * The order of the claims below is the order the run asks them in, and that order is itself a claim:
 * **zero violations out of zero fragments is a harness reporting that it found nothing wrong with
 * nothing.** So the denominator is asserted first, and a run that read no query fragment fails.
 */
class QuerySweepReportTest {

    @Test
    fun `the report says what it is before it says anything else`() {
        val head = QuerySweepReport(WHEN, listOf(swept())).render().lineSequence().take(10).joinToString("\n").lowercase()

        assertTrue("real identifiers" in head) { "The report does not say what it contains:\n$head" }
        assertTrue("do not paste" in head) { "The report does not say not to paste it:\n$head" }
        assertTrue("synthetic" in head) { "The report does not say findings become synthetic fixtures:\n$head" }
    }

    /**
     * **It reports its own denominator, and that is not bookkeeping.** Every rate below is a share of
     * the query fragments the run read, and a reader who cannot see that number cannot tell a report
     * over a corpus from a report over nothing.
     */
    @Test
    fun `the report names every denominator the rates stand on`() {
        val rendered = QuerySweepReport(WHEN, listOf(swept())).render()

        assertTrue("Injection hosts            : 40" in rendered) { rendered }
        assertTrue("Hosts with an injected file: 20" in rendered) { rendered }
        assertTrue("Injected fragments         : 20" in rendered) { rendered }
        assertTrue("Query fragments            : 18  <- the denominator" in rendered) { rendered }
        assertTrue("SQL: 18" in rendered) { "The fragments are not broken down by language:\n$rendered" }
    }

    /** The four rates four separate decisions asked for, each over the fragments and each spelled out. */
    @Test
    fun `the four rates are reported, each over the fragments the run read`() {
        val rendered = QuerySweepReport(
            WHEN,
            listOf(
                swept(
                    queryFragments = 20,
                    fallbacks = listOf(
                        fellBack(QueryFallback.UNPROJECTABLE_POSITION),
                        fellBack(QueryFallback.BIND_PARAMETER),
                        fellBack(QueryFallback.BIND_PARAMETER),
                        fellBack(QueryFallback.IDENTIFIER_KEYWORD_OUTSIDE_A_CLAIM),
                    ),
                    tableSpellings = mapOf("customers" to setOf("\"Customers\"", "customers")),
                ),
            ),
        ).render()

        assertTrue("Unprojectable position : 1 of 20 (5.0%)" in rendered) { rendered }
        assertTrue("Identifier keyword     : 1 of 20 (5.0%)" in rendered) { rendered }
        assertTrue("Bind parameter         : 2 of 20 (10.0%)" in rendered) { rendered }
        assertTrue("Two spellings of one table: met" in rendered) { rendered }
        assertTrue("customers is written \"Customers\", customers" in rendered) { rendered }
    }

    /**
     * **Every rate is reported and gates nothing**, and the report says so where the rates are — not
     * in a comment in a source file nobody reading the output will open.
     */
    @Test
    fun `the report says the rates gate nothing, and why there is no threshold`() {
        val rendered = QuerySweepReport(WHEN, listOf(swept())).render().lowercase()

        assertTrue("gates nothing" in rendered) { rendered }
        assertTrue("denominator" in rendered) { rendered }
        assertTrue("telemetry" in rendered) { "The report does not say why the right denominator cannot be had:\n$rendered" }
    }

    /**
     * **No rate has a threshold anywhere in the harness**, asserted as behaviour rather than as a
     * promise: a run whose every rate is as bad as it can be — every fragment but one falling back, one
     * table written two ways — still holds, because nothing compares a rate to anything.
     */
    @Test
    fun `no rate gates the run, however bad it is`() {
        val everyRateAtItsWorst = swept(
            queryFragments = 4,
            decomposed = 1,
            claimed = mapOf(SymbolRole.TABLE to 2),
            fallbacks = listOf(
                fellBack(QueryFallback.UNPROJECTABLE_POSITION),
                fellBack(QueryFallback.BIND_PARAMETER),
                fellBack(QueryFallback.IDENTIFIER_KEYWORD_OUTSIDE_A_CLAIM),
            ),
            tableSpellings = mapOf("customers" to setOf("\"Customers\"", "customers")),
        )

        assertTheRunHolds(listOf(everyRateAtItsWorst))
    }

    /** The triage list, which is what a human reads the report for: a reason, a count and where to look. */
    @Test
    fun `the triage list groups the fallback reasons, commonest first, and says where one is`() {
        val rendered = QuerySweepReport(
            WHEN,
            listOf(
                swept(
                    fallbacks = listOf(
                        fellBack(QueryFallback.BIND_PARAMETER, at = "SQL_QUESTION_MARK", path = "src/Repo.java"),
                        fellBack(QueryFallback.BIND_PARAMETER, at = "SQL_QUESTION_MARK", path = "src/Other.java"),
                        fellBack(QueryFallback.UNCLAIMED_TOKEN, at = "SQL_STRING_TOKEN", path = "src/Third.java"),
                    ),
                ),
            ),
        ).render()

        val triage = rendered.substringAfter("── fallback reasons")
        assertTrue(
            triage.indexOf("2  BIND_PARAMETER on SQL_QUESTION_MARK") < triage.indexOf("1  UNCLAIMED_TOKEN"),
            { "The triage list is not commonest first:\n$triage" },
        )
        assertTrue("src/Repo.java" in triage) { "The triage list names no file to go and look at:\n$triage" }
    }

    /**
     * **Zero host-range violations is the pass condition**, so the line saying so is printed on the
     * clean run too. A number that appears only on the bad day says nothing at all by its absence.
     */
    @Test
    fun `the report states the host-range violation count even when it is zero`() {
        val rendered = QuerySweepReport(WHEN, listOf(swept())).render()

        assertTrue("── host-range violations" in rendered) { rendered }
        assertTrue("0. Zero is the pass condition" in rendered) { rendered }
    }

    @Test
    fun `a host-range violation is reported with where it was and what the identity said`() {
        val rendered = QuerySweepReport(
            WHEN,
            listOf(swept(violations = listOf(HostRangeViolation("src/Repo.java", "SQL", "… maps back to nothing …")))),
        ).render()

        assertTrue("src/Repo.java" in rendered) { rendered }
        assertTrue("maps back to nothing" in rendered) { rendered }
    }

    /**
     * **A single occurrence whose host range does not map back holds the release.** Exactly one, and
     * exactly zero on the other side: it is a design failure rather than a tuning problem.
     */
    @Test
    fun `one host-range violation fails the run`() {
        val refused = assertThrows(IllegalStateException::class.java) {
            assertNoHostRangeViolation(listOf(swept(violations = listOf(HostRangeViolation("src/Repo.java", "SQL", "…")))))
        }

        assertTrue("design failure" in refused.message.orEmpty()) { refused.message.orEmpty() }
        assertNoHostRangeViolation(listOf(swept()))
    }

    /**
     * **A run reporting zero injected fragments is a failed run rather than a clean one.** A sweep
     * pointed at a well-known sample application got zero hosts with SQL, and that was a finding —
     * under an instrument reporting only failures it would have been a pass.
     */
    @Test
    fun `a corpus whose hosts yielded no injected file fails the run`() {
        val refused = assertThrows(IllegalStateException::class.java) {
            assertTheRunReadAQuery(listOf(swept(hostsWithInjection = 0, fragmentsByLanguage = emptyMap(), queryFragments = 0)))
        }

        assertTrue("failed run" in refused.message.orEmpty()) { refused.message.orEmpty() }
        assertTrue(".ipr" in refused.message.orEmpty()) { "The refusal does not say what to check:\n${refused.message}" }
    }

    @Test
    fun `a corpus with injected fragments and no query among them fails the run`() {
        val refused = assertThrows(IllegalStateException::class.java) {
            assertTheRunReadAQuery(listOf(swept(fragmentsByLanguage = mapOf("RegExp" to 4), queryFragments = 0, decomposed = 0)))
        }

        assertTrue("failed run" in refused.message.orEmpty()) { refused.message.orEmpty() }
    }

    /**
     * **The order is the claim.** A run that read nothing has zero violations too, so an instrument
     * that asked the pass condition first would report a corpus it never read as clean.
     */
    @Test
    fun `the denominator is asked before the pass condition, so an empty run cannot pass it`() {
        val nothingRead = swept(hostsWithInjection = 0, fragmentsByLanguage = emptyMap(), queryFragments = 0, decomposed = 0)

        assertNoHostRangeViolation(listOf(nothingRead))
        val refused = assertThrows(IllegalStateException::class.java) { assertTheRunHolds(listOf(nothingRead)) }
        assertTrue("failed run" in refused.message.orEmpty()) { refused.message.orEmpty() }
    }

    /**
     * **The coverage floor, and it is a floor rather than a threshold**: no fragment decomposed in any
     * corpus and there is nothing to describe, so the release waits. Any non-zero result passes it.
     */
    @Test
    fun `a run that decomposed nothing anywhere fails the floor, and one decomposition passes it`() {
        val refused = assertThrows(IllegalStateException::class.java) {
            assertTheContainersReadRealFragments(listOf(swept(decomposed = 0, claimed = emptyMap())))
        }

        assertTrue("nothing to describe" in refused.message.orEmpty()) { refused.message.orEmpty() }
        assertTheContainersReadRealFragments(listOf(swept(decomposed = 1, claimed = mapOf(SymbolRole.TABLE to 1))))
    }

    /** The harness measuring itself: a run with no claimed name has nothing under its rates. */
    @Test
    fun `a run that claimed no name fails`() {
        assertThrows(IllegalStateException::class.java) {
            assertTheContainersReadRealFragments(listOf(swept(claimed = emptyMap())))
        }
    }

    /** And a run that never saw a fallback has not seen the half of the behaviour every rate is about. */
    @Test
    fun `a run where nothing ever fell back fails`() {
        val refused = assertThrows(IllegalStateException::class.java) {
            assertTheContainersReadRealFragments(listOf(swept(fallbacks = emptyList())))
        }

        assertTrue("fell back" in refused.message.orEmpty()) { refused.message.orEmpty() }
    }

    /**
     * **Every reason the SQL container can give has a row in the triage vocabulary.** The container's
     * reasons are its own, and a reason it gains that this list lacks would arrive mid-sweep as a
     * crash — or, worse, as a row nobody could read.
     */
    @Test
    fun `every reason the SQL container can give is a reason the triage list can name`() {
        SqlFallback.entries.forEach { reason ->
            assertEquals(reason.name, queryFallbackOf(reason).name, "The triage list has no row for $reason")
        }
    }

    /**
     * **A corpus project is opened by its `.ipr` file, never by the directory.** Opening a directory
     * yields zero content roots and reports no source files, silently — a sweep that reads nothing.
     */
    @Test
    fun `a corpus given as a directory is refused rather than opened silently empty`(@TempDir root: Path) {
        val directory = root.resolve("corpus").also { it.toFile().mkdirs() }
        directory.resolve("corpus.ipr").toFile().writeText("<project/>")

        val refused = assertThrows(IllegalStateException::class.java) {
            queryCorpusFile(directory, root.resolve("repository"))
        }

        assertTrue(".ipr" in refused.message.orEmpty()) { refused.message.orEmpty() }
        assertTrue("silently" in refused.message.orEmpty()) {
            "The refusal does not say what a directory does:\n${refused.message}"
        }
    }

    @Test
    fun `a corpus given as its ipr is taken`(@TempDir root: Path) {
        val corpus = root.resolve("corpus").also { it.toFile().mkdirs() }.resolve("corpus.ipr")
        corpus.toFile().writeText("<project/>")

        assertEquals(corpus.toAbsolutePath().normalize(), queryCorpusFile(corpus, root.resolve("repository")))
    }

    @Test
    fun `a corpus that does not exist is refused`(@TempDir root: Path) {
        assertThrows(IllegalStateException::class.java) {
            queryCorpusFile(root.resolve("nowhere.ipr"), root.resolve("repository"))
        }
    }

    /**
     * **Inputs live outside the repository tree**, not merely gitignored: a file being *inside* the
     * repository is what makes pasting it feel safe.
     */
    @Test
    fun `a corpus inside this repository is refused`(@TempDir root: Path) {
        val corpus = root.resolve("checkout/corpus").also { it.toFile().mkdirs() }.resolve("corpus.ipr")
        corpus.toFile().writeText("<project/>")

        val refused = assertThrows(IllegalStateException::class.java) { queryCorpusFile(corpus, root.resolve("checkout")) }

        assertTrue("outside this tree" in refused.message.orEmpty()) { refused.message.orEmpty() }
    }

    /**
     * **And so does the output.** The query report holds real table spellings and real paths, and it is
     * refused inside this repository and inside any swept corpus by the same rule the leak report is —
     * which is asserted here over this report's own name, because the rule is only worth having if this
     * half goes through it too.
     */
    @Test
    fun `the report may not be written inside this repository or inside a corpus`(@TempDir root: Path) {
        val repository = root.resolve("snippetveil")
        val corpus = root.resolve("corpus")

        assertThrows(IllegalStateException::class.java) {
            sweepReportPath(repository.resolve("build/reports"), REPORT, forbidden = trees(repository, corpus))
        }
        assertThrows(IllegalStateException::class.java) {
            sweepReportPath(corpus.resolve("notes"), REPORT, forbidden = trees(repository, corpus))
        }
        assertEquals(
            root.resolve("outside").resolve(REPORT),
            sweepReportPath(root.resolve("outside"), REPORT, forbidden = trees(repository, corpus)),
        )
    }

    private fun trees(repository: Path, corpus: Path): Map<String, Path> =
        mapOf("the SnippetVeil repository" to repository, "the swept corpus" to corpus)

    private fun swept(
        corpus: String = "/home/me/corpora/acme/acme.ipr",
        files: Int = 812,
        hosts: Int = 40,
        hostsWithInjection: Int = 20,
        fragmentsByLanguage: Map<String, Int> = mapOf("RegExp" to 2, "SQL" to 18),
        queryFragments: Int = 18,
        decomposed: Int = 10,
        claimed: Map<SymbolRole, Int> = mapOf(SymbolRole.TABLE to 12, SymbolRole.COLUMN to 30),
        fallbacks: List<QueryFallbackRow> = listOf(fellBack(QueryFallback.UNCLAIMED_TOKEN)),
        tableSpellings: Map<String, Set<String>> = mapOf("customers" to setOf("customers")),
        violations: List<HostRangeViolation> = emptyList(),
        failures: List<SweepFailure> = emptyList(),
    ) = QuerySwept(
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

    private fun fellBack(
        reason: QueryFallback,
        at: String? = null,
        path: String = "src/Repo.java",
        language: String = "SQL",
    ) = QueryFallbackRow(reason, at, path, language)

    private companion object {
        const val WHEN = "2026-09-22T12:00:00"
        const val REPORT = "snippetveil-query-sweep-20260922-120000.txt"
    }
}
