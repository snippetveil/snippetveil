package com.snippetveil.core

/**
 * **MySQL's and MariaDB's formats** — the two this product reads, the four it recognises in order to
 * refuse them well, and the head literals that tell all six apart.
 *
 * ### A recogniser only picks the message
 *
 * It never decides what is emitted: the clipboard is untouched whichever way a refusal goes. What it
 * decides is **what this product says about somebody else's engine**, and a MariaDB paste read as a
 * broken MySQL would be told to run `EXPLAIN FORMAT=JSON` — a statement that is simply false about
 * the engine the user is actually running. A wrong recourse is a wrong *statement*, so the predicates
 * here are held to the same standard as the parser.
 *
 * ### Fixed literals, anchored at the head
 *
 * > **Nothing here scans the body for something `EXPLAIN`-looking.**
 *
 * Every predicate reads the first line or two and compares them against literals the engine's own
 * writer produces — a column header printed cell for cell, the first keys of a pretty-printed
 * document, the arrow a tree node opens with. A scan for a plan-shaped substring anywhere in the
 * input would let the *content* of a paste decide which engine's message the user is shown, which is
 * the one thing a recogniser must not let it do.
 *
 * ### Where the two engines are genuinely indistinguishable, the answer is the general refusal
 *
 * MySQL's JSON version 1 and MariaDB's JSON **share an opening key**: a plan going straight to its
 * table opens identically in both. Such a text matches two entries of [PLAN_FORMATS], and two is
 * answered exactly the way zero is: *this is not a readable plan*. **Ambiguity never falls to a
 * guess** — see [readingOf].
 */
internal val MYSQL_FORMATS: List<PlanFormat> = listOf(
    readsJson("mysql-json-v2", PlanEngine.MYSQL, ::opensMysqlJsonV2, MYSQL_V2_QUERY_FIELDS),
    readsJson("mysql-json-v1", PlanEngine.MYSQL, ::opensMysqlJsonV1, MYSQL_V1_QUERY_FIELDS),
    // `FORMAT=TREE`, and what `EXPLAIN ANALYZE` prints — one option away, and refused.
    refuses(
        "mysql-tree",
        PlanEngine.MYSQL,
        PlanFormatReach.ONE_FLAG,
        ::opensMysqlTree,
        PlanRefusedForm.MYSQL_TREE,
    ),
    // **This engine's default form, refused outright.** `EXPLAIN` with no format clause prints it,
    // so the public listing cannot say *paste your `EXPLAIN` output* without qualifying it for
    // MySQL. See `PlanRefusedForm.MYSQL_TABULAR` for why that is the least comfortable refusal here.
    refuses(
        "mysql-tabular",
        PlanEngine.MYSQL,
        PlanFormatReach.DEFAULT,
        ::opensMysqlTabular,
        PlanRefusedForm.MYSQL_TABULAR,
    ),
)

/**
 * **MariaDB, in every form it prints** — recognised so that it can be refused **as itself**.
 *
 * Nothing of MariaDB's is read, and no capture would change that: its JSON writer does not escape the
 * strings it writes, so a value carrying a quote ends its own slot and the document that comes out is
 * not valid JSON at all. Every other form it prints carries the same unquoted names its tabular
 * output does.
 *
 * It is recognised all the same, and that is the whole point of the entry. A MariaDB paste that fell
 * through to MySQL's predicates would be refused with MySQL's recourse — telling somebody running
 * MariaDB to run a MySQL command. **The refusal is silent about what to do instead**, because there
 * is nothing this product knows of to say; see [PlanRefusedForm.MARIADB].
 */
internal val MARIADB_FORMATS: List<PlanFormat> = listOf(
    refuses(
        "mariadb-json",
        PlanEngine.MARIADB,
        PlanFormatReach.ONE_FLAG,
        ::opensMariadbJson,
        PlanRefusedForm.MARIADB,
    ),
    // **This engine is refused in every form it prints, its default among them**, which is a
    // different copy fact from MySQL's: there is no option to name, so there is nothing to qualify
    // the sentence with either.
    refuses(
        "mariadb-tabular",
        PlanEngine.MARIADB,
        PlanFormatReach.DEFAULT,
        ::opensMariadbTabular,
        PlanRefusedForm.MARIADB,
    ),
)

