package com.snippetveil.core

/**
 * **The treatment classes a plan's fields are written against** — one per kind of thing an engine
 * prints, and the whole of what a format's field inventory has to choose between.
 *
 * They live here rather than beside PostgreSQL's text format because **every format's inventory is
 * written against them**: four engines and, for PostgreSQL alone, four output formats, each of which
 * says only *this field is a name of that kind, that one is a measured quantity, this one is echoed
 * query text*. A class defined per format would be a class re-argued per format, and the argument is
 * the part that has to hold.
 *
 * Each class answers one question — *what may leave the machine here* — and the answers are:
 *
 *  - [measured] — **preserved as printed.** Row counts, cardinalities, timings, buffers.
 *  - [engineFact] — **preserved behind a shape check.** A version, an enum of the engine's own.
 *  - [echoedQuery] — **one redacted literal, never parsed.**
 *  - [parameters] — each value **is a literal**, or one redacted literal if it is not exactly one.
 *  - [identifying] — **masked**, and equal spellings share a token.
 *  - [deployment] — **masked**, and it does not join name identity.
 *  - [unreadable] — **one redacted literal**, for a field the lexer cannot scan soundly.
 *
 * **Every mask draws on the existing redacted-literal family and its counter**, which is the one
 * decision that keeps this from growing an output surface. A family per class would make every class
 * name a forever-surface in text a model reads, and a new family serves no debugging purpose that
 * `str1` does not already serve. See [PlanDisposition.Mask].
 *
 * So four of the classes here share one line of body, deliberately: **what a class is, is its
 * argument**, and the argument is what a field inventory cites when it picks one. Collapsing them
 * into a single `mask()` would save four lines and leave every inventory row saying only *masked*,
 * with the reason it is masked — and therefore whether the reason still holds — nowhere.
 */
internal object PlanTreatments {

    /**
     * **A measured quantity, preserved exactly as the engine printed it** — and *nothing is reported
     * at all*, which is the treatment rather than an omission.
     *
     * A plan is pasted **for** its numbers. Row counts, cardinalities, timings, buffers and memory
     * are the answer the user wants from the model, and an anonymizer that moved one would have
     * destroyed the reason the plan was sent.
     *
     * **They are a disclosure, stated and not closed.** Numbers describe the data: a row count is a
     * cardinality, a timing is a workload. The recourse is at the engine — run the plan with fewer
     * numbers — and there is deliberately **no mask toggle, no bucketing, no notice and no count**.
     * Bucketing is refused twice over: it keeps the magnitude anyway, and it has this product
     * **write a number the engine never printed**, which is the one thing no surface here does.
     *
     * Reporting nothing is what makes the no-count half true by construction: a measured field
     * produces no occurrence, so there is nothing for a count or a notice to be computed from.
     */
    fun measured(): List<PlanOccurrence> = emptyList()

    /**
     * **An engine fact, preserved behind a shape check** — a dotted version, an enum the engine owns.
     *
     * **An off-shape value is masked, not refused.** Once a slot is known to be engine-owned,
     * masking a surprise leaks nothing; refusing would break every plan from a vendor release that
     * changed a version string's shape, which is a compatibility event this container is expected to
     * survive rather than a disclosure decision.
     */
    fun engineFact(slot: PlanSlot, shape: Regex): List<PlanOccurrence> {
        val value = slot.trimmed()
        if (value.isBlank) return emptyList()
        if (!shape.matches(value.written)) return maskOf(value)
        return listOf(PlanOccurrence(value.start, value.end, PlanDisposition.Preserve))
    }

    /**
     * **Echoed query text: one redacted literal, and it is never parsed.**
     *
     * It is the user's own SQL — the statement the plan was produced for, or the statement an engine
     * is about to send somewhere else — and parsing it would need exactly the dialect grammar this
     * container refuses to carry. Masking it whole is the only reading that does not guess.
     */
    fun echoedQuery(slot: PlanSlot): List<PlanOccurrence> = maskOf(slot.trimmed())

    /**
     * **A number or string that identifies, masked** — a query hash, a statement id, a plan digest.
     *
     * Preserving one is refused on a concrete attack rather than on principle: a hash derived from
     * the query text lets a receiver **confirm a guessed query**, which defeats the rule that the
     * query text is redacted rather than stating a gap next to it.
     *
     * **Equal spellings share a token**, so within-plan correlation survives the mask: two nodes
     * carrying one identifier still read as two nodes carrying one identifier. That falls out of the
     * key rather than being arranged — see [PlanKeys.masked].
     */
    fun identifying(slot: PlanSlot): List<PlanOccurrence> = maskOf(slot.trimmed())

