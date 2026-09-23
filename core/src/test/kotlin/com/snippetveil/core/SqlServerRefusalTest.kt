package com.snippetveil.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **The shapes SQL Server prints that this product refuses** — the transcript, the two wide rowsets,
 * the rows this engine writes raw, and the client mode that cuts a name in half.
 *
 * The line between them is the one drawn everywhere else in this product: a refusal that **recognises
 * what arrived** says which shape it was and what to run instead, and everything else takes the
 * general verdict. What is new here is that two of the recognised shapes carry **different**
 * recourses, because the option that replaces each is a different statement.
 */
class SqlServerRefusalTest {

    /**
     * **A paste beginning at the statement echo refuses; one beginning at the plan rowset parses.**
     *
     * `SET SHOWPLAN_TEXT ON` returns two rowsets per statement and the first is the statement the
     * user typed, so the natural *select both grids and copy* paste carries a transcript. It is the
     * same line this product draws between a client's chrome and a client's transcript everywhere
     * else, and it is asserted as the comparison it is: the same plan, with and without the echo.
     */
    @Test
    fun `a paste beginning at the statement echo refuses and one beginning at the plan rowset reads`() {
        assertEquals(
            PlanReading.Unreadable,
            parsePlan(SQLSERVER_TEXT_WITH_ECHO),
            "a paste carrying the statement echo was read",
        )
        assertTrue(
            parsePlan(SQLSERVER_TEXT_PLAN) is PlanReading.Read,
            "the same plan without the echo does not read, so this asserts nothing",
        )
    }

    /**
     * **A multi-statement output carrying a second echo refuses**, even where it begins exactly where
     * this product admits a paste.
     *
     * The rowset's header is admitted only as the input's first line, so the second one — and the
     * statement being echoed under it — is a line the reader has no row for. Nothing scans ahead for
     * a statement: the line closure reaches it the way it reaches every other unrecognised line.
     */
    @Test
    fun `a multi-statement output carrying a second echo refuses`() {
        assertEquals(PlanReading.Unreadable, parsePlan(SQLSERVER_TEXT_TWO_STATEMENTS))
    }

    /**
     * **A plan carrying a remote query or a remote scan refuses with the Showplan XML recourse.**
     *
     * These are the two rows this writer prints raw — the linked server unbracketed, the remote
     * statement verbatim — and they are the exception the whole admission of this format is told
     * about. The refusal names them rather than taking the general verdict, because the engine does
     * have a form where both are escaped.
     */
    @Test
    fun `a remote query or remote scan row refuses with the Showplan XML recourse`() {
        for ((printed, plan) in mapOf("Remote Query" to SQLSERVER_TEXT_REMOTE_QUERY, "Remote Scan" to SQLSERVER_TEXT_REMOTE_SCAN)) {
            val reading = parsePlan(plan)

            assertTrue(reading is PlanReading.Refused, "$printed was not refused as a recognised shape")
            assertEquals(
                PlanRefusedForm.SQLSERVER_SHOWPLAN_TEXT_REMOTE,
                (reading as PlanReading.Refused).form,
                "for $printed",
            )
            assertEquals(PlanRecourse.SQLSERVER_SHOWPLAN_XML, reading.form.recourse, "for $printed")
        }
    }

