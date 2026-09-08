package com.snippetveil.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **One symbol, one placeholder, two spellings** — the engine's half of it, against plan literals.
 *
 * > One symbol holds one placeholder. How that placeholder is *spelled* at a token is computed
 * > per-language, at splice time — `field1` in Kotlin, `getField1()` in Java. Rendering is
 * > language-dependent; the placeholder is not.
 *
 * That sentence is what makes a shared ledger genuinely shared rather than merely co-located, and
 * every assertion here is about the second half of it: the ledger entry never moves, and only the
 * characters spliced over the token do. So each test states **one** accessor symbol, writes it at
 * two tokens, and asserts what came out at each — because a test that used two symbols would be
 * asserting nothing about sharing.
 *
 * **This adds no rule.** The forced-sharing list stays closed at five, the fidelity notices closed
 * at two, and no new placeholder kind arrives; `KotlinAddsNoRuleTest` holds those three
 * structurally.
 */
class KotlinSpellingTest {

    /**
     * **A Kotlin token naming a Java `getBody()` de-prefixes, and the same symbol written as the
     * accessor's own name does not.**
     *
     * Kotlin presents a JavaBeans pair as a property, so `javaObj.body` is a property access spelled
     * with the **method's** symbol — and the key has to be the method's, or the two languages
     * disagree about a symbol they both see. What is left is a pure spelling function over the
     * placeholder the Java side already assigned.
     *
     * Both tokens in one snippet, because the claim is that they are one entry: `field1` and
     * `getField1` here are the *same* placeholder read two ways, and a fixture that wrote only one
     * of them could not tell that from two symbols that happened to agree.
     */
    @Test
    fun `a Kotlin token naming a Java accessor de-prefixes and the accessor's own name does not`() {
        val text = "println(javaObj.body)\nprintln(javaObj.getBody())"
        val plan = planWriting(text, inKotlin("body", GET_BODY), inKotlin("getBody", GET_BODY))

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("println(javaObj.field1)\nprintln(javaObj.getField1())", result.text, SPELLING)
        assertEquals(
            "field1",
            result.delta.placeholders[FIELD_KEY]?.placeholder,
            "the two tokens name one symbol, so one placeholder was handed out",
        )
    }

    /**
     * **The `is` accessor is the case the de-prefix must leave alone, and it needs no arm of its
     * own.** Kotlin spells the synthetic property of `isSettled()` as `isSettled`, so the token *is*
     * written as the accessor's own name and the ordinary rule splices it verbatim.
     *
     * Written down because it looks like an exception and is not: the test is *is the token the
     * accessor's name*, not *which of the three prefixes is this*.
     */
    @Test
    fun `an is-accessor's property spelling is the accessor's own name and is spliced verbatim`() {
        val settled = accessorSymbol("isSettled", "is", "settled")
        val plan = planWriting("if (javaObj.isSettled) {}", inKotlin("isSettled", settled))

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("if (javaObj.isField1) {}", result.text, SPELLING)
    }

    /**
     * **A setter written as a property assignment de-prefixes to the same field placeholder its
     * getter would.** One symbol, one placeholder — the prefix is what the *derivation* strips, and
     * which of the three it was is not something the Kotlin spelling has to know.
     */
    @Test
    fun `a Kotlin property assignment naming a Java setter de-prefixes`() {
        val plan = planWriting("javaObj.body = x", inKotlin("body", accessorSymbol("setBody", "set", "body")))

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("javaObj.field1 = x", result.text, SPELLING)
    }

