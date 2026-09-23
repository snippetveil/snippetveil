package com.snippetveil.core

/**
 * **The reading of a dialect that brackets every identifier it prints** — SQL Server's, in both the
 * forms this product reads.
 *
 * ### Why this is a reader of its own rather than a vocabulary
 *
 * [scanOf] exists because PostgreSQL prints *some* identifiers bare, so a bare word there has to be
 * looked up: the vocabulary is the list of spellings that engine's quoting rule cannot have given to
 * a user's object, and everything outside it anonymizes. SQL Server's showplan writer makes that
 * question go away. **Every identifier it prints is bracketed**, with the closing bracket doubled
 * inside — a schema, a table whose name carries a bracket and a comma, an index, a column carrying a
 * tab, a generated constraint name. So the rule here is the strongest form of the delimitation
 * argument rather than a weaker one:
 *
 * > **A bracketed token is a name. Every character outside a bracket or a quoted literal is the
 * > engine's own, and survives by not being reported.**
 *
 * That costs no word list and no per-release vocabulary row: an operator, a keyword or a hint
 * spelling this product has never seen passes through untouched *because it is not in brackets*,
 * and a user's object spelled identically is bracketed and does not.
 *
 * **The one place the writer breaks its own rule is refused rather than read.** A remote query or
 * remote scan row prints the linked server unbracketed and the remote statement verbatim; that is
 * out of vocabulary, and it refuses with an engine option that does escape them. See
 * [SQLSERVER_REMOTE_OPERATORS].
 *
 * ### An opener that never closes is the whole soundness check
 *
 * A bracketed name carrying a newline leaves its opener unbalanced, because the writer does not
 * escape one — and that is exactly what a row forged out of a table name runs into. **There is no
 * sound reading of an unclosed bracket, so there is no reading**: the input refuses, the way a field
 * whose quoting the lexer cannot close is never parsed. Nothing here guesses where a name ended.
 */
internal class BracketedName(val slot: PlanSlot, val name: PlanSlot, val recovered: String)

/**
 * **The bracketed name written at [at]**, or `null` where nothing is written there and where the
 * bracket opened at [at] never closes inside [slot].
 *
 * `[]` is `null` too. It is not a name the engine can have printed — no object can be called
 * nothing — so it is a value this reader cannot place rather than a name with an empty spelling.
 */
internal fun bracketedNameAt(slot: PlanSlot, at: Int): BracketedName? {
    if (at >= slot.end || slot.at(at) != BRACKET_OPEN) return null

    val recovered = StringBuilder()
    var next = at + 1
    while (next < slot.end) {
        if (slot.at(next) != BRACKET_CLOSE) {
            recovered.append(slot.at(next))
            next++
            continue
        }
        // **A doubled closing bracket is the character itself**, which is how this writer escapes
        // one — so a name carrying `]` can never close its own delimiter early.
        if (next + 1 < slot.end && slot.at(next + 1) == BRACKET_CLOSE) {
            recovered.append(BRACKET_CLOSE)
            next += 2
            continue
        }
        if (recovered.isEmpty()) return null
        return BracketedName(slot.narrowed(at, next + 1), slot.narrowed(at + 1, next), recovered.toString())
    }
    return null
}

/**
 * **The dotted chain of bracketed names beginning at [at]** — `[v].[OwnerId]`,
 * `[shop].[dbo].[Visits].[Id]` — or `null` where one of its parts does not close.
 *
 * Read whole rather than a part at a time for the reason [chainOf] gives: what a part **is** depends
 * on how many follow it, so a reader that classified each as it met it would have to change its mind
 * about the one before.
 */
internal fun bracketedChainAt(slot: PlanSlot, at: Int): List<BracketedName>? {
    val parts = mutableListOf(bracketedNameAt(slot, at) ?: return null)
    while (true) {
        val next = parts.last().slot.end
        if (next + 1 >= slot.end || slot.at(next) != '.' || slot.at(next + 1) != BRACKET_OPEN) return parts
        parts += bracketedNameAt(slot, next + 1) ?: return null
    }
}

/**
 * **One expression field of a bracketing dialect, read end to end** — the names, the literals, and
 * nothing else.
 *
 * Three things are reported and every other character is the engine's:
 *
 *  - **An object reference** — what the writer puts after `OBJECT:(` — is read **left to right**,
 *    because the writer always qualifies it fully: `[database].[schema].[table].[index]`. That is the
 *    opposite of how every other chain is read, and it has to be: `[shop].[dbo].[Visits].[Id]` and
 *    `[shop].[dbo].[Visits].[PK_Visits]` are the same four parts, and only the `OBJECT:(` in front of
 *    the second says that its last part is an access path rather than a column.
 *  - **Every other chain** is read **right to left** — last a column, the one before it a rowset, the
 *    rest namespace qualifiers — exactly as a dotted chain inside a PostgreSQL expression is.
 *  - **A quoted literal is masked, content only**, so the field still reads as the predicate it is.
 *
 * @throws PlanRefusal where a bracket or a quote opened and never closed, and where an object
 *   reference has more parts than an object can have
 */
