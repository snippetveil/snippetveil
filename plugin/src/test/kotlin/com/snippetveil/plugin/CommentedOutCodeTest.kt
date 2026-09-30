package com.snippetveil.plugin

import com.snippetveil.core.AnonymizationResult
import com.snippetveil.core.AnonymizationSettings
import com.snippetveil.core.CodeContainer
import com.snippetveil.core.LedgerSnapshot
import com.snippetveil.core.RecordedInvocation
import com.snippetveil.core.Sidecar
import com.snippetveil.core.anonymize
import com.snippetveil.core.deanonymize
import com.snippetveil.core.plus
import java.time.Instant

/**
 * **Commented-out Java code is anonymized and kept; a comment whose body does not parse is stripped.**
 *
 * Read off real Java, because every claim here is one only a real parser and a real resolve can
 * make: whether a body parses at the comment's own position, and which declaration each name in it
 * resolves to. What the engine then does with the occurrences is `:core`'s, and it does nothing new —
 * a name in a kept fragment is a name, a literal is a literal, and a nested comment meets the verdict
 * on its own terms.
 */
class CommentedOutCodeTest : JavaSnippetTestCase() {

    /**
     * **The ticket in one fixture.** The commented-out assignment is often the most useful clue in a
     * snippet, and the default used to delete it on every paste. It is kept now, with each name
     * replaced by the placeholder that name takes in live code; the TODO is prose, and prose is
     * where the domain leaks, so it goes.
     */
    fun `test a commented-out statement is kept and renamed and a prose comment is stripped`() {
        assertTheHarnessResolves()

        val result = copyOf(
            """
            class Customer {
                private Customer customer;
                private Customer order;

                void setOrder(Customer order) {
                    // this.customer.setOrder(order);
                    // TODO: fix this
                    this.order = order;
                }
            }
            """.trimIndent(),
        )

        assertEquals(
            """
            class Type1 {
                private Type1 field2;
                private Type1 field3;

                void setField3(Type1 param4) {
                    // this.field2.setField3(param4);
                    this.field3 = param4;
                }
            }
            """.trimIndent(),
            result.text,
        )
        assertEquals("only the prose was stripped", 1, result.comments.stripped)
    }

    /**
     * **The body is parsed at the comment's own position, not as a free-floating block.** Parsed as
     * a block — which is what the verdict used to do — a commented-out field, method or import fails
     * and is called prose. At its own position each one parses, and is kept with its names renamed.
     */
    fun `test a commented-out field, method and import parse at their own position`() {
        assertTheHarnessResolves()
        addClassInPackage("com.acme.billing", "Invoice")

        val result = copyOf(
            """
            package com.acme.billing;

            import java.util.List;
            // import com.acme.billing.Invoice;

            class Customer {
                private Customer customer;
                // private java.math.BigDecimal vipDiscount;
                // public void charge() { this.customer.charge(); }
                void charge() {}
            }
            """.trimIndent(),
        )

        assertEquals(
            """
            package com.pkg1.pkg2;

            import java.util.List;
            // import com.pkg1.pkg2.Type3;

            class Type4 {
                private Type4 field5;
                // private java.math.BigDecimal field6;
                // public void method7() { this.field5.method7(); }
                void method7() {}
            }
            """.trimIndent(),
            result.text,
        )
        assertEquals(0, result.comments.stripped)
    }

    /**
     * A second top-level class somebody commented out sits at **file** position, and parses as a
     * file: it is kept, its name is a class of the file's package, and the class it extends resolves
     * through the file it is written in.
     */
    fun `test a commented-out top-level class is kept at file position`() {
        assertTheHarnessResolves()

        val result = copyOf(
            """
            class Customer {}
            // class Vip extends Customer {}
            """.trimIndent(),
        )

        assertEquals(
            """
            class Type1 {}
            // class Type2 extends Type1 {}
            """.trimIndent(),
            result.text,
        )
    }

