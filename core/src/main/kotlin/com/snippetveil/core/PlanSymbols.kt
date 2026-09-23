package com.snippetveil.core

/**
 * **What a plan's names and expressions become** — one copy, read by every format.
 *
 * The text format and the three structured ones differ in **where** a name is written and agree
 * completely on **what becomes of one**. A relation is a relation whether the engine printed it after
 * `on` or under `"Relation Name"`; an expression is scanned by the same lexer either way; and a name
 * the plan declared is the plan's own in both. So the reading lives here and each format's reader
 * owns one of these, rather than four readers each re-deciding it.
 *
 * That is also what makes the key agree **across** formats, which is not a nicety: a schema named in a
 * qualified relation and the same schema named on the `search_path` share a placeholder only because
 * both go through [name] with the same kind and the same spelling.
 *
 * @param declared the names the plan itself introduces — an alias, a CTE, a subplan's own name.
 *   Filled by [declare] and read by [keyOf]. Every format reads its structure twice against one of
 *   these, because a plan prints the use of a declared name as readily above the declaration as
 *   below it; the first pass's occurrences are thrown away.
 * @param vocabulary the engine whose printer wrote this plan.
 */
internal class PlanSymbols(private val declared: MutableSet<String>, val vocabulary: PlanVocabulary) {

    val occurrences = mutableListOf<PlanOccurrence>()

    /**
     * **One slot, handed to the treatment its inventory row names** — the arms every format shares,
     * in the one place they can be shared.
     *
     * A field's *position* differs by format and a field's *treatment* does not: `Filter` is an
     * expression whether the engine wrote it after a colon or under a key, and `Relation Name` is a
     * relation either way. Two cascades over one sealed type is two places for a later class to be
     * added to and one place for it to be forgotten.
     *
     * @return `false` where [treatment] names a **container** rather than a value, or a line whose
     *   reading needs the node it was printed on. Neither is a case this can answer: what a container
     *   holds and what a node's siblings are is the document's shape, which each format's reader
     *   knows and this does not. Both callers refuse on it.
     */
    fun readSlot(slot: PlanSlot, treatment: PlanTreatment): Boolean {
        when (treatment) {
            is PlanTreatment.Name -> name(slot, treatment.kind)
            PlanTreatment.Declared -> declareWritten(slot)
            PlanTreatment.Expression -> readExpression(slot)
            is PlanTreatment.Reference -> readReference(slot, treatment.kinds)
            PlanTreatment.Measured -> occurrences += PlanTreatments.measured()
            is PlanTreatment.Fact -> occurrences += PlanTreatments.engineFact(slot, treatment.shape)
            is PlanTreatment.Unescaped -> occurrences += PlanTreatments.unescaped(slot, treatment.admits)
            PlanTreatment.EchoedQuery -> occurrences += PlanTreatments.echoedQuery(slot)
            PlanTreatment.Parameters -> occurrences += PlanTreatments.parameters(slot, vocabulary)
            PlanTreatment.Identifying -> occurrences += PlanTreatments.identifying(slot)
            PlanTreatment.Deployment -> occurrences += PlanTreatments.deployment(slot)
            PlanTreatment.AppendedRaw -> occurrences += PlanTreatments.appendedRaw(slot)

            is PlanTreatment.Subtree, PlanTreatment.SettingsMap, is PlanTreatment.Rendered -> return false
        }
        return true
    }

