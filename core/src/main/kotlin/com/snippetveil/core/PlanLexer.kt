package com.snippetveil.core

/**
 * **A slot of a plan's text** — the range one field's value occupies, carried with enough of its
 * surroundings to read it.
 *
 * Every offset here is **the plan's own**, so an occurrence built from a slot needs no arithmetic and
 * no caller has to remember which coordinate system it is in. [within] is whatever string the reader
 * happened to have — a line, or the whole input — and [offset] says where that string begins in the
 * plan, which is the whole of what makes the two agree.
 *
 * @param within the string [start] and [end] are read out of
 * @param start where the slot begins, in the plan's coordinates
 * @param end where it ends, in the plan's coordinates
 * @param offset where [within] begins in the plan. Zero when [within] *is* the plan.
 */
internal class PlanSlot(val within: String, val start: Int, val end: Int, val offset: Int = 0) {

    /** The character at [at], which is the plan's offset rather than [within]'s. */
    fun at(at: Int): Char = within[at - offset]

    /** The slot's text, exactly as the engine printed it. */
    val written: String get() = within.substring(start - offset, end - offset)

    /** Whether [text] is written at [at]. See [at]. */
    fun startsWith(text: String, at: Int): Boolean = within.startsWith(text, at - offset)

    /** The same slot narrowed to `[start, end)`, which is how a field hands one of its values on. */
    fun narrowed(start: Int, end: Int) = PlanSlot(within, start, end, offset)

    /**
     * The same slot with the whitespace around it taken off — so a mask replaces the **value** and
     * not the space the engine printed after the colon.
     */
    fun trimmed(): PlanSlot {
        var from = start
        var to = end
        while (from < to && at(from).isWhitespace()) from++
        while (to > from && at(to - 1).isWhitespace()) to--
        return narrowed(from, to)
    }

    /** Whether there is nothing here but whitespace. */
    val isBlank: Boolean get() = (start until end).all { at(it).isWhitespace() }

    /**
     * Where the word starting at [at] ends — letters, digits, `_` and the `$` a parameter is written
     * with, stopping at the slot's end.
     *
     * It lives here rather than beside the scanner so that the promise above holds all the way down:
     * **no caller does the arithmetic**, including the scanner.
     */
    fun endOfWordAt(at: Int): Int {
        var next = at
        while (next < end && (at(next).isLetterOrDigit() || at(next) == '_' || at(next) == '$')) next++
        return next
    }
}

/**
 * **The lexer that reads a plan's expression fields** — and the argument for why a lexer is the right
 * instrument and a dialect parser is not.
 *
 * A plan's expression fields are **not written in the engine's input grammar**. They are the engine's
 * *printer* language: a rendering of an internal node, with its own spacing, its own casts and its
 * own invented spellings. A SQL parser for the dialect is the wrong grammar for that text, and it
 * would drag an optional dependency into the one container built to need no IDE at all.
 *
 * So there is no tree. There is a scan into tokens, and a closure that decides each one.
 *
 * ### The residual runs toward replacement
 *
 * > **An unrecognised token anonymizes.** Never toward preservation.
 *
 * That direction is the whole safety argument, and it is **inherited rather than assumed**: a token
 * arrives inside a slot whose boundaries the engine's own escaping already fixed, so anonymizing it
 * is safe by construction. What it costs is fidelity — a printer word nobody here anticipated comes
 * out as `col4`, which is visible in the pane the user is reading.
 *
 * ### Delimitation replaces precedence
 *
 * With no PSI there is no composite to override a leaf, so *is this token a name* is answered by the
 * engine's own quoting rule instead:
 *
 * > **A delimited token is always a name. A bare token is preserved only where the engine's quoting
 * > rule is shown to delimit every colliding identifier.**
 *
 * The second clause is argued per engine from its published quoting rules — see [POSTGRES] for
 * PostgreSQL's — and never measured: a corpus covers the names an engine *invents*, not every user
 * name that might collide with a keyword.
 *
 * ### A field fails whole
 *
 * > **If the lexer cannot scan a field soundly, the field is never parsed and is always one redacted
 * > literal** — however cleanly it appears to parse.
 *
 * Whole-field, never partial. A misparse is not a parse failure and nothing signals it, so
 * classifying the whole *input* is not enough on its own: the check has to happen **inside** fields
 * too, which is what [PlanScan.sound] is. See [PlanTreatments.unreadable] for what becomes of one.
 */
