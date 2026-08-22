package com.snippetveil.plugin

import com.intellij.openapi.project.getProjectCachePath
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import com.snippetveil.core.LedgerDelta
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * **The store for the recent invocations, and the tier it lives in.**
 *
 * What the window *is* — how far back it reaches, what falls off which end — is `:core`'s and is
 * tested there over generated sequences, without an IDE. What can only be said here is where the
 * file lands: **the cache tier**, which is the treatment the persistent mapping refuses, and which
 * is only safe because losing this can never produce a wrong name.
 */
class PlaceholderSidecarTest : JavaSnippetTestCase() {

    /**
     * The hole the sidecar fills. None of these three has a qualified key, so none of them is in the
     * persistent mapping — and they are what a reply talks about.
     */
    fun `test an invocation's locals params and literals are decodable afterwards`() {
        val sidecar = PlaceholderSidecar.getInstance(project)

        sidecar.record(mapOf("local1" to "draft", "param2" to "amount", "str3" to "acme-live"))

        assertEquals("draft", sidecar.originalOf("local1"))
        assertEquals("amount", sidecar.originalOf("param2"))
        assertEquals("acme-live", sidecar.originalOf("str3"))
    }

    /** A placeholder nothing here has minted decodes to nothing, which reads as *left verbatim*. */
    fun `test a placeholder the window never held decodes to nothing`() {
        PlaceholderSidecar.getInstance(project).record(mapOf("local1" to "draft"))

        assertNull(PlaceholderSidecar.getInstance(project).originalOf("local9"))
    }

    /**
     * The round trip, through `XmlSerializer` because that is the machinery `@State` itself uses. A
     * field the serializer silently drops would leave every in-memory assertion green.
     */
    fun `test the window survives being written out and read back`() {
        val sidecar = PlaceholderSidecar.getInstance(project)
        sidecar.record(mapOf("str1" to "acme-live, and a comma"))

        val restarted = PlaceholderSidecar().also {
            it.loadState(XmlSerializer.deserialize(XmlSerializer.serialize(sidecar.state), PlaceholderSidecar.State::class.java))
        }

        assertEquals("acme-live, and a comma", restarted.originalOf("str1"))
    }

    /**
     * **The bound, at the store.** The window is `:core`'s and is tested there; what this holds is
     * that the store applies it at all rather than growing forever on disk.
     */
    fun `test the oldest invocation is evicted once the window is full`() {
        val sidecar = PlaceholderSidecar.getInstance(project)

        repeat(51) { paste -> sidecar.record(mapOf("local$paste" to "draft$paste")) }

        assertNull("the 51st paste did not evict the first", sidecar.originalOf("local0"))
        assertEquals("draft1", sidecar.originalOf("local1"))
        assertEquals("draft50", sidecar.originalOf("local50"))
    }

    /**
     * **The age cap, at the store**, and read off state that was written a year ago rather than
     * simulated — which is what an IDE that has been open since is holding.
     */
    fun `test an invocation past the age cap is forgotten even with nothing after it`() {
        val sidecar = PlaceholderSidecar.getInstance(project)
        sidecar.loadState(recordedAt(Instant.now().minus(Duration.ofDays(400))))

        assertNull("a year-old literal is still decodable", sidecar.originalOf("str1"))
    }

    /**
     * **And the cap decides what is at rest, not only what is answered.**
     *
     * The window reaches the disk through [PlaceholderSidecar.getState] and nowhere else, so that is
     * where the cap has to bite: an IDE pasted from once and left open for a year would otherwise
     * keep that paste's **literal text** in the cache file for the whole year, with every other
     * assertion about the cap still green. Read as the written text rather than through the API,
     * because *what is on the disk* is the claim.
     */
    fun `test what the platform writes holds nothing past the age cap`() {
        val sidecar = PlaceholderSidecar.getInstance(project)

        sidecar.loadState(recordedAt(Instant.now()))
        assertTrue(
            "the check is vacuous: a literal recorded now is not in the file either",
            "acme-live" in JDOMUtil.write(XmlSerializer.serialize(sidecar.state)),
        )

        sidecar.loadState(recordedAt(Instant.now().minus(Duration.ofDays(400))))
        assertFalse(
            "a year-old literal is still written to the cache file",
            "acme-live" in JDOMUtil.write(XmlSerializer.serialize(sidecar.state)),
        )
    }

