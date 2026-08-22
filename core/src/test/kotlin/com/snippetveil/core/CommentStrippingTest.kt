package com.snippetveil.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * **Comments and javadoc are stripped by default, behind a single per-invocation control, and the
 * strip is reported as a count so that removal is never silent.**
 *
 * Renaming symbols does essentially nothing to prose. An experiment measured **28/29/29 distinct
 * domain words surviving** across three naming schemes against **36** in the original — enough to
 * reconstruct the business domain — and those numbers *flatter* a real implementation, because the
 * experiment's script regex-rewrote identifiers inside comments where a PSI reference graph will
 * not. Comments hold no resolvable references. This is the largest single domain leak in the
 * product, and `// reconcile against the merchant ledger` sitting two lines above `field1` is
 * incoherent on its face.
 *
 * **Rewriting identifiers inside comments was rejected**: it is regex by another name, and it
 * under-delivers anyway, since `merchant ledger` as two lowercase prose words never matches
 * `merchantLedger`. Partial concealment plus manufactured inconsistency is the worst cell of the
 * matrix.
 */
class CommentStrippingTest {

    /** The default, and the whole ticket in one assertion: nobody asked for the strip. */
    @Test
    fun `a comment is stripped by default`() {
        val plan = planOf(
            """
            void post() {
                // reconcile against the merchant ledger
                settle();
            }
            """.trimIndent(),
            symbol("post", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT),
            symbol("settle", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT),
        ).withComment("// reconcile against the merchant ledger", CommentVerdict.PROSE)

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals(
            """
            void method1() {
                method2();
            }
            """.trimIndent(),
            result.text,
        )
    }

    /**
     * **One control, not two.** The leak is prose, and prose is in javadoc exactly as it is in a
     * line comment; a second knob would be a settings surface with no use case behind it.
     */
    @Test
    fun `javadoc is stripped by the same control`() {
        val plan = planOf(
            """
            /** Reconciles a batch against the merchant ledger. */
            void post() {}
            """.trimIndent(),
            symbol("post", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT),
        ).withComment("/** Reconciles a batch against the merchant ledger. */", CommentVerdict.PROSE)

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("void method1() {}", result.text)
    }

    /**
     * The reduction, which is per-invocation and lives nowhere else: with it on, the comment is
     * emitted exactly as it was written.
     */
    @Test
    fun `keepComments emits the comment verbatim`() {
        val snippet = """
            void post() {
                // reconcile against the merchant ledger
                settle();
            }
        """.trimIndent()
        val plan = planOf(
            snippet,
            symbol("post", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT),
            symbol("settle", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT),
        ).withComment("// reconcile against the merchant ledger", CommentVerdict.PROSE)

        val result = anonymize(plan, AnonymizationSettings(keepComments = true), LedgerSnapshot.EMPTY)

        assertEquals(
            """
            void method1() {
                // reconcile against the merchant ledger
                method2();
            }
            """.trimIndent(),
            result.text,
        )
        assertEquals(0, result.comments.stripped, "nothing was stripped, so nothing may be counted")
    }

    /**
     * **The split is what makes the count actionable.** `2 comments stripped` is a number; `2
     * comments stripped, 1 of them commented-out code` is a reason to open the preview.
     *
     * The one question every variant in the naming experiment answered at a full 9/9 was *"find the
     * commented-out assignment"* — the ground-truth bug was a comment, and a reviewer called that
     * line the single most useful surviving clue. The default deletes it on every paste; the default
     * does not flip, and the loss is disclosed instead.
     */
    @Test
    fun `the strip count is split by parse verdict`() {
        val plan = planOf(
            """
            void post() {
                // reconcile against the merchant ledger
                // this.customer.setOrder(order);
                settle();
            }
            """.trimIndent(),
            symbol("post", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT),
            symbol("settle", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT),
        )
            .withComment("// reconcile against the merchant ledger", CommentVerdict.PROSE)
            .withComment("// this.customer.setOrder(order);", CommentVerdict.CODE)

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals(1, result.comments.prose)
        assertEquals(1, result.comments.code)
        assertEquals(2, result.comments.stripped)
    }

