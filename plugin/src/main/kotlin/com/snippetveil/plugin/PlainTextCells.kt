package com.snippetveil.plugin

import javax.swing.JTable
import javax.swing.table.DefaultTableCellRenderer

/**
 * **A table cell that shows its text as text, whatever the text is.**
 *
 * Swing renders any label whose text begins `<html>` as a document, and a document fetches what it
 * references: `<html><img src="https://…">` in a cell is a request leaving the machine, made by this
 * plugin's own window. The cells these tables show are not this plugin's words. A redacted literal's
 * original is whatever somebody wrote between two quotes — and `"<html>…"` is an ordinary string in
 * a Swing codebase, which is exactly the kind this plugin is installed beside — and a package prefix
 * is read out of `.idea/snippetveil.xml`, which can arrive with a clone.
 *
 * The bytecode scan cannot see this route, because no class here names a socket: the request is made
 * by the JDK's own HTML view on the cell's behalf. So it is closed where it opens, and
 * `html.disable` is the switch the JDK reads for it — per component, on every change of text.
 */
internal open class PlainTextCellRenderer : DefaultTableCellRenderer() {
    init {
        putClientProperty(HTML_DISABLE, true)
    }
}

/**
 * Every cell of this table without a renderer of its own is drawn by a [PlainTextCellRenderer].
 *
 * Registered for [Any], which is where a `JTable` looks last for every column class — `String`
 * included — that has no renderer registered under its own name. A column that sets its own renderer
 * has to extend [PlainTextCellRenderer] itself; `Boolean`'s is a check box and has no text.
 */
internal fun JTable.showCellTextAsText() {
    setDefaultRenderer(Any::class.java, PlainTextCellRenderer())
}

/** `BasicHTML`'s own key, which the JDK keeps as a literal rather than a constant anyone can name. */
private const val HTML_DISABLE = "html.disable"