    /**
     * **One qualified reference, split on `.` and keyed by position** — and a part count that is not
     * the one the inventory named **refuses the input**.
     *
     * Every part is a name and the kinds are positional, exactly as they are in a dotted chain inside
     * an expression — but the counting is the opposite discipline. A chain is read as long as it
     * happens to be, because the lexer found its boundaries in the engine's own delimiters; a
     * reference arrives with **no delimiters at all**, so the only thing that says a `.` separated
     * two names rather than sitting inside one is that the total came out right.
     *
     * An alias a user spelled `a.b` is the case that makes this necessary and it is the case a
     * tolerant reader silently gets wrong: the reference has one part too many, every kind shifts by
     * one, and the output confidently calls somebody's alias a schema.
     *
     * A field with **nothing in it** is nothing to place, which is the answer every other treatment
     * gives an empty slot; an **empty part inside a reference** is a different thing, and refuses.
     *
     * @throws PlanRefusal where the split does not give exactly one part per kind, or where a part is
     *   empty — a reference that begins, ends or doubles a `.` is not one this reader can place
     */
    fun readReference(slot: PlanSlot, kinds: List<SymbolRole>) {
        val reference = slot.trimmed()
        if (reference.isBlank) return

        val parts = reference.written.split('.')
        if (parts.size != kinds.size || parts.any { it.isEmpty() }) throw PlanRefusal(PlanReading.Unreadable)

        var at = reference.start
        for ((position, part) in parts.withIndex()) {
            val kind = kinds[position]
            occurrences += PlanOccurrence(
                at,
                at + part.length,
                PlanDisposition.Anonymize(kind, keyOf(kind, part)),
            )
            // Past the part, and past the `.` that follows it — which is punctuation, and survives
            // by not being reported.
            at += part.length + 1
        }
    }

    /** **A name of [kind], written over the whole of [slot]** — where the slot *is* one name. */
    fun name(slot: PlanSlot, kind: SymbolRole) {
        if (slot.isBlank) return
        val spelling = slot.written
        occurrences += PlanOccurrence(slot.start, slot.end, PlanDisposition.Anonymize(kind, keyOf(kind, spelling)))
    }

    /**
     * **A name the plan introduced, as the plan writes one** — which is three shapes and not one.
     *
     * An alias is a bare name; a subplan's own name is either `CTE recent`, where the keyword is the
     * engine's and the name after it is the plan's, or `SubPlan 1`, which is a label the planner
     * invented and names nothing of anybody's. Reading all three through one rule is what lets the
     * structured formats key an alias, a CTE and a subplan name exactly as the text format does.
     */
    fun declareWritten(slot: PlanSlot) {
        if (slot.isBlank) return
        val written = slot.written

        val cte = CTE_HEADER.matchEntire(written)
        if (cte != null) {
            val name = cte.groups[1]!!.range
            preserve(slot.start, slot.start + name.first)
            declare(slot.narrowed(slot.start + name.first, slot.start + name.last + 1))
            return
        }

        if (SUBPLAN_HEADER.matches(written)) {
            preserve(slot.start, slot.end)
            return
        }
        declare(slot)
    }

    /** **A name the plan introduced**, written over the whole of [slot]. See [declareWritten]. */
    fun declare(slot: PlanSlot) {
        if (slot.isBlank) return
        val spelling = slot.written
        declared += spelling
        occurrences += PlanOccurrence(
            slot.start,
            slot.end,
            PlanDisposition.Anonymize(SymbolRole.TABLE, PlanKeys.declared(SymbolRole.TABLE, spelling)),
        )
    }

    /**
     * **A name the plan itself introduced** — an alias, a CTE — filed as the plan's own.
     *
     * It goes into [declared] before it is keyed, which is what the two passes every format runs are
     * for: a name the plan declares is the plan's wherever it is written afterwards, whether that is
     * above the declaration or below it.
     */
    fun declare(token: PlanToken) {
        declared += token.text
        occurrences += PlanOccurrence(
            token.start,
            token.end,
            PlanDisposition.Anonymize(SymbolRole.TABLE, PlanKeys.declared(SymbolRole.TABLE, token.text)),
            token.nameStart,
            token.nameEnd,
        )
    }

    /** The token slotted and emitted as written, over `[start, end)`. */
    fun preserve(start: Int, end: Int) {
        occurrences += PlanOccurrence(start, end, PlanDisposition.Preserve)
    }

