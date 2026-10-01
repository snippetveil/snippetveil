package com.snippetveil.plugin.kotlin

import com.intellij.psi.PsiFileFactory
import com.snippetveil.core.AnonymizationResult
import com.snippetveil.core.AnonymizationSettings
import com.snippetveil.core.CodeContainer
import com.snippetveil.core.LedgerSnapshot
import com.snippetveil.core.RecordedInvocation
import com.snippetveil.core.Sidecar
import com.snippetveil.core.anonymize
import com.snippetveil.core.deanonymize
import com.snippetveil.core.plus
import com.snippetveil.plugin.SnippetRequest
import org.jetbrains.kotlin.idea.KotlinLanguage
import java.time.Instant

/**
 * **Commented-out Kotlin code is anonymized and kept; a comment whose body does not parse is
 * stripped** — the Java rule, read off real Kotlin.
 *
 * Every claim here is one only a real parser and a real resolve can make: whether a body parses
 * where it is written, and which declaration each name in it resolves to. What the engine then does
 * with the occurrences is `:core`'s, and it does nothing new for Kotlin.
 */
internal class KotlinCommentedOutCodeTest : KotlinSnippetTestCase() {

    /**
     * **The ticket in one fixture.** The commented-out call is kept with each name replaced by the
     * placeholder it takes in live code; the TODO is prose, and goes.
     */
    fun `test a commented-out statement is kept and renamed and a prose comment is stripped`() {
        assertTheHarnessResolves()

        val result = copyOf(
            """
            package com.acme.billing

            class Customer(private val customer: Customer?, private var order: Customer?) {
                fun assignOrder(order: Customer?) {
                    // this.customer?.assignOrder(order)
                    // TODO: fix this
                    this.order = order
                }
            }
            """.trimIndent(),
        )

        assertEquals(
            """
            package com.pkg1.pkg2

            class Type3(private val field4: Type3?, private var field5: Type3?) {
                fun method6(param7: Type3?) {
                    // this.field4?.method6(param7)
                    this.field5 = param7
                }
            }
            """.trimIndent(),
            result.text,
        )
        assertEquals("only the prose was stripped", 1, result.comments.stripped)
        assertEquals(1, result.comments.anonymized)
    }

    /**
     * **The body is parsed at the comment's own position.** Kotlin has no member-position fragment,
     * so a commented-out property or function parsed as a block would come back a local, and an
     * import would not parse at all. Parsed where it is written, each one parses as what that
     * position holds, and is kept with its names renamed — the property and the function keyed as
     * members of their class, exactly as they would be uncommented, so the function takes the
     * placeholder of the live one of the same name.
     */
    fun `test a commented-out property, function and import parse at their own position`() {
        assertTheHarnessResolves()
        myFixture.addFileToProject("com/acme/billing/Invoice.kt", "package com.acme.billing\n\nclass Invoice\n")

        val result = copyOf(
            """
            package com.acme.billing

            import java.math.BigDecimal
            // import com.acme.billing.Invoice

            class Customer(private val customer: Customer?) {
                // private val vipDiscount: BigDecimal = BigDecimal.ONE
                // fun charge() { this.customer?.refund() }
                fun charge() {}
                fun refund() {}
            }
            """.trimIndent(),
        )

        assertEquals(
            """
            package com.pkg1.pkg2

            import java.math.BigDecimal
            // import com.pkg1.pkg2.Type3

            class Type4(private val field5: Type4?) {
                // private val field6: BigDecimal = BigDecimal.ONE
                // fun method7() { this.field5?.method8() }
                fun method7() {}
                fun method8() {}
            }
            """.trimIndent(),
            result.text,
        )
        assertEquals(0, result.comments.stripped)
    }

