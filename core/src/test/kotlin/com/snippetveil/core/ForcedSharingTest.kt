package com.snippetveil.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **Which distinct symbols collapse onto one placeholder, because Java's grammar forbids them from
 * diverging.** A closed list of five, each one tested here against a plan literal.
 *
 * The reframe that decides their shape: anonymized output is *read*, never compiled or run. So the
 * damage from getting one wrong is not a broken override — it is that the output is **visibly
 * contradictory**, and a reviewer reports a non-override as a bug that does not exist upstream.
 * Compilation is a proxy for coherence; coherence is the goal.
 *
 * Which is also why every assertion here **names its rule**. These five are statements about
 * entries agreeing, and a golden file encodes that only accidentally: when one of them dies, a
 * golden's diff shows a name change and leaves the reader to reason backwards to which of the five
 * broke. See [assertShared].
 */
class ForcedSharingTest {

    // ---------------------------------------------------------------- Rule 1: overloads

    /**
     * **Rule 1 — overloads share a name in source, so they share a placeholder.** The plan reports
     * each one's signature as evidence and keys them identically; the engine collapses them by never
     * reading the signature. Emitting two placeholders here would produce output that reads as two
     * unrelated methods — a *plausible* artifact, which is the prohibited class.
     *
     * The engine's half of this rule is *ignoring the signature*, which is what the two differing
     * signatures below are here to catch. That the builder keys two overloads identically in the
     * first place is asserted where it happens, in `JavaPlanBuilderTest`.
     */
    @Test
    fun `overloads collapse onto one placeholder`() {
        val text = "void send(String body) {} void send(String body, int retries) {}"
        val plan = planPlacing(
            text,
            at(0, symbol("send", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT, signature = "(String)")),
            at(1, symbol("send", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT, signature = "(String,int)")),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        result.assertShared(OVERLOADS, "send")
        assertEquals(
            "void method1(String body) {} void method1(String body, int retries) {}",
            result.text,
            OVERLOADS,
        )
    }

    // ------------------------------------------------- Rule 2: override chains key by their root

    /**
     * **Rule 2 — an override chain keys by the root of `findDeepestSuperMethods()`, not by the
     * declaring class.** Keying by the declaring class split an interface from its implementation,
     * so the `@Override` no longer implemented anything: the same call arrived at two different
     * method names depending on which type the reader was looking at.
     *
     * This is one of the three regressions the throwaway spike found, re-expressed here — and
     * re-expressed regressions are the ones that need most care, because a rewrite has never failed
     * in its life. Green here is ambiguous between *the bug is fixed* and *the plan literal never
     * reproduced the condition*, and from outside those look identical. So it is paired with
     * [`the same chain with its root evidence removed splits the interface from its implementation`],
     * which takes away the one piece of evidence and asserts the split — **the pairing is what makes
     * the assertion count**, and it keeps saying so long after the afternoon this was written.
     *
     * It was also demonstrated red the blunt way before being accepted, by making [sharedKeyOf]
     * return `symbol.key` unconditionally.
     */
    @Test
    fun `an override chain renders as one placeholder at every site`() {
        val text = """
            interface Named { String name(); }
            class Payment implements Named { public String name() { return "x"; } }
            String n = payment.name();
        """.trimIndent()

        val root = symbol("name", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT, key = "method:class:com.acme.Named#name")
        val overriding = symbol(
            "name",
            SymbolRole.METHOD,
            SymbolOrigin.IN_CONTENT,
            key = "method:class:com.acme.Payment#name",
            overrideRoots = listOf(OverrideRoot(root.key, SymbolOrigin.IN_CONTENT)),
        )

        val plan = planPlacing(text, at(0, root), at(1, overriding), at(2, root))

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        result.assertShared(OVERRIDE_ROOT, "name")
        assertEquals(3, Regex("""\bmethod\d+\b""").findAll(result.text).count())
    }

    /**
     * **The control for the test above.** The same snippet and the same two symbols, with the
     * override root taken away — and the output is the spike's bug verbatim: `method1` on the
     * interface, `method2` on the implementation, and an `@Override` that no longer implements
     * anything a reader of the output can see.
     */
    @Test
    fun `the same chain with its root evidence removed splits the interface from its implementation`() {
        val text = """
            interface Named { String name(); }
            class Payment implements Named { public String name() { return "x"; } }
            String n = payment.name();
        """.trimIndent()

        val root = symbol("name", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT, key = "method:class:com.acme.Named#name")
        // The one difference from the test above: no root, so nothing ties the two symbols together.
        val overriding = symbol(
            "name",
            SymbolRole.METHOD,
            SymbolOrigin.IN_CONTENT,
            key = "method:class:com.acme.Payment#name",
        )

        val plan = planPlacing(text, at(0, root), at(1, overriding), at(2, root))

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        result.assertDistinct(OVERRIDE_ROOT, "name", count = 2)
    }

    /**
     * The root is chosen **lexicographically**, and every symbol in the chain computes the same one
     * from its own evidence alone. That last clause is the load-bearing one: a key that depended on
     * which other symbols happened to be selected could not be persisted across invocations, which
     * is what stable placeholders are built on.
     */
    @Test
    fun `the shared key is the lexicographically smallest root, whichever end asks`() {
        val text = "interface Audited { void run(); } class Payment implements Audited { public void run() {} }"
        val root = symbol(
            "run",
            SymbolRole.METHOD,
            SymbolOrigin.IN_CONTENT,
            key = "method:class:com.acme.Audited#run",
            keyIsQualified = true,
        )
        val overriding = symbol(
            "run",
            SymbolRole.METHOD,
            SymbolOrigin.IN_CONTENT,
            key = "method:class:com.acme.Payment#run",
            // Reported unordered, and the smaller of the two decides — so a second interface added
            // to the chain cannot silently re-point an already-issued placeholder.
            overrideRoots = listOf(
                OverrideRoot("method:class:com.acme.Zebra#run", SymbolOrigin.IN_CONTENT, keyIsQualified = true),
                OverrideRoot(root.key, SymbolOrigin.IN_CONTENT, keyIsQualified = true),
            ),
            keyIsQualified = true,
        )

        val plan = planPlacing(text, at(0, root), at(1, overriding))

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        result.assertShared(OVERRIDE_ROOT, "run")
        assertEquals(mapOf("method:class:com.acme.Audited#run" to MintedName("method1", "run")), result.delta.placeholders)
    }

    // ------------------------------------------- Rule 3: accessors derive from their backing field

    /**
     * **Rule 3 — an accessor's placeholder is derived from its backing field's**, so
     * `merchantId → field1` implies `getMerchantId() → getField1()`.
     */
    @Test
    fun `a getter derives its placeholder from the field it reads`() {
        val text = "private String merchantId; public String getMerchantId() { return merchantId; }"
        val field = symbol("merchantId", SymbolRole.FIELD, SymbolOrigin.IN_CONTENT, key = "field:Payment#merchantId")
        val plan = planPlacing(
            text,
            at(0, field),
            at(0, symbol(
                "getMerchantId",
                SymbolRole.METHOD,
                SymbolOrigin.IN_CONTENT,
                key = "method:Payment#getMerchantId",
                accessor = AccessorEvidence(field.key, field.declaredName, "get"),
            )),
            at(1, field),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        result.assertRendersAs(ACCESSORS, "merchantId", "field1")
        result.assertRendersAs(ACCESSORS, "getMerchantId", "getField1")
        assertEquals("private String field1; public String getField1() { return field1; }", result.text)
    }

    /** A setter and a boolean getter derive the same way; the prefix is whatever the source wrote. */
    @Test
    fun `a setter and a boolean getter derive from their fields too`() {
        val text = "void setAmount(int amount) {} boolean isSettled() { return settled; }"
        val amount = symbol("amount", SymbolRole.FIELD, SymbolOrigin.IN_CONTENT, key = "field:Payment#amount")
        val settled = symbol("settled", SymbolRole.FIELD, SymbolOrigin.IN_CONTENT, key = "field:Payment#settled")

        val plan = planPlacing(
            text,
            at(0, symbol(
                "setAmount", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT,
                key = "method:Payment#setAmount", accessor = AccessorEvidence(amount.key, amount.declaredName, "set"),
            )),
            at(0, symbol("amount", SymbolRole.PARAMETER, SymbolOrigin.IN_CONTENT, key = "param:amount")),
            at(0, symbol(
                "isSettled", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT,
                key = "method:Payment#isSettled", accessor = AccessorEvidence(settled.key, settled.declaredName, "is"),
            )),
            at(0, settled),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        result.assertRendersAs(ACCESSORS, "setAmount", "setField1")
        result.assertRendersAs(ACCESSORS, "isSettled", "isField3")
        assertEquals("void setField1(int param2) {} boolean isField3() { return field3; }", result.text)
    }

    /**
     * **The Lombok case, which is what rule 3 is really for.** With `@Getter` the accessor has no
     * declaration in source at all — only call sites — and its backing field is nowhere near the
     * selection. An unrelated placeholder would leave a method with no visible origin: `Type1` would
     * expose a `method2()` that nothing in the output explains.
     *
     * So the field's placeholder is allocated on the strength of its key alone, even though the
     * field itself is not in the snippet. The number it burns is the point of a shared counter.
     */
    @Test
    fun `a Lombok accessor with no declaration in source still derives from its field`() {
        val text = "String ref = payment.getMerchantId();"
        val plan = planPlacing(
            text,
            at(0, symbol("payment", SymbolRole.LOCAL, SymbolOrigin.IN_CONTENT, key = "local:payment")),
            at(0, symbol(
                "getMerchantId",
                SymbolRole.METHOD,
                SymbolOrigin.IN_CONTENT,
                key = "method:class:com.acme.Payment#getMerchantId",
                accessor = AccessorEvidence(
                    "field:class:com.acme.Payment#merchantId",
                    "merchantId",
                    "get",
                    fieldKeyIsQualified = true,
                ),
                keyIsQualified = true,
            )),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("String ref = local1.getField2();", result.text)
        result.assertRendersAs(ACCESSORS, "getMerchantId", "getField2")

        // The absent field is in the delta under its own key: the next invocation, which may well
        // show the field itself, has to reach the same name for it.
        assertEquals(MintedName("field2", "merchantId"), result.delta.placeholders["field:class:com.acme.Payment#merchantId"])
    }

    /**
     * **Fluent and builder accessors are deliberately not covered.** `merchantId()` next to a field
     * `merchantId` is a convention rather than a grammar rule — nothing in Java forces the two names
     * to agree — and this list is the closed set of things that are forced. The plan reports no
     * accessor evidence for one, and the method takes an ordinary placeholder of its own.
     */
    @Test
    fun `a fluent accessor is not treated as one`() {
        val text = "private String merchantId; public String merchantId() { return merchantId; }"
        val field = symbol("merchantId", SymbolRole.FIELD, SymbolOrigin.IN_CONTENT, key = "field:Payment#merchantId")
        val fluent = symbol("merchantId", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT, key = "method:Payment#merchantId")

        val plan = planPlacing(text, at(0, field), at(1, fluent), at(2, field))

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        result.assertDistinct(ACCESSORS, "merchantId", count = 2)
        assertEquals(
            "private String field1; public String method2() { return field1; }",
            result.text,
            "$ACCESSORS: a fluent accessor takes an ordinary placeholder of its own",
        )
    }

    /**
     * **Injectivity outranks coherence on the one input where they collide.** A derived name that
     * already means something else in the output would make the reverse mapping many-to-one at
     * exactly the moment a human reads the AI's reply — so the accessor falls back to a placeholder
     * of its own. A split accessor costs the reader a hop; an ambiguous placeholder costs them the
     * answer.
     */
    @Test
    fun `a derived accessor name that collides with a surviving name falls back`() {
        // `getField1` is a preserved library method here, so the derivation cannot have it.
        val text = "String x = payment.getMerchantId(); String y = getField1();"
        val plan = planPlacing(
            text,
            at(0, symbol("payment", SymbolRole.FIELD, SymbolOrigin.LIBRARY, key = "field:lib.Ctx#payment")),
            at(0, symbol(
                "getMerchantId",
                SymbolRole.METHOD,
                SymbolOrigin.IN_CONTENT,
                key = "method:Payment#getMerchantId",
                accessor = AccessorEvidence("field:Payment#merchantId", "merchantId", "get"),
            )),
            at(0, symbol("getField1", SymbolRole.METHOD, SymbolOrigin.LIBRARY, key = "method:lib.Ctx#getField1")),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertTrue(
            "getField1" !in result.mapping,
            "$ACCESSORS: a name that survives verbatim may not also be a placeholder",
        )
        assertEquals(
            "String x = payment.method2(); String y = getField1();",
            result.text,
            "$ACCESSORS: a derived name that is taken falls back rather than colliding",
        )
    }

    // ------------------------------------------------------- Rule 4: constructor ↔ declaring type

    /**
     * **Rule 4 — a constructor's identifier *is* the class name.** Emitted out of the method
     * namespace it produces `public method1(String param1)` inside `class Type1`: a method with no
     * return type, which does not read as anonymized — **it reads as broken.**
     *
     * The plan carries this as identity rather than as policy: the identifier in `public Payment()`
     * names the class, so the builder reports the class's own key and role for it, and the engine
     * does what it does for any other two occurrences of one symbol.
     */
    @Test
    fun `a constructor renders as its declaring type's placeholder`() {
        val text = "class Payment { Payment(String ref) { this.ref = ref; } }"
        val type = symbol("Payment", SymbolRole.TYPE, SymbolOrigin.IN_CONTENT, key = "class:com.acme.Payment")
        val plan = planPlacing(
            text,
            at(0, type),
            at(1, type),
            at(0, symbol("ref", SymbolRole.PARAMETER, SymbolOrigin.IN_CONTENT, key = "param:ref")),
            at(1, symbol("ref", SymbolRole.FIELD, SymbolOrigin.IN_CONTENT, key = "field:Payment#ref")),
            at(2, symbol("ref", SymbolRole.PARAMETER, SymbolOrigin.IN_CONTENT, key = "param:ref")),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        result.assertRendersAs(CONSTRUCTORS, "Payment", "Type1")
        assertEquals("class Type1 { Type1(String param2) { this.field3 = param2; } }", result.text)
    }

    // --------------------------------------- Rule 5: record component ↔ field ↔ accessor

    /**
     * **Rule 5 — a record component, its implicit field and its implicit accessor are one declared
     * symbol with three PSI faces**, and Java forces all three to agree.
     *
     * The sharp end of it: **the accessor IS that placeholder, not `getField1()`.** Records carry no
     * `get` prefix, so rule 3's derivation would be actively wrong here — it would emit an accessor
     * the record does not declare, next to a component the record does.
     *
     * The engine's half is that last clause: given one key and no accessor evidence, it must not
     * reach for rule 3 anyway. That the builder reports one key and no accessor evidence for all
     * three faces is asserted against a real record in `NameRuleEvidenceTest`.
     */
    @Test
    fun `a record component its field and its accessor are one placeholder`() {
        val text = """
            record Payment(String merchantRef) {
                String masked() { return merchantRef.substring(2); }
            }
            String r = payment.merchantRef();
        """.trimIndent()

        // One symbol, one key, three faces — the component, the implicit field, the accessor.
        val component = symbol(
            "merchantRef",
            SymbolRole.FIELD,
            SymbolOrigin.IN_CONTENT,
            key = "field:class:com.acme.Payment#merchantRef",
        )
        val plan = planPlacing(text, at(0, component), at(1, component), at(2, component))

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        result.assertRendersAs(RECORDS, "merchantRef", "field1")
        assertTrue(
            "getField1" !in result.text,
            "$RECORDS: a record accessor carries no `get` prefix, so rule 3's derivation must not fire\n${result.text}",
        )
        assertTrue("field1()" in result.text, "$RECORDS: the accessor must be the component's placeholder\n${result.text}")
    }

    /**
     * **The fourth face of that same symbol: a compact constructor's implicit parameters.** A compact
     * constructor declares no parameter list — the JLS gives it one, named after the components — so
     * `merchantRef` in the body below *is* the component, and the record's own placeholder is what
     * has to appear there.
     *
     * Keyed apart it produced `param3` inside a record that declares no such thing: not a broken
     * override but **visibly contradictory output**, which is the prohibited class. A reader — human
     * or model — asked about the snippet has to account for a name nothing in it introduces, and
     * compact constructors are the idiomatic way to validate a record, so this is not a corner.
     *
     * **This test is green with no engine change, and that is the claim it makes.** The fix is in the
     * walk, which reports one key for both occurrences; the engine's half is to do with two
     * occurrences of one symbol what it already does with any other two. Written here so that the
     * fourth face is stated where the other five are, and so that an engine that later grew a special
     * case for record parameters would have to fail something.
     */
    @Test
    fun `a compact constructor's body renders the component's placeholder`() {
        val text = """
            record Payment(String merchantRef) {
                Payment {
                    if (merchantRef == null) throw new IllegalArgumentException();
                }
            }
        """.trimIndent()

        val type = symbol("Payment", SymbolRole.TYPE, SymbolOrigin.IN_CONTENT, key = "class:com.acme.Payment")
        val component = symbol(
            "merchantRef",
            SymbolRole.FIELD,
            SymbolOrigin.IN_CONTENT,
            key = "field:class:com.acme.Payment#merchantRef",
        )
        val plan = planPlacing(text, at(0, type), at(0, component), at(1, type), at(1, component))

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        result.assertShared(RECORDS, "merchantRef")
        assertEquals(
            """
            record Type1(String field2) {
                Type1 {
                    if (field2 == null) throw new IllegalArgumentException();
                }
            }
            """.trimIndent(),
            result.text,
            "$RECORDS: a compact constructor's body names the component, never a parameter of its own",
        )
    }

    // ------------------------------------------------- Checked, and rejected as non-forcing

    /**
     * **Enum constants rename freely.** Nothing in Java ties a constant's name to anything outside
     * its own enum — an enum implementing an interface is constrained on its *methods*, never on its
     * constant names — so they are ordinary project-owned symbols and the list stays closed at five.
     */
    @Test
    fun `enum constants are ordinary symbols and rename freely`() {
        val text = "enum Status { ACTIVE, SETTLED } Status s = Status.ACTIVE;"
        val plan = planOf(
            text,
            symbol("Status", SymbolRole.TYPE, SymbolOrigin.IN_CONTENT, key = "class:com.acme.Status"),
            symbol("ACTIVE", SymbolRole.FIELD, SymbolOrigin.IN_CONTENT, key = "field:class:com.acme.Status#ACTIVE"),
            symbol("SETTLED", SymbolRole.FIELD, SymbolOrigin.IN_CONTENT, key = "field:class:com.acme.Status#SETTLED"),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals(
            "enum Type1 { field2, field3 } Type1 s = Type1.field2;",
            result.text,
            "$NON_FORCING: enum constants rename freely",
        )
    }
}

private const val OVERLOADS = "Forced sharing rule 1 (overloads)"
private const val OVERRIDE_ROOT = "Forced sharing rule 2 (override chains key by their root)"
private const val ACCESSORS = "Forced sharing rule 3 (accessors derive from their backing field)"
private const val CONSTRUCTORS = "Forced sharing rule 4 (constructor ↔ declaring type)"
private const val NON_FORCING = "Checked and rejected as non-forcing"
private const val RECORDS = "Forced sharing rule 5 (record component ↔ field ↔ accessor)"
