package com.snippetveil.sweep

import com.snippetveil.core.AnonymizationSettings
import com.snippetveil.core.LedgerSnapshot
import com.snippetveil.core.PLAN_FORMATS
import com.snippetveil.core.PlanDisposition
import com.snippetveil.core.PlanEngine
import com.snippetveil.core.PlanFormat
import com.snippetveil.core.PlanFormatReach
import com.snippetveil.core.PlanFormatState
import com.snippetveil.core.PlanOccurrence
import com.snippetveil.core.PlanReading
import com.snippetveil.core.anonymize

/**
 * **Which of the two zeros a capture broke** — the pass condition of the plan corpus instrument, and
 * the whole of what holds a release.
 *
 * > **Zero refused-format captures that the parser accepts, and zero admitted-format captures that
 * > the parser refuses, across the whole corpus.**
 *
 * They run in opposite directions and they are two different failures.
 */
internal enum class PlanZero(val complaint: String) {

    /**
     * **The leak-shaped direction.** A capture of a form this product refuses, that the parser read.
     *
     * The per-format soundness argument is this container's entire admission case, and a refused
     * form parsing means that argument is being applied to a form it was not made about.
     */
    REFUSED_FORM_ACCEPTED("a capture of a refused form was read as a plan"),

    /**
     * **A false admission row.** A capture of a form this product admits, that the parser refused.
     *
     * It is **not** a field failure: a field that cannot be scanned soundly becomes one redacted
     * literal and never a refusal. So a refusal on an admitted form means the recogniser or the
     * vocabulary is wrong, which is a design failure rather than a tuning problem.
     */
    ADMITTED_FORM_REFUSED("a capture of an admitted form was refused"),

    /**
     * **A refusal message that is not the one the capture is of.** Not one of the two zeros, and
     * reported beside them because it is the same shape of mistake one layer in: the product
     * recognised the form and then said the wrong true thing about it.
     */
    WRONG_REFUSAL_MESSAGE("a capture of one refusal message was refused with another"),
}

/**
 * **What one capture came to** — the verdict, what it cost in fidelity, and what survived.
 *
 * @param violation the zero this capture broke, or `null`
 * @param unscannableFields how many whole fields the unsound-scanning test cost this capture. See
 *   [PlanOccurrence.unscannable].
 * @param preserved every spelling the builtin list saved, by the row that saved it
 * @param survivors the triage list — every spelling of the input that reached the output
 */
internal class PlanCaptureOutcome(
    val capture: PlanCapture,
    val violation: PlanZero?,
    val unscannableFields: Int,
    val preserved: List<String>,
    val survivors: List<PlanSurvivor>,
) {

    /** Whether the parser read this capture, which is what makes the numbers below mean anything. */
    val read: Boolean get() = capture.reading is PlanReading.Read
}

/**
 * **One corpus swept**: what was in it, and the names of anything in it nobody could say what it was.
 *
 * @param unlabelled directories whose name is neither a vocabulary row nor a refusal message. A
 *   capture nobody can say what it is of is not a capture a zero can be asserted over, so it is a
 *   finding rather than a skip.
 */
internal class PlanSwept(
    val corpus: String,
    val outcomes: List<PlanCaptureOutcome>,
    val unlabelled: List<String>,
)

/**
 * **What one capture came to, read once** — the parse, the anonymization, the oracle and the two
 * zeros, in the one place that does all four.
 *
 * It is shared by the instrument and by the pull-request cell over the committed subset, which is
 * the whole reason it is a function rather than something the instrument does inline: *the two zeros
 * run in CI over the committed fixtures and at corpus scale in the instrument* is only true if both
 * of them ask the same question.
 */
internal fun outcomeOf(capture: PlanCapture): PlanCaptureOutcome {
    val reading = capture.reading
    val violation = violationOf(capture, reading)

    if (reading !is PlanReading.Read) return PlanCaptureOutcome(capture, violation, 0, emptyList(), emptyList())

    val unscannable = reading.plan.occurrences
        .filterIsInstance<PlanOccurrence>()
        .count { it.unscannable && it.disposition is PlanDisposition.Mask }

    val rider = capture.rider
        ?: return PlanCaptureOutcome(capture, violation, unscannable, emptyList(), emptyList())

    val output = anonymize(reading.plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY).text
    val survivors = PlanLeakOracle.over(capture.text, rider).survivorsIn(output)
    val preserved = survivors.mapNotNull { survivor ->
        survivor.annotation?.removePrefix(PlanLeakOracle.BUILTIN_ANNOTATION)?.takeIf { it != survivor.annotation }
    }
    return PlanCaptureOutcome(capture, violation, unscannable, preserved, survivors)
}