    /**
     * **A name in a kept comment takes the placeholder it takes in live code, spelled for Kotlin.** A
     * Kotlin property is `field4` in the comment as on the live line under it, never an accessor; and
     * a Java getter written as a Kotlin property access de-prefixes to the one placeholder the Java
     * field behind it holds, in the comment exactly as in code.
     */
    fun `test a kept comment spells Kotlin and Java symbols as live code does`() {
        assertTheHarnessResolves()
        myFixture.addFileToProject(
            "com/acme/ledger/JavaBean.java",
            """
            package com.acme.ledger;

            public class JavaBean {
                private String body;
                public String getBody() { return body; }
                public void setBody(String body) { this.body = body; }
            }
            """.trimIndent(),
        )

        val result = anonymize(
            kotlinPlanFor(
                "com/acme/ledger/Ledger.kt",
                """
                package com.acme.ledger

                class Ledger(val merchantRef: String) {
                    fun use(javaObj: JavaBean) {
                        println(javaObj.body)
                        // println(javaObj.body + this.merchantRef)
                        // javaObj.body = merchantRef
                        println(merchantRef)
                    }
                }
                """.trimIndent(),
            ),
            AnonymizationSettings.DEFAULTS,
            LedgerSnapshot.EMPTY,
        )

        assertEquals(
            """
            package com.pkg1.pkg2

            class Type3(val field4: String) {
                fun method5(param6: Type7) {
                    println(param6.field8)
                    // println(param6.field8 + this.field4)
                    // param6.field8 = field4
                    println(field4)
                }
            }
            """.trimIndent(),
            result.text,
        )
    }

    /**
     * A second top-level class somebody commented out sits at **file** position, and parses as a
     * file: it is kept, it is a class of the file's package, and the class it names resolves through
     * the file it is written in.
     */
    fun `test a commented-out top-level class is kept at file position`() {
        assertTheHarnessResolves()

        val result = copyOf(
            """
            class Customer
            // class Vip(val owner: Customer)
            """.trimIndent(),
        )

        assertEquals(
            """
            class Type1
            // class Type2(val field3: Type1)
            """.trimIndent(),
            result.text,
        )
    }

    /**
     * **Code contains comments, so each nested comment meets the verdict on its own terms** — and a
     * nested block comment is the same case as a nested line comment, because Kotlin's block comments
     * nest: the outer one is a single token in the file, and the inner one is a comment of its own
     * only once the outer body is parsed. The prose after a commented-out call goes; a second
     * commented-out call after it stays.
     */
    fun `test a nested prose comment is stripped and a nested code comment is kept, line and block alike`() {
        assertTheHarnessResolves()

        val result = copyOf(
            """
            class Customer {
                fun charge() {}
                fun ship() {}

                fun settle(customer: Customer, order: Customer) {
                    // customer.charge() // premium tier only
                    // customer.charge() // order.ship()
                    /* customer.charge() /* premium tier only */ */
                    /* customer.charge() /* order.ship() */ */
                }
            }
            """.trimIndent(),
        )

        assertEquals(
            """
            class Type1 {
                fun method2() {}
                fun method3() {}

                fun method4(param5: Type1, param6: Type1) {
                    // param5.method2()
                    // param5.method2() // param6.method3()
                    /* param5.method2() */
                    /* param5.method2() /* param6.method3() */ */
                }
            }
            """.trimIndent(),
            result.text,
        )
        assertEquals("the nested prose is what was stripped", 2, result.comments.stripped)
    }

    /**
     * **A vacuous parse is not a parse.** An empty `//`, a body of whitespace and a body holding only
     * a nested comment parse to nothing at all, and none of them is code anonymized.
     */
    fun `test an empty, a blank and a nested-only comment are stripped`() {
        val result = copyOf(
            """
            class Customer {
                fun charge() {
                    //
                    /*   */
                    // // this.charge()
                }
            }
            """.trimIndent(),
        )

        assertEquals(
            """
            class Type1 {
                fun method2() {
                }
            }
            """.trimIndent(),
            result.text,
        )
        assertEquals(3, result.comments.stripped)
    }

    /**
     * **Kotlin prose that parses is prose.** Kotlin reads a bare word as an expression and three of
     * them as an infix call, so `retry on timeout` parses — as nothing but names, which is the
     * vacuous parse's second way of saying nothing. It is stripped, exactly as its Java twin is. A
     * body that does anything more than name things is code, and is kept.
     */
    fun `test prose that parses as names only is stripped and anything more is kept`() {
        val result = copyOf(
            """
            class Customer {
                fun charge(x: Int, foo: Customer) {
                    // retry on timeout
                    // TODO
                    // fix later
                    // retry(onTimeout)
                    // x = 1
                    // foo.bar()
                }
            }
            """.trimIndent(),
        )

        assertEquals(
            """
            class Type1 {
                fun method2(param3: Int, param4: Type1) {
                    // Unknown5(Unknown6)
                    // param3 = 1
                    // param4.Unknown7()
                }
            }
            """.trimIndent(),
            result.text,
        )
        assertEquals(3, result.comments.stripped)
    }

