package com.snippetveil.plugin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.impl.ApplicationImpl
import com.intellij.openapi.components.State
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import com.snippetveil.core.LedgerDelta
import com.snippetveil.core.LedgerSnapshot
import com.snippetveil.core.MintedName
import java.nio.file.Files
import java.nio.file.Path

/**
 * **The mapping reaching the disk, asserted against the disk.**
 *
 * `PlaceholderLedgerTest` is about *shape* — that a snapshot survives serialization and comes back
 * the same. Nothing in it is about *when* that serialization is written, which is a separate claim
 * and the one the settings page makes out loud.
 *
 * **Every test here reads the real file the settings page names**, and that is the whole point of the
 * class rather than a stylistic preference. The first version of this change asked
 * `SaveAndSyncHandler` for a save and was asserted by a test that watched **the request**: the
 * request was made on every commit, this suite was green, and a sandbox IDE held the mapping
 * unwritten for ten minutes because the task sat unprocessed in the platform's save queue. The
 * second version called `Application.saveSettings` and was silently throttled — see
 * [PlaceholderLedger] on `useSaveThreshold` — and a test watching the request would have stayed green
 * through that too. A test that watches an intention cannot tell either failure from working.
 */
class LedgerDurabilityTest : JavaSnippetTestCase() {

    /**
     * **A commit is on the disk, and nobody had to ask for it.**
     *
     * `Settings > Tools > SnippetVeil` shows the mapping's path in full and invites a suspicious
     * person to go and look. Until this, nothing in the plugin ever caused a write, so what was at
     * the path was whatever the platform's write-behind had got round to — days, in the field, under
     * a page that said `5 placeholders`.
     */
    fun `test a commit is on the disk without anybody asking for it`() {
        val file = PlaceholderLedger.storagePath()
        Files.deleteIfExists(file)

        PlaceholderLedger.getInstance().commit(
            project,
            LedgerDelta(mapOf("class:com.acme.Payment" to MintedName("Type8", "Payment")), nextNumber = 9),
        )

        val written = awaitTheFile(file) { it.nextNumber == 9 }
        assertEquals(MintedName("Type8", "Payment"), written.placeholders["class:com.acme.Payment"])
    }

    /**
     * **And so is a reset**, which is the same claim read from the other end: `Reset Mappings…` tells
     * the user their vocabulary is no longer on the disk, and until the file is rewritten it is still
     * there in full — every row, in plaintext, at a path the page has just shown them.
     */
    fun `test a reset is on the disk, so the vocabulary really has stopped being there`() {
        val file = PlaceholderLedger.storagePath()
        val ledger = PlaceholderLedger.getInstance()
        ledger.commit(project, LedgerDelta(mapOf("class:com.acme.Invoice" to MintedName("Type11", "Invoice")), nextNumber = 12))
        awaitTheFile(file) { it.placeholders.isNotEmpty() }

        ledger.clear(project)

        val emptied = awaitTheFile(file) { it.placeholders.isEmpty() }
        assertEquals("the counter is not rewound by a reset, on the disk any more than in memory", 12, emptied.nextNumber)
    }

