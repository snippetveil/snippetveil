package com.snippetveil.plugin

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.BooleanTableCellRenderer
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.snippetveil.core.AnonymizationSettings
import com.snippetveil.core.MappedKind
import com.snippetveil.core.MappedName
import java.awt.BorderLayout
import javax.swing.Action
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellRenderer
import javax.swing.table.TableRowSorter

/**
 * **The preview — and the only place in the product a user can anonymize *less*.**
 *
 * It is not what protects anyone from a hasty copy. Every reduction the design authorises — the
 * per-item preserve on an unresolved name, keeping comments — lives **only** here and **only** for
 * this invocation, and that is what makes `Copy Anonymized` the maximally-anonymizing path *by
 * construction* rather than by discipline: the fast path reads no reduction because there is none to
 * read.
 *
 * **Modal, not a tool window**, because a cancelled preview must burn nothing and that needs an
 * unambiguous commit point. A tool window has no Cancel, and *"did that invocation reach the
 * mapping?"* becomes a question the user cannot answer by looking.
 *
 * **The code pane is not decoration.** Selections snap outward to whole tokens, and there is no
 * honest way to show a snapped range except by showing the text that will actually be copied. The
 * same pane answers the question the user actually has, which is not *what were the renames* but
 * **"is this still answerable, and did it eat my comments?"**
 *
 * **A diff (original | anonymized) is rejected.** It optimises for the wrong question. A diff tells
 * you what changed, and **the leak is always in what didn't** — the preserved symbols, the
 * `Unknown`s, the literals that stayed. It highlights every rename equally and is silent on exactly
 * the rows that matter. The original is also still in the editor behind this dialog.
 *
 * ### One dialog, two openings
 *
 * [forCopy] is the reduction surface. [forReview] is the balloon's `Show mapping`, and it is **the
 * same object re-rendered** with no Copy button and no toggles. Read-only there is required rather
 * than a simplification: the delta is committed and the text has already left, so offering a
 * reduction would offer to change something that is gone.
 *
 * ### Nothing here commits anything
 *
 * The engine is a pure function over an immutable snapshot, so every toggle is one more call with
 * different settings and the ledger is not touched by any of them. Cancel discards an object.
 */
