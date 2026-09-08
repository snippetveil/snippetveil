package com.snippetveil.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * **Nothing about Kotlin adds a rule** — held structurally, where the codebase can hold it.
 *
 * Kotlin supplies new *instances* of rules the engine already had, and two of the five forced-sharing
 * rules are restated so that they say in a second language what they always meant. A change that
 * finds itself proposing a sixth rule, a third notice or a new placeholder kind has found a defect
 * rather than a design need — so the three closed lists are asserted here rather than left to review
 * alone.
 *
 * The list of five is the one this file cannot reach: which distinct symbols collapse onto one
 * placeholder is stated as five named assertions in `ForcedSharingTest`, and a count of them would be
 * a count of test methods. What *is* reachable is the sharper claim underneath it — see
 * [the language tag allocates nothing and shares nothing] — that the language a token is written in
 * changes the characters spliced over it and no other thing the engine produces. A sixth sharing rule
 * arriving through the Kotlin path would have to move a key or a number, and would fail there.
 */
class KotlinAddsNoRuleTest {

    /**
     * **The language tag changes the output text and nothing else.**
     *
     * The same plan, tagged Java and tagged Kotlin: two different strings out — that is the rule
     * working — and one identical ledger delta, one identical counter, one identical set of stems.
     * *Rendering is language-dependent; the placeholder is not*, asserted as the sentence is written.
     *
     * This is what closes the forced-sharing list from below. Every word the Kotlin spelling splices
     * is a placeholder the existing rules already handed out — the symbol's own, or its accessor's
     * backing field's — so the language tag cannot mint, cannot burn a number and cannot make two
     * symbols share. A sixth rule reached through it would show up here as a delta that differs.
     */
    @Test
    fun `the language tag allocates nothing and shares nothing`() {
        val text = "println(javaObj.body)"
        val accessor = symbol(
            "getBody",
            SymbolRole.METHOD,
            SymbolOrigin.IN_CONTENT,
            key = "method:class:com.acme.JavaBean#getBody",
            keyIsQualified = true,
            accessor = AccessorEvidence(
                "field:class:com.acme.JavaBean#body",
                "body",
                "get",
                fieldKeyIsQualified = true,
            ),
        )

        val java = anonymize(
            planWriting(text, inJava("body", accessor)),
            AnonymizationSettings.DEFAULTS,
            LedgerSnapshot.EMPTY,
        )
        val kotlin = anonymize(
            planWriting(text, inKotlin("body", accessor)),
            AnonymizationSettings.DEFAULTS,
            LedgerSnapshot.EMPTY,
        )

        assertEquals("println(javaObj.getField1)", java.text, "the Java spelling")
        assertEquals("println(javaObj.field1)", kotlin.text, "the Kotlin spelling")

        assertEquals(
            java.delta.placeholders.mapValues { it.value.placeholder + "/" + it.value.original },
            kotlin.delta.placeholders.mapValues { it.value.placeholder + "/" + it.value.original },
            "the two spellings wrote different characters and the same ledger entries",
        )
        assertEquals(java.delta.nextNumber, kotlin.delta.nextNumber, "the two spellings burnt the same numbers")
        assertEquals(java.delta.mintedStems, kotlin.delta.mintedStems, "the two spellings minted the same stems")
        assertEquals(java.counts.replaced, kotlin.counts.replaced, "the two spellings replaced the same symbols")
    }

    /**
     * **No new placeholder kind arrives.** A Kotlin property takes `field1`, a file facade `Type1`,
     * a type alias the ordinary type kind — every one of them a namespace this engine already had.
     *
     * Asserted as the whole table rather than as an absence, because *no new kind* is a claim about
     * the list and a test that checked only for kinds it knew about would pass on a list that grew.
     * The prefixes are here too: they are what a reader maps an AI's reply back with, and a namespace
     * that changed spelling would decode nothing while looking untouched.
     */
    @Test
    fun `the placeholder kinds and their prefixes are unchanged`() {
        assertEquals(
            listOf(
                "TYPE/Type",
                "TYPE_PARAMETER/T",
                "METHOD/method",
                "FIELD/field",
                "PARAMETER/param",
                "PACKAGE/pkg",
                "ANNOTATION/Anno",
                "ATTRIBUTE/attr",
                "LOCAL/local",
                "LABEL/label",
            ),
            SymbolRole.entries.map { it.name + "/" + it.placeholderPrefix },
            "a placeholder kind arrived or changed spelling; Kotlin was to add none",
        )

        assertEquals(
            listOf(
                "TYPE", "METHOD", "FIELD", "PARAMETER", "LOCAL", "PACKAGE",
                "TYPE_PARAMETER", "LABEL", "ANNOTATION", "ATTRIBUTE", "LITERAL", "UNKNOWN",
            ),
            MappedKind.entries.map { it.name },
            "a mapping-table kind arrived; Kotlin was to add none",
        )
    }

    /**
     * **The fidelity notices are still two**, asserted over an invocation that fires both.
     *
     * A third would have to clear the trigger test — *a notice exists iff the loss is invisible in
     * the anonymized output* — and the two Kotlin candidates fail it in opposite directions. A notice
     * for silent members would fire on nearly every Kotlin snippet, which is the definition of
     * wallpaper; and there is no loss to disclose in any case, because a name the language fixed was
     * never going to be replaced, so preserving it conceals nothing.
     */
    @Test
    fun `an invocation that loses everything it can still says exactly two things`() {
        val text = "// why\nString merchantRef = this.merchantRef;"
        val plan = planPlacing(
            text,
            at(0, symbol("merchantRef", SymbolRole.LOCAL, SymbolOrigin.IN_CONTENT, key = "local:1")),
            at(1, symbol("merchantRef", SymbolRole.FIELD, SymbolOrigin.IN_CONTENT, key = "field:class:com.acme.Payment#f")),
        ).withComment("// why", CommentVerdict.PROSE)

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals(2, result.fidelityNotices().size, "the notices are a closed list of two")
    }
}