    /**
     * **A deployment or object identifier, masked** — a hostname, a database user, a service or
     * cluster name, a custom-scan provider, a trigger, a constraint.
     *
     * These are precisely the organization-identifying strings this product exists to replace, and
     * they are **not names**: they do not join name identity, so a database user spelled like a
     * schema does not share that schema's placeholder. The key is what says so, and it says so by
     * being a different key rather than by a rule somebody has to keep. See [PlanKeys.masked].
     *
     * **A database object outside the four name kinds lands here too, and lands here deliberately.**
     * A trigger is not a rowset, a column, a schema or an access path, so there is no placeholder
     * kind that would be true of it — and giving it a *false* kind would have a reader looking for a
     * table that does not exist. Masking says the one true thing: something was named here.
     */
    fun deployment(slot: PlanSlot): List<PlanOccurrence> = maskOf(slot.trimmed())

    /**
     * **A field the lexer could not scan soundly: one redacted literal, whole.**
     *
     * Whole-field and never partial, because a misparse is not a parse failure — nothing signals it,
     * and a field that appears to parse cleanly is exactly the case this exists for. See [scanOf].
     *
     * **It is the one mask that says why**, through [PlanOccurrence.unscannable] — the same output
     * as [appendedRaw] and [deployment] produce, marked so that the corpus instrument can report how
     * often the residual rule costs a whole field. The mark is a reason and never an outcome:
     * nothing branches on it, here or anywhere.
     */
    fun unreadable(slot: PlanSlot): List<PlanOccurrence> = maskOf(slot.trimmed(), unscannable = true)

    /**
     * **A field whose names the engine appended raw: one redacted literal, whole.**
     *
     * The same one literal [unreadable] produces, and it is a separate function because the two are
     * separate claims. *This did not scan* is a fact about one field's quoting; *this engine prints
     * identifiers with nothing around them* is a fact about the engine, and a reader who found only
     * the first would go looking for the parse failure that never happened. See [PlanTreatment.AppendedRaw].
     */
    fun appendedRaw(slot: PlanSlot): List<PlanOccurrence> = maskOf(slot.trimmed())

    /**
     * **An unescaped field, admitted only for a value the engine itself writes** — and **an off-list
     * value refuses the input**, rather than being masked.
     *
     * Masking is what [engineFact] does, and it is right there because the value arrived inside
     * delimiters the engine maintained: the worst an unrecognised value can be is a value, and
     * replacing it costs fidelity and leaks nothing. **Here there are no delimiters to trust.** The
     * engine wrote the value into the document without escaping it, so a value that is not one the
     * engine can write is a value that may have closed its own slot and written siblings of its own
     * — and a mask replaces a range, not an invented neighbour it never saw.
     *
     * So the check is not a shape over the value; it is a question about the writer. See
     * [PlanTreatment.Unescaped].
     *
     * @throws PlanRefusal where the value is not one this engine release writes
     */
    fun unescaped(slot: PlanSlot, admits: (String) -> Boolean): List<PlanOccurrence> {
        val value = slot.trimmed()
        if (value.isBlank) return emptyList()
        if (!admits(value.written)) throw PlanRefusal(PlanReading.Unreadable)
        return listOf(PlanOccurrence(value.start, value.end, PlanDisposition.Preserve))
    }

    /**
     * **A value the planner printed, inside an expression** — masked, content only, delimiters left
     * where they were.
     *
     * `'ACTIVE'` becomes `'str1'`: the literal stays a literal, so the field still reads as the
     * predicate it is, and the value — an address, an account id, a token — does not reach the
     * clipboard.
     *
     * An **empty** literal is nothing to mask, by the same rule that leaves an empty slot alone
     * below: there is no content to carry a domain, and `= ''` silently becoming `= 'str1'` would
     * have this product assert a value where the engine printed none — *plausible* rather than
     * obvious, which is the prohibited class. It is where the Java literal rule lands on `""` too.
     */
    fun literal(token: PlanToken): List<PlanOccurrence> {
        if (token.nameEnd <= token.nameStart) return emptyList()
        return listOf(maskOver(token.start, token.end, token.nameStart, token.nameEnd, token.text))
    }

