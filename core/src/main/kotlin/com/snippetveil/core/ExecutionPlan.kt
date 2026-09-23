package com.snippetveil.core

/**
 * **Reads a PostgreSQL `EXPLAIN` plan, and says what is a name in it** — the engine's third entry
 * point, and the only one with no IDE anywhere behind it.
 *
 * ### Why the parse is here rather than in the plugin
 *
 * *Evidence crosses the boundary, never judgments.* **Refusal is a judgment** — *this is not a
 * readable plan* — and under a plugin-side parse the highest-consequence judgment this action makes
 * would sit on the IDE side of a boundary built to stop exactly that. Nothing here needs an IDE: it
 * is a string in and a [SnippetPlan] out, which makes it plain JUnit, exhaustive, and measured in
 * milliseconds.
 *
 * It returns the **finished plan**, so the caller's whole job is to hand the result to [anonymize].
 * There is no resolve step between the two calls because nothing resolves. **That relay is
 * load-bearing ceremony rather than an inconsistency**: [SnippetPlan] stays the one thing every rule
 * operates over, and [anonymize] stays the engine's only function taking settings and a ledger.
 *
 * Rejected, so they are not proposed again: a one-shot call taking text, settings and a ledger,
 * which is a second stateful path; returning an intermediate for the caller to assemble, since
 * construction that consults nothing and adds nothing is transcription and transcription can be
 * wrong; and dropping inside the parse to return edited text, which costs the plan its verbatim-text
 * invariant — the one the preview and the round trip both read.
 *
 * ### Several formats, and exactly one of them recognised
 *
 * PostgreSQL's default **text** output and its **JSON**, **YAML** and **XML** ones; MySQL's two
 * **JSON** formats; and the shapes of MySQL's and MariaDB's that are recognised in order to be
 * refused well. Each has its own anchored recognition predicate, and **if two of them accept, the
 * input refuses rather than being read under one of the two** — see [PlanFormat]. Recognition still
 * begins at the plan's first line: a subtree pasted from the middle of a text plan begins with
 * indentation, and a paste carrying the query above the plan begins with the query, so both refuse.
 *
 * **A recogniser only ever picks the message.** It never decides what is emitted, and the clipboard
 * is untouched either way — but a refusal that named the wrong engine's option would be a false
 * statement this product made about somebody else's engine, so the predicates are held to the same
 * standard as the parser. See [MYSQL_FORMATS] and [MARIADB_FORMATS].
 *
 * A **client frame** is peeled before any of that — `psql`'s aligned header, its rule, the space it
 * indents each row by and the count it prints underneath — so the shape most users arrive with is
 * read rather than refused, and re-emitted byte for byte. A frame is chrome; **a row echoing the
 * user's own statement is a transcript, and an input carrying one refuses.** See [PlanFraming].
 *
 * ### What it reports, and what it never touches
 *
 * Relations, columns, schemas, aliases and indexes; the values the planner printed, masked; and
 * **nothing numeric**. The costs, the row estimates, the widths and the timings are read straight
 * past: a field of measurements is reported as nothing at all. A plan is pasted *for* its numbers,
 * and an anonymizer that moved one would have destroyed the reason it was sent.
 *
 * **Two closures decide the rest, and they run in opposite directions.** A field the vocabulary does
 * not hold **refuses the input**; a token the vocabulary does not know, inside a field it does hold,
 * **anonymizes**. Both are argued in [PlanTreatment], which is also where the cost of the first —
 * an engine release that adds a field refuses every plan carrying it until a vocabulary row follows —
 * is accepted rather than discovered later.
 */
fun parsePlan(text: String): PlanReading = readingOf(text)

/**
 * **What [parsePlan] made of the text**: the finished plan, or a verdict that it is not one.
 *
 * > The engine decides what the text **is**; the message layer decides how that reads.
 *
 * **No verdict carries a `String` field, and that is the mechanism rather than a convention.** With
 * nowhere to put input text, *no refusal message quotes the input* stops being a discipline somebody
 * has to keep and becomes a property of the type — which matters here more than anywhere, because
 * the input is a plan the user has not yet been told is safe to show anyone.
 *
 * Rejected for the same reason: returning a rendered message, which puts the product's words in the
 * engine; and a verdict carrying the offending offset, which is a handle on exactly the text this
 * action withholds handed to the side that would have to render it.
 */
