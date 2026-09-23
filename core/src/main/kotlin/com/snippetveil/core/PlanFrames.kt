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
 * Two frames are admitted from `psql`'s aligned format **at default settings**, each in both line
 * styles:
 *
 *  - **One row per plan line**, which is what a text plan produces: the count says how many lines
 *    there were, and it has to agree with how many there are.
 *  - **One row holding the whole plan**, which is what a structured plan produces: every line but
 *    the last carries the client's continuation marker, and the count says one row.
 *
 * And a third from the `mysql` client, which is **the one admitted frame that is not a setting** —
 * the vertical terminator's. See [peelVertical] for why a keystroke typed per statement is a
 * different thing from a client configured a way this product has not captured.
 *
 * Anything else refuses — a border style, an unaligned format, tuples-only, a different column
 * header, and the bordered table the `mysql` client's default terminator draws. **A client version is
 * a compatibility event**, exactly like an engine release that adds a field, and for the same reason:
 * nothing else here is allowed to guess what an unrecognised row is.
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
        val segment = segments.lastOrNull { at >= it.inner } ?: throw PlanRefusal(PlanReading.Unreadable)
        if (to > segment.inner + segment.length) throw PlanRefusal(PlanReading.Unreadable)
        return segment.outer + (at - segment.inner)
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
 * Both offsets are named for **which string they index**, because that is the only thing that tells
 * them apart and a pair of bare integers here is the mistake the whole frame turns on.
 *
 * @param inner where it begins in [PlanFraming.inner] — the plan with the frame off
 * @param outer where it begins in the input the frame was peeled from
 * @param length how long it is, which is the same in both
 */
internal class PlanSegment(val inner: Int, val outer: Int, val length: Int)

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
    FRAME_STYLES.firstNotNullOfOrNull { peel(text, it) }
        ?: peelVertical(text)
        ?: PlanFraming(text, listOf(PlanSegment(0, 0, text.length)))

/**
 * **The `mysql` client's vertical frame, peeled** — the row banner, the column label, and the count
 * underneath — or `null` where [text] is not exactly that.
 *
 * ### Why a keystroke's frame is admitted where a setting's is not
 *
 * The rule above is *a capture taken at the client's default **settings***, and a user who has
 * changed one meets a frame this product has never seen on every plan afterwards. The vertical
 * terminator is not a setting: `\G` is typed in place of `;` **per statement**, and the frame it
 * produces is fixed — the same banner, the same `Label: ` and the same count, whatever the client is
 * configured to do with `;`. So it is a capture of its own rather than a variant of one, and it is
 * admitted.
 *
 * **The bordered table the default terminator draws around an admitted format is a different thing
 * and the two must not be confused.** That frame is not peeled here: it awaits a capture, and until
 * one exists a plan inside one is not recognised at all. It is refused rather than read, which is the
 * fail-closed direction — see [MYSQL_FORMATS] for the one thing that does look past it, which looks
 * only to decide which refusal to print.
 *
 * ### One row, and the count says so
 *
 * A plan is one row. The banner is checked to be the **first** row's, and the count underneath — the
 * client's own arithmetic — is checked to agree that there was one. A second banner is a result set
 * this reader has not seen, and it refuses rather than reading the first row and ignoring the rest.
 *
 * The value itself is **one contiguous run of the input**: `\G` writes a multi-line value with its
 * newlines intact and no per-line chrome, so unlike `psql`'s aligned table there is nothing inside it
 * to skip. One segment, and the translation back is an offset.
 */
