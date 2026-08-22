package com.snippetveil.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The engine, tested the way the module boundary exists to allow: plain JUnit over [SnippetPlan]
 * literals, at millisecond speed, with no IDE anywhere near it.
 *
 * Every plan here is hand-written, so each test states its own evidence and nothing is inherited
 * from a fixture. That is deliberate — a rule that is only ever exercised through a Java file is a
 * rule whose inputs you cannot see.
 */
class AnonymizeTest {

    /**
     * The spine rule: **anonymize a symbol iff its declaring file is project-owned.** The JDK and
     * third-party libraries alike are preserved, because concealing the tech stack is a declared
     * non-goal and library names are what make a snippet answerable at all.
     */
    @Test
    fun `project-owned symbols are renamed and everything else is preserved`() {
        val plan = planOf(
            "Payment payment = repository.findById(id);",
            symbol("Payment", SymbolRole.TYPE, SymbolOrigin.IN_CONTENT),
            symbol("payment", SymbolRole.LOCAL, SymbolOrigin.IN_CONTENT),
            symbol("repository", SymbolRole.FIELD, SymbolOrigin.IN_CONTENT),
            symbol("findById", SymbolRole.METHOD, SymbolOrigin.LIBRARY),
            symbol("id", SymbolRole.PARAMETER, SymbolOrigin.IN_CONTENT),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("Type1 local2 = field3.findById(param4);", result.text)
    }

    /**
     * Overloads share a name in source, so they share a placeholder. The plan reports each one's
     * signature as evidence and keys them identically; the engine collapses them by never reading
     * the signature. Emitting two placeholders here would produce a *plausible* artifact — output
     * that reads as two unrelated methods — which is the prohibited class.
     */
    @Test
    fun `overloads collapse to one placeholder`() {
        val plan = SnippetPlan(
            "void send(String body) {} void send(String body, int retries) {}",
            listOf(
                symbolAt(5, "send", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT, signature = "(String)"),
                symbolAt(31, "send", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT, signature = "(String,int)"),
            ),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("void method1(String body) {} void method1(String body, int retries) {}", result.text)
        assertEquals(mapOf("method1" to "send"), result.mapping)
    }

    /**
     * Two distinct symbols never share a placeholder, however identical they look in source. A
     * parameter shadowing a field is the case that proves it: one name, two symbols, two keys.
     */
    @Test
    fun `a parameter shadowing a field gets its own placeholder`() {
        val plan = SnippetPlan(
            "void setAmount(int amount) { this.amount = amount; }",
            listOf(
                symbolAt(19, "amount", SymbolRole.PARAMETER, SymbolOrigin.IN_CONTENT, key = "param:amount"),
                symbolAt(34, "amount", SymbolRole.FIELD, SymbolOrigin.IN_CONTENT, key = "field:amount"),
                symbolAt(43, "amount", SymbolRole.PARAMETER, SymbolOrigin.IN_CONTENT, key = "param:amount"),
            ),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("void setAmount(int param1) { this.field2 = param1; }", result.text)
        assertEquals(mapOf("param1" to "amount", "field2" to "amount"), result.mapping)
    }

    /**
     * One symbol renders identically everywhere it appears — which is the point of keying on the
     * declaration rather than on the occurrence. The plan builder resolves over the whole containing
     * file and across files, so a reference whose declaration is nowhere near the selection still
     * arrives here carrying the same key.
     */
    @Test
    fun `one symbol renders identically wherever it occurs`() {
        val plan = SnippetPlan(
            "Ledger a = new Ledger(); Ledger b = a;",
            listOf(
                symbolAt(0, "Ledger", SymbolRole.TYPE, SymbolOrigin.IN_CONTENT, key = "class:com.acme.Ledger"),
                symbolAt(15, "Ledger", SymbolRole.TYPE, SymbolOrigin.IN_CONTENT, key = "class:com.acme.Ledger"),
                symbolAt(25, "Ledger", SymbolRole.TYPE, SymbolOrigin.IN_CONTENT, key = "class:com.acme.Ledger"),
            ),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("Type1 a = new Type1(); Type1 b = a;", result.text)
    }

    /** Numbers follow document order of first occurrence, so the output reads top to bottom. */
    @Test
    fun `allocation follows document order of first occurrence`() {
        val plan = planOf(
            "zebra.apple(mango);",
            symbol("zebra", SymbolRole.FIELD, SymbolOrigin.IN_CONTENT),
            symbol("apple", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT),
            symbol("mango", SymbolRole.LOCAL, SymbolOrigin.IN_CONTENT),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("field1.method2(local3);", result.text)
    }

    /**
     * **The uniqueness invariant extends to names the engine did not touch.** A placeholder that
     * collides with an identifier surviving into the same output makes the mapping many-to-one at
     * exactly the moment a human reads the AI's reply — so the number is skipped, and skipped for
     * every role rather than retried under a different prefix.
     *
     * Here `Type1` is a preserved library class, so `1` is burnt whole: the project type takes
     * `Type2` and the counter has moved past `1` for good.
     */
    @Test
    fun `a placeholder never collides with a name that survives into the output`() {
        val plan = planOf(
            "Type1 a = new Ledger();",
            symbol("Type1", SymbolRole.TYPE, SymbolOrigin.LIBRARY),
            symbol("Ledger", SymbolRole.TYPE, SymbolOrigin.IN_CONTENT),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("Type1 a = new Type2();", result.text)
        assertEquals(3, result.delta.nextNumber)
    }

    /**
     * The collision rule reads the whole surviving text, not just the preserved symbols. A word in
     * a comment is not a symbol and no rule here owns it, but a reader holding `local1` from an
     * AI's reply cannot tell that — and the whole point of injectivity is that they never have to.
     */
    @Test
    fun `a placeholder never collides with a word in surviving prose`() {
        val plan = planOf(
            "// see local1 for the tricky case\nint amount = 0;",
            symbol("amount", SymbolRole.LOCAL, SymbolOrigin.IN_CONTENT),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("// see local1 for the tricky case\nint local2 = 0;", result.text)
    }

    /**
     * A reference that did not resolve passes through verbatim **for now**. This is a known and
     * deliberately temporary hole rather than an oversight — failing closed into an `Unknown`
     * namespace is the next ticket, and pinning today's behaviour is what makes that change show up
     * as a diff in this file rather than as a silent shift.
     */
    @Test
    fun `an unresolved reference is preserved by this ticket's rules`() {
        val plan = planOf(
            "MissingType x = undefinedVar;",
            symbol("MissingType", SymbolRole.TYPE, SymbolOrigin.UNRESOLVED),
            symbol("undefinedVar", SymbolRole.LOCAL, SymbolOrigin.UNRESOLVED),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("MissingType x = undefinedVar;", result.text)
    }

    /**
     * Nothing to anonymize is a truthful, useful reading rather than a reason to refuse: it is the
     * moment someone discovers the snippet they were worried about contains nothing of theirs.
     * Refusing would be a behavioural fork the user has to learn.
     */
    @Test
    fun `a snippet with nothing of the project's in it copies unchanged`() {
        val plan = planOf(
            "List<String> names = new ArrayList<>();",
            symbol("List", SymbolRole.TYPE, SymbolOrigin.JDK),
            symbol("ArrayList", SymbolRole.TYPE, SymbolOrigin.JDK),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("List<String> names = new ArrayList<>();", result.text)
        assertEquals(0, result.counts.replaced)
        assertTrue(result.mapping.isEmpty())
        assertTrue(result.delta.isEmpty)
    }

    /** The balloon's two numbers count distinct names, not occurrences. */
    @Test
    fun `the counts are distinct names replaced and distinct names preserved`() {
        val plan = planOf(
            "Ledger a = Ledger.of(String.valueOf(count), count);",
            symbol("Ledger", SymbolRole.TYPE, SymbolOrigin.IN_CONTENT),
            symbol("of", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT),
            symbol("count", SymbolRole.FIELD, SymbolOrigin.IN_CONTENT),
            symbol("String", SymbolRole.TYPE, SymbolOrigin.JDK),
            symbol("valueOf", SymbolRole.METHOD, SymbolOrigin.JDK),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals(3, result.counts.replaced)
        assertEquals(2, result.counts.preserved)
    }

    /**
     * The mapping is injective, which is the property every other rule in here exists to protect: a
     * reverse mapping is well-defined only if one placeholder stands for exactly one symbol.
     */
    @Test
    fun `the mapping never sends two placeholders to one another's meaning`() {
        val plan = planOf(
            "Ledger ledger = new Ledger(ledgerName);",
            symbol("Ledger", SymbolRole.TYPE, SymbolOrigin.IN_CONTENT, key = "class:com.acme.Ledger"),
            symbol("ledger", SymbolRole.LOCAL, SymbolOrigin.IN_CONTENT, key = "local:ledger"),
            symbol("ledgerName", SymbolRole.PARAMETER, SymbolOrigin.IN_CONTENT, key = "param:ledgerName"),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals(result.mapping.size, result.mapping.keys.size)
        assertEquals(3, result.mapping.size)
    }

    /**
     * Literals and comments are described by the plan and left alone by this ticket's rules. They
     * are in the plan because it describes the snippet rather than a work list; the tickets that
     * act on them read the same shapes.
     */
    @Test
    fun `literals and comments are reported and left untouched`() {
        val text = "// note\nString s = \"merchant\";"
        val plan = SnippetPlan(
            text,
            listOf(
                CommentOccurrence(0, 7),
                symbolAt(8, "String", SymbolRole.TYPE, SymbolOrigin.JDK),
                LiteralOccurrence(19, 29),
            ),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals(text, result.text)
    }
}

/**
 * A plan over [text] whose occurrences are located by searching [text] for each symbol's name, so a
 * test states the code and the evidence and never an offset. Each symbol is keyed by its own name,
 * which is what a test wants by default: same name, same symbol.
 */
internal fun planOf(text: String, vararg symbols: SymbolEvidence): SnippetPlan {
    val occurrences = mutableListOf<Occurrence>()
    for (symbol in symbols) {
        var from = 0
        while (true) {
            val at = text.indexOf(symbol.declaredName, from).takeIf { it >= 0 } ?: break
            occurrences += SymbolOccurrence(at, at + symbol.declaredName.length, symbol.declaredName, symbol)
            from = at + symbol.declaredName.length
        }
    }
    return SnippetPlan(text, occurrences.sortedBy { it.start })
}

internal fun symbol(
    name: String,
    role: SymbolRole,
    origin: SymbolOrigin,
    key: String = name,
    signature: String? = null,
) = SymbolEvidence(key, role, origin, name, signature)

internal fun symbolAt(
    start: Int,
    name: String,
    role: SymbolRole,
    origin: SymbolOrigin,
    key: String = name,
    signature: String? = null,
) = SymbolOccurrence(start, start + name.length, name, SymbolEvidence(key, role, origin, name, signature))
