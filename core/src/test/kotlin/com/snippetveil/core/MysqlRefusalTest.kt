package com.snippetveil.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **The shapes MySQL and MariaDB print that this product refuses** — and the rule that a refusal must
 * be *about the engine the user is actually running*.
 *
 * A recogniser here never decides what is emitted; the clipboard is untouched whichever way a refusal
 * goes. What it decides is what this product **says**, and a MariaDB paste told to run
 * `EXPLAIN FORMAT=JSON` would be a false statement about somebody else's engine. So the predicates
 * are asserted the way the parser is: each shape produces its own verdict, and a shape the two
 * engines print identically produces neither engine's.
 */
class MysqlRefusalTest {

    /**
     * **`TREE` and `EXPLAIN ANALYZE` refuse with the `FORMAT=JSON` recourse.**
     *
     * Both are the same rendering with different numbers in it, and both append aliases and index
     * names raw — so a name a user chose can forge a line that reads as well-formed, and nothing can
     * recover the boundary afterwards.
     */
    @Test
    fun `the TREE format and EXPLAIN ANALYZE refuse with the FORMAT=JSON recourse`() {
        for ((printed, plan) in mapOf("TREE" to MYSQL_TREE_PLAN, "EXPLAIN ANALYZE" to MYSQL_ANALYZE_PLAN)) {
            val reading = parsePlan(plan)

            assertTrue(reading is PlanReading.Refused, "$printed was not refused as a recognised shape")
            assertEquals(PlanRefusedForm.MYSQL_TREE, (reading as PlanReading.Refused).form, "for $printed")
            assertEquals(PlanRecourse.MYSQL_FORMAT_JSON, reading.form.recourse, "for $printed")
        }
    }

    /**
     * **The default `EXPLAIN` refuses**, with the tabular family's own form and the same recourse.
     *
     * This is the product's least comfortable fact and it is asserted plainly rather than left to
     * fall out of something else: **a MySQL user who pastes what they get without asking is refused.**
     * The tabular form is a row of its own precisely so that the sentence they are shown can describe
     * the thing they actually pasted.
     */
    @Test
    fun `the default EXPLAIN refuses with the tabular form and the FORMAT=JSON recourse`() {
        val reading = parsePlan(MYSQL_TABULAR_PLAN)

        assertTrue(reading is PlanReading.Refused, "the default EXPLAIN was not refused as a recognised shape")
        assertEquals(PlanRefusedForm.MYSQL_TABULAR, (reading as PlanReading.Refused).form)
        assertEquals(PlanRecourse.MYSQL_FORMAT_JSON, reading.form.recourse)
        assertNotEquals(
            PlanRefusedForm.MYSQL_TREE,
            reading.form,
            "the tabular output and the TREE output are one form, so one message covers two situations",
        )
    }

    /**
     * **Every MariaDB form refuses, and none of them names a recourse.**
     *
     * The absence is the assertion. MariaDB's JSON writer does not escape the strings it writes, so
     * there is no output of that engine's this product could point at — and pointing at MySQL's would
     * be advice that does not work, given to somebody running neither.
     */
    @Test
    fun `every MariaDB form refuses with no recourse at all`() {
        val forms = mapOf(
            "JSON" to MARIADB_JSON_PLAN,
            "EXPLAIN" to MARIADB_TABULAR_PLAN,
            "ANALYZE" to MARIADB_ANALYZE_PLAN,
        )

        for ((printed, plan) in forms) {
            val reading = parsePlan(plan)

            assertTrue(reading is PlanReading.Refused, "MariaDB's $printed was not refused as a recognised shape")
            assertEquals(PlanRefusedForm.MARIADB, (reading as PlanReading.Refused).form, "for $printed")
            assertEquals(null, reading.form.recourse, "MariaDB's $printed was given a recourse")
        }
    }

    /**
     * **A MariaDB paste is recognised as MariaDB and never as a broken MySQL** — asserted as the
     * comparison it is, over the two engines' near-identical printings of one plan.
     *
     * The two tables differ by the columns MySQL added and MariaDB did not, and the two JSON documents
     * by the key each engine reports its cost under. Those are the literals recognition turns on, and
     * this is what they are for.
     */
    @Test
    fun `a MariaDB paste does not produce MySQL's message`() {
        val mariadb = listOf(MARIADB_JSON_PLAN, MARIADB_TABULAR_PLAN, MARIADB_ANALYZE_PLAN)
        val mysql = listOf(MYSQL_TREE_PLAN, MYSQL_TABULAR_PLAN)

        for (plan in mariadb) {
            val form = (parsePlan(plan) as PlanReading.Refused).form

            assertNotEquals(PlanRefusedForm.MYSQL_TREE, form, "a MariaDB paste was refused as MySQL's TREE output")
            assertNotEquals(PlanRefusedForm.MYSQL_TABULAR, form, "a MariaDB paste was refused as MySQL's table")
        }
        for (plan in mysql) {
            assertNotEquals(
                PlanRefusedForm.MARIADB,
                (parsePlan(plan) as PlanReading.Refused).form,
                "a MySQL paste was refused as MariaDB's, so the predicates do not tell them apart at all",
            )
        }
    }

