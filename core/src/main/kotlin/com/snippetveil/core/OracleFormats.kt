package com.snippetveil.core

/**
 * **Oracle's formats** — the grid text output `DBMS_XPLAN` prints, the SQL Monitor XML report, and
 * the four shapes that are refused.
 *
 * ### The grid text output is admitted, and it is admitted on a counting argument
 *
 * Oracle does not escape anything inside its `|`-ruled grid: a `|` inside a name prints raw. It is
 * admissible anyway, because that character can only ever **add** a cell — a row whose `|` count
 * differs from the header's is malformed and **refuses**. Nothing can be hidden by it, only revealed
 * as broken.
 *
 * > **Cells are read by count, never by offset.**
 *
 * Display-width padding moves the separators for wide characters, so offset arithmetic silently
 * mis-slices a name. **Names are never truncated** in this output, which is what makes reading by
 * count sufficient in the first place.
 *
 * **Grids are allowed to go ragged.** Replacing a long index name with a short placeholder shortens a
 * cell and the columns stop lining up. That is allowed, because this format's own admission rests on
 * cells being read by count and never by offset — so nothing that reads the plan is broken by it, and
 * a human still reads the tree. **Re-padding was rejected**: it has the product emit whitespace the
 * engine never printed, and it would need the engine module to know which formats are grids.
 *
 * ### A client form that wraps refuses, and that is a verdict rather than a gap
 *
 * The standard client at its default line width **wraps the grid**, and a wrapped row fails the
 * `|`-count check — so the natural default-settings Oracle paste refuses. It refuses with *not a
 * readable plan*, because what the user pasted is an **admitted** form their client damaged rather
 * than a recognised refused form, and **the message does not name the line-width setting**: naming a
 * cause requires having recognised it, and this refusal is by definition the case where nothing
 * matched.
 *
 * ### What is refused, and why none of it is a recognised refused form
 *
 * The HTML and active report forms are not paste shapes and one of them embeds compressed data;
 * plan-table rows rendered as CSV by a client have lost the separators the grid argument rests on;
 * and **SQL Monitor's text report** is the same grid shape with no anchor line and a transcript
 * header above it. **None of these names a recourse**, and that is what decides how they refuse:
 * every recognised refused form in this product exists in order to *say something specific*, and the
 * only specific thing there would be to say about the text report — run it again as XML — is the
 * recourse the decision refuses to name. So each of them is refused by not being recognised, which is
 * the general verdict, and the general verdict names nothing by construction.
 *
 * None of them reaches a reader: the grid's predicate is anchored on the plan-hash line or the grid's
 * own rule, and the report's on the root element Oracle's monitoring writer writes.
 */
internal val ORACLE_FORMATS: List<PlanFormat> = listOf(
    // **The grid is what `DBMS_XPLAN` prints with no format argument**, which is this engine's
    // default form — and it is admitted, which is the second row carrying the coverage floor.
    reads(
        "oracle-grid-text",
        PlanEngine.ORACLE,
        PlanFormatReach.DEFAULT,
        ::opensAnOracleGrid,
        ::oracleGridOccurrencesIn,
    ),
    // **Further than one option**: a report procedure called with an XML type argument, against a
    // separately licensed pack. A user reaches it deliberately, so it can never carry the floor.
    reads("oracle-sql-monitor-xml", PlanEngine.ORACLE, PlanFormatReach.FURTHER, ::opensASqlMonitorReport) {
        structuredOccurrencesIn(
            contentXmlDocumentIn(it, MONITOR_ROOT)?.let(::listOf),
            ORACLE_MONITOR_FIELDS,
            ORACLE,
            AS_WRITTEN,
            XML_QUOTE,
        )
    },
)

