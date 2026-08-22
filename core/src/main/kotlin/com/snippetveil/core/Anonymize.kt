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
    val occurrences = plan.occurrences.sortedBy { it.start }
    val symbols = occurrences.filterIsInstance<SymbolOccurrence>()
    val literals = occurrences.filterIsInstance<LiteralOccurrence>()

    // **Name constraint is a property of the symbol, never of the occurrence.** One occurrence
    // carrying a non-project override root keeps the name everywhere the symbol appears, which is
    // the whole point: a declaration renamed and a call site preserved is exactly the failure this
    // rule exists to prevent, and it is a failure of two sites disagreeing. A reference inside a
    // literal is such an occurrence, so it is read here alongside the identifiers.
    val constrained = (symbols.map { it.symbol } + literals.flatMap { literal -> literal.references.map { it.symbol } })
        .filter(::isNameConstrained)
        .mapTo(HashSet(), ::sharedKeyOf)

    // The spine rule: anonymize a symbol iff its declaring file is project-owned. The JDK and
    // third-party libraries alike are preserved. An unresolved reference joins the anonymized set
    // by failing closed rather than by being owned — see [namespaceOf].
    fun isReplaced(symbol: SymbolEvidence): Boolean =
        isAnonymized(symbol, settings) && sharedKeyOf(symbol) !in constrained

    val allocator = PlaceholderAllocator(
        ledger.nextNumber,
        namesSurviving(plan, symbols.filter { isReplaced(it.symbol) }),
    )
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

    // What the output replaces, in ascending order, and every symbol the output speaks about — the
    // second being what the counts partition and what the mapping has to explain. A reference the
    // coverage rule spliced names its symbol as surely as an identifier does; a literal replaced
    // whole contributes nothing, because none of its references survived as a name.
    val edits = mutableListOf<Edit>()
    val named = mutableListOf<SymbolEvidence>()

    // One pass in document order, which is what makes the output read top to bottom: every
    // placeholder is allocated the first time the thing it stands for is written, whether that is
    // an identifier or a reference inside a literal. [placeholderFor] allocates once per key, so the
    // order falls out rather than being arranged — with one deliberate exception: an accessor seen
    // before its field allocates the field there and then, because the two names have to agree and
    // only one of them can be first.
    for (occurrence in occurrences) {
        when (occurrence) {
            is SymbolOccurrence -> {
                named += occurrence.symbol
                if (isReplaced(occurrence.symbol)) {
                    edits += Edit(occurrence.start, occurrence.end, placeholderFor(occurrence.symbol))
                }
            }

            is LiteralOccurrence -> when (val rewrite = rewriteOf(plan.text, occurrence)) {
                LiteralRewrite.Preserved -> Unit

                LiteralRewrite.Redacted ->
                    edits += Edit(occurrence.contentStart, occurrence.contentEnd, allocator.next(LITERAL_PREFIX))

                is LiteralRewrite.Spliced -> for (reference in rewrite.references) {
                    named += reference.symbol
                    if (isReplaced(reference.symbol)) {
                        edits += Edit(reference.start, reference.end, placeholderFor(reference.symbol))
                    }
                }
            }

            is CommentOccurrence -> Unit
        }
    }

    // Right to left, so that every replacement lands at an offset the ones before it have not
    // moved. Splicing into the original string is also why formatting comes out byte-perfect: no
    // PSI is mutated, no formatter is invoked, and nothing but the identifiers and the insides of
    // literals changes.
    val text = StringBuilder(plan.text)
    for (edit in edits.asReversed()) text.replace(edit.start, edit.end, edit.text)

    val mapping = named
        .filter(::isReplaced)
        .associate { placeholderByKey.getValue(sharedKeyOf(it)) to it.declaredName }

    // Every unresolved name, once, in document order of first occurrence — the order the preview
    // dialog sorts its rows by. The placeholder is read back through [isAnonymized] rather than out
    // of the ledger, so a name preserved by this invocation reports no placeholder even when an
    // earlier invocation gave it one.
    //
    // Read off the identifiers alone: an unresolved reference *inside* a literal covers nothing, so
    // the literal it sits in was replaced whole and there is no placeholder for a user to preserve
    // it against — offering one would be a row in the preview that cannot be acted on.
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
        counts = countsOf(named, ::isReplaced, unknowns.size),
        delta = LedgerDelta(allocated, allocator.nextNumber),
    )
}

/** One replacement, as a half-open range `[start, end)` into the plan's text and what goes there. */
private class Edit(val start: Int, val end: Int, val text: String)

/** What becomes of one literal. See [rewriteOf]. */
private sealed class LiteralRewrite {

    /** Emitted verbatim: a number, a character, `true`, `false`, `null`, or `""`. */
    object Preserved : LiteralRewrite()

    /** The content is replaced whole, by one `str` placeholder. The default for a string literal. */
    object Redacted : LiteralRewrite()

    /** These ranges rename with the symbols they name; everything between them survives verbatim. */
    class Spliced(val references: List<LiteralReference>) : LiteralRewrite()
}

