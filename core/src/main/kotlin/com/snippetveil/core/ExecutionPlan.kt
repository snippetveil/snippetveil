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
 * Relations, columns, schemas, aliases and indexes; the values the planner printed, masked; and
 * **nothing numeric**. The costs, the row estimates, the widths and the timings are read straight
 * past: names are taken from positions on a node line, which is why no rule here can reach the
 * parentheticals at all, and a field of measurements is reported as nothing at all. A plan is pasted
 * *for* its numbers, and an anonymizer that moved one would have destroyed the reason it was sent.
 *
 * A token this file does not recognise is reported as a **column** rather than passed through, which
 * is the fail-closed direction and is stated rather than tidy: over-reporting costs a visible,
 * obviously-anonymized artifact in a line of plan vocabulary, and under-reporting puts the
 * employer's domain on a clipboard. [POSTGRES] is what keeps that residual from eating the plan's
 * own language, and it holds the engine's vocabulary rather than anybody's names — see [scanOf] for
 * the argument that lets any of it be preserved at all, and [PlanTreatments] for the classes the
 * fields are read under.
 */
fun parsePlan(text: String): PlanReading {
    val lines = linesOf(text)
    if (lines.isEmpty() || !opensAPlan(lines.first())) return PlanReading.Unreadable

    // **Every declaration first, and only then anything keyed against one.** A plan prints
    // `CTE Scan on recent` above the `CTE recent` that declares `recent` as readily as below it, and
    // a name that keyed as an invocation-wide relation where it was printed first and as the plan's
    // own where it was printed second would be identity decided by print order — two placeholders
    // for one thing, in the output the user reads.
    //
    // So the structure is read twice against the same set: **the first reader's occurrences are
    // thrown away**, because the second reader makes them again with every declaration in hand. That
    // is cheaper than it looks — the walk allocates a token per name and resolves nothing — and it is
    // the whole of what makes the two keys agree.
    val declared = mutableSetOf<String>()
    val declarations = PlanReader(declared, POSTGRES)
    lines.forEach(declarations::readStructure)

    val reader = PlanReader(declared, POSTGRES)
    lines.forEach(reader::readStructure)
    lines.forEach(reader::readFields)

    return PlanReading.Read(SnippetPlan(text, reader.occurrences.sortedBy { it.start }))
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
 * **The reading of one plan** — the names it declares, and the occurrences it produces.
 *
 * A class rather than a run of functions because [declared] and the list being filled travel
 * together through every rule below, and threading the pair through seven signatures made the
 * parameters the loudest thing in the file. It also makes the two passes [parsePlan] runs visible as
 * what they are: two readers over one set of declarations, of which only the second keeps its work.
 *
 * @param declared the names the plan itself introduces — an alias, a CTE. [readStructure] fills it,
 *   and everything keyed as a relation reads it. See [PlanKeys].
 * @param vocabulary the engine whose printer wrote this plan, passed in rather than read out of a
 *   global anywhere below — so the second engine is a second argument here and not a second reader.
 */
private class PlanReader(private val declared: MutableSet<String>, private val vocabulary: PlanVocabulary) {

    val occurrences = mutableListOf<Occurrence>()

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
            if (field.label == SUBPLAN_NAME) readCteHeader(line, line.start + field.from)
            return
        }

        val label = labelOf(line.body)
        if (label == null) {
            // `CTE recent`, sitting above the subtree that computes it.
            readCteHeader(line, line.start + line.bodyAt)
            return
        }

        // **The head alone is scanned**, never the parenthetical behind it — which is the whole
        // reason a plan is pasted, and is out of reach of every rule here by not being read at all.
        val head = line.body.substringBefore(TAIL)
        val from = line.start + line.bodyAt
        val tokens = scanOf(PlanSlot(line.text, from, from + head.length, line.start)).tokens

        var at = label.length
        val using = head.indexOf(USING, at)
        if (using >= 0) at = readQualified(tokens, from + using + USING.length, SymbolRole.INDEX) - from

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
        val after = readQualified(tokens, from + relation, scanned)

        // The alias, where the engine printed one — `Seq Scan on visits v`. It is a name the **plan**
        // declares: two plans each calling something `v` are not talking about one thing, and
        // `visits_1` is an alias this engine invented rather than a relation anybody named.
        val alias = nameAt(tokens, after) ?: return
        declare(tokens[alias])
    }

    /** `CTE recent` — the name after the keyword, declared by the plan that computes it. */
    private fun readCteHeader(line: PlanLine, from: Int) {
        val tokens = scanOf(PlanSlot(line.text, from, line.start + line.text.length, line.start)).tokens
        if (tokens.size < 2 || tokens[0].text != CTE || !tokens[1].isName) return
        declare(tokens[1])
    }

    /**
     * **A name the plan itself introduced** — an alias, a CTE — filed as the plan's own.
     *
     * It goes into [declared] before it is keyed, which is what the two passes in [parsePlan] are
     * for: a name the plan declares is the plan's wherever it is written afterwards, whether that is
     * above the declaration or below it.
     */
    private fun declare(token: PlanToken) {
        declared += token.text
        occurrences += PlanOccurrence(
            token.start,
            token.end,
            PlanDisposition.Anonymize(SymbolRole.TABLE, PlanKeys.declared(SymbolRole.TABLE, token.text)),
            token.nameStart,
            token.nameEnd,
        )
    }

    /**
     * **One field line, handed to the treatment its label names** — which is the whole of this
     * format's field inventory, and the only place a label decides anything.
     *
     * The classes are [PlanTreatments]'; what is here is the mapping from PostgreSQL's text output to
     * them. The default arm is the decision: **a field this file has never heard of is read as an
     * expression** rather than passed over, so a plan option nobody anticipated cannot carry a column
     * name out unnoticed. What that costs is a metric word occasionally replaced in a line of
     * counters, which is visible in the pane the user is looking at.
     *
     * Three of the labels below — the echoed query, the parameters and the query identifier — are
     * printed by `EXPLAIN (VERBOSE)` and by `auto_explain` **above** the tree rather than inside it,
     * where this reader's anchored recognition does not yet admit them. They are inventory rows all
     * the same: a field is treated by what it is wherever it is printed, and a reader that answered
     * only for the positions seen so far would be one more thing to remember when the next format
     * arrives.
     */
    fun readFields(line: PlanLine) {
        val field = line.field ?: return
        val slot = PlanSlot(line.text, line.start + field.from, line.start + line.text.length, line.start)

        when {
            // A declaration, read whole by `readStructure` before anything was keyed against it.
            field.label == SUBPLAN_NAME -> return

            field.label in MEASURED_FIELDS -> occurrences += PlanTreatments.measured()
            field.label in ECHOED_QUERY_FIELDS -> occurrences += PlanTreatments.echoedQuery(slot)
            field.label in PARAMETER_FIELDS -> occurrences += PlanTreatments.parameters(slot, vocabulary)
            field.label in IDENTIFYING_FIELDS -> occurrences += PlanTreatments.identifying(slot)

            else -> readExpression(slot)
        }
    }

    /**
     * The dotted chain that begins at or after [at] — `billing.invoices`, `v`, `"Customers"` — with
     * [last] the kind of its final segment and every segment before it a schema.
     *
     * **A qualified name is several symbols and not one**, so each segment is keyed and replaced on
     * its own: `billing.invoices` renders `schema1.table2`, and the reader keeps the fact that the
     * two are different things in different namespaces.
     *
     * @return where the chain ended in the plan, so the caller can go on reading the line from
     *   there — and [at] itself where nothing identifier-shaped was waiting
     */
    private fun readQualified(tokens: List<PlanToken>, at: Int, last: SymbolRole): Int {
        val index = nameAt(tokens, at) ?: return at
        val chain = chainOf(tokens, index).tokens

        for ((position, token) in chain.withIndex()) {
            occurrences += anonymized(token, if (position == chain.lastIndex) last else SymbolRole.SCHEMA)
        }
        return chain.last().end
    }

    /**
     * **Which of the two key shapes a name takes** — the plan's own, or the invocation's.
     *
     * A name the plan declared is the plan's however it is written afterwards, which is what makes
     * the alias in `Seq Scan on visits v` and the `v` in `Filter: (v.id = 1)` one symbol. Everything
     * else keys on its spelling across the invocation, exactly as written. See [PlanKeys].
     */
    private fun keyOf(kind: SymbolRole, spelling: String): String =
        if (kind == SymbolRole.TABLE && spelling in declared) {
            PlanKeys.declared(kind, spelling)
        } else {
            PlanKeys.named(kind, spelling)
        }

    /**
     * **Every token in one expression field, and what the residual closure makes of each.**
     *
     * The field is scanned once by [scanOf] and then walked cell by cell. What decides a cell is its
     * shape and the vocabulary, and nothing else — there is no tree, and no rule here asks what a
     * token might mean:
     *
     *  - **The field is checked for soundness first.** A field whose quoting the engine does not
     *    make unforgeable is never parsed and becomes one redacted literal, whole. See
     *    [PlanTreatments.unreadable].
     *  - **A literal is masked**, content only. It is a value the planner printed — an address, an
     *    account id, a token — and it is the one thing in a plan that is unambiguously the user's.
     *  - **Numbers and punctuation survive by not being reported**, which is what keeps a plan's
     *    measurements out of reach of every rule here.
     *  - **A dotted chain is read whole**: `v.created_at` is a qualifier and a column, `billing.t.c`
     *    is a schema, a relation and a column. The last segment is a column **by position**, so a
     *    column genuinely called `text` keeps its kind rather than being mistaken for the type.
     *  - **A phrase the vocabulary holds survives whole**, and may not span a name slot.
     *  - **A word the vocabulary knows survives**; a delimited token never does, because a delimited
     *    token is always a name.
     *  - **Everything else is a column** — the residual, running toward replacement, argued in
     *    [scanOf] and on [parsePlan].
     */
    private fun readExpression(slot: PlanSlot) {
        val scan = scanOf(slot)
        if (!scan.sound) {
            occurrences += PlanTreatments.unreadable(slot)
            return
        }

        var at = 0
        while (at < scan.tokens.size) {
            val token = scan.tokens[at]
            at = when (token.kind) {
                PlanTokenKind.MARK, PlanTokenKind.NUMBER -> at + 1
                PlanTokenKind.LITERAL -> {
                    occurrences += PlanTreatments.literal(token)
                    at + 1
                }

                PlanTokenKind.WORD, PlanTokenKind.DELIMITED -> readName(scan.tokens, at)
            }
        }
    }

    /**
     * What becomes of the name-shaped token at [at], and where the walk goes next.
     *
     * **The chain is asked first**, because the kinds in one are positional and a word that belongs
     * to a qualified name is a name whatever else it is spelled like. The phrase is asked next, and
     * the bare word last, which is the order the delimitation argument comes in: a space is a
     * stronger warrant than a spelling.
     */
    private fun readName(tokens: List<PlanToken>, at: Int): Int {
        val chain = chainOf(tokens, at)
        if (chain.tokens.size > 1) {
            for ((position, token) in chain.tokens.withIndex()) {
                val kind = when (position) {
                    chain.tokens.lastIndex -> SymbolRole.COLUMN
                    chain.tokens.lastIndex - 1 -> SymbolRole.TABLE
                    else -> SymbolRole.SCHEMA
                }
                occurrences += anonymized(token, kind)
            }
            return chain.after
        }

        val phrase = vocabulary.phraseAt(tokens, at)
        if (phrase > 0) {
            occurrences += PlanOccurrence(tokens[at].start, tokens[at + phrase - 1].end, PlanDisposition.Preserve)
            return at + phrase
        }

        val token = tokens[at]
        if (token.kind == PlanTokenKind.WORD && vocabulary.knows(token.text)) {
            occurrences += PlanOccurrence(token.start, token.end, PlanDisposition.Preserve)
            return at + 1
        }

        occurrences += anonymized(token, SymbolRole.COLUMN)
        return at + 1
    }

    /** One token replaced by a placeholder of [kind], written inside its delimiters. */
    private fun anonymized(token: PlanToken, kind: SymbolRole) = PlanOccurrence(
        token.start,
        token.end,
        PlanDisposition.Anonymize(kind, keyOf(kind, token.text)),
        token.nameStart,
        token.nameEnd,
    )
}

