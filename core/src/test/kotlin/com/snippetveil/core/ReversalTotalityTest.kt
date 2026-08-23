package com.snippetveil.core

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertEquals
import java.time.Instant

/**
 * **Reversal is total over the minted namespace**, stated over generated runs of invocations rather
 * than over one paste.
 *
 * *Total* is the load-bearing word and it is why this is a property rather than an example. The
 * reversal contract is only allowed to be an exact match — no fuzzing, no compounds, no
 * case-insensitivity — **because** the namespace is one this engine created and recorded, so an
 * exact match misses nothing. If some corner of the namespace turned out not to be recorded, the
 * exact match would start under-recovering on a shape nobody wrote an example for, and the argument
 * for exactness would quietly stop holding. A generated run over an overlapping symbol universe is
 * what puts every shape through it: qualified and ephemeral symbols, accessors deriving from fields,
 * numbers burnt in between.
 *
 * Three statements, each checked on every invocation of every sequence so that a failure names the
 * paste that broke it:
 *
 *  - **Everything minted decodes.** Every placeholder the invocation produced maps back to the name
 *    it was minted for.
 *  - **The reply decodes whole.** Running the reversal over the anonymized text itself leaves no
 *    placeholder behind — which is the thing a user actually does.
 *  - **Losing the sidecar costs recovery and never correctness.** With the window emptied, every
 *    qualified symbol still decodes out of the mapping, and every placeholder that does *not* is
 *    reported as [UnrestoredReason.EVICTED] — never as foreign, and never as some other name.
 */
class ReversalTotalityTest {

    @Property(tries = 300)
    fun `every placeholder an invocation minted decodes to the name it was minted for`(
        @ForAll("invocationSequences") sequence: List<List<Int>>,
    ) {
        fold(sequence) { folded, result ->
            for ((placeholder, original) in result.mapping) {
                assertEquals(
                    original,
                    deanonymize(placeholder, folded.sidecar, folded.ledger).text,
                    "`$placeholder` was minted for `$original` and decodes to something else",
                )
            }
        }
    }

    @Property(tries = 300)
    fun `the anonymized text itself reverses with nothing left behind`(
        @ForAll("invocationSequences") sequence: List<List<Int>>,
    ) {
        fold(sequence) { folded, result ->
            val back = deanonymize(result.text, folded.sidecar, folded.ledger)
            assertEquals(
                emptyList<String>(),
                back.unrestored.map { it.placeholder },
                "the reversal left a placeholder behind in:\n${result.text}",
            )
        }
    }

    /**
     * **The sidecar is cache and the mapping is data**, read from the reversal's end: with the window
     * gone, what is lost is *recovery*, and what is never lost is *correctness*.
     *
     * Both halves are asserted, and the second is the one that matters. A qualified symbol still
     * decodes, because the mapping holds the name as well as the placeholder. Everything else decodes
     * to **nothing** and is reported as evicted — never to a wrong name, and never as *not from this
     * project*, which would send the user looking for a paste that never happened.
     */
    @Property(tries = 300)
    fun `with the window gone a qualified symbol still decodes and nothing decodes wrongly`(
        @ForAll("invocationSequences") sequence: List<List<Int>>,
    ) {
        fold(sequence) { folded, result ->
            val qualified = result.mapping.filterValues { name -> SUBJECTS.single { it.name == name }.qualified }

            for ((placeholder, original) in result.mapping) {
                val back = deanonymize(placeholder, Sidecar.EMPTY, folded.ledger)

                if (placeholder in qualified) {
                    assertEquals(original, back.text, "`$placeholder` is in the mapping and did not decode out of it")
                } else {
                    assertEquals(placeholder, back.text, "`$placeholder` decoded out of a mapping that never held it")
                    assertEquals(
                        listOf(UnrestoredReason.EVICTED),
                        back.unrestored.map { it.reason },
                        "`$placeholder` is a number this project burnt and was not reported as evicted",
                    )
                }
            }
        }
    }