/**
 * **Whether this opens a `DBMS_XPLAN` grid** — the plan-hash line, the grid's own rule, or its header
 * row.
 *
 * Three openings, and each is one a user actually copies. **The plan-hash line is the anchor the
 * cursor-display variant needs**: that variant prints the statement above its plan, so a paste of the
 * whole thing is a transcript and refuses, and the line the user has to start at is the one the
 * plan's own header begins with. The other two are what a paste of the grid alone begins with.
 *
 * **The header row is checked cell for cell against the column vocabulary**, which is what keeps this
 * predicate from reaching for somebody else's bordered table: a `|`-ruled grid whose columns are not
 * Oracle's is not recognised here at all.
 *
 * Only the first two lines with anything on them are read, so nothing in the body of a paste can
 * reach a predicate — and SQL Monitor's text report, whose grid is this same shape, is not recognised
 * because its report begins with a transcript header rather than with either anchor.
 */
private fun opensAnOracleGrid(text: String): Boolean {
    val head = headLinesIn(text, 2)
    val first = head.firstOrNull() ?: return false

    if (PLAN_HASH.matches(first.trim())) return true
    if (isARule(first)) return head.getOrNull(1)?.let(::opensAGridHeader) == true
    return opensAGridHeader(first)
}

/** Whether this line is the grid's header row — a `|`-ruled row of columns this product reads. */
private fun opensAGridHeader(line: String): Boolean {
    val cells = line.trim().takeIf { it.startsWith(BAR) && it.endsWith(BAR) }?.split(BAR) ?: return false
    val columns = cells.subList(1, cells.size - 1).map(String::trim)
    return columns.isNotEmpty() && columns.all { it in ORACLE_GRID_COLUMNS }
}

/** **Whether this opens Oracle's SQL Monitor report** — the root element its writer writes. */
private fun opensASqlMonitorReport(text: String): Boolean = opensAnElement(text, MONITOR_ROOT)

/** The root element Oracle's monitoring report writer writes. */
private const val MONITOR_ROOT = "report"

/**
 * **Everything a grid text plan reports** — the rows read twice against one set of declarations.
 *
 * The two passes are [occurrencesOverTwoPasses]'s, and this format needs them as plainly as any: the
 * outline printed under the plan names the aliases the plan's own rows introduced, and a plan whose
 * outline is read before its grid would key one of the two printings under the wrong owner.
 */
private fun oracleGridOccurrencesIn(text: String): List<PlanOccurrence> {
    val lines = linesIn(text)
    return occurrencesOverTwoPasses(ORACLE) { symbols -> OracleGridReader(symbols).read(lines) }
}

/**
 * **The reading of one grid text plan** — the line closure, the grid read by count, and the two
 * sections printed under it that this product has a reading for.
 *
 * A class rather than a run of functions because a grid has state: the header decides how many cells
 * every row after it has, and the section headers decide what the lines under them are. That state is
 * also the whole of the safety argument — **a line is read under the section it is in, and a line
 * under no section this reader knows refuses the input.**
 *
 * ### What has no reading here, and therefore refuses
 *
 * `Note`, the column-projection section, the query-block listing and the hint report are printed by
 * the same procedure and are **not read**: no default-settings capture of them was taken here, and a
 * vocabulary row may not be written from vendor documentation. Each is a line the closure has no row
 * for, so a plan carrying one refuses until a capture and a row follow. That is the field closure's
 * cost, accepted here rather than discovered later.
 *
 * ### The peeked-bind section refuses on its header
 *
 * The grid can print a peeked-bind section carrying a value **and** a size on one line. Its treatment
 * is decided — the size drops under the drop rule, the value takes the exactly-one-literal test
 * because the engine quotes it, the character-set id is an engine fact — but **no default-settings
 * capture of it exists**, so any input carrying it refuses, recognised on its header. The cost is
 * small: the section is absent from freshly run plans, which is most of what a user pastes while
 * debugging.
 */
private class OracleGridReader(private val symbols: PlanSymbols) {

    /** The section the lines being read belong to, which is what says what they are. */
    private var section = OracleSection.HEAD

