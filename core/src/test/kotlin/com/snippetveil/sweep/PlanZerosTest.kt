package com.snippetveil.sweep

import com.snippetveil.core.PLAN_FORMATS
import com.snippetveil.core.PlanFormatState
import com.snippetveil.core.PlanReading
import com.snippetveil.core.PlanRefusedForm
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **The two zeros, on every pull request, over the committed fixture subset.**
 *
 * > **Zero refused-format captures that the parser accepts, and zero admitted-format captures that
 * > the parser refuses.**
 *
 * At corpus scale they run in `PlanSweep` and nowhere else; here they run over the committed
 * captures, in `check`, on every change. That is the source half's shape exactly: **committed
 * fixtures gate pull requests, the real sweep is a human-run release gate.**
 *
 * The two ask the same question through the same function — [outcomeOf] — because *the same two
 * zeros* is only true if both of them do.
 */
class PlanZerosTest {

    /**
     * **The leak-shaped direction**, and the one that matters most: a capture of a form this product
     * refuses, that the parser read.
     *
     * The per-format soundness argument is this container's entire admission case. A refused form
     * parsing means that argument is being applied to a form nobody made it about — a design failure
     * rather than a tuning problem, and one that holds the release.
     */
    @Test
    fun `no capture of a refused form is read as a plan`() {
        assertNothingBroke(PlanZero.REFUSED_FORM_ACCEPTED)
    }

    /**
     * **A false admission row**: a capture of a form this product admits, that the parser refused.
     *
     * It is not a field failure — a field that cannot be scanned soundly becomes one redacted
     * literal, never a refusal — so a refusal here means the recogniser or the vocabulary is wrong.
     */
    @Test
    fun `no capture of an admitted form is refused`() {
        assertNothingBroke(PlanZero.ADMITTED_FORM_REFUSED)
    }

    /**
     * **A capture of one refusal message is refused with that message and no other.**
     *
     * Not one of the two zeros, and asserted beside them because it is the same mistake one layer
     * in: a recogniser only ever picks the message, and the wrong message is a false statement this
     * product makes about somebody else's engine.
     */
    @Test
    fun `each capture of a refusal message is refused with that message`() {
        assertNothingBroke(PlanZero.WRONG_REFUSAL_MESSAGE)
    }

    /**
     * No committed capture broke [zero].
     *
     * One helper rather than three copies of the same three lines — the difference between the
     * tests is which zero they are about, and everything else is the same question.
     */
    private fun assertNothingBroke(zero: PlanZero) {
        val broken = COMMITTED_PLAN_CAPTURES.map(::outcomeOf).filter { it.violation == zero }

        assertTrue(
            broken.isEmpty(),
            "${zero.complaint}: " + broken.joinToString { "${it.capture.name} (${it.capture.label.label})" },
        )
    }

    /**
     * **The zeros prove they can fail before they report that nothing failed.**
     *
     * A pass over a subset where nothing is wrong says nothing about a rule that has stopped asking.
     * Both directions are exercised here by swapping a capture's label, which is the one thing that
     * decides a violation: the parser is not touched, so what is being proved is the comparison.
     */
    @Test
    fun `the two zeros each go red on a capture labelled the other way`() {
        val admitted = PLAN_FORMATS.first { it.state == PlanFormatState.ADMITTED }
        val refusedRow = PLAN_FORMATS.first { it.state == PlanFormatState.REFUSED }
        val readable = COMMITTED_PLAN_CAPTURES.first { it.reading is PlanReading.Read }
        val refused = COMMITTED_PLAN_CAPTURES.first { it.reading is PlanReading.Refused }

        val readableCalledRefused = PlanCapture(
            "planted",
            PlanCaptureLabel.OfFormat(refusedRow),
            readable.text,
            PlanCaptureOrigin.CORPUS,
        )
        val refusedCalledAdmitted = PlanCapture(
            "planted",
            PlanCaptureLabel.OfFormat(admitted),
            refused.text,
            PlanCaptureOrigin.CORPUS,
        )

        assertEquals(
            PlanZero.REFUSED_FORM_ACCEPTED,
            outcomeOf(readableCalledRefused).violation,
            "a readable plan labelled as a refused form was not flagged",
        )
        assertEquals(
            PlanZero.ADMITTED_FORM_REFUSED,
            outcomeOf(refusedCalledAdmitted).violation,
            "a refused plan labelled as an admitted form was not flagged",
        )
        assertNull(outcomeOf(readable).violation, "the unplanted capture is itself a violation")
    }

    /** A capture labelled with a refusal message it is not of is flagged as the wrong message. */
    @Test
    fun `a refusal message check goes red on a capture of another message`() {
        val mysqlTree = COMMITTED_PLAN_CAPTURES.first { it.name == "mysql-tree-refusal" }
        val mislabelled = PlanCapture(
            "planted",
            PlanCaptureLabel.OfRefusal(PlanRefusedForm.MARIADB),
            mysqlTree.text,
            PlanCaptureOrigin.CORPUS,
        )

        assertEquals(PlanZero.WRONG_REFUSAL_MESSAGE, outcomeOf(mislabelled).violation)
    }

    /**
     * **Every committed capture resolves to a label**, which is the denominator of everything above:
     * a capture that fell out of the subset would make all three tests pass by not being asked.
     */
    @Test
    fun `every committed capture carries a label the vocabulary knows`() {
        assertTrue(COMMITTED_PLAN_CAPTURES.isNotEmpty(), "the committed subset is empty, so nothing is asserted")
        for (capture in COMMITTED_PLAN_CAPTURES) {
            assertNotNull(
                captureLabelOf(capture.label.label),
                "`${capture.name}` is labelled `${capture.label.label}`, which nothing resolves",
            )
        }
    }
}
