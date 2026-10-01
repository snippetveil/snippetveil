package com.snippetveil.sweep

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BareTestFixtureTestCase
import com.snippetveil.core.AnonymizationSettings
import com.snippetveil.plugin.InternalLibrarySettings
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * **The corpus instrument's trace half: real traces in, a triage list out, and the traces never move.**
 *
 * The population `Anonymize Stack Trace…` exists for cannot be enumerated into fixtures — a
 * colleague's paste, production output, Spring proxies, version skew. So this runs the action's core
 * path over a file of **real traces**, resolved against the project they came from, and reads every
 * output with the trace leak oracle. See [TracePass] for the loop and [TraceSweepReport] for what it
 * writes.
 *
 * ### Instrument, not test
 *
 * The oracle is blunt and false-positive-prone on purpose, so its hits are **listed for a human to
 * triage and never fail the run**. It is skipped entirely without `-PtraceSweepFile`, and it is
 * never run in CI — `assertTheSweepIsNeverRunInCi` in the root build says CI never asks, and the
 * task's own guard says it would refuse if asked.
 *
 * ### Committed trace fixtures stay 100% synthetic
 *
 * *"Grab a real trace, it's just a stack trace"* is a far easier mistake than committing an anonymized
 * corpus, and a trace is the artifact people drop into an issue without thinking. **A hit found here
 * earns a synthetic fixture reproducing its shape, never the trace that revealed it.**
 *
 * ### Its input lives outside the tree, alongside its output
 *
 * The other halves point at a codebase; this one's input is a file somebody assembles, and a file
 * somebody assembles will otherwise get assembled *in* the repository. So the trace file is refused if
 * it resolves inside this repository — see [traceSweepFile] — and the report is refused there and inside
 * the project the traces are resolved against, as the other halves' reports are.
 *
 * ### Running it
 *
 * ```
 * ./gradlew traceSweep -PtraceSweepFile=/path/to/traces.txt -PtraceSweepProject=/path/to/the/checkout
 * ./gradlew traceSweep -PplatformProfile=k2 -PtraceSweepFile=… -PtraceSweepProject=…
 * ```
 *
 * The second is the one a project with Kotlin in it needs, for the reason the source half's does —
 * see [refuseKotlinThatCannotBeSwept].
 *
 * Traces in the file are separated by a line of three or more hyphens — see [tracesIn]. The project is
 * the one the traces came from, since a trace resolved against nothing is the rule applied to a foreign
 * trace: every frame `Unknown`, and nothing for the oracle to find. `-PtraceSweepReportDir` moves the
 * report; the default is `~/snippetveil-sweep`.
 */
class TraceSweep : BareTestFixtureTestCase() {

    @Test
    fun `sweep a file of real traces`() {
        val named = System.getProperty(FILE_PROPERTY)

        // Skipped, not failed. The Gradle task skips too, so this is the case where somebody ran the
        // class directly; both say the same thing, and neither says "broken".
        assumeTrue("No -PtraceSweepFile was given, so there are no traces to sweep.", !named.isNullOrBlank())

        val repository = Paths.get(
            System.getProperty(REPOSITORY_PROPERTY)
                ?: error(
                    "-D$REPOSITORY_PROPERTY was not set, so this run does not know which tree the trace " +
                        "file and the report have to stay out of. The `traceSweep` Gradle task sets it; set " +
                        "it by hand to run this class directly."
                ),
        )
        // **First, before anything is opened or read**: an input inside the tree is refused however
        // little else about the run is right.
        val traceFile = traceSweepFile(Paths.get(named), repository)

        val projectPath = Paths.get(
            System.getProperty(PROJECT_PROPERTY)
                ?: error(
                    "No -PtraceSweepProject was given. A trace is resolved against the project it came " +
                        "from, and resolved against nothing it is every frame `Unknown` and nothing for " +
                        "the oracle to find — a report that looks clean and says nothing."
                ),
        ).toAbsolutePath().normalize()
        check(Files.isDirectory(projectPath)) { "$projectPath is not a directory, so there is no project to open." }

        val report = sweepReportPath(
            reportDirectory = Paths.get(System.getProperty(REPORT_DIRECTORY_PROPERTY) ?: defaultReportDirectory()),
            fileName = "snippetveil-trace-sweep-${LocalDateTime.now().format(STAMP)}.txt",
            forbidden = mapOf("the SnippetVeil repository" to repository, "the project the traces are resolved against" to projectPath),
            property = "traceSweepReportDir",
        )

        // A file nothing was read out of is a failed run rather than a clean one: every count in the
        // report would be a zero, and a report of zeros reads exactly like a report of no leaks.
        val traces = tracesIn(Files.readString(traceFile))
        check(traces.isNotEmpty()) { "$traceFile holds no trace, so there is nothing to sweep." }
        say("${traces.size} trace(s) in the file.")

        say("Opening $projectPath …")
        val project = PlatformTestUtil.loadAndOpenProject(projectPath, testRootDisposable)
        attachTheRunningJdkUnderTheNameTheProjectExpects(project, testRootDisposable)?.let {
            say("Attached the running JDK as '$it'; the project's own SDK is not configured in this process.")
        }

        // **Refused rather than swept around**, as the source half refuses: where SnippetVeil's Kotlin
        // support is not registered, a frame of a Kotlin class comes back `Unknown`, and a report full
        // of them reads exactly like a report over foreign traces.
        refuseKotlinThatCannotBeSwept(project, sourceFilesOf(project, projectPath, listOf(".kt")))

        // The settings the action itself runs under: the internal-library prefixes, read off the project.
        val settings = AnonymizationSettings(internalLibraries = InternalLibrarySettings.of(project).policy)
        val swept = TracePass(project, settings).over(traces, progress = { done -> if (done % 100 == 0) say("  … $done/${traces.size}") })

        // Counts only. The hits are the leak, and the console is the easiest thing in the world to copy
        // out of — so is an exception message, which can name the symbol it choked on.
        say(
            "${swept.traces} read, ${swept.refusedCount} refused, ${swept.failures.size} threw; " +
                "${swept.frames.total} frame(s); ${swept.findings.size} trace(s) with oracle hits.",
        )

        val rendered = TraceSweepReport(
            startedAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            traceFile = traceFile.toString(),
            project = projectPath.toString(),
            swept = swept,
        ).render()
        Files.createDirectories(report.parent)
        Files.writeString(report, rendered)
        say("")
        say("The report is at: $report")
        say("It lists tokens out of real traces. Do not paste it anywhere.")
    }

    /** Progress, and never a name. See the hazard note on [TraceSweepReport]. */
    private fun say(line: String) = println("[trace sweep] $line")

    private companion object {

        /** Set by the `traceSweep` Gradle task from `-PtraceSweepFile`. */
        const val FILE_PROPERTY = "snippetveil.trace.sweep.file"

        /** Set from `-PtraceSweepProject`. */
        const val PROJECT_PROPERTY = "snippetveil.trace.sweep.project"

        /** Set from `-PtraceSweepReportDir`, and defaulted below. */
        const val REPORT_DIRECTORY_PROPERTY = "snippetveil.trace.sweep.reportDirectory"

        /**
         * This repository's root, handed in by the Gradle task. **Required**, for the reason the other
         * halves require it: a missing value cannot mean *no tree to avoid*.
         */
        const val REPOSITORY_PROPERTY = "snippetveil.sweep.repository"

        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        /** `~/snippetveil-sweep`: outside every checkout, and where the other halves' reports already land. */
        fun defaultReportDirectory(): String = System.getProperty("user.home") + "/snippetveil-sweep"
    }
}
