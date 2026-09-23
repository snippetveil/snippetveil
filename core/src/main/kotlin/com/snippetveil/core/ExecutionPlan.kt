package com.snippetveil.core

/**
 * **Reads a PostgreSQL `EXPLAIN` text plan, and says what is a name in it** — the engine's third
 * entry point, and the only one with no IDE anywhere behind it.
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
 * ### One format, recognised at the head
 *
 * PostgreSQL's `EXPLAIN` in its **default text output**, and nothing else. Recognition is anchored:
 * the input must **begin at the plan's first line**, and it is tested against fixed literals rather
 * than scanned for something `EXPLAIN`-looking anywhere in the text. A subtree pasted from the middle
 * of a plan begins with indentation and a branch arrow, and a paste carrying the query above the plan
 * begins with the query — so both refuse, which is what [PlanReading.Unreadable] is for.
 *
 * **Two stated limits, because a user meets both.** `psql`'s own table decoration — the `QUERY PLAN`
 * header, the rule of dashes and the `(1 row)` footer — is `psql`'s and not `EXPLAIN`'s, so a paste
 * carrying it does not begin at the plan's first line and refuses. And `EXPLAIN (COSTS OFF)` is not
 * the default output; its first line carries no cost parenthetical, so it refuses too. Both refuse by
 * the same rule rather than by a special case, and the message says what to copy.
 *
 * ### What it reports, and what it never touches
 *
 * Relations, columns, schemas, aliases and indexes — and **nothing numeric**. The costs, the row
 * estimates, the widths and the timings are read straight past: names are taken from positions on a
 * node line, which is why no rule here can reach the parentheticals at all, and the expression fields
 * report identifier-shaped words alone. A plan is pasted *for* its numbers, and an anonymizer that
 * moved one would have destroyed the reason it was sent.
 *
 * A word this file does not recognise is reported as a **column** rather than passed through, which
 * is the fail-closed direction and is stated rather than tidy: over-reporting costs a visible,
 * obviously-anonymized artifact in a line of plan vocabulary, and under-reporting puts the
 * employer's domain on a clipboard. [SQL_WORDS] is what keeps that from eating the plan's own
 * language, and it holds the engine's vocabulary rather than anybody's names.
 */
fun parsePlan(text: String): PlanReading {
    val lines = linesOf(text)
    if (lines.isEmpty() || !opensAPlan(lines.first())) return PlanReading.Unreadable

    val occurrences = mutableListOf<Occurrence>()

    // **Declarations first, references second, in two passes over the same lines.** A plan prints
    // `Filter: (v.status = …)` above the `CTE v` that declares `v` as readily as below it, and a
    // qualifier that keyed as an invocation-wide relation on one plan and as the plan's own alias on
    // the next would be identity decided by print order. The passes are disjoint — a line is a node
    // line or a field line and never both — so nothing is reported twice, and the document order the
    // engine reads is restored by the sort below.
    val declared = mutableSetOf<String>()
    for (line in lines) readStructure(line, declared, occurrences)
    for (line in lines) readFields(line, declared, occurrences)

    return PlanReading.Read(SnippetPlan(text, occurrences.sortedBy { it.start }))
}

/**
 * **What [parsePlan] made of the text**: the finished plan, or the verdict that it is not one.
 *
 * > The engine decides what the text **is**; the message layer decides how that reads.
 *
 * **The verdict carries no `String` field, and that is the mechanism rather than a convention.** With
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
     * **The text is not a plan this engine reads** — the one verdict this format ships.
     *
     * It says nothing about *why*, and there is nothing it could say that the message does not
     * already: the answer is always *copy the whole plan from its first line, without the query*.
     */
    object Unreadable : PlanReading()
}