/**
 * One of MySQL's JSON formats: the same reader and the same document shape, against [fields].
 *
 * The two differ in their inventory and in nothing else, so the difference is the argument and the
 * rest is one line. A second spelling of the call would be a second place for the document shape,
 * the vocabulary or the escaping to be stated — and a second place is where they come apart.
 */
private fun readsJson(
    name: String,
    engine: PlanEngine,
    recognises: (String) -> Boolean,
    fields: Map<String, PlanTreatment>,
) = reads(name, engine, PlanFormatReach.ONE_FLAG, recognises) {
    structuredOccurrencesIn(jsonObjectIn(it)?.let(::listOf), fields, MYSQL, AS_WRITTEN, JSON_QUOTE)
}

/**
 * **Whether this opens MySQL's JSON version 2** — a document whose first key is `query`, which is the
 * statement the tree below it describes.
 *
 * Version 2 cannot be mistaken for version 1 or for MariaDB's document: both of those open on
 * `query_block`, and a key is compared as a whole rather than looked for.
 */
internal fun opensMysqlJsonV2(text: String): Boolean {
    val head = documentHeadIn(text) ?: return false
    return head.firstOrNull() == "query"
}

/**
 * **Whether this opens MySQL's JSON version 1** — `query_block`, a `select_id`, and then a key this
 * engine's writer prints there.
 *
 * The third key is what carries the work. MySQL and MariaDB both open a plan on
 * `query_block` → `select_id`, so the opening alone says only *one of these two engines*, and
 * choosing between them on anything less than a literal one of them writes and the other does not
 * would be the guess this file exists to avoid. Where the key is one **both** engines write — a plan
 * that goes straight to its table — both predicates accept, and the input falls to the general
 * refusal rather than to either engine's message.
 */
internal fun opensMysqlJsonV1(text: String): Boolean = opensAQueryBlock(text, MYSQL_V1_HEAD_KEYS)

/** **Whether this opens MariaDB's JSON**, by the keys MariaDB's writer prints. See [opensMysqlJsonV1]. */
internal fun opensMariadbJson(text: String): Boolean = opensAQueryBlock(text, MARIADB_HEAD_KEYS)

/** A pretty-printed `query_block` document whose third key is one of [keys]. */
private fun opensAQueryBlock(text: String, keys: Set<String>): Boolean {
    val head = documentHeadIn(text) ?: return false
    return head.size == 3 && head[0] == "query_block" && head[1] == "select_id" && head[2] in keys
}

/**
 * **The keys MySQL's version 1 writer prints first inside a query block.**
 *
 * `cost_info` is the one that settles the engine: MySQL's optimizer reports its cost under that name
 * and MariaDB's does not. The rest are shared with MariaDB deliberately — they are listed because
 * MySQL writes them, and their being on both lists is what makes a plan that opens with one of them
 * *ambiguous* rather than quietly MySQL's.
 */
private val MYSQL_V1_HEAD_KEYS: Set<String> = setOf(
    "cost_info", "table", "nested_loop", "ordering_operation", "grouping_operation",
    "duplicates_removal", "union_result", "message", "optimized_away_subqueries", "windowing",
)

/**
 * **The keys MariaDB's writer prints first inside a query block.**
 *
 * `r_loops`, `r_total_time_ms` and `read_sorted_file` are MariaDB's own — the first two are what
 * `ANALYZE` measures and the third is a node MySQL has no equivalent of — and `cost` is where
 * MariaDB reports what MySQL reports under `cost_info`. The shared keys are here for the reason they
 * are on MySQL's list: so that a plan opening with one of them is recognised as *either*, which is
 * the general refusal.
 */