sealed class PlanReading {

    /** The finished plan, ready for [anonymize] and for nothing in between. */
    class Read(val plan: SnippetPlan) : PlanReading()

    /**
     * **The text is not a plan this engine reads** — the general verdict, and the one nearly every
     * refusal takes.
     *
     * It says nothing about *why*, and there is nothing it could usefully say: a paste that is not a
     * plan, a client setting this product has not seen, a document that did not parse and a field no
     * vocabulary row covers are one answer to the user, and the answer is *copy the whole plan from
     * its first line, without the query*.
     *
     * **A field the vocabulary lacks lands here and adds nothing.** It is not a recognised refused
     * form: there is no other option of the engine's that this product knows would work, so there is
     * no recourse to offer and offering one anyway would be a guess printed as advice.
     */
    object Unreadable : PlanReading()

    /**
     * **A shape this product recognises as one it cannot read soundly** — named, so that the message
     * layer can say which shape it was and, where the engine offers a better one, which.
     *
     * The difference from [Unreadable] is not severity. It is that here the product **recognised what
     * arrived**, so it can say something true and specific about it instead of *this is not a plan I
     * read*. See [PlanRefusedForm] for the closed list, and [TEXT_RAW_NAME_ROWS] for the rows of
     * PostgreSQL's text format that produce one.
     */
    class Refused(val form: PlanRefusedForm) : PlanReading()
}

/**
 * **The shapes this product recognises and refuses** — an enumeration, never a sentence, and one row
 * per *situation the user is in* rather than one per reason a reader gave up.
 *
 * **A form rather than a bare recourse, because one message cannot honestly cover two situations.**
 * MySQL's `TREE` output and its tabular output are refused for different reasons and are fixed by the
 * same option, and a single sentence naming only the option would have to describe neither shape in
 * order to describe both. So the engine says *which shape arrived*, and the message layer says how
 * that reads — the same division of labour the verdict itself follows.
 *
 * **A form may carry no recourse at all, and one deliberately does.** MariaDB writes strings into its
 * JSON without escaping them, so its JSON output is not even valid JSON, and every other form it
 * prints inherits the same problem. There is no better form of that engine's to name, and naming one
 * anyway would be this product making a false statement about somebody else's engine.
 *
 * @param recourse the option of the same engine's that produces a plan this product reads, or `null`
 *   where this product knows of none. See [PlanRecourse].
 */
enum class PlanRefusedForm(val recourse: PlanRecourse?) {

    /**
     * **PostgreSQL's text format, on one of the rows it prints a raw unquoted name in.**
     *
     * See [TEXT_RAW_NAME_ROWS] for the closed list and for why each of them is unrecoverable from
     * the text.
     */
    POSTGRES_TEXT_RAW_NAME_ROW(PlanRecourse.POSTGRES_FORMAT_JSON),

    /**
     * **MySQL's `TREE` output** — which is also what `EXPLAIN ANALYZE` prints.
     *
     * Aliases and index names are **appended raw** into the rendered line, with no delimiter of any
     * kind around them, so a name a user chose can forge a line that reads as well-formed. Nothing
     * can recover the boundary afterwards, and `EXPLAIN FORMAT=JSON` escapes every one of them.
     */
    MYSQL_TREE(PlanRecourse.MYSQL_FORMAT_JSON),

    /**
     * **MySQL's tabular output** — the traditional column table, which is **what `EXPLAIN` with no
     * format clause prints**, and the hypergraph optimizer's tabular form, which prints the same
     * columns.
     *
     * Names are written into the cells unquoted and the cells are separated by a bare `|`, so a name
     * carrying one is indistinguishable from the separator. **This is the product's least comfortable
     * refusal**, because it is the output a MySQL user gets without asking for anything — which is
     * exactly why it has a sentence of its own rather than sharing [MYSQL_TREE]'s.
     */
    MYSQL_TABULAR(PlanRecourse.MYSQL_FORMAT_JSON),

