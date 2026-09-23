package com.snippetveil.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **The two MySQL formats this product reads** — the version 2 tree whose node lines are
 * cross-checked against templates, and the version 1 query block whose unescaped fields are admitted
 * only for values the engine itself writes.
 *
 * The refusals are `MysqlRefusalTest`'s. What is asserted here is the reading: that each format
 * anonymizes end to end, that the node line is placed rather than scanned, that a reference is
 * counted rather than shrugged at, and that an off-list value in an unescaped field **refuses**
 * instead of being masked.
 */
class MysqlPlanTest {

    /**
     * **A version 2 plan anonymizes end to end** — every name replaced, every measurement exactly as
     * MySQL printed it.
     *
     * The measurements are half the assertion rather than a bonus: a plan is pasted *for* its costs
     * and row estimates, and a reader that moved one would have destroyed the reason it was sent.
     */
    @Test
    fun `a version 2 plan anonymizes end to end`() {
        val text = anonymizedText(MYSQL_V2_PLAN)

        for (name in MYSQL_NAMES) {
            assertFalse(
                Regex("""\b${Regex.escape(name)}\b""").containsMatchIn(text),
                "the version 2 reading left `$name` on the clipboard:\n$text",
            )
        }
        for (measurement in listOf("0.85", "0.70", "2.0")) {
            assertTrue(measurement in text, "the version 2 reading moved `$measurement`:\n$text")
        }
        assertTrue(
            Regex("""\btable\d+\b""").containsMatchIn(text),
            "the version 2 reading replaced no relation at all:\n$text",
        )
        assertTrue("\"access_type\": \"index\"" in text, "an engine fact did not survive its shape:\n$text")
    }

