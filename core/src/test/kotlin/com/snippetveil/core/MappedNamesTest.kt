package com.snippetveil.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * **The preview's rows, decided here rather than in the dialog.**
 *
 * What the table shows is a statement about the invocation, not about a widget, so it is settled in
 * the module that can be tested against a plan literal in milliseconds. The dialog sorts and renders
 * it and decides nothing about what is in it — which is what makes the read-only re-open the same
 * object re-rendered rather than a second code path.
 *
 * Two rules carry the weight, and both are about what is **not** here: a preserved library symbol is
 * a number and never a row, because it is a row the user can do nothing about and `String`, `List`
 * and `println` would drown the ones that matter; and an unresolved name is a row *even when it was
 * preserved*, because the tick that preserved it lives on that row.
 */
class MappedNamesTest {

    /**
     * Every placeholder in the output has a row, in the order the output introduces them, and each
     * says what kind of thing it stood for. The kind is what makes the table readable at a glance —
     * `param1` and `field1` are told apart by their prefix, and `Unknown1` by nothing else at all.
     */
    @Test
    fun `every placeholder is a row, in first-occurrence order, with its kind`() {
        val plan = planOf(
            "Money settle(int amount) { Money total = amount; }",
            symbol("Money", SymbolRole.TYPE, SymbolOrigin.IN_CONTENT),
            symbol("settle", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT),
            symbol("amount", SymbolRole.PARAMETER, SymbolOrigin.IN_CONTENT),
            symbol("total", SymbolRole.LOCAL, SymbolOrigin.IN_CONTENT),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("Type1 method2(int param3) { Type1 local4 = param3; }", result.text)
        assertEquals(
            listOf(
                Triple("Money", "Type1", MappedKind.TYPE),
                Triple("settle", "method2", MappedKind.METHOD),
                Triple("amount", "param3", MappedKind.PARAMETER),
                Triple("total", "local4", MappedKind.LOCAL),
            ),
            result.names.map { Triple(it.original, it.placeholder, it.kind) },
        )
    }

    /**
     * **Preserved JDK and third-party symbols are not rows.** Their preservation is deliberate and a
     * declared non-goal, so each would be a row offering the user nothing to do — and there are
     * enough of them to bury the ones that matter. They become a number instead.
     */
    @Test
    fun `preserved library and JDK names are a count rather than rows`() {
        val plan = planOf(
            "List<String> names = ledger.all();",
            symbol("List", SymbolRole.TYPE, SymbolOrigin.JDK),
            symbol("String", SymbolRole.TYPE, SymbolOrigin.JDK),
            symbol("names", SymbolRole.LOCAL, SymbolOrigin.IN_CONTENT),
            symbol("ledger", SymbolRole.FIELD, SymbolOrigin.IN_CONTENT),
            symbol("all", SymbolRole.METHOD, SymbolOrigin.LIBRARY),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals(listOf("names", "ledger"), result.names.map { it.original })
        assertEquals(3, result.counts.preserved)
    }

    /**
     * **A name is a row whether or not this invocation preserved it**, and that is the whole
     * mechanism of the one reduction the design authorises: the tick sits on the row, so a row that
     * disappeared when ticked could not be unticked. A preserved row carries no placeholder, because
     * it stands for itself and a row mapping a name to itself maps nothing.
     *
     * Written here over unresolved names because that is where the mechanism started; it is the same
     * mechanism for a resolved name, asserted in [AnonymizeTest].
     */
    @Test
    fun `an unresolved name is a row whether or not it was preserved`() {
        val plan = planOf(
            "MissingType x = undefinedVar;",
            symbol("MissingType", SymbolRole.TYPE, SymbolOrigin.UNRESOLVED, key = "unresolved:MissingType"),
            symbol("undefinedVar", SymbolRole.LOCAL, SymbolOrigin.UNRESOLVED, key = "unresolved:undefinedVar"),
        )

        val replaced = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals(
            listOf(
                Triple("MissingType", "Unknown1", MappedKind.UNKNOWN),
                Triple("undefinedVar", "Unknown2", MappedKind.UNKNOWN),
            ),
            replaced.names.map { Triple(it.original, it.placeholder, it.kind) },
        )

        val preserved = anonymize(
            plan,
            AnonymizationSettings(preservedSymbols = setOf("unresolved:MissingType")),
            LedgerSnapshot.EMPTY,
        )

        assertEquals("MissingType x = Unknown1;", preserved.text)
        assertEquals(
            listOf(
                Triple("MissingType", null, "unresolved:MissingType"),
                Triple("undefinedVar", "Unknown1", "unresolved:undefinedVar"),
            ),
            preserved.names.map { Triple(it.original, it.placeholder, it.key) },
        )

        // And it is out of the mapping rather than in it under its own name: the mapping is what a
        // reply is decoded against, and a preserved name never needed decoding.
        assertEquals(mapOf("Unknown1" to "undefinedVar"), preserved.mapping)
    }

    /**
     * The kind of an unresolved name is `Unknown` **whatever role the plan reported**, for the same
     * reason its placeholder is: nothing resolved it, so the role is a guess, and the placeholder
     * does not carry it either. A table saying `method` about a name the IDE could not find would be
     * making a claim the invocation never made.
     */
    @Test
    fun `an unresolved name reports Unknown rather than the role the plan guessed`() {
        val plan = planOf(
            "settle();",
            symbol("settle", SymbolRole.METHOD, SymbolOrigin.UNRESOLVED, key = "unresolved:settle"),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals(listOf(MappedKind.UNKNOWN), result.names.map { it.kind })
    }

    /** One symbol met a dozen times is one row: the table is a table of names, not of occurrences. */
    @Test
    fun `one symbol met many times is one row`() {
        val plan = planOf(
            "settle(); settle(); settle();",
            symbol("settle", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals(listOf("settle"), result.names.map { it.original })
    }

    /**
     * A replaced literal is a row of its own kind, and it is the row with **no key**: a literal has
     * nothing to be keyed by, which is also why it is never written into the persistent mapping and
     * why the invocation's own table is the only place its text survives.
     */
    @Test
    fun `a redacted literal is a row of its own kind and carries no key`() {
        val plan = planOf(
            """String reason = "merchant settlement failed";""",
            symbol("reason", SymbolRole.LOCAL, SymbolOrigin.IN_CONTENT),
        ).withLiteral(""""merchant settlement failed"""")

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("""String local1 = "str2";""", result.text)

        val literal = result.names.single { it.kind == MappedKind.LITERAL }
        assertEquals("merchant settlement failed", literal.original)
        assertEquals("str2", literal.placeholder)
        assertNull(literal.key)
    }

    /**
     * **Two symbols forced to share a placeholder are one row, not two rows saying the same thing.**
     *
     * An override and its root are distinct symbols with distinct keys, and Java requires them to
     * share a name — so they share a placeholder, and the table has one thing to say about them. It
     * is why a row is filed under what it renders as rather than under whose key it came from; see
     * [ForcedSharingTest] for the rule that puts them together in the first place.
     */
    @Test
    fun `two symbols that share a placeholder are one row`() {
        val root = symbol("name", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT, key = "method:class:com.acme.Named#name")
        val overriding = symbol(
            "name",
            SymbolRole.METHOD,
            SymbolOrigin.IN_CONTENT,
            key = "method:class:com.acme.Payment#name",
            overrideRoots = listOf(OverrideRoot(root.key, SymbolOrigin.IN_CONTENT)),
        )
        val plan = planPlacing(
            "interface Named { String name(); }\nclass Payment implements Named { public String name() { return null; } }",
            at(0, root),
            at(1, overriding),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals(listOf("method1"), result.names.map { it.placeholder })
    }

    /**
     * A row for a symbol carries the key a preserve override is expressed in — which is what lets
     * the dialog hand one back through [AnonymizationSettings.preservedSymbols] without the table
     * having to be joined to a second list to find it.
     */
    @Test
    fun `a symbol row carries the key a preserve override is expressed in`() {
        val plan = planOf(
            "Ledger ledger;",
            symbol("Ledger", SymbolRole.TYPE, SymbolOrigin.IN_CONTENT, key = "class:com.acme.Ledger"),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals(listOf("class:com.acme.Ledger"), result.names.map { it.key })
    }

    /**
     * **And the key it carries is the one the placeholder was handed out against**, which is what
     * makes one tick on a shared row preserve the whole override chain.
     *
     * The two keys part company exactly here: an override and its root are distinct symbols that
     * Java forbids from diverging, so they are one row. A row keyed by whichever of them happened to
     * be met first would preserve that one and rename the other — a declaration renamed and its call
     * site kept, which is the incoherence the sharing rule exists to prevent, arriving through the
     * tick that was meant to avoid it.
     */
    @Test
    fun `a shared row is keyed by the shared key, so one tick preserves the whole chain`() {
        val root = symbol("name", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT, key = "method:class:com.acme.Named#name")
        val overriding = symbol(
            "name",
            SymbolRole.METHOD,
            SymbolOrigin.IN_CONTENT,
            key = "method:class:com.acme.Payment#name",
            overrideRoots = listOf(OverrideRoot(root.key, SymbolOrigin.IN_CONTENT)),
        )
        val text = "interface Named { String name(); }\nclass Payment implements Named { public String name() { return null; } }"
        val plan = planPlacing(text, at(0, root), at(1, overriding))

        val shared = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY).names.single().key

        assertEquals("method:class:com.acme.Named#name", shared)

        val preserved = anonymize(
            plan,
            AnonymizationSettings(preservedSymbols = setOfNotNull(shared)),
            LedgerSnapshot.EMPTY,
        )

        assertEquals(text, preserved.text)
        assertEquals(listOf(null), preserved.names.map { it.placeholder })
    }
}
