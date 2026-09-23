package com.snippetveil.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **What the lexer makes of a plan's expression fields** — the residual, the delimitation rule, the
 * phrases, and the field that fails whole.
 *
 * Every assertion here is about one field's text and what came out of it, because that is exactly
 * what the closure is: there is no tree, no dialect grammar and no index behind any of it.
 */
class PlanExpressionTest {

    /**
     * **The names go and everything the engine wrote around them stays** — the whole of what reading
     * an expression field buys.
     *
     * The parentheses, the casts and the operator are the engine's punctuation; `text` is its type;
     * the relation, the alias and the column are names; and `'ACTIVE'` is a value the planner printed
     * out of the user's own data, which is the one thing in the line that is unambiguously theirs.
     */
    @Test
    fun `a predicate keeps its operators and casts, loses its names and masks its value`() {
        val text = anonymizedText(
            """
            |Seq Scan on orders o  (cost=0.00..1.00 rows=1 width=4)
            |  Filter: ((o.status)::text = 'ACTIVE'::text)
            """.trimMargin(),
        )

        assertTrue(
            Regex("""Filter: \(\(table\d+\.col\d+\)::text = 'str\d+'::text\)""").containsMatchIn(text),
            "the predicate did not come out as names replaced and punctuation kept:\n$text",
        )
        assertFalse("status" in text, "a column name survived:\n$text")
        assertFalse("ACTIVE" in text, "the value the planner printed survived:\n$text")
    }

    /**
     * **An unrecognised token anonymizes**, and this test exists to fail if that direction is ever
     * inverted.
     *
     * > The residual runs toward **replacement**, never toward preservation.
     *
     * A word the vocabulary does not hold is the employer's domain until something says otherwise,
     * and nothing here can say otherwise: there is no index, no resolve and no grammar. Inverting it
     * — preserve what we do not recognise — would put every user function, every enum type and every
     * printer word from a version nobody tested on the clipboard, silently.
     */
    @Test
    fun `an unrecognised token anonymizes rather than surviving`() {
        val plan = """
            |Seq Scan on visits  (cost=0.00..1.00 rows=1 width=4)
            |  Filter: (merchant_ledger(settlement_window) > 0)
            """.trimMargin()

        val names = namesIn(plan)
        for (word in listOf("merchant_ledger", "settlement_window")) {
            assertEquals(
                SymbolRole.COLUMN,
                names[word],
                "the residual ran toward preservation: `$word` was not reported as a name",
            )
            assertFalse(
                word in preservedIn(plan),
                "the residual ran toward preservation: `$word` was emitted as written",
            )
        }
        assertFalse("merchant" in anonymizedText(plan), "a word nobody recognised reached the output:\n" + anonymizedText(plan))
    }

    /**
     * **A delimited token is always a name**, and the spelling inside the delimiters changes nothing.
     *
     * With no PSI there is no composite to override a leaf, so *is this a name* is answered by the
     * engine's quoting rule: PostgreSQL delimits an identifier precisely when it could not be read
     * back bare, so anything it delimited is something a user named. A vocabulary lookup inside the
     * quotes would hand the whole rule back to spelling — and `"count"` is a column called `count`.
     */
    @Test
    fun `a delimited token spelled like a keyword or a builtin is still a name`() {
        val plan = """
            |Seq Scan on visits  (cost=0.00..1.00 rows=1 width=4)
            |  Filter: (("AND" > 0) AND ("count" > 0))
            """.trimMargin()

        for (delimited in listOf("AND", "count")) {
            assertEquals(
                SymbolRole.COLUMN,
                namesIn(plan)[delimited],
                "a delimited token was not read as a name: \"$delimited\"",
            )
            assertFalse(
                "\"$delimited\"" in preservedIn(plan),
                "a delimited token was emitted as written: \"$delimited\"",
            )
        }

        val text = anonymizedText(plan)
        assertTrue(
            Regex("""Filter: \(\("col\d+" > 0\) AND \("col\d+" > 0\)\)""").containsMatchIn(text),
            "a delimited name did not render inside its delimiters:\n$text",
        )
        assertEquals(
            1,
            Regex("""\bAND\b""").findAll(text).count(),
            "the bare keyword and the delimited one did not part company:\n$text",
        )
    }