/**
 * The index of the token waiting at or after [at], where the thing waiting there is a name — and
 * `null` where it is punctuation, a number or nothing at all.
 *
 * At or after rather than exactly at, because the scan has already dropped the whitespace a plan
 * separates its names with: `Insert on billing.invoices` carries the keyword inside its own label,
 * so the offset a caller has is the space in front of the relation rather than the relation.
 */
private fun nameAt(tokens: List<PlanToken>, at: Int): Int? =
    tokens.indexOfFirst { it.start >= at }.takeIf { it >= 0 && tokens[it].isName }

/**
 * The dotted chain beginning at [at] — one token, or several joined by `.` — and where the walk
 * resumes after it.
 *
 * Read whole rather than a token at a time because the kinds are **positional**: what a segment is
 * depends on how many follow it, and a reader that classified each as it met it would have to change
 * its mind about the one before.
 */
private fun chainOf(tokens: List<PlanToken>, at: Int): PlanChain {
    val chain = mutableListOf(tokens[at])
    var next = at + 1
    while (next + 1 < tokens.size && tokens[next].isDot && tokens[next + 1].isName) {
        chain += tokens[next + 1]
        next += 2
    }
    return PlanChain(chain, next)
}

/** One qualified name, and the index the reader goes on from. See [chainOf]. */
private class PlanChain(val tokens: List<PlanToken>, val after: Int)

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
 * **The fields that hold measured quantities** — the numbers a plan is pasted *for*, preserved
 * exactly as printed. See [PlanTreatments.measured] for what that costs and why the cost is stated
 * rather than closed.
 *
 * A list of the measured fields rather than a list of the name-bearing ones, because the direction
 * of the mistake differs: a field missing from this list is read as an expression and its counters
 * come out anonymized, which is visible and ugly; a *name-bearing* field missing from a list of loud
 * fields would leave a column name on the clipboard, which is invisible and is the whole failure.
 */