private fun peelVertical(text: String): PlanFraming? {
    val lines = linesIn(text)
    if (lines.size < 2) return null
    if (!isRowBanner(lines[0].text)) return null

    val opening = lines[1].text
    if (!opening.startsWith(VERTICAL_LABEL)) return null

    // Where the value begins, and where the rows stop: at the count line, at a second row's banner —
    // which is a result set this reader has not seen — or at the end of the input.
    val start = lines[1].start + VERTICAL_LABEL.length
    var line = 2
    while (line < lines.size) {
        val row = lines[line].text
        if (isRowBanner(row)) return null
        if (VERTICAL_COUNT.matches(row)) break
        line++
    }

    val last = lines[line - 1]
    val end = last.start + last.text.length
    if (end < start) return null

    if (line < lines.size) {
        // The count is the client's arithmetic over the result, so it is checked against it: one row.
        val printed = VERTICAL_COUNT.matchEntire(lines[line].text)?.groupValues?.get(1)?.toIntOrNull()
        if (printed != 1) return null

        // Nothing but blank lines may follow it. A prompt or an echoed statement is not chrome.
        if ((line + 1 until lines.size).any { lines[it].text.isNotBlank() }) return null
    }

    return PlanFraming(text.substring(start, end), listOf(PlanSegment(0, start, end - start)))
}

/** Whether this is the banner the client prints above a row — `**** 1. row ****`, stars and all. */
private fun isRowBanner(line: String): Boolean = ROW_BANNER.matches(line)

/**
 * The banner the vertical terminator prints above each row of a result.
 *
 * The row number is read so that **only the first row's banner opens a frame**: a paste beginning at
 * the second row of a result is a paste whose first row this product never saw, and it is not one
 * plan with a frame around it.
 */
private val ROW_BANNER = Regex("""\*{3,} 1\. row \*{3,}""")

/** The count the client prints under a vertical result, with the number of rows it printed. */
private val VERTICAL_COUNT = Regex("""(\d+) rows? in set \(.*\)""")

/** The column label the client writes a plan's one column under, with the space that follows it. */
private const val VERTICAL_LABEL = "EXPLAIN: "

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
    // A carriage return is a line terminator rather than a row's content, so [linesIn] leaves it off
    // the line — which puts it inside the frame, where it belongs and where nothing can reach it.
    val lines = linesIn(text)

    if (lines.size < 4) return null
    if (lines[0].text.trim() != QUERY_PLAN) return null

    val rule = lines[1].text
    if (rule.length < QUERY_PLAN.length || rule.any { it != style.rule }) return null

    // The rows, which run to the count line. Each is the client's leading space, the plan's own
    // characters, the padding that aligns the column, and — where the row continues — the marker.
    val rows = mutableListOf<PlanSegment>()
    var continued = false
    var line = 2
    var inner = 0
    while (line < lines.size) {
        val row = lines[line].text
        if (ROW_COUNT.matches(row)) break
        if (!row.startsWith(" ")) return null

        var end = row.length
        val marks = end > 0 && row[end - 1] == style.marker
        if (marks) end--
        while (end > 1 && row[end - 1] == ' ') end--

        rows += PlanSegment(inner, lines[line].start + 1, end - 1)
        inner += end - 1 + 1
        continued = marks
        line++
    }

    // A row whose marker says it continues, with nothing after it, is a table this product did not
    // see the end of.
    if (rows.isEmpty() || continued) return null

    val count = lines.getOrNull(line)?.text?.let { ROW_COUNT.matchEntire(it) } ?: return null
    val printed = count.groupValues[1].toIntOrNull() ?: return null

    // **The count is the client's arithmetic over the plan**, so it is checked against the plan: one
    // row per line for a text plan, and one row holding every line for a structured one.
    val expected = if (rows.size > 1 && markerRuns(lines, style, 2, line)) 1 else rows.size
    if (printed != expected) return null

    // Nothing but blank lines may follow the count. A prompt, an echoed statement or a second table
    // is not chrome, and an input carrying one is not one plan with a frame around it.
    if ((line + 1 until lines.size).any { lines[it].text.isNotBlank() }) return null

    val body = rows.joinToString("\n") { text.substring(it.outer, it.outer + it.length) }
    return PlanFraming(body, rows)
}

/**
 * Whether every row **but the last** carries the continuation marker, which is one cell over many
 * lines.
 *
 * The last row is not re-checked here: the caller has already refused a table whose final row still
 * says it continues, because that is a table it did not see the end of.
 */
private fun markerRuns(lines: List<PlanTextLine>, style: FrameStyle, from: Int, to: Int): Boolean =
    (from until to - 1).all { lines[it].text.endsWith(style.marker) }
