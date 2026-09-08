package com.snippetveil.plugin.kotlin

import com.snippetveil.core.SymbolOrigin
import com.snippetveil.core.SymbolRole
import com.snippetveil.plugin.symbols

/**
 * **An import alias is a declaration in its own right, and every use of it names that declaration.**
 *
 * > `import com.acme.ledger.Payment as Pay` declares `Pay`. It is keyed by where it is written, it
 * > is never written to a durable mapping, and the alias and every token spelled `Pay` reach one
 * > placeholder.
 *
 * The rule this file asserts is the one #93 was opened to ask for, and it is worth writing down why
 * it is a rule rather than a row in `KotlinShapesTest`, the *shapes that need no rule of their own*
 * table. Two answers that were each
 * individually right made an output that was jointly wrong: `KtImportAlias`'s own token resolves to
 * `null`, so it failed closed into the `Unknown` namespace, while every **use** of the alias resolved
 * to the aliased declaration and rendered *that* symbol's placeholder. The file came out declaring
 * `Unknown4` beside code that only ever said `Type3` — **visibly contradictory output, which is the
 * prohibited class**, and a reader met a name nothing in the snippet introduced.
 *
 * It was also the `Unknown` namespace saying something false. `Unknown1` is documented as
 * load-bearing rather than conventional — it tells the model *the IDE could not resolve this*, which
 * localizes the breakage — and the IDE resolved this name perfectly well. The preview then offered a
 * **preserve** on the row, and preserve is the one authorised fail-open; a user acting on a row
 * saying the IDE could not resolve it would put the developer's own word back on the clipboard on
 * the strength of a claim that was not true. So *nothing here is unresolved* is asserted as its own
 * fact, in [test an aliased name is not reported as unresolved], rather than left implied by a
 * golden.
 *
 * **Kotlin only.** Java has no import aliases, and nothing in the Java walk changes.
 *
 * **Nothing here is reachable from a user's IDE.** The optional descriptor declares no
 * `languageSupport`, so every `.kt` file still takes the source file gate's stated refusal, and
 * these fixtures construct the walk directly — which is why the incoherence was worth fixing before
 * registration rather than after.
 */
internal class KotlinImportAliasTest : KotlinSnippetTestCase() {

    /**
     * **#93's own repro, rendered coherently**: the import declares a placeholder, and the code below
     * it uses that placeholder and no other.
     *
     * The alias takes the **kind of what it aliases** — a type alias is a `TypeN`, a function alias a
     * `methodN` — so the import still reads as an import of the thing it imports. It does not take
     * the aliased symbol's *number*: `Payment` and `Pay` are two names a reader has to map back, the
     * file renamed one to the other, and an output spelling both `Type3` would have thrown that away
     * and left `import … Type3 as Type3` behind.
     */
    fun `test an import alias renders as a declaration of its own`() {
        assertTheHarnessResolves()
        writeTheAliasedDeclarations()
        assertEquals(
            "the import declares one placeholder and the code below it uses another",
            """
            package com.pkg1.pkg2

            import com.pkg1.pkg2.Type3 as Type4
            import com.pkg1.pkg2.method5 as method6

            fun method7(param8: Type4): Int {
                println(param8)
                return method6(1)
            }
            """.trimIndent(),
            kotlinOutputFor(ALIASING_PATH, ALIASING),
        )
    }

    /**
     * **The alias and every use of it are one entry**, and the aliased declaration is a second one.
     *
     * Asserted over keys rather than over the output alone, because the golden above would also be
     * produced by a walk that minted two entries which happened to be numbered next to each other.
     * One symbol holds one placeholder; that the two are *the same symbol* is the claim.
     */
    fun `test the alias and its uses are one symbol and the aliased declaration another`() {
        assertTheHarnessResolves()
        writeTheAliasedDeclarations()
        val symbols = kotlinPlanFor(ALIASING_PATH, ALIASING).symbols()

        val alias = symbols.filter { it.text == "Pay" }
        assertEquals("the fixture writes `Pay` twice — the alias and the use in the signature — and the walk disagrees", 2, alias.size)
        assertEquals(
            "the alias declaration and the use of it are two entries",
            1,
            alias.map { it.symbol.key }.distinct().size,
        )
        assertEquals(
            "the alias is keyed on the aliased declaration, so the two share a placeholder",
            emptyList<String>(),
            alias.map { it.symbol.key }.intersect(symbols.filter { it.text == "Payment" }.map { it.symbol.key }.toSet()).toList(),
        )
    }