private val MARIADB_HEAD_KEYS: Set<String> = setOf(
    "cost", "r_loops", "r_total_time_ms", "read_sorted_file", "const_condition", "having_condition",
    "table", "nested_loop", "ordering_operation", "duplicates_removal", "union_result",
)

/**
 * The keys a pretty-printed JSON document opens with — the root's first key, and, where that key
 * opens an object, that object's first two.
 *
 * It reads **lines** rather than parsing, and that is deliberate twice over. A recogniser must be
 * cheap and must decide nothing about content; and MariaDB's document **is not valid JSON**, so a
 * predicate that had to parse it in order to recognise it could never recognise it at all.
 */
private fun documentHeadIn(text: String): List<String>? {
    val lines = headLinesIn(text, HEAD_LINES)
    if (lines.firstOrNull()?.trim() != "{") return null

    val first = keyOn(lines.getOrNull(1)) ?: return null
    if (!lines[1].trimEnd().endsWith("{")) return listOf(first)

    val second = keyOn(lines.getOrNull(2)) ?: return listOf(first)
    val third = keyOn(lines.getOrNull(3)) ?: return listOf(first, second)
    return listOf(first, second, third)
}

/** The key a pretty-printed line opens with — `"label":` — or `null` where the line is not one. */
private fun keyOn(line: String?): String? {
    val body = line?.trimStart() ?: return null
    if (!body.startsWith('"')) return null
    val close = body.indexOf('"', 1).takeIf { it > 0 } ?: return null
    if (body.getOrNull(close + 1) != ':') return null
    return body.substring(1, close)
}

/**
 * **Whether this opens MySQL's `TREE` output** — which is also what `EXPLAIN ANALYZE` prints, and
 * what a plan in an unrecognised client frame is refused as rather than read.
 *
 * A tree plan opens on the arrow its root node is printed with. That is a fixed literal at the head,
 * and it is the whole predicate: nothing looks for a cost parenthetical further along, because a
 * tree line's parenthetical is the part a plan is pasted for and no rule here goes near it.
 */
internal fun opensMysqlTree(text: String): Boolean {
    val rows = clientRowsIn(text)
    val first = rows.firstOrNull() ?: return false
    if (first.size == 1 && first[0].startsWith(TREE_ARROW)) return true

    // The same plan inside the client's bordered table, whose one column is headed `EXPLAIN`. The
    // frame is **not peeled** — that awaits a capture — but it does not hide the head literal either,
    // and the asymmetry with a bordered JSON plan is the point rather than an oversight: **looking
    // past a frame to choose a sentence costs nothing, because nothing is emitted either way, while
    // peeling one in order to emit needs a capture.** So a framed tree plan is refused in its own
    // words, and a framed JSON plan is refused as a text this product cannot place.
    return first == listOf(EXPLAIN_COLUMN) && rows.getOrNull(1)?.singleOrNull()?.startsWith(TREE_ARROW) == true
}

/**
 * **Whether this opens MySQL's tabular output** — the traditional column table, which is what
 * `EXPLAIN` with no format clause prints.
 *
 * This is the predicate behind the product's least comfortable refusal, so it is worth being plain
 * about what it recognises: **the output a MySQL user gets without asking for anything.** The header
 * is compared cell for cell against the columns this engine prints, which is what tells it from
 * MariaDB's — MySQL added `partitions` and `filtered` to the default table and MariaDB did not.
 */
internal fun opensMysqlTabular(text: String): Boolean = opensATable(text, MYSQL_TABULAR_HEADERS)

/** **Whether this opens one of MariaDB's tabular outputs.** See [opensMysqlTabular]. */
internal fun opensMariadbTabular(text: String): Boolean = opensATable(text, MARIADB_TABULAR_HEADERS)

private fun opensATable(text: String, headers: List<List<String>>): Boolean =
    clientRowsIn(text).firstOrNull() in headers