    /**
     * **MariaDB, in every form it prints** — and the one form with **no recourse, deliberately**.
     *
     * Its JSON writer does not escape strings at all, so the document it produces is not valid JSON
     * and a value carrying a quote runs into its siblings; its tabular forms carry the same unquoted
     * names MySQL's do. There is no output of MariaDB's that this product knows would work, so the
     * message names none. See [PlanRecourse] for why an invented one would be worse than silence.
     */
    MARIADB(null),
}

/**
 * **What the user can do instead** — an enumeration, never a sentence.
 *
 * A constant rather than a string for the reason [PlanReading] carries no `String` at all: the engine
 * decides *that* there is a better option and *which*, and the message layer decides how that reads.
 * A rendered sentence here would be the product's words in the engine, and a type that could hold one
 * would be a type that could hold a line of the user's plan.
 *
 * **One row per engine, because the option is spelled per engine.** PostgreSQL's is written
 * `EXPLAIN (FORMAT JSON)` and MySQL's `EXPLAIN FORMAT=JSON`, and a single row rendered one way would
 * hand half the users a statement their own engine rejects.
 */
enum class PlanRecourse {

    /**
     * **Re-run the same `EXPLAIN` with `FORMAT JSON`.**
     *
     * The text format prints a small, closed set of rows with raw unquoted names in them; the same
     * plan as JSON escapes every one of them. The recourse is exact rather than general: it is not
     * *try something else*, it is *this option of your engine's produces a plan this product reads*.
     */
    POSTGRES_FORMAT_JSON,

    /**
     * **Re-run the same `EXPLAIN` with `FORMAT=JSON`.**
     *
     * MySQL's JSON writer escapes every string it prints, which is the one thing its `TREE` and
     * tabular outputs do not do — so the same plan asked for as JSON carries the delimiters the
     * others threw away.
     */
    MYSQL_FORMAT_JSON,
}

/**
 * **Whether a text opens PostgreSQL's default text plan** — a fixed node label at offset zero, with
 * the cost parenthetical the default output always prints.
 *
 * Two literals rather than one, and each closes what the other cannot. The label alone would take
 * `Sort out the merchant ledger` for a plan; the cost alone would take any line that mentions one.
 * Anchoring at offset zero is what refuses the two pastes a user actually makes by mistake — a
 * subtree from the middle, which begins with indentation, and the query above the plan.
 *
 * It also refuses `EXPLAIN (COSTS OFF)`, whose first line carries no cost parenthetical, by the same
 * rule rather than by a special case.
 */
internal fun opensATextPlan(text: String): Boolean {
    val head = linesOf(text).firstOrNull() ?: return false
    return labelOf(head.text) != null && COST in head.text
}

/**
 * **Everything the text format's reader reports** — the declarations, the structure and the fields,
 * over a text whose every line has first been shown to be one this reader recognises.
 *
 * Three passes, and the first two are one argument. A plan prints `CTE Scan on recent` above the
 * `CTE recent` that declares `recent` as readily as below it, and a name that keyed as an
 * invocation-wide relation where it was printed first and as the plan's own where it was printed
 * second would be identity decided by print order — two placeholders for one thing, in the output the
 * user reads. So the structure is read twice against the same set and **the first reader's
 * occurrences are thrown away**, because the second reader makes them again with every declaration in
 * hand.
 */
internal fun textOccurrencesIn(text: String): List<PlanOccurrence> {
    val lines = linesOf(text)

    // **The line closure, before anything is read.** A line this reader does not recognise refuses
    // the input, so no later pass is ever looking at a line nothing classified. See [assertRecognised].
    lines.forEach(::assertRecognised)

    val declared = mutableSetOf<String>()
    val declarations = PlanTextReader(PlanSymbols(declared, POSTGRES))
    lines.forEach(declarations::readStructure)

    val symbols = PlanSymbols(declared, POSTGRES)
    val reader = PlanTextReader(symbols)
    lines.forEach(reader::readStructure)
    lines.forEach(reader::readFields)

    return symbols.occurrences
}

