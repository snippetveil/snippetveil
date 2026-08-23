package com.snippetveil.plugin

import com.snippetveil.core.AnonymizationSettings
import com.snippetveil.core.LedgerSnapshot
import com.snippetveil.core.SnippetPlan
import com.snippetveil.core.SymbolOrigin
import com.snippetveil.core.SymbolRole
import com.snippetveil.core.anonymize

/**
 * **The evidence the name-constrained and forced-sharing rules are decided on, read off real Java.**
 *
 * The rules themselves are tested in `:core` against plan literals, at millisecond speed and with
 * every input visible. What cannot be tested there is whether the plan told the truth about a real
 * file — whether `findDeepestSuperMethods()` was asked, whether a Lombok accessor was recognised
 * without a declaration to read — and that is what is here.
 *
 * A handful of these run the whole pipeline and assert on the text. Those are not duplicates of the
 * `:core` tests: they are the assertion that the two halves *meet*, which is the seam's one failure
 * mode that neither side can see alone.
 */
class NameRuleEvidenceTest : JavaSnippetTestCase() {

    // ------------------------------------------------------------------ Override chains

    /**
     * **The regression that started the list**, end to end. An anonymous `Runnable` whose `run()`
     * was renamed at the declaration and preserved at the call site — because the call site resolves
     * to `java.lang.Runnable.run` and the spine rule preserves that — and **the override silently
     * stopped overriding.**
     */
    fun `test a project method overriding a JDK member keeps its name at both sites`() {
        assertTheHarnessResolves()
        val plan = planFor(
            "Ledger.java",
            """
            class Ledger {
                void audit() {
                    <selection>Runnable task = new Runnable() { public void run() { audit(); } };
                    task.run();</selection>
                }
            }
            """.trimIndent(),
        )

        val declaration = plan.symbols().first { it.text == "run" }.symbol
        assertEquals(SymbolOrigin.IN_CONTENT, declaration.origin)
        assertEquals(
            listOf("method:class:java.lang.Runnable#run" to SymbolOrigin.JDK),
            declaration.overrideRoots.map { it.key to it.origin },
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)
        assertEquals(
            "name constraint 1: both sites must still read `run(`: " + result.text,
            2,
            Regex("""\brun\(""").findAll(result.text).count(),
        )
        assertFalse("`run` was renamed somewhere: " + result.text, result.mapping.containsValue("run"))
    }

    /** `equals` roots in `java.lang.Object`, which is the largest instance of this class of names. */
    fun `test equals roots in java lang Object`() {
        assertTheHarnessResolves()
        val plan = planFor(
            "Ledger.java",
            """
            class Ledger {
                <selection>@Override public boolean equals(Object other) { return false; }</selection>
            }
            """.trimIndent(),
        )

        assertEquals(
            listOf("method:class:java.lang.Object#equals" to SymbolOrigin.JDK),
            plan.symbols().single { it.text == "equals" }.symbol.overrideRoots.map { it.key to it.origin },
        )
    }

