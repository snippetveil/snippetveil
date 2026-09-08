package com.snippetveil.plugin.kotlin

import com.snippetveil.core.AnonymizationSettings
import com.snippetveil.core.LedgerSnapshot
import com.snippetveil.core.Occurrence
import com.snippetveil.core.SnippetPlan
import com.snippetveil.core.SymbolOccurrence
import com.snippetveil.core.anonymize

/**
 * **What a Kotlin token comes out as** — the whole invocation, from a real tree to the characters on
 * the clipboard.
 *
 * > One symbol holds one placeholder. How that placeholder is *spelled* at a token is computed
 * > per-language, at splice time — `field1` in Kotlin, `getField1()` in Java. Rendering is
 * > language-dependent; the placeholder is not.
 *
 * `KotlinSpellingTest` states the same rule in `:core` against plan literals, where it is a rule
 * about evidence and runs in milliseconds. This is the other half and neither replaces the other:
 * the engine can only be right about the evidence it is handed, and whether the **platform** hands a
 * Kotlin token naming a Java getter the accessor's own symbol is a question only a fixture can
 * answer. Everything asserted here is asserted against what the Kotlin plugin actually resolved.
 *
 * **Nothing here is reachable from a user's IDE.** The optional descriptor declares no
 * `languageSupport`, so every `.kt` file still takes the gate's stated refusal, and these fixtures
 * construct the walk directly.
 */
internal class KotlinRenderingTest : KotlinSnippetTestCase() {