/**
 * The zero [capture] broke, or `null`.
 *
 * **The label decides what must happen and the parser decides what did**, and the two are compared.
 * Nothing here asks the parser what the capture was: a run that did would be green whatever the
 * parser answered.
 */
internal fun violationOf(capture: PlanCapture, reading: PlanReading): PlanZero? = when (val label = capture.label) {
    is PlanCaptureLabel.OfFormat -> when (label.format.state) {
        PlanFormatState.ADMITTED -> PlanZero.ADMITTED_FORM_REFUSED.takeIf { reading !is PlanReading.Read }
        PlanFormatState.REFUSED, PlanFormatState.REFUSED_PENDING_CAPTURE ->
            PlanZero.REFUSED_FORM_ACCEPTED.takeIf { reading is PlanReading.Read }
    }

    is PlanCaptureLabel.OfRefusal -> when {
        reading is PlanReading.Read -> PlanZero.REFUSED_FORM_ACCEPTED
        reading !is PlanReading.Refused || reading.form != label.form -> PlanZero.WRONG_REFUSAL_MESSAGE
        else -> null
    }

    // Read by nobody is the whole of what this label claims, and being read by somebody is the
    // leak-shaped direction exactly as it is for a refused row.
    PlanCaptureLabel.Unreadable -> PlanZero.REFUSED_FORM_ACCEPTED.takeIf { reading is PlanReading.Read }
}

/**
 * **The report the plan corpus instrument writes** — read by a human, and gating nothing it reports.
 *
 * ### Three numbers, and no threshold for any of them
 *
 * > **A rate threshold is refused.** The right denominator is *plans a developer would paste*; it
 * > cannot be measured without telemetry this product refuses to collect; and a number written over
 * > the wrong denominator gets argued down the first time it fails.
 *
 * So the three are reported and nothing is compared against them:
 *
 *  - **The whole-field redaction rate per format** — the fidelity cost of the residual rule, and a
 *    **copy input**: the public listing cannot describe this feature honestly without it.
 *  - **The annotated preserve count per builtin-list row** — the size of the shadowing exposure,
 *    which is the number that makes the oracle's refusal to subtract that list answerable.
 *  - **The default-form share** — per engine, whether the form a user gets **without asking** is
 *    admitted. It is entirely a copy cost: the listing cannot say *paste your `EXPLAIN` output*
 *    unqualified while one engine's default is refused outright and another is refused in every form.
 *
 * ### What does hold the release
 *
 * [assertTheRunHolds]: the denominator first, then the two zeros, then the per-row denominator
 * clause, then the coverage floor. The report is written **before** those assertions are made — an
 * instrument is run to be read, and a run that fails its pass condition is exactly the run whose
 * triage list somebody needs.
 */
internal class PlanSweepReport(private val stamp: String, private val swept: List<PlanSwept>) {

    private val outcomes: List<PlanCaptureOutcome> get() = swept.flatMap { it.outcomes }

    fun render(): String = buildString {
        appendLine("SnippetVeil — plan corpus sweep")
        appendLine("Run at $stamp over ${swept.size} corpus(es), ${outcomes.size} capture(s).")
        appendLine()
        appendLine(PREAMBLE)
        appendLine()
        appendTheDenominator()
        appendTheZeros()
        appendTheRedactionRate()
        appendThePreserveCounts()
        appendTheDefaultFormShare()
        appendTheCoverageFloor()
        appendTheProvenanceSplit()
        appendTheTriageList()
    }

    /**
     * **Captures seen per format, against the vocabulary** — and the clause that says which zeros
     * are findings.
     *
     * > **A format in the vocabulary with zero captures is a finding, not a pass.**
     *
     * Split by row state, because the clause cannot tell *zero captures because the form is refused*
     * from *zero captures because nobody looked* unless the row says which.
     */
    private fun StringBuilder.appendTheDenominator() {
        appendLine("## Captures seen per format")
        appendLine()
        for (format in PLAN_FORMATS) {
            val seen = outcomes.count { (it.capture.label as? PlanCaptureLabel.OfFormat)?.format === format }
            appendLine("  ${format.name} (${format.engine.printed}, ${format.state}, ${format.reach}): $seen")
        }
        val starved = starvedAdmissions()
        appendLine()
        if (starved.isEmpty()) {
            appendLine("  Every admitted row has a capture behind it.")
        } else {
            appendLine("  ADMITTED ON NO EVIDENCE — these rows admit a form and no capture of it was seen:")
            starved.forEach { appendLine("    ${it.name}") }
        }
        val quiet = PLAN_FORMATS.filter { it.state != PlanFormatState.ADMITTED && capturesOf(it) == 0 }
        if (quiet.isNotEmpty()) {
            appendLine("  Consistent — a refused row with no capture is the shipped behaviour:")
            quiet.forEach { appendLine("    ${it.name} (${it.state})") }
        }
        val unlabelled = swept.flatMap { it.unlabelled }
        if (unlabelled.isNotEmpty()) {
            appendLine("  UNLABELLED — neither a vocabulary row nor a refusal message:")
            unlabelled.forEach { appendLine("    $it") }
        }
        appendLine()
    }