internal fun scanOf(slot: PlanSlot): PlanScan {
    val tokens = mutableListOf<PlanToken>()
    var at = slot.start
    while (at < slot.end) {
        val character = slot.at(at)
        when {
            character.isWhitespace() -> at++

            character == '\'' || character == '"' -> {
                // **The one thing that makes a field unscannable**, and the only one: a quote the
                // engine opened and this field never closes. PostgreSQL doubles a quote inside a
                // quoted thing and escapes nothing else, so a value carrying a newline is printed
                // raw and the field simply stops mid-literal. There is no sound reading of that, and
                // the answer is to stop reading rather than to guess where it ended.
                val closing = closingQuote(slot, at, character) ?: return PlanScan(emptyList(), sound = false)
                val kind = if (character == '\'') PlanTokenKind.LITERAL else PlanTokenKind.DELIMITED
                tokens += tokenIn(slot, kind, at, closing + 1, at + 1, closing)
                at = closing + 1
            }

            character.isDigit() -> {
                val end = endOfNumber(slot, at)
                tokens += tokenIn(slot, PlanTokenKind.NUMBER, at, end)
                at = end
            }

            opensAWord(character) -> {
                val end = slot.endOfWordAt(at)
                tokens += tokenIn(slot, PlanTokenKind.WORD, at, end)
                at = end
            }

            else -> {
                // `::` is one mark rather than two, because a cast is a thing the reader asks about.
                val end = if (slot.startsWith(CAST, at)) at + CAST.length else at + 1
                tokens += tokenIn(slot, PlanTokenKind.MARK, at, end)
                at = end
            }
        }
    }
    return PlanScan(tokens, sound = true)
}

/**
 * **What the lexer made of one field**: its tokens, and whether they can be trusted at all.
 *
 * @param sound whether the field's own quoting closed inside the field. **False means the tokens are
 *   empty and the field is never parsed** — not that some of it was read. See [scanOf].
 */
internal class PlanScan(val tokens: List<PlanToken>, val sound: Boolean)

/**
 * One token of an expression field, with **every offset the plan's own**.
 *
 * @param text the token exactly as written, delimiters included — which is what a key is made of, so
 *   `"Customers"` and `customers` are two symbols and neither is folded into the other
 * @param nameStart where the token's name or content starts: the whole token for a bare word, and
 *   after the opening delimiter for a quoted one. See [PlanOccurrence.nameStart].
 * @param nameEnd where it ends, before the closing delimiter
 */
internal class PlanToken(
    val kind: PlanTokenKind,
    val start: Int,
    val end: Int,
    val nameStart: Int,
    val nameEnd: Int,
    val text: String,
) {

    /** Whether this is the `.` that joins two segments of a qualified name. */
    val isDot: Boolean get() = isMark(".")

    /** Whether this token is a name slot — a word or a delimited identifier. */
    val isName: Boolean get() = kind == PlanTokenKind.WORD || kind == PlanTokenKind.DELIMITED

    /** Whether this token is the mark [written], which is how a reader asks about punctuation. */
    fun isMark(written: String): Boolean = kind == PlanTokenKind.MARK && text == written
}

/**
 * **What a token is, by its shape alone** — never by what it might mean.
 *
 * The kinds are what the closure dispatches on, and each is decided by the character that opened the
 * token. Nothing here looks a word up; that is the vocabulary's job, one layer out.
 */
internal enum class PlanTokenKind {

    /** A bare identifier-shaped word: `status`, `count`, `AND`, `SubPlan`. */
    WORD,

    /** `"Customers"` — a delimited identifier, which **is always a name**. See [scanOf]. */
    DELIMITED,