internal fun readBracketedExpression(symbols: PlanSymbols, slot: PlanSlot) {
    var at = slot.start
    while (at < slot.end) {
        at = when {
            slot.startsWith(OBJECT_REFERENCE, at) -> readObject(symbols, slot, at + OBJECT_REFERENCE.length)
            slot.at(at) == BRACKET_OPEN -> readChain(symbols, slot, at)
            slot.at(at) == LITERAL_QUOTE -> readLiteral(symbols, slot, at)
            else -> at + 1
        }
    }
}

/** A chain read by position from the right, which is what every reference outside `OBJECT:(` is. */
private fun readChain(symbols: PlanSymbols, slot: PlanSlot, at: Int): Int {
    val chain = bracketedChainAt(slot, at) ?: throw PlanRefusal(PlanReading.Unreadable)
    for ((position, part) in chain.withIndex()) {
        val kind = when (position) {
            chain.lastIndex -> SymbolRole.COLUMN
            chain.lastIndex - 1 -> SymbolRole.TABLE
            else -> SymbolRole.SCHEMA
        }
        symbols.bracketed(part, kind)
    }
    return chain.last().slot.end
}

/**
 * The object the writer named after `OBJECT:(` — read by position from the left — and the alias it
 * wrote after it, which is a name **the plan declares** and uses everywhere else in the field.
 *
 * The alias is matched on ` AS ` in the writer's own casing. The lower-case `as` that joins the two
 * spellings of one column inside a seek predicate is deliberately not this, and is read as the
 * reference it is by the general rule.
 */
private fun readObject(symbols: PlanSymbols, slot: PlanSlot, at: Int): Int {
    val chain = bracketedChainAt(slot, at) ?: throw PlanRefusal(PlanReading.Unreadable)
    val kinds = OBJECT_PARTS.getOrNull(chain.size - 1) ?: throw PlanRefusal(PlanReading.Unreadable)
    for ((position, part) in chain.withIndex()) symbols.bracketed(part, kinds[position])

    val after = chain.last().slot.end
    if (!slot.startsWith(OBJECT_ALIAS, after)) return after

    val alias = bracketedNameAt(slot, after + OBJECT_ALIAS.length) ?: throw PlanRefusal(PlanReading.Unreadable)
    symbols.declareBracketed(alias)
    return alias.slot.end
}

/** A quoted literal, masked content only — and a refusal where the field never closes it. */
private fun readLiteral(symbols: PlanSymbols, slot: PlanSlot, at: Int): Int {
    var next = at + 1
    while (next < slot.end) {
        if (slot.at(next) != LITERAL_QUOTE) {
            next++
            continue
        }
        // A doubled quote is the character itself, which is how this writer escapes one.
        if (next + 1 < slot.end && slot.at(next + 1) == LITERAL_QUOTE) {
            next += 2
            continue
        }
        val token = PlanToken(
            PlanTokenKind.LITERAL,
            at,
            next + 1,
            at + 1,
            next,
            slot.narrowed(at, next + 1).written,
        )
        symbols.occurrences += PlanTreatments.literal(token)
        return next + 1
    }
    throw PlanRefusal(PlanReading.Unreadable)
}

/**
 * **What each part of an object reference is, by how many parts the writer printed** — indexed by the
 * count less one.
 *
 * A database is read as a namespace qualifier because that is what it is in the reference, and
 * because there is no placeholder kind that says *database*: inventing one would be a new
 * output-grammar surface for a distinction a reader following the plan does not need. A reference
 * with more parts than this is one this reader cannot place, and it refuses.
 */
private val OBJECT_PARTS: List<List<SymbolRole>> = listOf(
    listOf(SymbolRole.TABLE),
    listOf(SymbolRole.SCHEMA, SymbolRole.TABLE),
    listOf(SymbolRole.SCHEMA, SymbolRole.SCHEMA, SymbolRole.TABLE),
    listOf(SymbolRole.SCHEMA, SymbolRole.SCHEMA, SymbolRole.TABLE, SymbolRole.INDEX),
)

/** What the writer opens an object reference with, its colon and bracket included. */
private const val OBJECT_REFERENCE = "OBJECT:("

/** What an object reference's alias follows, in the writer's own casing. See [readObject]. */
private const val OBJECT_ALIAS = " AS "

/** What opens a bracketed name. */
private const val BRACKET_OPEN = '['

/** What closes one, and what is doubled inside one to mean itself. */
private const val BRACKET_CLOSE = ']'

/** What a string literal is written inside, and doubled inside itself to mean itself. */
private const val LITERAL_QUOTE = '\''
