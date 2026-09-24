package com.snippetveil.sweep

import com.snippetveil.core.PlanVocabulary

/**
 * **The leak check over plan input, derived from the input and never from the mapping.**
 *
 * The contract is the source half's, unchanged:
 *
 * > A leak universe is derived from the *input*, never from the anonymiser's own walk and never from
 * > the mapping. There is exactly one contract — *nothing owned by whoever wrote the plan survives in
 * > the output except via a rule-stated preserve* — and more than one construction. The construction
 * > is chosen by what the input is. **The constructions are not unified.**
 *
 * This is the construction for **a plan text**, and it is a factory of its own over an input type of
 * its own rather than a widening of the source one. Widening would bring the source construction's
 * blindness — its subtraction of every library-declared spelling — into a reading where it means
 * nothing at all.
 *
 * ### The universe, and the one row it never subtracts
 *
 * [universeOf] builds it: the input's own identifier-shaped tokens, split on engine-neutral
 * punctuation runs, delimited spellings taken whole, minus the rider's keyword tokens and printer
 * phrases. **[PlanVocabulary.builtins] is never subtracted**, and this class cannot be constructed in
 * a way that subtracts it — [over] takes the rider and does the subtraction itself, so a maintainer
 * reaching for a shorter report has to change this file to get one.
 *
 * That row is the one rider that can silently authorise a user's name: an engine prints a user's
 * `count` function exactly as it prints its own. An oracle that subtracted the list would be green on
 * exactly the defect the list is known to be able to have — so a spelling the list saves is reported,
 * **annotated with the row that saved it**, and never removed.
 *
 * ### The one permanent hole, closed elsewhere
 *
 * Subtracting the keyword rows removes every token colliding with engine chrome, so this oracle
 * cannot catch a relation genuinely named after an engine keyword printed bare. That is **not**
 * answered by narrowing the universe — the noise would swallow the report — but by a named
 * assertion: a fixture whose table is named `Sort`, `Hash` or `Filter` asserts it became a table
 * placeholder. See `KEYWORD_NAMED_TABLE_PLAN`.
 *
 * ### Deliberately blunt, and deliberately false-positive-prone
 *
 * It reads the output as **text**. Every field label a plan prints, every node type an engine
 * invents and every word of a namespace URI is in the universe and survives into the output, because
 * no rider row holds them — so a clean capture still reports rows. That is affordable only because a
 * human adjudicates every row, which is the whole reason this half is an instrument and not a test,
 * and it is **why the report can be neither green nor red**. A recurring class of noise is annotated
 * rather than subtracted, for the reason the source half keeps reporting `com`: every subtraction is
 * a class of leak the sweep can never see again, bought with noise a human reads past once.
 *
 * @param universe every spelling the oracle tests against, each mapped to the annotation a reader
 *   needs in order to triage it — or to `null` where there is nothing to say about it
 */
internal class PlanLeakOracle private constructor(private val universe: Map<String, PlanTriageNote?>) {

    /**
     * Every spelling of the input's that survived into [output], once each, at the line it first
     * appears on — the triage list a human reads.
     *
     * Read line by line and tokenised the way the input was, so that a spelling is matched **as a
     * whole**: a plan that named a column `id` does not report on the `Id` of `NodeId`, and a name
     * that survived inside a placeholder-bearing line is found where it is.
     */
    fun survivorsIn(output: String): List<PlanSurvivor> {
        val found = LinkedHashMap<String, PlanSurvivor>()
        output.lineSequence().forEachIndexed { index, line ->
            spellingsIn(line)
                .map { it.spelling }
                .filter { it in universe }
                .forEach { found.putIfAbsent(it, PlanSurvivor(it, index + 1, line.trim(), universe[it])) }
        }
        return found.values.toList()
    }

