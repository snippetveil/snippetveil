package com.snippetveil.core

/**
 * **SQL Server's formats** — the two this product reads, the two it recognises in order to refuse
 * them well, and the one it expects to refuse once a capture exists.
 *
 * ### This engine has no admitted client frame, and that is worth saying out loud
 *
 * Every other engine here arrives wrapped in something a client drew: `psql`'s aligned table, the
 * `mysql` client's vertical banner. **SQL Server's plans arrive with no frame at all.** Showplan XML
 * is a complete document copied out of a result grid, and the `SHOWPLAN_TEXT` rowset is the grid's
 * own cells with nothing around them. So [framingOf] finds nothing to peel and there is nothing to
 * add — the absence is a fact about this engine rather than an omission here.
 *
 * ### What is admitted, and what the admission rests on
 *
 *  - **Showplan XML** is the primary form and the one a user copies from the grid. Its predicate
 *    content is **structural** — a column arrives as `Table` and `Column` attributes rather than as a
 *    rendered `t.c` — so the reader is told where each name is rather than having to find one.
 *  - **`SET SHOWPLAN_TEXT ON`**, as **the plan rowset only**. Every name in a row is bracketed with
 *    the closing bracket doubled, which is what makes a drawn tree readable at all.
 *
 * ### What refuses, and why each refusal is its own sentence
 *
 *  - **The statement echo is a transcript.** `SHOWPLAN_TEXT` returns two rowsets per statement, and
 *    the first is the statement the user typed. An input must therefore **begin at the plan rowset**,
 *    so the natural *statement plus plan* paste refuses — the same line this product draws between a
 *    client's chrome and a client's transcript everywhere else. See [PlanFraming].
 *  - **`SHOWPLAN_ALL` and `STATISTICS PROFILE`** print the statement **in the first row of the plan
 *    rowset itself**, in a grid whose tab and newline separators are the client's with nothing
 *    escaping them — so the statement's own tabs and newlines forge cells and rows, and there is no
 *    plan-only paste to anchor at. They are refused with **different recourses**, because the option
 *    that replaces each is a different statement.
 *  - **The results-to-text client mode** truncates every column at a fixed width, which cuts
 *    mid-bracket and destroys the soundness the whole admission rests on. It has no predicate here:
 *    it awaits a capture and falls to the general refusal until one exists.
 */
internal val SQLSERVER_FORMATS: List<PlanFormat> = listOf(
    PlanFormat("sqlserver-showplan-xml", ::opensShowplanXml) {
        structuredOccurrencesIn(
            attributedXmlDocumentIn(it, SHOWPLAN_ROOT)?.let(::listOf),
            SQLSERVER_SHOWPLAN_FIELDS,
            SQLSERVER,
            AS_WRITTEN,
            XML_QUOTE,
        )
    },
    PlanFormat("sqlserver-showplan-text", ::opensShowplanText, ::showplanTextOccurrencesIn),
    refuses("sqlserver-showplan-all", ::opensShowplanAll, PlanRefusedForm.SQLSERVER_SHOWPLAN_ALL),
    refuses(
        "sqlserver-statistics-profile",
        ::opensStatisticsProfile,
        PlanRefusedForm.SQLSERVER_STATISTICS_PROFILE,
    ),
)

/** **Whether this opens a Showplan XML document** — the writer's own root element, at the head. */
internal fun opensShowplanXml(text: String): Boolean = opensAnElement(text, SHOWPLAN_ROOT)

/**
 * **Whether this opens a `SHOWPLAN_TEXT` plan rowset** — which is the rowset's header above a node
 * row, or a node row on its own.
 *
 * Both are admitted because both are what a user actually copies: the grid's *copy with headers*
 * gives the first and its plain copy gives the second. **Neither of them is the statement echo**, and
 * that is the whole of the predicate's work: the row under the header has to be a node row, so a
 * paste that begins at the echoing rowset — header, then the user's own statement — is not
 * recognised at all.
 */
internal fun opensShowplanText(text: String): Boolean {
    val head = headLinesIn(text, 2)
    val first = head.firstOrNull() ?: return false
    if (nodeBodyIn(first) != null) return true
    return first.trim() == PLAN_COLUMN && head.getOrNull(1)?.let { nodeBodyIn(it) != null } == true
}

/** **Whether this opens a `SET SHOWPLAN_ALL ON` rowset**, by its header row cell for cell. */
internal fun opensShowplanAll(text: String): Boolean = opensAGrid(text, SHOWPLAN_ALL_COLUMNS)

/** **Whether this opens a `SET STATISTICS PROFILE ON` rowset**, by its header row cell for cell. */
internal fun opensStatisticsProfile(text: String): Boolean = opensAGrid(text, STATISTICS_PROFILE_COLUMNS)

/**
 * A grid whose header row is exactly [columns], as the client writes one: the cells separated by the
 * tab it copies them with, and nothing else on the line.
 *
 * **Only the first line is read**, so nothing in the body of a paste can reach a predicate — the rule
 * every recogniser in this product is held to.
 */
private fun opensAGrid(text: String, columns: List<String>): Boolean =
    headLinesIn(text, 1).firstOrNull()?.split(CELL)?.map(String::trim) == columns

/**
 * **Everything a `SHOWPLAN_TEXT` plan rowset reports** — the rows read twice against one set of
 * declarations, for the reason every format here reads its structure twice.
 *
 * A plan draws its tree parent-first, so the `AS [v]` that declares an alias is printed on the row
 * *above* the rows that use it — but a seek predicate on a parent row can name an alias a child row
 * declares, and a reader that keyed each name where it met it would hand out two placeholders for
 * one alias. The first pass's occurrences are thrown away.
 */
