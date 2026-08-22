package com.snippetveil.core

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import java.time.Duration
import java.time.Instant

/**
 * **What the horizon is, stated over sequences rather than over one paste.**
 *
 * The two cuts — a count and an age — interact only across a run of invocations, and which run of
 * gaps makes them disagree is exactly the thing nobody guesses in advance: a paste can fall out
 * because fifty followed it, or because thirty days did, and a sequence generator is what puts both
 * cuts under the same fold.
 *
 * Three statements, and none of them re-runs the fold to get its expectation — a property that
 * computed the answer the way the code does would pass by construction:
 *
 *  - **Bounded.** The window never holds more than the count, and never anything past the age cap.
 *  - **A suffix.** Eviction takes from the front. Everything kept is newer than everything dropped,
 *    and the order they were sent in is the order they are held in — nothing is rewritten.
 *  - **Nothing dropped without a reason.** Every eviction is answered by one of the two cuts, which
 *    is the half that stops *keep nothing at all* from satisfying the other two.
 */
class SidecarHorizonTest {

    @Property(tries = 300)
    fun `the window is never larger or older than the bound`(@ForAll("gapSequences") gaps: List<Int>) {
        fold(gaps) { window, sent ->
            assertTrue(
                window.invocations.size <= BOUND.invocations,
                "the window holds ${window.invocations.size} invocations, past a bound of ${BOUND.invocations}",
            )
            assertEquals(
                emptyList<Instant>(),
                window.invocations.map { it.at }.filter { it.isBefore(sent.minus(BOUND.age)) },
                "the window holds an invocation older than the age cap",
            )
        }
    }

    @Property(tries = 300)
    fun `what is kept is the newest, in the order it was sent`(@ForAll("gapSequences") gaps: List<Int>) {
        fold(gaps) { window, _ ->
            assertEquals(
                window.invocations.map { it.at }.sorted(),
                window.invocations.map { it.at },
                "the window is not in the order the invocations were sent",
            )
        }
    }

    @Property(tries = 300)
    fun `nothing is evicted that neither cut asked for`(@ForAll("gapSequences") gaps: List<Int>) {
        val sent = mutableListOf<Instant>()
        fold(gaps) { window, at ->
            sent += at
            val kept = window.invocations.mapTo(HashSet()) { it.at }

            for ((index, dropped) in sent.withIndex()) {
                if (dropped in kept) continue
                val newer = sent.size - index - 1
                assertTrue(
                    dropped.isBefore(at.minus(BOUND.age)) || newer >= BOUND.invocations,
                    "an invocation was evicted with $newer sent after it and no age cap reached: $dropped",
                )
            }
        }
    }

    /**
     * The sequence folded the way the store folds it — one recording per invocation, each checked as
     * it lands, so a failure names the paste that broke the window rather than the run.
     *
     * @param gaps days between one invocation and the next, from a fixed start
     */
    private fun fold(gaps: List<Int>, check: (Sidecar, Instant) -> Unit) {
        var window = Sidecar.EMPTY
        var at = START
        for ((paste, gap) in gaps.withIndex()) {
            at = at.plus(Duration.ofDays(gap.toLong()))
            window = window.recording(RecordedInvocation(at, mapOf("local$paste" to "draft$paste")), BOUND)
            check(window, at)
        }
    }

    /**
     * Gaps in days, over a range that straddles the age cap: a run of zeroes exercises the count cut
     * alone, a single large gap exercises the age cut alone, and the mixtures in between are the
     * cases neither example-shaped test would have thought to write down.
     */
    @Provide
    fun gapSequences(): Arbitrary<List<Int>> = Arbitraries.integers()
        .between(0, BOUND.age.toDays().toInt() * 2)
        .list()
        .ofMinSize(1)
        .ofMaxSize(BOUND.invocations * 3)
}

/**
 * **Small on purpose**, so that both cuts are reached inside a sequence a generator will actually
 * produce. The numbers under test are the rule rather than the constants; the shipped ones are
 * `SidecarBound.DEFAULT` and are pinned by an example in [SidecarTest].
 */
private val BOUND = SidecarBound(invocations = 3, age = Duration.ofDays(5))

private val START: Instant = Instant.EPOCH.plus(Duration.ofDays(1_000))
