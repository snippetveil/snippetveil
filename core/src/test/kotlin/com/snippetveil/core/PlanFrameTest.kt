package com.snippetveil.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **A client frame is admitted; a transcript is not** — and the frame comes back byte for byte.
 *
 * The frames are built here the way `psql` builds them rather than pasted as literals, because the
 * widths and the row count are **computed from the plan**: a fixture with hand-typed padding would
 * assert that this product tolerates one particular table rather than that it reads the client's
 * arithmetic.
 */
class PlanFrameTest {

    /**
     * **Each admitted frame is peeled, the plan inside anonymizes, and the frame is re-emitted
     * byte-identically** — for a text plan and for a structured one, in both of the client's line
     * styles.
     *
     * Byte-identical is asserted by comparing everything *outside* the plan's own characters before
     * and after. That is the property the design turns on: the frame is never copied or reassembled,
     * so no rule here can reach it.
     */
    @Test
    fun `an admitted frame is peeled, read and re-emitted byte-identically`() {
        for ((style, frame) in FRAMES) {
            val framed = frame(FRAMED_TEXT_PLAN, false)
            val text = anonymizedText(framed)

            assertFalse("visits" in text, "a relation inside a $style frame reached the clipboard:\n$text")
            assertTrue("0.123 ms" in text, "a measurement inside a $style frame was moved:\n$text")
            assertEquals(chromeOf(framed), chromeOf(text), "the $style frame was not re-emitted byte-identically")
        }
    }

    /**
     * **A structured plan arrives as one cell over many lines**, each but the last carrying the
     * client's continuation marker — which is the shape a user gets from `EXPLAIN (FORMAT JSON)` in
     * a terminal, and the reason the marker is part of the frame list at all.
     */
    @Test
    fun `a structured plan inside a one-cell frame is peeled and read`() {
        for ((style, frame) in FRAMES) {
            val framed = frame(JSON_PLAN, true)
            val text = anonymizedText(framed)

            assertFalse("invoices" in text, "a relation inside a $style frame reached the clipboard:\n$text")
            assertTrue("16.97" in text, "a measurement inside a $style frame was moved:\n$text")
            assertEquals(chromeOf(framed), chromeOf(text), "the $style frame was not re-emitted byte-identically")
        }
    }

    /**
     * **A row count the client did not compute from this plan is not this client's frame.**
     *
     * The count is the one part of the frame that is arithmetic over the plan, so it is checked
     * against the plan rather than read past — which is what keeps *the frame list is closed* from
     * quietly admitting a table with rows this product never saw.
     */
    @Test
    fun `a row count that does not match the rows refuses`() {
        val table = FRAMES.getValue("ascii")(FRAMED_TEXT_PLAN, false)
        val miscounted = table.replace(Regex("""\(\d+ rows\)"""), "(9 rows)")

        assertTrue(parsePlan(table) is PlanReading.Read, "the table alone does not read, so this asserts nothing")
        assertEquals(PlanReading.Unreadable, parsePlan(miscounted))
    }

    /**
     * **A non-default client setting refuses.**
     *
     * Each of these is one `\pset` away from the admitted frame and none of them is it: a border
     * style draws the rows inside pipes, the unaligned format prints no rule at all, and tuples-only
     * prints no header. The frame list is closed and each row is byte-exact, so **a client version is
     * a compatibility event** in exactly the way an engine release that adds a field is.
     */
    @Test
    fun `a non-default client setting refuses`() {
        val bordered = """
            |+------------------------------------------------------------+
            ||                          QUERY PLAN                        |
            |+------------------------------------------------------------+
            || Seq Scan on visits  (cost=0.00..35.50 rows=2550 width=244)  |
            |+------------------------------------------------------------+
            |(1 row)
        """.trimMargin()
        val unaligned = "QUERY PLAN\nSeq Scan on visits  (cost=0.00..35.50 rows=2550 width=244)\n(1 row)"
        val tuplesOnly = " Seq Scan on visits  (cost=0.00..35.50 rows=2550 width=244)\n"

        for (setting in listOf(bordered, unaligned, tuplesOnly)) {
            assertEquals(PlanReading.Unreadable, parsePlan(setting), "a non-default client setting was read:\n$setting")
        }
    }

