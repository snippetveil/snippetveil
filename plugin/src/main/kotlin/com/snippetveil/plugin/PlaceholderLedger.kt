package com.snippetveil.plugin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.ThreeState
import com.snippetveil.core.LedgerDelta
import com.snippetveil.core.LedgerSnapshot
import com.snippetveil.core.MintedName
import com.snippetveil.core.plus
import java.nio.file.Path

/**
 * **What each of a project's symbols has been called, kept between invocations and across restarts.**
 *
 * A placeholder is stable: `CustomerService` is `Type1` today, tomorrow and after the IDE has been
 * shut down in between. The deciding argument is fidelity rather than privacy — the common workflow
 * is a conversation, and fresh-per-invocation numbering makes the second paste contradict the first.
 * The invariants themselves live in `:core`, next to [LedgerDelta], where a test can reach them
 * without an IDE. This class is the store, and the store has exactly two jobs: hand out the snapshot
 * an invocation starts from, and take the delta a *successful* invocation produced.
 *
 * ### Where the file lives, and why that is not the obvious spelling
 *
 * The mapping has to satisfy four properties at once, and the ticket that specified it left the exact
 * `@Storage` open with the instruction to *verify whichever spelling actually achieves it*. Verified
 * against the platform, they are:
 *
 *  1. **Outside the project tree, never `.idea/`.** `.idea/` is routinely committed, and a mapping
 *     there publishes the project's domain vocabulary into git history and every fork.
 *  2. **Never roaming.** Settings Sync copies a roamable `@State` to JetBrains' servers, and this
 *     file is the densest collection of employer vocabulary the product holds.
 *  3. **Not the cache directory.** *Invalidate Caches* is routine IDE hygiene aimed at unrelated
 *     indexing problems, and letting it silently destroy every outstanding reverse mapping is a trap.
 *  4. **Project-scoped.** One project's vocabulary must never be handed to another project's paste.
 *
 * **No project-level `@Storage` satisfies (1) and (3) together.** Every project-level storage
 * resolves inside `.idea/` — `$PROJECT_FILE$`, `$PROJECT_CONFIG_DIR$`, `$WORKSPACE_FILE$` and
 * `$PRODUCT_WORKSPACE_FILE$` all do — with exactly one exception, `$CACHE_FILE$`, which resolves to
 * `<system>/projects/<hash>/cache-state.xml`: outside the tree, and the platform's own cache slot,
 * declared deletable at any time. That is (1) at the cost of (3).
 *
 * So the component is **application-level and the data is project-partitioned**: the file lands in
 * the IDE config directory, which is neither the project tree nor the cache, and every entry is filed
 * under the project it belongs to. What that gives up is the *component* being project-level; what
 * would otherwise be given up is a reverse mapping a routine Invalidate Caches destroys, and that is
 * the failure a user actually experiences. The reason project-level was asked for in the first place
 * — *application-level settings roam* — is closed directly and checkably by [RoamingType.DISABLED],
 * which `NoPersistentStateIsRoamableTest` holds as an absolute rule. Roaming off also keeps the file
 * out of **Export Settings**, which collects roamable components only.
 *
 * ### `useSaveThreshold = NO`, which is the price of (2)
 *
 * **A non-roamable component is written by an ordinary settings save at most once every five
 * minutes.** `NOT_ROAMABLE_COMPONENT_SAVE_THRESHOLD` is the platform's own throttle and it is aimed
 * at exactly this shape of component: roaming off means nothing else is waiting on the file, so the
 * platform spares the disk. Turning roaming off for the reason above therefore bought a five-minute
 * staleness nobody asked for, and it is invisible — the save runs, the component is skipped, and
 * nothing is logged above debug.
 *
 * That is not survivable for a file whose path is on a settings page under a live count, and it is
 * not survivable for a counter a crash can rewind. `ThreeState.NO` opts out of the throttle and
 * nothing else: the storage stays exactly as non-roaming, out-of-tree and out-of-cache as it was.
 * **It was found in a sandbox IDE and not in review** — see [askTheApplicationToWriteItsSettings] —
 * and `LedgerDurabilityTest` fails without it.
 *
 * ### Plaintext, deliberately
 *
 * The file holds names that are already sitting in plaintext `.java` files on the same disk, and **a
 * compromised local machine is out of the threat model.** The genuine risks are accidental commit
 * and cloud sync, and both are *location* problems, closed above. Encryption would cost
 * auditability — and greppable, readable state is worth more to a product whose pitch is *audit me*
 * than a lock on a door standing one directory from an open one.
 *
 * ### Append-only, unbounded, and no pruning
 *
 * Nothing here rewrites or deletes an entry; see [plus] for why a rename adds a key rather than
 * moving one. There is no expiry and no LRU, and pruning must not be re-proposed: its apparent
 * benefit — keeping numbers small — does not materialise, because recycling a number is forbidden.
 * Pruning would bound disk while the numbers climbed anyway, and pay for it in *silent instability*,
 * which is the exact failure stability was chosen to avoid. The disk bound was never needed: an entry
 * exists only for a symbol that was actually pasted.
 */
