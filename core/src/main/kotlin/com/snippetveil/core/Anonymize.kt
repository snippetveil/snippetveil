package com.snippetveil.core

/**
 * Rewrites a snippet so that nothing the project owns is named in it.
 *
 * Pure: no clock, no randomness, no I/O, no state outside its arguments. Called on a background
 * thread between a plan build and a clipboard write, and re-runnable at no cost — which is what
 * lets a concurrent write action cancel and restart the whole analysis without anything being lost.
 */
fun anonymize(
    plan: SnippetPlan,
    settings: AnonymizationSettings,
    ledger: LedgerSnapshot,
): AnonymizationResult {
    val symbols = plan.occurrences.filterIsInstance<SymbolOccurrence>().sortedBy { it.start }

    // The spine rule: anonymize a symbol iff its declaring file is project-owned. The JDK and
    // third-party libraries alike are preserved. An unresolved reference joins the anonymized set
    // by failing closed rather than by being owned — see [namespaceOf].
    val anonymized = symbols.filter { isAnonymized(it.symbol, settings) }

    val allocator = PlaceholderAllocator(ledger.nextNumber, namesSurviving(plan, anonymized))
    val placeholderByKey = LinkedHashMap<String, String>(ledger.placeholders)
    val allocated = LinkedHashMap<String, String>()

    // Allocation order is document order of first occurrence in the selection, which is what makes
    // the output read top to bottom. `anonymized` is sorted, and `getOrPut` fires once per key, so
    // the order falls out rather than being arranged.
    for (occurrence in anonymized) {
        placeholderByKey.getOrPut(occurrence.symbol.key) {
            allocator.next(namespaceOf(occurrence.symbol)).also { allocated[occurrence.symbol.key] = it }
        }
    }

    // Right to left, so that every replacement lands at an offset the ones before it have not
    // moved. Splicing into the original string is also why formatting comes out byte-perfect: no
    // PSI is mutated, no formatter is invoked, and nothing but the identifiers themselves changes.
    val text = StringBuilder(plan.text)
    for (occurrence in anonymized.asReversed()) {
        text.replace(occurrence.start, occurrence.end, placeholderByKey.getValue(occurrence.symbol.key))
    }

    val mapping = anonymized.associate { placeholderByKey.getValue(it.symbol.key) to it.symbol.declaredName }

    // Every unresolved name, once, in document order of first occurrence — the order the preview
    // dialog sorts its rows by. The placeholder is read back through [isAnonymized] rather than out
    // of the ledger, so a name preserved by this invocation reports no placeholder even when an
    // earlier invocation gave it one.
    val unknowns = symbols
        .filter { it.symbol.origin == SymbolOrigin.UNRESOLVED }
        .distinctBy { it.symbol.key }
        .map { occurrence ->
            UnknownName(
                key = occurrence.symbol.key,
                name = occurrence.symbol.declaredName,
                placeholder = placeholderByKey[occurrence.symbol.key]
                    ?.takeIf { isAnonymized(occurrence.symbol, settings) },
            )
        }

    return AnonymizationResult(
        text = text.toString(),
        mapping = mapping,
        unknowns = unknowns,
        counts = NameCounts(
            replaced = distinctKeys(symbols, SymbolOrigin.IN_CONTENT),
            unknown = unknowns.size,
            preserved = distinctKeys(symbols, SymbolOrigin.LIBRARY, SymbolOrigin.JDK),
        ),
        delta = LedgerDelta(allocated, allocator.nextNumber),
    )
}

/** How many distinct symbols in [symbols] carry one of [origins]. */
private fun distinctKeys(symbols: List<SymbolOccurrence>, vararg origins: SymbolOrigin): Int =
    symbols.filter { it.symbol.origin in origins }.mapTo(HashSet()) { it.symbol.key }.size

/**
 * Whether a symbol is replaced rather than emitted under its own name.
 *
 * Two rules, and it is worth seeing them side by side because they answer different questions.
 * [SymbolOrigin.IN_CONTENT] is the spine rule — *we own it, so it goes* — while
 * [SymbolOrigin.UNRESOLVED] is a rule about **not knowing**: nothing here can tell whose name it
 * is, and a name nobody can vouch for is treated as the user's own.
 *
 * [AnonymizationSettings.preservedUnknowns] is read here and nowhere else, which is what confines
 * the product's one deliberate fail-open to the branch it was granted for: the spine rule above it
 * reads no setting at all.
 */
