package com.snippetveil.sweep

import com.snippetveil.core.PLAN_FORMATS
import com.snippetveil.core.PlanFormatState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * **The plan instrument's report and pass condition, held without a corpus.**
 *
 * The instrument itself needs one and is skipped without it, which is exactly the shape that lets a
 * rule rot: a report nobody can run is a report nobody can hold. So the report is a pure function of
 * what was swept, and every claim it makes is asserted here, in `check`, over a corpus made up on
 * the spot. `PlanSweep` does the file reading and nothing else that matters.
 */
class PlanSweepReportTest {

    /**
     * **A run that read no capture is a failed run rather than a clean one.**
     *
     * The denominator comes first for the reason it does in the other halves: every number in the
     * report is a share of the captures read, and both zeros are zero when nothing was asked.
     */
    @Test
    fun `a sweep that read no capture fails`() {
        val thrown = runCatching { report(emptyList()).assertTheRunHolds() }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException, "a sweep that read nothing passed")
        assertTrue("no capture at all" in thrown?.message.orEmpty(), "the failure did not say why: ${thrown?.message}")
    }

    /**
     * **A format in the vocabulary with zero captures is a finding, not a pass — and the finding is
     * split by row state.**
     *
     * A row stating a **refusal** with zero captures is consistent and holds nothing up: that is the
     * shipped, honest behaviour. A row stating an **admission** with zero captures holds the
     * release, because it is a format admitted on no evidence.
     */
    @Test
    fun `an admitted row with no capture holds the release and a refused row with none does not`() {
        val everyAdmittedRow = PLAN_FORMATS.filter { it.state == PlanFormatState.ADMITTED }
            .map { outcomeOf(capture("of/${it.name}", PlanCaptureLabel.OfFormat(it), READABLE_PLANS.getValue(it.name))) }

        val whole = report(everyAdmittedRow)
        assertEquals(emptyList<String>(), whole.starvedAdmissions().map { it.name })
        whole.assertTheRunHolds()

        val missingOne = report(everyAdmittedRow.drop(1))
        assertEquals(
            listOf(everyAdmittedRow.first().capture.label.label),
            missingOne.starvedAdmissions().map { it.name },
            "an admitted row with no capture was not reported as admitted on no evidence",
        )
        val thrown = runCatching { missingOne.assertTheRunHolds() }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException, "a format admitted on no evidence did not hold the release")

        // Every refused row has zero captures in all of the runs above, and none of them failed for
        // it. That is the other half of the clause, and it is asserted by the pass on the first run.
        assertTrue(
            PLAN_FORMATS.any { it.state != PlanFormatState.ADMITTED },
            "there is no refused row, so the consistent half of the clause is not being asserted",
        )
    }

    /** Either zero holds the release, whichever direction it was broken in. */
    @Test
    fun `either zero holds the release`() {
        for (zero in PlanZero.entries) {
            val broken = report(listOf(brokenOutcome(zero)))
            val thrown = runCatching { broken.assertTheRunHolds() }.exceptionOrNull()

            assertTrue(thrown is IllegalStateException, "$zero did not hold the release")
            assertTrue(zero.complaint in thrown?.message.orEmpty(), "the failure did not name $zero")
        }
    }

    /** A capture nobody can say what it is of cannot be held to either zero, so it is a finding. */
    @Test
    fun `an unlabelled corpus directory holds the release`() {
        val swept = PlanSwept("/corpus", listOf(readableOutcome()), listOf("plans-i-found-somewhere"))
        val thrown = runCatching { PlanSweepReport(STAMP, listOf(swept)).assertTheRunHolds() }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException, "an unlabelled directory passed")
        assertTrue("plans-i-found-somewhere" in thrown?.message.orEmpty(), "the failure did not name it")
    }

    /**
     * **The three reported numbers are in the report, and no threshold exists for any of them.**
     *
     * > A rate threshold is refused: the right denominator is *plans a developer would paste*, it
     * > cannot be measured without telemetry this product refuses to collect, and a number written
     * > over the wrong denominator gets argued down the first time it fails.
     *
     * Asserted by the pass condition rather than by reading the prose: a report carrying all three
     * numbers over a run whose captures are perfectly ordinary still has to pass.
     */
    @Test
    fun `the three reported numbers are emitted and none of them gates anything`() {
        val rendered = report(listOf(readableOutcome())).render()

        assertTrue("Whole-field redaction, per format" in rendered, "the redaction rate is not reported")
        assertTrue("Annotated preserves, per builtin-list row" in rendered, "the preserve count is not reported")
        assertTrue("Default-form share, per engine" in rendered, "the default-form share is not reported")
        assertEquals(3, Regex("no threshold").findAll(rendered).count(), "a reported number lost its disclaimer")
    }

    /**
     * **The report says this half is not the sensitive one**, which is the negative the whole ticket
     * is stated as: the secrecy machinery the other two halves carry is not inherited here.
     */
    @Test
    fun `the report says its captures hold nothing anybody owns`() {
        val rendered = report(listOf(readableOutcome())).render()

        assertTrue("not sensitive" in rendered, "the report did not say what it is")
        assertFalse("Do not paste" in rendered, "the report inherited the other halves' warning")
    }

    /** The coverage floor is stated in the report as well as asserted. */
    @Test
    fun `the report states the coverage floor`() {
        val rendered = report(listOf(readableOutcome())).render()

        assertTrue("The coverage floor" in rendered)
        assertTrue("Met, by" in rendered, "the floor is unmet, which is the release waiting:\n$rendered")
    }

    /** The provenance split is reported, because the trap cannot assert it. */
    @Test
    fun `the report states where the committed fixtures came from`() {
        val rendered = report(listOf(readableOutcome())).render()

        assertTrue("Committed fixture provenance" in rendered)
        assertTrue("WRITTEN_FROM_DOCUMENTATION: ${COMMITTED_PLAN_CAPTURES.size}" in rendered)
    }

    /**
     * **Inputs and outputs live outside the repository tree**, and both refusals are asserted rather
     * than left to a comment — a rule that only exists in prose is a rule that stops being true.
     */
    @Test
    fun `a corpus or a report inside the repository is refused`() {
        val repository = Paths.get("/home/dev/snippetveil")

        val corpus = runCatching { planCorpusDirectory(repository.resolve("plans"), repository) }.exceptionOrNull()
        assertTrue(corpus is IllegalStateException, "a corpus inside the repository was accepted")

        val report = runCatching {
            planReportPath(repository.resolve("reports"), "r.txt", mapOf("the repository" to repository))
        }.exceptionOrNull()
        assertTrue(report is IllegalStateException, "a report inside the repository was accepted")
        assertTrue("outside every tree" in report?.message.orEmpty(), "the refusal did not say why")

        val allowed = planReportPath(Paths.get("/home/dev/reports"), "r.txt", mapOf("the repository" to repository))
        assertEquals(Paths.get("/home/dev/reports/r.txt"), allowed, "a report outside every tree was refused")
    }

    private fun report(outcomes: List<PlanCaptureOutcome>) =
        PlanSweepReport(STAMP, listOf(PlanSwept("/corpus", outcomes, emptyList())))

    private fun capture(name: String, label: PlanCaptureLabel, text: String) =
        PlanCapture(name, label, text, PlanCaptureOrigin.CORPUS)

    private fun readableOutcome() = outcomeOf(COMMITTED_PLAN_CAPTURES.first { it.name == "postgres-text" })

    /** A capture labelled so that it breaks [zero], built out of a committed one. */
    private fun brokenOutcome(zero: PlanZero): PlanCaptureOutcome {
        val readable = COMMITTED_PLAN_CAPTURES.first { it.name == "postgres-text" }
        val refused = COMMITTED_PLAN_CAPTURES.first { it.name == "mysql-tabular" }
        return when (zero) {
            PlanZero.REFUSED_FORM_ACCEPTED -> outcomeOf(
                capture("planted", PlanCaptureLabel.OfFormat(PLAN_FORMATS.first { it.name == "mysql-tabular" }), readable.text),
            )

            PlanZero.ADMITTED_FORM_REFUSED -> outcomeOf(
                capture("planted", PlanCaptureLabel.OfFormat(PLAN_FORMATS.first { it.name == "text" }), refused.text),
            )

            PlanZero.WRONG_REFUSAL_MESSAGE -> outcomeOf(
                capture("planted", PlanCaptureLabel.OfRefusal(com.snippetveil.core.PlanRefusedForm.MARIADB), refused.text),
            )
        }
    }

    private companion object {

        const val STAMP = "2026-01-01T00:00:00"

        /** One readable capture per admitted row, by that row's name. */
        val READABLE_PLANS: Map<String, String> = COMMITTED_PLAN_CAPTURES
            .mapNotNull { capture -> (capture.label as? PlanCaptureLabel.OfFormat)?.let { it.format.name to capture.text } }
            .toMap()
    }
}