/**
 * **Whether this is the first line of a plan** — a fixed node label at offset zero, with the cost
 * parenthetical the default output always prints.
 *
 * Two literals rather than one, and each closes what the other cannot. The label alone would take
 * `Sort out the merchant ledger` for a plan; the cost alone would take any line that mentions one.
 * Anchoring at offset zero is what refuses the two pastes a user actually makes by mistake — a
 * subtree from the middle, which begins with indentation, and the query above the plan.
 */
private fun opensAPlan(head: PlanLine): Boolean = labelOf(head.text) != null && COST in head.text

/** The cost parenthetical, as the default output spaces it. See [opensAPlan]. */
private const val COST = "  (cost="

/**
 * **A node line, and the names printed at fixed positions on it** — and the CTE headers, which are
 * the other thing a plan's structure declares.
 *
 * Everything this reads is positional: what follows `using` is an index, what follows `on` is a
 * relation, and what follows that is the alias the engine printed for it. Nothing is decided from
 * the shape of a word, which is what keeps the parenthetical at the end of the line — the whole
 * reason a plan is pasted — out of reach of every rule here.
 *
 * @param declared the names the plan declares, which this pass **fills**: an alias and a CTE name
 *   belong to the plan, and the fields pass reads this to tell a qualifier naming one of them from a
 *   qualifier naming a relation. See [PlanKeys].
 */
private fun readStructure(line: PlanLine, declared: MutableSet<String>, into: MutableList<Occurrence>) {
    val field = line.field
    if (field != null) {
        // `Subplan Name: CTE recent` names a CTE on a field line, which is the one declaration that
        // does not arrive on a line of its own.
        if (field.label == SUBPLAN_NAME) readCteHeader(line, field.from, declared, into)
        return
    }

    val label = labelOf(line.body)
    if (label == null) {
        // `CTE recent`, sitting above the subtree that computes it.
        readCteHeader(line, line.bodyAt, declared, into)
        return
    }

    val head = line.body.substringBefore(TAIL)
    var at = label.length

    val using = head.indexOf(USING, at)
    if (using >= 0) {
        at = readQualified(line, head, line.bodyAt, using + USING.length, SymbolRole.INDEX, declared, into)
    }

    val on = head.indexOf(ON, at)
    if (on < 0) return

    // **`Bitmap Index Scan on …` names an index where every other node names a relation**, and it is
    // the one node whose `on` means something else. Reading it as a relation would put a `table`
    // placeholder on an access path and assert a rowset that is not there.
    val scanned = if (label == BITMAP_INDEX_SCAN) SymbolRole.INDEX else SymbolRole.TABLE
    at = readQualified(line, head, line.bodyAt, on + ON.length, scanned, declared, into)

    // The alias, where the engine printed one — `Seq Scan on visits v`. It is a name the **plan**
    // declares: two plans each calling something `v` are not talking about one thing, and `visits_1`
    // is an alias this engine invented rather than a relation anybody named.
    val alias = tokenAt(head, skippingSpaces(head, at), line.bodyAt) ?: return
    declared += alias.spelling
    into += alias.anonymized(line, SymbolRole.TABLE, PlanKeys.declared(SymbolRole.TABLE, alias.spelling))
}

/** `CTE recent` — the name after the keyword, declared by the plan that computes it. */
private fun readCteHeader(line: PlanLine, from: Int, declared: MutableSet<String>, into: MutableList<Occurrence>) {
    val at = skippingSpaces(line.text, from)
    if (!line.text.startsWith(CTE, at)) return

    val name = tokenAt(line.text, at + CTE.length, 0) ?: return
    declared += name.spelling
    into += name.anonymized(line, SymbolRole.TABLE, PlanKeys.declared(SymbolRole.TABLE, name.spelling))
}

/**
 * **A field line's value, read as an expression** — `Filter: (v.status = 'open'::text)`.
 *
 * Every field is read except the ones [QUIET_FIELDS] names, and that direction is the decision: a
 * field this file has never heard of is read for names rather than passed over, so a plan option
 * nobody here anticipated cannot carry a column name out unnoticed. What it costs is a metric word
 * occasionally replaced in a line of counters, which is visible in the pane the user is looking at.
 */
