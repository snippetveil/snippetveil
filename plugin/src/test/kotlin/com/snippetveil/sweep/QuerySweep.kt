package com.snippetveil.sweep

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BareTestFixtureTestCase
import com.snippetveil.plugin.InjectedFragment
import com.snippetveil.plugin.QueryContainer
import com.snippetveil.plugin.RegisteredContainers
import com.snippetveil.plugin.sql.SqlContainer
import com.snippetveil.plugin.sql.SqlDecision
import com.snippetveil.plugin.sql.SqlFallback
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * **The corpus instrument's query half: real queries in, a triage list out, and the code never moves.**
 *
 * It runs the query containers over every fragment the IDE injects into the Java literals of one or
 * more real projects, and writes what they made of them: a list of the reasons fragments fell back,
 * and the four rates four separate decisions asked for. **Both failure shapes the query half exists to
 * guard against were found by an instrument over real code, not by anyone imagining them**, which is
 * why this is a deliverable rather than a nicety.
 *
 * ### Instrument, not test
 *
 * It opens a corpus by path from a Gradle property and is **skipped entirely when that property is
 * absent**, so public CI cannot demand it — and `assertTheSweepIsNeverRunInCi` in the root
 * `build.gradle.kts` says CI never asks, while the task's own guard says it would refuse if asked.
 * Inputs and outputs live **outside the repository tree** — not merely gitignored, because a file
 * being inside the repository is what makes pasting it feel safe. See [queryCorpusFile] and
 * [sweepReportPath]. What comes out is a conclusion a human reaches, not a green or red cell.
 *
 * ### What it asserts, and what it merely measures
 *
 * The rates gate nothing; [QuerySweepReport] says why a threshold is refused. What the run stands or
 * falls on is [assertTheRunHolds]: the denominator first — **a run reporting zero injected fragments
 * is a failed run rather than a clean one** — then **zero host-range violations across every swept
 * corpus**, then the coverage floor.
 *
 * The report is written **before** those assertions are made. An instrument is run to be read, and a
 * run that fails its pass condition is exactly the run whose triage list somebody needs.
 *
 * ### This half is permanent
 *
 * The measurement spikes that produced these corpora deleted their harnesses after reading them,
 * correctly, because they were spikes. A **release gate** rebuilt from scratch before each release is
 * an instruction to a future session, which is the category a self-asserting harness exists to replace.
 *
 * ### Running it
 *
 * ```
 * ./gradlew querySweep -PplatformProfile=latest -PquerySweepCorpus=/path/to/a/corpus.ipr
 * ```
 *
 * More than one corpus is separated the way a classpath is — `:` on Unix — and every one of them is
 * swept in the same run. **The `latest` profile is not decoration**: the plugins that inject SQL and
 * the persistence query languages are the unified IDE's, and the run refuses without them rather than
 * reporting a corpus of queries nobody injected into. `-PquerySweepReportDir` moves the report; the
 * default is `~/snippetveil-sweep`, and any location inside this repository or inside a swept corpus
 * is refused outright.
 *
 * ### Two mechanism facts, carried rather than rediscovered
 *
 *  - **A corpus project is opened by its `.ipr` file, never by the directory.** Opening a directory
 *    yields zero content roots and reports no source files, *silently*. [queryCorpusFile] refuses one.
 *  - **The JPA plugin id is `com.intellij.javaee.jpa`**, not `com.intellij.jpa`; no plugin with the
 *    latter id exists. The build's `queryContributors` is where that id is spelled.
 */
class QuerySweep : BareTestFixtureTestCase() {

