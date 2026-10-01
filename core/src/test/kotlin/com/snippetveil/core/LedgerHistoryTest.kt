package com.snippetveil.core

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * **The two invariants that only exist over a sequence of invocations.**
 *
 * These are property-based rather than example-based for a reason that is structural rather than
 * stylistic: the failure this whole design exists to prevent — *a second paste contradicting the
 * first* — **cannot manifest in any single-invocation test, by construction.** It needs two
 * invocations over an overlapping symbol universe, and which pair of orderings breaks it is exactly
 * the thing nobody can guess in advance. So the sequences are generated, over a universe small
 * enough that overlap is guaranteed rather than hoped for.
 *
 * The two properties are the whole contract:
 *
 *  - **Stability.** A symbol with a qualified key renders to what it rendered to before, in every
 *    later invocation it appears in, whatever else was selected and in whatever order.
 *  - **Injectivity across time.** No placeholder ever stands for two different symbols in the
 *    project's whole history. This is what makes reverse mapping well-defined at all, and it is what
 *    burning numbers buys: an unpersisted symbol takes a number out of circulation rather than
 *    leaving it to be handed to something else later.
 *
 * ### Two-sorted: source and trace invocations, interleaved
 *
 * A stack trace mints too, so the sequences interleave **snippets, in Java and in Kotlin, and
 * traces**. That is nearly free, because [parseTrace] is pure: a trace invocation is a trace text
 * written out of the selection, read by [parseTrace], and resolved against the universe the way the
 * plugin's resolver resolves it against the index. Over those sequences the contract reads as three
 * properties:
 *
 *  1. **Cross-source stability.** A symbol first minted by a trace and later met in a snippet takes
 *     the same placeholder, and the reverse. Seeding the ledger from a trace is only defensible if the
 *     seeds are the placeholders a snippet would have been given.
 *  2. **Cross-language stability.** One symbol met from Java and from Kotlin is one row.
 *  3. **No recycling across sources.** One counter and two minting sites, so no number appears twice
 *     in the whole interleaved history.
 *
 * Every property is checked on every invocation of every generated sequence rather than at the end,
 * so a failure names the invocation that broke it.
 */
class LedgerHistoryTest {

    /** **Cross-source stability**, and the plain stability property it extends. */
    @Property(tries = 300)
    fun `a qualified symbol renders to what it rendered to before, whether a trace or a snippet met it first`(
        @ForAll("invocationSequences") sequence: List<HistoryInvocation>,
    ) {
        val history = History()
        for (invocation in sequence) {
            for ((symbol, placeholder) in history.invoke(invocation)) {
                if (!symbol.qualified) continue
                val before = history.stable.put(symbol.key, placeholder)
                assertEquals(
                    before ?: placeholder,
                    placeholder,
                    "`${symbol.name}` was `$before` and is now `$placeholder` in $invocation: a later paste contradicts an earlier one",
                )
            }
        }
    }

    /**
     * **Cross-language stability.** A symbol has one key whichever language spelled it, so it has
     * one row and one placeholder: the ledger holds exactly the symbols met so far, once each.
     */
    @Property(tries = 300)
    fun `one symbol met from Java and from Kotlin is one row`(
        @ForAll("invocationSequences") sequence: List<HistoryInvocation>,
    ) {
        val history = History()
        val met = mutableMapOf<String, MutableSet<String>>()
        for (invocation in sequence) {
            for ((symbol, placeholder) in history.invoke(invocation)) {
                if (!symbol.qualified) continue
                val rendered = met.getOrPut(symbol.key) { mutableSetOf() }.apply { add(placeholder) }
                assertEquals(1, rendered.size, "`${symbol.name}` rendered as $rendered across languages, by $invocation")
            }
            assertEquals(met.keys, history.ledger.placeholders.keys, "the ledger's rows are not the symbols met, once each")
        }
    }

    /**
     * **No recycling across sources**, which is injectivity over the whole interleaved history and
     * stronger than it: a number is never handed out twice, whatever namespace it was handed out
     * in. `Type3` and `method3` never stand for two names, and nor do a snippet's `local4` and a
     * trace's `Unknown4`.
     */
    @Property(tries = 300)
    fun `no number is handed out twice across snippets and traces`(
        @ForAll("invocationSequences") sequence: List<HistoryInvocation>,
    ) {
        val history = History()
        for (invocation in sequence) {
            history.invoke(invocation)
            for ((placeholder, original) in history.lastMapping) {
                val number = NUMBER.find(placeholder)?.value ?: continue
                val owner = history.numbers.putIfAbsent(number, original)
                assertEquals(
                    owner ?: original,
                    original,
                    "number $number stood for `$owner` and now stands for `$original` (`$placeholder`, in $invocation): a number was recycled",
                )
            }
        }
    }

    @Property(tries = 300)
    fun `no placeholder ever stands for two different symbols`(
        @ForAll("invocationSequences") sequence: List<HistoryInvocation>,
    ) {
        val history = History()
        for (invocation in sequence) {
            for ((symbol, placeholder) in history.invoke(invocation)) {
                val owner = history.owners.putIfAbsent(placeholder, symbol.key)
                assertEquals(
                    owner ?: symbol.key,
                    symbol.key,
                    "`$placeholder` stood for `$owner` and now stands for `${symbol.key}`: reverse mapping is ambiguous",
                )
            }
        }
    }