private val MEASURED_FIELDS: Set<String> = setOf(
    "Planning Time", "Execution Time", "Planning", "Execution", "Buffers", "I/O Timings",
    "Sort Method", "Sort Space Used", "Sort Space Type", "Workers Planned", "Workers Launched",
    "Worker", "Heap Blocks", "Exact Heap Blocks", "Lossy Heap Blocks", "Buckets", "Batches",
    "Memory Usage", "Peak Memory Usage", "Disk Usage", "Rows Removed by Filter",
    "Rows Removed by Index Recheck", "Rows Removed by Join Filter", "Rows Removed by Conflict Filter",
    "Functions", "Options", "Timing", "JIT", "Settings", "Full-sort Groups", "Pre-sorted Groups",
    "Hits", "Misses", "Evictions", "Overflows", "Storage", "Tuples Inserted", "Conflicting Tuples",
    "Heap Fetches",
)

/**
 * **The fields holding the user's own statement text** — `auto_explain`'s echo of the query, and the
 * statement a foreign scan is about to send to another server.
 *
 * Each is one redacted literal and is never parsed. See [PlanTreatments.echoedQuery].
 */
private val ECHOED_QUERY_FIELDS: Set<String> = setOf("Query Text", "Remote SQL")

/** **The fields holding a parameter list** — each value a literal. See [PlanTreatments.parameters]. */
private val PARAMETER_FIELDS: Set<String> = setOf("Query Parameters")

/**
 * **The fields holding a value that identifies rather than describes** — PostgreSQL's query id is a
 * hash of the statement, and a receiver holding one can confirm a guessed query against it. See
 * [PlanTreatments.identifying].
 */
private val IDENTIFYING_FIELDS: Set<String> = setOf("Query Identifier")

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

/** The one node label whose `on` names an access path rather than a rowset. See [PlanReader]. */
private const val BITMAP_INDEX_SCAN = "Bitmap Index Scan"

/** The field a subplan's own name arrives on, which is a declaration rather than an expression. */
private const val SUBPLAN_NAME = "Subplan Name"

/** The keyword a plan writes in front of a name it is computing for itself. */
private const val CTE = "CTE"

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