private fun readFields(line: PlanLine, declared: Set<String>, into: MutableList<Occurrence>) {
    val field = line.field ?: return
    if (field.label == SUBPLAN_NAME || field.label in QUIET_FIELDS) return
    readExpression(line, field.from, declared, into)
}

/**
 * The dotted chain starting at [at] — `billing.invoices`, `v`, `"Customers"` — with [last] the kind
 * of its final segment and every segment before it a schema.
 *
 * **A qualified name is several symbols and not one**, so each segment is keyed and replaced on its
 * own: `billing.invoices` renders `schema1.table2`, and the reader keeps the fact that the two are
 * different things in different namespaces.
 *
 * @return where the chain ended in [within], so the caller can go on reading the line from there
 */
private fun readQualified(
    line: PlanLine,
    within: String,
    offset: Int,
    at: Int,
    last: SymbolRole,
    declared: Set<String>,
    into: MutableList<Occurrence>,
): Int {
    val chain = chainAt(within, at, offset)
    if (chain.isEmpty()) return at

    for ((index, token) in chain.withIndex()) {
        val kind = if (index == chain.lastIndex) last else SymbolRole.SCHEMA
        into += token.anonymized(line, kind, keyOf(kind, token.spelling, declared))
    }
    return chain.last().after
}

/**
 * **Which of the two key shapes a name takes** — the plan's own, or the invocation's.
 *
 * A name the plan declared is the plan's however it is written afterwards, which is what makes the
 * alias in `Seq Scan on visits v` and the `v` in `Filter: (v.id = 1)` one symbol. Everything else
 * keys on its spelling across the invocation, exactly as written. See [PlanKeys].
 */
private fun keyOf(kind: SymbolRole, spelling: String, declared: Set<String>): String =
    if (kind == SymbolRole.TABLE && spelling in declared) {
        PlanKeys.declared(kind, spelling)
    } else {
        PlanKeys.named(kind, spelling)
    }

/**
 * **Every name in one expression field, and every word that is not one.**
 *
 * The walk is deliberately small, because everything it could do instead is a guess at a grammar
 * nothing here parses:
 *
 *  - **A single-quoted literal is stepped over**, content untouched. It is a value the planner
 *    printed, and rewriting one would change what the plan says the engine did.
 *  - **A word after `::` is a type name**, and a word this engine's own vocabulary knows is the
 *    engine's. Both are [PlanDisposition.Preserve] — slotted, and emitted as written, which is what
 *    the `preserved` half of the counts counts.
 *  - **A dotted chain is read whole**: `v.created_at` is a qualifier and a column, `billing.t.c` is a
 *    schema, a relation and a column. The last segment is a column **by position**, so a column
 *    genuinely called `text` keeps its kind rather than being mistaken for the type.
 *  - **Everything else identifier-shaped is a column**, which is the fail-closed arm argued on
 *    [parsePlan].
 *
 * A word immediately after a digit is skipped, because it is a unit rather than a word — `25kB` is
 * one token to a reader and two to a scanner.
 */
private fun readExpression(line: PlanLine, from: Int, declared: Set<String>, into: MutableList<Occurrence>) {
    val text = line.text
    var at = from
    while (at < text.length) {
        val character = text[at]
        when {
            character == '\'' -> at = pastQuote(text, at)

            character == '"' || opensAWord(character) -> {
                if (opensAWord(character) && at > 0 && text[at - 1].isDigit()) {
                    at = endOfWord(text, at)
                    continue
                }
                val chain = chainAt(text, at, 0)
                if (chain.isEmpty()) {
                    at++
                    continue
                }
                readChain(line, chain, declared, into)
                at = chain.last().after
            }

            else -> at++
        }
    }
}

