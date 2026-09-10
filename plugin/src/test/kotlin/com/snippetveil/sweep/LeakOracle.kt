package com.snippetveil.sweep

import com.snippetveil.sweep.Declaration.KotlinFacade
import com.snippetveil.sweep.Declaration.KotlinObject
import com.snippetveil.sweep.Declaration.KotlinProperty
import com.snippetveil.sweep.Declaration.Written

/**
 * **The leak check, derived from the input and never from the mapping.**
 *
 * There are two ways to assert that nothing project-owned got out of an invocation. Walk the
 * **mapping** and check that each entry's source name is absent from the output; or build the set of
 * project-owned names **from the input** and check that none of them survives. They look equivalent.
 *
 * **They are not.** The worst bug the spike produced was a reference that passed through verbatim —
 * and *that name was never in the mapping*. That is what the bug **was**. A mapping-derived check is
 * green on it forever, because it can only ask about entries that exist: it can prove that what the
 * anonymiser did was done, and it can never prove that it did everything.
 *
 * So this class is built from sets that the anonymiser's own walk had no part in, and it is
 * constructible from nothing else — see [over]. **This is the only layer in the project where a
 * missing plan item is visible.**
 *
 * ### One contract, more than one construction
 *
 * > A leak universe is derived from the *input*, never from the anonymiser's own walk and never from
 * > the mapping. There is exactly one contract — *nothing project-owned survives in the output except
 * > via a rule-stated preserve* — and more than one construction. The construction is chosen by what
 * > the input is. The constructions are not unified.
 *
 * [survivorsIn] is the contract. [over] is the construction for **source under analysis** —
 * declaration-derived, closed under [SourceSpellings]' rule, minus JDK- and library-declared names —
 * and it is the only construction there is today. **Another input shape takes a factory of its own,
 * over an input type of its own, rather than widening this one.** Unification would collapse every
 * construction to this one and bring its blindness with it; that is the reason they are kept apart,
 * not a preference about style.
 *
 * ### Deliberately blunt, and deliberately false-positive-prone
 *
 * It reads the output as **text**, not as code, and it will throw on genuine collisions with
 * preserved library members. That is affordable only because a human adjudicates every row it
 * produces: a false positive costs a minute, and a false negative is the product's core promise
 * failing silently. It is why the sweep is an instrument and not a test — see [CorpusSweep].
 *
 * @param universe every spelling the oracle tests against, each mapped to what the closure derived it
 *   from — or to `null` where the source writes it
 */
internal class LeakOracle private constructor(private val universe: Map<String, String?>) {

    /**
     * How many spellings the oracle actually tests against — reported rather than recomputed by the
     * caller, because the two input sets overlap only in one direction and a caller that subtracted
     * sizes would be re-deriving a number this class already holds.
     */
    val size: Int get() = universe.size

    /** Whether any spelling is qualified, which is the only case that needs a line read for chains. */
    private val anyQualified = universe.keys.any { '.' in it }

    /**
     * Every project-owned spelling that survived into [output], once each, at the line it first
     * appears on — the triage list a human reads.
     *
     * Read line by line rather than by scanning for each owned name in turn, which is the same
     * answer for a fraction of the work: a project with 40,000 declared names would otherwise be
     * 40,000 passes over every file's output.
     */
    fun survivorsIn(output: String): List<Survivor> {
        val found = LinkedHashMap<String, Survivor>()
        output.lineSequence().forEachIndexed { index, line ->
            spellingsIn(line)
                .filter { it in universe }
                .forEach { name -> found.putIfAbsent(name, Survivor(name, index + 1, line.trim(), universe[name])) }
        }
        return found.values.toList()
    }