    /**
     * **A text both engines could have printed falls to the general refusal, never to a guess.**
     *
     * MySQL's JSON version 1 and MariaDB's JSON share an opening key: a query block going straight to
     * its table opens identically in both. Such a text matches two entries of the format list, and
     * two is answered the way zero is — *this is not a readable plan* — rather than by picking the
     * engine whose predicate happens to be listed first.
     */
    @Test
    fun `a document both engines could have printed takes the general refusal`() {
        assertTrue(
            opensMysqlJsonV1(AMBIGUOUS_JSON_PLAN) && opensMariadbJson(AMBIGUOUS_JSON_PLAN),
            "the fixture is not ambiguous, so this asserts nothing",
        )
        assertEquals(PlanReading.Unreadable, parsePlan(AMBIGUOUS_JSON_PLAN), "an ambiguous paste was given a message")
    }

    /**
     * **The warnings overlay is refused as one of MySQL's tabular forms**, with the same recourse the
     * rest of that family carries.
     *
     * It is the one head literal the two engines print identically, and it is MySQL's all the same —
     * which is recorded rather than hidden. The overlay names no engine and says nothing about the
     * plan, and *ask for the plan as JSON* is the true advice whichever engine printed it; the
     * near-identical **plan** forms, where a recourse would genuinely be wrong, are told apart by
     * literals of their own, which the MariaDB test above is what checks.
     */
    @Test
    fun `the warnings overlay is refused as a tabular form of MySQL's`() {
        val reading = parsePlan(WARNINGS_OVERLAY_PLAN)

        assertTrue(reading is PlanReading.Refused, "the warnings overlay was not refused as a recognised shape")
        assertEquals(PlanRefusedForm.MYSQL_TABULAR, (reading as PlanReading.Refused).form)
        assertEquals(PlanRecourse.MYSQL_FORMAT_JSON, reading.form.recourse)
    }

    /**
     * **Recognition is head-anchored fixed literals, and nothing scans the body.**
     *
     * Each fixture is a text that is *not* a plan and that carries a plan's head literals further
     * down. A predicate that looked anywhere but the head would recognise every one of them — and the
     * *content* of a paste would then decide which engine's sentence its author is shown.
     */
    @Test
    fun `a head literal buried in the body recognises nothing`() {
        val buried = listOf(
            "a note somebody pasted\n$MYSQL_TREE_PLAN",
            "SELECT * FROM visits;\n$MYSQL_TABULAR_PLAN",
            "-- the plan follows\n$MARIADB_TABULAR_PLAN",
            "here is the JSON:\n$MYSQL_V2_PLAN",
        )

        for (text in buried) {
            assertEquals(
                PlanReading.Unreadable,
                parsePlan(text),
                "a head literal found in the body decided what this text is:\n$text",
            )
        }
    }

    /**
     * **A subtree pasted from the middle of a tree plan is not a tree plan**, because the arrow it
     * opens with is indented and the literal is anchored at offset zero.
     */
    @Test
    fun `a subtree pasted from the middle of a tree plan is not recognised`() {
        val subtree = MYSQL_TREE_PLAN.lines().last()

        assertTrue(subtree.startsWith(" "), "the fixture's second line is not indented, so this asserts nothing")
        assertEquals(PlanReading.Unreadable, parsePlan(subtree))
    }

    /**
     * **The vertical terminator's frame is peeled, the plan inside reads, and the frame comes back
     * byte for byte.**
     *
     * `\G` is a keystroke typed per statement rather than a client setting, so the frame it produces
     * is fixed and is admitted. The frame is never copied or reassembled — the peel hands back a map
     * into the input — so this compares everything outside the plan's own characters before and after.
     */
    @Test
    fun `a plan inside the vertical terminator's frame is peeled, read and re-emitted`() {
        for ((format, plan) in mapOf("v2" to MYSQL_V2_PLAN, "v1" to MYSQL_V1_PLAN)) {
            val framed = verticalFrameAround(plan)
            val text = anonymizedText(framed)

            assertFalse("visits_by_owner" in text, "an index inside the $format frame reached the clipboard:\n$text")
            assertTrue("0.85" in text, "a measurement inside the $format frame was moved:\n$text")
            assertTrue(
                text.startsWith("*************************** 1. row ***************************\nEXPLAIN: "),
                "the $format frame's banner was not re-emitted:\n$text",
            )
            assertTrue(text.endsWith("1 row in set (0.00 sec)"), "the $format frame's count was not re-emitted:\n$text")
        }
    }

    /**
     * **The count under a vertical frame is chrome, and a paste that stops before it still reads** —
     * but a prompt after it does not, because a prompt is a transcript rather than chrome.
     *
     * The pair is the frame's whole boundary said in one place: what the client drew around the
     * result is peeled, and what the user's own session wrote into the input is not.
     */
    @Test
    fun `a vertical frame copied without its count reads, and one carrying a prompt does not`() {
        val withoutCount = "*************************** 1. row ***************************\nEXPLAIN: $MYSQL_V2_PLAN"
        val withPrompt = verticalFrameAround(MYSQL_V2_PLAN) + "\nmysql> "

        assertTrue(parsePlan(withoutCount) is PlanReading.Read, "a paste stopping before the count was refused")
        assertFalse("visits_by_owner" in anonymizedText(withoutCount), "an index reached the clipboard")
        assertEquals(PlanReading.Unreadable, parsePlan(withPrompt), "an input carrying a prompt was read")
    }

