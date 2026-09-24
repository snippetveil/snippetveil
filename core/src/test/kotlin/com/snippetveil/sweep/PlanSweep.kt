package com.snippetveil.sweep

import com.snippetveil.core.PLAN_FORMATS
import com.snippetveil.core.PlanEngine
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * **The corpus instrument's plan half: real captures in, two zeros and a triage list out.**
 *
 * It runs [com.snippetveil.core.parsePlan] and [com.snippetveil.core.anonymize] over every capture in
 * one or more plan corpora, and writes what they made of them: the two zeros, three numbers that gate
 * nothing, and the list of spellings that reached an output.
 *
 * ### This half is an instrument for the *other* reason, and the difference is load-bearing
 *
 * > **Stated as a negative, because the secrecy machinery will otherwise be inherited wholesale:
 * > this half is an instrument because the oracle throws false positives by design, so it cannot be
 * > green or red — and for that reason alone. The argument that the report is the most sensitive file
 * > this project can produce does not apply here.**
 *
 * The captures are **agent-generated in Docker against throwaway schemas**. There is no leak in them,
 * and pretending otherwise would be cargo-cult secrecy that costs real usability: the report can be
 * pasted into a ticket, and the other two halves' reports cannot.
 *
 * **So this half carries no CI refusal of its own**, where the source and query halves both do. Their
 * second layer is argued from a real codebase reaching a machine nobody chose; there is no such
 * hazard here, and a guard copied across without its argument is a guard nobody can maintain. What
 * keeps a corpus-scale run out of CI is that there is nothing for it to read there — the run is
 * **skipped, not failed**, without `-PplanSweepCorpus` — and the root build's
 * `assertTheSweepIsNeverRunInCi`, which reads every `./gradlew` line in `.github/workflows/`. The
 * reason *that* rule covers this half is not secrecy either: **a CI cell over the whole corpus would
 * produce a number nobody reads attached to a list nobody triages.**
 *
 * ### The corpus still lives outside the tree, on two grounds that are not secrecy
 *
 * It is over a thousand captures across many formats, **regenerable from a kept generator**; and it
 * is **pinned to specific engine versions**, so a rebuild that differs is a *finding about format
 * drift* rather than a new baseline. Both inputs and outputs stay outside this repository — see
 * [planCorpusDirectory] and [planReportPath].
 *
 * ### Where the corpus's own claim about a capture comes from
 *
 * **A directory per label, and a capture per file inside it.** The directory names a vocabulary row
 * — `text`, `mysql-tabular`, `oracle-grid-text` — or a refusal message — `refusal-mariadb`. That
 * label is what the generator said it was producing, and **the parser is never asked**: a run that
 * asked the parser what a capture was and then checked the answer against itself would be green
 * whatever the parser did. A directory whose name is neither is a **finding**, not a skip.
 *
 * ### What holds the release, and what is merely reported
 *
 * [PlanSweepReport.assertTheRunHolds] is the pass condition: the denominator first, then the two
 * zeros, then the per-row denominator clause, then the coverage floor. The three numbers gate
 * nothing and **no threshold exists for any of them** — see [PlanSweepReport].
 *
 * ### Running it
 *
 * ```
 * ./gradlew planSweep -PplanSweepCorpus=/path/to/a/plan/corpus
 * ```
 *
 * More than one corpus is separated the way a classpath is. `-PplanSweepReportDir` moves the report;
 * the default is `~/snippetveil-sweep`, and any location inside this repository or inside a swept
 * corpus is refused outright.
 */
class PlanSweep {

    @Test
    fun `sweep a corpus of plans`() {
        val named = System.getProperty(CORPUS_PROPERTY)

        // Skipped, not failed. The Gradle task skips too, so this is the case where somebody ran the
        // class directly; both say the same thing, and neither says "broken".
        assumeTrue(!named.isNullOrBlank(), "No -PplanSweepCorpus was given, so there is no corpus to sweep.")

        val repository = Paths.get(
            System.getProperty(REPOSITORY_PROPERTY)
                ?: error(
                    "-D$REPOSITORY_PROPERTY was not set, so this run does not know which tree the corpus " +
                        "and the report have to stay out of. The `planSweep` Gradle task sets it; set it " +
                        "by hand to run this class directly."
                ),
        )
        val corpora = named.split(File.pathSeparator).filter { it.isNotBlank() }
            .map { planCorpusDirectory(Paths.get(it), repository) }

        val report = planReportPath(
            reportDirectory = Paths.get(System.getProperty(REPORT_DIRECTORY_PROPERTY) ?: defaultReportDirectory()),
            fileName = "snippetveil-plan-sweep-${LocalDateTime.now().format(STAMP)}.txt",
            forbidden = buildMap {
                put("the SnippetVeil repository", repository)
                corpora.forEach { put("the swept corpus at $it", it) }
            },
        )

        // **The rules prove they can fail before a capture is opened.** A red path that is never
        // exercised decays into a check that always passes, and this one is the only layer between a
        // name reaching an output and a report that says nothing survived.
        for (engine in PlanEngine.entries) {
            val rider = riderOf(engine) ?: continue
            val asserted = PlanLeakOracle.proveTheRulesCanFail(rider)
            say("The oracle proved ${engine.printed}'s rules can fail, in $asserted assertion(s).")
        }

        val swept = corpora.map(::sweep)
        val rendered = PlanSweepReport(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME), swept)