internal fun showplanTextOccurrencesIn(text: String): List<PlanOccurrence> {
    val rows = planRowsIn(text)

    val declared = mutableSetOf<String>()
    val declarations = PlanSymbols(declared, SQLSERVER)
    rows.forEach { readBracketedExpression(declarations, it) }

    val symbols = PlanSymbols(declared, SQLSERVER)
    rows.forEach { readBracketedExpression(symbols, it) }
    return symbols.occurrences
}

/**
 * **The rows of the plan rowset, and the line closure that says every line of the input is one.**
 *
 * It is [assertRecognised] for a format whose structure is a drawn tree: a line that is not a node
 * row is a line nothing here classified, and reading past one is how a value leaves in the part
 * nobody looked at. Three things a line may be, and no fourth:
 *
 *  - **Blank**, which is nothing.
 *  - **The rowset's header**, and only as the input's **first** line. A second header is a second
 *    rowset, and the row under it in a multi-statement output is the next statement being echoed —
 *    which is a transcript, and refuses.
 *  - **A node row**, whose operator is one this product reads. An operator on neither list is the
 *    general refusal; one of the **two rows this writer prints raw** is refused with the option that
 *    would have escaped them. See [SQLSERVER_REMOTE_OPERATORS].
 *
 * @throws PlanRefusal on anything else, and on an input with no node row in it at all
 */
private fun planRowsIn(text: String): List<PlanSlot> {
    val rows = mutableListOf<PlanSlot>()
    var opening = true

    for (line in linesIn(text)) {
        if (line.text.isBlank()) continue

        val body = nodeBodyIn(line.text)
        if (body == null) {
            if (!opening || line.text.trim() != PLAN_COLUMN) throw PlanRefusal(PlanReading.Unreadable)
            opening = false
            continue
        }
        opening = false

        when (val operator = line.text.substring(body).substringBefore(ARGUMENTS).trim()) {
            in SQLSERVER_REMOTE_OPERATORS ->
                throw PlanRefusal(PlanReading.Refused(PlanRefusedForm.SQLSERVER_SHOWPLAN_TEXT_REMOTE))

            in SQLSERVER_OPERATORS ->
                rows += PlanSlot(line.text, line.start + body, line.start + line.text.length, line.start)

            else -> throw PlanRefusal(PlanReading.Unreadable)
        }
    }

    if (rows.isEmpty()) throw PlanRefusal(PlanReading.Unreadable)
    return rows
}

/**
 * Where a node row's own text begins in [line] — past the spaces and the vertical bars the writer
 * draws the tree with, and past the branch that ends them — or `null` where this is not a node row.
 *
 * The run has to **end in a bar**, so the branch is the writer's own marker rather than two hyphens
 * that happened to be indented: a statement echoing `  --a comment` is not a node row.
 */
internal fun nodeBodyIn(line: String): Int? {
    var at = 0
    while (at < line.length && (line[at] == ' ' || line[at] == BAR)) at++
    if (at == 0 || line[at - 1] != BAR) return null
    if (!line.startsWith(BRANCH, at)) return null
    return at + BRANCH.length
}

/** The root element SQL Server's showplan writer writes, which is what says the document is its. */
internal const val SHOWPLAN_ROOT = "ShowPlanXML"

/** The one column a `SHOWPLAN_TEXT` rowset has, which is also the column the statement echo has. */
private const val PLAN_COLUMN = "StmtText"

/** The bar the writer draws a tree's vertical runs with. */
private const val BAR = '|'

/** What the writer ends a tree branch with, the bar in front of it excluded. */
private const val BRANCH = "--"

/** Where a node row's arguments begin, which is where its operator's name stops. */
private const val ARGUMENTS = '('

/** What the client separates a grid's copied cells with. */
private const val CELL = '\t'

/**
 * **The columns `SET SHOWPLAN_ALL ON` prints**, cell for cell.
 *
 * The refusal is the interesting half rather than the recognition: the names inside these cells are
 * bracket-sound, so it is not the names that make the rowset unreadable. It is that **the first row
 * carries the statement in its `StmtText` cell** — in every capture — while the grid's separators are
 * the client's with nothing escaping them, so the statement's own tabs and newlines forge cells and
 * rows. There is no plan-only paste to anchor at, the way there is for `SHOWPLAN_TEXT`, because here
 * the transcript is *inside* the plan rowset.
 */
private val SHOWPLAN_ALL_COLUMNS: List<String> = listOf(
    "StmtText", "StmtId", "NodeId", "Parent", "PhysicalOp", "LogicalOp", "Argument", "DefinedValues",
    "EstimateRows", "EstimateIO", "EstimateCPU", "AvgRowSize", "TotalSubtreeCost", "OutputList",
    "Warnings", "Type", "Parallel", "EstimateExecutions",
)

/**
 * **The columns `SET STATISTICS PROFILE ON` prints** — the same rowset with what actually ran in
 * front of it.
 *
 * A list of its own rather than a prefix rule, because the two are told apart by what the client
 * copied and the comparison should say so. It is refused for the same reason `SHOWPLAN_ALL` is and
 * **with a different recourse**: the option that produces a readable plan under `SHOWPLAN_ALL` is
 * `SET SHOWPLAN_XML ON`, and the one that keeps this form's actual row counts is
 * `SET STATISTICS XML ON`. One sentence covering both would have to name an option that is wrong for
 * one of them.
 */
private val STATISTICS_PROFILE_COLUMNS: List<String> = listOf("Rows", "Executes") + SHOWPLAN_ALL_COLUMNS