/** What becomes of one dotted chain met in an expression. See [readExpression]. */
private fun readChain(
    line: PlanLine,
    chain: List<PlanToken>,
    declared: Set<String>,
    into: MutableList<Occurrence>,
) {
    if (chain.size == 1) {
        val token = chain.single()
        into += if (token.isTheEnginesOwnWord(line)) {
            PlanOccurrence(line.start + token.start, line.start + token.end, PlanDisposition.Preserve)
        } else {
            token.anonymized(line, SymbolRole.COLUMN, PlanKeys.named(SymbolRole.COLUMN, token.spelling))
        }
        return
    }

    for ((index, token) in chain.withIndex()) {
        val kind = when (index) {
            chain.lastIndex -> SymbolRole.COLUMN
            chain.lastIndex - 1 -> SymbolRole.TABLE
            else -> SymbolRole.SCHEMA
        }
        into += token.anonymized(line, kind, keyOf(kind, token.spelling, declared))
    }
}

/** One line of the input, and the two readings of it every rule above asks for. */
private class PlanLine(val text: String, val start: Int) {

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
 * One identifier-shaped token, bare or delimited — with every offset **relative to the line**, and
 * [after] relative to whatever string it was read out of.
 *
 * @param spelling the token exactly as written, delimiters included. It is what a key is made of, so
 *   `"Customers"` and `customers` are two symbols and neither is folded into the other.
 */
private class PlanToken(
    val start: Int,
    val end: Int,
    val nameStart: Int,
    val nameEnd: Int,
    val spelling: String,
    val after: Int,
) {

    /** A token replaced by a placeholder of [kind], written inside its delimiters. */
    fun anonymized(line: PlanLine, kind: SymbolRole, key: String) = PlanOccurrence(
        line.start + start,
        line.start + end,
        PlanDisposition.Anonymize(kind, key),
        line.start + nameStart,
        line.start + nameEnd,
    )

    /** Whether this is a word of the engine's own — a type after a cast, or one [SQL_WORDS] holds. */
    fun isTheEnginesOwnWord(line: PlanLine): Boolean =
        line.text.startsWith(CAST, start - CAST.length) ||
            (nameStart == start && spelling.uppercase() in SQL_WORDS)
}

private const val CAST = "::"

/**
 * **The engine's own vocabulary** — the words a plan's expressions are written in, which are the
 * engine's and never the user's.
 *
 * It is a list of words rather than a lexer because there is no lexer here: text assigned the slot,
 * and this is the whole of what text can say about a word it did not have to resolve. **It holds no
 * name**: every entry is SQL's or PostgreSQL's, so the list cannot grow into the plaintext glossary
 * of somebody's schema that a preserve-by-spelling list becomes.
 *
 * The stated cost: a column genuinely called `count` is preserved where it stands alone. It carries
 * no domain — that is the entry test for this list — and a column met in a dotted chain keeps its
 * kind regardless, because position outranks spelling there.
 */
private val SQL_WORDS: Set<String> = setOf(
    // Operators, predicates and the shapes an expression is built from.
    "AND", "OR", "NOT", "NULL", "IS", "IN", "EXISTS", "LIKE", "ILIKE", "SIMILAR", "BETWEEN", "CASE",
    "WHEN", "THEN", "ELSE", "END", "ANY", "ALL", "SOME", "AS", "ASC", "DESC", "NULLS", "FIRST",
    "LAST", "DISTINCT", "ON", "USING", "COLLATE", "ARRAY", "ROW", "TRUE", "FALSE", "UNKNOWN", "CAST",
    "INTERVAL", "AT", "TIME", "ZONE", "FOR", "FROM", "WITH", "WITHOUT", "RECURSIVE", "NEVER",
    "EXECUTED", "LOOPS", "ROWS", "WIDTH", "COST", "ACTUAL",
    // Types, which is what follows a cast.
    "BOOL", "BOOLEAN", "BYTEA", "CHAR", "BPCHAR", "VARCHAR", "TEXT", "NAME", "INT", "INT2", "INT4",
    "INT8", "SMALLINT", "INTEGER", "BIGINT", "NUMERIC", "DECIMAL", "REAL", "FLOAT", "FLOAT4",
    "FLOAT8", "DOUBLE", "PRECISION", "MONEY", "DATE", "TIMESTAMP", "TIMESTAMPTZ", "TIMETZ", "UUID",
    "JSON", "JSONB", "XML", "INET", "CIDR", "MACADDR", "OID", "REGCLASS", "RECORD", "SERIAL",
    "BIGSERIAL", "TSVECTOR", "TSQUERY", "POINT", "LINE", "BOX", "CIRCLE", "POLYGON",
    // The functions an engine prints into a plan of its own accord.
    "COUNT", "SUM", "MIN", "MAX", "AVG", "COALESCE", "NULLIF", "GREATEST", "LEAST", "ABS", "ROUND",
    "CEIL", "FLOOR", "LOWER", "UPPER", "INITCAP", "LENGTH", "SUBSTRING", "SUBSTR", "POSITION",
    "OVERLAY", "TRIM", "BTRIM", "LTRIM", "RTRIM", "REPLACE", "SPLIT_PART", "CONCAT", "FORMAT",
    "NOW", "CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP", "LOCALTIME", "LOCALTIMESTAMP",
    "AGE", "DATE_PART", "DATE_TRUNC", "EXTRACT", "TO_CHAR", "TO_DATE", "TO_NUMBER", "TO_TIMESTAMP",
    "GENERATE_SERIES", "RANDOM", "NEXTVAL", "CURRVAL", "ROW_NUMBER", "RANK", "DENSE_RANK",
    "LAG", "LEAD", "OVER", "PARTITION", "BY", "ARRAY_AGG", "STRING_AGG", "JSONB_AGG", "UNNEST",
)

/**
 * **The fields that hold counters rather than names** — the measurements a plan is pasted *for*, and
 * the one place this file passes a line over rather than reading it.
 *
 * A list of the quiet fields rather than a list of the loud ones, because the direction of the
 * mistake differs: a field missing from this list is read for names and its counters come out
 * anonymized, which is visible and ugly; a *name-bearing* field missing from a list of loud fields
 * would leave a column name on the clipboard, which is invisible and is the whole failure.
 */
private val QUIET_FIELDS: Set<String> = setOf(
    "Planning Time", "Execution Time", "Planning", "Execution", "Buffers", "I/O Timings",
    "Sort Method", "Sort Space Used", "Sort Space Type", "Workers Planned", "Workers Launched",
    "Worker", "Heap Blocks", "Exact Heap Blocks", "Lossy Heap Blocks", "Buckets", "Batches",
    "Memory Usage", "Peak Memory Usage", "Disk Usage", "Rows Removed by Filter",
    "Rows Removed by Index Recheck", "Rows Removed by Join Filter", "Rows Removed by Conflict Filter",
    "Functions", "Options", "Timing", "JIT", "Settings", "Full-sort Groups", "Pre-sorted Groups",
    "Hits", "Misses", "Evictions", "Overflows", "Storage", "Tuples Inserted", "Conflicting Tuples",
)

/**
 * **The node labels a plan line can begin with** — the fixed literals recognition is anchored on.
 *
 * Fixed literals rather than a pattern, because a pattern is what a scan for something
 * `EXPLAIN`-looking would be. A label this list is missing costs the names on that one line, which
 * the fields around it usually carry anyway; a pattern loose enough never to miss one would accept
 * prose.
 */
private val NODE_LABELS: List<String> = listOf(
    "Aggregate", "Append", "Bitmap Heap Scan", "Bitmap Index Scan", "BitmapAnd", "BitmapOr",
    "CTE Scan", "Custom Scan", "Delete on", "Foreign Scan", "Function Scan", "Gather Merge",
    "Gather", "GroupAggregate", "Group", "Hash Join", "HashAggregate", "HashSetOp", "Hash",
    "Incremental Sort", "Index Only Scan", "Index Scan", "Insert on", "Limit", "LockRows",
    "Materialize", "Memoize", "Merge Append", "Merge Join", "Merge on", "MixedAggregate",
    "Named Tuplestore Scan", "Nested Loop", "ProjectSet", "Recursive Union", "Result", "Sample Scan",
    "Seq Scan", "SetOp", "Sort", "Subquery Scan", "Tid Scan", "Unique", "Update on", "Values Scan",
    "WindowAgg", "WorkTable Scan",
)

private const val BITMAP_INDEX_SCAN = "Bitmap Index Scan"

private const val SUBPLAN_NAME = "Subplan Name"

private const val CTE = "CTE "

private const val USING = " using "

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

/** The lines of [text], each knowing where it begins — so every offset stays the input's own. */
private fun linesOf(text: String): List<PlanLine> {
    val lines = mutableListOf<PlanLine>()
    var at = 0
    while (true) {
        val end = text.indexOf('\n', at).takeIf { it >= 0 } ?: text.length
        lines += PlanLine(text.substring(at, end), at)
        if (end == text.length) return lines
        at = end + 1
    }
}

/**
 * The dotted chain beginning at [at] in [within] — one token, or several separated by dots.
 *
 * Read whole rather than a token at a time because the kinds are **positional**: what a segment is
 * depends on how many follow it, and a reader that classified each as it met it would have to change
 * its mind about the one before.
 *
 * @param offset where [within] begins in the line, so a token's offsets are the line's own
 */
private fun chainAt(within: String, at: Int, offset: Int): List<PlanToken> {
    val chain = mutableListOf<PlanToken>()
    var next = at
    while (true) {
        val token = tokenAt(within, next, offset) ?: break
        chain += token
        if (token.after >= within.length || within[token.after] != '.') break
        next = token.after + 1
    }
    return chain
}

/**
 * The token at [at] in [within], or `null` where nothing identifier-shaped starts there.
 *
 * A delimited name keeps its delimiters and reports the name inside them, which is where a
 * placeholder is written: `"Customers"` renders `"table2"`, and the token as a whole is still one
 * range, so occurrences still never split a token. See [PlanOccurrence.nameStart].
 */
private fun tokenAt(within: String, at: Int, offset: Int): PlanToken? {
    if (at >= within.length) return null

    if (within[at] == '"') {
        var end = at + 1
        while (end < within.length) {
            if (within[end] == '"') {
                if (end + 1 < within.length && within[end + 1] == '"') end++ else break
            }
            end++
        }
        if (end >= within.length) return null
        return PlanToken(
            offset + at,
            offset + end + 1,
            offset + at + 1,
            offset + end,
            within.substring(at, end + 1),
            end + 1,
        )
    }

    if (!opensAWord(within[at])) return null
    val end = endOfWord(within, at)
    return PlanToken(offset + at, offset + end, offset + at, offset + end, within.substring(at, end), end)
}

private fun opensAWord(character: Char): Boolean = character.isLetter() || character == '_'

private fun endOfWord(text: String, at: Int): Int {
    var end = at
    while (end < text.length && (text[end].isLetterOrDigit() || text[end] == '_' || text[end] == '$')) end++
    return end
}

private fun skippingSpaces(text: String, at: Int): Int {
    var next = at
    while (next < text.length && text[next] == ' ') next++
    return next
}

/** Past a single-quoted literal, whose content is the planner's and is never read. */
private fun pastQuote(text: String, at: Int): Int {
    var next = at + 1
    while (next < text.length) {
        if (text[next] == '\'') {
            if (next + 1 < text.length && text[next + 1] == '\'') next++ else return next + 1
        }
        next++
    }
    return text.length
}
