package com.snippetveil.plugin

import com.intellij.openapi.keymap.impl.ui.KeymapPanel
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.EditableModel
import com.intellij.util.ui.JBUI
import javax.swing.DefaultCellEditor
import javax.swing.JComponent
import javax.swing.table.AbstractTableModel

/**
 * **`Settings > Tools > SnippetVeil` — and it is worth recording how small it is.**
 *
 * The rule governing every setting this product will ever have is that *persistent settings may only
 * ever increase anonymization; any reduction is per-invocation and visible in the preview*, and this
 * page is what that rule looks like once it has finished eating the knob list. Comment retention went
 * per-invocation, the per-item preserve stayed in the preview, and **exactly one knob survives as
 * persistent state — the one that only ever increases anonymization.** What is left besides it is
 * not configuration at all: two facts about the mapping, a button, and a link.
 *
 * ### Project-level
 *
 * The one setting it edits is inherently per-repository — a different employer means a different
 * group id — so the page is registered as a `projectConfigurable` rather than an application one.
 * A single global page would offer to write one project's vocabulary into the next one's analysis.
 *
 * ### The path is shown, in full
 *
 * **The cheapest possible support for auditability.** The whole claim rests on evidence a suspicious
 * person can check, and a user can verify in two seconds that the mapping is not in `.idea/` and not
 * roaming — instead of taking our word for it. See [PlaceholderLedger.storagePath].
 *
 * ### The orphan count is deliberately not shown
 *
 * Orphans — entries for symbols that no longer exist — are non-actionable **by construction**, since
 * pruning is forbidden and they are retained on purpose. The only action the number could motivate is
 * `Reset Mappings…`, the single destructive operation in the product. **A number whose sole
 * affordance is the button you should not press is worse than no number**, so it is not computed and
 * not displayed, and a test reads this page's own text to keep it that way.
 */
internal class SnippetVeilConfigurable(private val project: Project) : SearchableConfigurable {

    /**
     * The knob. Its label says what it *does* rather than naming the heuristic, because the person
     * reading it is deciding about their own repository and not about our implementation.
     */
    private val autoDetect = JBCheckBox("Treat libraries under this project's root package as project code")

    private val prefixes = PrefixTableModel()

    /**
     * The list beneath the knob, as **one** table rather than an added list and a removed list.
     *
     * The two directions are one decision — *this package is ours* or *this package is not* — and a
     * user who has to work out which of two boxes a prefix belongs in is being handed our data model.
     * `terminateEditOnFocusLost` is what makes a half-typed prefix count as typed: without it, a
     * click straight on `Apply` leaves the cell editor holding the text and the page reads as
     * unmodified.
     */
    private val table = JBTable(prefixes).also {
        it.putClientProperty("terminateEditOnFocusLost", true)
        it.preferredScrollableViewportSize = JBUI.size(400, 120)
        it.columnModel.getColumn(TREATMENT).cellEditor = DefaultCellEditor(ComboBox(arrayOf(PROJECT_CODE, THIRD_PARTY)))
    }

    /**
     * How many placeholders this project has been handed — **an entry count and nothing beside it.**
     *
     * Rebuilt rather than bound, because the one thing that changes it while the page is open is the
     * button two rows down, and a stale number under a button that just emptied the store is exactly
     * the kind of small wrongness this product cannot afford.
     */
    private val entries = JBLabel()

    /** The file, in full, and selectable so that a sceptic can paste it into a terminal. */
    private val location = JBLabel().also { it.setCopyable(true) }

    override fun getId(): String = SETTINGS_PAGE_ID

    override fun getDisplayName(): String = "SnippetVeil"

