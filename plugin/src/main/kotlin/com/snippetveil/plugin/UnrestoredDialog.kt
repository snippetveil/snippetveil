package com.snippetveil.plugin

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.snippetveil.core.Unrestored
import java.awt.BorderLayout
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableRowSorter

/**
 * **What did not come back, and which of the two things that means.**
 *
 * The balloon's `Show details`, and it is offered only when there is something to list. Read-only,
 * and not because a read-only dialog was simpler: there is **nothing to do** to a placeholder that
 * decoded to nothing. One of them is a name this project minted and has since forgotten, and the
 * other never stood for anything here at all — and neither is a button.
 *
 * **The split is the whole content.** Collapsed into a count, a user hunts for a mapping that either
 * never existed or is provably gone; told apart, one row says *stop looking* and the other says
 * *this is not a name, the model made it up*. That distinction is free — the counter never recycles a
 * number, so which side of it a placeholder falls on is an honest test with no record required.
 *
 * A row's *reason* is written out rather than shown as a code, because this list is read once by
 * somebody holding a reply they cannot fully read, and a legend is a second thing to learn.
 */
internal class UnrestoredDialog(project: Project, private val unrestored: List<Unrestored>) :
    DialogWrapper(project, true) {

    init {
        title = "Placeholders Not Restored"
        init()
    }

    /** There is nothing to confirm, so the way out reads as what it is. */
    public override fun createActions(): Array<Action> {
        cancelAction.putValue(Action.NAME, "Close")
        return arrayOf(cancelAction)
    }

    /** Public for the same reason [PreviewDialog.createCenterPanel] is: the rows are assertable. */
    public override fun createCenterPanel(): JComponent {
        val rows = UnrestoredTableModel(unrestored)
        val table = JBTable(rows)
        table.setShowGrid(false)
        table.rowSorter = TableRowSorter(rows)

        val panel = JPanel(BorderLayout())
        panel.add(
            // Above the table rather than in a tooltip: it is the sentence that makes the second
            // column mean anything, and the reply on the clipboard is intact either way.
            JBLabel("These words were left exactly as the reply wrote them.").also {
                it.border = JBUI.Borders.emptyBottom(8)
            },
            BorderLayout.NORTH,
        )
        panel.add(JBScrollPane(table), BorderLayout.CENTER)
        panel.preferredSize = JBUI.size(460, 320)
        return panel
    }
}

/** One row per placeholder, in the order the reply writes them. */
internal class UnrestoredTableModel(private val unrestored: List<Unrestored>) : AbstractTableModel() {

    override fun getRowCount(): Int = unrestored.size

    override fun getColumnCount(): Int = COLUMNS.size

    override fun getColumnName(column: Int): String = COLUMNS[column]

    override fun getValueAt(row: Int, column: Int): String = when (column) {
        PLACEHOLDER -> unrestored[row].placeholder
        else -> unrestored[row].reason.message
    }
}

private val COLUMNS = arrayOf("Placeholder", "Why")

private const val PLACEHOLDER = 0
