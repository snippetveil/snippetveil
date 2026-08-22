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

        val plan = planPlacing(
            text,
            at(0, pkg("com", SymbolOrigin.IN_CONTENT)),
            at(0, pkg("com.acme", SymbolOrigin.IN_CONTENT)),
            at(0, pkg("com.acme.billing", SymbolOrigin.IN_CONTENT)),
            at(0, symbol("Payment", SymbolRole.TYPE, SymbolOrigin.IN_CONTENT, key = "class:com.acme.billing.Payment")),
            at(0, symbol("payment", SymbolRole.FIELD, SymbolOrigin.IN_CONTENT, key = "field:class:Ledger#payment")),
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
     * mean exactly one thing across a whole output.
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
     * A package the project does not own is preserved whole, by the spine rule and nothing else:
     * `java.util` is not renamed because it is the JDK's, not because of where the dots fall.
     *
     * This is the case the old *packages are not touched yet* behaviour got right by accident, and
     * it is worth keeping as an assertion now that the rule above can reach package segments at all.
     */
    @Test
    fun `a JDK package is preserved whole`() {
        val text = "java.util.List<String> rows;"

        val plan = planPlacing(
            text,
            at(0, pkg("java", SymbolOrigin.JDK)),
            at(0, pkg("java.util", SymbolOrigin.JDK)),
            at(0, symbol("List", SymbolRole.TYPE, SymbolOrigin.JDK, key = "class:java.util.List")),
            at(0, symbol("String", SymbolRole.TYPE, SymbolOrigin.JDK, key = "class:java.lang.String")),
            at(0, symbol("rows", SymbolRole.FIELD, SymbolOrigin.IN_CONTENT, key = "field:class:Ledger#rows")),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("java.util.List<String> field1;", result.text)
    }
}

private const val TOP_LEVEL = "the top-level segment is passed through"
private const val SAME_PACKAGE = "two types in one package share their package placeholder"