    /**
     * **An alias is a word the developer chose, and it is what the mapping table hands back.**
     *
     * This is the direction that settled the rule. A reply is read placeholder-first and decoded
     * against this table, so `Type4 -> Pay` is what puts the developer's own file back together —
     * where an alias rendered as the aliased symbol's placeholder would have restored
     * `import com.acme.ledger.Payment as Payment` and quietly dropped the rename.
     */
    fun `test the alias is what the mapping hands back`() {
        assertTheHarnessResolves()
        writeTheAliasedDeclarations()
        val mapping = kotlinResultFor(ALIASING_PATH, ALIASING).mapping

        assertEquals("the alias decodes to something other than the word the developer wrote", "Pay", mapping["Type4"])
        assertEquals("the aliased class decodes to something other than its own name", "Payment", mapping["Type3"])
        assertEquals("the function alias decodes to something other than the developer's word", "fee", mapping["method6"])
        assertEquals("the aliased function decodes to something other than its own name", "feeFor", mapping["method5"])
    }

    /**
     * **Nothing in this file failed to resolve, and the invocation says so.**
     *
     * The separable half of #93: the alias resolves, so neither the row nor the count may claim it
     * did not. Asserted on the count *and* on the list, because they are two surfaces — the balloon's
     * number and the preview's preservable rows — and it is the second that offers the fail-open.
     */
    fun `test an aliased name is not reported as unresolved`() {
        assertTheHarnessResolves()
        writeTheAliasedDeclarations()
        val plan = kotlinPlanFor(ALIASING_PATH, ALIASING)
        val result = kotlinResultFor(plan)

        assertEquals(
            "the walk reported a name the IDE resolved as unresolved",
            emptyList<String>(),
            plan.symbols().filter { it.symbol.origin == SymbolOrigin.UNRESOLVED }.map { it.text },
        )
        assertEquals("the balloon reports names the IDE could not resolve, on a file with none", 0, result.counts.unknown)
        assertEquals("a row offers a preserve on a name the IDE resolved", emptyList<String>(), result.unknowns.map { it.name })
    }

    /**
     * **A name that merely coincides with an alias is not the alias**, and this is the case that
     * decides how the question is asked.
     *
     * The platform's `getImportAlias()` answers it by looking the *written spelling* up in the file's
     * import list, so a local `fee` shadowing an alias named `fee` comes back as the alias. Taking
     * that answer would render a local variable under the import's placeholder — the same
     * two-symbols-one-placeholder incoherence this file exists to remove, in the other direction. So
     * the alias is taken only where it actually **names what the use resolved to**.
     */
    fun `test a local name that only coincides with an alias is not the alias`() {
        assertTheHarnessResolves()
        writeTheAliasedDeclarations()
        assertEquals(
            "a local shadowing an alias took the alias's placeholder",
            """
            package com.pkg1.pkg2

            import com.pkg1.pkg2.method3 as method4

            fun method5(): Int {
                val local6 = 2
                return local6
            }

            fun method7(): Int = method4(1)
            """.trimIndent(),
            kotlinOutputFor(
                "com/acme/ledger/Shadowed.kt",
                """
                package com.acme.ledger

                import com.acme.ledger.feeFor as fee

                fun use(): Int {
                    val fee = 2
                    return fee
                }

                fun charge(): Int = fee(1)
                """.trimIndent(),
            ),
        )
    }