    /**
     * **A fieldless getter renders `javaObj.method7`, and that is the accepted artifact rather than
     * a defect.**
     *
     * A property access spelled with a **method** placeholder is an **obvious** artifact — a reader
     * meets a name that says *method* where they wrote a property, and can see that they did — which
     * is the same trade already accepted for `String.format("str1", a, b)`. The prohibited class is
     * the *plausible* artifact, and there is none here.
     *
     * **Rejected, and not to be reopened:** minting a property placeholder for a fieldless getter.
     * It changes the shipped Java rendering, diverges by install age, and names a field the source
     * does not have. Rejected: rewriting the token to `javaObj.getMethod7()`, which is shape
     * rewriting rather than descriptive substitution.
     */
    @Test
    fun `a Kotlin token naming a fieldless Java getter splices the method placeholder verbatim`() {
        val fieldless = symbol(
            "getFieldless",
            SymbolRole.METHOD,
            SymbolOrigin.IN_CONTENT,
            key = "method:class:com.acme.JavaBean#getFieldless",
            keyIsQualified = true,
        )
        val plan = planWriting("println(javaObj.fieldless)", inKotlin("fieldless", fieldless))

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("println(javaObj.method1)", result.text, SPELLING)
    }

    /**
     * **An accessor placeholder that was not derived from its field's is spliced verbatim** — which
     * is the rule's second half stated over the case that produces it.
     *
     * Derivation is checked against the names surviving into the output, because injectivity
     * outranks coherence: `getField1` here is already a JDK method's name in the snippet, so the
     * accessor falls back to an ordinary allocation. De-prefixing *that* would put `field1` at the
     * token while the symbol's placeholder is `method2`, which is a reverse mapping that decodes to
     * the wrong symbol — the one failure this product may never produce.
     */
    @Test
    fun `an accessor placeholder that is not its field's derived name is spliced verbatim`() {
        val text = "println(javaObj.body)\nreflected.getField1()"
        val plan = planWriting(
            text,
            inKotlin("body", GET_BODY),
            inJava("getField1", symbol("getField1", SymbolRole.METHOD, SymbolOrigin.JDK, key = "jdk#getField1")),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertTrue(
            result.text.startsWith("println(javaObj.method"),
            "$SPELLING: a placeholder that is not the field's derived name may not de-prefix\n${result.text}",
        )
        assertEquals(emptySet<String>(), result.placeholdersFor("body"), "$SPELLING: nothing stands for the field")
    }

    /**
     * **The spelling reads the language and nothing else.** The same plan, the same symbol and the
     * same token text, tagged Java: the accessor's own placeholder is spliced, unchanged.
     *
     * The Java path renders byte-identically to what it rendered before this rule existed, and this
     * is the assertion that says so in one line rather than leaving it to the Java suite's absence
     * of a failure. A Java walk never writes `body` at a `getBody` symbol — Java has no syntax for
     * it — which is exactly why the fixture can: it isolates the language tag as the only difference
     * between this test and the first one.
     */
    @Test
    fun `the same token tagged Java splices the accessor placeholder unchanged`() {
        val text = "println(javaObj.body)\nprintln(javaObj.getBody())"
        val plan = planWriting(text, inJava("body", GET_BODY), inJava("getBody", GET_BODY))

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("println(javaObj.getField1)\nprintln(javaObj.getField1())", result.text, SPELLING)
    }

    /**
     * **The de-prefixed name is a row, and the row is the field's** — `field1` stands for `body`, not
     * for `getBody`.
     *
     * A row is a placeholder **and what it stands for**, so the table has to explain the word that is
     * actually in the output. Filing this under the accessor would leave `field1` unexplained in the
     * preview and put `getField1` in the table when no such word is in the text.
     *
     * The row is renamable, and against the **field's** key — which is the key a stem lands on for a
     * derived pair anyway; see [Renaming.DERIVED].
     */
    @Test
    fun `a de-prefixed token files the field's row and offers it for renaming`() {
        val plan = planWriting("println(javaObj.body)", inKotlin("body", GET_BODY))

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        val row = result.names.single()
        assertEquals("field1", row.placeholder)
        assertEquals("body", row.original)
        assertEquals(MappedKind.FIELD, row.kind)
        assertEquals(FIELD_KEY, row.key)
        assertEquals(Renaming.OFFERED, row.renaming)
        assertEquals(mapOf("field1" to "body"), result.mapping, "the table explains the word in the output")
    }

    /**
     * **Renaming the field renames the token**, because the token *is* the field's placeholder — the
     * same sentence [Renaming.DERIVED] puts on the accessor's row, reaching the Kotlin spelling for
     * free.
     */
    @Test
    fun `a stem on the field's key moves the de-prefixed token`() {
        val plan = planWriting("println(javaObj.body)", inKotlin("body", GET_BODY))
        val settings = AnonymizationSettings(renamedStems = mapOf(FIELD_KEY to "amount"))

        val result = anonymize(plan, settings, LedgerSnapshot.EMPTY)

        assertEquals("println(javaObj.amount1)", result.text, SPELLING)
    }

    /**
     * **The stated limit: an accessor whose field this invocation never placed splices verbatim.**
     *
     * A field's placeholder is allocated as a side effect of its accessor's, so it is present
     * whenever the accessor's was minted here. It is absent exactly when the accessor's placeholder
     * came back out of the ledger and the field's did not — an unqualified field key, which is never
     * written down. The spelling then has nothing to de-prefix *to*, and guessing one by taking the
     * prefix off the text would be a spelling convention standing in for the fact.
     *
     * Under-rendering rather than a wrong name, which is the direction everything here errs in: the
     * output says `getField1` at a property access, which is the fieldless getter's accepted
     * artifact again.
     */
    @Test
    fun `an accessor whose field has no placeholder splices verbatim`() {
        val plan = planWriting("println(javaObj.body)", inKotlin("body", GET_BODY))
        val ledger = LedgerSnapshot(
            placeholders = mapOf(GET_BODY.key to MintedName("getField1", "getBody")),
            nextNumber = 2,
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, ledger)

        assertEquals("println(javaObj.getField1)", result.text, SPELLING)
    }

    /**
     * **A Kotlin property and a Java call to its accessor, in one invocation: one ledger entry.**
     *
     * The Kotlin half writes the property's own token and the Java half writes `getBar()`; the plan
     * carries both because [SourceLanguage] is a tag on the occurrence rather than a property of the
     * plan, which is what lets one snippet hold tokens of more than one language.
     *
     * **This is rule 3's shape, not rule 5's.** A Java call site rendering `k.field1()` would read as
     * broken Java, and two independent placeholders would reproduce exactly the incoherence rule 3
     * was written to prevent.
     */
    @Test
    fun `a Kotlin property and a Java call to its accessor are one entry with two spellings`() {
        val text = "val bar: String\nk.getBar()"
        val property = symbol(
            "bar",
            SymbolRole.FIELD,
            SymbolOrigin.IN_CONTENT,
            key = "field:class:com.acme.Ledger#bar",
            keyIsQualified = true,
        )
        val getter = symbol(
            "getBar",
            SymbolRole.METHOD,
            SymbolOrigin.IN_CONTENT,
            key = "method:class:com.acme.Ledger#getBar",
            keyIsQualified = true,
            accessor = AccessorEvidence("field:class:com.acme.Ledger#bar", "bar", "get", fieldKeyIsQualified = true),
        )
        val plan = planWriting(text, inKotlin("bar", property), inJava("getBar", getter))

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("val field1: String\nk.getField1()", result.text, SPELLING)
        assertEquals(
            "field1",
            result.delta.placeholders["field:class:com.acme.Ledger#bar"]?.placeholder,
            "the property and the accessor's backing field are one entry carrying one number",
        )
    }
}

/** The accessor every test here writes at two tokens: `getBody()` over a field named `body`. */
private val GET_BODY = accessorSymbol("getBody", "get", "body")

private const val FIELD_KEY = "field:class:com.acme.JavaBean#body"

/** A JavaBeans accessor on `com.acme.JavaBean`, and the field it reads or writes. */
private fun accessorSymbol(name: String, prefix: String, fieldName: String) = symbol(
    name,
    SymbolRole.METHOD,
    SymbolOrigin.IN_CONTENT,
    key = "method:class:com.acme.JavaBean#$name",
    keyIsQualified = true,
    accessor = AccessorEvidence(
        "field:class:com.acme.JavaBean#$fieldName",
        fieldName,
        prefix,
        fieldKeyIsQualified = true,
    ),
)

private const val SPELLING = "One symbol, one placeholder, two spellings"
