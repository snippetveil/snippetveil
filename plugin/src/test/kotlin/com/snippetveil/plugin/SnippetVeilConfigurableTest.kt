package com.snippetveil.plugin

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurableEP
import com.intellij.util.ui.EditableModel
import com.snippetveil.core.LedgerDelta
import com.snippetveil.core.MintedName
import java.awt.Container
import javax.swing.AbstractButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JTable

/**
 * **The settings page: where it is registered, what it writes, and what it says.**
 *
 * The half worth testing is not the widgets. It is that the page is *project*-level, that it edits
 * the one persistent setting and nothing else, and that its text carries the fact the ticket put
 * there for auditability — the storage path, in full — while carrying none of the one it deliberately
 * refused, the orphan count.
 *
 * The text assertions read the built component tree rather than the constants behind it, because a
 * label computed correctly and never added to a panel is exactly the failure a constant check cannot
 * see.
 */
class SnippetVeilConfigurableTest : JavaSnippetTestCase() {

    /**
     * **Registered as a `projectConfigurable`, under Tools, and not as an application one.**
     *
     * The one setting the page edits is inherently per-repository — a different employer means a
     * different group id — and a page registered application-wide would offer to write one project's
     * vocabulary into the next one's analysis. Asserted against the loaded descriptor rather than
     * read off the XML, for the reason `ActionRegistrationTest` gives: a `parentId` the platform
     * cannot resolve leaves a page that is registered and unreachable.
     */
    fun `test the page is a project-level page under Tools`() {
        val registered = Configurable.PROJECT_CONFIGURABLE.getExtensions(project)
            .single { it.instanceClass == SnippetVeilConfigurable::class.java.name }

        assertEquals("tools", registered.parentId)
        assertEquals("SnippetVeil", registered.displayName)
        assertEquals(SETTINGS_PAGE_ID, registered.id)

        assertEmpty(Configurable.APPLICATION_CONFIGURABLE.extensionList.filter { it.isSnippetVeil() })
    }

    /**
     * **Exactly one persistent knob plus its prefix list.** The page's whole write surface is
     * [PrefixConfiguration], and this is what says a second setting has not quietly appeared beside
     * it — under the rule that a persistent setting may only ever *increase* anonymization, a new one
     * is a decision and never a detail.
     */
    fun `test the page writes the one knob and its prefix list and nothing else`() {
        assertEquals(
            listOf("autoDetectRootPackage", "internalPrefixes", "thirdPartyPrefixes"),
            InternalLibrarySettings.State::class.java.declaredFields.map { it.name },
        )
    }

    /** What is stored is what the page opens showing, and `Apply` is dark until something is typed. */
    fun `test the page opens on the stored configuration and reports itself unmodified`() {
        InternalLibrarySettings.of(project).configuration =
            PrefixConfiguration(autoDetectRootPackage = false, internalPrefixes = listOf("io.acmecorp"))

        val page = open()

        assertFalse("the page reports a modification nobody made", page.configurable.isModified)
        assertFalse("the stored knob is not what the page shows", page.knob().isSelected)
        assertEquals(listOf("io.acmecorp" to PROJECT_CODE), page.rows())
    }

    /** And an edit reaches the project's own state, through the only writer there is. */
    fun `test applying an edit writes the project's prefix list`() {
        val page = open()

        page.knob().isSelected = false
        page.type("com.acme.oss", THIRD_PARTY)

        assertTrue("the page does not see its own edit", page.configurable.isModified)
        page.configurable.apply()
        assertEquals(
            PrefixConfiguration(autoDetectRootPackage = false, thirdPartyPrefixes = listOf("com.acme.oss")),
            InternalLibrarySettings.of(project).configuration,
        )
        assertFalse("the page still reads as modified after applying", page.configurable.isModified)
    }

    /** `Reset` on the settings dialog throws the edit away rather than half-keeping it. */
    fun `test resetting the page restores what is stored`() {
        val page = open()
        page.type("io.acmecorp", PROJECT_CODE)

        page.configurable.reset()

        assertFalse(page.configurable.isModified)
        assertEmpty(page.rows())
        assertEquals(PrefixConfiguration(), InternalLibrarySettings.of(project).configuration)
    }

    /**
     * A row left blank is nothing, not a prefix that matches every package — the add button puts an
     * empty row on the table, and a user who thinks better of it leaves it there.
     */
    fun `test a blank row is not a prefix`() {
        val page = open()

        page.type("   ", PROJECT_CODE)

        assertFalse("a blank row was read as a prefix", page.configurable.isModified)
    }

    /**
     * **The storage path, in full.** This is the cheapest possible support for auditability: a user
     * checks in two seconds that the mapping is not in `.idea/` and not anywhere Settings Sync
     * collects from, instead of taking our word for it. A truncated or summarised path supports
     * neither check.
     */
    fun `test the mapping's storage path is shown in full`() {
        val shown = open().text()

        assertTrue("the storage path is not on the page: $shown", PlaceholderLedger.storagePath().toString() in shown)
    }