/**
 * **The line closure: every line of a text plan is one of the shapes this reader knows, and any
 * other line is *not a readable plan*.**
 *
 * It is the field closure written for a format whose structure is indentation rather than nesting.
 * A structured document has entries, and an entry the inventory lacks refuses; a text plan has lines,
 * and the same reasoning reaches every one of them. Nothing says what an unrecognised line holds, and
 * **reading past one is how a value leaves in the part nobody looked at** — which is exactly what a
 * string literal carrying a newline used to do, its continuation line being neither a node nor a
 * field and so passed through unread.
 *
 * The one exception is the enumerated raw-name row, which is refused **with a recourse** because the
 * engine offers a form where the same row is recoverable. It is asked first, because those rows are
 * recognised by their head rather than by parsing as anything.
 */
private fun assertRecognised(line: PlanLine) {
    if (line.text.isBlank()) return

    if (TEXT_RAW_NAME_ROWS.any { line.body.startsWith(it) }) {
        throw PlanRefusal(PlanReading.Refused(PlanRefusedForm.POSTGRES_TEXT_RAW_NAME_ROW))
    }

    val field = line.field
    if (field != null) {
        if (treatmentOf(field.label) == null) throw PlanRefusal(PlanReading.Unreadable)
        return
    }

    if (labelOf(line.body) != null) return

    val body = line.body.trim()
    if (CTE_HEADER.matches(body) || SUBPLAN_HEADER.matches(body)) return
    throw PlanRefusal(PlanReading.Unreadable)
}

/**
 * The treatment a text field label takes, or `null` where the inventory does not hold it.
 *
 * The label is normalised first, because the text format numbers one of its own rows: a parallel
 * plan prints `Worker 0:`, `Worker 1:` and so on, which are one inventory row wearing a counter the
 * engine put there.
 */
internal fun treatmentOf(label: String): PlanTreatment? {
    POSTGRES_TEXT_FIELDS[label]?.let { return it }
    if (label.substringAfterLast(' ').toIntOrNull() == null) return null
    return POSTGRES_TEXT_FIELDS[label.substringBeforeLast(' ')]
}

/**
 * **The reading of one text plan** — the names it declares, and the occurrences it produces.
 *
 * A class rather than a run of functions because the symbols being filled travel through every rule
 * below. It also makes the two structure passes [textOccurrencesIn] runs visible as what they are:
 * two readers over one set of declarations, of which only the second keeps its work.
 */
private class PlanTextReader(private val symbols: PlanSymbols) {