    /**
     * **Code contains comments, so each nested comment meets the verdict on its own terms.** The
     * prose after a commented-out call goes; a second commented-out call after it stays. The strip
     * count includes the nested prose although the user never saw it as a comment of its own —
     * accepted, because the count reports what was stripped, and that was.
     */
    fun `test a nested prose comment is stripped and a nested code comment is kept`() {
        assertTheHarnessResolves()

        val result = copyOf(
            """
            class Customer {
                void charge() {}
                void ship() {}

                void settle(Customer customer, Customer order) {
                    // customer.charge(); // premium tier only
                    // customer.charge(); // order.ship();
                }
            }
            """.trimIndent(),
        )

        assertEquals(
            """
            class Type1 {
                void method2() {}
                void method3() {}

                void method4(Type1 param5, Type1 param6) {
                    // param5.method2();
                    // param5.method2(); // param6.method3();
                }
            }
            """.trimIndent(),
            result.text,
        )
        assertEquals("the nested prose is what was stripped", 1, result.comments.stripped)
    }

    /**
     * **A vacuous parse is not a parse.** An empty `//` parses to nothing at all, and so does a body
     * of whitespace or one holding only a nested comment — none of them is code anonymized, and an
     * empty comment must never count as one.
     */
    fun `test an empty, a blank and a nested-only comment are stripped`() {
        val result = copyOf(
            """
            class Customer {
                void charge() {
                    //
                    /*   */
                    // // this.charge();
                }
            }
            """.trimIndent(),
        )

        assertEquals(
            """
            class Type1 {
                void method2() {
                }
            }
            """.trimIndent(),
            result.text,
        )
        assertEquals(3, result.comments.stripped)
    }

    /**
     * **A stale fragment resolves unevenly, and the verdict does not fail on it.** Commented-out code
     * is stale by construction, so a name in it that no longer resolves is an `Unknown` — something
     * this product *did* anonymize — and the fragment is kept.
     *
     * **A resolved symbol that occurs only inside a comment is written to the mapping** exactly as it
     * would be from live code: the key belongs to the declaration, not to where the reference sat.
     */
    fun `test a stale fragment is kept with its unresolved names as unknowns and its resolved ones persisted`() {
        assertTheHarnessResolves()

        val result = copyOf(
            """
            class Customer {
                private int vipDiscount;

                void settle() {
                    // this.vipDiscount = legacyRate();
                }
            }
            """.trimIndent(),
            selecting = "void settle() {\n        // this.vipDiscount = legacyRate();\n    }",
        )

        assertEquals("void method1() {\n        // this.field2 = Unknown3();\n    }", result.text)
        assertEquals(listOf("legacyRate"), result.unknowns.map { it.name })
        assertEquals(
            "the field named only in the comment was not persisted: ${result.delta.placeholders}",
            "field2",
            result.delta.placeholders["field:class:Customer#vipDiscount"]?.placeholder,
        )
    }

    /**
     * **The tick keeps prose verbatim, and it never un-renames.** With it on, the TODO survives as
     * written and the commented-out call is still anonymized — exactly as it is with the tick off.
     */
    fun `test with the tick on prose is kept verbatim and a parsed fragment is still renamed`() {
        assertTheHarnessResolves()

        val result = copyOf(
            """
            class Customer {
                void charge() {
                    // TODO: reconcile against the merchant ledger
                    // this.charge();
                }
            }
            """.trimIndent(),
            AnonymizationSettings(keepComments = true),
        )

        assertEquals(
            """
            class Type1 {
                void method2() {
                    // TODO: reconcile against the merchant ledger
                    // this.method2();
                }
            }
            """.trimIndent(),
            result.text,
        )
        assertEquals(0, result.comments.stripped)
    }