internal class PreviewDialog private constructor(
    project: Project,
    opening: Analysis,
    private val reducible: Boolean,
) : DialogWrapper(project, true) {

    /**
     * The one persistent setting, carried across every re-render. It is the exception that proves
     * the rule: nothing in it can take the output back past what a library-preserving spine rule
     * already produced, which is why it may be read at all.
     */
    private val libraries = opening.settings.internalLibraries

    /**
     * The unresolved names this invocation is releasing, **empty on every open**. A reduction is
     * per-invocation, and this is the field that makes that structural rather than remembered.
     */
    private val preserved = mutableSetOf<String>()

    /**
     * **Unchecked on every open, without exception**, and unchecked because it is constructed that
     * way rather than because something reset it. Comments are the largest single domain leak in the
     * product, and a tick that survived an invocation would be the forgotten reduction the whole
     * settings rule exists to prevent.
     */
    private val commentsBox = JBCheckBox("Keep comments and javadoc", false)

    /**
     * What is on screen — and what the OK button hands back to be copied.
     *
     * **The reduction surface re-renders from its own controls the moment it opens**, so what it
     * shows cannot have been inherited from anywhere: the controls start at their most-anonymizing
     * settings, and the pane is what those produce. It is the same render the fast path would have
     * made, which is why the text here is byte-identical to what `Copy Anonymized` would have put on
     * the clipboard.
     *
     * The read-only opening renders nothing. It shows what was delivered, because that is the text
     * that left.
     */
    var analysis: Analysis = if (reducible) opening.rendered(settings = settingsNow()) else opening
        private set

    private val code = EditorTextField(
        EditorFactory.getInstance().createDocument(analysis.result.text),
        project,
        JavaFileType.INSTANCE,
        true,
        false,
    )

    private val rows = MappingTableModel(analysis.result.names, reducible, ::preserve)

    private val table = JBTable(rows)

    private val strip = JBLabel(stripOf(analysis))

    /** Built once, however often it is asked for — the panes are the dialog's, not each caller's. */
    private val center: JComponent by lazy { assemble() }

    init {
        title = if (reducible) "Anonymize with Preview" else "Anonymized Snippet"
        if (reducible) setOKButtonText("Copy Anonymized")
        commentsBox.addActionListener { rerender() }
        init()
    }

    /**
     * Remembered across openings, which is the whole of what this dialog persists: a size and a
     * splitter position. Neither is a choice about anonymization, and there is nothing else here
     * that may be remembered at all.
     */
    override fun getDimensionServiceKey(): String = "SnippetVeil.Preview"

    /**
     * Read-only means read-only: no Copy button, and the way out reads as what it is.
     *
     * Public rather than protected so that *"the re-open offers no copy"* is a thing a test can
     * read, for the same reason [Previews] is an interface: a guarantee nobody can assert is a
     * guarantee nobody is keeping.
     */
    public override fun createActions(): Array<Action> {
        if (reducible) return super.createActions()
        cancelAction.putValue(Action.NAME, "Close")
        return arrayOf(cancelAction)
    }

    /** Public for the same reason [createActions] is: the absence of the toggles is assertable. */
    public override fun createCenterPanel(): JComponent = center

    private fun assemble(): JComponent {
        code.setOneLineMode(false)
        code.addSettingsProvider { editor ->
            editor.setVerticalScrollbarVisible(true)
            editor.setHorizontalScrollbarVisible(true)
            editor.settings.isLineNumbersShown = true
            editor.settings.isUseSoftWraps = false
        }

        table.setShowGrid(false)
        table.rowSorter = TableRowSorter(rows).also { sorter ->
            // Header-click re-sorts; unsorted is the model's own order, which is first occurrence in
            // this snippet. The tick column is not a column anyone reads down.
            if (reducible) sorter.setSortable(PRESERVE, false)
        }
        if (reducible) {
            table.columnModel.getColumn(PRESERVE).also {
                it.cellRenderer = PreserveRenderer()
                it.maxWidth = JBUI.scale(80)
            }
        }

        val splitter = JBSplitter(false, "SnippetVeil.Preview.Splitter", 0.6f)
        splitter.firstComponent = code
        splitter.secondComponent = JBScrollPane(table)

        val panel = JPanel(BorderLayout())
        panel.add(splitter, BorderLayout.CENTER)
        panel.add(footer(), BorderLayout.SOUTH)
        panel.preferredSize = JBUI.size(940, 620)
        return panel
    }

    private fun footer(): JComponent {
        val footer = JPanel()
        footer.layout = BoxLayout(footer, BoxLayout.Y_AXIS)
        footer.border = JBUI.Borders.emptyTop(8)
        footer.add(strip)
        if (reducible) footer.add(commentsBox)
        return footer
    }

    /**
     * A tick on an `Unknown` row, in or out. It reaches the engine as a key rather than as a name,
     * and the engine ignores a key that names anything but an unresolved symbol — so the override
     * cannot creep into a preserve list for resolved symbols by anyone adding a checkbox here.
     */
    private fun preserve(name: MappedName, released: Boolean) {
        val key = name.key ?: return
        if (released) preserved += key else preserved -= key
        rerender()
    }

    /** Both panes and the strip, from one more call to a pure function. Nothing else moves. */
    private fun rerender() {
        analysis = analysis.rendered(settings = settingsNow())
        code.text = analysis.result.text
        rows.showing = analysis.result.names
        strip.text = stripOf(analysis)
    }

    private fun settingsNow(): AnonymizationSettings = AnonymizationSettings(
        preservedUnknowns = preserved.toSet(),
        keepComments = commentsBox.isSelected,
        internalLibraries = libraries,
    )

    companion object {

        /** The reduction surface: toggles, and a Copy button that is the commit point. */
        fun forCopy(project: Project, analysis: Analysis): PreviewDialog =
            PreviewDialog(project, analysis, reducible = true)

        /** The balloon's `Show mapping`: the same dialog over an invocation that has already left. */
        fun forReview(project: Project, analysis: Analysis): PreviewDialog =
            PreviewDialog(project, analysis, reducible = false)
    }
}

/**
 * The dialog, or whatever a test puts in its place — the seam the preview's guarantees are asserted
 * at.
 *
 * *"The preview shows what the clipboard gets"* and *"Cancel burns nothing"* are claims about the
 * invocation rather than about Swing, and both are only assertable if something can stand where the
 * modal dialog stands. It is the same reason [PlanBuilder] is an interface.
 */
internal fun interface Previews {

    /** The invocation to deliver — the user's own settings, re-rendered — or `null` if they cancelled. */
    fun confirm(project: Project, analysis: Analysis): Analysis?
}

