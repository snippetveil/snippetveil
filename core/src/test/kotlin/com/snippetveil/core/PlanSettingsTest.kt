package com.snippetveil.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **`Settings` is typed by key** — the one field in the inventory whose *keys* carry a rule, and the
 * one whose key set is open where every other one is closed.
 *
 * The rejected alternative is worth keeping in view while reading these: keep every key and mask
 * every value. It needs no vocabulary row and carries no exposure, and it throws away the settings
 * that actually explain a plan choice — which is the answer the plan was pasted for.
 */
class PlanSettingsTest {

    /**
     * **A core setting this release flags is preserved behind its shape check; a setting flagged
     * after it is masked.**
     *
     * The flagged set is a per-version vocabulary row, so a newly flagged setting is a compatibility
     * event and not a disclosure decision — it is masked until the row follows, exactly as an
     * off-shape engine fact is. The pair is asserted together because the claim is a difference.
     */
    @Test
    fun `a flagged setting is preserved and a newly flagged one is masked`() {
        val text = anonymizedText(
            planWithSettings("work_mem" to "8MB", "enable_distinct_reordering" to "off"),
        )

        assertTrue("\"work_mem\": \"8MB\"" in text, "a flagged setting did not survive as printed:\n$text")
        assertFalse("enable_distinct_reordering" in text, "a setting no row covers was preserved:\n$text")
        assertTrue(
            Regex(""""str\d+": "str\d+"""").containsMatchIn(text),
            "a setting no row covers was not masked, key and value:\n$text",
        )
    }

    /** **An off-shape value of a flagged setting is masked**, as an engine fact anywhere else is. */
    @Test
    fun `a flagged setting whose value stopped looking like one is masked`() {
        val text = anonymizedText(planWithSettings("work_mem" to "8MB, and something else"))

        assertFalse("something else" in text, "an off-shape setting value was preserved:\n$text")
        assertTrue(Regex(""""work_mem": "str\d+"""").containsMatchIn(text), "the value was not masked:\n$text")
    }

    /**
     * **A `search_path` element shares its placeholder with the same schema named in a qualified
     * relation.**
     *
     * That sharing is the whole reason the path is read as names rather than masked whole: a plan
     * that says `Schema: billing` and a path that says `billing` are talking about one thing, and a
     * reader who saw `schema1` in one place and `str4` in the other would have lost the fact that the
     * relation was found *because* of the path.
     */
    @Test
    fun `a schema on the search_path and the same schema on a relation are one name`() {
        for ((format, plan) in STRUCTURED_PLANS) {
            val result = anonymize(planIn(plan), AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

            result.assertShared("a schema is one symbol wherever it is written ($format)", "billing")
            assertEquals(
                2,
                Regex("""\b${result.placeholdersFor("billing").single()}\b""").findAll(result.text).count(),
                "the $format reading did not write the schema's placeholder in both places:\n${result.text}",
            )
        }
    }

    /**
     * **`"$user"` survives verbatim, and the reason is the value grammar rather than a list of known
     * spellings.**
     *
     * PostgreSQL always substitutes the session user for a `search_path` element written **quoted**
     * as `"$user"`, so no schema can be named onto the path by that spelling and there is nothing of
     * the user's in it. The spelling **unquoted** is not that element at all — it is not a legal bare
     * identifier — and it does not survive. A rule that matched a list of spellings would keep both;
     * this keeps exactly the one the grammar says is the engine's.
     */
    @Test
    fun `the session-user element survives quoted and does not survive bare`() {
        val quoted = anonymizedText(planWithSettings("search_path" to """billing, \"${'$'}user\", public"""))
        val bare = anonymizedText(planWithSettings("search_path" to "billing, ${'$'}user, public"))

        assertTrue("""\"${'$'}user\"""" in quoted, "the session-user element did not survive verbatim:\n$quoted")
        assertFalse("${'$'}user" in bare, "a bare spelling of it survived, so the rule reads a list:\n$bare")
        assertTrue(
            Regex(""""search_path": "str\d+"""").containsMatchIn(bare),
            "a path that does not split cleanly was not masked whole:\n$bare",
        )
    }

    /** **A `search_path` that does not split cleanly is masked**, whole, rather than read in pieces. */
    @Test
    fun `a search_path that does not split cleanly is masked whole`() {
        val text = anonymizedText(planWithSettings("search_path" to """billing, \"unclosed, public"""))

        assertFalse("billing" in text, "an element of an unsplittable path reached the clipboard:\n$text")
        assertTrue(Regex(""""search_path": "str\d+"""").containsMatchIn(text), "it was not masked whole:\n$text")
    }

    /**
     * **An unknown settings key has both key and value masked, and the masked key is a legal XML
     * element name and a legal YAML key.**
     *
     * The strongest way to say that is to say it in the format's own terms: the anonymized document
     * still reads as the document it arrived as. A placeholder that was not a legal name there would
     * have turned a plan into something the format's own reader cannot parse, which is a failure the
     * user meets after they have pasted it somewhere.
     */
    @Test
    fun `a masked settings key leaves the document readable in its own format`() {
        val yaml = anonymizedText(YAML_PLAN)
        val xml = anonymizedText(XML_PLAN)

        assertFalse("acme.audit_level" in yaml, "an unknown settings key survived in YAML:\n$yaml")
        assertFalse("acme.audit_level" in xml, "an unknown settings key survived in XML:\n$xml")
        assertNotNull(yamlQueriesIn(yaml), "the anonymized YAML plan no longer reads as YAML:\n$yaml")
        assertNotNull(xmlQueriesIn(xml), "the anonymized XML plan no longer reads as XML:\n$xml")

        val placeholder = Regex("""<(str\d+)>""").find(xml)?.groupValues?.get(1)
        assertNotNull(placeholder, "no masked key reached the XML output, so this asserts nothing:\n$xml")
        assertTrue(
            Regex("""[A-Za-z_][A-Za-z0-9_.-]*""").matches(placeholder!!),
            "the masked key is not a legal XML element name: $placeholder",
        )
    }

    /** **The text format's `Settings:` row stays refused**, which is a vocabulary fact rather than a choice. */
    @Test
    fun `the text format's settings row is refused rather than typed`() {
        val reading = parsePlan(
            "Seq Scan on visits  (cost=0.00..1.00 rows=1 width=4)\nSettings: work_mem = '8MB'",
        )

        assertTrue(reading is PlanReading.Refused, "the text settings row was read rather than refused")
        assertEquals(PlanRecourse.POSTGRES_FORMAT_JSON, (reading as PlanReading.Refused).form.recourse)
    }
}

/**
 * A JSON plan naming one schema-qualified relation and carrying [settings], so a test states the
 * settings it is about and nothing else.
 *
 * The values are written exactly as they would appear **inside the JSON string**, escapes included —
 * which is what the `search_path` rule has to read, and what a fixture that decoded them first would
 * have hidden.
 */
private fun planWithSettings(vararg settings: Pair<String, String>): String {
    val rows = settings.joinToString(",\n      ") { (key, value) -> """"$key": "$value"""" }
    return """
        |[
        |  {
        |    "Plan": {
        |      "Node Type": "Seq Scan",
        |      "Relation Name": "invoices",
        |      "Schema": "billing",
        |      "Startup Cost": 0.00,
        |      "Total Cost": 1.00,
        |      "Plan Rows": 1,
        |      "Plan Width": 4
        |    },
        |    "Settings": {
        |      $rows
        |    }
        |  }
        |]
    """.trimMargin()
}
