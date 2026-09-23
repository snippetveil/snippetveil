package com.snippetveil.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **Three structured vocabularies, and the rule that they must not both accept.**
 *
 * JSON, YAML and XML carry the same tree and are still three vocabularies with three recognition
 * predicates. What is asserted here is that each reads its own plan end to end, that no two of them
 * accept one text, and that an input two of them *did* accept would refuse rather than be read under
 * one of the two.
 */
class PlanFormatTest {

    /** **Each structured format anonymizes end to end**, over the same plan the text format reads. */
    @Test
    fun `a JSON, a YAML and an XML plan each anonymize end to end`() {
        for ((format, plan) in STRUCTURED_PLANS) {
            val text = anonymizedText(plan)

            for (name in listOf("visits", "invoices", "billing", "visits_pkey", "visit_id", "status")) {
                assertFalse(
                    Regex("""\b${Regex.escape(name)}\b""").containsMatchIn(text),
                    "the $format reading left `$name` on the clipboard:\n$text",
                )
            }
            for (measurement in listOf("16.97", "0.123", "0.456", "68")) {
                assertTrue(measurement in text, "the $format reading moved `$measurement`:\n$text")
            }
            assertTrue("Nested Loop" in text, "the $format reading did not preserve a node type:\n$text")
            assertTrue(
                Regex("""\btable\d+\b""").containsMatchIn(text),
                "the $format reading replaced no relation at all:\n$text",
            )
        }
    }

    /**
     * **The predicates are mutually exclusive, asserted pairwise** — over the four printings of one
     * plan, so that the claim is about the formats rather than about four unrelated texts.
     */
    @Test
    fun `no two recognition predicates accept one plan`() {
        val plans = STRUCTURED_PLANS + ("text" to TEXT_PLAN)

        for ((printed, plan) in plans) {
            val accepting = POSTGRES_FORMATS.filter { it.recognises(plan) }.map { it.name }

            assertEquals(listOf(printed), accepting, "the $printed plan was accepted by $accepting")
        }
    }

    /**
     * **A JSON plan is not a YAML document**, stated on its own because it is the pair a reader
     * assumes is safe: the two carry the same tree, and a predicate that leaned on the tree rather
     * than on the spelling would accept both.
     */
    @Test
    fun `a JSON plan is not accepted by the YAML predicate`() {
        val yaml = POSTGRES_FORMATS.single { it.name == "yaml" }
        val json = POSTGRES_FORMATS.single { it.name == "json" }

        assertFalse(yaml.recognises(JSON_PLAN), "the YAML predicate accepted a JSON plan")
        assertFalse(json.recognises(YAML_PLAN), "the JSON predicate accepted a YAML plan")
    }

    /**
     * **An input matching more than one vocabulary refuses rather than being read under one of the
     * two.**
     *
     * It is asserted over a list built for it rather than over the real four, and that is the point:
     * the real predicates are exclusive, so the only way to state what happens when two accept is to
     * make two accept. A rule that could only be checked by inspecting the predicates is one that
     * stops being checked the day a fifth format makes the inspection wrong.
     */
    @Test
    fun `an input two vocabularies accept refuses rather than being read under one`() {
        val both = POSTGRES_FORMATS.filter { it.name == "json" } +
            PlanFormat("json-again", { it.trimStart().startsWith("[") }) { emptyList() }

        assertTrue(
            readingOf(JSON_PLAN, both) is PlanReading.Unreadable,
            "a text two vocabularies accepted was read under one of them",
        )
        assertTrue(
            readingOf(JSON_PLAN, both.take(1)) is PlanReading.Read,
            "the same text was not read when only one vocabulary accepted, so this asserts nothing",
        )
    }

    /** A document that opens like a plan and does not parse whole is not a plan. */
    @Test
    fun `a structured document that does not parse refuses`() {
        for (broken in listOf("[{\"Plan\": {", "- Plan\n", "<explain><Query>")) {
            assertTrue(
                parsePlan(broken) is PlanReading.Unreadable,
                "this was read as a plan, and does not parse:\n$broken",
            )
        }
    }

    /**
     * **A name the plan declared is one symbol across the whole document**, wherever the format
     * printed the declaration relative to its uses.
     *
     * The structured formats print an alias in an `Alias` field and use it inside the expressions of
     * a *sibling* node, which is the same ordering problem the text format's CTE header has: a reader
     * that keyed each name where it met it would hand out two placeholders for one alias.
     */
    @Test
    fun `an alias and its uses in a sibling node's expression are one name`() {
        for ((format, plan) in STRUCTURED_PLANS) {
            val result = anonymize(planIn(plan), AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

            assertEquals(
                setOf("v"),
                result.mapping.values.filter { it == "v" }.toSet(),
                "the $format reading did not report the alias at all",
            )
            result.assertShared("an alias is one symbol wherever it is written ($format)", "v")
        }
    }
}