    /** The grid's columns, in order — `null` until the header row has been read. */
    private var columns: List<String>? = null

    /** Which cell of a row carries its operation, or `null` where the grid prints no such column. */
    private var operation: Int? = null

    /** Whether the outline's own comment has been opened, which is where its hints begin. */
    private var opened = false

    fun read(lines: List<PlanTextLine>) {
        lines.forEach(::readLine)

        // A paste with no grid in it is not a plan: the anchor line alone says a plan exists
        // somewhere, and this product does not report on a plan it was not given.
        if (columns == null) throw PlanRefusal(PlanReading.Unreadable)
    }

    private fun readLine(line: PlanTextLine) {
        val body = line.text.trim()
        if (body.isEmpty()) return

        // **The peeked-bind section, refused on its header** — before anything else, because the
        // section may be printed under any of the sections this reader does read.
        if (body.startsWith(PEEKED_BINDS)) throw PlanRefusal(PlanReading.Unreadable)

        val titled = SECTIONS[body]
        if (titled != null) {
            section = titled
            return
        }

        // A rule of dashes is layout — under the header, under a section's title — and survives by
        // not being reported at all.
        if (isARule(line.text)) return

        when (section) {
            OracleSection.HEAD, OracleSection.GRID -> readPlanLine(line, body)
            OracleSection.PREDICATES -> readPredicate(line)
            OracleSection.OUTLINE -> readOutline(line, body)

            // **Past the end of a section, and not at the start of another.** The outline says where
            // it stops, so a line after it belongs to a section this reader has no row for — and a
            // line nothing classified refuses, exactly as an unrecognised line does anywhere else.
            OracleSection.CLOSED -> throw PlanRefusal(PlanReading.Unreadable)
        }
    }

    /** A line of the plan itself: the anchor the cursor variant is pasted from, or a grid row. */
    private fun readPlanLine(line: PlanTextLine, body: String) {
        if (body.startsWith(BAR)) {
            readRow(line, body)
            section = OracleSection.GRID
            return
        }

        // **The plan hash, masked.** It is derived from the plan, so a receiver holding a candidate
        // statement could confirm it by matching the hash — the attack every identifying value in
        // this product is masked over, and the one this container's drop rule is argued from.
        val hash = PLAN_HASH.matchEntire(body)
        if (hash == null || section != OracleSection.HEAD) throw PlanRefusal(PlanReading.Unreadable)
        val at = line.start + line.text.indexOf(hash.groupValues[1], PLAN_HASH_LABEL.length)
        symbols.occurrences += PlanTreatments.identifying(
            PlanSlot(line.text, at, at + hash.groupValues[1].length, line.start),
        )
    }

    /**
     * **One row of the grid, cut into cells by counting separators** — the header where none has been
     * read yet, and a row of the plan otherwise.
     *
     * @throws PlanRefusal where the row draws a different number of separators than the header did,
     *   which is the same statement as its having a different number of cells — a row's cells are
     *   what lie between its separators. That is the whole of what admits this format: a `|` inside a name prints raw, so it can only
     *   ever **add** a cell, and a row carrying one is malformed rather than misread. It is also what
     *   refuses a grid a client wrapped, which loses the separators at the end of every row it broke.
     */
    private fun readRow(line: PlanTextLine, body: String) {
        if (!body.endsWith(BAR)) throw PlanRefusal(PlanReading.Unreadable)

        val cells = cellsOf(line)
        val header = columns
        if (header == null) {
            readHeader(cells)
            return
        }
        if (cells.size != header.size) throw PlanRefusal(PlanReading.Unreadable)

        // A second header row is a second grid — an adaptive plan's, or two plans in one paste —
        // and no capture of one was taken here, so it refuses rather than being read as a row.
        val written = cells.map { it.trimmed().written }
        if (written == header) throw PlanRefusal(PlanReading.Unreadable)

        val operation = this.operation?.let { written[it] }.orEmpty()
        for ((position, cell) in cells.withIndex()) {
            val column = header[position]

            // **The `Name` cell's kind is read off the operation beside it**, which is admissible
            // because both answers replace what they find. See [oracleNameKindOf].
            val treatment = if (column == NAME_COLUMN) {
                PlanTreatment.Name(oracleNameKindOf(operation))
            } else {
                ORACLE_GRID_COLUMNS.getValue(column)
            }
            if (!symbols.readSlot(cell.trimmed(), treatment)) throw PlanRefusal(PlanReading.Unreadable)
        }
    }