    /**
     * **A parameter list, where each value is a literal** — `$1 = 'acme', $2 = 42`.
     *
     * > A parameter value **is a literal** — exactly one literal, or **one redacted literal** if it
     * > is not exactly one.
     *
     * The second half is the whole of the rule's safety: a value that does not read as one literal
     * is a slot this reader does not understand, and understanding it partially is how a value
     * leaves in the part nobody looked at. A number survives as printed, a constant the vocabulary
     * knows survives as written, and everything else is masked.
     *
     * **The list's own shape is read strictly, and a field that does not have it fails whole.** The
     * reader looks for `$n = value`, comma-separated, and anything else — a list with no `=` in it
     * at all, a value with nothing after the `=`, a shape from a release nobody here has seen — is
     * one redacted literal. Walking past what it did not recognise is how the residual would come to
     * run toward preservation in the one field whose rule is *or one redacted literal*.
     */
    fun parameters(slot: PlanSlot, vocabulary: PlanVocabulary): List<PlanOccurrence> {
        val scan = scanOf(slot)
        if (!scan.sound) return unreadable(slot)

        val tokens = scan.tokens
        val occurrences = mutableListOf<PlanOccurrence>()
        var at = 0
        while (at < tokens.size) {
            val opensAParameter = at + 2 < tokens.size &&
                tokens[at].isMark("$") &&
                tokens[at + 1].kind == PlanTokenKind.NUMBER &&
                tokens[at + 2].isMark("=")
            if (!opensAParameter) return unreadable(slot)

            // The value runs to the comma that separates this parameter from the next — at depth
            // zero, so a comma inside a row or an array constructor does not end it.
            var end = at + 3
            var depth = 0
            while (end < tokens.size) {
                val token = tokens[end]
                if (token.isMark("(") || token.isMark("[")) depth++
                if (token.isMark(")") || token.isMark("]")) depth--
                if (token.isMark(",") && depth <= 0) break
                end++
            }
            if (end == at + 3) return unreadable(slot)

            occurrences += valueOf(slot, tokens.subList(at + 3, end), vocabulary)
            at = end + 1
        }
        return occurrences
    }

    /** What becomes of one parameter's value, which is never empty. See [parameters]. */
    private fun valueOf(slot: PlanSlot, value: List<PlanToken>, vocabulary: PlanVocabulary): List<PlanOccurrence> {
        val only = value.singleOrNull()
        if (only != null) {
            when {
                only.kind == PlanTokenKind.LITERAL -> return literal(only)
                only.kind == PlanTokenKind.NUMBER -> return emptyList()
                only.kind == PlanTokenKind.WORD && only.text in vocabulary.constants ->
                    return listOf(PlanOccurrence(only.start, only.end, PlanDisposition.Preserve))
            }
        }
        return maskOf(slot.narrowed(value.first().start, value.last().end))
    }

    /**
     * **The field, taken out of the output** — the one treatment here that removes rather than
     * replaces, and the whole of what the drop rule does.
     *
     * It reports **one occurrence with a [PlanDisposition.Drop]** over the range the caller gives it,
     * which is the *field's* range and not its value's: the name, the `=`, the quotes and the space
     * in front of them all go, because a field emitted as `len=""` would still say there was a
     * length. There is no allocation, no row and no counter movement behind it — see
     * [PlanDisposition.Drop] — so two fields carrying one number cannot share a token, which is the
     * reason the rule drops rather than masks. See [PlanTreatment.Dropped].
     */
    fun dropped(slot: PlanSlot): List<PlanOccurrence> {
        if (slot.start >= slot.end) return emptyList()
        return listOf(PlanOccurrence(slot.start, slot.end, PlanDisposition.Drop))
    }