/**
 * **What becomes of one literal, decided from its kind, its length and its resolved references —
 * and from nothing else about its text.**
 *
 * The default is [LiteralRewrite.Redacted], and that is the ticket's central finding rather than
 * caution: blanket replacement took 38 identifiable literals to 0 while answering 5 of 6 questions
 * at parity. The exception is a literal carrying references, which renames in lockstep with the
 * symbols those references name.
 *
 * **The coverage rule.** A reference usually covers only part of a literal —
 * `JavaClassReferenceSet` yields one reference per dotted segment — so the question is what to do
 * with the gaps between them:
 *
 *  - **Gaps consisting only of non-alphanumeric characters do not break coverage.** Strict full
 *    coverage would lose `@Index(columnList = "merchantId, createdAt")` to a `", "` gap, which is
 *    the exact case the experiment asked to fix.
 *  - **Any word-bearing gap sends the whole literal to `str`.** Rewriting the covered ranges and
 *    passing the gaps through — *mixed-always* — fixes the two easy rows and leaks the two that
 *    matter: `@RequestMapping("/api/merchants/{id}")` and
 *    `createQuery("SELECT p FROM com.acme.Payment p")` both carry domain words in their gaps.
 *
 * **Disclosed judgment call:** classifying a gap as punctuation-only *is* looking at literal text.
 * It stays on the right side of the not-regex constraint because the prohibition is on deciding a
 * literal's **rewrite** from its content, and this decides only **coverage** — never what any
 * character becomes. Recorded as a knowing call, not an oversight.
 *
 * **A reference that resolved to nothing is neither a covered range nor a gap** — it is ignored, and
 * what decides the literal is the text around the references that *did* resolve. That is
 * load-bearing rather than tidy: `Class.forName("com.acme.billing.Payment")` arrives with its four
 * dotted segments resolved **and** an unresolved reference over the whole name, contributed
 * alongside them by the reflection contributor. A rule that let an unresolved reference redact the
 * literal would redact every class name in the language. Where such a reference sits over a name and
 * nothing else claims it, that name is a word-bearing gap and the literal fails closed anyway, which
 * is the direction to fail in.
 *
 * A reference set that cannot be spliced — one range overlapping another, or reaching outside the
 * content — fails closed too. No contributor is expected to produce that shape, and a shape nothing
 * expects is not one to guess the meaning of.
 */
private fun rewriteOf(text: String, literal: LiteralOccurrence): LiteralRewrite {
    if (literal.kind != LiteralKind.STRING && literal.kind != LiteralKind.TEXT_BLOCK) {
        return LiteralRewrite.Preserved
    }

    // `""` carries no domain content by definition, while `s.equals("")` becoming
    // `s.equals("str1")` silently converts an empty-check into a value-check — *plausible* rather
    // than obvious, which is the prohibited class. Testing for length zero is not inspecting
    // content.
    if (literal.contentEnd <= literal.contentStart) return LiteralRewrite.Preserved

    val covered = literal.references
        .filter { it.symbol.origin != SymbolOrigin.UNRESOLVED }
        .sortedBy { it.start }
    if (covered.isEmpty()) return LiteralRewrite.Redacted

    var at = literal.contentStart
    for (reference in covered) {
        if (reference.start < at || reference.end > literal.contentEnd) return LiteralRewrite.Redacted
        if (bearsAWord(text, at, reference.start)) return LiteralRewrite.Redacted
        at = reference.end
    }
    if (bearsAWord(text, at, literal.contentEnd)) return LiteralRewrite.Redacted

    return LiteralRewrite.Spliced(covered)
}

/** Whether `[from, to)` holds a letter or a digit, as Unicode defines the two rather than as ASCII would. */
private fun bearsAWord(text: String, from: Int, to: Int): Boolean =
    (from until to).any { text[it].isLetterOrDigit() }

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
 *
 * @param named every symbol the output speaks about — the identifiers, and the literal references
 *   the coverage rule spliced. A name written only inside a literal is a name in the snippet.
 */
private fun countsOf(
    named: List<SymbolEvidence>,
    isReplaced: (SymbolEvidence) -> Boolean,
    unknown: Int,
): NameCounts {
    val unresolvedKeys = named
        .filter { it.origin == SymbolOrigin.UNRESOLVED }
        .mapTo(HashSet(), ::sharedKeyOf)
    val resolved = named
        .filter { sharedKeyOf(it) !in unresolvedKeys }
        .distinctBy(::sharedKeyOf)

    return NameCounts(
        replaced = resolved.count(isReplaced),
        unknown = unknown,
        preserved = resolved.count { !isReplaced(it) },
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
 * Two rules of ownership, and it is worth seeing them side by side because they answer different
 * questions. [SymbolOrigin.IN_CONTENT] is the spine rule — *we own it, so it goes* — while
 * [SymbolOrigin.UNRESOLVED] is a rule about **not knowing**: nothing here can tell whose name it
 * is, and a name nobody can vouch for is treated as the user's own.
 *
 * [isTopLevelPackageSegment] sits in front of both, and it is the only rule here that is not about
 * ownership at all — `com` is the project's and is preserved anyway, because there is nothing in it
 * to conceal. It is checked first rather than folded into the spine rule so that the spine rule
 * stays the one sentence it has to stay.
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
 *
 * **The stated limit, because it is the one case where this rule preserves a domain word.** The
 * test is *positional* — the segment with nothing before it — rather than a check against `com`,
 * `org` and `io` by spelling. A project rooted at a single-segment package, `package billing;`,
 * therefore has `billing` passed through. The alternative is a list of names preserved by spelling,
 * which is the one thing this product has ruled out everywhere else: a preserve list leaks by
 * construction and there is nowhere to stop adding to it. Reverse-domain packages are the Java
 * convention and the positional rule is right for all of them.
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
 *
 * A word inside a literal that is about to be replaced is reserved along with them, and that
 * over-reservation is deliberate: what becomes of a literal is decided against this set, so a set
 * that already knew would have to be built out of the answer it feeds. The cost is a burnt number,
 * which is the direction this may err in.
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

/**
 * The namespace a redacted literal falls into — `"str1"`, out of the same counter every symbol is
 * numbered from. A literal is not a symbol and has no key, so two identical literals get two
 * placeholders: collapsing them would mean deciding from their text that they are the same thing.
 */
private const val LITERAL_PREFIX = "str"