    /**
     * **Which of the two key shapes a name takes** — the plan's own, or the invocation's.
     *
     * A name the plan declared is the plan's however it is written afterwards, which is what makes
     * the alias in `Seq Scan on visits v` and the `v` in `Filter: (v.id = 1)` one symbol. Everything
     * else keys on its spelling across the invocation, exactly as written. See [PlanKeys].
     */
    fun keyOf(kind: SymbolRole, spelling: String): String =
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
    fun readExpression(slot: PlanSlot) {
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
    fun readName(tokens: List<PlanToken>, at: Int): Int {
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
            preserve(tokens[at].start, tokens[at + phrase - 1].end)
            return at + phrase
        }

        val token = tokens[at]
        if (token.kind == PlanTokenKind.WORD && vocabulary.knows(token.text)) {
            preserve(token.start, token.end)
            return at + 1
        }

        occurrences += anonymized(token, SymbolRole.COLUMN)
        return at + 1
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
    fun readQualified(tokens: List<PlanToken>, at: Int, last: SymbolRole): Int {
        val index = nameAt(tokens, at) ?: return at
        val chain = chainOf(tokens, index).tokens

        for ((position, token) in chain.withIndex()) {
            occurrences += anonymized(token, if (position == chain.lastIndex) last else SymbolRole.SCHEMA)
        }
        return chain.last().end
    }

    /** One token replaced by a placeholder of [kind], written inside its delimiters. */
    fun anonymized(token: PlanToken, kind: SymbolRole) = PlanOccurrence(
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
internal fun nameAt(tokens: List<PlanToken>, at: Int): Int? =
    tokens.indexOfFirst { it.start >= at }.takeIf { it >= 0 && tokens[it].isName }

/**
 * The dotted chain beginning at [at] — one token, or several joined by `.` — and where the walk
 * resumes after it.
 *
 * Read whole rather than a token at a time because the kinds are **positional**: what a segment is
 * depends on how many follow it, and a reader that classified each as it met it would have to change
 * its mind about the one before.
 */
internal fun chainOf(tokens: List<PlanToken>, at: Int): PlanChain {
    val chain = mutableListOf(tokens[at])
    var next = at + 1
    while (next + 1 < tokens.size && tokens[next].isDot && tokens[next + 1].isName) {
        chain += tokens[next + 1]
        next += 2
    }
    return PlanChain(chain, next)
}

/** One qualified name, and the index the reader goes on from. See [chainOf]. */
internal class PlanChain(val tokens: List<PlanToken>, val after: Int)

/**
 * **One line of an input, and where it begins in it** — so every offset cut from a line stays the
 * input's own.
 *
 * A type rather than a pair, because the two numbers a frame deals in are both offsets and telling
 * them apart by position is exactly the mistake this file is written to prevent. See [linesIn].
 */
internal class PlanTextLine(val text: String, val start: Int)

/**
 * The lines of [text], for every reader here that works a line at a time — the frame peeler and the
 * text format's own reader alike.
 *
 * **A carriage return is a line terminator rather than content**, so it is left off the line and is
 * therefore never inside a slot, never scanned and never reported. A plan pasted out of a Windows
 * terminal reads exactly as the same plan pasted anywhere else, and its line endings come back
 * untouched because nothing above can reach them.
 */
internal fun linesIn(text: String): List<PlanTextLine> {
    val lines = mutableListOf<PlanTextLine>()
    var at = 0
    while (true) {
        val end = text.indexOf('\n', at).takeIf { it >= 0 } ?: text.length
        lines += PlanTextLine(text.substring(at, end).removeSuffix(RETURN), at)
        if (end == text.length) return lines
        at = end + 1
    }
}

/** `CTE recent` — the keyword the engine writes in front of a name the plan is computing for itself. */
internal val CTE_HEADER = Regex("""CTE (\S+)""")

/**
 * `SubPlan 1`, `InitPlan 2 (returns $0)` — the labels the planner invents for the pieces of a plan.
 *
 * They name nothing of anybody's: the word is the printer's and the number is its own counter, so the
 * whole label is preserved rather than declared.
 */
internal val SUBPLAN_HEADER = Regex("""(SubPlan|InitPlan) \d+( \(returns [$\d, ]+\))?""")