    /**
     * **The asymmetry, as a test: the sidecar is cache and the mapping is data.**
     *
     * *Invalidate Caches* is routine IDE hygiene, and it wipes the tier this file lives in. What that
     * costs is under-recovery — a placeholder decodes to nothing, visibly — and what it must never
     * touch is the mapping, where a lost entry would take a *stable* placeholder with it.
     */
    fun `test wiping the cache leaves the persistent mapping intact`() {
        PlaceholderLedger.getInstance().commit(project, LedgerDelta(mapOf("class:com.acme.Payment" to "Type1"), nextNumber = 2))
        PlaceholderSidecar.getInstance(project).record(mapOf("Type1" to "Payment", "local2" to "draft"))

        // What Invalidate Caches does to this file: it is gone, and the component starts from empty.
        PlaceholderSidecar.getInstance(project).loadState(PlaceholderSidecar.State())

        assertNull("the wipe left the sidecar answering", PlaceholderSidecar.getInstance(project).originalOf("local2"))
        val mapping = PlaceholderLedger.getInstance().snapshotOf(project)
        assertEquals(mapOf("class:com.acme.Payment" to "Type1"), mapping.placeholders)
        assertEquals("the wipe moved the counter", 2, mapping.nextNumber)
    }

    /**
     * **The sidecar allocates nothing.** Numbers come from the one shared counter in the mapping, so
     * nothing recorded here can ever collide with anything in it. There is no counter in the sidecar
     * to reach; this is the observable half of that.
     */
    fun `test recording an invocation hands out no numbers`() {
        PlaceholderLedger.getInstance().commit(project, LedgerDelta(mapOf("class:com.acme.Payment" to "Type1"), nextNumber = 2))

        PlaceholderSidecar.getInstance(project).record(mapOf("Type1" to "Payment", "local2" to "draft", "str3" to "acme-live"))

        assertEquals(
            "the sidecar moved the counter, so it allocated something",
            2,
            PlaceholderLedger.getInstance().snapshotOf(project).nextNumber,
        )
    }

    /**
     * **A distinct holder from the mapping, and deliberately so.** They differ on nature — cache
     * against data — and decisively on reset semantics: *Reset Mappings* has to clear the mapping
     * without destroying the org-prefix configuration, and a merged component makes that a
     * hand-written special case instead of a structural fact.
     */
    fun `test the sidecar is a separate state holder from the mapping`() {
        val sidecar = PlaceholderSidecar::class.java.getAnnotation(State::class.java)
        val ledger = PlaceholderLedger::class.java.getAnnotation(State::class.java)

        assertFalse("both holders answer to one @State name", sidecar.name == ledger.name)
        assertFalse(
            "the sidecar shares the mapping's file, so wiping one would take the other",
            sidecar.storages.single().value == ledger.storages.single().value,
        )
        assertEquals(RoamingType.DISABLED, sidecar.storages.single().roamingType)
        assertEquals(RoamingType.DISABLED, ledger.storages.single().roamingType)
    }

    /**
     * **The cache tier, asserted against the platform rather than described.**
     *
     * `$CACHE_FILE$` is the platform's own per-project cache slot, declared deletable at any time —
     * which is exactly the treatment this file may have and the mapping may not. Named by the
     * platform's constant rather than spelled out, and then resolved through the project's own
     * storage manager, because *the decision is the property, not the spelling*.
     */
    fun `test the window is written to the project's cache file, which Invalidate Caches clears`() {
        val storage = PlaceholderSidecar::class.java.getAnnotation(State::class.java).storages.single()

        assertEquals(StoragePathMacros.CACHE_FILE, storage.value)
        assertEquals(
            "a project-level component is what scopes the window to one project without partitioning it",
            listOf(Service.Level.PROJECT),
            PlaceholderSidecar::class.java.getAnnotation(Service::class.java).value.toList(),
        )

        // `$CACHE_FILE$` resolves to `<system>/projects/<hash>/cache-state.xml`. Spelled out here
        // because the assertions below are about that path and would otherwise be about nothing —
        // and the step of it this test can hold the platform to rather than assume is the tier: the
        // platform's own per-project cache directory, which is the thing Invalidate Caches clears.
        //
        // Asked of the platform through the project cache path rather than by expanding the macro
        // through the project's store, whose accessor is spelled in a different package at the
        // floor than at latest stable — and both are versions the CI matrix runs this at.
        val tier = project.getProjectCachePath("projects")

        assertTrue(
            "the project cache tier is not under the system directory, so Invalidate Caches would leave it: $tier",
            tier.startsWith(Path.of(PathManager.getSystemPath())),
        )
        assertFalse(
            "the project cache tier is in the project tree, where .idea/ gets committed and forked: $tier",
            tier.startsWith(Path.of(project.basePath!!)),
        )
        assertFalse(
            "the mapping shares the cache tier the sidecar asked for, so one wipe would take both",
            PlaceholderLedger::class.java.getAnnotation(State::class.java).storages.single().value == storage.value,
        )
    }

    /** One invocation naming one literal, sent at [at] — the shape an IDE that has been open a long time is holding. */
    private fun recordedAt(at: Instant) = PlaceholderSidecar.State().also { state ->
        state.invocations += PlaceholderSidecar.InvocationEntry().also { entry ->
            entry.at = at.toEpochMilli()
            entry.decodings += PlaceholderSidecar.Decoding().also {
                it.placeholder = "str1"
                it.original = "acme-live"
            }
        }
    }
}
