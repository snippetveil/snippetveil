package com.snippetveil.plugin

import com.intellij.openapi.util.Disposer
import java.awt.Component
import java.awt.Container
import javax.swing.JComponent
import javax.swing.JTable

/**
 * What every test that reaches into the preview dialog needs — held once, because two files reaching
 * into the same widget by two copies of the same walk is two files that can disagree about it.
 *
 * [PRESERVE_COLUMN] is the reason this file exists rather than a convenience: it is the table's
 * column order written down, and a second copy of it goes stale silently the day a column moves —
 * the assertions on either side would keep passing while reading a different column.
 */
internal const val PRESERVE_COLUMN = 3

/** Runs [assertions] against a dialog that is built, never shown, and always disposed. */
internal fun withDialog(dialog: PreviewDialog, assertions: (PreviewDialog) -> Unit) {
    try {
        assertions(dialog)
    } finally {
        Disposer.dispose(dialog.disposable)
    }
}

/** The mapping table, as it is rendered — there is exactly one in the dialog. */
internal fun tableIn(dialog: PreviewDialog): JTable = tableIn(dialog.createCenterPanel())

internal fun tableIn(component: Container): JTable =
    descendantsOf(component).filterIsInstance<JTable>().single()

internal fun descendantsOf(component: Container): List<Component> =
    component.components.flatMap { listOf(it) + if (it is Container) descendantsOf(it) else emptyList() }

/**
 * The header tooltips, read the way the platform reads them — the renderer the column carries, or
 * the header's own default where it carries none, asked for the component and then for its tip.
 * That walk is [javax.swing.table.JTableHeader.getToolTipText]'s own, minus the mouse: a helper that
 * read a field instead would assert a tooltip a user cannot get to.
 *
 * **Every header is rendered once before any tip is read**, because that is the order a user meets
 * them in: painting the header draws all of them through one renderer that hands back one shared
 * component, and a tip set on it for one column is still on it when the next is drawn. Reading in
 * column order alone would report the tips a header shows only if nothing had ever been painted.
 */
internal fun headerTooltipsIn(table: JTable): List<String?> {
    val columns = 0 until table.columnModel.columnCount

    fun render(column: Int): Component {
        val header = table.columnModel.getColumn(column)
        val renderer = header.headerRenderer ?: table.tableHeader.defaultRenderer
        return renderer.getTableCellRendererComponent(table, header.headerValue, false, false, -1, column)
    }

    columns.forEach { render(it) }
    return columns.map { (render(it) as? JComponent)?.toolTipText }
}