    /**
     * **The header row, which fixes the columns and, with them, how many cells every row after it
     * has** — and the field closure, which is the whole of what this function is.
     *
     * A column the vocabulary does not hold refuses the input. It is the same closure every format
     * here is read under, spelled for a format whose fields are columns: nothing says what an
     * unrecognised column holds, and reading past one is how a value leaves in the part nobody
     * looked at.
     */
    private fun readHeader(cells: List<PlanSlot>) {
        val written = cells.map { it.trimmed().written }
        if (written.isEmpty() || written.any { it !in ORACLE_GRID_COLUMNS }) {
            throw PlanRefusal(PlanReading.Unreadable)
        }
        columns = written
        operation = written.indexOf(OPERATION_COLUMN).takeIf { it >= 0 }
    }

    /**
     * The cells of one row: what lies between one separator and the next, with the run before the
     * first and after the last dropped — they are the row's own edges rather than cells.
     */
    private fun cellsOf(line: PlanTextLine): List<PlanSlot> {
        val bars = line.text.indices.filter { line.text[it] == BAR }
        return (0 until bars.size - 1).map {
            PlanSlot(line.text, line.start + bars[it] + 1, line.start + bars[it + 1], line.start)
        }
    }

    /**
     * **One row of the predicate section, read as the expression it is.**
     *
     * The row opens with the operation id it belongs to — `   1 - filter(…)` — and runs on over as
     * many continuation lines as the printer needed. Both are expression text and both are handed to
     * the lexer, which is safe for the reason every expression field in this product is: the slot's
     * boundaries are fixed by the engine's own quoting, and the residual inside it runs toward
     * replacement. See [PlanSymbols.readExpression].
     */
    private fun readPredicate(line: PlanTextLine) {
        val entry = ENTRY.find(line.text)?.takeIf { it.range.first == 0 }

        // **A row of this section is indented**, by the printer, under the operation id it belongs
        // to. A line at the margin is the next section's title — and a title this reader has no row
        // for is a line nothing classified, which refuses rather than being read as predicate text.
        if (entry == null && !line.text.first().isWhitespace()) throw PlanRefusal(PlanReading.Unreadable)

        val from = entry?.range?.last?.plus(1) ?: 0
        val slot = PlanSlot(line.text, line.start + from, line.start + line.text.length, line.start)
        if (!symbols.readSlot(slot.trimmed(), PlanTreatment.Expression)) {
            throw PlanRefusal(PlanReading.Unreadable)
        }
    }

    /**
     * **One line of the outline** — the comment it is written inside, or one hint.
     *
     * The comment's own delimiters are the engine's and survive by not being reported. Everything
     * else on the line is one hint, parsed against the closed per-version grammar. See [readHint].
     */
    private fun readOutline(line: PlanTextLine, body: String) {
        if (body == OUTLINE_OPEN) {
            opened = true
            return
        }

        // **The comment says where the outline stops**, which is what keeps this section from
        // swallowing the one printed under it: a hint is only ever read between the two delimiters,
        // and everything after them is a line the closure has to place.
        if (body == OUTLINE_CLOSE) {
            section = OracleSection.CLOSED
            return
        }
        if (!opened) throw PlanRefusal(PlanReading.Unreadable)

        val slot = PlanSlot(line.text, line.start, line.start + line.text.length, line.start)
        readHint(symbols, slot.trimmed())
    }
}