    /** The entry count is this project's, in words that agree with the number. */
    fun `test the entry count is the number of stored placeholders`() {
        PlaceholderLedger.getInstance().commit(
            project,
            LedgerDelta(
                mapOf(
                    "class:com.acme.Payment" to MintedName("Type1", "Payment"),
                    "class:com.acme.Charge" to MintedName("Type2", "Charge"),
                ),
                nextNumber = 3,
            ),
        )

        assertTrue("the entry count is missing", "2 placeholders" in open().text())
    }

    /** And it is read again after the button beside it has emptied the store. */
    fun `test the entry count is re-read after a reset`() {
        PlaceholderLedger.getInstance().commit(
            project,
            LedgerDelta(mapOf("class:com.acme.Payment" to MintedName("Type1", "Payment")), nextNumber = 2),
        )
        val page = open()
        assertTrue("the entry count is missing", "1 placeholder" in page.text())

        MappingReset.reset(project)
        page.configurable.reset()

        assertTrue("the count still reports entries the reset destroyed", "0 placeholders" in page.text())
    }

    /**
     * **The orphan count is deliberately not shown**, and this is what keeps it that way. Orphans are
     * non-actionable by construction — pruning is forbidden and they are retained on purpose — so the
     * only action the number could motivate is the one destructive button on the page. A number whose
     * sole affordance is the button you should not press is worse than no number.
     */
    fun `test nothing on the page counts orphans`() {
        val shown = open().text().lowercase()

        // A negative assertion over a reader that returned nothing would pass over an empty page, so
        // the reader is held to something it must find before it is asked what it must not.
        assertTrue("the page's text was not read at all, so the assertion below means nothing", "placeholder" in shown)
        assertFalse("the page mentions orphans, which are non-actionable by construction", "orphan" in shown)
    }

    /**
     * `Reset Mappings…` is **on this page**, which is one half of a decision whose other half — *and
     * in no menu* — is pinned by `ActionRegistrationTest`'s list of every id this plugin registers.
     * The ellipsis is the platform's convention for an item that opens a dialog, and here it is
     * load-bearing: this one always asks first.
     */
    fun `test the reset button is on the page`() {
        assertTrue("Reset Mappings… is not on the settings page", "Reset Mappings…" in open().clickables())
    }

    /**
     * **The shortcut link, and the id it selects.** The plugin ships no default binding — every
     * combination worth having is taken, differently, across four keymaps — so this link is what turns
     * the absence into a one-click affordance. A stale action id would open Keymap with nothing
     * selected, which is a failure with no symptom.
     */
    fun `test the shortcut link names an action this plugin registers`() {
        assertTrue("Configure shortcut… is not on the settings page", "Configure shortcut…" in open().clickables())
        assertNotNull(
            "the shortcut link points at an action this plugin does not register",
            ActionManager.getInstance().getAction(SHORTCUT_ACTION),
        )
    }

    /** The quiet line at the bottom: the claim, and the document that states its limits. */
    fun `test the page states the no-network fact and links the threat model`() {
        val shown = open().text()

        assertTrue("the no-network fact is missing: $shown", "makes no network calls" in shown)
        assertTrue("the threat model is not linked: $shown", THREAT_MODEL in shown)
    }

    /** A page with its component built, which is the state every assertion above is about. */
    private fun open(): Page = SnippetVeilConfigurable(project).let { Page(it, it.createComponent()) }

    private fun ConfigurableEP<*>.isSnippetVeil(): Boolean =
        listOfNotNull(instanceClass, implementationClass, providerClass).any { it.startsWith("com.snippetveil") }

    /**
     * The page as a user meets it: a configurable and the component it built, read through the
     * widgets rather than through the fields behind them.
     */
    private class Page(val configurable: SnippetVeilConfigurable, private val component: JComponent) {

        /** The one knob. `single` is the assertion: a second tick box on this page is a new setting. */
        fun knob(): JCheckBox = widgets().filterIsInstance<JCheckBox>().single()

        /** The prefix list, as the rows a user would read off it. */
        fun rows(): List<Pair<String, String>> = table().let { table ->
            (0 until table.rowCount).map { row ->
                table.getValueAt(row, 0).toString() to table.getValueAt(row, 1).toString()
            }
        }

        /** Adds a row and fills it in, the way the toolbar's `+` and the two cells do. */
        fun type(prefix: String, treatment: String) {
            val table = table()
            (table.model as EditableModel).addRow()
            val row = table.rowCount - 1
            table.setValueAt(prefix, row, 0)
            table.setValueAt(treatment, row, 1)
        }

        /** Every word the page shows, labels and paragraphs alike. */
        fun text(): String = widgets().joinToString("\n") {
            when (it) {
                is JLabel -> it.text.orEmpty()
                is JEditorPane -> it.text.orEmpty()
                is AbstractButton -> it.text.orEmpty()
                else -> ""
            }
        }

        /** Everything on the page that can be pressed, by its label. */
        fun clickables(): List<String> = widgets().filterIsInstance<AbstractButton>().mapNotNull { it.text }

        private fun table(): JTable = widgets().filterIsInstance<JTable>().single()

        private fun widgets(): List<Container> = descendantsOf(component)

        private fun descendantsOf(root: Container): List<Container> =
            listOf(root) + root.components.filterIsInstance<Container>().flatMap { descendantsOf(it) }
    }
}