    /** `'open'` — a value the planner printed. Its content is the user's, never the engine's. */
    LITERAL,

    /** `42`, `1.5`, `25kB` — shape rather than domain, and preserved as printed. */
    NUMBER,

    /** An operator, a bracket, a comma, a dot, a `::`. Punctuation survives by not being reported. */
    MARK,
}

/**
 * **The words and phrases one engine's printer writes that are the engine's own** — and the argument,
 * per engine, for why preserving each of them cannot preserve a user's name.
 *
 * Three populations, because they are protected by three different arguments and a reader who blurs
 * them will add a row to the wrong one:
 *
 *  - [words] — **protected by the engine's quoting rule.** Every spelling here is one that the
 *    engine cannot print bare for a user's identifier, so preserving it is safe by construction.
 *  - [builtins] — **the stated leak surface.** A builtin function or type name is printed exactly as
 *    a user's object of the same name would be, so a user function shadowing a builtin is preserved.
 *    A row this list holds is an exposure and a row it omits costs only fidelity, which is the
 *    asymmetry that makes this the one thing here that is enumerated rather than derived — and why a
 *    later ticket's oracle must **never subtract from it**.
 *  - [phrases] — **protected more strongly than any token.** An identifier containing a space must
 *    be delimited in every engine this product reads, so a fixed multi-word phrase cannot collide
 *    with a bare name at all. That is also why the vocabulary holds phrases in the first place: a
 *    token-only vocabulary renders `(hashed SubPlan 2).col1` as `(str7 SubPlan 2).col1` and throws
 *    away the plan.
 *
 * **Matching is case-sensitive, and that is load-bearing rather than tidy.** The printer's spelling
 * is the thing being recognised; `SubPlan` is safe precisely *because* it is not a spelling an
 * identifier can be printed bare under.
 *
 * Rejected, so they are not proposed again: anonymizing every alphabetic token, which renders
 * `Filter: (str1 str2 str3)`; masking every function and type name, which needs no list and carries
 * no exposure but loses the reason the field is readable at all; and shipping the list for two
 * engines only, resting the others on an unmeasured claim that they print no functions.
 *
 * @param constants the spellings that are **values** rather than names — `NULL`, `true`, `false`.
 *   Read only where a slot is known to hold one value; see [PlanTreatments.parameters].
 */
internal class PlanVocabulary(
    val words: Set<String>,
    val builtins: Set<String>,
    val constants: Set<String>,
    phrases: List<String>,
) {

    /** The phrases as token sequences, longest first, so the longest spelling wins its prefix. */
    private val phrases: List<List<String>> = phrases.map { it.split(' ') }.sortedByDescending { it.size }

    /** Whether this bare word is the engine's own. See [words] and [builtins]. */
    fun knows(word: String): Boolean = word in words || word in builtins

    /**
     * How many cells the phrase starting at [at] covers, or zero where none does.
     *
     * **A phrase may not span a name slot**, which is the whole of the two guards: a word sitting
     * after a dot is a segment of somebody's qualified name and a word followed by a dot is a
     * qualifier of one, so a phrase reaching either would preserve a name in the middle of a
     * spelling it recognised.
     */
    fun phraseAt(tokens: List<PlanToken>, at: Int): Int {
        if (at > 0 && tokens[at - 1].isDot) return 0

        for (phrase in phrases) {
            if (at + phrase.size > tokens.size) continue
            if (phrase.indices.any { tokens[at + it].kind != PlanTokenKind.WORD || tokens[at + it].text != phrase[it] }) {
                continue
            }
            val after = at + phrase.size
            if (after < tokens.size && tokens[after].isDot) continue
            return phrase.size
        }
        return 0
    }
}