@Service(Service.Level.APP)
@State(
    name = "SnippetVeilPlaceholders",
    storages = [Storage(LEDGER_FILE, roamingType = RoamingType.DISABLED, useSaveThreshold = ThreeState.NO)],
)
internal class PlaceholderLedger : PersistentStateComponent<PlaceholderLedger.State> {

    /**
     * The serialized bean, which is the shape the platform reads and writes by reflection.
     *
     * Mutable `var`s and `MutableList`s because that is what `XmlSerializer` requires, rather than
     * because anything mutates them in place: every read below copies on the way out and every write
     * replaces, so no caller can reach back through a returned value.
     */
    class State {
        var projects: MutableList<ProjectEntry> = mutableListOf()
    }

    /**
     * One project's mapping.
     *
     * @param project the project's location hash, which is what tells two open projects apart and
     *   survives a restart. It is the platform's own identity for a project and it reads as
     *   `<name>.<hash of path>`, so a person opening the file can see whose entries these are —
     *   which is the point of keeping the file greppable at all.
     *
     *   **The stated cost of not being project-level storage**: the hash is derived from the
     *   project's path, so moving or renaming the project directory reads as a new project and the
     *   mapping starts empty. A file in `.idea/` would have travelled with the tree. What that costs
     *   is stability across a move — the old entries are still there, under the old hash, and
     *   nothing decodes to the wrong name, because a fresh mapping never reuses the old numbers for
     *   the old symbols; it starts again from 1 in a namespace of its own. It is a real loss and it
     *   is written down here rather than discovered.
     * @param nextNumber where this project's counter stands. **Persisted alongside the entries and
     *   not derived from them**, because numbers burnt by symbols that were never written down are
     *   exactly the ones no entry records — and re-deriving would hand them out a second time.
     * @param stems **the words this project has minted placeholders under**, and the one thing in
     *   this file that is not filed under a key. It is not a mapping row and it is not a tier of
     *   one: a stem stands for no symbol and carries no number, and it is here because the
     *   placeholder that most needs recognising later is a renamed **local's**, whose key is exactly
     *   the kind this file refuses to hold. See [com.snippetveil.core.LedgerDelta.mintedStems] for
     *   what it buys, and [clear] for the one operation that takes it away.
     *
     *   Empty on a row written before this field existed, and that is the whole of the migration:
     *   such a project goes back to not recognising a renamed placeholder past the horizon, which is
     *   where it already was. Under-recovery, never a wrong name.
     */
    class ProjectEntry {
        var project: String = ""
        var nextNumber: Int = LedgerSnapshot.EMPTY.nextNumber
        var placeholders: MutableList<Naming> = mutableListOf()
        var stems: MutableList<String> = mutableListOf()
    }