    /**
     * **A node line, and the names printed at fixed positions on it** — and the CTE headers, which
     * are the other thing a plan's structure declares.
     *
     * Everything this reads is positional: what follows `using` is an index, what follows `on` is a
     * relation, and what follows that is the alias the engine printed for it. Nothing is decided
     * from the shape of a word, which is what keeps the parenthetical at the end of the line — the
     * whole reason a plan is pasted — out of reach of every rule here.
     */
    fun readStructure(line: PlanLine) {
        val field = line.field
        if (field != null) {
            // `Subplan Name: CTE recent` names a CTE on a field line, which is the one declaration
            // that does not arrive on a line of its own.
            if (treatmentOf(field.label) == PlanTreatment.Declared) {
                symbols.declareWritten(
                    PlanSlot(line.text, line.start + field.from, line.start + line.text.length, line.start).trimmed(),
                )
            }
            return
        }

        val label = labelOf(line.body)
        if (label == null) {
            // `CTE recent`, sitting above the subtree that computes it — or `SubPlan 1`, which is a
            // label the planner invented and declares nothing.
            symbols.declareWritten(
                PlanSlot(line.text, line.start + line.bodyAt, line.start + line.text.length, line.start).trimmed(),
            )
            return
        }

        // **The head alone is scanned**, never the parenthetical behind it — which is the whole
        // reason a plan is pasted, and is out of reach of every rule here by not being read at all.
        val head = line.body.substringBefore(TAIL)
        val from = line.start + line.bodyAt
        val tokens = scanOf(PlanSlot(line.text, from, from + head.length, line.start)).tokens

        var at = label.length
        val using = head.indexOf(USING, at)
        if (using >= 0) at = symbols.readQualified(tokens, from + using + USING.length, SymbolRole.INDEX) - from

        // **Where the relation begins, which is one of two places.** `Seq Scan on visits` carries the
        // keyword in the middle of the line; `Insert on visits`, `Update on`, `Delete on` and
        // `Merge on` carry it **inside the node's own label**, and a reader that went looking for a
        // second one would walk past the relation and leave a table name on the clipboard. The
        // suffix is ` on` rather than `on` so that `Recursive Union` is not read as one of them.
        val relation = if (label.endsWith(ON.trimEnd())) {
            label.length
        } else {
            head.indexOf(ON, at).takeIf { it >= 0 }?.plus(ON.length)
        }
        if (relation == null) return

        // **`Bitmap Index Scan on …` names an index where every other node names a relation**, and it
        // is the one node whose `on` means something else. Reading it as a relation would put a
        // `table` placeholder on an access path and assert a rowset that is not there.
        val scanned = if (label == BITMAP_INDEX_SCAN) SymbolRole.INDEX else SymbolRole.TABLE
        val after = symbols.readQualified(tokens, from + relation, scanned)

        // The alias, where the engine printed one — `Seq Scan on visits v`. It is a name the **plan**
        // declares: two plans each calling something `v` are not talking about one thing, and
        // `visits_1` is an alias this engine invented rather than a relation anybody named.
        val alias = nameAt(tokens, after) ?: return
        symbols.declare(tokens[alias])
    }

    /**
     * **One field line, handed to the treatment its label names** — the text format's half of the
     * field inventory, read out of [POSTGRES_TEXT_FIELDS].
     *
     * There is **no default arm**, and that is the change the field closure made: a label this
     * inventory does not hold has already refused the whole input in [assertRecognised], so nothing
     * here has to decide what to do with a field nobody typed.
     *
     * Three of the labels in the inventory — the echoed query, the parameters and the query
     * identifier — are printed by `EXPLAIN (VERBOSE)` and by `auto_explain` **above** the tree rather
     * than inside it, where this reader's anchored recognition does not yet admit them. They are
     * inventory rows all the same: a field is treated by what it is wherever it is printed.
     */
    fun readFields(line: PlanLine) {
        val field = line.field ?: return
        val treatment = treatmentOf(field.label) ?: throw PlanRefusal(PlanReading.Unreadable)

        // A declaration is read whole by `readStructure`, before anything was keyed against it —
        // so reading it again here would report one name twice and splice over its own placeholder.
        if (treatment == PlanTreatment.Declared) return

        val slot = PlanSlot(line.text, line.start + field.from, line.start + line.text.length, line.start)
        if (!symbols.readSlot(slot.trimmed(), treatment)) throw PlanRefusal(PlanReading.Unreadable)
    }
}

/** One line of the input, and the two readings of it every rule above asks for. */
private class PlanLine(line: PlanTextLine) {

    val text: String = line.text

    val start: Int = line.start

    /**
     * Where the node body begins — the indentation and the branch arrow taken off, and the
     * `Parallel` a worker's node is printed with, which is a modifier rather than a node.
     */
    val bodyAt: Int = bodyIn(text)

    val body: String = text.substring(bodyAt)

    /**
     * The `Label: value` this line is, or `null` for a node line.
     *
     * A node line has no colon before its parenthetical, and a field label is letters and spaces —
     * so the two are told apart without either having to know what the other looks like. It matters
     * that this is asked **first**: `Sort Key: v.created_at` starts with a node label and is a field,
     * and reading it as a node would walk past every name on it.
     */
    val field: PlanField? = fieldIn(body, bodyAt)
}