/**
 * **PostgreSQL's rider** — the argument that lets a bare token be preserved in this engine's plans,
 * written from its published quoting rules rather than measured from a corpus.
 *
 * `EXPLAIN` prints every identifier through `quote_identifier()`, which delimits an identifier
 * unless it is **all lower case**, made only of `a-z0-9_`, does not begin with a digit, **and is not
 * a keyword outside the unreserved category**. Two consequences, and they are the two halves of
 * [PlanVocabulary.words]:
 *
 *  - **A spelling carrying an upper-case letter can never be a bare identifier.** `AND`, `NULL`,
 *    `CASE`, `SubPlan`, `InitPlan`, `NaN` are therefore safe as written, whatever a user has named
 *    something: an identifier spelled that way is printed `"SubPlan"`, and a delimited token is
 *    always a name.
 *  - **A reserved, `col_name` or `type_func_name` keyword is delimited too**, which is what covers
 *    the handful of lower-case spellings here — `true` and `false` are reserved words, so a column
 *    called either prints quoted.
 *
 * **The unreserved keywords are deliberately absent**, and that absence is the rider doing its work:
 * PostgreSQL does *not* quote `zone`, `varying`, `without` or `first`, so a column named one of them
 * prints bare and preserving the bare spelling would put it on the clipboard. Every one of them
 * reaches the output through a **phrase** instead, where the space argues what the spelling cannot.
 *
 * [PlanVocabulary.builtins] is the exception to all of it and is stated as such: PostgreSQL prints a
 * user's `count` function exactly as it prints its own, so that name is preserved and the exposure is
 * recorded rather than argued away.
 */
internal val POSTGRES = PlanVocabulary(
    words = setOf(
        // The printer's keywords, upper case as it writes them — and safe for that reason alone.
        "AND", "OR", "NOT", "IS", "ISNULL", "NOTNULL", "NULL", "IN", "EXISTS", "LIKE", "ILIKE",
        "SIMILAR", "TO", "BETWEEN", "SYMMETRIC", "ASYMMETRIC", "CASE", "WHEN", "THEN", "ELSE", "END",
        "ANY", "ALL", "SOME", "AS", "ASC", "DESC", "NULLS", "FIRST", "LAST", "DISTINCT", "ON",
        "USING", "COLLATE", "ARRAY", "ROW", "CAST", "INTERVAL", "AT", "TIME", "ZONE", "LOCAL", "FOR",
        "FROM", "WITH", "WITHOUT", "ORDER", "BY", "GROUP", "PARTITION", "OVER", "RANGE", "ROWS",
        "GROUPS", "UNBOUNDED", "PRECEDING", "FOLLOWING", "CURRENT", "EXCLUDE", "TIES", "OTHERS",
        "FILTER", "WHERE", "ESCAPE", "OPERATOR", "GROUPING", "SETS", "ROLLUP", "CUBE", "WITHIN",
        "VARIADIC", "ORDINALITY", "LATERAL", "TRUE", "FALSE", "UNKNOWN", "DEFAULT", "VALUES", "KEY",
        // The names the planner invents for the pieces of a plan, spelled as it invents them.
        "SubPlan", "InitPlan", "NaN", "Infinity",
        // The constants, lower case as the printer writes them — reserved words, so still delimited
        // wherever a user names something one of them.
        "true", "false",
    ),
    builtins = setOf(
        // Types, which is what follows a cast.
        "bool", "boolean", "bytea", "char", "bpchar", "varchar", "text", "name", "int", "int2",
        "int4", "int8", "smallint", "integer", "bigint", "numeric", "decimal", "real", "float",
        "float4", "float8", "money", "date", "timestamp", "timestamptz", "timetz", "time",
        "interval", "uuid", "json", "jsonb", "jsonpath", "xml", "inet", "cidr", "macaddr",
        "macaddr8", "oid", "regclass", "regtype", "regproc", "record", "void", "bit", "varbit",
        "tsvector", "tsquery", "point", "lseg", "line", "box", "path", "polygon", "circle",
        "int4range", "int8range", "numrange", "tsrange", "tstzrange", "daterange", "anyarray",
        // The functions an engine prints into a plan of its own accord.
        "abs", "age", "array_agg", "array_append", "array_cat", "array_length", "array_position",
        "array_remove", "array_to_string", "avg", "btrim", "cardinality", "ceil", "ceiling",
        "char_length", "character_length", "coalesce", "concat", "concat_ws", "corr", "count",
        "cume_dist", "current_date", "current_schema", "current_time", "current_timestamp",
        "current_user", "date_part", "date_trunc", "decode", "degrees", "dense_rank", "div",
        "encode", "every", "exp", "extract", "first_value", "floor", "format", "generate_series",
        "greatest", "initcap", "json_agg", "json_build_object", "jsonb_agg", "jsonb_build_object",
        "jsonb_object_agg", "lag", "last_value", "lead", "least", "left", "length", "ln", "localtime",
        "localtimestamp", "log", "lower", "lpad", "ltrim", "make_date", "make_timestamp", "max",
        "md5", "min", "mod", "mode", "now", "nth_value", "ntile", "nullif", "num_nonnulls",
        "octet_length", "overlay", "percent_rank", "percentile_cont", "percentile_disc", "pi",
        "position", "power", "radians", "random", "rank", "regexp_matches", "regexp_replace",
        "regexp_split_to_array", "regexp_split_to_table", "repeat", "replace", "reverse", "right",
        "round", "row_number", "rpad", "rtrim", "session_user", "sign", "split_part", "sqrt",
        "starts_with", "stddev", "stddev_pop", "stddev_samp", "string_agg", "string_to_array",
        "strpos", "substr", "substring", "sum", "timeofday", "to_char", "to_date", "to_hex",
        "to_json", "to_jsonb", "to_number", "to_timestamp", "translate", "trim", "trunc", "unnest",
        "upper", "var_pop", "var_samp", "variance", "version", "width_bucket", "nextval", "currval",
        "setval", "pg_typeof",
    ),
    constants = setOf("NULL", "true", "false", "TRUE", "FALSE"),
    phrases = listOf(
        // The printer's own multi-word spellings, and the multi-word type names. Every one of them
        // carries a word that is unsafe alone — `hashed`, `zone`, `varying`, `precision`, `with`,
        // `without` — which is exactly why it is here rather than in `words`.
        "hashed SubPlan",
        "double precision",
        "character varying",
        "bit varying",
        "timestamp with time zone",
        "timestamp without time zone",
        "time with time zone",
        "time without time zone",
    ),
)