    private fun StringBuilder.appendTheZeros() {
        appendLine("## The two zeros")
        appendLine()
        for (zero in PlanZero.entries) {
            val broken = outcomes.filter { it.violation == zero }
            appendLine("  ${zero.name}: ${broken.size} — ${zero.complaint}")
            broken.forEach { appendLine("    ${it.capture.name} (labelled ${it.capture.label.label})") }
        }
        appendLine()
    }

    /** See the class banner: reported, and compared against nothing. */
    private fun StringBuilder.appendTheRedactionRate() {
        appendLine("## Whole-field redaction, per format — reported, no threshold")
        appendLine()
        for (format in PLAN_FORMATS.filter { it.state == PlanFormatState.ADMITTED }) {
            val read = outcomes.filter { it.read && it.capture.recognisedBy === format }
            val costly = read.filter { it.unscannableFields > 0 }
            val fields = read.sumOf { it.unscannableFields }
            appendLine("  ${format.name}: ${costly.size} of ${read.size} capture(s) lost a field, $fields field(s) in all")
        }
        appendLine()
    }

    /** See the class banner: the size of the shadowing exposure, row by row. */
    private fun StringBuilder.appendThePreserveCounts() {
        appendLine("## Annotated preserves, per builtin-list row — reported, no threshold")
        appendLine()
        val counted = outcomes.flatMap { it.preserved }.groupingBy { it }.eachCount()
        if (counted.isEmpty()) {
            appendLine("  No capture preserved a spelling the builtin list saved.")
        } else {
            counted.entries.sortedByDescending { it.value }.forEach { appendLine("  ${it.key}: ${it.value}") }
        }
        appendLine()
    }

    /** See the class banner: the copy cost, per engine. */
    private fun StringBuilder.appendTheDefaultFormShare() {
        appendLine("## Default-form share, per engine — reported, no threshold")
        appendLine()
        for (engine in PlanEngine.entries) {
            val defaults = PLAN_FORMATS.filter { it.engine == engine && it.reach == PlanFormatReach.DEFAULT }
            when {
                defaults.isEmpty() ->
                    appendLine("  ${engine.printed}: no default form — this engine prints no plan until asked")
                defaults.any { it.state == PlanFormatState.ADMITTED } ->
                    appendLine("  ${engine.printed}: admitted (${defaults.joinToString { it.name }})")
                else ->
                    appendLine("  ${engine.printed}: REFUSED (${defaults.joinToString { it.name }})")
            }
        }
        val withADefault = PlanEngine.entries.filter { engine ->
            PLAN_FORMATS.any { it.engine == engine && it.reach == PlanFormatReach.DEFAULT }
        }
        val admitted = withADefault.count { engine ->
            PLAN_FORMATS.any {
                it.engine == engine && it.reach == PlanFormatReach.DEFAULT && it.state == PlanFormatState.ADMITTED
            }
        }
        appendLine("  Share: $admitted of ${withADefault.size} engine(s) that print a plan unasked.")
        appendLine()
    }

    private fun StringBuilder.appendTheCoverageFloor() {
        appendLine("## The coverage floor")
        appendLine()
        val carrying = formatsCarryingTheFloor()
        if (carrying.isEmpty()) {
            appendLine("  UNMET — no engine's default-or-one-flag-away form is admitted.")
        } else {
            appendLine("  Met, by ${carrying.size} row(s): ${carrying.joinToString { "${it.name} (${it.reach})" }}")
        }
        appendLine()
    }

    /**
     * **Where the committed fixtures came from** — reported rather than asserted. See
     * [PlanCaptureOrigin] for why the assertion is the trap and not this.
     */
    private fun StringBuilder.appendTheProvenanceSplit() {
        appendLine("## Committed fixture provenance")
        appendLine()
        val byOrigin = COMMITTED_PLAN_CAPTURES.groupingBy { it.origin }.eachCount()
        PlanCaptureOrigin.entries.forEach { appendLine("  $it: ${byOrigin[it] ?: 0}") }
        appendLine("  The trap asserts what a fixture carries. It cannot assert where a fixture came from.")
        appendLine()
    }

