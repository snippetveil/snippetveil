package com.snippetveil.plugin

import com.intellij.openapi.util.Disposer
import java.awt.Component
import java.awt.Container
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