    /**
     * **An alias on a library symbol renames**, because the word is the developer's even where the
     * symbol is not.
     *
     * The spine rule anonymizes a symbol iff its declaring file is project-owned, and the file
     * declaring `Rx` is the one being copied. `kotlin.text.Regex` is preserved beside it, which is
     * the same sentence read on the other symbol — and this is the silence rule's stated reason for
     * excluding aliases, holding on a case where the aliased declaration is not the project's at all.
     */
    fun `test an alias on a library symbol is the developer's word and renames`() {
        assertTheHarnessResolves()
        assertEquals(
            "the alias of a library type kept the developer's word",
            """
            package com.pkg1.pkg2

            import kotlin.text.Regex as Type3

            fun method4(param5: String): Type3 = Type3(param5)
            """.trimIndent(),
            kotlinOutputFor(
                "com/acme/ledger/Library.kt",
                """
                package com.acme.ledger

                import kotlin.text.Regex as Rx

                fun compile(pattern: String): Rx = Rx(pattern)
                """.trimIndent(),
            ),
        )
    }

    /**
     * **An alias whose import does not resolve is unresolved, and so are its uses — one `Unknown`
     * between them.**
     *
     * Red or incomplete code is normal rather than exceptional, and this is the direction the
     * `Unknown` namespace is *for*: here the IDE genuinely cannot resolve the name, so saying so is
     * true. What the rule buys is that the two halves still agree — the alias declaration and every
     * use of it fail closed into **one** namespace, rather than the file declaring one name and
     * using another. The aliased name is a second `Unknown` because it is a second name that did not
     * resolve, which is the same sentence read on the other symbol.
     *
     * The **count** is asserted here and not only the output, and it is the other half of the count
     * assertion in [test an aliased name is not reported as unresolved]: that one pins zero, and a
     * change that simply stopped counting unresolved names would leave it green. Two names did not
     * resolve, and *2 names the IDE could not resolve* is what the balloon owes.
     */
    fun `test an alias whose import does not resolve agrees with its uses`() {
        assertTheHarnessResolves()
        val source = """
            package com.acme.ledger

            import com.acme.ledger.Missing as Gone

            fun use(g: Gone): Gone = g
        """.trimIndent()

        val result = kotlinResultFor("com/acme/ledger/Missing.kt", source)

        assertEquals(
            "the alias declaration and its uses fell into two Unknown namespaces",
            """
            package com.pkg1.pkg2

            import com.pkg1.pkg2.Unknown3 as Unknown4

            fun method5(param6: Unknown4): Unknown4 = param6
            """.trimIndent(),
            result.text,
        )
        assertEquals(
            "the balloon miscounted the names the IDE genuinely could not resolve",
            2,
            result.counts.unknown,
        )
    }

    /**
     * **The alias's key is never written down**, which is what *keyed by where it is written* means
     * where it matters: a durable mapping is shared across snippets, and an alias is scoped to one
     * file and cannot be named from another.
     *
     * The role is the aliased symbol's, and it is asserted here rather than read off the golden: a
     * `TypeN` in the output is what a reader sees, and *the walk reported a type* is what produced it.
     */
    fun `test the alias is keyed on where it is written and is not persistable`() {
        assertTheHarnessResolves()
        writeTheAliasedDeclarations()
        val symbols = kotlinPlanFor(ALIASING_PATH, ALIASING).symbols()

        val type = symbols.first { it.text == "Pay" }.symbol
        assertFalse("a file-local alias reached a durable mapping", type.keyIsQualified)
        assertEquals("an alias of a class was not reported as a type", SymbolRole.TYPE, type.role)
        assertEquals("the alias is declared in the file being copied and was classified elsewhere", SymbolOrigin.IN_CONTENT, type.origin)

        val method = symbols.first { it.text == "fee" }.symbol
        assertFalse("a file-local alias reached a durable mapping", method.keyIsQualified)
        assertEquals("an alias of a function was not reported as a method", SymbolRole.METHOD, method.role)
    }