    /**
     * **A phrase in the vocabulary survives whole**, which is why the vocabulary holds phrases at
     * all: a token-only vocabulary renders `::double precision` as `::col1 col2` and throws away the
     * type the plan is telling you about.
     *
     * The delimitation argument is **stronger** for a phrase than for a token — an identifier
     * containing a space must be delimited in every engine here — which is what lets `precision` and
     * `zone` be preserved inside a phrase when neither may be preserved alone: PostgreSQL does not
     * quote an unreserved keyword, so a column called `zone` prints bare.
     */
    @Test
    fun `a multi-word type name survives as one token`() {
        val plan = """
            |Aggregate  (cost=1.00..1.01 rows=1 width=8)
            |  Output: (sum(v.amount))::double precision
            |  ->  Seq Scan on visits v  (cost=0.00..1.00 rows=1 width=4)
            """.trimMargin()

        assertTrue(
            "double precision" in preservedIn(plan),
            "the phrase did not survive as one token: ${preservedIn(plan)}",
        )
        assertTrue("::double precision" in anonymizedText(plan), "the phrase did not reach the output:\n" + anonymizedText(plan))
    }

    /**
     * **A phrase may not span a name slot**, asserted against a field carrying both.
     *
     * `hashed` is a word the printer writes only in front of `SubPlan`, and it is a perfectly good
     * column name — PostgreSQL prints one bare, because `hashed` is no keyword at all. So a phrase
     * that matched across a dot would preserve somebody's column in the middle of a spelling it
     * recognised, and it would do it silently.
     */
    @Test
    fun `a phrase adjacent to a name slot does not span it`() {
        val plan = """
            |Seq Scan on visits v  (cost=0.00..1.00 rows=1 width=4)
            |  Filter: ((v.hashed = 1) AND (hashed SubPlan 1))
            |  Output: (v.hashed SubPlan 1)
            """.trimMargin()

        assertEquals(SymbolRole.COLUMN, namesIn(plan)["hashed"], "the column in the name slot was not read as a name")
        assertTrue(
            "hashed SubPlan" in preservedIn(plan),
            "the phrase did not survive beside the name slot: ${preservedIn(plan)}",
        )

        val text = anonymizedText(plan)
        assertFalse("v.hashed SubPlan" in text, "the phrase spanned the name slot:\n$text")
        assertTrue("(hashed SubPlan 1)" in text, "the phrase did not reach the output:\n$text")
        assertEquals(
            2,
            Regex("""\bSubPlan\b""").findAll(text).count(),
            "the SubPlan the phrase could not cover was replaced instead of preserved:\n$text",
        )
    }

    /**
     * **A field the lexer cannot scan soundly is one redacted literal, whole** — and nothing partial
     * is emitted from it.
     *
     * PostgreSQL doubles a quote inside a quoted thing and escapes nothing else, so a value carrying
     * a newline is printed raw and the field simply stops mid-literal. Reading the part before the
     * break is exactly the failure this rule exists for: **a misparse is not a parse failure**, so a
     * field that appears to parse cleanly proves nothing, and the only safe reading is no reading.
     */
    @Test
    fun `an unsoundly scanned field is masked whole, with no partial parse`() {
        val plan = """
            |Seq Scan on visits  (cost=0.00..1.00 rows=1 width=4)
            |  Filter: (merchant_ref = 'a value the engine never closed)
            """.trimMargin()

        val reading = planIn(plan)
        val onTheField = reading.occurrences.filterIsInstance<PlanOccurrence>()
            .filter { it.start > plan.indexOf("Filter:") }

        assertEquals(1, onTheField.size, "the unsound field was read in pieces: ${onTheField.size} occurrences")
        assertTrue(onTheField.single().disposition is PlanDisposition.Mask, "the unsound field was not masked")
        assertEquals(
            "(merchant_ref = 'a value the engine never closed)",
            plan.substring(onTheField.single().start, onTheField.single().end),
            "the mask did not cover the whole field",
        )

        val text = anonymizedText(plan)
        assertFalse("merchant_ref" in text, "a name was read out of a field nothing could scan:\n$text")
        assertFalse("value the engine" in text, "part of an unsound field survived:\n$text")
        assertTrue(
            Regex("""Filter: str\d+$""", RegexOption.MULTILINE).containsMatchIn(text),
            "the field did not come out as one redacted literal:\n$text",
        )
    }

