package com.snippetveil.core

import java.time.Duration
import java.time.Instant

/**
 * **The complete symbol table of one invocation, kept whole.**
 *
 * [AnonymizationResult.mapping] is what an invocation produced and it is all of it — the qualified
 * symbols that go in the persistent mapping, the ephemeral ones that do not, and the redacted
 * literals that have no key to be written down under. The sidecar takes it verbatim rather than
 * filtering it: the persistent mapping is keyed by symbol key and this is keyed by placeholder,
 * which is the direction a reply is read in, so the overlap is not duplication so much as the same
 * fact reachable from the side a reader arrives from.
 *
 * @param at when the snippet reached the clipboard. Only the age cap reads it, and it is passed in
 *   rather than taken from a clock so that everything here stays a value — the engine has no clock,
 *   and eviction that could not be tested without waiting is eviction nobody tests.
 * @param table placeholder -> what it stands for: a real name, or the text of a redacted literal.
 */
class RecordedInvocation(val at: Instant, val table: Map<String, String>)

/**
 * **The last N invocations, whole — so that a reply about a local variable can still be decoded.**
 *
 * Only qualified keys survive in the persistent mapping, yet a reply talks about locals, parameters
 * and literals constantly: *"the `local43` here is null before you validate."* Without this, the
 * round trip recovers types and fields and drops **exactly the detail a debugging conversation runs
 * on.** So the sidecar holds every placeholder a recent invocation minted, and reversal consults it
 * **first, then the persistent mapping.**
 *
 * ### It is cache; the mapping is data
 *
 * Losing this degrades to **under-recovery** — a placeholder past the horizon stays verbatim in the
 * reply, which is a visible gap — and it can **never** produce a wrong name. That is what makes it
 * safe to keep in the IDE's cache directory and to let *Invalidate Caches* wipe it, the exact
 * treatment the persistent mapping refuses. The asymmetry is not a preference: it falls out of
 * fail-obvious, and it is why the two are separate holders rather than one file with two halves.
 *
 * **Never a wrong name** is a property of the counter rather than of anything here. A number is
 * burnt when it is handed out and no two symbols in the project's history ever share a placeholder,
 * so a row that survives eviction still says the only thing that row could ever have said. Eviction
 * removes rows; it cannot re-point one.
 *
 * ### It allocates nothing
 *
 * There is no counter in this file and no way to reach one. Numbers come from the single shared
 * counter in the persistent mapping, and the sidecar only ever writes down what the engine already
 * decided — so nothing recorded here can collide with anything in the mapping, by construction
 * rather than by care.
 */
class Sidecar(val invocations: List<RecordedInvocation>) {

    /**
     * What [placeholder] stood for, or `null` when nothing inside the horizon knows — which is the
     * answer a reversal renders as *left verbatim*, never as a guess.
     *
     * Newest first. Two invocations naming one placeholder differently is a thing the shared counter
     * makes impossible, so the order is a tie-break for a tie that cannot happen; it is stated this
     * way round because *the most recent thing we know* is the reading a person would expect if it
     * ever did.
     */
    fun originalOf(placeholder: String): String? =
        invocations.asReversed().firstNotNullOfOrNull { it.table[placeholder] }

    /**
     * This sidecar with [invocation] added and [bound] applied — the one place a recording happens,
     * so the window cannot grow by a path that forgot to trim it.
     *
     * The new invocation's own instant is *now*: it is the moment the snippet reached the clipboard,
     * and taking it from the record rather than from a clock keeps this a function of its arguments.
     */
    fun recording(invocation: RecordedInvocation, bound: SidecarBound = SidecarBound.DEFAULT): Sidecar =
        Sidecar(invocations + invocation).bounded(invocation.at, bound)

    /**
     * This sidecar with everything past [bound] dropped, as of [now].
     *
     * Applied on **every** read as well as on every write, so that the horizon is one fact rather
     * than two: a window that was only trimmed when something was added would go on answering out of
     * entries older than the cap for as long as nobody pasted anything, and would go on holding
     * their literal text on disk for just as long.
     *
     * Ordering the two rules does not matter — invocations arrive in time order, so both cuts take a
     * suffix — and they are written age-then-count because that is the order they are argued in.
     */
    fun bounded(now: Instant, bound: SidecarBound = SidecarBound.DEFAULT): Sidecar {
        val horizon = now.minus(bound.age)
        return Sidecar(
            invocations
                .filterNot { it.at.isBefore(horizon) }
                .takeLast(bound.invocations),
        )
    }

    companion object {
        /** Nothing has been sent from this project yet. */
        val EMPTY: Sidecar = Sidecar(emptyList())
    }
}

/**
 * **How far back the sidecar remembers.** A default rather than a decision — the spec fixed the
 * shape and left the numbers to be tuned here — and deliberately not a setting: a knob on this one
 * trades recovery against retention, and neither side of that trade is a thing a user can observe
 * until they are already past the horizon.
 *
 * @param invocations how many pastes are kept, oldest evicted first. Fifty is a working session's
 *   worth of pastes and then some, which is the span a conversation actually reaches back over.
 * @param age how long a paste is kept regardless of how few have followed it. The count bound alone
 *   is no bound at all for someone who pastes twice a month, and this file is the one durable place
 *   **literal text** comes to rest — the most directly sensitive content the product handles. Thirty
 *   days is long enough that a conversation resumed after a holiday still decodes, and short enough
 *   that last year's strings are not still on the disk.
 */
class SidecarBound(val invocations: Int, val age: Duration) {

    companion object {
        /** The spec's default: ~50 invocations, with an age cap. */
        val DEFAULT: SidecarBound = SidecarBound(invocations = 50, age = Duration.ofDays(30))
    }
}
