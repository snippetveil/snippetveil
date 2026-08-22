package com.snippetveil.core

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
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
 * Both are checked on every invocation of every generated sequence rather than at the end, so a
 * failure names the invocation that broke it.
 */
class LedgerHistoryTest {

    @Property(tries = 300)
    fun `every qualified symbol renders to what it rendered to before`(
        @ForAll("invocationSequences") sequence: List<List<Int>>,
    ) {
        val history = History()
        for (selection in sequence) {
            val invocation = history.invoke(selection)
            for ((symbol, placeholder) in invocation) {
                if (!symbol.qualified) continue
                val before = history.stable.put(symbol.key, placeholder)
                assertEquals(
                    before ?: placeholder,
                    placeholder,
                    "`${symbol.name}` was `$before` and is now `$placeholder`: a later paste contradicts an earlier one",
                )
            }
        }
    }

    @Property(tries = 300)
    fun `no placeholder ever stands for two different symbols`(
        @ForAll("invocationSequences") sequence: List<List<Int>>,
    ) {
        val history = History()
        for (selection in sequence) {
            for ((symbol, placeholder) in history.invoke(selection)) {
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
     * **Nothing without a qualified key is ever in the stored file**, over the same generated
     * sequences — the durable artifact holds classes, members and packages, and no local, no
     * anonymous-class member and no unresolved name.
     */
    @Property(tries = 300)
    fun `only qualified keys are ever written down`(
        @ForAll("invocationSequences") sequence: List<List<Int>>,
    ) {
        val history = History()
        val ephemeral = UNIVERSE.filterNot { it.qualified }.mapTo(HashSet()) { it.key }

        for (selection in sequence) {
            history.invoke(selection)
            assertTrue(
                history.ledger.placeholders.keys.none { it in ephemeral },
                "the stored file holds a key that is not derived from a qualified name: ${history.ledger.placeholders}",
            )
        }
    }

    /**
     * Sequences of selections over [UNIVERSE], each an ordering of a non-empty subset.
     *
     * The subset is a set and then shuffled, which is two things at once: a selection never names one
     * symbol twice, and **the order symbols are met in varies independently of which symbols they
     * are** — which is the half that matters, because allocation order is what stability has to
     * survive being unrelated to.
     */
    @Provide
    fun invocationSequences(): Arbitrary<List<List<Int>>> = Arbitraries.integers()
        .between(0, UNIVERSE.size - 1)
        .set()
        .ofMinSize(1)
        .ofMaxSize(UNIVERSE.size)
        .flatMap { selected -> Arbitraries.shuffle(selected.toList()) }
        .list()
        .ofMinSize(1)
        .ofMaxSize(6)
}

/**
 * One project-owned symbol in the generated universe.
 *
 * @param qualified whether the plan builder would have derived this key from a fully-qualified name.
 *   The universe holds both kinds deliberately: a property over qualified symbols alone would never
 *   see the number an ephemeral one burns, which is the mechanism the second property is about.
 */
private class Sym(val name: String, val key: String, val role: SymbolRole, val qualified: Boolean) {
    override fun toString(): String = name
}

/**
 * **Small on purpose, so that overlap between invocations is guaranteed rather than hoped for.**
 *
 * None of these names is placeholder-shaped, which keeps the run free of a distraction rather than
 * of a rule: the allocator reserves identifiers that survive into the output, so a fixture named
 * `Type1` would burn numbers for a reason that has nothing to do with what is under test here.
 */
private val UNIVERSE = listOf(
    Sym("Payment", "class:com.acme.Payment", SymbolRole.TYPE, qualified = true),
    Sym("Refund", "class:com.acme.Refund", SymbolRole.TYPE, qualified = true),
    Sym("merchantRef", "field:class:com.acme.Payment#merchantRef", SymbolRole.FIELD, qualified = true),
    Sym("settle", "method:class:com.acme.Payment#settle", SymbolRole.METHOD, qualified = true),
    Sym("draft", "local:file@17", SymbolRole.LOCAL, qualified = false),
    Sym("state", "field:class:file@40#state", SymbolRole.FIELD, qualified = false),
)

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

    /** What each qualified key has rendered as, once it has rendered as anything. */
    val stable: MutableMap<String, String> = mutableMapOf()

    /** What each placeholder has ever stood for. */
    val owners: MutableMap<String, String> = mutableMapOf()

    /** One invocation over [selection], and what each selected symbol rendered as. */
    fun invoke(selection: List<Int>): List<Pair<Sym, String>> {
        val symbols = selection.map { UNIVERSE[it] }
        val plan = planOf(
            symbols.joinToString(" ") { it.name },
            *symbols.map { evidenceOf(it) }.toTypedArray(),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, ledger)
        ledger += result.delta

        // The mapping is keyed by placeholder, so two symbols collapsing onto one placeholder shows
        // up here as a missing row rather than as a duplicate. Asserted before it is inverted, both
        // because it is the within-output half of injectivity and because a silently short mapping
        // would turn a real collision into a lookup failure with nothing to read.
        assertEquals(
            symbols.size,
            result.mapping.size,
            "two of $symbols rendered to one placeholder in a single output: ${result.mapping}",
        )

        val byName = result.mapping.entries.associate { (placeholder, name) -> name to placeholder }
        return symbols.map { it to byName.getValue(it.name) }
    }
}

private fun evidenceOf(sym: Sym) = symbol(
    name = sym.name,
    role = sym.role,
    origin = SymbolOrigin.IN_CONTENT,
    key = sym.key,
    keyIsQualified = sym.qualified,
)
