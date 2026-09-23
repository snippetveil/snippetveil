package com.snippetveil.core

/**
 * **The reading of a structured plan** — one walk over [PlanNode]s against the field inventory,
 * serving JSON, YAML and XML alike.
 *
 * The three formats differ only in how a document is spelled, and [PlanDocument] has already absorbed
 * that. What is left is the part that is the same for all three and is the part that matters: **every
 * entry is looked up in the inventory, and an entry the inventory does not hold refuses the input.**
 * See [PlanTreatment] for the closure and for what it costs.
 *
 * @param naming how a label written in the document is spelled in the inventory — the identity for
 *   JSON and YAML, and [POSTGRES_XML_LABELS] for XML, which writes `Node Type` as `Node-Type`.
 * @param quote how the format writes a double quote **inside a scalar** — `\"` where the format
 *   escapes it, `"` where it does not. Exactly one rule reads it, and it is the rule that has to
 *   find the boundaries of a `search_path` element. See [searchPathIn].
 */
internal class PlanStructureReader(
    private val symbols: PlanSymbols,
    private val naming: (String) -> String,
    private val quote: String,
) {

    /** One query, read against the top of the inventory. */
    fun readQuery(query: PlanMapping) = readMapping(query, POSTGRES_QUERY_FIELDS)

    /**
     * **Every entry of one mapping, against one field set** — and the closure, which is the whole of
     * what this function is.
     *
     * A label the field set does not hold is not read, not skipped and not guessed at: the input
     * refuses, with the general verdict and no recourse. See [PlanTreatment].
     */
    private fun readMapping(mapping: PlanMapping, fields: Map<String, PlanTreatment>) {
        for (entry in mapping.entries) {
            val treatment = fields[naming(entry.label)] ?: throw PlanRefusal(PlanReading.Unreadable)
            read(entry.value, treatment)
        }
    }

    private fun read(node: PlanNode, treatment: PlanTreatment) {
        when (treatment) {
            is PlanTreatment.Subtree -> when (node) {
                is PlanMapping -> readMapping(node, treatment.fields())
                is PlanSequence -> node.items.forEach { read(it, treatment) }
                // A container printed as a scalar is the empty list a format writes with no members.
                is PlanScalar -> Unit
            }

            PlanTreatment.SettingsMap -> when (node) {
                is PlanMapping -> readSettings(node)
                is PlanSequence -> node.items.forEach { read(it, treatment) }
                is PlanScalar -> Unit
            }

            else -> eachScalar(node) { readScalar(it, treatment) }
        }
    }

    /**
     * One scalar, handed to the treatment its label named.
     *
     * A **container** treatment reaching here is a document whose shape contradicts its own
     * inventory row — the arms in [read] have already taken every container this reader understands
     * — so it refuses rather than reading the tree under it as a value.
     */
    private fun readScalar(scalar: PlanScalar, treatment: PlanTreatment) {
        if (!symbols.readSlot(scalar.content, treatment)) throw PlanRefusal(PlanReading.Unreadable)
    }

    /**
     * **The settings map, typed by key** — the one place in this product where an unknown label does
     * **not** refuse, and the reason it does not is worth stating next to the reason a field does.
     *
     * A settings map is a slot whose *shape* the inventory already fixed: it is a map of engine
     * settings to their values, and that is known before a single key is read. What is open is only
     * which settings a release flags — so an unknown key is a **compatibility event inside a known
     * field** rather than an unknown field, and masking it leaks nothing while refusing would break
     * every plan from a server with one extension loaded.
     *
     * Three rules, and each is a different question:
     *
     *  - **`search_path` holds schema names**, and they join the identity of every other schema in
     *    the plan. See [searchPathIn].
     *  - **A core setting this release flags is an engine fact**, preserved behind a shape check.
     *    These are the settings that actually explain a plan choice, which is why the rejected
     *    alternative — keep every key, mask every value — was refused: it throws away the answer the
     *    plan was pasted for.
     *  - **Anything else has its key and its value masked.** A key nothing here can type might be a
     *    private extension's, and an extension's name and an extension's value are both the
     *    deployment's rather than the engine's — so neither is preserved and neither joins name
     *    identity. The placeholder is `str1`, which is a legal XML element name and a legal YAML key,
     *    so a masked key leaves the document still readable as the format it arrived in.
     */
    private fun readSettings(mapping: PlanMapping) {
        for (entry in mapping.entries) {
            when (entry.label) {
                SEARCH_PATH -> eachScalar(entry.value) {
                    symbols.occurrences += searchPathIn(symbols, it.content, quote)
                }

                in POSTGRES_FLAGGED_SETTINGS -> eachScalar(entry.value) {
                    symbols.occurrences += PlanTreatments.engineFact(it.content, PlanShapes.SETTING)
                }

                else -> {
                    for (name in entry.names) symbols.occurrences += PlanTreatments.deployment(name)
                    eachScalar(entry.value) { symbols.occurrences += PlanTreatments.deployment(it.content) }
                }
            }
        }
    }

    /**
     * Every scalar under [node], which is one for a plain value and several for a list.
     *
     * A **mapping** reaching here is a slot the inventory said holds a value and the document filled
     * with a tree, so it refuses — the fail-closed direction, and the same answer the field closure
     * gives to everything it does not recognise.
     */
    private fun eachScalar(node: PlanNode, read: (PlanScalar) -> Unit) {
        when (node) {
            is PlanScalar -> read(node)
            is PlanSequence -> node.items.forEach { eachScalar(it, read) }
            is PlanMapping -> throw PlanRefusal(PlanReading.Unreadable)
        }
    }
}