        val text = rendered.render()
        Files.createDirectories(report.parent)
        Files.writeString(report, text)
        say("")
        say("The report is at: $report")
        say("It is not sensitive. The captures behind it hold nothing anybody owns.")
        say("")

        // Last, and after the report is on disk: a run that fails here is exactly the run whose
        // triage list somebody needs to read.
        rendered.assertTheRunHolds()
    }

    /** One corpus, from its directories to what the product made of every capture in them. */
    private fun sweep(corpus: Path): PlanSwept {
        say("Opening $corpus …")
        val outcomes = mutableListOf<PlanCaptureOutcome>()
        val unlabelled = mutableListOf<String>()

        for (directory in corpus.listDirectoryEntries().filter { it.isDirectory() }.sortedBy { it.name }) {
            val label = captureLabelOf(directory.name)
            if (label == null) {
                unlabelled += directory.name
                continue
            }
            val files = directory.listDirectoryEntries().filter { it.isRegularFile() }.sortedBy { it.name }
            for (file in files) {
                val capture = PlanCapture(
                    name = "${directory.name}/${file.name}",
                    label = label,
                    text = Files.readString(file),
                    origin = PlanCaptureOrigin.CORPUS,
                )
                outcomes += outcomeOf(capture)
            }
            say("  ${directory.name}: ${files.size} capture(s)")
        }

        say("  ${outcomes.size} capture(s) over ${PLAN_FORMATS.size} vocabulary row(s).")
        return PlanSwept(corpus.toString(), outcomes, unlabelled)
    }

    /** Progress. Unlike the other halves, nothing printed here is anybody's to withhold. */
    private fun say(line: String) = println("[plan sweep] $line")

    private companion object {

        /** Set by the `planSweep` Gradle task from `-PplanSweepCorpus`. */
        const val CORPUS_PROPERTY = "snippetveil.plan.sweep.corpus"

        /** Set from `-PplanSweepReportDir`, and defaulted below. */
        const val REPORT_DIRECTORY_PROPERTY = "snippetveil.plan.sweep.reportDirectory"

        /**
         * This repository's root, handed in by the Gradle task rather than worked out here. Required,
         * for the reason the other halves require it: a missing value cannot mean *no tree to avoid*.
         */
        const val REPOSITORY_PROPERTY = "snippetveil.sweep.repository"

        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        /** `~/snippetveil-sweep`: outside every checkout, and where the other halves' reports land. */
        fun defaultReportDirectory(): String = System.getProperty("user.home") + "/snippetveil-sweep"
    }
}

/**
 * The corpus at [named], **refused where it is inside [repository]**.
 *
 * The refusal is not about secrecy — there is nothing in a capture to keep — it is about the two
 * grounds the corpus is outside the tree on: a thousand captures regenerable from a kept generator,
 * pinned to engine versions, so that **a rebuild that differs is a finding rather than a new
 * baseline**. A corpus committed into the tree is a baseline nobody can tell from a regeneration.
 */
internal fun planCorpusDirectory(named: Path, repository: Path): Path {
    val corpus = named.toAbsolutePath().normalize()
    check(corpus.isDirectory()) {
        "-PplanSweepCorpus named $corpus, which is not a directory. A plan corpus is a directory per " +
            "format row or refusal message, with the captures inside it."
    }
    check(!corpus.startsWith(repository.toAbsolutePath().normalize())) {
        "The corpus at $corpus is inside this repository. It lives outside the tree so that a " +
            "regeneration that differs is a finding about format drift rather than a new baseline."
    }
    return corpus
}

/** The report's path, **refused where it lands in any tree it may not land in**. */
internal fun planReportPath(reportDirectory: Path, fileName: String, forbidden: Map<String, Path>): Path {
    val directory = reportDirectory.toAbsolutePath().normalize()
    for ((what, tree) in forbidden) {
        check(!directory.startsWith(tree.toAbsolutePath().normalize())) {
            "The report would be written to $directory, which is inside $what. Inputs and outputs " +
                "both live outside every tree this run reads."
        }
    }
    return directory.resolve(fileName)
}