    /**
     * Two comments on consecutive lines, which is the shape where a strip that reached for the
     * whitespace around it could reach into the strip before it. Each takes its own line and no
     * more.
     */
    @Test
    fun `consecutive whole-line comments each take one line`() {
        val plan = planOf(
            """
            void post() {
                // one
                // two
                settle();
            }
            """.trimIndent(),
            symbol("post", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT),
            symbol("settle", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT),
        )
            .withComment("// one", CommentVerdict.PROSE)
            .withComment("// two", CommentVerdict.PROSE)

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals(
            """
            void method1() {
                method2();
            }
            """.trimIndent(),
            result.text,
        )
    }

    /** A trailing comment leaves the code it trails, and no space where it used to sit. */
    @Test
    fun `a trailing comment leaves the line it was on`() {
        val plan = planOf(
            """
            void post() {
                settle(); // the ledger is authoritative
            }
            """.trimIndent(),
            symbol("post", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT),
            symbol("settle", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT),
        ).withComment("// the ledger is authoritative", CommentVerdict.PROSE)

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals(
            """
            void method1() {
                method2();
            }
            """.trimIndent(),
            result.text,
        )
    }

    /** A comment with code after it on the line keeps that code where its indentation put it. */
    @Test
    fun `a comment in front of code takes the space behind it`() {
        val plan = planOf(
            """
            void post() {
                /* the merchant ledger */ settle();
            }
            """.trimIndent(),
            symbol("post", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT),
            symbol("settle", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT),
        ).withComment("/* the merchant ledger */", CommentVerdict.PROSE)

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals(
            """
            void method1() {
                method2();
            }
            """.trimIndent(),
            result.text,
        )
    }

    /**
     * **A stripped comment is not part of the output, so no rule about the output speaks about it.**
     *
     * A name written only inside one is not on the clipboard: counting it as *replaced* would be a
     * claim about a placeholder nobody can find, and a mapping row for it would explain a name that
     * is not there. The count is a claim about what was copied.
     */
    @Test
    fun `a name written only inside a stripped comment is neither counted nor mapped`() {
        val plan = planPlacing(
            """
            void post() {
                // merchantLedger is reconciled nightly
                settle();
            }
            """.trimIndent(),
            at(0, symbol("post", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT)),
            at(0, symbol("merchantLedger", SymbolRole.FIELD, SymbolOrigin.IN_CONTENT)),
            at(0, symbol("settle", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT)),
        ).withComment("// merchantLedger is reconciled nightly", CommentVerdict.PROSE)

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals(emptySet<String>(), result.placeholdersFor("merchantLedger"))
        assertEquals(2, result.counts.replaced, "only the two names that survive into the output count")
        assertFalse("merchantLedger" in result.text)
    }

    /**
     * The reverse of the case above: with the comment kept, the name inside it is a name in the
     * snippet like any other and renames with the symbol it belongs to.
     *
     * That is the whole of what *renaming through the PSI graph* means here — the occurrence exists
     * because a reference resolved, never because a word matched. Which words in a comment carry a
     * reference is the plan builder's business, and it reports only the ones that do.
     */
    @Test
    fun `a kept comment's resolved reference renames with the symbol it names`() {
        val plan = planPlacing(
            """
            /** @param merchantRef the merchant reference */
            void post(String merchantRef) {}
            """.trimIndent(),
            at(0, symbol("merchantRef", SymbolRole.PARAMETER, SymbolOrigin.IN_CONTENT)),
            at(0, symbol("post", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT)),
            at(1, symbol("merchantRef", SymbolRole.PARAMETER, SymbolOrigin.IN_CONTENT)),
        ).withComment("/** @param merchantRef the merchant reference */", CommentVerdict.PROSE)

        val result = anonymize(plan, AnonymizationSettings(keepComments = true), LedgerSnapshot.EMPTY)

        assertEquals(
            """
            /** @param param1 the merchant reference */
            void method2(String param1) {}
            """.trimIndent(),
            result.text,
        )
    }

    /**
     * A word in a stripped comment reserves nothing, because reservation is about the output: the
     * invariant is that a reader can map `Type1` in the AI's reply back onto exactly one thing, and
     * a `Type1` that was deleted stands for nothing at all.
     */
    @Test
    fun `a placeholder-shaped word in a stripped comment does not burn its number`() {
        val plan = planOf(
            """
            // Type1 is the ledger's own name for it
            class Ledger {}
            """.trimIndent(),
            symbol("Ledger", SymbolRole.TYPE, SymbolOrigin.IN_CONTENT),
        ).withComment("// Type1 is the ledger's own name for it", CommentVerdict.PROSE)

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("class Type1 {}", result.text)
    }
}