    /**
     * **`SHOWPLAN_ALL` and `STATISTICS PROFILE` refuse with two different recourse strings.**
     *
     * Both are refused for one reason — the first row of the plan rowset carries the statement in its
     * text cell, in a grid whose tab and newline separators are the client's with nothing escaping
     * them — and they are **two forms all the same**, because the option that replaces each is a
     * different statement. Telling somebody who asked for actual row counts to run the estimated-plan
     * option would be advice that throws away what they came for.
     */
    @Test
    fun `SHOWPLAN_ALL and STATISTICS PROFILE refuse with two different recourses`() {
        val showplanAll = parsePlan(SQLSERVER_SHOWPLAN_ALL_PLAN)
        val statisticsProfile = parsePlan(SQLSERVER_STATISTICS_PROFILE_PLAN)

        assertTrue(showplanAll is PlanReading.Refused, "SHOWPLAN_ALL was not refused as a recognised shape")
        assertTrue(statisticsProfile is PlanReading.Refused, "STATISTICS PROFILE was not refused as a recognised shape")

        val first = (showplanAll as PlanReading.Refused).form
        val second = (statisticsProfile as PlanReading.Refused).form

        assertEquals(PlanRefusedForm.SQLSERVER_SHOWPLAN_ALL, first)
        assertEquals(PlanRefusedForm.SQLSERVER_STATISTICS_PROFILE, second)
        assertEquals(PlanRecourse.SQLSERVER_SHOWPLAN_XML, first.recourse)
        assertEquals(PlanRecourse.SQLSERVER_STATISTICS_XML, second.recourse)
        assertNotEquals(
            first.recourse,
            second.recourse,
            "the two wide rowsets share a recourse, so one message is covering two situations",
        )
    }

    /**
     * **A forged bracket sequence in a name does not produce a parse.**
     *
     * Two halves, and they are the two ways a name can try to write the plan. A closing bracket
     * inside a name is **doubled**, so it cannot end its own delimiter early and the row it appears
     * in reads as the one name it is. A **newline** inside one is not escaped at all, so the opener
     * never closes and everything after it — including text shaped exactly like the node row below —
     * is inside a name that has no end: there is no sound reading of that, and the input refuses
     * rather than guessing where the name stopped.
     */
    @Test
    fun `a forged bracket sequence in a name does not produce a parse`() {
        assertEquals(
            PlanReading.Unreadable,
            parsePlan(SQLSERVER_TEXT_FORGED_ROW),
            "a name whose bracket never closes was read as a plan",
        )

        val doubled = anonymize(
            planIn(SQLSERVER_TEXT_AWKWARD_NAME),
            AnonymizationSettings.DEFAULTS,
            LedgerSnapshot.EMPTY,
        )
        assertEquals(
            setOf("Odd]]Table, Two"),
            doubled.mapping.values.filter { "Odd" in it }.toSet(),
            "a doubled closing bracket was read as the end of a name:\n${doubled.text}",
        )
    }

    /**
     * **A remote row this product's refusal does not name is still never read** — and this is the
     * test that says what the operator list is for.
     *
     * SQL Server prints five remote operators; two of them are named and refused with an option. All
     * five write the linked server **unbracketed** after `SOURCE:`, and an unbracketed token in this
     * format is preserved, because *everything outside a bracket is the engine's own* is the whole
     * reading. So a remote update that reached the reader would hand back `SOURCE:(ACME_FINANCE_SRV)`
     * exactly as printed — the organization-identifying string this product exists to replace.
     *
     * The operator list is what stops it, and **the leak is asserted rather than described**: each
     * row is read as far as it gets, and neither the linked server nor a readable plan may come out
     * of it. Cut the list and this test goes red on the second and third fixtures.
     */
    @Test
    fun `a remote row outside the two that are named is never read`() {
        for ((operator, plan) in SQLSERVER_TEXT_UNNAMED_REMOTE_ROWS) {
            val reading = parsePlan(plan)

            assertFalse(reading is PlanReading.Read, "$operator was read, and its linked server is raw in it")
            assertEquals(PlanReading.Unreadable, reading, "for $operator")
        }
    }

    /**
     * **The truncating results-to-text client mode refuses.**
     *
     * It pads every column to a fixed width and cuts what does not fit, which lands mid-bracket and
     * destroys the soundness this format's admission rests on. It has no predicate of its own — a
     * capture would be what adds one — so it takes the general verdict today, which is the
     * fail-closed direction and the behaviour this asserts.
     */
    @Test
    fun `the truncating results-to-text form refuses`() {
        assertEquals(PlanReading.Unreadable, parsePlan(SQLSERVER_RESULTS_TO_TEXT_PLAN))
    }