    @Test
    fun `sweep a corpus for queries`() {
        val named = System.getProperty(CORPUS_PROPERTY)

        // Skipped, not failed. The Gradle task skips too, so this is the case where somebody ran the
        // class directly; both say the same thing, and neither says "broken".
        assumeTrue(
            "No -P$CORPUS_PROPERTY was given, so there is no corpus to sweep.",
            !named.isNullOrBlank(),
        )

        val repository = Paths.get(
            System.getProperty(REPOSITORY_PROPERTY)
                ?: error(
                    "-D$REPOSITORY_PROPERTY was not set, so this run does not know which tree the corpus " +
                        "and the report have to stay out of. The `querySweep` Gradle task sets it; set it " +
                        "by hand to run this class directly."
                ),
        )
        val corpora = named.split(File.pathSeparator).filter { it.isNotBlank() }
            .map { queryCorpusFile(Paths.get(it), repository) }

        val report = sweepReportPath(
            reportDirectory = Paths.get(System.getProperty(REPORT_DIRECTORY_PROPERTY) ?: defaultReportDirectory()),
            fileName = "snippetveil-query-sweep-${LocalDateTime.now().format(STAMP)}.txt",
            forbidden = buildMap {
                put("the SnippetVeil repository", repository)
                corpora.forEach { put("the swept corpus ${it.fileName}", it.parent) }
            },
        )

        // **Asserted before a line is read, not after.** Without the plugins that inject, every host
        // in every corpus yields nothing, every rate is a share of zero, and the run says nothing at
        // all — while looking exactly like a corpus with no queries in it.
        assertTheInjectingPluginsAreHere()

        val swept = corpora.map { sweep(it) }
        val rendered = QuerySweepReport(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME), swept).render()

        Files.createDirectories(report.parent)
        Files.writeString(report, rendered)
        say("")
        say("The report is at: $report")
        say("It lists real identifiers and real paths. Do not paste it anywhere.")
        say("")

        // Last, and after the report is on disk: a run that fails here is exactly the run whose triage
        // list somebody needs to read.
        assertTheRunHolds(swept)
    }

    /** One corpus, from its `.ipr` to what the containers made of every fragment in it. */
    private fun sweep(corpus: Path): QuerySwept {
        say("Opening $corpus …")
        val project = PlatformTestUtil.loadAndOpenProject(corpus, testRootDisposable)
        attachTheRunningJdkUnderTheNameTheProjectExpects(project, testRootDisposable)?.let {
            say("Attached the running JDK as '$it'; the corpus's own SDK is not configured in this process.")
        }

        val root = corpus.parent
        val files = sourceFilesOf(project, root, SOURCE_EXTENSIONS)
        check(files.isNotEmpty()) {
            "No Java source file was found in the content of $corpus. A corpus project is opened by its " +
                "`.ipr` and not by its directory, and a project that came up with no content root reports " +
                "exactly this — no files, and no complaint."
        }
        say("${files.size} Java source file(s) in project content.")

        val swept = QueryPass(project, ContainersWithReasons).over(
            corpus = corpus.toString(),
            files = files,
            pathOf = { file -> runCatching { root.relativize(Paths.get(file.path)).toString() }.getOrDefault(file.path) },
            progress = { done -> if (done % 200 == 0) say("  … $done/${files.size}") },
        )

        // Counts only, and never a name: a table spelling is the corpus's, and the console is the
        // easiest thing in the world to copy out of.
        say(
            "  ${swept.hostsWithInjection} of ${swept.hosts} host(s) injected; ${swept.queryFragments} query " +
                "fragment(s) of ${swept.fragments}; ${swept.decomposed} decomposed, ${swept.fallbacks.size} " +
                "fell back, ${swept.claimedOccurrences} name(s) claimed, ${swept.violations.size} host-range " +
                "violation(s), ${swept.failures.size} file(s) threw.",
        )
        return swept
    }

    /**
     * **The database plugin is loaded and the SQL container is registered on this IDE**, or the run
     * refuses.
     *
     * Two facts rather than one, because the second is the one that decides anything: the container
     * ships in an optional descriptor that the platform loads only where `com.intellij.database` is,
     * so a plugin that is present but not loaded and a descriptor that did not load look the same from
     * here — a sweep that reads no SQL at all. Both are named so that a refusal says which happened.
     */
    private fun assertTheInjectingPluginsAreHere() {
        val database = PluginManagerCore.getPlugin(PluginId.getId(DATABASE_PLUGIN))
        check(database != null && database.isEnabled) {
            "The `$DATABASE_PLUGIN` plugin is ${if (database == null) "not installed" else "disabled"} in this " +
                "IDE, so nothing injects SQL into anything and every corpus below would come back empty. " +
                "Run the sweep on the unified IDE: ./gradlew querySweep -PplatformProfile=latest -P$CORPUS_PROPERTY=…"
        }
        check(RegisteredContainers.INJECTED_CONTAINERS.extensionList.isNotEmpty()) {
            "The database plugin is loaded and no container is registered on " +
                "`${RegisteredContainers.INJECTED_CONTAINERS.name}`, so this plugin's optional descriptor did " +
                "not load and no SQL fragment can decompose however many the corpus holds."
        }
        say(
            "The database plugin is loaded, and ${RegisteredContainers.INJECTED_CONTAINERS.extensionList.size} " +
                "container(s) are registered beside the query one.",
        )
    }

    /** Progress, and never a name. See the hazard note on [QuerySweepReport]. */
    private fun say(line: String) = println("[query sweep] $line")

    private companion object {

        /** Set by the `querySweep` Gradle task from `-PquerySweepCorpus`. */
        const val CORPUS_PROPERTY = "snippetveil.query.sweep.corpus"

        /** Set from `-PquerySweepReportDir`, and defaulted below. */
        const val REPORT_DIRECTORY_PROPERTY = "snippetveil.query.sweep.reportDirectory"

        /**
         * This repository's root, handed in by the Gradle task rather than worked out here — a process
         * that guessed at where it was running from could guess the report into the tree it is supposed
         * to stay out of. **Required**, for the reason the leak half requires it: a missing value cannot
         * mean *no tree to avoid*.
         */
        const val REPOSITORY_PROPERTY = "snippetveil.sweep.repository"

        /** The plugin that injects SQL, and whose PSI the SQL container reads. */
        const val DATABASE_PLUGIN = "com.intellij.database"

        /** What the seam reaches. A fragment injected into a Kotlin or an XML host decomposes nowhere yet. */
        val SOURCE_EXTENSIONS = listOf(".java")

        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        /** `~/snippetveil-sweep`: outside every checkout, and where the leak half's reports already land. */
        fun defaultReportDirectory(): String = System.getProperty("user.home") + "/snippetveil-sweep"
    }
}