    /**
     * A chain that stays inside the project reports a project root, which is what tells the engine
     * to share one placeholder rather than to preserve the name. Both ends report the same root —
     * the implementation from its chain, the interface by being it — which is what makes the shared
     * key computable from one symbol's evidence alone.
     */
    fun `test a project-to-project override chain reports a project root at both ends`() {
        assertTheHarnessResolves()
        myFixture.addFileToProject("com/acme/Named.java", "package com.acme; public interface Named { String name(); }")
        val plan = planFor(
            "com/acme/Payment.java",
            """
            package com.acme;

            class Payment implements Named {
                <selection>public String name() { return "x"; }
                String describe(Named other) { return other.name(); }</selection>
            }
            """.trimIndent(),
        )

        val root = "method:class:com.acme.Named#name"
        val implementation = plan.symbols().first { it.text == "name" }.symbol
        assertEquals(listOf(root to SymbolOrigin.IN_CONTENT), implementation.overrideRoots.map { it.key to it.origin })
        assertEquals("method:class:com.acme.Payment#name", implementation.key)

        // The call site resolves to the interface, which is the root and reports none of its own.
        val call = plan.symbols().last { it.text == "name" }.symbol
        assertEquals(root, call.key)
        assertEquals(emptyList<Any>(), call.overrideRoots)

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)
        assertEquals(
            "forced sharing rule 2: the chain must render as one placeholder: " + result.text,
            1,
            result.mapping.filterValues { it == "name" }.size,
        )
    }

    /** Most methods override nothing, and report nothing. */
    fun `test a method that overrides nothing reports no roots`() {
        assertTheHarnessResolves()
        val plan = planFor(
            "Ledger.java",
            "class Ledger { <selection>void audit(int amount) {}</selection> }",
        )

        assertEquals(emptyList<Any>(), plan.symbols().single { it.text == "audit" }.symbol.overrideRoots)
    }

    // ---------------------------------------------------------------------- Accessors

    /** A getter and a setter report the field they name; the prefix is read off the method's name. */
    fun `test a getter and a setter report their backing field`() {
        assertTheHarnessResolves()
        val plan = planFor(
            "com/acme/Payment.java",
            """
            package com.acme;

            class Payment {
                private String merchantId;
                <selection>public String getMerchantId() { return merchantId; }
                public void setMerchantId(String merchantId) { this.merchantId = merchantId; }</selection>
            }
            """.trimIndent(),
        )

        val field = "field:class:com.acme.Payment#merchantId"
        assertEquals(field to "get", plan.accessorOf("getMerchantId"))
        assertEquals(field to "set", plan.accessorOf("setMerchantId"))

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)
        assertEquals(
            "forced sharing rule 3: a getter derives from its field: " + result.text,
            setOf("getField1"),
            result.mapping.filterValues { it == "getMerchantId" }.keys,
        )
        assertEquals(
            "forced sharing rule 3: a setter derives from its field: " + result.text,
            setOf("setField1"),
            result.mapping.filterValues { it == "setMerchantId" }.keys,
        )
    }

    /**
     * **Fluent accessors are deliberately not covered.** Nothing in Java forces `merchantId()` to
     * track the field `merchantId`, and this list is the closed set of things that are forced.
     */
    fun `test a fluent accessor reports no backing field`() {
        assertTheHarnessResolves()
        val plan = planFor(
            "com/acme/Payment.java",
            """
            package com.acme;

            class Payment {
                private String merchantId;
                <selection>public String merchantId() { return merchantId; }</selection>
            }
            """.trimIndent(),
        )

        assertNull(plan.accessorOf("merchantId"))
    }

    /**
     * A getter-shaped name over a field that does not exist is not an accessor. The match is a
     * conjunction of name, arity and a field to point at — a name alone would tie two placeholders
     * together on a resemblance rather than on a rule.
     */
    fun `test a getter-shaped method with no such field reports nothing`() {
        assertTheHarnessResolves()
        val plan = planFor(
            "com/acme/Payment.java",
            """
            package com.acme;

            class Payment {
                private int amount;
                <selection>public int getTotal() { return amount; }
                public int getAmount(int scale) { return amount * scale; }</selection>
            }
            """.trimIndent(),
        )

        assertNull("there is no field called `total`", plan.accessorOf("getTotal"))
        assertNull("a getter takes no arguments", plan.accessorOf("getAmount"))
    }

    // ----------------------------------------------------------- Constructors and records

    /**
     * **A constructor's identifier is its class's name**, so it carries the class's key and the type
     * role. Out of the method namespace it would read `public method1(String param1)` inside
     * `class Type1` — a method with no return type, which does not read as anonymized, **it reads as
     * broken.**
     */
    fun `test a constructor is keyed and rendered as its declaring type`() {
        assertTheHarnessResolves()
        val plan = planFor(
            "com/acme/Payment.java",
            """
            package com.acme;

            <selection>class Payment {
                Payment(String ref) {}
            }</selection>
            """.trimIndent(),
        )

        val constructor = plan.symbols().last { it.text == "Payment" }.symbol
        assertEquals("class:com.acme.Payment", constructor.key)
        assertEquals(SymbolRole.TYPE, constructor.role)

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)
        assertTrue("a constructor must render as its type: " + result.text, "Type1(String param2)" in result.text)
    }

    /**
     * **One declared symbol, three PSI faces.** `record Payment(String merchantRef)` gives a
     * `PsiRecordComponent`, an implicit field and an implicit accessor, and Java forces all three to
     * agree — so they reach one key, and **the accessor is that placeholder rather than
     * `getField1()`**: records carry no `get` prefix, so rule 3's derivation would be actively wrong
     * here.
     */
    fun `test a record component its field and its accessor are one symbol`() {
        assertTheHarnessResolves()
        val plan = planFor(
            "com/acme/Payment.java",
            """
            package com.acme;

            record Payment(String merchantRef) {
                <selection>String masked() { return merchantRef.substring(2); }
                static String of(Payment p) { return p.merchantRef(); }</selection>
            }
            """.trimIndent(),
        )

        val faces = plan.symbols().filter { it.text == "merchantRef" }
        assertEquals(2, faces.size)
        assertEquals(
            setOf("field:class:com.acme.Payment#merchantRef"),
            faces.map { it.symbol.key }.toSet(),
        )
        assertEquals(setOf(SymbolRole.FIELD), faces.map { it.symbol.role }.toSet())

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)
        val placeholder = result.mapping.filterValues { it == "merchantRef" }.keys.single()
        assertTrue("the accessor carries no prefix: " + result.text, "$placeholder()" in result.text)
        assertFalse(
            "rule 3 must not fire on a record accessor: " + result.text,
            "get" + placeholder.replaceFirstChar(Char::uppercaseChar) in result.text,
        )
    }

    /**
     * **The fourth face of that same symbol: a compact constructor's implicit parameters.** A compact
     * constructor declares no parameter list — the JLS gives it one, named after the components — so
     * `merchantRef` in the body below *is* the component, in exactly the way a record accessor's
     * identifier is.
     *
     * Reported as a parameter of its own it produced `param3` inside a record that declares no such
     * thing: a name the output cannot account for, which is the **plausible** artifact this list
     * exists to prevent rather than a cosmetic one. Compact constructors are the idiomatic way to
     * validate a record, so this is not a corner.
     *
     * It does not open the closed list to six. The word doing the work is *implicit*, and where that
     * word stops the cut falls — see
     * [`test an explicit canonical constructor's parameters rename freely`], which is this test's
     * control and asserts the mirror-image error is not made.
     */
    fun `test a compact constructor's implicit parameter is its record component`() {
        assertTheHarnessResolves()
        val plan = planFor(
            "com/acme/Payment.java",
            """
            package com.acme;

            <selection>record Payment(String merchantRef) {
                Payment {
                    if (merchantRef == null) throw new IllegalArgumentException();
                }
            }</selection>
            """.trimIndent(),
        )

        val faces = plan.symbols().filter { it.text == "merchantRef" }
        assertEquals("the fixture must name the component and the constructor's parameter", 2, faces.size)
        assertEquals(
            setOf("field:class:com.acme.Payment#merchantRef"),
            faces.map { it.symbol.key }.toSet(),
        )
        assertEquals(setOf(SymbolRole.FIELD), faces.map { it.symbol.role }.toSet())

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)
        val placeholder = result.mapping.filterValues { it == "merchantRef" }.keys.single()
        assertEquals(
            "forced sharing rule 5: the constructor's body must name the component: " + result.text,
            2,
            Regex("""\b""" + Regex.escape(placeholder) + """\b""").findAll(result.text).count(),
        )
        assertFalse(
            "the output names a parameter nothing in it declares: " + result.text,
            "param" in result.text,
        )
    }

    /**
     * **The control, and the cut is load-bearing.** An *explicit* canonical constructor's parameter
     * names are the author's to choose — nothing forces `Payment(String ref)` to say `merchantRef` —
     * so its parameters are ordinary independent symbols and must keep renaming freely. Sharing them
     * with the component would be forcing an agreement Java does not force, which is the mirror-image
     * error.
     *
     * The fixture writes the parameter with **the component's own name**, which is the shape a rule
     * keyed on resemblance rather than on implicitness would get wrong — and it is the shape most
     * explicit canonical constructors are actually written in, so this is the likely input rather
     * than a contrived one. The `this.merchantRef` assignment is rule 5's third face in the same
     * fixture: the implicit field shares the component's key while the parameter beside it does not.
     */
    fun `test an explicit canonical constructor's parameters rename freely`() {
        assertTheHarnessResolves()
        val plan = planFor(
            "com/acme/Payment.java",
            """
            package com.acme;

            <selection>record Payment(String merchantRef) {
                Payment(String merchantRef) {
                    this.merchantRef = merchantRef.trim();
                }
            }</selection>
            """.trimIndent(),
        )

        val faces = plan.symbols().filter { it.text == "merchantRef" }.map { it.symbol }
        assertEquals("component, parameter, field, parameter", 4, faces.size)
        val (component, parameter, field, use) = faces

        val componentKey = "field:class:com.acme.Payment#merchantRef"
        assertEquals(componentKey, component.key)
        assertEquals("the implicit field is the component", componentKey, field.key)
        assertEquals("one parameter, named twice", parameter.key, use.key)
        assertTrue(
            "an explicit constructor's parameter is an ordinary symbol, and was keyed as " + parameter.key,
            parameter.key != componentKey,
        )
        assertEquals(SymbolRole.PARAMETER, parameter.role)

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)
        assertEquals(
            "forced sharing rule 5 stops at the implicit: the parameter renames freely: " + result.text,
            2,
            result.mapping.filterValues { it == "merchantRef" }.size,
        )
    }

    /**
     * **A non-canonical constructor's parameters are untouched.** It has a parameter list of its own
     * and delegates to the canonical one, so nothing about its names is implied by anything — the
     * component's name appearing among them is a coincidence the walk must not read as a rule.
     */
    fun `test a non-canonical constructor's parameters are unaffected`() {
        assertTheHarnessResolves()
        val plan = planFor(
            "com/acme/Payment.java",
            """
            package com.acme;

            <selection>record Payment(String merchantRef) {
                Payment(String merchantRef, int retries) {
                    this(merchantRef);
                }
            }</selection>
            """.trimIndent(),
        )

        val faces = plan.symbols().filter { it.text == "merchantRef" }.map { it.symbol }
        assertEquals("component, parameter, parameter", 3, faces.size)
        val (component, parameter, use) = faces

        assertEquals("field:class:com.acme.Payment#merchantRef", component.key)
        assertEquals("one parameter, named twice", parameter.key, use.key)
        assertTrue(
            "a non-canonical constructor's parameter is an ordinary symbol, and was keyed as " + parameter.key,
            parameter.key != component.key,
        )
        assertEquals(SymbolRole.PARAMETER, parameter.role)
    }

    // ------------------------------------------------ Checked, and rejected as non-forcing

    /**
     * **A `switch` label resolves to the constant and follows it**, which is why case labels are not
     * a sixth forced-sharing rule: there is nothing to force, because the label and the declaration
     * were already one symbol.
     */
    fun `test a switch label follows the enum constant it resolves to`() {
        assertTheHarnessResolves()
        val plan = planFor(
            "com/acme/Ledger.java",
            """
            package com.acme;

            class Ledger {
                <selection>enum Status { ACTIVE, SETTLED }

                static String label(Status status) {
                    switch (status) {
                        case ACTIVE: return "a";
                        default: return "b";
                    }
                }</selection>
            }
            """.trimIndent(),
        )

        assertEquals(
            listOf("field:class:com.acme.Ledger.Status#ACTIVE", "field:class:com.acme.Ledger.Status#ACTIVE"),
            plan.symbols().filter { it.text == "ACTIVE" }.map { it.symbol.key },
        )

        // And they rename freely: an enum implementing an interface is constrained on its methods,
        // never on its constant names.
        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)
        assertFalse("an enum constant is renamed like any other field: " + result.text, "ACTIVE" in result.text)
    }

    /** A static import resolves to the member, and follows it for the same reason. */
    fun `test a static import follows the member it resolves to`() {
        assertTheHarnessResolves()
        myFixture.addFileToProject(
            "com/acme/Limits.java",
            "package com.acme; public class Limits { public static final int MAX = 5; }",
        )
        val plan = planFor(
            "com/acme/Usage.java",
            """
            package com.acme;

            <selection>import static com.acme.Limits.MAX;

            class Usage {
                int ceiling = MAX;
            }</selection>
            """.trimIndent(),
        )

        assertEquals(
            listOf("field:class:com.acme.Limits#MAX", "field:class:com.acme.Limits#MAX"),
            plan.symbols().filter { it.text == "MAX" }.map { it.symbol.key },
        )
    }

    /** The backing field's key and the prefix reported for the method named [name]. */
    private fun SnippetPlan.accessorOf(name: String): Pair<String, String>? =
        symbols().first { it.text == name }.symbol.accessor?.let { it.fieldKey to it.prefix }
}