    /**
     * **A declared type name, decomposed** — each word of the type preserved, each parenthesized size
     * dropped, and the whole field masked where it is not that shape.
     *
     * The grammar is closed and is read here rather than trusted: words of letters, digits and
     * underscores, single-spaced, each optionally carrying a size in parentheses. That is what makes
     * this a decomposition — the reader knows which characters are the type and which are the width —
     * rather than a substring taken out of a value nobody parsed.
     *
     * **A value that does not parse is masked whole**, the answer everything here gives a slot it did
     * not understand. Keeping the part that looked familiar is how the part nobody looked at leaves
     * the machine. **A word outside [names] does not parse**, which is what keeps a type somebody
     * wrote — an object type, a collection — from being emitted as written. See
     * [PlanTreatment.TypeName].
     */
    fun typeName(slot: PlanSlot, names: Set<String>): List<PlanOccurrence> {
        val value = slot.trimmed()
        if (value.isBlank) return emptyList()

        val occurrences = mutableListOf<PlanOccurrence>()
        var at = value.start
        while (at < value.end) {
            if (!opensAWord(value.at(at))) return maskOf(value)
            val word = value.endOfWordAt(at)
            if (value.narrowed(at, word).written !in names) return maskOf(value)
            occurrences += PlanOccurrence(at, word, PlanDisposition.Preserve)
            at = word

            if (at < value.end && value.at(at) == '(') {
                val close = closingParenthesis(value, at) ?: return maskOf(value)
                if (!SIZE.matches(value.narrowed(at + 1, close).written)) return maskOf(value)
                occurrences += PlanOccurrence(at, close + 1, PlanDisposition.Drop)
                at = close + 1
            }

            if (at == value.end) break
            // One space between the words of a type, and nothing else anywhere in it.
            if (value.at(at) != ' ') return maskOf(value)
            at++
        }
        return occurrences
    }

    /**
     * **The name a statement gave a bind, masked** — one redacted literal, whole.
     *
     * Its own function rather than a call to [deployment] because the two are separate claims: a
     * deployment identifier names something in the installation, and a bind name is the **statement's
     * own vocabulary**, routinely the column it filters written out beside the value the mask has
     * just taken away. See [PlanTreatment.BoundName].
     */
    fun boundName(slot: PlanSlot): List<PlanOccurrence> = maskOf(slot.trimmed())

    /**
     * **The value a statement bound, masked whole and untrimmed.**
     *
     * Untrimmed is the difference from every other mask here, and it is deliberate: the space around
     * a bound value is **part of the value**, and emitting it would state a fact about data this rule
     * exists to withhold. An empty value is nothing to mask, by the rule every empty slot takes.
     */
    fun boundValue(slot: PlanSlot): List<PlanOccurrence> {
        if (slot.start >= slot.end) return emptyList()
        return listOf(maskOver(slot.start, slot.end, slot.start, slot.end, slot.written))
    }

    /**
     * **A bound value whose bind is typed a number: preserved where it lexes as one, and one redacted
     * literal where it does not.**
     *
     * The shape is the guard rather than the type. A bind's type says which reading to try; whether
     * anything at all is emitted as written is decided by the value, which is what makes this
     * admissible as a branch of a value discriminator. See [PlanTreatment.BoundNumber].
     */
    fun boundNumber(slot: PlanSlot): List<PlanOccurrence> {
        if (slot.start >= slot.end) return emptyList()
        if (!PlanShapes.NUMBER.matches(slot.written)) return boundValue(slot)
        return listOf(PlanOccurrence(slot.start, slot.end, PlanDisposition.Preserve))
    }

    /** Where the parenthesized group opened at [at] closes, or `null` where it never does. */
    private fun closingParenthesis(slot: PlanSlot, at: Int): Int? {
        var next = at + 1
        while (next < slot.end) {
            if (slot.at(next) == ')') return next
            if (slot.at(next) == '(') return null
            next++
        }
        return null
    }

    /**
     * The whole of [slot], replaced by one redacted literal — or nothing, where there is nothing.
     *
     * @param unscannable whether this is the mask [unreadable] produces. See [PlanOccurrence].
     */
    private fun maskOf(slot: PlanSlot, unscannable: Boolean = false): List<PlanOccurrence> {
        if (slot.isBlank) return emptyList()
        return listOf(maskOver(slot.start, slot.end, slot.start, slot.end, slot.written, unscannable))
    }

    private fun maskOver(
        start: Int,
        end: Int,
        nameStart: Int,
        nameEnd: Int,
        written: String,
        unscannable: Boolean = false,
    ) = PlanOccurrence(
        start,
        end,
        PlanDisposition.Mask(PlanKeys.masked(written)),
        nameStart,
        nameEnd,
        unscannable,
    )