    /**
     * **An operator this product has no row for refuses**, which is the line closure of this format.
     *
     * It is the same cost the field closure carries, paid in the same place: a release that invents a
     * node shape refuses every plan carrying it until a capture and a row follow. The alternative — a
     * pattern loose enough never to miss an operator — is a pattern loose enough to accept a line
     * nobody wrote as a plan.
     */
    @Test
    fun `a node row whose operator has no row refuses`() {
        val invented = SQLSERVER_TEXT_PLAN.replace("Nested Loops(Inner Join", "Cranberry Loops(Inner Join")

        assertTrue(parsePlan(SQLSERVER_TEXT_PLAN) is PlanReading.Read, "the fixture does not read, so this asserts nothing")
        assertEquals(PlanReading.Unreadable, parsePlan(invented))
    }

    /**
     * **A Showplan XML field the inventory does not hold refuses the document.**
     *
     * An attribute is an entry like any other, so the field closure reaches one exactly as it reaches
     * an element — which is the whole of what reading attributes as entries buys. Nothing is skipped
     * in either spelling.
     */
    @Test
    fun `a Showplan XML attribute the inventory lacks refuses`() {
        val invented = SQLSERVER_XML_PLAN.replace("""CachedPlanSize="24"""", """CachedPlanSize="24" Cranberries="7"""")

        assertTrue(parsePlan(SQLSERVER_XML_PLAN) is PlanReading.Read, "the fixture does not read, so this asserts nothing")
        assertEquals(PlanReading.Unreadable, parsePlan(invented))
    }

    /**
     * **This engine's shapes are told apart from every other engine's**, which is what keeps a
     * refusal from naming somebody else's option.
     *
     * The Showplan XML document is the pair that matters: PostgreSQL's XML plan is an XML document
     * too, so a predicate anchored on the punctuation rather than on the root element each writer
     * writes would accept both — and two acceptances is the general refusal, which would take the
     * readable plan with it.
     */
    @Test
    fun `no two recognition predicates accept one SQL Server plan`() {
        val plans = mapOf(
            "sqlserver-showplan-xml" to SQLSERVER_XML_PLAN,
            "sqlserver-showplan-text" to SQLSERVER_TEXT_PLAN,
            "sqlserver-showplan-all" to SQLSERVER_SHOWPLAN_ALL_PLAN,
            "sqlserver-statistics-profile" to SQLSERVER_STATISTICS_PROFILE_PLAN,
        )

        for ((printed, plan) in plans) {
            val accepting = PLAN_FORMATS.filter { it.recognises(plan) }.map { it.name }

            assertEquals(listOf(printed), accepting, "the $printed plan was accepted by $accepting")
        }
        assertEquals(
            listOf("xml"),
            PLAN_FORMATS.filter { it.recognises(XML_PLAN) }.map { it.name },
            "PostgreSQL's XML plan is accepted by more than its own vocabulary",
        )
    }

    /**
     * **Nothing this engine prints is peeled as a client frame.**
     *
     * Every other engine here arrives wrapped in something a client drew, and SQL Server's plans
     * arrive out of a result grid with nothing around them. The absence is asserted rather than
     * assumed, because a frame peeler that matched one of these would be splicing over characters
     * that are the plan's own.
     */
    @Test
    fun `no SQL Server shape is peeled as a client frame`() {
        val pastes = listOf(
            SQLSERVER_XML_PLAN, SQLSERVER_TEXT_PLAN, SQLSERVER_TEXT_ROWS_ONLY,
            SQLSERVER_SHOWPLAN_ALL_PLAN, SQLSERVER_STATISTICS_PROFILE_PLAN, SQLSERVER_RESULTS_TO_TEXT_PLAN,
        )

        for (paste in pastes) {
            assertEquals(paste, framingOf(paste).inner, "a SQL Server paste had a client frame peeled off it")
        }
    }
}
