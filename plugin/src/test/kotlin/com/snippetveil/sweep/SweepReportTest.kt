package com.snippetveil.sweep

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * What the sweep writes, and where it is allowed to write it.
 *
 * Both halves are here because both are rules rather than formatting. **The report contains the leak
 * by construction** — it is a list of real identifiers from a real proprietary codebase — so where
 * it lands and what it says about itself are the two things about it that are load-bearing.
 */
class SweepReportTest {

    @Test
    fun `the report says what it is before it says anything else`() {
        val head = report(findings = listOf(fileWith("Ledger.java", "MerchantLedger"))).render().lineSequence()
            .take(12)
            .joinToString("\n")
            .lowercase()

        assertTrue("real identifiers" in head) { "The report does not say what it contains:\n$head" }
        assertTrue("do not paste" in head) { "The report does not say not to paste it:\n$head" }
        assertTrue("synthetic" in head) { "The report does not say findings become synthetic fixtures:\n$head" }
    }

    @Test
    fun `the report names the target, the coverage and the counts`() {
        val rendered = report(findings = listOf(fileWith("Ledger.java", "MerchantLedger"))).render()

        assertTrue("/home/me/acme" in rendered) { rendered }
        assertTrue("812" in rendered) { "The file count is missing:\n$rendered" }
        assertTrue("14203" in rendered || "14,203" in rendered) { "The universe size is missing:\n$rendered" }
    }

    @Test
    fun `a finding renders as its file, its line, the name and the line it survived on`() {
        val rendered = report(findings = listOf(fileWith("src/Ledger.java", "MerchantLedger"))).render()

        assertTrue("src/Ledger.java" in rendered) { rendered }
        assertTrue("42" in rendered) { rendered }
        assertTrue("MerchantLedger" in rendered) { rendered }
        assertTrue("MerchantLedger field2;" in rendered) { rendered }
    }

    /**
     * A sweep that found nothing has to say so in the same words every time. *"No findings"* is a
     * conclusion a human acts on, and a blank page is one they cannot tell from a crash.
     */
    @Test
    fun `a sweep that found nothing says so`() {
        val rendered = report(findings = emptyList()).render()

        assertTrue("no project-owned name survived" in rendered.lowercase()) { rendered }
    }

    /** The report is a triage list, so a file with nothing to triage is not in it. */
    @Test
    fun `a file with no findings is not listed`() {
        val rendered = report(
            findings = listOf(fileWith("Ledger.java", "MerchantLedger"), FileFindings("Clean.java", emptyList())),
        ).render()

        assertTrue("Clean.java" !in rendered) { rendered }
    }

    /**
     * The row a reader is most likely to act on wrongly. Every line below the header is a **suspect**
     * — the oracle throws on genuine collisions with preserved library members on purpose — and a
     * report that presented them as verdicts would be one whose first false positive discredits it.
     */
    @Test
    fun `the report says its rows are suspects rather than verdicts`() {
        val rendered = report(findings = listOf(fileWith("Ledger.java", "MerchantLedger"))).render().lowercase()

        assertTrue("suspect" in rendered) { rendered }
    }

    @Test
    fun `a path outside every named tree is allowed`(@TempDir root: Path) {
        val outside = root.resolve("elsewhere")
        val repository = root.resolve("repo").also { it.toFile().mkdirs() }

        val resolved = sweepReportPath(outside, "sweep.txt", mapOf("the repository" to repository))

        assertEquals(outside.resolve("sweep.txt").toAbsolutePath().normalize(), resolved)
    }

    /**
     * `.gitignore` stops `git add`. It does not stop a paste or a screenshot, and **a file being
     * inside the repository is exactly what makes those feel safe.**
     */
    @Test
    fun `a path inside the repository is refused`(@TempDir root: Path) {
        val repository = root.resolve("repo").also { it.toFile().mkdirs() }

        val refused = assertThrows(IllegalStateException::class.java) {
            sweepReportPath(repository.resolve("build/reports"), "sweep.txt", mapOf("the repository" to repository))
        }

        assertTrue("the repository" in refused.message.orEmpty()) { refused.message.orEmpty() }
    }

    /** The other tree it must stay out of: the proprietary codebase it was just read from. */
    @Test
    fun `a path inside the swept project is refused`(@TempDir root: Path) {
        val target = root.resolve("acme").also { it.toFile().mkdirs() }

        assertThrows(IllegalStateException::class.java) {
            sweepReportPath(target.resolve("notes"), "sweep.txt", mapOf("the swept project" to target))
        }
    }

    /** The tree itself, not only something under it. */
    @Test
    fun `the named tree itself is refused`(@TempDir root: Path) {
        val repository = root.resolve("repo").also { it.toFile().mkdirs() }

        assertThrows(IllegalStateException::class.java) {
            sweepReportPath(repository, "sweep.txt", mapOf("the repository" to repository))
        }
    }

    /**
     * A file the anonymiser threw on is a conclusion, not an outage. Losing the whole run to one PSI
     * edge case in a codebase of thousands of files is the difference between an instrument and a
     * thing nobody manages to finish running.
     */
    @Test
    fun `a file the anonymiser threw on is reported rather than lost`() {
        val rendered = report(
            findings = listOf(fileWith("Ledger.java", "MerchantLedger")),
            failures = listOf(SweepFailure("src/Odd.java", "IllegalStateException: no root")),
        ).render()

        assertTrue("src/Odd.java" in rendered) { rendered }
        assertTrue("IllegalStateException: no root" in rendered) { rendered }
    }

    /** A throw is a finding in its own right, so a sweep with nothing else to say still has this. */
    @Test
    fun `a sweep whose only result is a throw does not report itself clean`() {
        val rendered = report(findings = emptyList(), failures = listOf(SweepFailure("src/Odd.java", "boom")))

        assertTrue("src/Odd.java" in rendered.render()) { rendered.render() }
        assertTrue("no project-owned name survived" !in rendered.render().lowercase()) { rendered.render() }
    }

    @Test
    fun `a sweep with no throws says nothing about them`() {
        assertTrue("could not be swept" !in report(findings = emptyList()).render().lowercase())
    }

    private fun report(findings: List<FileFindings>, failures: List<SweepFailure> = emptyList()) = SweepReport(
        startedAt = "2026-08-23T19:26:00",
        targetProject = "/home/me/acme",
        filesSwept = 812,
        universe = UniverseSize(owned = 14203, declared = 17315, sharedWithLibraries = 3110, topLevelSegments = 2),
        findings = findings,
        failures = failures,
    )

    private fun fileWith(path: String, name: String) =
        FileFindings(path, listOf(Survivor(name, 42, "$name field2;")))
}