    /**
     * One symbol, and what it was called.
     *
     * @param original the symbol's own name, which is what makes this file a **reverse** mapping
     *   rather than only a forward one: `De-anonymize Clipboard` arrives holding `Type1` and has no
     *   idea what key it was filed under. It puts nothing new on the disk — [key] is derived from
     *   the fully-qualified name and already contains it — and what it buys is that reversal reads a
     *   stated fact instead of parsing a key whose spelling belongs to the plan builder. See
     *   [com.snippetveil.core.MintedName].
     *
     *   **Empty on a row written before this field existed**, and that is the whole of the migration:
     *   such a row still names its placeholder, so it still keeps the number out of circulation, and
     *   what it costs is one name that decodes to nothing. Under-recovery, never a wrong name.
     */
    class Naming {
        var key: String = ""
        var placeholder: String = ""
        var original: String = ""
    }

    /**
     * **Copy-on-write, and that is not a style choice.** [snapshotOf] is read on the background
     * thread the analysis runs on, [commit] is written on the EDT, and the platform reads
     * [getState] on a third when it saves — so a bean that was edited in place would be read while
     * it was half-written. A commit builds a whole new [State] and swaps it in, which makes every
     * value ever handed out one nobody will touch again.
     */
    @Volatile
    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    /** What [project] has already handed out, as the value type `:core` reads. */
    fun snapshotOf(project: Project): LedgerSnapshot = snapshotOf(state, project)

    /**
     * Takes what one invocation produced, for [project].
     *
     * **Always call this, even for a delta with no entries in it.** A delta whose map is empty may
     * still have moved the counter — an anonymous member and a redacted literal both burn a number
     * without leaving a row — and a caller that skipped the commit would hand those numbers out
     * again to different symbols later. That is why [LedgerDelta] has no `isEmpty`.
     *
     * Synchronized so that the read-and-replace here is one step. **That is all it protects, and it
     * is not what keeps two concurrent invocations from being handed the same number** — the window
     * that matters opens when an invocation reads its snapshot, long before it gets here, and it is
     * closed in `CopyAnonymizedAction.deliver` by reading the ledger again on the EDT and re-running
     * the engine if it moved. This lock is what makes that read-again see a whole ledger rather than
     * half of one.
     *
     * **And it asks for the file to be written**, rather than leaving that to the platform's own
     * schedule: a number this hands out has already been pasted into somebody's conversation by the
     * time it gets here, so a commit that is still only in memory is one a crash turns into a
     * plausible wrong name. See [askTheApplicationToWriteItsSettings].
     */
    @Synchronized
    fun commit(project: Project, delta: LedgerDelta) {
        val current = state
        val committed = snapshotOf(current, project) + delta

        val entry = ProjectEntry().also {
            it.project = project.locationHash
            it.nextNumber = committed.nextNumber
            it.stems = committed.mintedStems.toMutableList()
            it.placeholders = committed.placeholders.mapTo(mutableListOf()) { (key, minted) ->
                Naming().also { naming ->
                    naming.key = key
                    naming.placeholder = minted.placeholder
                    naming.original = minted.original
                }
            }
        }

        state = State().also {
            it.projects = current.projects.filterTo(mutableListOf()) { other -> other.project != entry.project }
            it.projects += entry
        }

        askTheApplicationToWriteItsSettings()
    }

