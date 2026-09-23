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
     */
    fun unreadable(slot: PlanSlot): List<PlanOccurrence> = maskOf(slot.trimmed())

    /**
     * **A field whose names the engine appended raw: one redacted literal, whole.**
     *
     * The same one literal [unreadable] produces, and it is a separate function because the two are
     * separate claims. *This did not scan* is a fact about one field's quoting; *this engine prints
     * identifiers with nothing around them* is a fact about the engine, and a reader who found only
     * the first would go looking for the parse failure that never happened. See [PlanTreatment.Raw].
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

    /** The whole of [slot], replaced by one redacted literal — or nothing, where there is nothing. */
    private fun maskOf(slot: PlanSlot): List<PlanOccurrence> {
        if (slot.isBlank) return emptyList()
        return listOf(maskOver(slot.start, slot.end, slot.start, slot.end, slot.written))
    }

    private fun maskOver(start: Int, end: Int, nameStart: Int, nameEnd: Int, written: String) =
        PlanOccurrence(start, end, PlanDisposition.Mask(PlanKeys.masked(written)), nameStart, nameEnd)

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
     * **The value of a flagged setting** — `on`, `off`, `4MB`, `0.005`, `partition`.
     *
     * Deliberately broad, and the breadth is the point rather than a weakness: the slot is already
     * known to be a **core setting this release flags**, which is what the vocabulary row asserts, so
     * the shape's job is to catch a value that stopped looking like a setting at all — a quoted
     * string, a list, a path — and to mask it. See [POSTGRES_FLAGGED_SETTINGS].
     */
    val SETTING = Regex("""[-+]?[A-Za-z0-9_.]+""")
}