/**
 * The header the warnings overlay prints — the rewritten statement `SHOW WARNINGS` hands back after
 * an `EXPLAIN`.
 *
 * It is on MySQL's list because the overlay is one of the forms that engine prints, and it is on no
 * other: MariaDB prints the same three columns, so a MariaDB overlay takes MySQL's sentence. That is
 * recorded rather than hidden. The overlay says nothing about the plan and names no engine, and the
 * option it points at — ask for the plan as JSON — is the true advice for whichever engine printed
 * it; the near-identical *plan* forms, where the recourse would genuinely be wrong, are told apart by
 * literals of their own.
 */
private val WARNINGS_OVERLAY: List<String> = listOf("Level", "Code", "Message")

/**
 * **The column headers MySQL's tabular family prints**, cell for cell.
 *
 *  - The **traditional** table, which is the default `EXPLAIN`. The hypergraph optimizer's tabular
 *    output prints the same columns, so it is this row rather than one of its own; a capture showing
 *    otherwise is what would add one.
 *  - The **warnings overlay** — the rewritten statement `SHOW WARNINGS` prints after an `EXPLAIN`.
 *    It is byte-identical in MariaDB, so it is on that engine's list too and a paste of one is
 *    therefore ambiguous. **That is the right answer**: the overlay says nothing about which engine
 *    produced it, and a message naming one engine's recourse would be a coin toss printed as advice.
 */
private val MYSQL_TABULAR_HEADERS: List<List<String>> = listOf(
    listOf(
        "id", "select_type", "table", "partitions", "type", "possible_keys", "key", "key_len", "ref",
        "rows", "filtered", "Extra",
    ),
    WARNINGS_OVERLAY,
)

/**
 * **The column headers MariaDB's tabular family prints**, cell for cell — its `EXPLAIN`, its
 * `EXPLAIN EXTENDED` and its `ANALYZE`, none of which carries MySQL's `partitions` column.
 *
 * The vertical form of any of them is a capture this product has not taken: `EXPLAIN …\G` prints one
 * label per line rather than a header row, and the first labels it prints are ones both engines
 * write. It falls to the general refusal until a capture settles how far down the two diverge.
 */
private val MARIADB_TABULAR_HEADERS: List<List<String>> = listOf(
    listOf("id", "select_type", "table", "type", "possible_keys", "key", "key_len", "ref", "rows", "Extra"),
    listOf(
        "id", "select_type", "table", "type", "possible_keys", "key", "key_len", "ref", "rows",
        "filtered", "Extra",
    ),
    listOf(
        "id", "select_type", "table", "type", "possible_keys", "key", "key_len", "ref", "rows",
        "r_rows", "filtered", "r_filtered", "Extra",
    ),
)

/**
 * The first rows of a client's table, with the border rules dropped and each row cut into its cells —
 * or the plain lines, where there is no table at all.
 *
 * **Only the head is read.** The rows past the second are never looked at, so nothing in the body of
 * a paste can reach a predicate.
 */
private fun clientRowsIn(text: String): List<List<String>> =
    headLinesIn(text, HEAD_LINES)
        .filterNot { it.isNotEmpty() && it.all { character -> character == '+' || character == '-' } }
        .map { line ->
            if (line.startsWith(CELL) && line.endsWith(CELL)) {
                line.removePrefix(CELL).removeSuffix(CELL).split(CELL).map(String::trim)
            } else {
                // Left exactly as it was written, so that a head literal is anchored at the head: a
                // subtree pasted out of the middle of a plan arrives indented, and is not one.
                listOf(line)
            }
        }
        .take(2)

/** How many lines a predicate here may look at. See [headLinesIn]. */
private const val HEAD_LINES = 4

/** What a `TREE` plan's root node opens with. */
private const val TREE_ARROW = "-> "

/** What the client heads the one column of a `TREE` or JSON plan with. */
private const val EXPLAIN_COLUMN = "EXPLAIN"

/** What the client separates the cells of a bordered table with. */
private const val CELL = "|"