    /**
     * **Exactly the qualified keys are in the stored file** — the durable artifact holds classes,
     * members and packages, and no local, no anonymous-class member and no unresolved name.
     *
     * Both halves, and neither is the other. *Nothing ephemeral got in* is the rule that keeps the
     * file from filling with keys that re-point at a different symbol after an edit; *everything
     * qualified did* is what stops the rule being satisfied by writing nothing down at all, which is
     * the shape a fail-closed bug takes here — and which every other property in this class would
     * still be green over.
     *
     * **A trace seeds the ledger, and only with what resolved.** Its resolved classes and methods are
     * written down like a snippet's; its unresolved frame's class-shaped string is text, and is not.
     */
    @Property(tries = 300)
    fun `exactly the qualified keys are written down`(
        @ForAll("invocationSequences") sequence: List<HistoryInvocation>,
    ) {
        val history = History()
        val expected = mutableSetOf<String>()

        for (invocation in sequence) {
            val met = history.invoke(invocation).map { it.first }
            expected += met.filter { it.qualified }.map { it.key }

            assertTrue(
                history.ledger.placeholders.keys.none { key -> UNIVERSE.none { it.key == key && it.qualified } },
                "the stored file holds a key that is not derived from a qualified name: ${history.ledger.placeholders}",
            )
            assertEquals(
                emptySet<String>(),
                expected - history.ledger.placeholders.keys,
                "a symbol with a qualified key was named and not written down",
            )
        }
    }

    /**
     * Sequences of invocations, each a source — a Java snippet, a Kotlin snippet or a trace — and an
     * ordering of a non-empty subset of [UNIVERSE].
     *
     * The subset is a set and then shuffled, which is two things at once: a selection never names one
     * symbol twice, and **the order symbols are met in varies independently of which symbols they
     * are** — which is the half that matters, because allocation order is what stability has to
     * survive being unrelated to. A trace names only what a trace can, so the rest of its selection
     * is dropped: no trace names a local.
     */
    @Provide
    fun invocationSequences(): Arbitrary<List<HistoryInvocation>> {
        val selections = Arbitraries.integers()
            .between(0, UNIVERSE.size - 1)
            .set()
            .ofMinSize(1)
            .ofMaxSize(UNIVERSE.size)
            .flatMap { selected -> Arbitraries.shuffle(selected.toList()) }
        return Combinators.combine(Arbitraries.of(*HistorySource.values()), selections)
            .`as` { source, selection -> HistoryInvocation(source, selection) }
            .list()
            .ofMinSize(1)
            .ofMaxSize(6)
    }
}

/** Where an invocation's text came from: a snippet in one of the two languages, or a stack trace. */
enum class HistorySource { JAVA_SNIPPET, KOTLIN_SNIPPET, TRACE }

/** One invocation in a generated history. */
class HistoryInvocation(val source: HistorySource, val selection: List<Int>) {
    override fun toString(): String = "$source${selection.map { UNIVERSE[it] }}"
}

/**
 * One symbol in the generated universe.
 *
 * @param qualified whether the plan builder would have derived this key from a fully-qualified name.
 *   The universe holds both kinds deliberately: a property over qualified symbols alone would never
 *   see the number an ephemeral one burns, which is the mechanism the injectivity property is about.
 * @param frame how a trace names this symbol — a frame's class and method, as the JVM writes them —
 *   or `null` for a symbol no trace can name
 */
private class Sym(
    val name: String,
    val key: String,
    val role: SymbolRole,
    val qualified: Boolean,
    val origin: SymbolOrigin = SymbolOrigin.IN_CONTENT,
    val frame: Pair<String, String?>? = null,
) {
    override fun toString(): String = name
}

/**
 * **Small on purpose, so that overlap between invocations is guaranteed rather than hoped for.**
 *
 * None of these names is placeholder-shaped, which keeps the run free of a distraction rather than
 * of a rule: the allocator reserves identifiers that survive into the output, so a fixture named
 * `Type1` would burn numbers for a reason that has nothing to do with what is under test here.
 *
 * `Ghost` is a frame whose class does not resolve: in a trace it is one whole `Unknown`, keyed by
 * its text and never written down. A snippet meeting the same spelling meets an unresolved name too.
 */
private val UNIVERSE = listOf(
    Sym("Payment", "class:com.acme.Payment", SymbolRole.TYPE, qualified = true, frame = "com.acme.Payment" to null),
    Sym("Refund", "class:com.acme.Refund", SymbolRole.TYPE, qualified = true, frame = "com.acme.Refund" to null),
    Sym("merchantRef", "field:class:com.acme.Payment#merchantRef", SymbolRole.FIELD, qualified = true),
    Sym("settle", "method:class:com.acme.Payment#settle", SymbolRole.METHOD, qualified = true, frame = "com.acme.Payment" to "settle"),
    Sym("draft", "local:file@17", SymbolRole.LOCAL, qualified = false),
    Sym("state", "field:class:file@40#state", SymbolRole.FIELD, qualified = false),
    Sym("Ghost", "unresolved:Ghost", SymbolRole.TYPE, qualified = false, origin = SymbolOrigin.UNRESOLVED, frame = "Ghost" to null),
)