/**
 * **One outline hint, parsed against the closed per-version grammar — or replaced whole.**
 *
 * > **An off-list hint name, or a hint that does not parse, makes that one hint a redacted literal
 * > rather than refusing the plan.**
 *
 * The grammar is three things and no fourth: a hint name from [ORACLE_OUTLINE_HINTS], the bare words
 * of [ORACLE_HINT_WORDS], and **quoted names**, which is everything else in an outline. Oracle's
 * outline printer quotes every identifier it writes, so the quoted tokens are exactly the names and
 * every unquoted character is the printer's own.
 *
 * What each quoted token *is*, is read by position, the way every qualified name in this product is:
 * a token after `@` is the **query block** it names, a token before an `@` is an object **in** that
 * block, and a dotted pair is a table and a column.
 *
 * **Nothing partial is ever committed.** The occurrences are collected and handed over only if the
 * whole hint parsed — a hint half-read is a hint with a name left in it, which is the failure this
 * whole file is arranged to prevent.
 */
private fun readHint(symbols: PlanSymbols, slot: PlanSlot) {
    symbols.occurrences += hintIn(symbols, slot) ?: PlanTreatments.unreadable(slot)
}

/** What one hint reports, or `null` where it is not a hint this grammar parses. See [readHint]. */
private fun hintIn(symbols: PlanSymbols, slot: PlanSlot): List<PlanOccurrence>? {
    val scan = scanOf(slot)
    if (!scan.sound) return null

    val tokens = scan.tokens
    val name = tokens.firstOrNull() ?: return null
    if (name.kind != PlanTokenKind.WORD || name.text !in ORACLE_OUTLINE_HINTS) return null

    val occurrences = mutableListOf(PlanOccurrence(name.start, name.end, PlanDisposition.Preserve))
    if (tokens.size == 1) return occurrences
    if (!tokens[1].isMark("(") || !tokens.last().isMark(")")) return null

    val arguments = tokens.subList(2, tokens.size - 1)
    if (name.text in ORACLE_VERSION_HINTS) {
        // **The two hints whose argument is a version rather than a name**, preserved behind the
        // version shape. A literal anywhere else in an outline is the user's, and is masked.
        val only = arguments.singleOrNull() ?: return null
        if (only.kind != PlanTokenKind.LITERAL) return null
        if (!PlanShapes.VERSION.matches(slot.narrowed(only.nameStart, only.nameEnd).written)) return null
        occurrences += PlanOccurrence(only.start, only.end, PlanDisposition.Preserve)
        return occurrences
    }

    var at = 0
    while (at < arguments.size) {
        val token = arguments[at]
        when {
            token.kind == PlanTokenKind.NUMBER -> at++

            token.isMark("@") -> {
                val block = arguments.getOrNull(at + 1) ?: return null
                if (block.kind != PlanTokenKind.DELIMITED) return null
                occurrences += queryBlockIn(slot, block)
                at += 2
            }

            token.kind == PlanTokenKind.MARK && token.text in HINT_MARKS -> at++

            // A literal inside a hint that is not one of the two version hints is a value somebody
            // wrote — a parameter's setting, a sampling seed — and it is masked, content only.
            token.kind == PlanTokenKind.LITERAL -> {
                occurrences += PlanTreatments.literal(token)
                at++
            }

            token.kind == PlanTokenKind.WORD -> {
                if (token.text !in ORACLE_HINT_WORDS) return null
                occurrences += PlanOccurrence(token.start, token.end, PlanDisposition.Preserve)
                at++
            }

            token.kind == PlanTokenKind.DELIMITED -> {
                val chain = chainOf(arguments, at)
                for ((position, part) in chain.tokens.withIndex()) {
                    occurrences += symbols.anonymized(part, chainKindAt(chain.tokens.size, position))
                }
                at = chain.after

                // The object may say which query block it lives in — `"V"@"SEL$1"`.
                if (arguments.getOrNull(at)?.isMark("@") == true) {
                    val block = arguments.getOrNull(at + 1) ?: return null
                    if (block.kind != PlanTokenKind.DELIMITED) return null
                    occurrences += queryBlockIn(slot, block)
                    at += 2
                }
            }

            else -> return null
        }
    }
    return occurrences
}