    /**
     * **An aliased type renames where it is *constructed* as well as where it is named**, and the two
     * are one placeholder.
     *
     * `Pay(…)` resolves past the alias to the **constructor**, which is a different declaration from
     * the class the import names — so the comparison that decides whether an alias applies has to
     * make the normalisation the one key rule already makes, that a constructor is its class.
     * Without it an alias would redirect where it is written as a type and not where it is called,
     * which is one alias wearing two placeholders: the incoherence this rule exists to remove,
     * reintroduced two lines further down the file.
     *
     * A `typealias` under an alias is here for the opposite reason — it needs no rule. `M()` resolves
     * to the same declaration `import … Money as M` names, so it falls out of the comparison as
     * written, and this is the assertion that says so rather than a comment claiming it.
     */
    fun `test an aliased type renames where it is constructed as well as where it is named`() {
        assertTheHarnessResolves()
        writeTheAliasedDeclarations()
        assertEquals(
            "an alias redirected where it was named and not where it was called",
            """
            package com.pkg1.pkg2

            import com.pkg1.pkg2.Type3 as Type4
            import com.pkg1.pkg2.Type5 as Type6

            fun method7(): Type4 = Type4()

            fun method8(): Type6 = Type6()
            """.trimIndent(),
            kotlinOutputFor(
                "com/acme/ledger/Constructed.kt",
                """
                package com.acme.ledger

                import com.acme.ledger.Payment as Pay
                import com.acme.ledger.Money as M

                fun make(): Pay = Pay()

                fun makeAgain(): M = M()
                """.trimIndent(),
            ),
        )
    }

    /**
     * **Every other shape an `import` can name takes the same rule and needs none of its own** — a
     * Java class and its constructor, a Java `static` member, an enum entry.
     *
     * A row of `KotlinShapesTest`'s *shapes that need no rule of their own* table, written here
     * instead because the rule the row is about is this one. Each of these resolves to something that is not a Kotlin declaration, or not a
     * classifier, or both, and the fully-qualified spelling the comparison is made on has to reach
     * all of them — a comparison that only knew Kotlin classifiers would be green on the fixture
     * above and silently stop redirecting here.
     */
    fun `test a Java class, a Java static member and an enum entry are aliased by the same rule`() {
        assertTheHarnessResolves()
        myFixture.addFileToProject(
            "com/acme/ledger/Fees.java",
            """
            package com.acme.ledger;

            public class Fees {
                public static int feeFor(int amount) { return amount; }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "com/acme/ledger/Colour.kt",
            """
            package com.acme.ledger

            enum class Colour { RED }
            """.trimIndent(),
        )
        assertEquals(
            "a shape an import can name did not take the alias rule",
            """
            package com.pkg1.pkg2

            import com.pkg1.pkg2.Type3 as Type4
            import com.pkg1.pkg2.Type3.method5 as method6
            import com.pkg1.pkg2.Type7.field8 as field9

            fun method10(): Int {
                val local11 = Type4()
                println(local11)
                println(field9)
                return method6(1)
            }
            """.trimIndent(),
            kotlinOutputFor(
                "com/acme/ledger/Imported.kt",
                """
                package com.acme.ledger

                import com.acme.ledger.Fees as F
                import com.acme.ledger.Fees.feeFor as fee
                import com.acme.ledger.Colour.RED as R

                fun use(): Int {
                    val f = F()
                    println(f)
                    println(R)
                    return fee(1)
                }
                """.trimIndent(),
            ),
        )
    }

    /**
     * The declarations this file's fixtures import under other names, in a file of their own.
     *
     * One file for every test that needs one, rather than an inline fixture per test: two files at
     * one path declaring one class with drifting bodies is a way for two goldens to stop being about
     * the same thing. Nothing here is referred to by every test — a `typealias` no snippet names
     * costs the snippet nothing, because only what the snippet writes is walked.
     */
    private fun writeTheAliasedDeclarations() {
        myFixture.addFileToProject(
            "com/acme/ledger/Payment.kt",
            """
            package com.acme.ledger

            class Payment

            typealias Money = Payment

            fun feeFor(amount: Int): Int = amount
            """.trimIndent(),
        )
    }

    private companion object {

        const val ALIASING_PATH = "com/acme/ledger/Aliasing.kt"

        /**
         * #93's repro, unchanged: a type and a function, each declared under one name and used under
         * another.
         */
        val ALIASING = """
            package com.acme.ledger

            import com.acme.ledger.Payment as Pay
            import com.acme.ledger.feeFor as fee

            fun use(p: Pay): Int {
                println(p)
                return fee(1)
            }
        """.trimIndent()
    }
}
