package com.snippetveil.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **String literals in project-owned code are anonymized by default, into their own `str`
 * namespace.** A literal is not passive text; it is a domain carrier, like a comment.
 *
 * Preserving literals verbatim is not the conservative default — it is a live leak. An experiment
 * counted 38 identifiable literals in the original snippets; blanket replacement took that to 0
 * while answering 5 of 6 questions at parity. Blanket replacement did not damage answerability, and
 * it closed a leak the size of the comment leak.
 *
 * The one exception is a literal carrying resolved PSI references, which renames **in lockstep with
 * the symbols those references name — never by inspecting the literal's text.** Text-driven
 * rewriting is the mechanism that manufactured a *confident, false* finding in the experiment: a
 * pattern-rewritten foreign-key literal went out of sync with its symbol-renamed field, and the
 * reviewer reported an inconsistency that does not exist upstream.
 */
class LiteralRedactionTest {

    /** The default, and the case with nothing clever in it: no references, so the whole content goes. */
    @Test
    fun `a string literal with no references is replaced whole`() {
        val plan = planOf(
            """log.warn("merchant settlement failed");""",
            symbol("log", SymbolRole.FIELD, SymbolOrigin.IN_CONTENT),
            symbol("warn", SymbolRole.METHOD, SymbolOrigin.LIBRARY),
        ).withLiteral(""""merchant settlement failed"""")

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("""field1.warn("str2");""", result.text)
    }

    /**
     * **Row one of the coverage table.** `JavaClassReferenceSet` yields one reference per dotted
     * segment, so the gaps are the dots — non-alphanumeric, and coverage holds. Each segment then
     * renames as whatever its own symbol renames as, including `com` being passed through as the
     * top-level segment.
     *
     * **The numbers are not the ones the ticket illustrates** (`com.pkg1.pkg2.Type1`), for the same
     * reason [PackageRenamingTest] says they are not: one counter is shared by every role, so
     * `pkg1`, `pkg2` and `Type3` are consecutive because they were allocated consecutively. The
     * structure the illustration is about — which segments rename and which agree — is as drawn.
     */
    @Test
    fun `a fully covered literal renames segment by segment with the symbols its references name`() {
        val plan = planOf(
            """Class.forName("com.acme.billing.Payment");""",
            symbol("Class", SymbolRole.TYPE, SymbolOrigin.JDK),
            symbol("forName", SymbolRole.METHOD, SymbolOrigin.JDK),
        ).withLiteral(
            """"com.acme.billing.Payment"""",
            covers = arrayOf(
                covering("com", pkg("com", SymbolOrigin.IN_CONTENT)),
                covering("acme", pkg("com.acme", SymbolOrigin.IN_CONTENT)),
                covering("billing", pkg("com.acme.billing", SymbolOrigin.IN_CONTENT)),
                covering("Payment", symbol("Payment", SymbolRole.TYPE, SymbolOrigin.IN_CONTENT, key = "class:com.acme.billing.Payment")),
            ),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("""Class.forName("com.pkg1.pkg2.Type3");""", result.text)
    }

    /**
     * **Row two.** The gap is `", "`, which bears no word — and this is the exact case that ruled
     * out strict full coverage, which would have lost `columnList` to it.
     */
    @Test
    fun `a punctuation-only gap does not break coverage`() {
        val merchantId = symbol("merchantId", SymbolRole.FIELD, SymbolOrigin.IN_CONTENT, key = "field:merchantId")
        val createdAt = symbol("createdAt", SymbolRole.FIELD, SymbolOrigin.IN_CONTENT, key = "field:createdAt")
        val plan = SnippetPlan("""@Index(columnList = "merchantId, createdAt")""", emptyList()).withLiteral(
            """"merchantId, createdAt"""",
            covers = arrayOf(covering("merchantId", merchantId), covering("createdAt", createdAt)),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("""@Index(columnList = "field1, field2")""", result.text)
    }

    /**
     * **Row three, and the reason `mixed-always` was rejected.** Rewriting the covered ranges and
     * passing the gaps through fixes the two rows above and leaks this one: *merchants* is a domain
     * word sitting in a gap. Any word-bearing gap sends the whole literal to `str`.
     */
    @Test
    fun `a word-bearing gap sends the whole literal to str`() {
        val plan = planOf(
            """@RequestMapping("/api/merchants/{id}") void find(String id) {}""",
            symbol("find", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT),
        ).withLiteral(
            """"/api/merchants/{id}"""",
            covers = arrayOf(covering("id", symbol("id", SymbolRole.PARAMETER, SymbolOrigin.IN_CONTENT, key = "param:id"))),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertTrue(result.text.startsWith("""@RequestMapping("str1")"""), result.text)
    }

    /**
     * **Row four**, and the case that rules out a preserve list keyed by resolved callee: this is an
     * argument to a library method, and a callee-keyed list would wave it straight through with a
     * project type and its fields in plain text.
     */
    @Test
    fun `a query naming a project type is replaced whole rather than partly rewritten`() {
        val plan = planOf(
            """entityManager.createQuery("SELECT p FROM com.acme.Payment p");""",
            symbol("entityManager", SymbolRole.PARAMETER, SymbolOrigin.LIBRARY),
            symbol("createQuery", SymbolRole.METHOD, SymbolOrigin.LIBRARY),
        ).withLiteral(
            """"SELECT p FROM com.acme.Payment p"""",
            covers = arrayOf(
                covering("com", pkg("com", SymbolOrigin.IN_CONTENT)),
                covering("acme", pkg("com.acme", SymbolOrigin.IN_CONTENT)),
                covering("Payment", symbol("Payment", SymbolRole.TYPE, SymbolOrigin.IN_CONTENT, key = "class:com.acme.Payment")),
            ),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("""entityManager.createQuery("str1");""", result.text)
    }

    /**
     * Ownership decides here as it decides everywhere else: a reference resolving to a JDK or
     * library symbol is preserved, so the literal comes out byte-identical.
     */
    @Test
    fun `a literal whose references resolve to the JDK is preserved`() {
        val plan = planOf(
            """Class.forName("java.util.List");""",
            symbol("Class", SymbolRole.TYPE, SymbolOrigin.JDK),
            symbol("forName", SymbolRole.METHOD, SymbolOrigin.JDK),
        ).withLiteral(
            """"java.util.List"""",
            covers = arrayOf(
                covering("java", pkg("java", SymbolOrigin.JDK)),
                covering("util", pkg("java.util", SymbolOrigin.JDK)),
                covering("List", symbol("List", SymbolRole.TYPE, SymbolOrigin.JDK, key = "class:java.util.List")),
            ),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("""Class.forName("java.util.List");""", result.text)
    }

    /**
     * **A reference that resolved to nothing covers nothing**, so the gap it sits in is word-bearing
     * and the literal fails closed. That is the same direction an unresolved identifier fails in,
     * reached without a special case: a name nobody can vouch for is treated as the user's own.
     */
    @Test
    fun `an unresolved reference fails the whole literal closed`() {
        val plan = SnippetPlan("""Class.forName("com.acme.Payment")""", emptyList()).withLiteral(
            """"com.acme.Payment"""",
            covers = arrayOf(
                covering("com", pkg("com", SymbolOrigin.IN_CONTENT)),
                covering("acme", pkg("com.acme", SymbolOrigin.IN_CONTENT)),
                coveringUnresolved("Payment"),
            ),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("""Class.forName("str1")""", result.text)
    }

    /**
     * **The rewrite is driven only by resolved references.** Two snippets whose literals share a
     * reference structure and share nothing else produce the same rewrite — which is the assertion
     * that the content is never read to decide what a literal becomes.
     */
    @Test
    fun `changing a literal's text without changing its references does not change the rewrite`() {
        fun planFor(literal: String, first: String, second: String) =
            SnippetPlan("""@Index(columnList = $literal)""", emptyList()).withLiteral(
                literal,
                covers = arrayOf(
                    covering(first, symbol(first, SymbolRole.FIELD, SymbolOrigin.IN_CONTENT, key = "field:one")),
                    covering(second, symbol(second, SymbolRole.FIELD, SymbolOrigin.IN_CONTENT, key = "field:two")),
                ),
            )

        val settled = anonymize(
            planFor(""""merchantId, createdAt"""", "merchantId", "createdAt"),
            AnonymizationSettings.DEFAULTS,
            LedgerSnapshot.EMPTY,
        )
        val disguised = anonymize(
            planFor(""""aaaaaaaaaa, bbbbbbbbb"""", "aaaaaaaaaa", "bbbbbbbbb"),
            AnonymizationSettings.DEFAULTS,
            LedgerSnapshot.EMPTY,
        )

        assertEquals("""@Index(columnList = "field1, field2")""", settled.text)
        assertEquals(settled.text, disguised.text)
    }

    /**
     * `""` carries no domain content by definition, and replacing it would be worse than useless:
     * `s.equals("")` becoming `s.equals("str1")` silently converts an empty-check into a
     * value-check — *plausible* rather than obvious, which is the prohibited class of artifact.
     * Testing for length zero is not inspecting content.
     */
    @Test
    fun `an empty literal is preserved`() {
        val plan = planOf(
            """if (reference.equals("")) {}""",
            symbol("reference", SymbolRole.LOCAL, SymbolOrigin.IN_CONTENT),
        ).withLiteral("""""""")

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("""if (local1.equals("")) {}""", result.text)
    }

    /**
     * **A number is shape, not domain**: `3`, `443` and `30_000` tell a reviewer about retry counts,
     * ports and timeouts, which is frequently the bug. Characters, booleans and `null` go with them.
     */
    @Test
    fun `numeric character boolean and null literals are preserved`() {
        val plan = planOf(
            """retry(3, 30_000, 'x', true, null);""",
            symbol("retry", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT),
        )
            .withLiteral("3", LiteralKind.NUMBER)
            .withLiteral("30_000", LiteralKind.NUMBER)
            .withLiteral("'x'", LiteralKind.CHARACTER)
            .withLiteral("true", LiteralKind.BOOLEAN)
            .withLiteral("null", LiteralKind.NULL)

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("""method1(3, 30_000, 'x', true, null);""", result.text)
    }

    /**
     * **A text block stays a text block.** Only the content between the delimiters is rewritten, so
     * the form survives without anything here knowing how a text block is spelled — `"""…"""`
     * collapsed to `"str1"` would read as a bug in this tool rather than as anonymization.
     */
    @Test
    fun `a text block stays a text block`() {
        val text = "String query = \"\"\"\n    SELECT * FROM merchants\n    \"\"\";"
        val plan = SnippetPlan(text, emptyList())
            .withLiteral("\"\"\"\n    SELECT * FROM merchants\n    \"\"\"", LiteralKind.TEXT_BLOCK)

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("String query = \"\"\"\nstr1\"\"\";", result.text)
    }

    /**
     * A symbol named inside a literal and named in code is one symbol, so it renders as one
     * placeholder — which is the whole content of *in lockstep with the symbols those references
     * name*. A rewrite that read the literal's text instead is exactly how the two fall out of sync.
     */
    @Test
    fun `a symbol named in a literal and in code renders as one placeholder`() {
        val merchantId = symbol("merchantId", SymbolRole.FIELD, SymbolOrigin.IN_CONTENT, key = "field:merchantId")
        // Placed rather than searched for: the declaration is the second `merchantId` in the text,
        // and the first is inside the literal, where the plan builder reports a reference and never
        // an identifier.
        val plan = planPlacing(
            """@Index(columnList = "merchantId") private String merchantId;""",
            at(1, merchantId),
        ).withLiteral(""""merchantId"""", covers = arrayOf(covering("merchantId", merchantId)))

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("""@Index(columnList = "field1") private String field1;""", result.text)
        result.assertShared("lockstep", "merchantId")
    }

    /**
     * A name that is only ever written inside a literal is still a name the output renamed, so it is
     * in the mapping — a placeholder in the output with no row in the mapping table is a reverse
     * mapping the reader cannot complete.
     */
    @Test
    fun `a name written only inside a literal is still in the mapping and the counts`() {
        val plan = SnippetPlan("""Class.forName("Payment")""", emptyList()).withLiteral(
            """"Payment"""",
            covers = arrayOf(covering("Payment", symbol("Payment", SymbolRole.TYPE, SymbolOrigin.IN_CONTENT, key = "class:Payment"))),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("""Class.forName("Type1")""", result.text)
        assertEquals(mapOf("Type1" to "Payment"), result.mapping)
        assertEquals(1, result.counts.replaced)
    }

    /**
     * The `str` namespace shares the one counter every other namespace shares, and a candidate
     * colliding with a name that survives into the output burns its number like any other. A reader
     * holding `str1` from an AI's reply has to be able to map it back to one thing.
     */
    @Test
    fun `a literal placeholder never collides with a name that survives into the output`() {
        val plan = planOf(
            """String str1 = describe("merchant");""",
            symbol("describe", SymbolRole.METHOD, SymbolOrigin.LIBRARY),
        ).withLiteral(""""merchant"""")

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("""String str1 = describe("str2");""", result.text)
    }

    /**
     * Name constraint is a property of the symbol rather than of the occurrence, and a reference
     * inside a literal is an occurrence like any other: a method whose override chain reaches a
     * library root keeps its name here too, or the literal and the declaration disagree.
     */
    @Test
    fun `a literal reference to a name-constrained symbol keeps its name`() {
        val run = symbol(
            "run",
            SymbolRole.METHOD,
            SymbolOrigin.IN_CONTENT,
            key = "method:class:com.acme.Job#run",
            overrideRoots = listOf(OverrideRoot("method:class:java.lang.Runnable#run", SymbolOrigin.JDK)),
        )
        val plan = planOf("""void run() { log("run"); }""", run).withLiteral(
            """"run"""",
            covers = arrayOf(covering("run", run)),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("""void run() { log("run"); }""", result.text)
    }
}