    /**
     * **What a declared type's parenthesized size may be written with** — a width, a precision and
     * scale, a character-length semantic, or the maximum marker a dialect writes.
     *
     * A shape rather than anything looser, because this is the half of a type name that is **dropped
     * unread**: it is the closure that says the characters being removed are a size and not something
     * else the engine printed in the same position.
     */
    private val SIZE = Regex("""(\d+|\*)(,\d+)?( (BYTE|CHAR))?""")
}

/**
 * **The shapes an engine fact is checked against** — each one a statement about a slot the engine
 * owns, never about a value a user wrote.
 *
 * A shape rather than a list of known values, because the list would have to be a per-release
 * vocabulary row and the failure of a missing row would be a refusal. A shape that no longer matches
 * masks instead, which is a fidelity loss the user can see. See [PlanTreatments.engineFact].
 */
internal object PlanShapes {

    /** A dotted version — `16`, `16.2`, `9.6.24`. */
    val VERSION = Regex("""\d+(\.\d+)*""")

    /**
     * **One of the engine's own enumerations** — `Seq Scan`, `Outer`, `Finalize`, `NoMovement`.
     *
     * Letters, digits and the single spaces the printer writes between the words of a node type. A
     * value with anything else in it is a slot this release has not seen, and it is masked.
     */
    val ENGINE_ENUM = Regex("""[A-Za-z][A-Za-z0-9]*( [A-Za-z0-9]+)*""")

    /**
     * **One of the engine's own enumerations written as a single token** — `index_lookup`, `eq_ref`,
     * `nested_loop`, `ALL`.
     *
     * The same statement [ENGINE_ENUM] makes for a printer that spaces the words of an enum, for one
     * that joins them with an underscore instead. A value carrying anything but letters, digits and
     * underscores is a slot this release has not seen, and it is masked.
     */
    val ENGINE_TOKEN = Regex("""[A-Za-z][A-Za-z0-9_]*""")

    /** **A boolean the engine printed**, lower case as every one of the four formats writes it. */
    val BOOLEAN = Regex("""true|false""")

    /**
     * **A boolean written the way XML Schema defines one** — `true`, `false`, `1`, `0`.
     *
     * A shape of its own rather than a widening of [BOOLEAN], because the two are claims about
     * different writers: PostgreSQL's printers write the word, and a document typed against
     * `xsd:boolean` may write either spelling for one attribute and the other for the next.
     */
    val XML_BOOLEAN = Regex("""true|false|0|1""")

    /** **A count the engine printed as a bare integer** — a trace flag's number, a declared length. */
    val COUNT = Regex("""\d+""")

    /**
     * **A number, as a value rather than as a measurement** — `42`, `-1.5`, `1.5E+02`.
     *
     * It is the shape a bound value is held to before any of it is emitted as written, so it is
     * written tightly: a sign, digits, one fractional part and one exponent. Anything else — a space,
     * a currency mark, a thousands separator, a second sign — is a value that is not a number, and it
     * is masked. See [PlanTreatments.boundNumber].
     */
    val NUMBER = Regex("""[-+]?(\d+(\.\d*)?|\.\d+)([eE][-+]?\d+)?""")

    /**
     * **A data type as a dialect writes one in a plan** — `int`, `varchar(10)`, `nvarchar(max)`,
     * `decimal(18,2)`.
     *
     * The parenthesised part is the slot's **declared width**, which is why this is a shape rather
     * than [ENGINE_TOKEN]: a type name alone would fail the check on every sized column and be
     * masked, which is the one field a reader chasing an implicit conversion has come for.
     *
     * The words are spaced the way [ENGINE_ENUM] spaces an enum's — single spaces between them and
     * none at either end — so `double precision` matches and a value trailing a space does not.
     */
    val DATA_TYPE = Regex("""[A-Za-z][A-Za-z0-9_]*( [A-Za-z0-9_]+)*(\((max|\d+(,\d+)?)\))?""")

    /**
     * **The value of a flagged setting** — `on`, `off`, `4MB`, `0.005`, `partition`.
     *
     * Deliberately broad, and the breadth is the point rather than a weakness: the slot is already
     * known to be a **core setting this release flags**, which is what the vocabulary row asserts, so
     * the shape's job is to catch a value that stopped looking like a setting at all — a quoted
     * string, a list, a path — and to mask it. See [POSTGRES_FLAGGED_SETTINGS].
     */
    val SETTING = Regex("""[-+]?[A-Za-z0-9_.]+""")
}