    /**
     * **The builtin list's known exposure, recorded as a test rather than as a sentence.**
     *
     * **The plan below is a user's own `count(…)` function and a user's own `count` column**, and
     * nothing in the text says so: PostgreSQL prints its own aggregate exactly as it prints either
     * of them, in the same position, with the same spelling. So both survive under their own name,
     * and that is the price of the field being readable at all — the alternative is masking every
     * function and type name, which needs no list and carries no exposure but loses the reason the
     * field is worth reading.
     *
     * The asymmetry is why the list is enumerated rather than derived: a row it **holds** is an
     * exposure, and a row it **omits** costs only fidelity. A later ticket's oracle may add to it and
     * must never subtract from it. A user function the list does **not** hold is replaced, which is
     * the other half of the same claim.
     */
    @Test
    fun `a user function named after a builtin is preserved, which is the list's exposure`() {
        val plan = """
            |Aggregate  (cost=1.00..1.01 rows=1 width=8)
            |  Output: count(v.id), merchant_rank(v.id)
            |  ->  Seq Scan on visits v  (cost=0.00..1.00 rows=1 width=4)
            |        Filter: (count > 0)
            """.trimMargin()

        assertTrue("count" in preservedIn(plan), "the builtin was not preserved: ${preservedIn(plan)}")
        assertEquals(
            2,
            Regex("""\bcount\b""").findAll(anonymizedText(plan)).count(),
            "the exposure changed: a user object spelled like a builtin no longer survives\n" + anonymizedText(plan),
        )
        assertEquals(
            SymbolRole.COLUMN,
            namesIn(plan)["merchant_rank"],
            "a user function the list does not hold was preserved, which is the exposure widening",
        )
    }

    /**
     * **Equal spellings share one token**, so a value masked twice still reads as one value met
     * twice. Within-plan correlation is most of what a plan's predicates are read for, and a mask
     * that destroyed it would cost more than the value is worth.
     */
    @Test
    fun `two printings of one value share one redacted literal`() {
        val text = anonymizedText(
            """
            |Seq Scan on visits v  (cost=0.00..1.00 rows=1 width=4)
            |  Filter: ((v.status = 'settled') OR (v.previous_status = 'settled'))
            """.trimMargin(),
        )

        val masks = Regex("""'(str\d+)'""").findAll(text).map { it.groupValues[1] }.toList()

        assertEquals(2, masks.size, "the two values were not both masked:\n$text")
        assertEquals(1, masks.toSet().size, "two printings of one value took two placeholders:\n$text")
    }

    /**
     * **An empty literal is preserved**, for the reason the Java literal rule preserves `""`: there
     * is no content to carry a domain, and `= ''` silently becoming `= 'str1'` turns an empty-check
     * into a value-check, which is *plausible* rather than obvious.
     */
    @Test
    fun `an empty literal is left alone`() {
        val plan = """
            |Seq Scan on visits  (cost=0.00..1.00 rows=1 width=4)
            |  Filter: (note = ''::text)
            """.trimMargin()

        assertEquals(emptyList<String>(), maskedIn(plan), "an empty literal was masked")
        assertTrue("= ''::text)" in anonymizedText(plan), "an empty literal did not survive:\n" + anonymizedText(plan))
    }

    /**
     * **A number, a unit and a parameter marker are shape rather than domain**, and none of them is
     * reported at all — which is what keeps a plan's measurements out of reach of every rule in the
     * closure.
     */
    @Test
    fun `numbers, units and parameter markers are never reported`() {
        val plan = """
            |Seq Scan on visits  (cost=0.00..1.00 rows=1 width=4)
            |  Index Cond: (id = $1)
            |  Filter: (size > 25kB)
            """.trimMargin()

        val text = anonymizedText(plan)

        assertTrue("= $1)" in text, "the parameter marker did not survive:\n$text")
        assertTrue("25kB" in text, "a unit did not survive:\n$text")
        assertFalse("kB" in namesIn(plan).keys, "a unit was read as a name")
    }
}
