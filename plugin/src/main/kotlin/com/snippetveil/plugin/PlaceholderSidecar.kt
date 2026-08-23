package com.snippetveil.plugin

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.snippetveil.core.RecordedInvocation
import com.snippetveil.core.Sidecar
import java.time.Instant

/**
 * **The last few invocations, whole — so that a reply about a local variable can still be decoded.**
 *
 * Only symbols with a qualified key persist in [PlaceholderLedger], yet an AI's reply talks about
 * locals, parameters and literals constantly: *"the `local43` here is null before you validate."*
 * Without this, the round trip recovers types and fields and drops **exactly the detail a debugging
 * conversation runs on.** So a successful invocation's complete table is recorded here, and reversal
 * asks the sidecar first and the mapping second.
 *
 * What the window *is* — how far back it reaches and what falls off which end — lives in [Sidecar],
 * in `:core`, where a test can fold a thousand invocations through it without an IDE. This class is
 * the store, and the store has two jobs: take a successful invocation's table, and answer what a
 * placeholder stood for.
 *
 * ### The cache tier, which the mapping refuses and this one asks for
 *
 * [PlaceholderLedger] may not go in the cache directory: *Invalidate Caches* is routine IDE hygiene
 * aimed at unrelated indexing problems, and letting it silently destroy every outstanding **stable**
 * placeholder is a trap. Here the same wipe costs **under-recovery** — a placeholder past the
 * horizon stays verbatim in the reply, which is a visible gap and never a wrong name — so the file
 * belongs in the tier the platform is free to delete. `$CACHE_FILE$` is that tier, and it is the one
 * project-level storage that resolves outside the project tree, so this component gets to be
 * project-level where the mapping could not: **the sidecar is cache, the mapping is data**, and the
 * asymmetry falls out of fail-obvious rather than out of taste.
 *
 * It is a **separate holder** from the mapping for that reason and for one more, which is decisive
 * on its own: *Reset Mappings* has to clear the mapping without destroying the org-prefix
 * configuration, and a merged component makes that a hand-written special case instead of a
 * structural fact.
 *
 * ### It allocates nothing
 *
 * There is no counter here and no way to reach one. Numbers come from the single shared counter in
 * the mapping, and this only ever writes down what the engine already decided — so nothing recorded
 * here can collide with anything in the mapping, by construction rather than by care.
 *
 * ### What is at rest here, stated plainly
 *
 * The table holds **the text of every redacted string literal**, which is the most directly
 * sensitive content the product handles and the reason literals are kept out of the durable mapping
 * entirely. It is here because the alternative is a reply quoting `str43` that decodes to nothing —
 * and it is here **bounded**: the age cap in [com.snippetveil.core.SidecarBound] is what keeps last
 * year's strings from sitting on the disk, and it is applied on every read as well as on every
 * write so that it holds for an IDE nobody has pasted from in months. `RoamingType.DISABLED` keeps
 * it off JetBrains' servers like everything else this plugin writes.
 */
@Service(Service.Level.PROJECT)
@State(
    name = "SnippetVeilSidecar",
    storages = [Storage(StoragePathMacros.CACHE_FILE, roamingType = RoamingType.DISABLED)],
)
internal class PlaceholderSidecar : PersistentStateComponent<PlaceholderSidecar.State> {

    /**
     * The serialized bean, which is the shape the platform reads and writes by reflection.
     *
     * Mutable `var`s and `MutableList`s because that is what `XmlSerializer` requires, rather than
     * because anything mutates them in place: every read below copies on the way out and every write
     * replaces, so no caller can reach back through a returned value.
     */
    class State {
        var invocations: MutableList<InvocationEntry> = mutableListOf()
    }

    /**
     * One invocation, and everything it named.
     *
     * @param at when the snippet reached the clipboard, in epoch milliseconds — the wall clock
     *   rather than a counter, because the age cap is a statement about how long text has been on
     *   this disk. A clock the user moves backwards moves the horizon with it, which costs recovery
     *   and can never cost correctness.
     */
    class InvocationEntry {
        var at: Long = 0
        var decodings: MutableList<Decoding> = mutableListOf()
    }

