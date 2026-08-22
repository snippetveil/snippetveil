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

    // **Name constraint is a property of the symbol, never of the occurrence.** One occurrence
    // carrying a non-project override root keeps the name everywhere the symbol appears, which is
    // the whole point: a declaration renamed and a call site preserved is exactly the failure this
    // rule exists to prevent, and it is a failure of two sites disagreeing.
    val constrained = symbols
        .filter { isNameConstrained(it.symbol) }
        .mapTo(HashSet()) { sharedKeyOf(it.symbol) }

    // The spine rule: anonymize a symbol iff its declaring file is project-owned. The JDK and
    // third-party libraries alike are preserved. An unresolved reference joins the anonymized set
    // by failing closed rather than by being owned — see [namespaceOf].
    val anonymized = symbols.filter { isAnonymized(it.symbol, settings) && sharedKeyOf(it.symbol) !in constrained }

    val allocator = PlaceholderAllocator(ledger.nextNumber, namesSurviving(plan, anonymized))
    val placeholderByKey = LinkedHashMap<String, String>(ledger.placeholders)
    val allocated = LinkedHashMap<String, String>()

    /**
     * The placeholder [symbol] renders as, allocating one the first time its key is asked for.
     *
     * An accessor allocates its backing field's placeholder as a side effect, whether or not the
     * field is anywhere in the snippet — with Lombok it usually is not, and a key is enough to name
     * a symbol that has no declaration in source at all.
     */
    fun placeholderFor(symbol: SymbolEvidence): String {
        val key = sharedKeyOf(symbol)
        placeholderByKey[key]?.let { return it }

        val accessor = symbol.accessor
        val placeholder = if (accessor == null) {
            allocator.next(namespaceOf(symbol))
        } else {
            val field = placeholderByKey.getOrPut(accessor.fieldKey) {
                allocator.next(SymbolRole.FIELD.placeholderPrefix).also { allocated[accessor.fieldKey] = it }
            }

            // Injectivity outranks coherence when the two ever collide, which is why the derived
            // name is checked against the surviving text like any allocated one. A reader holding
            // `getField1` from an AI's reply must be able to map it back to one thing; a split
            // accessor costs them a hop, and an ambiguous one costs them the answer.
            val derived = accessor.prefix + field.replaceFirstChar(Char::uppercaseChar)
            if (allocator.isFree(derived)) derived else allocator.next(namespaceOf(symbol))
        }

        placeholderByKey[key] = placeholder
        allocated[key] = placeholder
        return placeholder
    }

    // Allocation order is document order of first occurrence in the selection, which is what makes
    // the output read top to bottom. `anonymized` is sorted and [placeholderFor] allocates once per
    // key, so the order falls out rather than being arranged — with one deliberate exception: an
    // accessor seen before its field allocates the field there and then, because the two names have
    // to agree and only one of them can be first.
    for (occurrence in anonymized) placeholderFor(occurrence.symbol)

    // Right to left, so that every replacement lands at an offset the ones before it have not
    // moved. Splicing into the original string is also why formatting comes out byte-perfect: no
    // PSI is mutated, no formatter is invoked, and nothing but the identifiers themselves changes.
    val text = StringBuilder(plan.text)
    for (occurrence in anonymized.asReversed()) {
        text.replace(occurrence.start, occurrence.end, placeholderByKey.getValue(sharedKeyOf(occurrence.symbol)))
    }

    val mapping = anonymized.associate {
        placeholderByKey.getValue(sharedKeyOf(it.symbol)) to it.symbol.declaredName
    }

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
        counts = countsOf(symbols, anonymized, unknowns.size),
        delta = LedgerDelta(allocated, allocator.nextNumber),
    )
}

/**
 * The balloon's three numbers, which **partition the distinct names in the snippet**: every name is
 * counted exactly once, and the three add up to what is in the snippet.
 *
 * They are counted by *outcome* — replaced, or surviving verbatim — rather than by the origin the
 * plan reported, and that is a correction the name-constrained rules force. A project method that
 * keeps its real name because it implements `Runnable` is project-owned evidence and a preserved
 * name, and `replaced` is a claim about what is on the clipboard: counting it as replaced would be
 * false about the one surface a user checks. Unresolved names are counted by evidence, because
 * [NameCounts.unknown] reports what the IDE could not resolve rather than what became of it.
 *
 * Distinct means distinct *symbols*, so an override chain rendered as one placeholder is one name.
 */
private fun countsOf(
    symbols: List<SymbolOccurrence>,
    anonymized: List<SymbolOccurrence>,
    unknown: Int,
): NameCounts {
    val anonymizedKeys = anonymized.mapTo(HashSet()) { sharedKeyOf(it.symbol) }
    val unresolvedKeys = symbols
        .filter { it.symbol.origin == SymbolOrigin.UNRESOLVED }
        .mapTo(HashSet()) { sharedKeyOf(it.symbol) }
    val resolvedKeys = symbols.mapTo(LinkedHashSet()) { sharedKeyOf(it.symbol) } - unresolvedKeys

    return NameCounts(
        replaced = resolvedKeys.count { it in anonymizedKeys },
        unknown = unknown,
        preserved = resolvedKeys.count { it !in anonymizedKeys },
    )
}

