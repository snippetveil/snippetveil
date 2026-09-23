package com.snippetveil.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **The two closures, and the two refusals they produce.**
 *
 * A field the vocabulary does not hold refuses the input, and a token the vocabulary does not know
 * inside a field it does hold anonymizes. The second is `PlanExpressionTest`'s; this file is the
 * first, plus the one shape that refuses **with** a recourse instead of the general verdict.
 *
 * The pair is asserted together wherever it can be, because the whole risk in these rules is a reader
 * generalising one into the other's territory.
 */
class PlanClosureTest {

    /**
     * **A field absent from the vocabulary refuses the input**, in every structured format — with a
     * field this product has never heard of, added to a plan that reads cleanly without it.
     *
     * The plan is read before and after, so the refusal is the *field's* and not the fixture's.
     */
    @Test
    fun `a field the vocabulary does not hold refuses the input`() {
        for ((format, plan) in STRUCTURED_PLANS) {
            assertTrue(parsePlan(plan) is PlanReading.Read, "the $format fixture does not read, so this asserts nothing")

            val invented = plan.withInventedField()
            assertTrue(
                parsePlan(invented) is PlanReading.Unreadable,
                "a $format plan carrying a field nothing typed was read:\n$invented",
            )
        }
    }

    /**
     * **The refusal for an unknown field is the general one, and adds no recourse.**
     *
     * There is nothing for it to offer. A recognised refused shape is one whose engine prints the
     * same thing in a form this product *can* read; a field nobody here has typed is not that, and a
     * recourse printed next to it would be advice this product made up. The distinction is asserted
     * as a pair with the row that *does* carry one, because a verdict that is only ever seen alone is
     * one nothing holds to being different.
     */
    @Test
    fun `an unknown field refuses generally where a raw-name row refuses with a recourse`() {
        val unknownField = parsePlan(JSON_PLAN.withInventedField())
        val rawNameRow = parsePlan(
            """
            |Seq Scan on visits  (cost=0.00..1.00 rows=1 width=4)
            |Settings: search_path = 'billing, "${'$'}user", public'
            """.trimMargin(),
        )

        assertEquals(PlanReading.Unreadable, unknownField, "an unknown field was refused as a recognised shape")
        assertTrue(rawNameRow is PlanReading.Refused, "a raw-name row was not refused as a recognised shape")
        assertEquals(PlanRecourse.FORMAT_JSON, (rawNameRow as PlanReading.Refused).recourse)
    }

    /**
     * **Every enumerated raw-name row refuses with the `FORMAT JSON` recourse.**
     *
     * These are the rows the text format prints with a name in them that `quote_identifier()` never
     * saw: a comma-separated list of index names, a trigger row written as a sentence, and the
     * settings row, whose values are single-quoted with no escape for a single quote inside one.
     * None can be recovered soundly from the text, and the same plan as JSON escapes all three.
     */
    @Test
    fun `each raw-name row the text format prints refuses with the FORMAT JSON recourse`() {
        val rows = listOf(
            "Settings: search_path = 'billing, public'",
            "  Conflict Arbiter Indexes: visits_pkey",
            "Trigger trg_audit on visits: time=0.123 calls=1",
        )

        for (row in rows) {
            val plan = "Insert on visits  (cost=0.00..1.00 rows=1 width=4)\n$row"
            val reading = parsePlan(plan)

            assertTrue(reading is PlanReading.Refused, "`$row` was not refused as a recognised shape")
            assertEquals(PlanRecourse.FORMAT_JSON, (reading as PlanReading.Refused).recourse, "for `$row`")
        }
    }

    /**
     * **Any other unrecognised line is *not a readable plan* instead**, and the two are asserted as a
     * distinct pair over one plan shape so the difference cannot be an accident of two fixtures.
     *
     * The enumerated heads are a closed list. A row outside it is not one this product knows a better
     * form for, so it takes the general verdict.
     */
    @Test
    fun `an unrecognised line refuses generally where an enumerated row refuses with a recourse`() {
        val head = "Seq Scan on visits  (cost=0.00..1.00 rows=1 width=4)"

        assertEquals(PlanReading.Unreadable, parsePlan("$head\n  Cranberry Count: 4"))
        assertEquals(PlanReading.Unreadable, parsePlan("$head\n  a line nothing here recognises"))
        assertTrue(parsePlan("$head\nSettings: work_mem = '8MB'") is PlanReading.Refused)
    }

    /**
     * **A string literal carrying a newline no longer leaks its continuation line.**
     *
     * The lexer already refuses to scan a field whose quoting does not close inside it, and the field
     * becomes one redacted literal. What it could not reach was the **next line**, which is neither a
     * node nor a field and used to be passed through unread — so the rest of the value reached the
     * clipboard verbatim. The line closure is what shuts that: the continuation is not a line this
     * reader recognises, so the input is not a readable plan.
     */
    @Test
    fun `the continuation line of a value carrying a newline is not a readable plan`() {
        val plan = """
            |Seq Scan on visits  (cost=0.00..1.00 rows=1 width=4)
            |  Filter: (note = 'first line
            |second line, which used to reach the clipboard')
        """.trimMargin()

        assertEquals(PlanReading.Unreadable, parsePlan(plan))
    }

    /** A blank line is layout, and a plan that has one is still a plan. */
    @Test
    fun `a blank line between rows is not an unrecognised line`() {
        val plan = "Seq Scan on visits  (cost=0.00..1.00 rows=1 width=4)\n\nPlanning Time: 0.123 ms\n"

        assertTrue(parsePlan(plan) is PlanReading.Read, "a blank line refused the plan around it")
    }

    /** The planner's own headers name nothing of anybody's, and a plan carrying one still reads. */
    @Test
    fun `the headers a planner writes for the pieces of a plan are recognised lines`() {
        val plan = """
            |Result  (cost=0.02..0.03 rows=1 width=4)
            |  InitPlan 1 (returns ${'$'}0)
            |    ->  Seq Scan on visits  (cost=0.00..1.00 rows=1 width=4)
            |  SubPlan 2
            |    ->  Seq Scan on invoices  (cost=0.00..1.00 rows=1 width=4)
        """.trimMargin()

        val kinds = namesIn(plan)

        assertEquals(SymbolRole.TABLE, kinds["visits"])
        assertEquals(SymbolRole.TABLE, kinds["invoices"])
    }
}

/**
 * The same plan with one field in it that no vocabulary row covers — written in whichever of the
 * three spellings the plan arrived in, next to a field that does read, so the refusal can only be
 * the new field's.
 */
private fun String.withInventedField(): String = when {
    startsWith("[") -> replaceFirst("\"Node Type\"", "\"Cranberry Count\": 4,\n      \"Node Type\"")
    startsWith("<") -> replaceFirst("<Node-Type>", "<Cranberry-Count>4</Cranberry-Count>\n      <Node-Type>")
    else -> replaceFirst("    Node Type:", "    Cranberry Count: 4\n    Node Type:")
}