private fun isAnonymized(symbol: SymbolEvidence, settings: AnonymizationSettings): Boolean = when (symbol.origin) {
    SymbolOrigin.IN_CONTENT -> true
    SymbolOrigin.UNRESOLVED -> symbol.key !in settings.preservedUnknowns

    // Preserved, and not by omission: concealing the tech stack is a declared non-goal, and library
    // names are what make a snippet answerable at all.
    SymbolOrigin.LIBRARY, SymbolOrigin.JDK -> false
}

/**
 * The placeholder namespace a symbol renders into.
 *
 * **A reference that fails to resolve gets its own namespace — `Unknown1`, never `Type7`.** This is
 * a usability win rather than merely the safe choice: `Unknown1` tells the model *the IDE could not
 * resolve this*, which localizes the breakage, whereas a uniform `Type7` invites confident reasoning
 * about a phantom symbol. Unresolved names also skew heavily project-owned — a missing import of
 * your own class, a class half-written — so failing them closed is the statistically correct guess
 * as well as the safe one.
 *
 * The namespace is read off the *origin* rather than off [SymbolRole], because the role of a name
 * that did not resolve is not knowable: whatever the plan reports for it is a placeholder value the
 * builder had to invent. [SymbolRole] stays a statement about Java's grammar, and this is the one
 * place the two part company.
 */
private fun namespaceOf(symbol: SymbolEvidence): String =
    if (symbol.origin == SymbolOrigin.UNRESOLVED) UNKNOWN_PREFIX else symbol.role.placeholderPrefix

/**
 * Every identifier-shaped word that survives into the output — which is every word in the snippet
 * except the ones about to be replaced.
 *
 * Read wider than "the preserved symbols" on purpose. The invariant is that a reader can take a
 * placeholder out of the AI's reply and map it back to exactly one thing, and that fails the moment
 * the output contains `Type1` meaning something else — whether that `Type1` is a preserved library
 * class, an identifier no rule here recognises yet, or a word sitting in a comment. Scanning the
 * text costs one pass and closes all three at once.
 */
private fun namesSurviving(plan: SnippetPlan, replaced: List<SymbolOccurrence>): Set<String> {
    val isReplaced = BooleanArray(plan.text.length)
    for (occurrence in replaced) {
        for (offset in occurrence.start until occurrence.end) isReplaced[offset] = true
    }
    return IDENTIFIER.findAll(plan.text)
        .filterNot { isReplaced[it.range.first] }
        .mapTo(HashSet()) { it.value }
}

/** A Java identifier, as the language defines one rather than as ASCII would. */
private val IDENTIFIER = Regex("""[\p{L}_$][\p{L}\p{N}_$]*""")

/**
 * Hands out placeholders from **one counter shared by every role**, so `local14` is ordinary and a
 * per-method counter restarting at `local1` is not a thing that can happen.
 *
 * One counter rather than one per role because the invariant is about the whole output: reverse
 * mapping is well-defined only if placeholder -> original is injective, and per-scope reuse makes it
 * many-to-one, needing scope context that the AI's *reply* does not carry. A shared counter also
 * makes the numbers sortable into allocation order by eye, which is what a person reading a mapping
 * table actually wants.
 *
 * @param reserved names that survive into the output; a candidate colliding with one of these
 *   **burns its number** rather than trying the same number under a different prefix. Burning
 *   rather than reusing is the same reason the counter is shared: a number that means two things at
 *   two moments is exactly what injectivity forbids.
 */
private class PlaceholderAllocator(start: Int, private val reserved: Set<String>) {
    var nextNumber: Int = start
        private set

    fun next(namespace: String): String {
        while (true) {
            val candidate = namespace + nextNumber++
            if (candidate !in reserved) return candidate
        }
    }
}

/** The namespace a reference that failed to resolve falls into. See [namespaceOf]. */
private const val UNKNOWN_PREFIX = "Unknown"