    /**
     * **The triage list** — every spelling of a capture's own that reached its anonymized output.
     *
     * It is long, and it is long on purpose: the oracle throws false positives by design, and a row
     * annotated as a known class is annotated rather than removed. See [PlanLeakOracle].
     */
    private fun StringBuilder.appendTheTriageList() {
        appendLine("## Triage list")
        appendLine()
        val withSurvivors = outcomes.filter { it.survivors.isNotEmpty() }
        if (withSurvivors.isEmpty()) {
            appendLine("  Nothing of any capture's own reached any output.")
            return
        }
        for (outcome in withSurvivors) {
            appendLine("  ${outcome.capture.name} — ${outcome.survivors.size} spelling(s)")
            for (survivor in outcome.survivors) {
                val annotation = survivor.annotation?.let { " [$it]" } ?: ""
                appendLine("    line ${survivor.line}: ${survivor.spelling}$annotation")
            }
        }
    }

    private fun capturesOf(format: PlanFormat): Int =
        outcomes.count { (it.capture.label as? PlanCaptureLabel.OfFormat)?.format === format }

    /**
     * Every admitted row with no capture behind it — **a format admitted on no evidence**, which
     * holds the release. A refused row with none is consistent and holds nothing.
     */
    fun starvedAdmissions(): List<PlanFormat> =
        PLAN_FORMATS.filter { it.state == PlanFormatState.ADMITTED && capturesOf(it) == 0 }

    /**
     * **The pass condition.** Called after the report is on disk, for the reason the source half
     * calls its own last: a run that fails here is exactly the run whose triage list somebody needs.
     */
    fun assertTheRunHolds() {
        // The denominator first. A sweep that read no capture is a failed run rather than a clean
        // one — the same rule every check in this repository follows.
        check(outcomes.isNotEmpty()) {
            "The sweep read no capture at all, so every number above is a share of zero and the two " +
                "zeros are zero because nothing was asked."
        }

        val unlabelled = swept.flatMap { it.unlabelled }
        check(unlabelled.isEmpty()) {
            "A capture nobody can say what it is of cannot be held to either zero: $unlabelled"
        }

        for (zero in PlanZero.entries) {
            val broken = outcomes.filter { it.violation == zero }
            check(broken.isEmpty()) {
                "${broken.size} capture(s) — ${zero.complaint}: ${broken.joinToString { it.capture.name }}"
            }
        }

        val starved = starvedAdmissions()
        check(starved.isEmpty()) {
            "These rows admit a form and no capture of it was seen, which is a format admitted on " +
                "no evidence: ${starved.joinToString { it.name }}"
        }

        assertTheCoverageFloorIsMet()
    }

    private companion object {

        /**
         * **The negative this half is stated as**, printed at the head of every report so that
         * nobody reading one reaches for the secrecy machinery the other halves carry.
         */
        const val PREAMBLE =
            "The captures behind this report are agent-generated, in a container, against throwaway\n" +
                "schemas. There is no leak in them and this file is not sensitive. This half is an\n" +
                "instrument for the other reason: the oracle throws false positives by design, so it\n" +
                "cannot be green or red — a human runs it, reads it, and records a conclusion."
    }
}

/**
 * **The coverage floor, which is met on arrival and cannot become unmet by accident.**
 *
 * > **If no engine's default-or-one-flag-away form is admitted, the release waits.**
 *
 * Not on tuning: the feature would fire only for users who already know which option to pass, which
 * is a different product from the one being shipped. PostgreSQL's default text output is admitted,
 * so the floor is met today — and the only way to unmeet it is to delete an admission row, which is
 * what this assertion is here to notice.
 *
 * It is a statement about **the vocabulary** and not about a corpus, which is why it holds without
 * one: `PlanCoverageTest` asserts it on every pull request, and the instrument asserts it again over
 * whatever it swept.
 */
internal fun assertTheCoverageFloorIsMet() {
    val carrying = formatsCarryingTheFloor()
    check(carrying.isNotEmpty()) {
        "No engine's default-or-one-flag-away form is admitted, so the feature would fire only for " +
            "users who already know which option to pass."
    }
}

/** Every admitted row a user reaches without asking, or one option away. See [assertTheCoverageFloorIsMet]. */
internal fun formatsCarryingTheFloor(): List<PlanFormat> = PLAN_FORMATS.filter {
    it.state == PlanFormatState.ADMITTED &&
        (it.reach == PlanFormatReach.DEFAULT || it.reach == PlanFormatReach.ONE_FLAG)
}
