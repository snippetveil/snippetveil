package com.snippetveil.sweep

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
 * So this class is built from three sets that the anonymiser's own walk had no part in, and it is
 * constructible from nothing else — see [over]. **This is the only layer in the project where a
 * missing plan item is visible.**
 *
 * ### Deliberately blunt, and deliberately false-positive-prone
 *
 * It reads the output as **text**, not as code, and it will throw on genuine collisions with
 * preserved library members. That is affordable only because a human adjudicates every row it
 * produces: a false positive costs a minute, and a false negative is the product's core promise
 * failing silently. It is why the sweep is an instrument and not a test — see [CorpusSweep].
 */
internal class LeakOracle private constructor(private val projectOwned: Set<String>) {

    /**
     * How many names the oracle actually tests against — reported rather than recomputed by the
     * caller, because the two input sets overlap only in one direction and a caller that subtracted
     * sizes would be re-deriving a number this class already holds.
     */
    val size: Int get() = projectOwned.size

    /**
     * Every project-owned name that survived into [output], once each, at the line it first appears
     * on — the triage list a human reads.
     *
     * Read line by line rather than by scanning for each owned name in turn, which is the same
     * answer for a fraction of the work: a project with 40,000 declared names would otherwise be
     * 40,000 passes over every file's output.
     */
    fun survivorsIn(output: String): List<Survivor> {
        val found = LinkedHashMap<String, Survivor>()
        output.lineSequence().forEachIndexed { index, line ->
            IDENTIFIER.findAll(line)
                .map { it.value }
                .filter { it in projectOwned }
                .forEach { name -> found.putIfAbsent(name, Survivor(name, index + 1, line.trim())) }
        }
        return found.values.toList()
    }

    companion object {

        /**
         * **The universe, and the only way to build one**: every identifier declared anywhere in the
         * target project's own sources, minus the names the JDK and the libraries declare. **That is
         * the whole subtraction, and nothing else may be added to it here.**
         *
         * The private constructor is the point. A future maintainer reaching for
         * `AnonymizationResult.mapping` to build a "better" universe has to change this signature to
         * do it, and the signature says what the universe is derived from.
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
         * exactly the size the ticket said it should be.
         *
         * @param declaredInProjectSources every name declared in the project's own source files —
         *   classes, methods, fields, parameters, locals, type parameters, labels and package
         *   segments alike. Over-inclusive on purpose.
         * @param declaredByLibraries the subset of those names that the JDK or a library also
         *   declares. Subtracted rather than reported, because the anonymiser preserves library names
         *   deliberately and the oracle cannot tell a preserved `Builder` from a leaked one. **This
         *   is the oracle's only blind spot, and it is stated rather than hidden**: a project class
         *   whose name collides exactly with a library class's is one this check cannot see.
         */
        fun over(declaredInProjectSources: Set<String>, declaredByLibraries: Set<String>): LeakOracle {
            val universe = declaredInProjectSources - declaredByLibraries

            // A check that found nothing to check is not a pass — the same rule the trust checks in
            // build.gradle.kts follow. An empty universe here means the declaration walk read
            // nothing, and every file would then come back clean.
            check(universe.isNotEmpty()) {
                "The project-owned name universe came out empty, so every file would report clean. " +
                    "${declaredInProjectSources.size} name(s) were declared, and " +
                    "${declaredByLibraries.size} of them are also declared by the JDK or a library."
            }
            return LeakOracle(universe)
        }

        /**
         * **The rules prove they can fail before they report that nothing failed.**
         *
         * A red path that is never exercised decays into a check that always passes, and this one is
         * the only layer standing between a silent leak and a report that says *clean*. Run by
         * [CorpusSweep] before it opens anything, so that a human reading a report knows the rules
         * behind it had just demonstrated each of their edges.
         *
         * @return how many assertions were made, so that a self-proof which stopped asserting
         *   anything is itself visible
         */
        fun proveTheRulesCanFail(): Int {
            val oracle = over(setOf("MerchantLedger", "merchantId", "Builder"), setOf("Builder"))
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
            return asserted
        }

        /**
         * A Java identifier, as the output is tokenised into names to test.
         *
         * Unicode rather than ASCII, because Java allows it and a name this pattern could not read
         * would be a name the oracle silently vouches for.
         */
        private val IDENTIFIER = Regex("""[\p{L}_$][\p{L}\p{N}_$]*""")
    }
}

/**
 * One project-owned name that reached the output, and where.
 *
 * @param name the name, exactly as the project declares it
 * @param line the 1-based line of the anonymized output it first appears on
 * @param text that line, trimmed — the context a human needs to tell a leak from a collision
 */
internal class Survivor(val name: String, val line: Int, val text: String)
