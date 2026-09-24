package com.snippetveil.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **The Oracle shapes this product refuses** — the malformed row, the wrapped paste, the section with
 * no capture behind it, and the four forms that are not plans this product reads at all.
 *
 * **Every one of them takes the general verdict, and none of them names a recourse.** That is the
 * decision rather than an omission: a recognised refused form exists in order to say something
 * specific, and the only specific thing there is to say about Oracle's text report — run it again as
 * XML — is exactly the recourse the decision refuses to name. So this engine adds no row to
 * `PlanRefusedForm`, and the refusals here are asserted as the verdict they take.
 */
class OracleRefusalTest {

    /**
     * **A row whose separator count differs from the header's refuses** — the counting argument the
     * whole admission of this format rests on.
     *
     * Oracle escapes nothing inside the grid, so a table named `Odd|Table` prints raw. The character
     * can only ever **add** a cell, so the row that carries one is malformed rather than misread, and
     * the count is what says so. Nothing can be hidden by it — which is why a format with no escaping
     * is admitted at all.
     */
    @Test
    fun `a row drawing a different number of separators than the header refuses`() {
        assertEquals(
            PlanReading.Unreadable,
            parsePlan(ORACLE_GRID_FORGED_CELL),
            "a row carrying the grid's own separator in a name was read",
        )
        assertTrue(
            parsePlan(ORACLE_GRID_PLAN) is PlanReading.Read,
            "the same grid without the forged cell does not read, so this asserts nothing",
        )
    }

    /**
     * **The natural default-settings paste refuses, and the message names no line-width setting.**
     *
     * The standard client wraps the grid at its default width, and a wrapped row has lost the
     * separator that closed it. What arrived is an **admitted** form the client damaged rather than a
     * form this product recognises and rejects — so the verdict is the general one, which carries no
     * recourse at all. **Naming a cause requires having recognised it**, and this refusal is by
     * definition the case where nothing matched.
     */
    @Test
    fun `a client-wrapped grid refuses with the general verdict and names nothing`() {
        val reading = parsePlan(ORACLE_GRID_WRAPPED)

        assertEquals(PlanReading.Unreadable, reading, "a wrapped grid was read")
        assertEquals(null, recourseOf(reading), "the wrapped paste was answered with a recourse")
    }

    /**
     * **A grid carrying the peeked-bind section refuses, recognised on its header.**
     *
     * The section prints a value and a size on one line and its treatment is decided — the size drops,
     * the value takes the exactly-one-literal test, the character-set id is an engine fact — but **no
     * capture of it exists**, and a vocabulary row may not be written from vendor documentation. The
     * cost is small: the section is absent from freshly run plans, which is most of what a user pastes
     * while debugging.
     */
    @Test
    fun `a grid carrying a peeked-bind section refuses`() {
        assertEquals(PlanReading.Unreadable, parsePlan(ORACLE_GRID_PEEKED_BINDS))
    }

    /**
     * **A section with no capture behind it refuses the plan carrying it** — the line closure, one
     * line at a time.
     *
     * The note some plans print is the commonest of them, and it refuses for the reason every
     * unrecognised field in this product refuses: nothing says what the line holds, and reading past
     * it is how a value leaves in the part nobody looked at. A vocabulary row is a routine patch, and
     * this container is expected to ship them.
     */
    @Test
    fun `a plan carrying a section this product has no capture of refuses`() {
        assertEquals(PlanReading.Unreadable, parsePlan(ORACLE_GRID_WITH_NOTE))
    }

    /**
     * **The HTML report, the active report, a client's CSV and the text report all refuse, and none
     * of them names a recourse.**
     *
     * Two of them are documents rather than paste shapes and one embeds its data compressed; the CSV
     * has thrown away the separators the counting argument rests on; and the text report is the same
     * grid shape with no anchor line and a transcript header above it. None is recognised, so each
     * takes the verdict that cannot carry a recourse.
     */
    @Test
    fun `the HTML, active, CSV and text report forms refuse, naming no recourse`() {
        val pastes = mapOf(
            "the HTML report" to ORACLE_HTML_REPORT,
            "the active report" to ORACLE_ACTIVE_REPORT,
            "plan-table rows as CSV" to ORACLE_PLAN_TABLE_CSV,
            "the SQL Monitor text report" to ORACLE_MONITOR_TEXT,
        )

        for ((printed, paste) in pastes) {
            val reading = parsePlan(paste)

            assertEquals(PlanReading.Unreadable, reading, "$printed was not refused")
            assertEquals(null, recourseOf(reading), "$printed was answered with a recourse")
        }
    }

    /**
     * **This engine's shapes are told apart from every other engine's**, which is what keeps a
     * refusal from naming somebody else's option and a readable plan from being lost to ambiguity.
     *
     * The grid is the pair that matters: MySQL's tabular output is a `|`-ruled table too, and a
     * predicate anchored on the punctuation rather than on the columns each engine prints would
     * accept both — and two acceptances is the general refusal, which would take MySQL's own sentence
     * with it.
     */
    @Test
    fun `no two recognition predicates accept one Oracle plan`() {
        val plans = mapOf(
            "oracle-grid-text" to ORACLE_GRID_PLAN,
            "oracle-sql-monitor-xml" to ORACLE_MONITOR_XML,
        )

        for ((printed, plan) in plans) {
            val accepting = PLAN_FORMATS.filter { it.recognises(plan) }.map { it.name }

            assertEquals(listOf(printed), accepting, "the $printed plan was accepted by $accepting")
        }
        assertEquals(
            listOf("oracle-grid-text"),
            PLAN_FORMATS.filter { it.recognises(ORACLE_GRID_ROWS_ONLY) }.map { it.name },
            "a grid pasted without its anchor is accepted by more than its own vocabulary",
        )
        assertEquals(
            emptyList<String>(),
            PLAN_FORMATS.filter { it.recognises(ORACLE_MONITOR_TEXT) }.map { it.name },
            "the text report is recognised as something, and it is nothing this product reads",
        )
    }

    /**
     * **Nothing Oracle prints is peeled as a client frame.**
     *
     * The grid is the engine's own drawing rather than a client's chrome — the rules and the
     * separators are `DBMS_XPLAN`'s, and peeling one would be splicing over characters that are the
     * plan's own. The absence is asserted rather than assumed.
     */
    @Test
    fun `no Oracle paste has a client frame peeled off it`() {
        val pastes = listOf(
            ORACLE_GRID_PLAN, ORACLE_GRID_ROWS_ONLY, ORACLE_GRID_WRAPPED, ORACLE_MONITOR_XML,
            ORACLE_MONITOR_TEXT, ORACLE_PLAN_TABLE_CSV,
        )

        for (paste in pastes) {
            assertEquals(paste, framingOf(paste).inner, "an Oracle paste had a client frame peeled off it")
        }
    }
}

/** What the message layer would have to offer for this verdict, which for a general refusal is nothing. */
internal fun recourseOf(reading: PlanReading): PlanRecourse? = (reading as? PlanReading.Refused)?.form?.recourse