/** The trailing number of a placeholder — `3` out of `Type3`, `method3` or `Unknown3`. */
private val NUMBER = Regex("""\d+$""")

/**
 * A ledger folded through a sequence of invocations, the way the plugin folds it: snapshot in, delta
 * committed, and the committed ledger handed to the next call.
 *
 * The commit is [plus] — the same operator the store uses — rather than a merge written out here,
 * because a test that spelled committing its own way would prove a property of a rule the product
 * does not have.
 */
private class History {
    var ledger: LedgerSnapshot = LedgerSnapshot.EMPTY
        private set

    /** The last invocation's mapping, placeholder to what it stood for. */
    var lastMapping: Map<String, String> = emptyMap()
        private set

    /** What each qualified key has rendered as, once it has rendered as anything. */
    val stable: MutableMap<String, String> = mutableMapOf()

    /** What each placeholder has ever stood for. */
    val owners: MutableMap<String, String> = mutableMapOf()

    /** What each number has ever stood for, in any namespace. */
    val numbers: MutableMap<String, String> = mutableMapOf()

    /** One invocation, and what each symbol it met rendered as. */
    fun invoke(invocation: HistoryInvocation): List<Pair<Sym, String>> {
        val symbols = invocation.selection.map { UNIVERSE[it] }
        val (plan, met) = when (invocation.source) {
            HistorySource.JAVA_SNIPPET -> snippetOf(symbols, SourceLanguage.JAVA) to symbols
            HistorySource.KOTLIN_SNIPPET -> snippetOf(symbols, SourceLanguage.KOTLIN) to symbols
            HistorySource.TRACE -> traceOf(symbols)
        }

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, ledger)
        ledger += result.delta
        lastMapping = result.mapping

        // The mapping is keyed by placeholder, so two symbols collapsing onto one placeholder shows
        // up here as a missing row rather than as a duplicate. Asserted before it is inverted, both
        // because it is the within-output half of injectivity and because a silently short mapping
        // would turn a real collision into a lookup failure with nothing to read.
        assertEquals(
            met.map { it.key }.distinct().size,
            result.mapping.size,
            "two of $met rendered to one placeholder in a single output: ${result.mapping}",
        )

        val byName = result.mapping.entries.associate { (placeholder, name) -> name to placeholder }
        return met.distinct().map { it to byName.getValue(it.name) }
    }
}

/** A snippet naming [symbols] in order, every token tagged [language]. */
private fun snippetOf(symbols: List<Sym>, language: SourceLanguage): SnippetPlan {
    val text = StringBuilder()
    val occurrences = mutableListOf<Occurrence>()
    for (sym in symbols) {
        if (text.isNotEmpty()) text.append(' ')
        occurrences += SymbolOccurrence(text.length, text.length + sym.name.length, sym.name, evidenceOf(sym), language)
        text.append(sym.name)
    }
    return SnippetPlan(text.toString(), occurrences)
}

/**
 * **A trace naming whichever of [symbols] a trace can name**, read by [parseTrace] and resolved
 * against [UNIVERSE] the way the plugin resolves it against the index: a frame's class by its
 * binary name and its method on that class, and a class the universe does not hold as one whole
 * `Unknown`. Package segments are left as written, which no property here is about.
 *
 * @return the plan and the universe's symbols it met, in order
 */
private fun traceOf(symbols: List<Sym>): Pair<SnippetPlan, List<Sym>> {
    val frames = symbols.mapNotNull { it.frame }.ifEmpty { listOf("Ghost" to null) }
    val text = buildString {
        append("java.lang.IllegalStateException: boom")
        for ((type, method) in frames) append("\n\tat ").append(type).append('.').append(method ?: "<init>").append("(Unknown Source)")
    }
    val trace = (parseTrace(text) as TraceReading.Read).trace

    val occurrences = mutableListOf<Occurrence>()
    val met = mutableListOf<Sym>()
    for (frame in trace.frames) {
        val type = UNIVERSE.single { it.frame == frame.type.text to null }
        val simple = type.name
        occurrences += SymbolOccurrence(frame.type.end - simple.length, frame.type.end, simple, evidenceOf(type), SourceLanguage.JAVA)
        met += type
        val method = frame.method ?: continue
        val declared = UNIVERSE.single { it.frame == frame.type.text to method.text }
        occurrences += SymbolOccurrence(method.start, method.end, method.text, evidenceOf(declared), SourceLanguage.JAVA)
        met += declared
    }
    return SnippetPlan(trace.text, occurrences.sortedBy { it.start }, rootFromOwnedTypes = true) to met
}

private fun evidenceOf(sym: Sym) = symbol(
    name = sym.name,
    role = sym.role,
    origin = sym.origin,
    key = sym.key,
    keyIsQualified = sym.qualified,
)