/**
 * **The containers, asked exactly as the product asks them, plus the reason a SQL fragment fell back.**
 *
 * The reading is [RegisteredContainers]' — the product's own seam, so that what this instrument
 * measures is what a user's `Copy Anonymized` would do with the same literal. The *reason* is
 * `SqlContainer.decide`'s, which is the one place a fallback's reason is kept; the container itself
 * answers `null` and says nothing, because a fallback is silent by design and a message about it would
 * tell the user which plugins their IDE has.
 *
 * It lives here, in the class the merge gate never runs, because every reason it reads is spelled in
 * the database plugin's types. [QueryPass] names none of them, which is what lets the harness itself be
 * exercised in `check`.
 */
private object ContainersWithReasons : FragmentReader {

    override fun read(fragment: InjectedFragment): FragmentVerdict {
        RegisteredContainers.read(fragment)?.let { return FragmentVerdict.Decomposed(it) }

        return when (val decision = SqlContainer.decide(fragment)) {
            // The SQL container read a fragment the registered containers did not, which can only mean
            // it is not among them — a wiring failure, and one that would otherwise be reported as a
            // corpus that decomposed nothing.
            is SqlDecision.Decomposed -> error(
                "The SQL container read this fragment and the registered containers did not, so it is not " +
                    "registered in this IDE and nothing measured here is what a user would get.",
            )
            is SqlDecision.FellBack ->
                if (decision.reason == SqlFallback.NOT_SQL) notSql(fragment) else fellBack(decision)
        }
    }

    /**
     * A fragment the SQL container was never about: a query in one of the persistence query languages,
     * which the query container reads and keeps no reason for — or a language no container reads at
     * all, which is not a query and is outside every rate.
     */
    private fun notSql(fragment: InjectedFragment): FragmentVerdict {
        val language = fragment.file.language.id
        return if (language in QueryContainer.QUERY_LANGUAGES) {
            FragmentVerdict.FellBack(QueryFallback.NOTHING_WAS_READ, language)
        } else {
            FragmentVerdict.NotAQuery
        }
    }

    private fun fellBack(decision: SqlDecision.FellBack): FragmentVerdict =
        FragmentVerdict.FellBack(queryFallbackOf(decision.reason), decision.elementType)
}

/**
 * The SQL container's reason, as the triage vocabulary spells it — **by name, so that a reason it
 * gains and this list lacks fails loudly** rather than arriving as a row nobody can read.
 * `QuerySweepReportTest` holds that every one of them has a name here, without a corpus to sweep.
 */
internal fun queryFallbackOf(reason: SqlFallback): QueryFallback =
    requireNotNull(QueryFallback.entries.firstOrNull { it.name == reason.name }) {
        "The SQL container answered `$reason`, which the query sweep's triage list has no row for."
    }
