package com.snippetveil.plugin.kotlin

import com.snippetveil.core.SymbolRole
import com.snippetveil.plugin.symbols

/**
 * **The Kotlin shapes that fall out of the rules already written, and get no rule of their own.**
 *
 * Each test here is one row of the ticket's table, and each is an **assertion rather than a note** —
 * which is the point of writing them down at all. Whoever meets one of these shapes and finds it
 * behaving correctly should find a test saying so, rather than reaching for a sixth forced-sharing
 * rule to cover a case four existing rules already reach.
 *
 * Two rows are asserted elsewhere and are not repeated: the operator conventions behind `a + b`,
 * `list[i]`, `for (x in xs)` and `x++` carry no identifier, and `componentN`, `copy` and
 * destructuring are the silence rule and forced-sharing rule 5 in that order — both in
 * `KotlinSilenceTest`, beside the leak case they are indistinguishable from.
 *
 * Whole-file goldens, because a shape *needing no rule* is a claim about the whole output: a test
 * that read one placeholder out of the table would be green on an output that had renamed something
 * else beside it.
 */
internal class KotlinShapesTest : KotlinSnippetTestCase() {

    /**
     * **A named argument is the spine rule on the *callee's* parameter** — a library callee is
     * preserved and a project callee renames, with the label following in lockstep.
     *
     * Ownership is **resolved, never assumed**, and that is a Kotlin-specific obligation: Java has no
     * syntax that names a parameter of a callee, so the Java walk may treat a `PsiParameter` as
     * project-owned by construction. A Kotlin walk that shortcut it the same way would rename
     * `separator` in `joinToString(separator = ",")` and break every call it touched — a *plausible*
     * artifact, which is the class this product refuses outright.
     */
    fun `test a named argument follows the ownership of the parameter it names`() {
        assertTheHarnessResolves()
        assertRenders(
            "com/acme/ledger/Arguments.kt",
            """
            package com.acme.ledger

            fun settle(amount: Int): Int = amount

            fun use(xs: List<Int>): String {
                settle(amount = 1)
                return xs.joinToString(separator = ",")
            }
            """.trimIndent(),
            """
            package com.pkg1.pkg2

            fun method3(param4: Int): Int = param4

            fun method5(param6: List<Int>): String {
                method3(param4 = 1)
                return param6.joinToString(separator = ",")
            }
            """.trimIndent(),
        )
    }

    /**
     * **An annotation attribute name *is* a named argument in Kotlin**, so the ownership-inheritance
     * rule transposes unchanged and the attribute takes the `attr` kind it already had.
     */
    fun `test an annotation attribute name is a named argument`() {
        assertTheHarnessResolves()
        assertRenders(
            "com/acme/ledger/Annotated.kt",
            """
            package com.acme.ledger

            annotation class Audited(val reason: String)

            @Audited(reason = "why")
            fun settle(): Int = 1
            """.trimIndent(),
            """
            package com.pkg1.pkg2

            annotation class Anno3(val attr4: String)

            @Anno3(attr4 = "why")
            fun method5(): Int = 1
            """.trimIndent(),
        )
    }

    /**
     * **An unnamed `value` argument has no token**: `KtDefaultAnnotationArgumentReference` reports an
     * **empty** `rangeInElement`, so there is nothing in the file spelling the attribute's name and
     * nothing for the walk to report.
     *
     * Asserted as *no occurrence names the attribute*, rather than as a golden alone — an output
     * identical to this one would also be produced by a walk that reported the attribute over a
     * zero-width range and spliced nothing, and that walk would put a phantom row in the preview.
     */
    fun `test an unnamed value argument is not a token`() {
        assertTheHarnessResolves()
        val source = """
            package com.acme.ledger

            annotation class Audited(val value: String)

            @Audited("why")
            fun settle(): Int = 1
        """.trimIndent()

        val plan = kotlinPlanFor("com/acme/ledger/Unnamed.kt", source)

        assertEquals(
            "the attribute is named once — at its declaration — and nowhere in the annotation's use",
            1,
            plan.symbols().count { it.symbol.role == SymbolRole.ATTRIBUTE },
        )
        assertRenders(
            "com/acme/ledger/Unnamed2.kt",
            source,
            """
            package com.pkg1.pkg2

            annotation class Anno3(val attr4: String)

            @Anno3("why")
            fun method5(): Int = 1
            """.trimIndent(),
        )
    }

    /**
     * **An `object` declaration is an ordinary type declaration, and so is a named companion** — the
     * near-miss on the silence rule, which reaches only a companion the source did **not** name.
     */
    fun `test an object declaration and a named companion are ordinary types`() {
        assertTheHarnessResolves()
        assertRenders(
            "com/acme/ledger/Objects.kt",
            """
            package com.acme.ledger

            object Registry {
                fun reset() {}
            }

            class Named {
                companion object Factory
            }

            fun use() {
                Registry.reset()
                Named.Factory
            }
            """.trimIndent(),
            """
            package com.pkg1.pkg2

            object Type3 {
                fun method4() {}
            }

            class Type5 {
                companion object Type6
            }

            fun method7() {
                Type3.method4()
                Type5.Type6
            }
            """.trimIndent(),
        )
    }