/** A field line's label, and where its value starts in the line. See [PlanLine.field]. */
private class PlanField(val label: String, val from: Int)

/**
 * **The node labels a plan line can begin with** — the fixed literals recognition is anchored on.
 *
 * Fixed literals rather than a pattern, because a pattern is what a scan for something
 * `EXPLAIN`-looking would be. A label this list is missing now refuses the plan carrying it, under
 * the same closure every field is read under; a pattern loose enough never to miss one would accept
 * prose.
 */
private val NODE_LABELS: List<String> = listOf(
    "Aggregate", "Append", "Bitmap Heap Scan", "Bitmap Index Scan", "BitmapAnd", "BitmapOr",
    "CTE Scan", "Custom Scan", "Delete on", "Foreign Scan", "Function Scan", "Gather Merge",
    "Gather", "GroupAggregate", "Group", "Hash Join", "HashAggregate", "HashSetOp", "Hash",
    "Incremental Sort", "Index Only Scan", "Index Scan", "Insert on", "Limit", "LockRows",
    "Materialize", "Memoize", "Merge Append", "Merge Join", "Merge on", "MixedAggregate",
    "Named Tuplestore Scan", "Nested Loop", "ProjectSet", "Recursive Union", "Result", "Sample Scan",
    "Seq Scan", "SetOp", "Sort", "Subquery Scan", "Table Function Scan", "Tid Range Scan",
    "Tid Scan", "Unique", "Update on", "Values Scan", "WindowAgg", "WorkTable Scan",
)

/** The one node label whose `on` names an access path rather than a rowset. See [PlanTextReader]. */
private const val BITMAP_INDEX_SCAN = "Bitmap Index Scan"

/** The cost parenthetical, as the default output spaces it. See [opensATextPlan]. */
private const val COST = "  (cost="

/** What an index name follows, spaced as a plan prints it. */
private const val USING = " using "

/**
 * What a relation name follows — with its spaces, so that a column called `on` cannot be mistaken
 * for the keyword, and so that a label ending in ` on` can be told from one merely ending in `on`.
 */
private const val ON = " on "

/** Where a node line's parenthetical begins, which is where the names on it stop. */
private const val TAIL = "  ("

/** The modifier a worker's node is printed with, which is not a node label. */
private const val PARALLEL = "Parallel "

/** The branch arrow, which is layout rather than language. */
private const val ARROW = "->"

/**
 * The label this line's node is, or `null` where it is not a node line.
 *
 * The character after the label has to be a non-letter, so `Sort` matches `Sort  (cost=…)` and not
 * `Sortition`; the longer labels are matched as they are written, so `Hash Join` and `Hash` are told
 * apart by which of them the line actually begins with.
 */
private fun labelOf(body: String): String? = NODE_LABELS.firstOrNull { label ->
    body.startsWith(label) && (body.length == label.length || !body[label.length].isLetter())
}

/**
 * Where the node body begins in [line] — past the indentation, past the branch arrow, and past the
 * `Parallel` that marks a worker's copy of a node. All three are layout, and none of them is a name.
 */
private fun bodyIn(line: String): Int {
    var at = line.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: line.length
    if (line.startsWith(ARROW, at)) {
        at += ARROW.length
        while (at < line.length && line[at].isWhitespace()) at++
    }
    if (line.startsWith(PARALLEL, at)) at += PARALLEL.length
    return at
}

/** The `Label: value` shape, as [PlanLine.field] reads it. */
private fun fieldIn(body: String, bodyAt: Int): PlanField? {
    val colon = body.indexOf(':').takeIf { it > 0 } ?: return null
    if (body.startsWith(CAST, colon)) return null

    val label = body.substring(0, colon)
    if (!label.all { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '/' }) return null
    return PlanField(label, bodyAt + colon + 1)
}

/** The lines of [text], each read the two ways every rule above asks for. See [linesIn]. */
private fun linesOf(text: String): List<PlanLine> = linesIn(text).map(::PlanLine)
