package com.snippetveil.plugin.kotlin

import com.snippetveil.core.AnonymizationSettings
import com.snippetveil.core.LedgerSnapshot
import com.snippetveil.core.SnippetPlan
import com.snippetveil.core.anonymize
import com.snippetveil.core.fidelityNotices
import com.snippetveil.plugin.symbols

/**
 * **The silence rule, and the count it keeps out.**
 *
 * > A generated name is silent iff the *language* fixes it. A generated name fixed to a *declared
 * > symbol's* name is not silent — it force-shares with that symbol.
 *
 * **Both halves look identical in PSI** — a generated member, no `realPsi`, nothing to splice — and
 * getting the **second** half wrong is a **leak** rather than a degradation. That asymmetry is the
 * whole reason the sentence is one sentence, and it is why the two halves are asserted in the same
 * fixture: `p.copy(merchantRef = …)` is written three lines from `it`, and one of them has to lose
 * its name while the other keeps it.
 *
 * **Silent means no occurrence at all** — uncounted, no notice, no refusal. There is no loss to
 * disclose, because a name the language fixed was never going to be replaced, so preserving it
 * conceals nothing; and 151 `it`s in the preserved count would swamp a number that is supposed to
 * mean *names that survived verbatim*. So the assertions here are about the plan as well as the
 * output: a token reported and then coincidentally spliced back to its own spelling would satisfy a
 * golden and fail the count.
 *
 * The near-miss the rule exists to catch is a **file facade** name, which has no declaration in
 * source either and is **not** silent — it is the file name, and the file name is domain vocabulary.
 * It is asserted from the Java side, in `KotlinRenderingTest`.
 */
internal class KotlinSilenceTest : KotlinSnippetTestCase() {

    /**
     * **Every silent member is silent**, asserted by offset against the plan rather than by reading
     * the output — so a token that was reported and happened to splice back to its own spelling
     * cannot pass.
     *
     * The list is the ticket's, and each entry says which mechanism makes it silent. Three of them
     * need a rule in the walk; the rest are silent because the Kotlin lexer never makes them an
     * `IDENTIFIER` at all, and *that* is asserted here rather than assumed — a soft keyword that
     * became an identifier in some later platform would otherwise start renaming `get`.
     */
    fun `test every name the language fixes is silent`() {
        assertTheHarnessResolves()
        val plan = kotlinPlanFor(SILENT_PATH, SILENT)

        // Resolved to the lambda, because there is no declaration of `it` to resolve to.
        plan.assertSilent("{ it.length }", "it")

        // A companion object the source did not name. The `Companion` spelling is the language's.
        plan.assertSilent("Holder.Companion", "Companion")

        // The `field` soft keyword resolves to the property whose accessor it is written in.
        plan.assertSilent("get() = field", "field")
        plan.assertSilent("set(value) { field = value }", "field")

        // Generated members of a data class, resolved back to the declarations they were generated
        // from — the primary constructor for `copy`, the `val` parameter for `component1`.
        plan.assertSilent("p.copy(", "copy")
        plan.assertSilent("p.component1()", "component1")

        // Hard keywords: never an identifier, and asserted so that the walk's claim to that is not
        // an assumption about the lexer.
        plan.assertSilent("= this", "this")
        plan.assertSilent("super.settle()", "super")

        // The `get` and `set` that open a property accessor, and the use-site and file annotation
        // targets — soft keywords, and `KtAnnotationUseSiteTarget` rather than a reference.
        plan.assertSilent("get() = field", "get")
        plan.assertSilent("set(value)", "set")
        plan.assertSilent("@get:JvmName", "get")
        plan.assertSilent("@file:JvmName", "file")

        // Operator conventions: a `KtOperationReferenceExpression` carries no identifier, so the
        // names Kotlin fixes by convention are never tokens to begin with. `invoke` is the same
        // statement in call syntax — `handler(1)` names it and spells nothing.
        plan.assertSilent("1 + 2", "+")
        plan.assertSilent("xs[0]", "[")
        plan.assertSilent("counter++", "++")
        plan.assertSilent("for (entry in xs)", "in")
        plan.assertSilent("handler(1)", "(")
    }

    /**
     * **A label reference carrying no name identifier has no token to report** — the last entry on
     * §4's list, and the one that needs nothing at all.
     *
     * `return@` with no label after it is red code, which is normal rather than exceptional: the
     * snippet a developer is debugging is the likely one. A fixture of its own because the file every
     * other assertion reads has to parse, and this one deliberately does not.
     */
    fun `test a label reference with no name identifier has no token`() {
        assertTheHarnessResolves()
        val source = """
            package com.acme.ledger

            fun use(xs: List<Int>) {
                xs.forEach { return@ }
            }
        """.trimIndent()

        kotlinPlanFor("com/acme/ledger/Bare.kt", source).assertSilent("return@ }", "@")

        assertEquals(
            "$SILENCE: a label with nothing to name is spliced over",
            """
            package com.pkg1.pkg2

            fun method3(param4: List<Int>) {
                param4.forEach { return@ }
            }
            """.trimIndent(),
            kotlinOutputFor("com/acme/ledger/Bare2.kt", source),
        )
    }