    /**
     * **Forgets every name [project] has been handed — the one operation that is not append-only.**
     *
     * Nothing else here deletes a row. What the user is buying is that the vocabulary stops being on
     * the disk, and what they are told they are paying is that outstanding snippets stop decoding;
     * both are true of the rows, which is why the rows go. **The first of those is a claim about a
     * file**, so this asks for the file to be rewritten rather than leaving the old one lying there
     * until the platform gets round to it — see [askTheApplicationToWriteItsSettings].
     *
     * **The stems go with the rows, and the counter does not.** A stem is a word the user typed to
     * describe one of their own symbols, so it is vocabulary in exactly the sense this button exists
     * to remove — where the counter it keeps is one integer that names nobody. The cost is stated
     * rather than discovered and is the narrow half of the same coin: after a reset, a reply holding
     * only renamed placeholders matches nothing and `De-anonymize Clipboard and Paste` writes it,
     * where one holding any default-stemmed placeholder is still refused because the counter
     * survived. Keeping the words would close that and would keep the vocabulary the user just asked
     * this project to forget.
     *
     * **The counter stays where it stood, and that is not a leftover.** The invariant the whole
     * design rests on is that no two symbols in a project's history ever render to the same
     * placeholder — a number that comes back into circulation makes an old `Type1` stand for a new
     * symbol, and then a reply pasted from last week's conversation decodes to a **plausible wrong
     * name**, which is the one failure this product refuses outright. Restarting at 1 would
     * manufacture exactly that, days later and invisibly. Keeping the counter costs a larger number
     * on the next paste and keeps nothing of the vocabulary: it is one integer, and it names nobody.
     * The ticket asked for the mapping to be cleared and said nothing about the counter; this is the
     * reading that does not contradict the mapping's own rule.
     *
     * **Only this project's entry.** The component is application-level and the data is partitioned,
     * so a reset that took the whole bean would silently destroy the mapping of every other project
     * on the machine.
     */
    @Synchronized
    fun clear(project: Project) {
        val current = state
        // The counter and nothing else: a fresh entry has no rows and no stems, which is the whole
        // of what this operation is. It is spelled as a new entry rather than as two clears so that
        // a field added to [ProjectEntry] later is dropped by default rather than kept by omission.
        val emptied = ProjectEntry().also {
            it.project = project.locationHash
            it.nextNumber = snapshotOf(current, project).nextNumber
        }

        state = State().also {
            it.projects = current.projects.filterTo(mutableListOf()) { other -> other.project != emptied.project }
            it.projects += emptied
        }

        askTheApplicationToWriteItsSettings()
    }

    /**
     * **Puts the mapping on the disk now, rather than whenever the platform gets round to it.**
     *
     * A `@State` component is written when the platform decides to write it, and that decision is
     * ordinary write-behind: an IDE four days into a session was observed with nothing in its whole
     * `options/` directory newer than its first minute. Two claims this file makes are false for as
     * long as that window is open, and they fail in opposite directions.
     *
     *  1. **The settings page shows this file's path and invites a sceptic to go and look**, which is
     *     the only auditability evidence the product hands over. A page reading `5 placeholders` over
     *     a path holding no file at all reads as *it is storing this somewhere it is not telling me*
     *     — the exact suspicion the line exists to defuse, and it fails worst for the most suspicious
     *     user, who is the only user the line is for.
     *  2. **[ProjectEntry.nextNumber] is persisted in the same bean as the rows**, so a session that
     *     ends without a write — a crash, a force-quit, an OOM kill — does not merely lose entries:
     *     the counter rolls back with them, and numbers already handed out and already pasted into a
     *     conversation get handed out a second time to different symbols. A reply quoting `Type3`
     *     from before the crash then decodes to whatever `Type3` means after it, which is a
     *     **plausible wrong name** — the one failure this whole design refuses outright, and the one
     *     it errs towards under-recovery everywhere else to avoid.
     *
     * **What this closes and what it does not.** The window becomes the length of one file write
     * instead of the platform's own schedule; a crash inside that write still loses the commit, and
     * `LedgerDurabilityTest` is where that residue is written down rather than implied.
     *
     * **The application is asked to write its settings — and two other spellings were tried in a
     * real IDE before this one, which is the only reason this one is here.**
     *
     * `SaveAndSyncHandler.scheduleSave` is the obvious spelling and it is **actively harmful**. The
     * task goes onto the platform's shared save queue, where it was never processed: the mapping sat
     * unwritten for ten minutes, **and the IDE's own save on frame deactivation stopped happening
     * too**, because `scheduleSave` calls `requestSave` only when `addToSaveQueue` returns true and
     * that method dedups against whatever is already queued. One task of ours in that queue is enough
     * to make the platform's settings save a no-op. A save requested wrongly does not merely fail to
     * help; it starves the scheduler.
     *
     * [com.intellij.openapi.application.Application.saveSettings] goes straight to the store instead,
     * with no queue in between — and on its own it did nothing either, because a non-roamable
     * component is throttled to one write every five minutes. That is the `useSaveThreshold` argued
     * above; **the save call and the opt-out are one fix in two places**, and either alone leaves the
     * file stale.
     *
     * Public API throughout, which is not a preference: `verifyPlugin` fails this build on internal
     * API, and the narrower `IComponentStore.saveComponent` — which writes this one component and
     * would have been cheaper — is internal.
     *
     * **Off the EDT**, because [commit] runs on it inside the user's copy gesture — see `deliver` —
     * and this is a file write; a whole-application settings save was measured at ~3s in a sandbox.
     * Two invocations at once ask twice; each save reads [getState] when it runs rather than when it
     * was asked for, and the bean is copy-on-write, so each writes a whole ledger and the later
     * commit is the one that survives.
     *
     * **What it reaches is wider than this file**, which is the accepted cost of a public API: every
     * dirty application component is written with ours, the same work the IDE does whenever it loses
     * focus. Project settings are not this save's business, so the sidecar keeps the platform's
     * ordinary write-behind — right for the cache tier, where losing a row costs recovery and never
     * correctness.
     */
    private fun askTheApplicationToWriteItsSettings() {
        val application = ApplicationManager.getApplication()
        application.executeOnPooledThread { application.saveSettings() }
    }