    /**
     * **An `object` expression is anonymous**, so the identity-based keys already cover it: there is
     * no name in the source to rename, and the type it extends is a library name that is preserved.
     */
    fun `test an object expression is anonymous and needs no rule`() {
        assertTheHarnessResolves()
        assertRenders(
            "com/acme/ledger/Anonymous.kt",
            """
            package com.acme.ledger

            fun use(): Runnable = object : Runnable {
                override fun run() {}
            }
            """.trimIndent(),
            """
            package com.pkg1.pkg2

            fun method3(): Runnable = object : Runnable {
                override fun run() {}
            }
            """.trimIndent(),
        )
    }

    /**
     * **A type alias is an ordinary type in type position, and brings no new placeholder kind.**
     * `KotlinAddsNoRuleTest` holds the second half of that structurally over the whole table.
     */
    fun `test a type alias is an ordinary type`() {
        assertTheHarnessResolves()
        assertRenders(
            "com/acme/ledger/Aliases.kt",
            """
            package com.acme.ledger

            typealias Money = Int

            fun settle(amount: Money): Money = amount
            """.trimIndent(),
            """
            package com.pkg1.pkg2

            typealias Type3 = Int

            fun method4(param5: Type3): Type3 = param5
            """.trimIndent(),
        )
    }

    /**
     * **A declared label takes the label kind**, and every jump to it reaches the same placeholder —
     * a label is not a variable, and `break@local7` reads as one.
     */
    fun `test a declared label takes the label kind`() {
        assertTheHarnessResolves()
        assertRenders(
            "com/acme/ledger/Labels.kt",
            """
            package com.acme.ledger

            fun use(xs: List<Int>) {
                loop@ for (x in xs) {
                    break@loop
                }
            }
            """.trimIndent(),
            """
            package com.pkg1.pkg2

            fun method3(param4: List<Int>) {
                label5@ for (local6 in param4) {
                    break@label5
                }
            }
            """.trimIndent(),
        )
    }

    /**
     * **A SAM constructor is forced-sharing rule 4, unchanged**: the token is a constructor
     * identifier spelling its declaring type, so `PaymentHandler { }` renders `Type3 { }`.
     */
    fun `test a SAM constructor renders its declaring type`() {
        assertTheHarnessResolves()
        assertRenders(
            "com/acme/ledger/Sam.kt",
            """
            package com.acme.ledger

            fun interface PaymentHandler {
                fun handle(amount: Int)
            }

            fun use(): PaymentHandler = PaymentHandler { amount -> println(amount) }
            """.trimIndent(),
            """
            package com.pkg1.pkg2

            fun interface Type3 {
                fun method4(param5: Int)
            }

            fun method6(): Type3 = Type3 { param7 -> println(param7) }
            """.trimIndent(),
        )
    }

    /**
     * **A static import of a top-level callable is imported in the same shape as a Java static
     * import, and the import is rewritten in place** — the same placeholder in the import directive
     * and at the call site, because they are one symbol.
     */
    fun `test an import of a top-level callable is rewritten in place`() {
        assertTheHarnessResolves()
        myFixture.addFileToProject(
            "com/acme/ledger/Fees.kt",
            """
            package com.acme.ledger

            fun feeFor(amount: Int): Int = amount
            """.trimIndent(),
        )

        assertRenders(
            "com/acme/ledger/Imports.kt",
            """
            package com.acme.ledger

            import com.acme.ledger.feeFor

            fun use(): Int = feeFor(1)
            """.trimIndent(),
            """
            package com.pkg1.pkg2

            import com.pkg1.pkg2.method3

            fun method4(): Int = method3(1)
            """.trimIndent(),
        )
    }

    /**
     * **A string template without interpolation is an ordinary literal**, and it reaches no rule of
     * this walk's: **this walk reports identifiers.** Kotlin's literals and comments are not walked
     * here — a template *with* interpolation is a container with a snapping rule and a decomposition
     * of its own, and that is separate work, stated as a limit in the builder's own header.
     *
     * So the assertion is what the row claims — the template needs no rule of its own here — rather
     * than a claim that Kotlin literals are handled. Nothing about this reaches a user: the optional
     * descriptor declares no `languageSupport`, so no `.kt` file is anonymized from an IDE at all.
     */
    fun `test a string template without interpolation reaches no rule of this walk's`() {
        assertTheHarnessResolves()
        val source = """
            package com.acme.ledger

            fun use(): String = "no interpolation here"
        """.trimIndent()

        val plan = kotlinPlanFor("com/acme/ledger/Templates.kt", source)

        assertEquals(
            "this walk reports identifiers, and reported something else",
            plan.occurrences.size,
            plan.symbols().size,
        )
        assertRenders(
            "com/acme/ledger/Templates2.kt",
            source,
            """
            package com.pkg1.pkg2

            fun method3(): String = "no interpolation here"
            """.trimIndent(),
        )
    }

    /** Asserts that [source], anonymized whole, comes out as [expected]. */
    private fun assertRenders(path: String, source: String, expected: String) {
        assertEquals("This shape needs no rule of its own", expected, kotlinOutputFor(path, source))
    }
}