/** The production one: the modal dialog, and its OK button. */
internal object PreviewDialogs : Previews {
    override fun confirm(project: Project, analysis: Analysis): Analysis? =
        PreviewDialog.forCopy(project, analysis).let { if (it.showAndGet()) it.analysis else null }
}

/**
 * **The counts, and one conditional clause.**
 *
 * `14 renamed · 3 unknown · 22 preserved · 2 comments stripped` — every number every time, including
 * the zeroes, because a number that appeared only when it fired would make its absence unreadable.
 * Preserved JDK and third-party symbols are here rather than in the table: their preservation is
 * deliberate and a declared non-goal, so each would be a row the user can do nothing about.
 *
 * *Selection expanded to whole tokens* is the exception, and it is conditional in the other
 * direction: always-on it is noise, and conditional it is information. It fires only when snapping
 * actually moved an end of the selection — the copy then contains text the user did not select, and
 * the pane beside this line is where they can see what.
 */
internal fun stripOf(analysis: Analysis): String {
    val counts = analysis.result.counts
    val strip = "${counts.replaced} renamed · ${counts.unknown} unknown · ${counts.preserved} preserved" +
        " · ${analysis.result.comments.stripped} comments stripped"
    return if (analysis.plan.selectionExpanded) "$strip · selection expanded to whole tokens" else strip
}

/**
 * **Rows are placeholders; preservation is a count.**
 *
 * Every symbol that received one is a row, `Unknown`s included, in first-occurrence order — that
 * ordering is the engine's, not this model's, which is what lets the sort start where the snippet
 * does. Preserved library symbols are not rows: listing them literally would produce a table where
 * `String`, `List` and `println` drown the fourteen rows that matter.
 *
 * **The `Preserve` column exists only where a reduction is possible, and its cells only on `Unknown`
 * rows.** The override was given to unresolved names specifically and **it must not creep**: a
 * preserve checkbox on every row *is* the free-text preserve list this design already rejected,
 * built out of clicks instead of text.
 */
internal class MappingTableModel(
    names: List<MappedName>,
    private val reducible: Boolean,
    private val onPreserve: (MappedName, Boolean) -> Unit,
) : AbstractTableModel() {

    var showing: List<MappedName> = names
        set(value) {
            field = value
            fireTableDataChanged()
        }

    override fun getRowCount(): Int = showing.size

    override fun getColumnCount(): Int = if (reducible) 4 else 3

    override fun getColumnName(column: Int): String = COLUMNS[column]

    override fun getColumnClass(column: Int): Class<*> =
        if (column == PRESERVE) Boolean::class.javaObjectType else String::class.java

    override fun getValueAt(row: Int, column: Int): Any? {
        val name = showing[row]
        return when (column) {
            ORIGINAL -> name.original
            PLACEHOLDER -> name.placeholder ?: NO_PLACEHOLDER
            KIND -> name.kind.label
            // A tick reads as "this name is being emitted as itself", which is what a preserved
            // unresolved name is: no placeholder, and its own text in the output. Every other row
            // has nothing here at all — see [PreserveRenderer].
            else -> if (name.kind == MappedKind.UNKNOWN) name.placeholder == null else null
        }
    }

    override fun isCellEditable(row: Int, column: Int): Boolean =
        reducible && column == PRESERVE && showing[row].kind == MappedKind.UNKNOWN

    override fun setValueAt(value: Any?, row: Int, column: Int) {
        if (column == PRESERVE) onPreserve(showing[row], value == true)
    }
}

/**
 * A checkbox on the rows that offer the override, and **nothing at all on the rest** — not a
 * disabled checkbox, which reads as an offer the user has done something wrong to lose.
 */
private class PreserveRenderer : TableCellRenderer {

    private val tick = BooleanTableCellRenderer()
    private val blank = DefaultTableCellRenderer()

    override fun getTableCellRendererComponent(
        table: JTable,
        value: Any?,
        selected: Boolean,
        focused: Boolean,
        row: Int,
        column: Int,
    ) = if (value is Boolean) {
        tick.getTableCellRendererComponent(table, value, selected, focused, row, column)
    } else {
        blank.getTableCellRendererComponent(table, "", selected, focused, row, column)
    }
}

private val COLUMNS = arrayOf("Original", "Placeholder", "Kind", "Preserve")

private const val ORIGINAL = 0
private const val PLACEHOLDER = 1
private const val KIND = 2
private const val PRESERVE = 3

/** What a preserved name renders as: it has no placeholder, and it stands for itself. */
private const val NO_PLACEHOLDER = "—"