    override fun createComponent(): JComponent {
        reset()
        return panel {
            group("Internal libraries") {
                row { cell(autoDetect) }
                row {
                    cell(
                        ToolbarDecorator.createDecorator(table)
                            .setAddAction { addPrefix() }
                            .disableUpDownActions()
                            .createPanel(),
                    )
                        .align(Align.FILL)
                }.rowComment(
                    "A prefix the heuristic misses — a shared artifact published under a different group id — " +
                        "is added here as project code. One it claims wrongly is taken back as third-party.",
                )
            }

            group("Mappings") {
                row("Entries:") { cell(entries) }
                row("Stored in:") { cell(location) }
                row {
                    button("Reset Mappings…") {
                        MappingReset.confirmAndReset(project)
                        showMapping()
                    }
                }
            }

            group("Shortcut") {
                row {
                    link("Configure shortcut…") { openKeymap() }
                }.rowComment(
                    "SnippetVeil ships no default keyboard shortcut: every combination worth having is already " +
                        "taken, differently, in the Default, macOS, Eclipse and VS Code keymaps.",
                )
            }

            row {
                comment("SnippetVeil makes no network calls. <a href=\"$THREAT_MODEL\">Threat model</a>")
            }

            row {
                comment(
                    "SnippetVeil collects no telemetry, so a problem only reaches the maintainer if you " +
                        "report it. <a href=\"$REPORT_A_PROBLEM\">Report a problem</a>",
                )
            }
        }
    }

    override fun isModified(): Boolean = edited() != InternalLibrarySettings.of(project).configuration

    override fun apply() {
        InternalLibrarySettings.of(project).configuration = edited()
    }

    override fun reset() {
        val configuration = InternalLibrarySettings.of(project).configuration
        autoDetect.isSelected = configuration.autoDetectRootPackage
        prefixes.show(configuration)
        showMapping()
    }

    /**
     * What the controls say now.
     *
     * Blank rows are dropped rather than rejected: the add button puts an empty row in the table and
     * a user who thinks better of it leaves it there, which must mean *nothing* rather than a prefix
     * that matches every package.
     */
    private fun edited(): PrefixConfiguration {
        val rows = prefixes.rows.filter { it.prefix.isNotBlank() }
        return PrefixConfiguration(
            autoDetectRootPackage = autoDetect.isSelected,
            internalPrefixes = rows.filter { it.projectCode }.map { it.prefix }.distinct(),
            thirdPartyPrefixes = rows.filterNot { it.projectCode }.map { it.prefix }.distinct(),
        )
    }

    /**
     * The toolbar's `+`: the model's own insertion, and then the caret in the cell the user is about
     * to type into.
     *
     * **The row is added by [PrefixTableModel.addRow] rather than here**, so the toolbar and every
     * other caller insert one and the same row — this method owns only what a keyboard user would
     * otherwise have to do by hand, and that choreography is the part no test drives.
     */
    private fun addPrefix() {
        prefixes.addRow()
        val row = prefixes.rowCount - 1
        table.setRowSelectionInterval(row, row)
        table.editCellAt(row, PREFIX)
        table.editorComponent?.requestFocusInWindow()
    }

    /** The two facts about the mapping, read fresh — see [entries]. */
    private fun showMapping() {
        val stored = PlaceholderLedger.getInstance().snapshotOf(project).placeholders.size
        entries.text = "$stored placeholder${if (stored == 1) "" else "s"}"
        location.text = PlaceholderLedger.storagePath().toString()
    }

    /**
     * **Opens Keymap with the action selected**, which is what turns an absent default binding from
     * an omission into a one-click affordance.
     *
     * It names `Copy Anonymized` rather than the submenu, because that is the item somebody wanting a
     * shortcut wants one for. A renamed or dropped action id would land the user in an unfiltered
     * keymap tree with nothing selected — silent — so the id is pinned by a test.
     */
    private fun openKeymap() {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, KeymapPanel::class.java) {
            it.selectAction(SHORTCUT_ACTION)
        }
    }

    companion object {

        /** Opens this page, for the first-run balloon's `Open settings`. */
        fun openFor(project: Project) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, SnippetVeilConfigurable::class.java)
        }
    }
}

/**
 * One row of the prefix list: a package prefix, and which way it is being classified.
 *
 * Mutable because a `JTable` edits cells in place, and small enough that the table model below is the
 * whole of its invariants.
 */
private class PrefixRow(var prefix: String, var projectCode: Boolean)

/**
 * The prefix list as a table: one row per prefix, one column saying what the prefix means.
 *
 * The second column is a two-value choice rather than a tick box, because *third-party* is a positive
 * statement a user makes and not the absence of one — a row exists precisely because somebody
 * disagreed with the heuristic in one direction or the other.
 *
 * It is an [EditableModel] because that is what a [ToolbarDecorator] reads to decide whether its `+`
 * and `-` do anything: a plain table model gets a toolbar with both buttons disabled, which looks
 * like a bug rather than like a list nobody may edit.
 */