    /**
     * **The rendered node line carries the same placeholders its own fields do**, because the line is
     * rebuilt from those fields rather than read.
     *
     * This is the claim that makes the format admissible at all. The line is the one place in the
     * document where a name is written with nothing around it, and it comes out anonymized **because
     * the template said where the name was**, not because anything recognised it there.
     */
    @Test
    fun `a node line comes out carrying the placeholders of the fields it was assembled from`() {
        val text = anonymizedText(MYSQL_V2_PLAN)
        val index = Regex(""""index_name": "(idx\d+)"""").find(text)?.groupValues?.get(1)
        val table = Regex(""""table_name": "(table\d+)"""").find(text)?.groupValues?.get(1)

        assertNotNull(index, "the index name was not replaced at all:\n$text")
        assertNotNull(table, "the relation was not replaced at all:\n$text")
        assertTrue(
            "\"operation\": \"Index lookup on $table using $index (" in text,
            "the node line does not carry its own fields' placeholders:\n$text",
        )
    }

    /**
     * **A node line that does not match a template instantiated from its own fields refuses.**
     *
     * The fixture's line is left alone and its `table_name` is changed, so the two no longer agree.
     * Nothing about the line is malformed — it still reads as perfectly good MySQL prose, which is
     * exactly the point: **the line is checked against the node, and a line the node cannot have
     * produced is not one this product will place.**
     */
    @Test
    fun `a node line that its own fields cannot have produced refuses`() {
        val forged = MYSQL_V2_PLAN.replace("\"table_name\": \"v\"", "\"table_name\": \"other\"")

        assertTrue(parsePlan(MYSQL_V2_PLAN) is PlanReading.Read, "the fixture does not read, so this asserts nothing")
        assertEquals(PlanReading.Unreadable, parsePlan(forged), "a line its node cannot have produced was read")
    }

    /**
     * **A sentence no template produces refuses**, rather than being read as prose.
     *
     * It is the template set's own closure, and it runs the way the field closure runs: a rendering a
     * release invented refuses the plan carrying it until a capture and a row follow. The alternative
     * is a reader that decides, out of an English sentence it has never seen, which of its words
     * belong to the user.
     */
    @Test
    fun `a node line matching no template at all refuses`() {
        val invented = MYSQL_V2_PLAN.replace(
            "\"operation\": \"Sort: shop.v.created_at\"",
            "\"operation\": \"Cranberry sort on shop.v.created_at\"",
        )

        assertEquals(PlanReading.Unreadable, parsePlan(invented))
    }

    /**
     * **The node line is never scanned for something name-shaped**, asserted with a relation named
     * `on` — the engine's own keyword in the very template that names it.
     *
     * A scanner would replace both spellings and hand back `Table scan table1 table1`, destroying the
     * sentence; a scanner that matched the keyword first would walk past the relation and leave it on
     * the clipboard. The template does neither, because it never looks at the line's words at all: it
     * knows the relation is the run of characters after `Table scan on ` **because it put it there**.
     */
    @Test
    fun `a relation spelled like the keyword beside it is placed rather than found`() {
        val plan = """
            |{
            |  "query": "select * from `shop`.`on`",
            |  "inputs": [
            |    {
            |      "operation": "Table scan on on",
            |      "access_type": "table",
            |      "table_name": "on",
            |      "schema_name": "shop",
            |      "estimated_rows": 1.0,
            |      "estimated_total_cost": 0.35
            |    }
            |  ]
            |}
        """.trimMargin()

        val text = anonymizedText(plan)
        val table = Regex(""""table_name": "(table\d+)"""").find(text)?.groupValues?.get(1)

        assertNotNull(table, "the relation was not replaced at all:\n$text")
        assertTrue(
            "\"operation\": \"Table scan on $table\"" in text,
            "the keyword and the relation were not told apart by position:\n$text",
        )
    }

    /**
     * **A dotted reference is split by position and every part keyed as what that position is** — the
     * schema, the relation it displays and the column.
     *
     * The schema is the assertion that matters: the same spelling appears in the node's own
     * `schema_name` field, and the two share one placeholder only because both went through the same
     * key. A reference read as one opaque name would have handed out two.
     */
    @Test
    fun `a dotted reference anonymizes by position and shares the schema's placeholder`() {
        val kinds = namesIn(MYSQL_V2_PLAN)
        val text = anonymizedText(MYSQL_V2_PLAN)
        val schema = Regex(""""schema_name": "(schema\d+)"""").find(text)?.groupValues?.get(1)

        assertEquals(SymbolRole.SCHEMA, kinds["shop"], "the reference's first part is not a schema")
        assertEquals(SymbolRole.COLUMN, kinds["created_at"], "the reference's last part is not a column")
        assertNotNull(schema, "the schema field was not replaced at all:\n$text")
        assertTrue(
            "\"sort_key\": \"$schema." in text,
            "the schema on the reference and the schema in its own field are two names:\n$text",
        )
    }

    /**
     * **A reference whose part count is not the one the inventory named refuses** — and the case it
     * guards against is an alias with a `.` in it.
     *
     * Nothing is malformed here either. `shop.v.b.created_at` is what MySQL prints for a table
     * displayed as `v.b`, and every part of it is a perfectly good identifier. A tolerant reader would
     * take the last part for the column and the one before it for the relation, and would then report
     * somebody's alias as a schema — confidently, and with no way for anyone to notice.
     */
    @Test
    fun `a reference whose alias carries a dot gives the wrong part count and refuses`() {
        val dotted = MYSQL_V2_PLAN
            .replace("\"sort_key\": \"shop.v.created_at\"", "\"sort_key\": \"shop.v.b.created_at\"")
            .replace("\"operation\": \"Sort: shop.v.created_at\"", "\"operation\": \"Sort: shop.v.b.created_at\"")

        assertEquals(PlanReading.Unreadable, parsePlan(dotted))
    }

    /** A reference with one part too **few** is refused by the same count, and not by a special case. */
    @Test
    fun `a reference with too few parts refuses`() {
        val short = MYSQL_V2_PLAN
            .replace("\"sort_key\": \"shop.v.created_at\"", "\"sort_key\": \"v.created_at\"")
            .replace("\"operation\": \"Sort: shop.v.created_at\"", "\"operation\": \"Sort: v.created_at\"")

        assertEquals(PlanReading.Unreadable, parsePlan(short))
    }

    /**
     * **A version 1 plan anonymizes end to end**, and its unescaped fields come out exactly as MySQL
     * wrote them.
     */
    @Test
    fun `a version 1 plan anonymizes end to end`() {
        val text = anonymizedText(MYSQL_V1_PLAN)

        for (name in MYSQL_NAMES) {
            assertFalse(
                Regex("""\b${Regex.escape(name)}\b""").containsMatchIn(text),
                "the version 1 reading left `$name` on the clipboard:\n$text",
            )
        }
        for (measurement in listOf("0.85", "0.60", "100.00")) {
            assertTrue(measurement in text, "the version 1 reading moved `$measurement`:\n$text")
        }
        assertTrue("\"access_type\": \"ref\"" in text, "an unescaped enum did not survive:\n$text")
        assertTrue("\"key_length\": \"4,8\"" in text, "an unescaped key length did not survive:\n$text")
        assertTrue(
            Regex(""""attached_condition": "str\d+"""").containsMatchIn(text),
            "a rendered condition was not masked whole:\n$text",
        )
    }

    /**
     * **An off-list value in an unescaped field refuses rather than being masked** — asserted as a
     * pair against a field that *is* masked on a surprise, because the two opposite answers are the
     * whole of the rule.
     *
     * `message` is fed entirely by fixed strings in the engine and written into the document with no
     * escaping, so a value the engine cannot have produced is not merely unrecognised: it may have
     * ended its own slot and written the siblings printed after it. **A mask replaces a range; it
     * cannot un-invent a neighbour**, so there is nothing safe to keep and nothing is kept.
     */
    @Test
    fun `an off-list message refuses where an off-shape engine fact is masked`() {
        val onList = MYSQL_V1_PLAN.replace(
            "\"using_filesort\": true",
            "\"message\": \"Impossible WHERE\"",
        )
        val offList = MYSQL_V1_PLAN.replace(
            "\"using_filesort\": true",
            "\"message\": \"cost 0.85 for customer acme\"",
        )
        val offShape = MYSQL_V1_PLAN.replace("\"using_filesort\": true", "\"using_filesort\": \"perhaps\"")

        assertTrue(parsePlan(onList) is PlanReading.Read, "a message the engine writes was refused")
        assertEquals(PlanReading.Unreadable, parsePlan(offList), "an off-list message was not refused")
        assertTrue(parsePlan(offShape) is PlanReading.Read, "an off-shape engine fact was refused rather than masked")
        assertTrue(
            Regex(""""using_filesort": "str\d+"""").containsMatchIn(anonymizedText(offShape)),
            "an off-shape engine fact was not masked",
        )
    }

    /** **An off-list access type and an off-shape key length refuse too**, by the same row. */
    @Test
    fun `the other two unescaped fields refuse an off-list value`() {
        val accessType = MYSQL_V1_PLAN.replace("\"access_type\": \"ref\"", "\"access_type\": \"acme_scan\"")
        val keyLength = MYSQL_V1_PLAN.replace("\"key_length\": \"4,8\"", "\"key_length\": \"4 bytes\"")

        assertEquals(PlanReading.Unreadable, parsePlan(accessType))
        assertEquals(PlanReading.Unreadable, parsePlan(keyLength))
    }

    /**
     * **A column name with a `.` in it refuses**, by the same counting rule a qualified reference is
     * read under — one part expected, two found.
     */
    @Test
    fun `a used column spelled with a dot refuses`() {
        val dotted = MYSQL_V1_PLAN.replace("\"created_at\"", "\"created.at\"")

        assertEquals(PlanReading.Unreadable, parsePlan(dotted))
    }

    /**
     * **A field neither MySQL inventory holds refuses the plan carrying it**, which is the field
     * closure reaching this engine exactly as it reaches PostgreSQL's.
     */
    @Test
    fun `a field no MySQL row covers refuses`() {
        val v2 = MYSQL_V2_PLAN.replace("\"access_type\": \"sort\"", "\"cranberry_count\": 4")
        val v1 = MYSQL_V1_PLAN.replace("\"using_filesort\": true", "\"cranberry_count\": 4")

        assertEquals(PlanReading.Unreadable, parsePlan(v2))
        assertEquals(PlanReading.Unreadable, parsePlan(v1))
    }

    /**
     * **The two JSON formats are two vocabularies**, and neither predicate accepts the other's
     * document — asserted over the two printings of one plan rather than over two unrelated texts.
     */
    @Test
    fun `neither MySQL JSON predicate accepts the other's document`() {
        assertTrue(opensMysqlJsonV2(MYSQL_V2_PLAN), "the version 2 predicate does not accept a version 2 plan")
        assertTrue(opensMysqlJsonV1(MYSQL_V1_PLAN), "the version 1 predicate does not accept a version 1 plan")
        assertFalse(opensMysqlJsonV1(MYSQL_V2_PLAN), "the version 1 predicate accepted a version 2 plan")
        assertFalse(opensMysqlJsonV2(MYSQL_V1_PLAN), "the version 2 predicate accepted a version 1 plan")
    }

    /**
     * **A MySQL plan pasted from a Windows terminal reads like any other**, and its line endings come
     * back untouched — the same claim the PostgreSQL formats are held to.
     */
    @Test
    fun `a MySQL plan whose lines end in a carriage return reads, and keeps them`() {
        for ((format, plan) in mapOf("v2" to MYSQL_V2_PLAN, "v1" to MYSQL_V1_PLAN)) {
            val windows = plan.replace("\n", "\r\n")
            val text = anonymizedText(windows)

            assertEquals(
                windows.count { it == '\r' },
                text.count { it == '\r' },
                "the $format reading moved a line ending:\n$text",
            )
            assertFalse(
                Regex("""\bvisits_by_owner\b""").containsMatchIn(text),
                "the $format reading of a Windows paste left an index on the clipboard:\n$text",
            )
        }
    }
}
