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
     *
     * Stated over a comment this invocation **kept**, because that is the only way prose survives:
     * the default strips it, and a word that was deleted stands for nothing. See
     * [CommentStrippingTest] for the other half of that.
     */
    @Test
    fun `a placeholder never collides with a word in surviving prose`() {
        val plan = planOf(
            "// see local1 for the tricky case\nint amount = 0;",
            symbol("amount", SymbolRole.LOCAL, SymbolOrigin.IN_CONTENT),
        ).withComment("// see local1 for the tricky case", CommentVerdict.PROSE)

        val result = anonymize(plan, AnonymizationSettings(keepComments = true), LedgerSnapshot.EMPTY)

        assertEquals("// see local1 for the tricky case\nint local2 = 0;", result.text)
    }

    /**
     * **A reference that fails to resolve fails closed into its own namespace.** Passing it through
     * verbatim is the silent privacy hole this rule exists to close: the snippet a developer is
     * debugging is exactly the one most likely to contain red code, so this is the common case
     * rather than an edge case.
     */
    @Test
    fun `an unresolved reference is replaced rather than passed through`() {
        val plan = planOf(
            "MissingType x = undefinedVar;",
            symbol("MissingType", SymbolRole.TYPE, SymbolOrigin.UNRESOLVED),
            symbol("undefinedVar", SymbolRole.LOCAL, SymbolOrigin.UNRESOLVED),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("Unknown1 x = Unknown2;", result.text)

        // And they are in the mapping like any other placeholder: a reader holding `Unknown1` out of
        // the AI's reply has the same claim on knowing what it stood for as one holding `Type1`.
        assertEquals(mapOf("Unknown1" to "MissingType", "Unknown2" to "undefinedVar"), result.mapping)
    }

    /**
     * `Unknown` is a namespace, not a private counter: it draws from the same shared counter every
     * other role draws from, and it burns a number that would collide with a name surviving into the
     * output exactly as they do. A reader holding `Unknown1` from an AI's reply has no more scope
     * context than one holding `Type1`, so the invariant is identical and so is the rule.
     */
    @Test
    fun `an Unknown placeholder shares the counter and never collides with a surviving name`() {
        val plan = planOf(
            "Unknown1 mystery = Ledger.of();",
            symbol("Unknown1", SymbolRole.TYPE, SymbolOrigin.LIBRARY),
            symbol("mystery", SymbolRole.LOCAL, SymbolOrigin.UNRESOLVED),
            symbol("Ledger", SymbolRole.TYPE, SymbolOrigin.IN_CONTENT),
            symbol("of", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        // `Unknown1` is a preserved library class here, so the unresolved name asking for number 1
        // burns it whole rather than retrying under another prefix — and the project symbols after
        // it carry on from the same counter, which is what makes the whole output sortable by eye.
        assertEquals("Unknown1 Unknown2 = Type3.method4();", result.text)
        assertEquals(5, result.delta.nextNumber)
    }

    /**
     * **The result lists every unresolved name against the placeholder it became.** The preview
     * dialog's per-item `Preserve` is built on exactly this: it needs the real name to show, the
     * placeholder to show it against, and the key to hand back through [AnonymizationSettings]. A
     * count alone would name a surface the user cannot act on.
     */
    @Test
    fun `the result lists every unresolved name against its placeholder`() {
        val plan = planOf(
            "MissingType x = undefinedVar;",
            symbol("MissingType", SymbolRole.TYPE, SymbolOrigin.UNRESOLVED, key = "unresolved:MissingType"),
            symbol("undefinedVar", SymbolRole.LOCAL, SymbolOrigin.UNRESOLVED, key = "unresolved:undefinedVar"),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        // In document order of first occurrence, which is the order the dialog's rows are sorted in.
        assertEquals(
            listOf(
                Triple("unresolved:MissingType", "MissingType", "Unknown1"),
                Triple("unresolved:undefinedVar", "undefinedVar", "Unknown2"),
            ),
            result.unknowns.map { Triple(it.key, it.name, it.placeholder) },
        )
    }

    /**
     * **The one deliberate fail-open in the product**, and it is bought rather than assumed away: a
     * typo'd JDK call hidden behind a placeholder makes a snippet unanswerable, so one unresolved
     * item at a time can be released.
     *
     * It is **per-invocation only, never persistent**, because the governing rule is that persistent
     * settings may only ever increase anonymization — a reduction that can be set once and forgotten
     * silently leaks on every paste after it.
     */
    @Test
    fun `preserving an unresolved name emits it verbatim`() {
        val plan = planOf(
            "MissingType x = undefinedVar;",
            symbol("MissingType", SymbolRole.TYPE, SymbolOrigin.UNRESOLVED, key = "unresolved:MissingType"),
            symbol("undefinedVar", SymbolRole.LOCAL, SymbolOrigin.UNRESOLVED, key = "unresolved:undefinedVar"),
        )

        val result = anonymize(
            plan,
            AnonymizationSettings(preservedUnknowns = setOf("unresolved:MissingType")),
            LedgerSnapshot.EMPTY,
        )

        assertEquals("MissingType x = Unknown1;", result.text)

        // The row stays in the list with no placeholder, so the dialog can offer to take the
        // override back. And the count is unmoved: it reports what the IDE could not resolve, which
        // the override does not change.
        assertEquals(listOf(null, "Unknown1"), result.unknowns.map { it.placeholder })
        assertEquals(2, result.counts.unknown)
    }

    /**
     * **The override must not creep.** A preserve that reached resolved symbols would be the
     * free-text preserve list this design already rejected, built out of keys instead of text — and
     * it would put a reduction on the spine rule, which is the one thing no setting may touch. So
     * the engine ignores a key that does not name an unresolved symbol rather than trusting whoever
     * assembled the set.
     */
    @Test
    fun `a preserve override cannot reach a resolved project-owned symbol`() {
        val plan = planOf(
            "Ledger ledger;",
            symbol("Ledger", SymbolRole.TYPE, SymbolOrigin.IN_CONTENT, key = "class:com.acme.Ledger"),
        )

        val result = anonymize(
            plan,
            AnonymizationSettings(preservedUnknowns = setOf("class:com.acme.Ledger")),
            LedgerSnapshot.EMPTY,
        )

        assertEquals("Type1 ledger;", result.text)
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
        assertTrue(result.delta.placeholders.isEmpty())
        assertEquals(LedgerSnapshot.EMPTY.nextNumber, result.delta.nextNumber)
    }

    /**
     * **The three counts partition the distinct names in the snippet, by evidence rather than by
     * outcome.** `IN_CONTENT` is replaced, `UNRESOLVED` is unknown, the JDK and libraries are
     * preserved — so the numbers add up to what is in the snippet, and no name is counted twice.
     *
     * The `Unknown` count is broken out rather than folded into `replaced` because it is the one
     * honest quality signal the tool has. Folding it in would hide it; giving it a warning's styling
     * would invert it, because under fail-closed an `Unknown` *was* anonymized — it is a quality
     * risk, never a privacy one.
     */
    @Test
    fun `the counts partition the snippet into replaced unknown and preserved`() {
        val plan = planOf(
            "Ledger a = Ledger.open(mystery, String.valueOf(count));",
            symbol("Ledger", SymbolRole.TYPE, SymbolOrigin.IN_CONTENT),
            symbol("open", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT),
            symbol("mystery", SymbolRole.LOCAL, SymbolOrigin.UNRESOLVED),
            symbol("count", SymbolRole.FIELD, SymbolOrigin.IN_CONTENT),
            symbol("String", SymbolRole.TYPE, SymbolOrigin.JDK),
            symbol("valueOf", SymbolRole.METHOD, SymbolOrigin.JDK),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals(3, result.counts.replaced)
        assertEquals(1, result.counts.unknown)
        assertEquals(2, result.counts.preserved)
    }

    /** The balloon's numbers count distinct names, not occurrences. */
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
     * A comment reported by the plan is **removed**, and the code around it is untouched — the rules
     * that act on names do not see a comment at all. See [CommentStrippingTest] for the whole of
     * that rule; this states the one line of it the spine cares about.
     */
    @Test
    fun `a comment is stripped and its line goes with it`() {
        val text = "// note about the merchant\nint days = 30;"
        val plan = SnippetPlan(
            text,
            listOf(
                CommentOccurrence(0, 26, CommentVerdict.PROSE),
                symbolAt(31, "days", SymbolRole.LOCAL, SymbolOrigin.IN_CONTENT),
                LiteralOccurrence(38, 40, LiteralKind.NUMBER, 38, 40),
            ),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("int local1 = 30;", result.text)
    }

    /**
     * **A type parameter is anonymized as its own kind.** `<T>` carries no domain and
     * `<REQ extends MerchantRequest>` does, and no rule keeps the one and replaces the other:
     * preserving by name length is inspecting the text.
     */
    @Test
    fun `a type parameter renames into its own namespace`() {
        val plan = planOf(
            "class Handler<REQ extends MerchantRequest> {}",
            symbol("Handler", SymbolRole.TYPE, SymbolOrigin.IN_CONTENT),
            symbol("REQ", SymbolRole.TYPE_PARAMETER, SymbolOrigin.IN_CONTENT),
            symbol("MerchantRequest", SymbolRole.TYPE, SymbolOrigin.IN_CONTENT),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("class Type1<T2 extends Type3> {}", result.text)
    }

    /**
     * **A label is anonymized as its own kind**, and it is project-owned by construction: a label is
     * declared in the method it is jumped from, so there is no library that could own one.
     */
    @Test
    fun `a label renames into its own namespace`() {
        val plan = planOf(
            "outer: for (int i = 0; i < 10; i++) { break outer; }",
            symbol("outer", SymbolRole.LABEL, SymbolOrigin.IN_CONTENT),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("label1: for (int i = 0; i < 10; i++) { break label1; }", result.text)
    }
}