/**
 * **Whether Java forbids this symbol from being renamed at all.**
 *
 * The reframe that decides this rule's shape: anonymized output is *read*, never compiled or run.
 * The damage from getting one wrong is not a broken override — it is that the output is visibly
 * contradictory, and a reviewer reports a non-override as a bug that does not exist upstream.
 * Compilation is a proxy for coherence; coherence is the goal.
 *
 * Two constraints, and the second exists because the first misses it:
 *
 *  - **An override chain reaching a non-project root.** The name belongs to whoever declared the
 *    root, and renaming it here silently stops the override overriding. This class is large in real
 *    code: `@Override` of framework types, interface implementations, `equals`/`hashCode`/
 *    `toString`, JUnit and Spring lifecycle methods.
 *  - **A fixed list of platform-constrained names** that no override chain reaches, because the
 *    platform looks them up by name rather than through a type: `main` and the serialization hooks.
 *    All non-domain words, so preserving them leaks nothing.
 *
 * **There is deliberately no user-editable list of preserved names** — a knob that leaks by
 * construction, and a settings file that becomes a plaintext domain glossary committed to the repo.
 *
 * Scoped to [SymbolOrigin.IN_CONTENT], which is not a shortcut: the JDK and libraries are preserved
 * by the spine rule already, and an unresolved name must stay failed closed. A constraint is a
 * reason to keep a name the project owns, so it is the only origin it may speak about.
 */
private fun isNameConstrained(symbol: SymbolEvidence): Boolean =
    symbol.origin == SymbolOrigin.IN_CONTENT &&
        (symbol.overrideRoots.any { it.origin != SymbolOrigin.IN_CONTENT } ||
            (symbol.role == SymbolRole.METHOD && symbol.declaredName in PLATFORM_CONSTRAINED_NAMES))

/**
 * **The key a placeholder is handed out against**, which is the symbol's own unless an override
 * chain forces it to share.
 *
 * An overriding method and the method it overrides are distinct PSI symbols that Java forbids from
 * diverging, so they collapse onto one placeholder — keyed by **the root of the chain**, not by the
 * declaring class. Keying by the declaring class split an interface from its implementation, and
 * the `@Override` then no longer implemented anything.
 *
 * The root is the lexicographically smallest of them, which is a tie-break rather than a preference:
 * what matters is that every symbol in the chain computes the same one, from its own evidence alone.
 * That last clause is why this is a pure function of one symbol rather than a closure over the
 * snippet — a placeholder that depended on which *other* symbols happened to be selected would not
 * survive being persisted across invocations.
 *
 * **The stated limit of that:** a method implementing two unrelated interfaces that both declare it
 * has two roots, and the interface that loses the tie-break renders as its own placeholder. The
 * closure that would fix it is exactly the snippet-dependent key the paragraph above rules out.
 */
private fun sharedKeyOf(symbol: SymbolEvidence): String =
    symbol.overrideRoots.minOfOrNull { it.key } ?: symbol.key

/**
 * Names the platform resolves by spelling rather than through a type, so no override chain reaches
 * them. `main` is the JVM entry point; the other four are `java.io.Serializable`'s hooks, which the
 * serialization machinery finds reflectively on a class that declares them privately.
 */
private val PLATFORM_CONSTRAINED_NAMES =
    setOf("main", "readObject", "writeObject", "readResolve", "writeReplace")

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
private fun isAnonymized(symbol: SymbolEvidence, settings: AnonymizationSettings): Boolean = when {
    isTopLevelPackageSegment(symbol) -> false

    else -> when (symbol.origin) {
        SymbolOrigin.IN_CONTENT -> true
        SymbolOrigin.UNRESOLVED -> symbol.key !in settings.preservedUnknowns

        // Preserved, and not by omission: concealing the tech stack is a declared non-goal, and
        // library names are what make a snippet answerable at all.
        SymbolOrigin.LIBRARY, SymbolOrigin.JDK -> false
    }
}

/**
 * **Whether this is the top-level segment of a package name** — `com` out of `com.acme.billing`.
 *
 * It is passed through, and it is the one place a project-owned name is preserved on the strength of
 * *what it is* rather than of who owns it. There are three of these words in practice — `com`, `org`,
 * `io` — none of them carries a byte of domain, and renaming one buys nothing while costing a row in
 * the mapping table and a word the reader has to hold.
 *
 * Everything under it renames, which is what keeps the rule from eating the thing it sits next to:
 * `com.acme.billing` still comes out as `com.pkg1.pkg2`, so same-package and different-package stay
 * as distinguishable as they were.
 *
 * A package whose qualified name the plan did not report falls through to the spine rule and is
 * anonymized, which is the fail-closed direction: a segment nobody can place is not one this rule
 * may vouch for.
 */
private fun isTopLevelPackageSegment(symbol: SymbolEvidence): Boolean =
    symbol.role == SymbolRole.PACKAGE && symbol.qualifiedName?.contains('.') == false

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
            if (isFree(candidate)) return candidate
        }
    }

    /** Whether [name] can stand for one thing in the output — asked of derived names too. */
    fun isFree(name: String): Boolean = name !in reserved
}

/** The namespace a reference that failed to resolve falls into. See [namespaceOf]. */
private const val UNKNOWN_PREFIX = "Unknown"