    /** One placeholder, and what it stood for: a real name, or a redacted literal's text. */
    class Decoding {
        var placeholder: String = ""
        var original: String = ""
    }

    /**
     * **Copy-on-write, for the reason [PlaceholderLedger] is.** A record happens on the EDT, a read
     * happens wherever a reversal runs, and the platform reads [getState] on a third thread when it
     * saves — so a bean edited in place would be read while it was half-written. Every write here
     * builds a whole new [State] and swaps it in.
     */
    @Volatile
    private var state = State()

    /**
     * **What the platform writes, trimmed on the way out.** Not a plain field read, and that is the
     * difference between an age cap and a housekeeping habit: this is the one moment the window
     * reaches the disk, so it is the moment that decides what is *at rest* there. An IDE that was
     * pasted from once and left open for a year would otherwise keep that paste's literal text in
     * `cache-state.xml` for the whole year, with the cap passing every test and holding nothing.
     */
    override fun getState(): State = stateOf(trimmed())

    override fun loadState(state: State) {
        this.state = state
    }

    /**
     * Takes what one **successful** invocation named: the whole of
     * [com.snippetveil.core.AnonymizationResult.mapping], ephemeral symbols and literals included.
     *
     * Called at the same moment the mapping is committed, which is the moment the snippet reached the
     * clipboard: an invocation that never got there was never sent, so there is nothing about it to
     * decode.
     *
     * Synchronized so that the read-and-replace is one step. Unlike the mapping's commit there is
     * nothing here for a concurrent invocation to get wrong beyond ordering — the sidecar hands out
     * no numbers, so two invocations recording at once can only interleave two windows, never
     * collide over one.
     */
    @Synchronized
    fun record(table: Map<String, String>) {
        state = stateOf(stored().recording(RecordedInvocation(Instant.now(), table)))
    }

    /**
     * **The window as `:core` reasons about it, trimmed** — which is what a reversal takes, once,
     * rather than asking this class a question per word of an AI's reply.
     *
     * Handing out the value type rather than answering lookups is what keeps the horizon enforced on
     * every read while a reversal stays a pure function: the cap is applied here, on the way out, and
     * what the caller then holds is an immutable window it can ask a thousand times for nothing.
     */
    fun window(): Sidecar = trimmed()

    /**
     * What [placeholder] stood for, or `null` when nothing inside the horizon knows — which a
     * reversal renders by leaving the word alone.
     */
    fun originalOf(placeholder: String): String? = trimmed().originalOf(placeholder)

    /**
     * The window with the bound applied, **and the trimmed window put back** — so that reading it,
     * like writing it and saving it, is a moment the cap is enforced rather than merely respected.
     * Trimming without storing would answer correctly and leave the entries the cap has expired on
     * the disk with their literal text in them, which is the half of the cap that is about retention
     * rather than about answers.
     */
    @Synchronized
    private fun trimmed(): Sidecar = stored().bounded(Instant.now()).also { state = stateOf(it) }

    /** The stored bean as the value type `:core` reasons about. Reads; changes nothing. */
    private fun stored(): Sidecar = Sidecar(
        state.invocations.map { entry ->
            RecordedInvocation(
                Instant.ofEpochMilli(entry.at),
                entry.decodings.associate { it.placeholder to it.original },
            )
        },
    )

    /** [sidecar] as the bean the platform writes. */
    private fun stateOf(sidecar: Sidecar): State = State().also { state ->
        state.invocations = sidecar.invocations.mapTo(mutableListOf()) { invocation ->
            InvocationEntry().also { entry ->
                entry.at = invocation.at.toEpochMilli()
                entry.decodings = invocation.table.mapTo(mutableListOf()) { (placeholder, original) ->
                    Decoding().also {
                        it.placeholder = placeholder
                        it.original = original
                    }
                }
            }
        }
    }

    companion object {
        /** This project's window. A light service, so the platform creates it on first ask. */
        fun getInstance(project: Project): PlaceholderSidecar = project.service()
    }
}
