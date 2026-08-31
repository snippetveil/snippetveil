package com.snippetveil.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * **A package renames one segment at a time, and the structure survives it.**
 *
 * Collapsing `com.acme.billing.Payment` to a single placeholder would be simpler and it would
 * destroy the fact the snippet is usually about: *same package or not*. Package-private access,
 * a sealed type's `permits` clause and every "why can't this see that" question ride on it, and a
 * reader handed one opaque token for a whole qualified name cannot answer any of them.
 *
 * So each segment is its own symbol with its own placeholder, keyed by the package it ends — which
 * is what makes two types in one package share a prefix and two types in sibling packages share
 * only what they really share.
 */
class PackageRenamingTest {

    /**
     * **The top-level segment is passed through, and every segment under it renames.**
     *
     * `com`, `org` and `io` carry no domain — there are three of them in practice and knowing which
     * one a snippet uses tells a reader nothing about the business. Renaming them is pure noise in
     * the output and one more placeholder in the mapping table a human has to read.
     *
     * That pass-through outranks the spine rule rather than sitting beside it: the project owns the
     * `com` directory, so the evidence here says [SymbolOrigin.IN_CONTENT] and the segment is
     * preserved anyway.
     */
    @Test
    fun `a qualified project type renames per segment with the top-level segment passed through`() {
        val text = """
            class Ledger {
                com.acme.billing.Payment payment;
            }
        """.trimIndent()

        val plan = planOf(
            text,
            pkg("com", SymbolOrigin.IN_CONTENT),
            pkg("com.acme", SymbolOrigin.IN_CONTENT),
            pkg("com.acme.billing", SymbolOrigin.IN_CONTENT),
            symbol("Payment", SymbolRole.TYPE, SymbolOrigin.IN_CONTENT, key = "class:com.acme.billing.Payment"),
            symbol("payment", SymbolRole.FIELD, SymbolOrigin.IN_CONTENT, key = "field:class:Ledger#payment"),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        result.assertKeptItsName(TOP_LEVEL, "com")
        assertEquals(
            "class Ledger {\n" +
                "    com.pkg1.pkg2.Type3 field4;\n" +
                "}",
            result.text,
        )
    }

    /**
     * **Two types in one package share their package placeholder; two in sibling packages share only
     * the prefix they really share.**
     *
     * This is the whole reason segments are separate symbols. `Payment` and `Refund` come out of
     * `com.pkg1.pkg2` and `Controller` out of `com.pkg1.pkg5`, so a reader can still see that the
     * first two are package-private-visible to each other and the third is not.
     *
     * The numbers come from one counter shared by every role — `pkg2` and `Type3` are consecutive
     * because they were allocated consecutively — which is the invariant that makes a placeholder
     * mean exactly one thing across a whole output. **That is why these are not the numbers the
     * ticket illustrates** (`com.pkg1.pkg2.Type1`): a per-role counter is what would produce them,
     * and it is the thing the shared counter exists to rule out. The structure the illustration is
     * about — which segments agree and which do not — is exactly as drawn.
     */
    @Test
    fun `types in one package share their package placeholder and types elsewhere do not`() {
        val text = """
            import com.acme.billing.Payment;
            import com.acme.billing.Refund;
            import com.acme.web.Controller;
        """.trimIndent()

        val com = pkg("com", SymbolOrigin.IN_CONTENT)
        val acme = pkg("com.acme", SymbolOrigin.IN_CONTENT)
        val billing = pkg("com.acme.billing", SymbolOrigin.IN_CONTENT)
        val plan = planPlacing(
            text,
            at(0, com), at(0, acme), at(0, billing),
            at(0, symbol("Payment", SymbolRole.TYPE, SymbolOrigin.IN_CONTENT, key = "class:com.acme.billing.Payment")),
            at(1, com), at(1, acme), at(1, billing),
            at(0, symbol("Refund", SymbolRole.TYPE, SymbolOrigin.IN_CONTENT, key = "class:com.acme.billing.Refund")),
            at(2, com), at(2, acme),
            at(0, pkg("com.acme.web", SymbolOrigin.IN_CONTENT)),
            at(0, symbol("Controller", SymbolRole.TYPE, SymbolOrigin.IN_CONTENT, key = "class:com.acme.web.Controller")),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        result.assertShared(SAME_PACKAGE, "acme")
        result.assertShared(SAME_PACKAGE, "billing")
        assertEquals(
            "import com.pkg1.pkg2.Type3;\n" +
                "import com.pkg1.pkg2.Type4;\n" +
                "import com.pkg1.pkg5.Type6;",
            result.text,
        )
    }

    /**
     * **The stated limit of the rule above, asserted rather than left to be discovered.**
     *
     * The pass-through is *positional* — the segment with nothing before it — so a project rooted at
     * a single-segment package emits that segment verbatim, and `billing` is a domain word where
     * `com` is not. The alternative is a *persistent* list of names preserved by spelling, which is
     * the one thing this product has ruled out everywhere else: such a list leaks by construction,
     * there is nowhere to stop adding to it, and it is a reduction nobody sees again after the day
     * they set it. The per-invocation preserve the preview offers is the other thing — keys rather
     * than spellings, unticked on every open, written down nowhere — and it is no answer here,
     * because it would have to be ticked on every paste for as long as the project has that root.
     *
     * This is green on purpose. It says what today's rule does, so that changing the rule is a
     * deliberate edit to an assertion rather than a golden quietly shifting under someone.
     */
    @Test
    fun `a single-segment project package is passed through, domain word and all`() {
        val text = "billing.Payment payment;"

        val plan = planOf(
            text,
            pkg("billing", SymbolOrigin.IN_CONTENT),
            symbol("Payment", SymbolRole.TYPE, SymbolOrigin.IN_CONTENT, key = "class:billing.Payment"),
            symbol("payment", SymbolRole.FIELD, SymbolOrigin.IN_CONTENT, key = "field:class:Ledger#payment"),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        result.assertKeptItsName(TOP_LEVEL, "billing")
        assertEquals("billing.Type1 field2;", result.text)
    }

    /**
     * **A preserved type keeps its simple name; its qualifiers still follow the package rules.**
     *
     * Deliberate rather than incidental: preservation reaches the symbol's own name, and the
     * package-renaming spine rule is not a thing it touches. So `com.acme.billing.PaymentFilter`
     * ticked in the preview renders `com.pkg1.pkg2.PaymentFilter` — the reader keeps the name that
     * made the snippet answerable, and still cannot read the employer's package layout off it.
     */
    @Test
    fun `a preserved type keeps its simple name and its package renames around it`() {
        val text = """
            class Ledger {
                com.acme.billing.PaymentFilter filter;
            }
        """.trimIndent()

        val plan = planOf(
            text,
            pkg("com", SymbolOrigin.IN_CONTENT),
            pkg("com.acme", SymbolOrigin.IN_CONTENT),
            pkg("com.acme.billing", SymbolOrigin.IN_CONTENT),
            symbol(
                "PaymentFilter",
                SymbolRole.TYPE,
                SymbolOrigin.IN_CONTENT,
                key = "class:com.acme.billing.PaymentFilter",
            ),
            symbol("filter", SymbolRole.FIELD, SymbolOrigin.IN_CONTENT, key = "field:class:Ledger#filter"),
        )

        val result = anonymize(
            plan,
            AnonymizationSettings(preservedSymbols = setOf("class:com.acme.billing.PaymentFilter")),
            LedgerSnapshot.EMPTY,
        )

        assertEquals(
            "class Ledger {\n" +
                "    com.pkg1.pkg2.PaymentFilter field3;\n" +
                "}",
            result.text,
        )
    }

    /**
     * A package the project does not own is preserved whole, by the spine rule and nothing else:
     * `java.util` is not renamed because it is the JDK's, not because of where the dots fall.
     *
     * This is the case the old *packages are not touched yet* behaviour got right by accident, and
     * it is worth keeping as an assertion now that the rule above can reach package segments at all.
     */
    @Test
    fun `a JDK package is preserved whole`() {
        val text = "java.util.List<String> rows;"

        val plan = planOf(
            text,
            pkg("java", SymbolOrigin.JDK),
            pkg("java.util", SymbolOrigin.JDK),
            symbol("List", SymbolRole.TYPE, SymbolOrigin.JDK, key = "class:java.util.List"),
            symbol("String", SymbolRole.TYPE, SymbolOrigin.JDK, key = "class:java.lang.String"),
            symbol("rows", SymbolRole.FIELD, SymbolOrigin.IN_CONTENT, key = "field:class:Ledger#rows"),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        result.assertKeptItsName(NOT_OURS, "util")
        assertEquals("java.util.List<String> field1;", result.text)
    }
}

private const val TOP_LEVEL = "the top-level segment is passed through"
private const val SAME_PACKAGE = "two types in one package share their package placeholder"
private const val NOT_OURS = "a package the project does not own is preserved"
