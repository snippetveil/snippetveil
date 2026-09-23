package com.snippetveil.core

/**
 * **The chrome a client drew around a plan, peeled off before anything is classified** — and the
 * line between chrome and a transcript, which is where this stops.
 *
 * ### A frame is admitted; a transcript is not
 *
 * A **frame** is what the client drew: a column header, a rule of dashes, the space it indents each
 * row by, the padding it aligns them with, and the count of rows it printed. Every one of those is
 * the client's own text, computed from the plan and carrying nothing else — so peeling it costs
 * nothing and refusing it would refuse the shape most users actually arrive with.
 *
 * A **transcript** is different in kind. A row echoing the user's statement is the statement, sitting
 * in the input under a client's prompt, and an input carrying one refuses. That falls out of the
 * rules below rather than being special-cased: a prompt line is not one of the fixed rows, so the
 * frame does not match, and nothing else in the input will be read as a plan either.
 *
 * ### The fixed rows are byte-exact, and the frame list is closed
 *
 * Two frames are admitted, both `psql`'s aligned format **at default settings**, and each in both
 * line styles:
 *
 *  - **One row per plan line**, which is what a text plan produces: the count says how many lines
 *    there were, and it has to agree with how many there are.
 *  - **One row holding the whole plan**, which is what a structured plan produces: every line but
 *    the last carries the client's continuation marker, and the count says one row.
 *
 * Anything else refuses — a border style, an unaligned format, tuples-only, a different column
 * header. **A client version is a compatibility event**, exactly like an engine release that adds a
 * field, and for the same reason: nothing else here is allowed to guess what an unrecognised row is.
 *
 * ### Re-emitted byte-identically, and that is a property of the shape
 *
 * The frame is not copied, rewritten or reassembled. The peel produces the plan's text **and a map
 * back into the input**, so every occurrence this product reports lands inside a content range and
 * every character of the frame is one no rule can reach. See [PlanFraming.translate].
 */
internal class PlanFraming(val inner: String, val segments: List<PlanSegment>) {

    /**
     * Where [at], an offset into [inner], lies in the input the frame was peeled from.
     *
     * @throws PlanRefusal where `[at, to)` crosses from one framed row into the next. A frame peels
     *   **line by line**, so a value spanning two rows is one this reader cannot address without
     *   splicing over the client's own chrome — and it refuses rather than doing that. It is
     *   unreachable without a frame, where the whole input is one segment.
     */
    fun translate(at: Int, to: Int): Int {
        val segment = segments.lastOrNull { at >= it.from } ?: throw PlanRefusal(PlanReading.Unreadable)
        if (to > segment.from + segment.length) throw PlanRefusal(PlanReading.Unreadable)
        return segment.at + (at - segment.from)
    }

    /** The same occurrence, with every offset moved back into the framed input. */
    fun translate(occurrence: PlanOccurrence) = PlanOccurrence(
        translate(occurrence.start, occurrence.end),
        translate(occurrence.end, occurrence.end),
        occurrence.disposition,
        translate(occurrence.nameStart, occurrence.nameEnd),
        translate(occurrence.nameEnd, occurrence.nameEnd),
    )
}

/**
 * One contiguous run of plan text inside the input.
 *
 * @param from where it begins in [PlanFraming.inner]
 * @param at where it begins in the input
 * @param length how long it is, which is the same in both
 */
internal class PlanSegment(val from: Int, val at: Int, val length: Int)

/**
 * **The plan inside [text]**, with whatever client frame was drawn around it taken off — or the whole
 * of [text] where there is no frame to take off.
 *
 * Unframed input is one segment covering everything, so nothing downstream has two cases to handle
 * and the translation is the identity. A text that *looks* framed and is not exactly one of the
 * admitted frames is returned unpeeled, and then refuses at recognition like any other text that is
 * not a plan — which is what makes *a non-default client setting refuses* a consequence of the frame
 * list being closed rather than a rule of its own.
 */