    /**
     * **A count the client did not compute from this result is not this client's frame**, and a
     * second row's banner is a result set this product has not seen.
     */
    @Test
    fun `a vertical frame whose count or row set is not one refuses`() {
        val miscounted = verticalFrameAround(MYSQL_V2_PLAN, rows = 9)
        val second = verticalFrameAround(MYSQL_V2_PLAN)
            .replace("1 row in set", "*************************** 2. row ***************************\n1 row in set")

        assertTrue(
            parsePlan(verticalFrameAround(MYSQL_V2_PLAN)) is PlanReading.Read,
            "the frame alone does not read, so this asserts nothing",
        )
        assertEquals(PlanReading.Unreadable, parsePlan(miscounted))
        assertEquals(PlanReading.Unreadable, parsePlan(second))
    }

    /**
     * **The bordered frame the default terminator draws awaits a capture, and refuses until one
     * exists** — which is a different thing from the vertical frame being admitted, and the two are
     * asserted together so they cannot be confused.
     *
     * The plan inside it is one this product reads perfectly well without the frame. It is the
     * **frame** that has never been captured, and an uncaptured frame is refused rather than guessed
     * at, exactly as a non-default `psql` setting is.
     */
    @Test
    fun `the bordered default-terminator frame refuses where the vertical one reads`() {
        val bordered = borderedFrameAround(MYSQL_V2_PLAN)

        assertTrue(
            parsePlan(verticalFrameAround(MYSQL_V2_PLAN)) is PlanReading.Read,
            "the vertical frame does not read, so this asserts nothing about the pair",
        )
        assertEquals(PlanReading.Unreadable, parsePlan(bordered), "a frame nothing captured was read")
    }

    /**
     * **A tree plan inside the bordered frame is still refused as a tree plan.**
     *
     * The frame is not peeled — that awaits a capture — but it does not hide the head literal either,
     * and the one thing that looks past it decides only which refusal to print. Nothing is emitted
     * either way, so looking is free; what it buys is a sentence that describes what the user pasted.
     */
    @Test
    fun `a tree plan inside the bordered frame is refused as the tree format`() {
        val reading = parsePlan(borderedFrameAround(MYSQL_TREE_PLAN))

        assertTrue(reading is PlanReading.Refused, "a framed tree plan was not refused as a recognised shape")
        assertEquals(PlanRefusedForm.MYSQL_TREE, (reading as PlanReading.Refused).form)
    }

    /**
     * **No two predicates accept one text**, asserted pairwise over every fixture — the same claim
     * PostgreSQL's four formats are held to, extended across the engines that were added beside them.
     *
     * The ambiguous pair is left out on purpose: it is the one text that *is* meant to match twice,
     * and what happens to it is asserted on its own above.
     */
    @Test
    fun `no two formats accept one MySQL or MariaDB fixture`() {
        val plans = mapOf(
            "mysql-json-v2" to MYSQL_V2_PLAN,
            "mysql-json-v1" to MYSQL_V1_PLAN,
            "mariadb-json" to MARIADB_JSON_PLAN,
            "mysql-tree" to MYSQL_TREE_PLAN,
            "mysql-tree-analyze" to MYSQL_ANALYZE_PLAN,
            "mysql-tabular" to MYSQL_TABULAR_PLAN,
            "mariadb-tabular" to MARIADB_TABULAR_PLAN,
            "mariadb-analyze" to MARIADB_ANALYZE_PLAN,
            "mysql-warnings-overlay" to WARNINGS_OVERLAY_PLAN,
        )

        for ((printed, plan) in plans) {
            val accepting = PLAN_FORMATS.filter { it.recognises(plan) }.map { it.name }

            assertEquals(1, accepting.size, "the $printed fixture was accepted by $accepting")
        }
    }

    /** **No PostgreSQL printing is recognised by any of the formats added beside it**, or the reverse. */
    @Test
    fun `the PostgreSQL formats and the MySQL ones do not reach into each other`() {
        val added = MYSQL_FORMATS + MARIADB_FORMATS

        for ((printed, plan) in STRUCTURED_PLANS + ("text" to TEXT_PLAN)) {
            assertEquals(
                emptyList<String>(),
                added.filter { it.recognises(plan) }.map { it.name },
                "a PostgreSQL $printed plan was recognised by another engine's predicate",
            )
        }
        for ((printed, plan) in mapOf("v2" to MYSQL_V2_PLAN, "v1" to MYSQL_V1_PLAN, "tree" to MYSQL_TREE_PLAN)) {
            assertEquals(
                emptyList<String>(),
                POSTGRES_FORMATS.filter { it.recognises(plan) }.map { it.name },
                "a MySQL $printed plan was recognised by a PostgreSQL predicate",
            )
        }
    }
}