    /**
     * **The two near-misses on the third silent shape, and each of them a leak if it were not
     * excluded.**
     *
     * A backtick-escaped name and an import alias both resolve to a declaration the token does not
     * spell — which is exactly what `copy` and `component1` do. The difference is *who chose the
     * spelling*: the language chose `copy`, and the **developer** chose `` `merchant ref` `` and
     * `Pay`. Silencing either would rename the declaration a few lines above and leave the developer's
     * own word at every call site, which is the leak class this rule exists to prevent rather than a
     * degradation of it.
     *
     * **The two are excluded by different mechanisms, and only one of them is the silence rule's
     * own.** A backtick-escaped name is compared on its unquoted spelling, which is a clause in
     * `namesSomethingTheLanguageFixed`. An import alias is not: it is a declaration in its own right
     * and every token spelled `Pay` names *it*, so `evidenceFor` has already redirected the token by
     * the time silence is asked, and there is no declaration left to compare a spelling against. That
     * this test is green for a reason it did not used to be green for is the point of saying so here
     * — the rule it once asserted is gone, and what it asserts now is the outcome both mechanisms owe:
     * neither word reaches the clipboard. `KotlinImportAliasTest` holds the alias half whole.
     *
     * Asserted as output rather than as a plan, because a leak is a fact about the clipboard.
     */
    fun `test a backticked name and an import alias are the developer's words and are not silent`() {
        assertTheHarnessResolves()
        myFixture.addFileToProject(
            "com/acme/ledger/Payment.kt",
            """
            package com.acme.ledger

            class Payment(val merchantRef: String)
            """.trimIndent(),
        )

        val output = kotlinOutputFor(
            "com/acme/ledger/Chosen.kt",
            """
            package com.acme.ledger

            import com.acme.ledger.Payment as Pay

            fun `merchant ref`(): Int = 1

            fun use(p: Pay): Int {
                println(p)
                return `merchant ref`()
            }
            """.trimIndent(),
        )

        assertFalse("$SILENCE: a backtick-escaped name survived into the output\n$output", output.contains("merchant ref"))
        assertFalse("$SILENCE: an import alias survived into the output\n$output", Regex("""\bPay\b""").containsMatchIn(output))
    }