    companion object {

        /**
         * **The construction for a plan text, and the only way to build one of these.**
         *
         * The private constructor is the point, exactly as it is in the source half: a maintainer
         * reaching for the parser's own occurrences to build a "better" universe has to change this
         * signature to do it, and the signature says what the universe is derived from — a text and a
         * rider, neither of which the anonymiser's walk had any part in.
         *
         * @param text the capture, exactly as it arrived
         * @param rider the engine's vocabulary. Its keyword and phrase rows are subtracted; its
         *   builtin row is **not**, and is used only to annotate.
         */
        fun over(text: String, rider: PlanVocabulary): PlanLeakOracle {
            val universe = universeOf(text, rider).associate { spelling ->
                spelling.spelling to noteFor(spelling, rider)
            }

            // A check that found nothing to check is not a pass — the rule every check in this
            // repository follows. An empty universe over a plan means the splitter read nothing, and
            // every capture would then come back clean.
            check(universe.isNotEmpty()) {
                "The plan's own name universe came out empty, so the capture would report clean. " +
                    "${text.length} character(s) were read."
            }
            return PlanLeakOracle(universe)
        }

        /**
         * What a reader is told about a spelling before they triage it. See [PlanTriageNote].
         */
        private fun noteFor(spelling: PlanSpelling, rider: PlanVocabulary): PlanTriageNote? = when {
            // A delimited spelling is a name whatever it is spelled, so nothing is said about it: a
            // note reading *the printer writes this word too* would be the sentence that gets a
            // leaked `"Sort"` read past.
            spelling.delimited -> null
            spelling.spelling in rider.builtins -> PlanTriageNote.Builtin(spelling.spelling)
            spelling.spelling in PlanProvenance.chrome -> PlanTriageNote.Printer
            else -> null
        }

        /**
         * **The rules prove they can fail before they report that nothing failed.**
         *
         * A red path that is never exercised decays into a check that always passes, and this one is
         * the only layer standing between a plan leaking a name and a report that says nothing
         * survived. Run by the instrument before it opens a corpus, so that a human reading a report
         * knows the rules behind it had just demonstrated each of their edges.
         *
         * @return how many assertions were made, so that a self-proof which stopped asserting
         *   anything is itself visible
         */
        fun proveTheRulesCanFail(rider: PlanVocabulary): Int {
            var asserted = 0

            fun proves(complaint: String, held: Boolean) {
                asserted++
                check(held) { "The plan leak oracle $complaint. It cannot be trusted to report anything." }
            }

            // **A rider may hold no bare-word row at all, and one does.** SQL Server's plans bracket
            // every identifier, so nothing there is preserved by a quoting argument and there is no
            // keyword subtraction to demonstrate. The edges that exist are proved; the count comes
            // back so that a rider proving fewer than it could is visible rather than assumed.
            val keyword = rider.words.firstOrNull()
            val builtin = rider.builtins.firstOrNull()
            val oracle = over("Filter: (billing.invoices.status = 'open') AND (id = 42)", rider)

            // What is asserted below is always about a **named** spelling, never about an empty
            // list: this oracle is false-positive-prone by design, so a proof resting on nothing
            // else surviving would be a proof of the wrong property.

            proves(
                "failed to flag a relation sitting verbatim in the output",
                "invoices" in oracle.survivorsIn("Seq Scan on schema1.invoices i").map { it.spelling },
            )
            proves(
                "flagged a name the input never wrote",
                oracle.survivorsIn("Seq Scan on schema1.table2 t").none { it.spelling == "invoices" },
            )
            proves(
                "matched a name as a substring of a longer identifier",
                oracle.survivorsIn("Seq Scan on invoices_archive").none { it.spelling == "invoices" },
            )
            if (keyword != null) {
                proves(
                    "reported a keyword the engine's quoting rule cannot print for a user's name",
                    over("$keyword (status = 1)", rider)
                        .survivorsIn("$keyword (col1 = 1)")
                        .none { it.spelling == keyword },
                )
            }
            proves(
                "reported one name more than once for one capture",
                oracle.survivorsIn("invoices\ninvoices").count { it.spelling == "invoices" } == 1,
            )

            // A delimited spelling is a name whatever it is spelled, so the keyword subtraction must
            // not reach it — this is the assertion that the two halves of the universe stay apart.
            if (keyword != null) {
                val delimited = over("""Index Cond: ("$keyword" = 1)""", rider)
                proves(
                    "subtracted a keyword spelling the engine had delimited, which is always a name",
                    keyword in delimited.survivorsIn("""Index Cond: ("$keyword" = 1)""").map { it.spelling },
                )
            }

            // The builtin row is never subtracted, and what it saves is annotated with the row.
            if (builtin != null) {
                val shadowing = over("Filter: ($builtin(x) > 1)", rider)
                proves(
                    "subtracted the builtin list, which is the one row it must never subtract",
                    builtin in shadowing.survivorsIn("Filter: ($builtin(col1) > 1)").map { it.spelling },
                )
                proves(
                    "lost the row that saved a spelling the builtin list preserved",
                    (
                        shadowing.survivorsIn("Filter: ($builtin(col1) > 1)")
                            .single { it.spelling == builtin }
                            .note as? PlanTriageNote.Builtin
                        )?.row == builtin,
                )
            }
            return asserted
        }
    }
}

/**
 * One spelling of the input's that reached the output, and where.
 *
 * @param spelling the spelling, exactly as the universe holds it
 * @param line the 1-based line of the anonymized output it first appears on
 * @param text that line, trimmed — the context a human needs to tell a leak from a collision
 * @param note what a reader is told before they triage it, or `null` where there is nothing to say.
 *   See [PlanTriageNote].
 */
internal class PlanSurvivor(
    val spelling: String,
    val line: Int,
    val text: String,
    val note: PlanTriageNote? = null,
)

/**
 * **What a reader is told about a survivor before they triage it** — a class, not a sentence, so
 * that a number can be counted off it rather than recovered by re-reading prose.
 *
 * Two of them, because two things are worth saying and they are worth saying for opposite reasons.
 */
internal sealed class PlanTriageNote {

    /** How the note reads in a report. */
    abstract val printed: String

    /**
     * **This spelling survived because a builtin row saved it** — the row named.
     *
     * The load-bearing one. It is the number that makes the oracle's refusal to subtract that list
     * answerable: *this many spellings survived only because a builtin row saved them, and here is
     * the row.* Counting it is how the size of the shadowing exposure stops being an argument and
     * becomes a measurement.
     */
    class Builtin(val row: String) : PlanTriageNote() {
        override val printed: String get() = "preserved by the builtin row $row"
    }

    /**
     * **This spelling is one the engine's own printer writes** — the known recurring false positive
     * of this oracle, named rather than removed.
     *
     * Every field label, node type and namespace word of a plan is in the universe, because no rider
     * row holds them, and every one of them survives into the output. **Subtracting them was refused
     * for the reason the source half keeps reporting `com`**: each subtraction is a class of leak
     * this instrument can never see again — a relation genuinely named `Filter` among them — bought
     * with noise a human reads past once. So the row is printed, with a note saying which class it
     * is in, and a human adjudicates it.
     */
    object Printer : PlanTriageNote() {
        override val printed: String get() = "a spelling the engine's own printer writes"
    }
}