    /**
     * What [project] had been handed as of [state] — read against a stated version of the bean rather
     * than against the field, so that a commit reads and replaces one and the same ledger.
     */
    private fun snapshotOf(state: State, project: Project): LedgerSnapshot {
        val entry = state.projects.firstOrNull { it.project == project.locationHash }
            ?: return LedgerSnapshot.EMPTY
        return LedgerSnapshot(
            entry.placeholders.associate { it.key to MintedName(it.placeholder, it.original) },
            entry.nextNumber,
            entry.stems.toSet(),
        )
    }

    companion object {
        /** The one store. A light service, so the platform creates it on first ask. */
        fun getInstance(): PlaceholderLedger = ApplicationManager.getApplication().service()

        /**
         * **The file itself, in full — which the settings page shows and does not summarise.**
         *
         * Showing the path is the cheapest possible support for auditability: the four properties
         * argued above are claims about *where this file is*, and a suspicious person can check all
         * of them in two seconds by reading the path — not in `.idea/`, not in the cache directory,
         * not anywhere Settings Sync collects from. A product whose pitch is *audit me* hands over
         * the evidence rather than the assurance.
         *
         * Derived from [LEDGER_FILE], which is the same constant the `@Storage` above names, so the
         * page cannot show a path the platform is not writing to. A bare file name on an
         * application-level `@State` resolves under `$APP_CONFIG$`, which is
         * [PathManager.getOptionsPath]; `PlaceholderLedgerTest` holds the platform to that step
         * rather than assuming it.
         *
         * **That the path is right and that a file is at it are two claims**, and the second one is
         * [askTheApplicationToWriteItsSettings]'s: a path resolving correctly is worth nothing to somebody who
         * looks there and finds nothing.
         */
        fun storagePath(): Path = Path.of(PathManager.getOptionsPath()).resolve(LEDGER_FILE)
    }
}

/**
 * The mapping's file name, named once: the `@Storage` above and [PlaceholderLedger.storagePath] are
 * two readings of one fact, and a settings page displaying a path the platform does not write to
 * would be worse than showing none at all.
 */
private const val LEDGER_FILE = "snippetveil-placeholders.xml"