private class PrefixTableModel : AbstractTableModel(), EditableModel {

    var rows: List<PrefixRow> = emptyList()
        private set

    fun show(configuration: PrefixConfiguration) {
        rows = configuration.internalPrefixes.map { PrefixRow(it, projectCode = true) } +
            configuration.thirdPartyPrefixes.map { PrefixRow(it, projectCode = false) }
        fireTableDataChanged()
    }

    /**
     * A blank row, treated as project code until it is told otherwise — the commoner of the two
     * reasons to add one, and the direction the heuristic cannot reach on its own.
     */
    override fun addRow() {
        rows = rows + PrefixRow("", projectCode = true)
        fireTableRowsInserted(rows.size - 1, rows.size - 1)
    }

    override fun removeRow(index: Int) {
        rows = rows.filterIndexed { at, _ -> at != index }
        fireTableRowsDeleted(index, index)
    }

    /** Order is presentation and the engine reads sets, so there is nothing here to reorder. */
    override fun exchangeRows(oldIndex: Int, newIndex: Int) = Unit

    override fun canExchangeRows(oldIndex: Int, newIndex: Int): Boolean = false

    override fun getRowCount(): Int = rows.size

    override fun getColumnCount(): Int = 2

    override fun getColumnName(column: Int): String = if (column == PREFIX) "Package prefix" else "Treated as"

    override fun getColumnClass(column: Int): Class<*> = String::class.java

    override fun isCellEditable(row: Int, column: Int): Boolean = true

    override fun getValueAt(row: Int, column: Int): Any =
        if (column == PREFIX) rows[row].prefix else if (rows[row].projectCode) PROJECT_CODE else THIRD_PARTY

    override fun setValueAt(value: Any?, row: Int, column: Int) {
        if (column == PREFIX) {
            rows[row].prefix = value?.toString()?.trim().orEmpty()
        } else {
            rows[row].projectCode = value == PROJECT_CODE
        }
        fireTableCellUpdated(row, column)
    }
}

/** The page's own id, which is what `Open settings` and Find Action resolve. */
internal const val SETTINGS_PAGE_ID = "com.snippetveil.settings"

/**
 * The action `Configure shortcut…` selects. Pinned by `SnippetVeilConfigurableTest` against the ids
 * the plugin actually registers: a stale id here opens Keymap with nothing selected, which is a
 * failure with no symptom.
 */
internal const val SHORTCUT_ACTION = "SnippetVeil.CopyAnonymized"

/**
 * **The document behind the quiet line at the bottom.** It states the no-network claim's limits,
 * including what the three trust checks do and do not cover, and it ships with the publication
 * artifacts in snippetveil/snippetveil#18 — this page is one of the two surfaces that link to it.
 */
internal const val THREAT_MODEL = "https://github.com/snippetveil/snippetveil/blob/main/THREAT-MODEL.md"

/**
 * **Where every `Report a problem` and `Report an issue` in this product goes.** The chooser page,
 * not `issues/new` — a blank form is the one surface between a failed anonymization and a public
 * comment box that says nothing about what to paste into it, and the chooser's rendered template
 * descriptions are what say it.
 *
 * Spelled once because it has two callers that look nothing alike: the row at the bottom of this
 * page, which is cold, and `SnippetVeilNotifications`' `Report an issue` action, which is hot on
 * five error balloons. Two spellings of one destination is how one of them ends up pointing at the
 * blank form again.
 *
 * Opening it is not a network call by this plugin. The platform hands a URL to the desktop when the
 * user clicks a named link; nothing here opens a socket, and the trust checks would fail the build
 * if it did.
 */
internal const val REPORT_A_PROBLEM = "https://github.com/snippetveil/snippetveil/issues/new/choose"

private const val PREFIX = 0

private const val TREATMENT = 1

/**
 * The two things a row can say, spelled as the user reads them in the cell's own drop-down.
 *
 * Internal rather than private because `SnippetVeilConfigurableTest` types them into the table the
 * way a user picks them, and a test that spelled them itself would keep passing after they changed.
 */
internal const val PROJECT_CODE = "Project code"

internal const val THIRD_PARTY = "Third-party"