/**
 * **A `search_path`, read as the list of schema names it is** — so a schema named on the path shares
 * its placeholder with the same schema named in a qualified relation.
 *
 * That sharing is the whole reason this is not one more masked value. A plan that says
 * `Schema: billing` and a `search_path` that says `billing` are talking about one thing, and a reader
 * who sees `schema1` in one place and `str4` in the other has lost the fact that the relation was
 * found *because* of the path.
 *
 * Three element shapes and nothing else:
 *
 *  - **A bare element is a schema name.** PostgreSQL's own quoting rule prints an identifier bare
 *    only when it is lower case, so a bare element is unambiguous.
 *  - **A quoted element is a schema name written inside its delimiters** — with one exception.
 *  - **`"$user"` survives verbatim, by the value grammar** rather than by appearing on any list of
 *    known spellings: the engine always substitutes the session user for that spelling, so no schema
 *    can be named onto the path by it and there is nothing of the user's in it. See
 *    [SESSION_USER_ELEMENT].
 *
 * **A value that does not split cleanly is masked, whole.** Partial understanding of a list is how
 * the part nobody looked at leaves the machine, so the elements are read first and committed only if
 * every one of them read.
 *
 * @param symbols the reading the rest of the plan is going through — because the sharing above is
 *   **the key rule agreeing with itself**, and a second copy of that rule here is exactly where the
 *   two would drift apart. See [PlanSymbols.keyOf].
 * @param quote how this format writes a double quote inside a scalar, which is what says where a
 *   quoted element begins and ends. See [PlanStructureReader].
 */
internal fun searchPathIn(symbols: PlanSymbols, slot: PlanSlot, quote: String): List<PlanOccurrence> {
    if (slot.isBlank) return emptyList()

    val occurrences = mutableListOf<PlanOccurrence>()
    for (element in elementsOf(slot, quote) ?: return PlanTreatments.unreadable(slot)) {
        val quoted = element.written.length >= 2 * quote.length &&
            element.written.startsWith(quote) &&
            element.written.endsWith(quote)

        val name = if (quoted) {
            element.narrowed(element.start + quote.length, element.end - quote.length)
        } else {
            element
        }
        val written = name.written

        when {
            quoted && written == SESSION_USER_ELEMENT ->
                occurrences += PlanOccurrence(element.start, element.end, PlanDisposition.Preserve)

            written.isEmpty() -> return PlanTreatments.unreadable(slot)

            quoted || written.matches(BARE_SCHEMA) -> occurrences += PlanOccurrence(
                name.start,
                name.end,
                PlanDisposition.Anonymize(SymbolRole.SCHEMA, symbols.keyOf(SymbolRole.SCHEMA, written)),
            )

            else -> return PlanTreatments.unreadable(slot)
        }
    }
    return occurrences
}

/**
 * The comma-separated elements of [slot], with the whitespace around each taken off — and `null`
 * where a quoted element never closes, which is a value that does not split at all.
 */
private fun elementsOf(slot: PlanSlot, quote: String): List<PlanSlot>? {
    val elements = mutableListOf<PlanSlot>()
    var from = slot.start
    var at = slot.start
    var inside = false
    while (at < slot.end) {
        when {
            slot.startsWith(quote, at) -> {
                inside = !inside
                at += quote.length
            }

            !inside && slot.at(at) == ',' -> {
                elements += slot.narrowed(from, at).trimmed()
                at++
                from = at
            }

            else -> at++
        }
    }
    if (inside) return null
    elements += slot.narrowed(from, slot.end).trimmed()
    return elements
}

/** What an unquoted `search_path` element may be spelled with. See [searchPathIn]. */
private val BARE_SCHEMA = Regex("""[A-Za-z_][A-Za-z0-9_]*""")