    /**
     * Every spelling [line] writes, in the order it writes them: each identifier, and — where the
     * universe holds a qualified spelling such as `Settlement.INSTANCE` — each run of two or more
     * identifiers joined by dots.
     *
     * A qualified spelling is matched **as a whole**, so that its tail is never a finding on its own:
     * `INSTANCE` is a word the language fixes, and `Type1.INSTANCE` names nothing the project owns.
     */
    private fun spellingsIn(line: String): Sequence<String> {
        val identifiers = IDENTIFIER.findAll(line).map { it.range.first to it.value }
        if (!anyQualified) return identifiers.map { it.second }

        val qualified = QUALIFIED.findAll(line).flatMap { chain ->
            val segments = IDENTIFIER.findAll(chain.value).map { (chain.range.first + it.range.first) to it.value }.toList()
            segments.indices.asSequence().flatMap { from ->
                (from + 1 until segments.size).asSequence().map { to ->
                    segments[from].first to segments.subList(from, to + 1).joinToString(".") { it.second }
                }
            }
        }
        return (identifiers + qualified).sortedBy { it.first }.map { it.second }
    }

    companion object {

        /**
         * **The construction for source under analysis, and the only way to build an oracle**: the
         * closure of every declaration in the target project's own sources, minus the spellings the
         * JDK and the libraries declare. **That is the whole subtraction, and nothing else may be
         * added to it here.**
         *
         * The private constructor is the point. A future maintainer reaching for
         * `AnonymizationResult.mapping` to build a "better" universe has to change this signature to
         * do it, and the signature says what the universe is derived from — a [SourceSpellings],
         * which is itself constructible from declaration text alone.
         *
         * ### One subtraction, and why there is not a second
         *
         * There was briefly a third argument: the top-level package segment of each declared package
         * — `com` out of `com.acme.billing` — which the engine passes through by a positional rule of
         * its own, and which therefore appears in the output of every file ever swept. Subtracting it
         * made the report shorter. **It was reverted, and the reasoning is worth keeping**, because
         * the argument for it is the argument that will be made for the next one.
         *
         * The point of this instrument is to **bias toward false positives rather than silently
         * suppress possible leaks.** Every subtraction here is a class of leak the sweep can never
         * see again, bought with a class of noise a human would otherwise read past once. The
         * library subtraction is here because the ticket authorised it and because without it the
         * oracle cannot function at all; a second one is a **product decision**, and it belongs in a
         * ticket of its own rather than in a maintainer's judgement about report length.
         *
         * So `com` is reported, in every file, like anything else the project declares. It is a known
         * recurring false positive, it is documented as one in CONTRIBUTING.md and named in the
         * report itself, and a human adjudicates it — which costs a minute and leaves the blind spot
         * exactly the size the ticket said it should be. **The closure's noise is on the same
         * footing**: a spelling it derived is annotated as derived, and never removed.
         *
         * @param spellings every spelling of every declaration in the project's own source files, in
         *   both languages — declared names and the siblings the closure derives from them alike.
         *   Over-inclusive on purpose.
         * @param declaredByLibraries the spellings that the JDK or a library also declares.
         *   Subtracted rather than reported, because the anonymiser preserves library names
         *   deliberately and the oracle cannot tell a preserved `Builder` from a leaked one. **This
         *   is the oracle's only blind spot, and it is stated rather than hidden**: a project
         *   spelling that collides exactly with a library's is one this check cannot see. It only
         *   ever subtracts, so an incomplete library set costs false positives and never a miss.
         */
        fun over(spellings: SourceSpellings, declaredByLibraries: Set<String>): LeakOracle {
            val universe = spellings.except(declaredByLibraries)

            // A check that found nothing to check is not a pass — the same rule the trust checks in
            // build.gradle.kts follow. An empty universe here means the declaration walk read
            // nothing, and every file would then come back clean.
            check(universe.isNotEmpty()) {
                "The project-owned name universe came out empty, so every file would report clean. " +
                    "${spellings.names.size} spelling(s) were read or derived, and " +
                    "${declaredByLibraries.size} are also declared by the JDK or a library."
            }
            return LeakOracle(universe)
        }

        /**
         * **The rules prove they can fail before they report that nothing failed.**
         *
         * A red path that is never exercised decays into a check that always passes, and this one is
         * the only layer standing between a silent leak and a report that says *clean*. Run by
         * [CorpusSweep] before it opens anything, so that a human reading a report knows the rules
         * behind it had just demonstrated each of their edges — the closure's among them: a sibling
         * spelling and a facade name are exactly what a universe of declared text reported clean on.
         *
         * @return how many assertions were made, so that a self-proof which stopped asserting
         *   anything is itself visible
         */
        fun proveTheRulesCanFail(): Int {
            val oracle = over(
                SourceSpellings.of(
                    listOf(
                        Written("MerchantLedger"),
                        Written("merchantId"),
                        Written("Builder"),
                        KotlinProperty("body", mutable = false),
                        KotlinFacade("Ledger.kt", jvmName = null),
                        KotlinObject("Settlement"),
                    )
                ),
                declaredByLibraries = setOf("Builder"),
            )
            var asserted = 0

            fun proves(complaint: String, held: Boolean) {
                asserted++
                check(held) { "The leak oracle $complaint. It cannot be trusted to report anything." }
            }

            proves(
                "failed to flag a project-owned name sitting verbatim in the output",
                oracle.survivorsIn("MerchantLedger m;").map { it.name } == listOf("MerchantLedger"),
            )
            proves(
                "flagged an output that names nothing the project owns",
                oracle.survivorsIn("Type1 field2;").isEmpty(),
            )
            proves(
                "matched a name as a substring of a longer identifier",
                oracle.survivorsIn("int merchantIdentifier;").isEmpty(),
            )
            proves(
                "missed a name surviving inside a literal",
                oracle.survivorsIn("""x("MerchantLedger")""").map { it.name } == listOf("MerchantLedger"),
            )
            proves(
                "reported a library-declared name, which the anonymiser preserves on purpose",
                oracle.survivorsIn("Builder b;").isEmpty(),
            )
            proves(
                "reported a name more than once for one file",
                oracle.survivorsIn("MerchantLedger a;\nMerchantLedger b;").size == 1,
            )
            proves(
                "failed to flag a Kotlin property surviving under its Java getter, which no source text declares",
                oracle.survivorsIn("String s = x.getBody();").map { it.name } == listOf("getBody"),
            )
            proves(
                "failed to flag a facade name, which is declared in no source text at all",
                oracle.survivorsIn("LedgerKt.settle(1);").map { it.name } == listOf("LedgerKt"),
            )
            proves(
                "failed to flag an object surviving through its instance",
                "Settlement.INSTANCE" in oracle.survivorsIn("Settlement.INSTANCE.run();").map { it.name },
            )
            proves(
                "flagged a name the language fixes rather than one the project declares",
                oracle.survivorsIn("Type1.INSTANCE.copy(it.component1()); Type2.Companion").isEmpty(),
            )
            proves(
                "lost the annotation that tells a spelling derived by closure from one the source declares",
                oracle.survivorsIn("x.getBody();").single().derivation != null &&
                    oracle.survivorsIn("MerchantLedger m;").single().derivation == null,
            )
            return asserted
        }

        /**
         * A Java identifier, as the output is tokenised into names to test.
         *
         * Unicode rather than ASCII, because Java allows it and a name this pattern could not read
         * would be a name the oracle silently vouches for.
         */
        private const val IDENTIFIER_PATTERN = """[\p{L}_$][\p{L}\p{N}_$]*"""

        private val IDENTIFIER = Regex(IDENTIFIER_PATTERN)

        /** Two or more identifiers joined by dots, whitespace allowed around each dot. */
        private val QUALIFIED = Regex("""$IDENTIFIER_PATTERN(?:\s*\.\s*$IDENTIFIER_PATTERN)+""")
    }
}

/**
 * One project-owned spelling that reached the output, and where.
 *
 * @param name the spelling, exactly as the universe holds it — qualified where it is qualified
 * @param line the 1-based line of the anonymized output it first appears on
 * @param text that line, trimmed — the context a human needs to tell a leak from a collision
 * @param derivation what the closure derived this spelling from, or `null` where the project's source
 *   writes it. **The triage annotation**: a row that entered the universe by closure rather than by
 *   declaration is a new, known class of false positive, and a reader is told so rather than left to
 *   rediscover it.
 */
internal class Survivor(val name: String, val line: Int, val text: String, val derivation: String? = null)
