package com.snippetveil.sweep

import com.snippetveil.core.PLAN_FORMATS
import com.snippetveil.core.PlanEngine
import com.snippetveil.core.PlanFormatReach
import com.snippetveil.core.PlanFormatState
import com.snippetveil.core.PlanReading
import com.snippetveil.core.PlanRefusedForm
import com.snippetveil.core.SymbolRole
import com.snippetveil.core.anonymizedText
import com.snippetveil.core.namesIn
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **What the committed subset has to cover, and the two statements about the vocabulary that hold
 * without a corpus.**
 *
 * The coverage floor and the row-state rules are properties of the format vocabulary rather than of
 * anything swept, so they are asserted here, on every pull request. The denominator clause — *a
 * format in the vocabulary with zero captures is a finding* — is the one that needs a corpus, and it
 * runs in `PlanSweep`.
 */
class PlanCoverageTest {

    /**
     * **One committed fixture per admitted format.**
     *
     * A format admitted with nothing committed behind it is a row the pull-request cell never asks
     * about, which is the committed-subset version of *admitted on no evidence*.
     */
    @Test
    fun `every admitted format has a committed capture`() {
        val admitted = PLAN_FORMATS.filter { it.state == PlanFormatState.ADMITTED }
        val covered = COMMITTED_PLAN_CAPTURES
            .mapNotNull { (it.label as? PlanCaptureLabel.OfFormat)?.format?.name }
            .toSet()

        assertTrue(
            admitted.all { it.name in covered },
            "these formats are admitted and no committed capture is labelled with them: " +
                admitted.filterNot { it.name in covered }.joinToString { it.name },
        )
    }

    /** **One committed fixture per refusal message**, so that every message has a capture behind it. */
    @Test
    fun `every refusal message has a committed capture`() {
        val covered = COMMITTED_PLAN_CAPTURES
            .mapNotNull { (it.label as? PlanCaptureLabel.OfRefusal)?.form }
            .toSet()

        assertEquals(
            PlanRefusedForm.entries.toSet(),
            covered,
            "every refusal message needs a capture that produces it",
        )
    }

    /**
     * **The coverage floor, met on arrival.**
     *
     * > **If no engine's default-or-one-flag-away form is admitted, the release waits** — not on
     * > tuning, but because the feature would fire only for users who already know which flag to
     * > pass.
     *
     * PostgreSQL's default text output is admitted, so the floor is met today and **cannot become
     * unmet without deleting an admission row** — which is the event this assertion exists to
     * notice.
     */
    @Test
    fun `the coverage floor is met`() {
        assertTheCoverageFloorIsMet()

        val defaults = formatsCarryingTheFloor().filter { it.reach == PlanFormatReach.DEFAULT }
        assertTrue(
            defaults.isNotEmpty(),
            "the floor is carried only by forms a user has to ask for, which is the product the " +
                "floor exists to refuse",
        )
    }

    /**
     * **Every row carries a state, and the state agrees with what the row's reader does.**
     *
     * The state is what the denominator clause reads, and a row whose state said one thing while its
     * reader did another would make a zero-capture finding unreadable. Asserted by running each row's
     * reader over a text it recognises is impossible without a capture; what *is* checkable is that
     * a refused row never reads anything, which is asserted through the committed subset in
     * `PlanZerosTest`, and that the two enumerations are used at all.
     */
    @Test
    fun `every format row carries a state and a reach`() {
        assertTrue(PLAN_FORMATS.isNotEmpty(), "the vocabulary is empty, so nothing below is asserted")
        assertTrue(
            PLAN_FORMATS.any { it.state == PlanFormatState.ADMITTED },
            "no row admits anything, so this product reads no plan at all",
        )
        assertTrue(
            PLAN_FORMATS.any { it.state == PlanFormatState.REFUSED },
            "no row refuses anything, so the leak-shaped zero has nothing to be about",
        )
    }

    /**
     * **No row is refused for want of a capture today, and that is a fact rather than an oversight.**
     *
     * The third state exists because the denominator clause cannot tell *zero captures because the
     * form is refused* from *zero captures because nobody looked*. Every form recognised here is
     * refused on an argument about the form itself, and the shapes refused for want of a capture
     * have no predicate at all — so they are not rows, and they fall to the general refusal.
     *
     * Asserted rather than written in prose, so that the day a row does take the state, the sentence
     * saying otherwise is found.
     */
    @Test
    fun `no format row is refused pending a capture`() {
        val pending = PLAN_FORMATS.filter { it.state == PlanFormatState.REFUSED_PENDING_CAPTURE }

        assertTrue(
            pending.isEmpty(),
            "these rows are refused pending a capture: ${pending.joinToString { it.name }}. That is a " +
                "supported state — update the prose in PlanFormatState and here, which both say no " +
                "row carries it.",
        )
    }

    /**
     * **One engine's default form is refused outright, and another is refused in every form.**
     *
     * The default-form share is the third reported number and it gates nothing — but the two facts
     * it is reporting are the copy cost the public listing has to carry, so they are pinned here:
     * the listing cannot say *paste your `EXPLAIN` output* unqualified while they hold.
     */
    @Test
    fun `the default-form share is the copy cost it is reported to be`() {
        val mysqlDefault = PLAN_FORMATS.single {
            it.engine == PlanEngine.MYSQL && it.reach == PlanFormatReach.DEFAULT
        }
        val mariadb = PLAN_FORMATS.filter { it.engine == PlanEngine.MARIADB }

        assertEquals(PlanFormatState.REFUSED, mysqlDefault.state, "MySQL's default form is no longer refused")
        assertTrue(mariadb.isNotEmpty(), "MariaDB has no rows at all, so nothing is being said about it")
        assertTrue(
            mariadb.none { it.state == PlanFormatState.ADMITTED },
            "MariaDB is no longer refused in every form",
        )
        assertTrue(
            PLAN_FORMATS.none { it.engine == PlanEngine.SQLSERVER && it.reach == PlanFormatReach.DEFAULT },
            "SQL Server has gained a default form, which changes what the listing may say",
        )
    }

    /**
     * **A fixture whose table is named after an engine keyword asserts it became a table
     * placeholder** — the named assertion that closes the oracle's one permanent hole.
     *
     * Subtracting the rider's keyword rows takes every token colliding with engine chrome out of the
     * universe, so no leak oracle built here can catch a relation genuinely named `Sort`. The answer
     * is **not** to narrow the universe, which would drown the triage list; it is to name the case
     * and assert the reader on it. The reader never scanned for the name in the first place: the
     * inventory said which slot holds a relation, and that is why it holds.
     */
    @Test
    fun `a relation named after an engine keyword becomes a table placeholder`() {
        val names = namesIn(KEYWORD_NAMED_TABLE_PLAN)
        val output = anonymizedText(KEYWORD_NAMED_TABLE_PLAN)

        assertEquals(SymbolRole.TABLE, names["Sort"], "the relation named `Sort` was not read as a relation")
        assertEquals(SymbolRole.SCHEMA, names["Hash"], "the schema named `Hash` was not read as a schema")
        assertTrue(
            Regex("""^\s*"Relation Name": "table\d+",?$""", RegexOption.MULTILINE).containsMatchIn(output),
            "the relation did not come out as a table placeholder:\n$output",
        )
        for (keyword in KEYWORD_NAMES) {
            assertFalse(
                Regex(""""(Relation Name|Schema|Alias)": "$keyword"""").containsMatchIn(output),
                "`$keyword` survived in the slot it named:\n$output",
            )
        }
    }
}