    /** Sequences of selections over [SUBJECTS], each an ordering of a non-empty subset. */
    @Provide
    fun invocationSequences(): Arbitrary<List<List<Int>>> = Arbitraries.integers()
        .between(0, SUBJECTS.size - 1)
        .set()
        .ofMinSize(1)
        .ofMaxSize(SUBJECTS.size)
        .flatMap { selected -> Arbitraries.shuffle(selected.toList()) }
        .list()
        .ofMinSize(1)
        .ofMaxSize(6)

    /**
     * The sequence folded the way the plugin folds it — snapshot in, delta committed, table recorded
     * — with each invocation checked as it lands.
     *
     * The sidecar takes the default bound, and the sequences are far shorter than it, so nothing is
     * evicted while a property is being checked. Eviction is [SidecarHorizonTest]'s subject; what is
     * under test here is that the tables are *complete* while they are held.
     */
    private fun fold(sequence: List<List<Int>>, check: (Run, AnonymizationResult) -> Unit) {
        val folded = Run()
        for (selection in sequence) check(folded, folded.invoke(selection))
    }
}

/** A ledger and a window, folded through a run of invocations exactly as the plugin folds them. */
private class Run {
    var ledger: LedgerSnapshot = LedgerSnapshot.EMPTY
        private set

    var sidecar: Sidecar = Sidecar.EMPTY
        private set

    fun invoke(selection: List<Int>): AnonymizationResult {
        val symbols = selection.map { SUBJECTS[it] }
        val plan = planOf(
            symbols.joinToString(" ") { it.name },
            *symbols.map { it.evidence }.toTypedArray(),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, ledger)

        // The plugin's own order and the plugin's own arguments: the delta is committed and the
        // *whole* table is recorded, ephemeral rows included. A test that recorded only the persisted
        // rows would be testing a store the product does not have.
        ledger += result.delta
        sidecar = sidecar.recording(RecordedInvocation(Instant.now(), result.mapping))
        return result
    }
}

/**
 * One symbol in the generated universe.
 *
 * **Small on purpose**, so that overlap between invocations is guaranteed rather than hoped for, and
 * spanning the shapes a reversal has to reach: qualified and ephemeral, and an accessor whose
 * placeholder is *derived* from its field's rather than allocated — `getField1` is a minted token
 * that is not `prefix + number`, and it is exactly the shape a recogniser written the obvious way
 * misses.
 *
 * None of these names is placeholder-shaped, which keeps the run free of a distraction rather than of
 * a rule: the allocator reserves identifiers surviving into the output, so a fixture named `Type1`
 * would burn numbers for a reason unrelated to what is under test.
 */
private class Subject(val name: String, val qualified: Boolean, val evidence: SymbolEvidence)

private fun subject(
    name: String,
    role: SymbolRole,
    key: String,
    qualified: Boolean,
    accessor: AccessorEvidence? = null,
) = Subject(
    name,
    qualified,
    symbol(name, role, SymbolOrigin.IN_CONTENT, key = key, accessor = accessor, keyIsQualified = qualified),
)

private val SUBJECTS = listOf(
    subject("Payment", SymbolRole.TYPE, "class:com.acme.Payment", qualified = true),
    subject("Refund", SymbolRole.TYPE, "class:com.acme.Refund", qualified = true),
    subject("merchantRef", SymbolRole.FIELD, "field:class:com.acme.Payment#merchantRef", qualified = true),
    subject("settle", SymbolRole.METHOD, "method:class:com.acme.Payment#settle", qualified = true),
    subject(
        "getMerchantRef",
        SymbolRole.METHOD,
        "method:class:com.acme.Payment#getMerchantRef",
        qualified = true,
        accessor = AccessorEvidence("field:class:com.acme.Payment#merchantRef", "merchantRef", "get", fieldKeyIsQualified = true),
    ),
    subject("draft", SymbolRole.LOCAL, "local:file@17", qualified = false),
    subject("state", SymbolRole.FIELD, "field:class:file@40#state", qualified = false),
)