    /**
     * **A frame is chrome; a row echoing the user's statement is a transcript, and an input carrying
     * one refuses.**
     *
     * The difference is not decoration versus content. A frame carries nothing but what the client
     * computed from the plan; an echoed statement **is the statement**, which is the one thing in the
     * input this product is most careful with. Asserted above and below the table, because a pasted
     * terminal session usually has both.
     */
    @Test
    fun `an input carrying a statement echo refuses`() {
        val table = FRAMES.getValue("ascii")(FRAMED_TEXT_PLAN, false)

        assertTrue(parsePlan(table) is PlanReading.Read, "the table alone does not read, so this asserts nothing")
        assertEquals(
            PlanReading.Unreadable,
            parsePlan("snippetveil=# EXPLAIN ANALYZE SELECT * FROM visits;\n$table"),
            "a statement echoed above the table was read as chrome",
        )
        assertEquals(
            PlanReading.Unreadable,
            parsePlan("$table\nsnippetveil=# EXPLAIN ANALYZE SELECT * FROM visits;"),
            "a statement echoed below the table was read as chrome",
        )
    }

    /** A subtree pasted out of the middle of a framed table still refuses: peeling moves no anchor. */
    @Test
    fun `a subtree pasted out of a framed table still refuses`() {
        val table = FRAMES.getValue("ascii")(FRAMED_TEXT_PLAN.lines().drop(1).joinToString("\n"), false)

        assertEquals(PlanReading.Unreadable, parsePlan(table))
    }
}

/** A plan of three rows, which is what a client prints three rows of and counts as three. */
private val FRAMED_TEXT_PLAN = """
    |Seq Scan on visits  (cost=0.00..35.50 rows=2550 width=244)
    |  Filter: ((status)::text = 'open'::text)
    |Planning Time: 0.123 ms
""".trimMargin()

/** The two line styles a client draws an aligned table in, named. */
private val FRAMES: Map<String, (String, Boolean) -> String> = mapOf(
    "ascii" to { plan, oneCell -> psqlFrame(plan, '-', '+', oneCell) },
    "unicode" to { plan, oneCell -> psqlFrame(plan, '─', '↵', oneCell) },
)

/**
 * `psql`'s aligned table around [plan], built the way the client builds one: a column as wide as its
 * widest line, a centred header, a rule, every row indented by a space, and the count underneath.
 *
 * @param oneCell whether the plan arrived as **one** value over many lines, which is what a
 *   structured format returns — the client then pads each line but the last and marks it as
 *   continuing.
 */
private fun psqlFrame(plan: String, rule: Char, marker: Char, oneCell: Boolean): String {
    val lines = plan.lines()
    val width = maxOf(lines.maxOf { it.length }, HEADER.length)
    val header = HEADER.padStart((width + HEADER.length) / 2).padEnd(width)
    val rows = lines.mapIndexed { index, line ->
        if (oneCell && index < lines.lastIndex) " " + line.padEnd(width) + marker else " $line"
    }
    val count = if (oneCell || lines.size == 1) "(1 row)" else "(${lines.size} rows)"

    return (listOf(" $header", rule.toString().repeat(width + 2)) + rows + count + "").joinToString("\n")
}

private const val HEADER = "QUERY PLAN"

/**
 * Everything in [text] that is **not** the plan's own characters — the frame, with the content
 * removed.
 *
 * Comparing this before and after is what *re-emitted byte-identically* means: the frame is not
 * copied, rewritten or reassembled by anything here, so every character of it that went in has to
 * come back.
 */
private fun chromeOf(text: String): String {
    val framing = framingOf(text)
    val chrome = StringBuilder(text)
    for (segment in framing.segments.asReversed()) chrome.delete(segment.at, segment.at + segment.length)
    return chrome.toString()
}