    /**
     * **Javadoc is not carved out.** Its body is what PSI exposes, with the delimiters and leading
     * asterisks dropped as tokens; a body carrying `@param` does not parse, so the block is stripped
     * with no rule needed. With the tick on it is prose kept verbatim, and its `{@link}` and `@param`
     * targets — resolvable references — rename with the symbols they name.
     */
    fun `test a javadoc block with a param tag is stripped and with the tick on its targets rename`() {
        assertTheHarnessResolves()
        val source = """
            class Customer {
                /**
                 * Charges through {@link Customer#settle}.
                 * @param amount the amount
                 */
                void charge(int amount) {}
                void settle() {}
            }
        """.trimIndent()

        assertEquals(
            """
            class Type1 {
                void method2(int param3) {}
                void method4() {}
            }
            """.trimIndent(),
            copyOf(source).text,
        )
        assertEquals(
            """
            class Type1 {
                /**
                 * Charges through {@link Type1#method2}.
                 * @param param3 the amount
                 */
                void method4(int param3) {}
                void method2() {}
            }
            """.trimIndent(),
            copyOf(source, AnonymizationSettings(keepComments = true)).text,
        )
    }

    /**
     * **Each occurrence carries the container it came from, and the plan stays flat.** A kept
     * fragment is never one occurrence spanning the comment: its parts lie inside it, each tagged as
     * read from that comment — by its range, so that kept comments can be counted — and no two
     * occurrences anywhere in the plan overlap.
     */
    fun `test occurrences are tagged with their container and never overlap`() {
        assertTheHarnessResolves()
        val source = """
            class Customer {
                void charge(String reason) {
                    // this.charge("late"); // why
                    charge(reason);
                }
            }
        """.trimIndent()
        val plan = planFor("Customer.java", source)

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
     * **The leak check for a kept fragment: the prose nested in it does not leave.**
     *
     * The word list is taken from the fixture's own comment text, never from its symbols — a
     * symbol-derived list cannot see prose, and prose is exactly what this is about. Matched as whole
     * words, so that `the` is not found inside `method`.
     */
    fun `test the prose nested in a kept fragment leaves none of its words behind`() {
        assertTheHarnessResolves()
        val prose = "// reconcile against the Globex merchant ledger nightly"
        val source = """
            class Customer {
                void charge() {
                    // this.charge(); $prose
                }
            }
        """.trimIndent()

        val result = copyOf(source)

        assertTrue("the fragment was not kept: ${result.text}", "// this.method2();" in result.text)
        for (word in Regex("\\p{L}+").findAll(prose).map { it.value }) {
            assertFalse("`$word` survived: ${result.text}", Regex("\\b" + Regex.escape(word) + "\\b") in result.text)
        }
    }

    /**
     * **Round trip, stated where it holds.** With the tick on, one invocation reverses to the source
     * byte for byte — the kept fragment included, since its names are placeholders like any other.
     *
     * **On the default path the loss is the prose and nothing else**, and that is asserted rather than
     * left as a caveat: the commented-out call comes back, the TODO does not. A change that made this
     * close on the default path would be one that made the strip reversible, which is a strip that
     * leaves the prose on the clipboard.
     */
    fun `test the round trip closes with the tick on and loses only prose by default`() {
        assertTheHarnessResolves()
        val source = """
            class Customer {
                void charge(int amount) {
                    // TODO: reconcile against the merchant ledger
                    // this.charge(amount + 1);
                    charge(amount);
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
     * Copy Anonymized's own analysis of [source] — of the whole file, or of the text [selecting]
     * names — against an empty mapping.
     */
    private fun copyOf(
        source: String,
        settings: AnonymizationSettings = AnonymizationSettings.DEFAULTS,
        selecting: String? = null,
    ): AnonymizationResult {
        val marked = selecting?.let { source.replace(it, "<selection>$it</selection>") } ?: source
        return anonymize(planFor("Customer.java", marked), settings, LedgerSnapshot.EMPTY)
    }

    /** [result] reversed by what one invocation of it recorded — the sidecar and the mapping both. */
    private fun reversalOf(result: AnonymizationResult): String = deanonymize(
        result.text,
        Sidecar.EMPTY.recording(RecordedInvocation(Instant.now(), result.mapping)),
        LedgerSnapshot.EMPTY + result.delta,
    ).text
}
