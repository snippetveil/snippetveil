package com.snippetveil.plugin

import com.intellij.openapi.Disposable
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.snippetveil.core.LedgerDelta
import com.snippetveil.core.MintedName

/**
 * **The single destructive operation in the product**: what it destroys, what it leaves, and what the
 * user is told before either happens.
 *
 * The three properties are tested separately because they fail separately. *Clears the mapping* is
 * the operation; *clears the sidecar too* is the half a merged-store design would have got for free
 * and a two-store design has to remember; *leaves the prefixes* is the reason the two stores are
 * separate from the settings in the first place, and it is the one a future refactor is most likely
 * to break while every other test stays green.
 */
class MappingResetTest : JavaSnippetTestCase() {

    /** The mapping is gone, entries and counter together — a project that has never been pasted from. */
    fun `test reset clears this project's mapping`() {
        val ledger = PlaceholderLedger.getInstance()
        ledger.commit(project, LedgerDelta(mapOf("class:com.acme.Payment" to MintedName("Type1", "Payment")), nextNumber = 8))

        MappingReset.reset(project)

        val snapshot = ledger.snapshotOf(project)
        assertEmpty(snapshot.placeholders.entries.toList())
        assertEquals("the counter is part of the mapping and goes with it", 1, snapshot.nextNumber)
    }

    /**
     * **And the sidecar with it.** This is the half that is easy to leave behind: the durable mapping
     * is what the page is about, while the window holds the last few invocations' locals, parameters
     * and **the text of every string literal that was replaced** — the most directly sensitive
     * content the product keeps anywhere.
     */
    fun `test reset clears the sidecar, where the literal text is`() {
        val sidecar = PlaceholderSidecar.getInstance(project)
        sidecar.record(mapOf("str1" to "the-production-api-key"))

        MappingReset.reset(project)

        assertEmpty(sidecar.window().invocations)
        assertNull(sidecar.originalOf("str1"))
    }

    /**
     * **The org prefixes survive, and that is why they are a component of their own.**
     *
     * A merged state holder would make this a hand-written special case — *clear these fields and
     * not those* — which is the shape that quietly stops holding. Here it is structural, and this
     * test is what says the structure is still being used that way.
     */
    fun `test reset leaves the internal-library prefixes exactly as they were`() {
        val settings = InternalLibrarySettings.of(project)
        settings.configuration = PrefixConfiguration(
            autoDetectRootPackage = false,
            internalPrefixes = listOf("io.acmecorp"),
            thirdPartyPrefixes = listOf("io.acmecorp.oss"),
        )

        MappingReset.reset(project)

        assertEquals(
            PrefixConfiguration(false, listOf("io.acmecorp"), listOf("io.acmecorp.oss")),
            settings.configuration,
        )
    }

    /**
     * **One project's reset is not every project's.** The mapping is an application-level component
     * with project-partitioned data, so *clear it* has an obvious wrong reading — take the whole bean
     * — that would silently destroy the mapping of every other project on the machine.
     */
    fun `test reset leaves another project's mapping alone`() {
        val ledger = PlaceholderLedger.getInstance()
        val theirs = PlaceholderLedger.State()
        theirs.projects += PlaceholderLedger.ProjectEntry().also { entry ->
            entry.project = "some-other-project.deadbeef"
            entry.nextNumber = 40
            entry.placeholders += PlaceholderLedger.Naming().also {
                it.key = "class:com.other.Secret"
                it.placeholder = "Type39"
                it.original = "Secret"
            }
        }
        ledger.loadState(theirs)

        MappingReset.reset(project)

        assertEquals(1, ledger.state.projects.size)
        assertEquals("some-other-project.deadbeef", ledger.state.projects.single().project)
    }

    /**
     * **The confirmation states the consequence** — not *are you sure*, which asks for a feeling
     * about an operation whose effect is invisible until a reply fails to decode days later.
     *
     * Asserted over the text the platform is actually shown, rather than over the constant, so that a
     * message assembled differently one day is still the message being read here.
     */
    fun `test the confirmation says that existing snippets become undecodable`() {
        val shown = mutableListOf<String>()
        answering(Messages.NO) { shown += it }

        MappingReset.confirmAndReset(project)

        val message = shown.single()
        assertTrue("the consequence is not stated: $message", "undecodable" in message)
        assertTrue("what survives the reset is not stated: $message", "prefixes" in message)
    }

    /** Cancel means nothing happened, which is the only reading a destructive confirmation may have. */
    fun `test answering no leaves the mapping alone`() {
        val ledger = PlaceholderLedger.getInstance()
        ledger.commit(project, LedgerDelta(mapOf("class:com.acme.Payment" to MintedName("Type1", "Payment")), nextNumber = 2))
        answering(Messages.NO) {}

        MappingReset.confirmAndReset(project)

        assertEquals(mapOf("class:com.acme.Payment" to MintedName("Type1", "Payment")), ledger.snapshotOf(project).placeholders)
    }

    /** And yes means it happened — the assertion that keeps the one above from passing vacuously. */
    fun `test answering yes clears the mapping`() {
        val ledger = PlaceholderLedger.getInstance()
        ledger.commit(project, LedgerDelta(mapOf("class:com.acme.Payment" to MintedName("Type1", "Payment")), nextNumber = 2))
        answering(Messages.YES) {}

        MappingReset.confirmAndReset(project)

        assertEmpty(ledger.snapshotOf(project).placeholders.entries.toList())
    }

    /** The next confirmation answers [answer], and hands its text to [read]. Undone at teardown. */
    private fun answering(answer: Int, read: (String) -> Unit) {
        TestDialogManager.setTestDialog(
            TestDialog { message ->
                read(message)
                answer
            },
        )
        disposeOnTearDown(Disposable { TestDialogManager.setTestDialog(TestDialog.DEFAULT) })
    }
}