/** Where a quoted thing closes, or `null` where this field never closes it. See [scanOf]. */
private fun closingQuote(slot: PlanSlot, at: Int, quote: Char): Int? {
    var next = at + 1
    while (next < slot.end) {
        if (slot.at(next) == quote) {
            // A doubled quote is the character itself, which is how every engine here escapes one.
            if (next + 1 < slot.end && slot.at(next + 1) == quote) next++ else return next
        }
        next++
    }
    return null
}

/**
 * Where the number starting at [at] ends — digits, a fractional part, and **the unit a plan writes
 * against one**: `25kB` is one token to a reader and two to a scanner.
 */
private fun endOfNumber(slot: PlanSlot, at: Int): Int {
    var end = at
    while (end < slot.end) {
        val character = slot.at(end)
        when {
            character.isLetterOrDigit() || character == '_' -> end++
            character == '.' && end + 1 < slot.end && slot.at(end + 1).isDigit() -> end++
            else -> return end
        }
    }
    return end
}

/** One token, with the plan's offsets and the text it is written with. */
private fun tokenIn(
    slot: PlanSlot,
    kind: PlanTokenKind,
    start: Int,
    end: Int,
    nameStart: Int = start,
    nameEnd: Int = end,
) = PlanToken(kind, start, end, nameStart, nameEnd, slot.narrowed(start, end).written)

/**
 * Whether a token can start here — a letter or an underscore, as every engine's identifiers do, and
 * as Unicode defines a letter rather than as ASCII would.
 */
internal fun opensAWord(character: Char): Boolean = character.isLetter() || character == '_'


/** The cast mark, which is punctuation and never a name. */
internal const val CAST = "::"