    /**
     * **A stale fragment resolves unevenly, and the verdict does not fail on it.** A name in it that no
     * longer resolves is an `Unknown`, and the fragment is kept. A resolved symbol that occurs only
     * inside a comment is written to the mapping exactly as it would be from live code.
     */
    fun `test a stale fragment is kept with its unresolved names as unknowns and its resolved ones persisted`() {
        assertTheHarnessResolves()

        val result = copyOf(
            """
            class Customer {
                private var vipDiscount: Int = 0

                fun settle() {
                    // this.vipDiscount = legacyRate()
                }
            }
            """.trimIndent(),
            selecting = "fun settle() {\n        // this.vipDiscount = legacyRate()\n    }",
        )

        assertEquals("fun method1() {\n        // this.field2 = Unknown3()\n    }", result.text)
        assertEquals(listOf("legacyRate"), result.unknowns.map { it.name })
        assertEquals(
            "the property named only in the comment was not persisted: ${result.delta.placeholders}",
            "field2",
            result.delta.placeholders["field:class:Customer#vipDiscount"]?.placeholder,
        )
    }

    /**
     * **The tick keeps prose verbatim, and it never un-renames.** With it on, the TODO survives as
     * written and the commented-out call is still anonymized.
     */
    fun `test with the tick on prose is kept verbatim and a parsed fragment is still renamed`() {
        assertTheHarnessResolves()

        val result = copyOf(
            """
            class Customer {
                fun charge() {
                    // TODO: reconcile against the merchant ledger
                    // this.charge()
                }
            }
            """.trimIndent(),
            AnonymizationSettings(keepComments = true),
        )

        assertEquals(
            """
            class Type1 {
                fun method2() {
                    // TODO: reconcile against the merchant ledger
                    // this.method2()
                }
            }
            """.trimIndent(),
            result.text,
        )
        assertEquals(0, result.comments.stripped)
    }

    /**
     * **KDoc is not carved out**, any more than javadoc is. Its body is what PSI exposes, its
     * delimiters and leading asterisks dropped as tokens; a body carrying `@param` does not parse, so
     * the block is stripped with no rule needed. With the tick on it is prose kept verbatim, and its
     * `[links]` and `@param` target rename with the symbols they name.
     */
    fun `test a KDoc block with a param tag is stripped and with the tick on its links rename`() {
        assertTheHarnessResolves()
        val source = """
            class Customer {
                /**
                 * Charges through [Customer.settle].
                 * @param amount the amount
                 */
                fun charge(amount: Int) {}
                fun settle() {}
            }
        """.trimIndent()

        assertEquals(
            """
            class Type1 {
                fun method2(param3: Int) {}
                fun method4() {}
            }
            """.trimIndent(),
            copyOf(source).text,
        )
        assertEquals(
            """
            class Type1 {
                /**
                 * Charges through [Type1.method2].
                 * @param param3 the amount
                 */
                fun method4(param3: Int) {}
                fun method2() {}
            }
            """.trimIndent(),
            copyOf(source, AnonymizationSettings(keepComments = true)).text,
        )
    }

    /**
     * **Each occurrence carries the container it came from, and the plan stays flat.** A kept
     * fragment is never one occurrence spanning the comment: its parts lie inside it, each tagged as
     * read from that comment, and no two occurrences anywhere in the plan overlap.
     */
    fun `test occurrences are tagged with their container and never overlap`() {
        assertTheHarnessResolves()
        val source = """
            class Customer {
                fun charge(reason: String) {
                    // this.charge("late") // why
                    charge(reason)
                }
            }
        """.trimIndent()
        val plan = kotlinPlanFor("Customer.kt", source)

        val comment = source.indexOf("// this")..source.indexOf("// why") + "// why".length
        val inside = plan.occurrences.filter { it.start in comment }
        val outside = plan.occurrences - inside.toSet()

        assertEquals(
            listOf("SymbolOccurrence", "LiteralOccurrence", "CommentOccurrence"),
            inside.map { it::class.simpleName },
        )
        assertTrue("an occurrence spans the whole comment", inside.none { it.start == comment.first && it.end == comment.last })
        assertEquals(
            "every part of the kept line, the prose nested in it included, names that line",
            setOf(CodeContainer.ParsedComment(comment.first, comment.last)),
            inside.map { it.container }.toSet(),
        )
        assertTrue(outside.isNotEmpty() && outside.all { it.container == CodeContainer.LiveCode })
        assertTrue(
            "two occurrences overlap: ${plan.occurrences.map { it.start to it.end }}",
            plan.occurrences.zipWithNext().all { (first, second) -> first.end <= second.start },
        )
    }

