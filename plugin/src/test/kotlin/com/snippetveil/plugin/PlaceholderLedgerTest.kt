package com.snippetveil.plugin

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import com.snippetveil.core.LedgerDelta
import java.nio.file.Path

/**
 * **The store, and the four properties its location has to have.**
 *
 * The invariants the mapping obeys are `:core`'s and are tested there against a snapshot. Nothing in
 * core can prove the thing this class exists for: that the snapshot **survives being written to a
 * file and read back**, in a place that is not committed to git, not synced to a server and not
 * deleted by routine IDE hygiene. If serialization silently dropped a field, every core property
 * would stay green while stability died in the field.
 */
class PlaceholderLedgerTest : JavaSnippetTestCase() {

    /**
     * **The round trip, which is the whole of what an IDE restart is.**
     *
     * Through `XmlSerializer`, because that is the machinery `@State` itself uses — a hand-written
     * check of the bean's fields would prove that the fields exist rather than that the platform can
     * write and read them.
     */
    fun `test the mapping survives being written out and read back`() {
        val ledger = PlaceholderLedger.getInstance()
        ledger.commit(project, LedgerDelta(mapOf("class:com.acme.Payment" to "Type1"), nextNumber = 9))

        val restarted = restart(ledger)

        val snapshot = restarted.snapshotOf(project)
        assertEquals(mapOf("class:com.acme.Payment" to "Type1"), snapshot.placeholders)
        assertEquals("a burnt number must survive the restart, or it gets handed out twice", 9, snapshot.nextNumber)
    }

    /**
     * **A delta with nothing in it still has to be committed**, because the counter may have moved
     * without any row being written — an anonymous member and a redacted literal both burn a number
     * and leave no entry. A store that skipped an empty delta would hand those numbers to different
     * symbols later, which is the one thing the invariant forbids.
     */
    fun `test committing a delta with no entries still carries the burnt numbers`() {
        val ledger = PlaceholderLedger.getInstance()

        ledger.commit(project, LedgerDelta(emptyMap(), nextNumber = 12))

        assertEquals(12, ledger.snapshotOf(project).nextNumber)
        assertEquals(12, restart(ledger).snapshotOf(project).nextNumber)
    }

    /** Append-only: a later commit adds to what is there and rewrites nothing. */
    fun `test a later commit adds entries and never rewrites one`() {
        val ledger = PlaceholderLedger.getInstance()

        ledger.commit(project, LedgerDelta(mapOf("class:com.acme.Payment" to "Type1"), nextNumber = 2))
        ledger.commit(project, LedgerDelta(mapOf("class:com.acme.Charge" to "Type2"), nextNumber = 3))

        assertEquals(
            mapOf("class:com.acme.Payment" to "Type1", "class:com.acme.Charge" to "Type2"),
            ledger.snapshotOf(project).placeholders,
        )
    }

    /**
     * **One project's vocabulary is never handed to another project's paste.** The component is
     * application-level, so the partition is the thing that makes the data project-scoped — and a
     * partition nothing tests is a partition that quietly stops holding.
     */
    fun `test a project reads its own entries and nobody else's`() {
        val ledger = PlaceholderLedger.getInstance()
        val theirs = PlaceholderLedger.State()
        theirs.projects += PlaceholderLedger.ProjectEntry().also { entry ->
            entry.project = "some-other-project.deadbeef"
            entry.nextNumber = 40
            entry.placeholders += PlaceholderLedger.Naming().also {
                it.key = "class:com.other.Secret"
                it.placeholder = "Type39"
            }
        }
        ledger.loadState(theirs)

        val snapshot = ledger.snapshotOf(project)

        assertEquals(emptyMap<String, String>(), snapshot.placeholders)
        assertEquals("another project's counter must not move this one's", 1, snapshot.nextNumber)
    }

    /**
     * **The location, asserted rather than described.** Four properties, and the reasoning for each
     * is in [PlaceholderLedger] — including why no project-level `@Storage` can hold the last two at
     * once, which is what makes this component application-level with project-partitioned data.
     */
    fun `test the mapping is written outside the project tree, out of the cache directory, and never roams`() {
        val storage = PlaceholderLedger::class.java.getAnnotation(State::class.java).storages.single()

        assertEquals(
            "settings sync would carry the employer's whole vocabulary off the machine",
            RoamingType.DISABLED,
            storage.roamingType,
        )
        assertEquals(
            "an application-level component is what puts the file outside every project tree",
            listOf(Service.Level.APP),
            PlaceholderLedger::class.java.getAnnotation(Service::class.java).value.toList(),
        )
        assertFalse(
            "a storage naming a macro does not land where this test says it lands: ${storage.value}",
            '$' in storage.value,
        )

        // A bare file name on an application-level @State resolves under `$APP_CONFIG$`, which is
        // the IDE's own options directory. Spelled out here because the assertions below are about
        // that path and would otherwise be about nothing.
        val file = Path.of(PathManager.getOptionsPath()).resolve(storage.value)

        assertFalse(
            "the mapping is in the project tree, where .idea/ gets committed and forked: $file",
            file.startsWith(Path.of(project.basePath!!)),
        )
        assertFalse("the mapping is inside .idea/: $file", ".idea" in file.toString().split(java.io.File.separator))
        assertFalse(
            "the mapping is in the system directory, which Invalidate Caches and its neighbours clear: $file",
            file.startsWith(Path.of(PathManager.getSystemPath())),
        )
    }

    /**
     * **Plaintext, deliberately** — greppable and auditable, for a product whose pitch is *audit me*.
     * Encryption is declined because the file holds names already sitting in plaintext `.java` files
     * on the same disk, and a compromised local machine is out of the threat model; the genuine risks
     * are accidental commit and cloud sync, and both are location problems closed by the test above.
     */
    fun `test what the platform writes is plaintext`() {
        val ledger = PlaceholderLedger.getInstance()
        ledger.commit(project, LedgerDelta(mapOf("class:com.acme.Payment" to "Type1"), nextNumber = 2))

        val written = JDOMUtil.write(XmlSerializer.serialize(ledger.state))

        assertTrue("the key is not readable in the file: $written", "class:com.acme.Payment" in written)
        assertTrue("the placeholder is not readable in the file: $written", "Type1" in written)
    }

    /**
     * The same store after an IDE restart: its state written out the way the platform writes it, and
     * read back into a component that has never seen it — which is the stronger half of the round
     * trip, because a component that kept the object it started with would pass whatever the
     * serializer did.
     */
    private fun restart(ledger: PlaceholderLedger): PlaceholderLedger =
        PlaceholderLedger().also { it.loadState(asWrittenAndReadBack(ledger.state)) }
}

/**
 * [state] as the platform would leave it after writing it to disk and reading it back — through
 * `XmlSerializer`, which is the machinery `@State` itself uses.
 *
 * Shared with the end-to-end tests, so that *an IDE restart* means one thing in this module rather
 * than two.
 */
internal fun asWrittenAndReadBack(state: PlaceholderLedger.State): PlaceholderLedger.State =
    XmlSerializer.deserialize(XmlSerializer.serialize(state), PlaceholderLedger.State::class.java)