internal fun framingOf(text: String): PlanFraming =
    FRAME_STYLES.firstNotNullOfOrNull { peel(text, it) } ?: PlanFraming(text, listOf(PlanSegment(0, 0, text.length)))

/**
 * **One client's line style** — the character it rules with, and the marker it ends a continued row
 * with.
 *
 * Two of them because `psql` ships two: the ASCII default, and the `unicode` line style, which a user
 * who has set it once meets on every plan afterwards.
 *
 * @param rule the character the rule under the header is drawn with
 * @param marker what the client writes at the right edge of a row that continues on the next line
 */
private class FrameStyle(val rule: Char, val marker: Char)

/** The two line styles `psql` draws an aligned table in. See [FrameStyle]. */
private val FRAME_STYLES = listOf(FrameStyle('-', '+'), FrameStyle('─', '↵'))

/** The column header `EXPLAIN`'s one column is printed under, byte-exact once the padding is off. */
private const val QUERY_PLAN = "QUERY PLAN"

/** The count the client prints under the table, with the number it computed from the plan. */
private val ROW_COUNT = Regex("""\((\d+) rows?\)""")

/**
 * The plan inside [text] under [style], or `null` where [text] is not that frame.
 *
 * Every row is checked rather than skipped past, and the count is checked **against the plan** rather
 * than read: a count the client computed from something else is a frame this product has not seen.
 */
private fun peel(text: String, style: FrameStyle): PlanFraming? {
    val lines = mutableListOf<Pair<String, Int>>()
    var at = 0
    while (at <= text.length) {
        val end = text.indexOf('\n', at).takeIf { it >= 0 } ?: text.length
        lines += text.substring(at, end) to at
        if (end == text.length) break
        at = end + 1
    }

    if (lines.size < 4) return null
    if (lines[0].first.trim() != QUERY_PLAN) return null

    val rule = lines[1].first
    if (rule.length < QUERY_PLAN.length || rule.any { it != style.rule }) return null

    // The rows, which run to the count line. Each is the client's leading space, the plan's own
    // characters, the padding that aligns the column, and — where the row continues — the marker.
    val rows = mutableListOf<PlanSegment>()
    var continued = false
    var line = 2
    var inner = 0
    while (line < lines.size) {
        val (row, start) = lines[line]
        if (ROW_COUNT.matches(row)) break
        if (!row.startsWith(" ")) return null

        var end = row.length
        val marks = end > 0 && row[end - 1] == style.marker
        if (marks) end--
        while (end > 1 && row[end - 1] == ' ') end--

        rows += PlanSegment(inner, start + 1, end - 1)
        inner += end - 1 + 1
        continued = marks
        line++
    }

    // A row whose marker says it continues, with nothing after it, is a table this product did not
    // see the end of.
    if (rows.isEmpty() || continued) return null

    val count = lines.getOrNull(line)?.first?.let { ROW_COUNT.matchEntire(it) } ?: return null
    val printed = count.groupValues[1].toIntOrNull() ?: return null

    // **The count is the client's arithmetic over the plan**, so it is checked against the plan: one
    // row per line for a text plan, and one row holding every line for a structured one.
    val expected = if (rows.size > 1 && markerRuns(lines, style, 2, line)) 1 else rows.size
    if (printed != expected) return null

    // Nothing but blank lines may follow the count. A prompt, an echoed statement or a second table
    // is not chrome, and an input carrying one is not one plan with a frame around it.
    if ((line + 1 until lines.size).any { lines[it].first.isNotBlank() }) return null

    val body = rows.joinToString("\n") { text.substring(it.at, it.at + it.length) }
    return PlanFraming(body, rows)
}

/** Whether every row but the last carries the continuation marker, which is one cell over many lines. */
private fun markerRuns(lines: List<Pair<String, Int>>, style: FrameStyle, from: Int, to: Int): Boolean =
    (from until to - 1).all { lines[it].first.endsWith(style.marker) } && !lines[to - 1].first.endsWith(style.marker)