    /**
     * **The leak check for a kept fragment: the prose nested in it does not leave.** The word list is
     * taken from the fixture's own comment text, never from its symbols — a symbol-derived list
     * cannot see prose. Matched as whole words, so that `the` is not found inside `method`.
     */
    fun `test the prose nested in a kept fragment leaves none of its words behind`() {
        assertTheHarnessResolves()
        val prose = "// reconcile against the Globex merchant ledger nightly"
        val blockProse = "/* Initech settles these by hand */"
        val source = """
            class Customer {
                fun charge() {
                    // this.charge() $prose
                    /* this.charge() $blockProse */
                }
            }
        """.trimIndent()

        val result = copyOf(source)

        assertTrue("the fragment was not kept: ${result.text}", "// this.method2()" in result.text)
        assertTrue("the block fragment was not kept: ${result.text}", "/* this.method2() */" in result.text)
        for (word in Regex("\\p{L}+").findAll(prose + " " + blockProse).map { it.value }) {
            assertFalse("`$word` survived: ${result.text}", Regex("\\b" + Regex.escape(word) + "\\b") in result.text)
        }
    }

    /**
     * **Round trip, stated where it holds.** With the tick on, one invocation reverses to the source
     * byte for byte, the kept fragment included. **On the default path the loss is the prose and
     * nothing else**, asserted rather than left as a caveat: a change that made this close on the
     * default path would be one that made the strip reversible, which is a strip that leaves the
     * prose on the clipboard.
     */
    fun `test the round trip closes with the tick on and loses only prose by default`() {
        assertTheHarnessResolves()
        val source = """
            class Customer {
                fun charge(amount: Int) {
                    // TODO: reconcile against the merchant ledger
                    // this.charge(amount + 1)
                    charge(amount)
                }
            }
        """.trimIndent()

        val kept = copyOf(source, AnonymizationSettings(keepComments = true))
        assertFalse("the snippet came back unchanged, so this asserts nothing", source == kept.text)
        assertEquals(source, reversalOf(kept))

        assertEquals(
            source.lines().filterNot { "TODO" in it }.joinToString("\n"),
            reversalOf(copyOf(source)),
        )
    }

    /**
     * **A name declared in the copy a body was parsed in is the user's own, whatever file the copy
     * reports.** The copy is nowhere in the project, so a copy that reported its own throwaway file
     * would classify every declaration it holds as nobody's — and keep its name. Shown with a parse
     * whose copy does exactly that: nothing the comment names may survive it verbatim.
     */
    fun `test a declaration in the copy is project content even when the copy reports its own file`() {
        assertTheHarnessResolves()
        val file = myFixture.configureByText(
            "Customer.kt",
            """
            class Customer {
                fun charge(customer: Customer) {
                    // val vipDiscount = customer.hashCode()
                }
            }
            """.trimIndent(),
        )
        val ownFile = KotlinCommentParser { comment, text ->
            PsiFileFactory.getInstance(project).createFileFromText(comment.containingFile.name, KotlinLanguage.INSTANCE, text)
        }

        val plan = KotlinPlanBuilder.build(SnippetRequest(project, file, emptyList()), ownFile)
        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertTrue("the fragment was not kept: ${result.text}", "// val " in result.text)
        for (name in listOf("vipDiscount", "customer", "Customer", "charge")) {
            assertFalse("`$name` survived: ${result.text}", Regex("\\b$name\\b") in result.text)
        }
    }

    /**
     * Copy Anonymized's own analysis of [source] — of the whole file, or of the text [selecting]
     * names — against an empty mapping.
     */
    private fun copyOf(
        source: String,
        settings: AnonymizationSettings = AnonymizationSettings.DEFAULTS,
        selecting: String? = null,
    ): AnonymizationResult {
        val marked = selecting?.let { source.replace(it, "<selection>$it</selection>") } ?: source
        return anonymize(kotlinPlanFor("com/acme/billing/Customer.kt", marked), settings, LedgerSnapshot.EMPTY)
    }

    /** [result] reversed by what one invocation of it recorded — the sidecar and the mapping both. */
    private fun reversalOf(result: AnonymizationResult): String = deanonymize(
        result.text,
        Sidecar.EMPTY.recording(RecordedInvocation(Instant.now(), result.mapping)),
        LedgerSnapshot.EMPTY + result.delta,
    ).text
}