/**
 * What the segment at [position] of a chain of [length] names.
 *
 * The same positional ladder [PlanSymbols.readName] and `readChain` walk, and it is written out again
 * rather than shared because the three **disagree about a lone token**, each for a reason of its own.
 * A lone quoted token in a hint is an **object** — the table or the alias the hint is about — where
 * a lone bracketed name in a `SHOWPLAN_TEXT` row is a column, since that writer has nothing to tell
 * a computed name from a real one. Folding them together would need a flag naming the difference and
 * would leave neither argument next to the rule it decides.
 */
private fun chainKindAt(length: Int, position: Int): SymbolRole = when {
    length == 1 -> SymbolRole.TABLE
    position == length - 1 -> SymbolRole.COLUMN
    position == length - 2 -> SymbolRole.TABLE
    else -> SymbolRole.SCHEMA
}

/**
 * **A query block name** — preserved where the optimizer invented it, masked where a statement chose
 * it.
 *
 * `SEL$1` is the printer's letters and its own counter, exactly as `SubPlan 1` is in a PostgreSQL
 * plan, and there is nothing of anybody's in it. A block a statement named for itself is the user's
 * word, and it is masked inside the quotes the printer drew — so the hint still reads as the hint it
 * is.
 */
private fun queryBlockIn(slot: PlanSlot, token: PlanToken): PlanOccurrence {
    val written = slot.narrowed(token.nameStart, token.nameEnd).written
    if (ORACLE_QUERY_BLOCK.matches(written)) {
        return PlanOccurrence(token.start, token.end, PlanDisposition.Preserve)
    }
    return PlanOccurrence(
        token.start,
        token.end,
        PlanDisposition.Mask(PlanKeys.masked(written)),
        token.nameStart,
        token.nameEnd,
    )
}

/** Which part of a grid text plan a line belongs to, which is what says how it is read. */
private enum class OracleSection { HEAD, GRID, PREDICATES, OUTLINE, CLOSED }

/**
 * **The sections this product reads**, by the title the printer writes above each.
 *
 * A closed list, and the closure is the point: a title this map does not hold is a line nothing
 * classified, and it refuses. See [OracleGridReader].
 */
private val SECTIONS: Map<String, OracleSection> = mapOf(
    "Predicate Information (identified by operation id):" to OracleSection.PREDICATES,
    "Outline Data" to OracleSection.OUTLINE,
)

/** The header the peeked-bind section is recognised on, and refused by. */
private const val PEEKED_BINDS = "Peeked Binds"

/** The line a plan's own header begins with, and the anchor the cursor variant is pasted from. */
private val PLAN_HASH = Regex("""Plan hash value: (\d+)""")

private const val PLAN_HASH_LABEL = "Plan hash value: "

/** What a predicate row opens with: the operation id it belongs to. */
private val ENTRY = Regex("""\s*\d+ - """)

/** What the outline writes its hints inside. */
private const val OUTLINE_OPEN = "/*+"

private const val OUTLINE_CLOSE = "*/"

/** The punctuation a hint's arguments are written with, outside the names themselves. */
private val HINT_MARKS: Set<String> = setOf("(", ")", ",", "=")

/** The separator this format's grid draws its cells with, and counts by. */
private const val BAR = '|'

/** Whether this line is one of the rules the printer draws under a header or a title. */
private fun isARule(line: String): Boolean {
    val body = line.trim()
    return body.length >= SHORTEST_RULE && body.all { it == '-' }
}

/** How short a run of dashes may be and still be a rule rather than something else. */
private const val SHORTEST_RULE = 3