    /**
     * **What a restart finds is the file — counter included, and that is the whole of the hazard.**
     *
     * [PlaceholderLedger.ProjectEntry.nextNumber] is persisted *inside the same bean as the rows*, so
     * a commit that never reached the file does not merely lose entries: the counter comes back as it
     * stood at the last write, and numbers already handed out — already pasted into somebody's
     * conversation — are handed out again to different symbols. A reply quoting `Type15` from before
     * the crash then decodes to whatever `Type15` means after it: a **plausible wrong name**, which is
     * the one failure this design refuses outright.
     *
     * Writing on every commit shrinks that window to the length of one write. **It does not close
     * it**, and this is where the residue is written down rather than implied: what comes back is
     * this file, so whatever a crash costs is exactly what is missing from it. Observed for real —
     * a sandbox IDE was killed with nine committed rows unwritten, and the restarted IDE came back
     * with the counter as the file held it.
     *
     * The divergence is spelled with `loadState` rather than a second `commit`, because a commit now
     * writes: what is being modelled is the state a crash leaves behind, not the path that reached it.
     */
    fun `test what a restart finds is the file, so a commit that never reached it takes the counter with it`() {
        val file = PlaceholderLedger.storagePath()
        val ledger = PlaceholderLedger.getInstance()
        ledger.commit(project, LedgerDelta(mapOf("class:com.acme.Ledger" to MintedName("Type14", "Ledger")), nextNumber = 15))
        val saved = awaitTheFile(file) { it.nextNumber == 15 }

        ledger.loadState(
            PlaceholderLedger.State().also { state ->
                state.projects += PlaceholderLedger.ProjectEntry().also { entry ->
                    entry.project = project.locationHash
                    entry.nextNumber = 21
                    entry.placeholders += PlaceholderLedger.Naming().also {
                        it.key = "class:com.acme.Charge"
                        it.placeholder = "Type15"
                        it.original = "Charge"
                    }
                }
            },
        )

        assertEquals("the live ledger is ahead of the file", 21, ledger.snapshotOf(project).nextNumber)
        assertEquals("what a restart reads is the file, and the counter rolls back with the rows", 15, saved.nextNumber)
        assertNull("a commit that never reached the file left a row behind", saved.placeholders["class:com.acme.Charge"])
        assertEquals(MintedName("Type14", "Ledger"), saved.placeholders["class:com.acme.Ledger"])
    }

    /**
     * The file, once it says what [until] asks of it, and **what it said** — so that the assertions
     * after it are about the file this test waited for rather than about whatever it holds a moment
     * later. Every commit in this module now writes, and the suite shares one options directory.
     *
     * A wait rather than a read, because the write is deliberately off the EDT — see
     * [PlaceholderLedger]. **Each test commits something no other test has committed**: the store
     * writes a component only when its state differs from the state it last wrote, so a test
     * re-committing an earlier test's state would wait for a write the store is right to skip, and
     * deleting the file does not change the store's mind — it is remembering, not looking.
     */
    private fun awaitTheFile(file: Path, until: (LedgerSnapshot) -> Boolean): LedgerSnapshot {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            val read = runCatching { if (Files.exists(file)) onDisk(file) else null }.getOrNull()
            if (read != null && until(read)) return read
            Thread.sleep(10)
        }
        throw AssertionError(
            "the mapping never reached $file. " +
                if (Files.exists(file)) "It holds: ${onDisk(file).placeholders} / ${onDisk(file).nextNumber}" else "There is no file at it.",
        )
    }

    /**
     * This project's mapping **as the file [file] holds it** — the platform's own writing, read back
     * out of the file it wrote.
     *
     * Deliberately not `asWrittenAndReadBack`, which is this module's one spelling of *an IDE restart*
     * and takes a bean straight back through `XmlSerializer` in memory. The question here is a
     * different one and only a file can answer it: not *does this bean survive a round trip* but
     * *what would a restart find at the path the settings page names*.
     */
    private fun onDisk(file: Path): LedgerSnapshot {
        val component = JDOMUtil.load(file).getChildren("component")
            .single { it.getAttributeValue("name") == PlaceholderLedger::class.java.getAnnotation(State::class.java).name }
        val state = XmlSerializer.deserialize(component, PlaceholderLedger.State::class.java)
        return PlaceholderLedger().also { it.loadState(state) }.snapshotOf(project)
    }

    /**
     * **Saving is switched on for these tests, because a test application starts with it off.**
     *
     * `ApplicationImpl.saveSettings` does nothing while `saveAllowed` is false — which is what keeps
     * every other suite in this module from writing the developer's sandbox on every commit. This is
     * the class that has to watch the real file, so it turns the platform's own switch on for its own
     * tests and puts it back afterwards.
     */
    override fun setUp() {
        super.setUp()
        (ApplicationManager.getApplication() as ApplicationImpl).isSaveAllowed = true
    }

    /**
     * **The file goes on the way out**, because it is the state here the fixture's own reset cannot
     * reach: the light project and the application services are emptied in `JavaSnippetTestCase.setUp`,
     * and a real file at the shared options path outlives all of it — and this suite's sandbox config
     * directory outlives the whole run.
     */
    override fun tearDown() {
        try {
            (ApplicationManager.getApplication() as ApplicationImpl).isSaveAllowed = false
            Files.deleteIfExists(PlaceholderLedger.storagePath())
        } finally {
            super.tearDown()
        }
    }
}