    /**
     * **A Kotlin property renders `field1` and a Java call to its accessor renders `getField1()`, in
     * one invocation and against one ledger entry.**
     *
     * A Kotlin property is one symbol and takes the field placeholder. Its accessors have no name of
     * their own — a `KtPropertyAccessor` uses the `get` / `set` keyword as its name placeholder — so
     * there is **nothing to splice** on the Kotlin side, while a Java file in the same project
     * calling `getMerchantRef()` renders `getField4()` through the derivation that already existed.
     *
     * **This is rule 3's shape, not rule 5's.** A Java call site rendering `param8.field4()` would
     * read as broken Java, and two independent placeholders would reproduce exactly the incoherence
     * rule 3 was written to prevent.
     *
     * One invocation over two files, which the plan can carry because [com.snippetveil.core.SourceLanguage]
     * is a tag on the **occurrence**: the plan stays a flat list of non-overlapping ranges and one
     * snippet may hold tokens of more than one language. The entry is asserted as **one** rather than
     * as two that agree — a fixture that only compared the two spellings would pass on two symbols
     * that happened to be numbered next to each other.
     */
    fun `test a Kotlin property and a Java call to its accessor are one entry`() {
        assertTheHarnessResolves()
        val kotlinPlan = kotlinPlanFor(
            "com/acme/ledger/Ledger.kt",
            """
            package com.acme.ledger

            class Ledger(val merchantRef: String) {
                fun show(): String = merchantRef
            }
            """.trimIndent(),
        )
        val javaPlan = planFor(
            "com/acme/ledger/UsesLedger.java",
            """
            package com.acme.ledger;

            class UsesLedger {
                String read(Ledger ledger) { return ledger.getMerchantRef(); }
            }
            """.trimIndent(),
        )

        val result = anonymize(merged(kotlinPlan, javaPlan), AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals(
            "$SPELLING: the Kotlin property and the Java accessor are one symbol wearing two spellings",
            """
            package com.pkg1.pkg2

            class Type3(val field4: String) {
                fun method5(): String = field4
            }

            package com.pkg1.pkg2;

            class Type6 {
                String method7(Type3 param8) { return param8.getField4(); }
            }
            """.trimIndent(),
            result.text,
        )

        assertEquals(
            "$SPELLING: one entry carrying one number, not two that agree",
            listOf("field4/merchantRef"),
            result.delta.placeholders
                .filterKeys { it.endsWith("#merchantRef") }
                .map { (_, minted) -> minted.placeholder + "/" + minted.original },
        )
        assertNull(
            "$SPELLING: the Kotlin property minted nothing of its own beside the accessor's field",
            result.delta.placeholders.keys.singleOrNull { it.contains("merchantRef") && it.startsWith("method:") },
        )
    }

    /**
     * **The mirror case, and it is the largest one in the corpus** — a Kotlin token naming a Java
     * `getBody()`, at 308 occurrences, twice as many as `it`.
     *
     * The key is **the Java method's**, or the two languages disagree about a symbol they both see.
     * So the Kotlin token renders as a pure spelling function over the placeholder the Java side
     * already assigned: an accessor-shaped placeholder **de-prefixes**, and the accessor written
     * under its own name does not. A setter written as a property assignment de-prefixes to the same
     * field placeholder, because the prefix is what the derivation strips and the field is what is
     * left.
     */
    fun `test a Kotlin token naming a Java accessor de-prefixes`() {
        assertTheHarnessResolves()
        writeTheJavaBean()

        val output = kotlinOutputFor(
            "com/acme/ledger/Uses.kt",
            """
            package com.acme.ledger

            fun use(javaObj: JavaBean) {
                println(javaObj.body)
                println(javaObj.getBody())
                javaObj.body = null
            }
            """.trimIndent(),
        )

        assertEquals(
            "$SPELLING: a Kotlin property access over a Java accessor de-prefixes, and the accessor's own name does not",
            """
            package com.pkg1.pkg2

            fun method3(param4: Type5) {
                println(param4.field6)
                println(param4.getField6())
                param4.field6 = null
            }
            """.trimIndent(),
            output,
        )
    }

    /**
     * **A fieldless getter renders `javaObj.method7`, and that is the accepted artifact.**
     *
     * A property access spelled with a **method** placeholder is an **obvious** artifact rather than
     * a plausible one — a reader meets a name that says *method* where they wrote a property, and can
     * see that they did — and it is the same trade already accepted for `String.format("str1", a, b)`.
     *
     * **Rejected, and not to be reopened inside this change:** minting a property placeholder for
     * fieldless getters. It changes the shipped Java rendering, diverges by install age, and names a
     * field the source does not have. Rejected: rewriting to an explicit `javaObj.getMethod7()` —
     * that is shape rewriting rather than descriptive substitution.
     */
    fun `test a Kotlin token naming a fieldless Java getter renders the method placeholder`() {
        assertTheHarnessResolves()
        writeTheJavaBean()

        val output = kotlinOutputFor(
            "com/acme/ledger/UsesFieldless.kt",
            """
            package com.acme.ledger

            fun use(javaObj: JavaBean) {
                println(javaObj.fieldless)
            }
            """.trimIndent(),
        )

        assertEquals(
            "$SPELLING: a fieldless getter has no field placeholder to de-prefix to, so it splices verbatim",
            """
            package com.pkg1.pkg2

            fun method3(param4: Type5) {
                println(param4.method6)
            }
            """.trimIndent(),
            output,
        )
    }

    /**
     * **`p.copy(merchantRef = x)` renders `p.copy(field4 = x)` — this is the leak case.**
     *
     * A Kotlin `data class` is the **same statement as forced-sharing rule 5, not a sixth rule**:
     * where the language generates members whose names it *forces to agree with a declared symbol*,
     * they are one symbol with one placeholder. Three of the four faces need nothing — `component1`
     * is fixed by the language and silent, a destructuring entry is a local the developer named and
     * Kotlin destructuring is positional, and a primary-constructor `val` is one declaration wearing
     * two roles that was already one placeholder.
     *
     * **The fourth is the leak this restatement exists for.** Get it wrong and the argument label
     * carries the developer's domain word through verbatim, so it is asserted directly *and* the
     * absence of the word is asserted beside it: an output that renders the label as some other
     * placeholder would be wrong, and one that leaves `merchantRef` in the text would be the leak.
     *
     * **Measured, and reported as absence rather than as evidence:** the corpus has **0 occurrences**
     * of `copy` and `componentN`, being extension-function and DSL heavy. So this is decided on
     * principle, and this fixture is written rather than harvested.
     */
    fun `test a data class copy call carries no domain word through`() {
        assertTheHarnessResolves()
        val output = kotlinOutputFor(
            "com/acme/ledger/Payment.kt",
            """
            package com.acme.ledger

            data class Payment(val merchantRef: String)

            fun use(p: Payment, x: String) {
                p.copy(merchantRef = x)
                p.component1()
                val (ref) = p
                println(ref)
            }
            """.trimIndent(),
        )

        assertEquals(
            "$RULE_5: the generated `copy` takes its parameter names from the declaration, so they are one symbol",
            """
            package com.pkg1.pkg2

            data class Type3(val field4: String)

            fun method5(param6: Type3, param7: String) {
                param6.copy(field4 = param7)
                param6.component1()
                val (local8) = param6
                println(local8)
            }
            """.trimIndent(),
            output,
        )

        assertFalse(
            "$RULE_5: the developer's domain word survived into the output",
            output.contains("merchantRef"),
        )
    }

    /**
     * **A file facade renders a `Type` placeholder and a Java reference to it renders
     * `Type5.method6()`.**
     *
     * `PaymentService.kt` holding top-level functions presents `PaymentServiceKt` to Java, with **no
     * token anywhere in the Kotlin source**. It is an **ordinary project-owned class**: keyed by FQN,
     * taking a `Type` placeholder, spliced wherever a token exists.
     *
     * > **Rejected as a trap: preserving the facade as name-constrained.** Unlike `it` and
     * > `Companion`, this name is **not fixed by the language** — it is the **file name**, and the
     * > file name is domain vocabulary.
     *
     * The three halves of that are asserted apart, because a golden alone would leave the reason to be
     * reasoned backwards to: it renamed at all (so it is not name-constrained), it was keyed by its
     * qualified name and written down (so it is an ordinary project class rather than something
     * identified by position), and it has a row (so it is **not** excluded from what the counts speak
     * about — the near-miss §6 exists for).
     *
     * Written unconditionally against the platform pin: a `.kt` file with a top-level callable has a
     * facade light class, on all four platforms measured. See [assertTheFacadeBehaviourIsPinned].
     */
    fun `test a file facade is an ordinary project class and renames`() {
        assertTheHarnessResolves()
        assertTheFacadeBehaviourIsPinned()
        myFixture.addFileToProject(
            "com/acme/ledger/PaymentService.kt",
            """
            package com.acme.ledger

            fun foo(): Int = 1
            """.trimIndent(),
        )

        val plan = planFor(
            "com/acme/ledger/UsesFacade.java",
            """
            package com.acme.ledger;

            class UsesFacade {
                int run() { return PaymentServiceKt.foo(); }
            }
            """.trimIndent(),
        )
        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals(
            "$FACADE: the facade is an ordinary project-owned class and its top-level callable an ordinary method",
            """
            package com.pkg1.pkg2;

            class Type3 {
                int method4() { return Type5.method6(); }
            }
            """.trimIndent(),
            result.text,
        )

        assertEquals(
            "$FACADE: keyed by its fully-qualified name, and therefore written down",
            "Type5/PaymentServiceKt",
            result.delta.placeholders["class:com.acme.ledger.PaymentServiceKt"]
                ?.let { it.placeholder + "/" + it.original },
        )
        assertTrue(
            "$FACADE: the facade name is a row, so it is not excluded from what the counts speak about",
            result.names.any { it.original == "PaymentServiceKt" },
        )
    }

    /** The Java bean the mirror case reads: a field with a getter and a setter, and a getter with no field. */
    private fun writeTheJavaBean() {
        myFixture.addFileToProject(
            "com/acme/ledger/JavaBean.java",
            """
            package com.acme.ledger;

            public class JavaBean {
                private String body;
                public String getBody() { return body; }
                public void setBody(String body) { this.body = body; }
                public String getFieldless() { return null; }
            }
            """.trimIndent(),
        )
    }

    /**
     * The two plans as one snippet — the Kotlin file's text, a blank line, then the Java file's, with
     * the second plan's offsets moved by what precedes them.
     *
     * A snippet holding tokens of two languages is what *one invocation* means for a rule about the
     * two agreeing, and the plan already has the shape for it: [com.snippetveil.core.SourceLanguage]
     * is a tag on each occurrence rather than a property of the plan, so nothing about the flat list
     * of ranges changes. Identifiers only, which is what both fixtures hold.
     */
    private fun merged(first: SnippetPlan, second: SnippetPlan): SnippetPlan {
        val separator = "\n\n"
        val shift = first.text.length + separator.length
        val moved: List<Occurrence> = second.occurrences.map { occurrence ->
            val symbol = occurrence as? SymbolOccurrence
                ?: error("this fixture is identifiers only, and reported a ${occurrence.javaClass.simpleName}")
            SymbolOccurrence(
                symbol.start + shift,
                symbol.end + shift,
                symbol.text,
                symbol.symbol,
                symbol.language,
            )
        }

        return SnippetPlan(
            first.text + separator + second.text,
            (first.occurrences + moved).sortedBy { it.start },
            first.rootPackage,
        )
    }
}

private const val SPELLING = "One symbol, one placeholder, two spellings"
private const val RULE_5 = "Forced sharing rule 5, restated language-neutrally"
private const val FACADE = "The facade is an ordinary class"