    /**
     * **The half that is not silent, in the same fixture: the argument label of a generated `copy`
     * renames with the property it was generated from.**
     *
     * This is the leak the silence rule's second clause exists to prevent, and it is asserted beside
     * the silent members rather than in a fixture of its own precisely because the two are
     * indistinguishable in PSI. `KotlinRenderingTest` asserts what it renders **as**; this asserts
     * that the rule which silenced `copy` did not also silence the word next to it.
     */
    fun `test a generated name fixed to a declared symbol is not silent`() {
        assertTheHarnessResolves()
        val plan = kotlinPlanFor(SILENT_PATH, SILENT)

        val label = plan.symbols().single { it.text == "merchantRef" && it.start > SILENT.indexOf("p.copy(") }
        assertEquals(
            "the argument label of a generated `copy` is the property's own symbol",
            "field:class:com.acme.ledger.Payment#merchantRef",
            label.symbol.key,
        )
        assertFalse(
            "the developer's domain word survived into the output",
            anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY).text.contains("merchantRef"),
        )
    }

    /**
     * **Silent, uncounted, no notice, no refusal** — the three consequences of reporting no
     * occurrence, asserted together because they are one claim about one invocation.
     *
     * A name nobody declared is not a preservation in the sense the count means: *names survived
     * verbatim* is about the spine rule, about what the language forbids being renamed, and about
     * what the user ticked. `it` is none of the three.
     */
    fun `test the silent members produce no notice, no unknown and no row`() {
        assertTheHarnessResolves()
        val result = anonymize(
            kotlinPlanFor(SILENT_PATH, SILENT),
            AnonymizationSettings.DEFAULTS,
            LedgerSnapshot.EMPTY,
        )

        assertEquals("a silent name is not a name the IDE failed to resolve", emptyList<String>(), result.unknowns.map { it.name })
        assertEquals("a silent name is not a disclosure", emptyList<String>(), result.fidelityNotices())

        for (silent in SILENT_WORDS) {
            assertFalse(
                "`$silent` is a row in the mapping table, and nobody declared it",
                result.names.any { it.original == silent },
            )

            // And it is still in the text: there is nothing to restore, so nothing to refuse a paste
            // over — a name the language fixed was never going to be replaced, so preserving it
            // conceals nothing.
            assertTrue(
                "$SILENCE: `$silent` did not survive into the output\n${result.text}",
                Regex("""\b${Regex.escape(silent)}\b""").containsMatchIn(result.text),
            )
        }
    }

    /**
     * **151 `it`s would swamp the preserved count, so they are excluded — asserted as a control
     * pair.**
     *
     * Two snippets differing in nothing but the silent members: same counts, same ledger delta, same
     * numbers burnt. That is the claim §6 makes — *the rule is who fixed the spelling, not did a
     * token survive* — stated as the difference it must not make, rather than as a number somebody
     * read off one fixture and wrote down.
     */
    fun `test adding silent members moves no count and no number`() {
        assertTheHarnessResolves()

        val without = anonymize(
            kotlinPlanFor(
                "com/acme/ledger/Control.kt",
                """
                package com.acme.ledger

                fun use(xs: List<String>) {
                    xs.map { 1 }
                }
                """.trimIndent(),
            ),
            AnonymizationSettings.DEFAULTS,
            LedgerSnapshot.EMPTY,
        )
        val with = anonymize(
            kotlinPlanFor(
                "com/acme/ledger/Silenced.kt",
                """
                package com.acme.ledger

                fun use(xs: List<String>) {
                    xs.map { it }
                    xs.map { it }
                    xs.map { it }
                }
                """.trimIndent(),
            ),
            AnonymizationSettings.DEFAULTS,
            LedgerSnapshot.EMPTY,
        )

        assertEquals("three `it`s moved the preserved count", without.counts.preserved, with.counts.preserved)
        assertEquals("three `it`s moved the replaced count", without.counts.replaced, with.counts.replaced)
        assertEquals("three `it`s moved the unknown count", without.counts.unknown, with.counts.unknown)
        assertEquals("three `it`s burnt a number", without.delta.nextNumber, with.delta.nextNumber)
    }

    /**
     * Asserts that the walk reported **nothing at all** over [token], as it is written inside
     * [context].
     *
     * By offset rather than by spelling, because half the words here — `field`, `get`, `in` — are
     * ordinary identifiers somewhere else in the same file, and a test that asked *is `field`
     * anywhere in the plan* would be asking a different question. [context] is what locates the one
     * occurrence meant, and a fixture that stopped containing it fails rather than passing vacuously.
     */
    private fun SnippetPlan.assertSilent(context: String, token: String) {
        val at = text.indexOf(context)
        assertTrue("the fixture no longer contains `$context`, so this asserts nothing", at >= 0)

        val start = at + context.indexOf(token)
        val covering = symbols().filter { it.start < start + token.length && it.end > start }
        assertEquals(
            "$SILENCE: `$token` in `$context` is a name the language fixed and was reported anyway",
            emptyList<String>(),
            covering.map { it.text },
        )
    }
}

private const val SILENT_PATH = "com/acme/ledger/Silent.kt"

/**
 * One file carrying every silent member the ticket names, plus the one thing that looks identical to
 * them in PSI and is **not** silent — the argument label of a generated `copy`.
 *
 * Together rather than a fixture apiece, because *both halves look identical* is the claim: a suite
 * that asserted the silent ones over here and the force-shared one over there would not be asserting
 * that one walk tells them apart.
 */
private val SILENT = """
    @file:JvmName("Silent")

    package com.acme.ledger

    data class Payment(val merchantRef: String)

    open class Base {
        open fun settle() {}
    }

    class Holder : Base() {
        companion object

        var amount: Int = 0
            get() = field
            set(value) { field = value }

        @get:JvmName("readTotal")
        val total: Int = 0

        fun self(): Holder = this

        override fun settle() { super.settle() }
    }

    class Handler {
        operator fun invoke(units: Int) {}
    }

    fun use(xs: List<String>, p: Payment, handler: Handler) {
        xs.map { it.length }
        Holder.Companion
        p.copy(merchantRef = "x")
        p.component1()
        val sum = 1 + 2
        val first = xs[0]
        var counter = 0
        counter++
        for (entry in xs) {}
        handler(1)
    }
""".trimIndent()

/**
 * The silent words [SILENT] writes that a **whole word** search can find — the three the walk
 * silences by a rule of its own, plus the two generated data-class members.
 *
 * The rest of the ticket's list is not here because it is not a word: `this` and `super` are hard
 * keywords, `get` / `set` / `@get:` / `@file:` are soft ones, and the operator conventions carry no
 * identifier at all. Those are asserted by offset, where the claim is *no token*, rather than by
 * spelling, where every one of them would also match something else in the file.
 */
private val SILENT_WORDS = listOf("it", "Companion", "field", "copy", "component1")

private const val SILENCE = "The silence rule"
