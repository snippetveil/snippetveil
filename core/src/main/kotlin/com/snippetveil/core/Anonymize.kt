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

    // The spine rule, and the only policy decision in this ticket: anonymize a symbol iff its
    // declaring file is project-owned. The JDK and third-party libraries alike are preserved.
    val (anonymized, preserved) = symbols.partition { it.symbol.origin == SymbolOrigin.IN_CONTENT }

    val allocator = PlaceholderAllocator(ledger.nextNumber, namesSurviving(plan, anonymized))
    val placeholderByKey = LinkedHashMap<String, String>(ledger.placeholders)
    val allocated = LinkedHashMap<String, String>()

    // Allocation order is document order of first occurrence in the selection, which is what makes
    // the output read top to bottom. `anonymized` is sorted, and `getOrPut` fires once per key, so
    // the order falls out rather than being arranged.
    for (occurrence in anonymized) {
        placeholderByKey.getOrPut(occurrence.symbol.key) {
            allocator.next(occurrence.symbol.role).also { allocated[occurrence.symbol.key] = it }
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
    return AnonymizationResult(
        text = text.toString(),
        mapping = mapping,
        counts = NameCounts(
            replaced = mapping.size,
            preserved = preserved.mapTo(HashSet()) { it.symbol.key }.size,
        ),
        delta = LedgerDelta(allocated, allocator.nextNumber),
    )
}

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

    fun next(role: SymbolRole): String {
        while (true) {
            val candidate = role.placeholderPrefix + nextNumber++
            if (candidate !in reserved) return candidate
        }
    }
}
