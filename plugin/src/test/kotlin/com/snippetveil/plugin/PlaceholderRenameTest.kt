package com.snippetveil.plugin

import com.intellij.openapi.ui.Messages
import com.intellij.ui.EditorTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.snippetveil.core.AnonymizationSettings
import com.snippetveil.core.LedgerDelta
import com.snippetveil.core.LedgerSnapshot
import com.snippetveil.core.MappedKind
import com.snippetveil.core.MappedName
import com.snippetveil.core.MintedName
import com.snippetveil.core.Renaming
import com.snippetveil.core.StemRejection
import com.snippetveil.core.numberOf
import com.snippetveil.core.stemOf
import com.snippetveil.core.plus
import java.awt.Container
import javax.swing.JTable
import javax.swing.table.TableCellEditor

/**
 * **Renaming a placeholder's stem in the preview — which rows offer it, and what the number does.**
 *
 * The engine's half is asserted in `:core`: a stem renders as `stem + number`, and a stem for a
 * ledgered key, an `Unknown` or a literal is ignored whatever a dialog sends. What is left here is
 * the dialog's half — which rows carry an editor, what the ones that do not say instead, and that
 * the number is not in the editable text at all.
 */
class PlaceholderRenameTest : JavaSnippetTestCase() {

    /**
     * **Only a row this invocation minted is editable**, and the other three shapes each say why not
     * rather than silently refusing the double-click. The literal is the row with nothing to say: it
     * has no key, so there was never an offer for it to have lost.
     */
    fun `test only rows minted in this invocation are editable, and the rest say why not`() {
        val model = modelOf(FOUR_ROWS, reducible = true)

        assertEquals(
            listOf(true, false, false, false),
            FOUR_ROWS.indices.map { model.isCellEditable(it, PLACEHOLDER_COLUMN) },
        )
        assertEquals(
            listOf(RENAME_TOOLTIP, Renaming.ESTABLISHED.message, Renaming.DERIVED.message, null),
            FOUR_ROWS.indices.map { model.renameTooltipAt(it) },
        )
    }

    /**
     * **The read-only re-open is untouched.** Its invocation has already left, so a stem typed into
     * it would name a snippet nobody can still send — no editor, and no sentence offering one.
     */
    fun `test the read-only re-open offers no rename and explains nothing`() {
        val model = modelOf(FOUR_ROWS, reducible = false)

        assertEquals(
            listOf(false, false, false, false),
            FOUR_ROWS.indices.map { model.isCellEditable(it, PLACEHOLDER_COLUMN) },
        )
        assertEquals(listOf(null, null, null, null), FOUR_ROWS.indices.map { model.renameTooltipAt(it) })
    }

    /**
     * The round trip, end to end: a stem typed on a row re-renders both panes under `stem + number`,
     * and **nothing is committed by any of it** — the ledger is where it was, numbers included.
     */
    fun `test renaming a row re-renders both panes and commits nothing`() {
        assertTheHarnessResolves()
        val analysis = analysisOf("class Ledger { <selection>void settle(int filter) { int x = filter; }</selection> }")

        withDialog(PreviewDialog.forCopy(project, analysis)) { dialog ->
            val panel = dialog.createCenterPanel()
            val table = tableIn(panel)
            val before = PlaceholderLedger.getInstance().snapshotOf(project)
            val row = rowOf(table, "filter")
            val number = table.getValueAt(row, PLACEHOLDER_COLUMN).toString().takeLastWhile(Char::isDigit)

            table.setValueAt("theFilter", row, PLACEHOLDER_COLUMN)

            assertEquals("theFilter$number", table.getValueAt(rowOf(table, "filter"), PLACEHOLDER_COLUMN))
            assertTrue(
                "the renamed placeholder is not in the render: " + dialog.analysis.result.text,
                "theFilter$number" in dialog.analysis.result.text,
            )
            assertTrue("the code pane still shows the old render", "theFilter$number" in codeIn(panel).text)

            // And back: an empty stem is the way back to the default, not a rejection.
            table.setValueAt("", rowOf(table, "filter"), PLACEHOLDER_COLUMN)
            assertFalse(
                "clearing the stem left the rename in the render: " + dialog.analysis.result.text,
                "theFilter" in dialog.analysis.result.text,
            )

            val after = PlaceholderLedger.getInstance().snapshotOf(project)
            assertEquals("a rename named a symbol", before.placeholders, after.placeholders)
            assertEquals("a rename burnt a number", before.nextNumber, after.nextNumber)
        }
    }

    /**
     * **A name an earlier snippet already used keeps it**, and core is what enforces that rather
     * than the missing editor: the cell is set here the way a dialog with the wrong idea would set
     * it, and the placeholder does not move.
     */
    fun `test a name from an earlier snippet keeps it, whatever the table is told`() {
        assertTheHarnessResolves()
        val plan = planFor("Ledger.java", "class Ledger { <selection>void settle() {}</selection> }")
        val first = Analysis.of(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)
        val again = Analysis.of(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY + first.result.delta)

        withDialog(PreviewDialog.forCopy(project, again)) { dialog ->
            val table = tableIn(dialog)
            val row = rowOf(table, "settle")
            val established = table.getValueAt(row, PLACEHOLDER_COLUMN)

            assertFalse("a ledgered row offered its editor", table.isCellEditable(row, PLACEHOLDER_COLUMN))
            assertEquals(Renaming.ESTABLISHED.message, cellTooltipAt(table, row, PLACEHOLDER_COLUMN))

            table.setValueAt("FilterMethod", row, PLACEHOLDER_COLUMN)

            assertEquals(established, table.getValueAt(rowOf(table, "settle"), PLACEHOLDER_COLUMN))
            assertTrue(
                "the ignored stem reached the output: " + dialog.analysis.result.text,
                "FilterMethod" !in dialog.analysis.result.text,
            )
        }
    }

    /**
     * **Ticking `Preserve` takes the editor away with the placeholder, and unticking brings both
     * back** — the rename with them, because a stem is kept against the symbol's key rather than
     * against the placeholder it produced.
     */
    fun `test preserving a renamed row hides the editor and releasing it restores the rename`() {
        assertTheHarnessResolves()
        val analysis = analysisOf("class Ledger { <selection>void settle(int filter) { int x = filter; }</selection> }")
        answerDialogsWith(testRootDisposable, Messages.YES)

        withDialog(PreviewDialog.forCopy(project, analysis)) { dialog ->
            val table = tableIn(dialog)
            unlockIn(dialog).doClick()
            table.setValueAt("theFilter", rowOf(table, "filter"), PLACEHOLDER_COLUMN)

            table.setValueAt(true, rowOf(table, "filter"), PRESERVE_COLUMN)

            val preserved = rowOf(table, "filter")
            assertEquals("—", table.getValueAt(preserved, PLACEHOLDER_COLUMN))
            assertFalse("a preserved row still offered its editor", table.isCellEditable(preserved, PLACEHOLDER_COLUMN))
            assertNull(cellTooltipAt(table, preserved, PLACEHOLDER_COLUMN))

            table.setValueAt(false, preserved, PRESERVE_COLUMN)

            val released = rowOf(table, "filter")
            assertTrue("releasing the row lost the rename", table.isCellEditable(released, PLACEHOLDER_COLUMN))
            assertTrue(
                "releasing the row came back under the default stem: " + dialog.analysis.result.text,
                "theFilter" in table.getValueAt(released, PLACEHOLDER_COLUMN).toString(),
            )
        }
    }

    /**
     * **The number is not in the editable text at all** — it sits in a label beside the field, which
     * is what makes *there is no way to remove it* a property of the widget rather than of a check.
     */
    fun `test the editor edits the stem and shows the number beside it`() {
        assertTheHarnessResolves()
        val analysis = analysisOf("class Ledger { <selection>void settle(int filter) { int x = filter; }</selection> }")

        withDialog(PreviewDialog.forCopy(project, analysis)) { dialog ->
            val table = tableIn(dialog)
            val row = rowOf(table, "filter")
            val placeholder = table.getValueAt(row, PLACEHOLDER_COLUMN).toString()
            val opened = openEditor(table, row)

            assertEquals(placeholder.dropLastWhile(Char::isDigit), opened.stem.text)
            assertEquals(placeholder.takeLastWhile(Char::isDigit), opened.number.text)
        }
    }

    /**
     * **The editor refuses what the engine would drop, and says which rule it broke.** The trailing
     * digit is the case that is about this product rather than about Java: stem `Filter2` with
     * number 1 and stem `Filter` with number 21 would both render `Filter21`.
     *
     * The edit stays open on a refusal — [TableCellEditor.stopCellEditing] answering `false` is what
     * that means — because what was typed is nearly right, and throwing it away to show a message is
     * the worst of both.
     */
    fun `test the editor rejects an invalid stem with the reason and keeps the edit open`() {
        assertTheHarnessResolves()
        val analysis = analysisOf("class Ledger { <selection>void settle(int filter) { int x = filter; }</selection> }")

        withDialog(PreviewDialog.forCopy(project, analysis)) { dialog ->
            val table = tableIn(dialog)
            val opened = openEditor(table, rowOf(table, "filter"))

            opened.stem.text = "filter2"
            assertFalse("an invalid stem was accepted", opened.editor.stopCellEditing())
            assertEquals(StemRejection.ENDS_WITH_A_DIGIT.message, opened.stem.toolTipText)
            assertEquals(StemRejection.ENDS_WITH_A_DIGIT, dialog.rejection)

            opened.stem.text = "my filter"
            assertFalse("a stem that is not an identifier was accepted", opened.editor.stopCellEditing())
            assertEquals(StemRejection.NOT_AN_IDENTIFIER.message, opened.stem.toolTipText)
            assertEquals(StemRejection.NOT_AN_IDENTIFIER, dialog.rejection)

            opened.stem.text = "Unknown"
            assertFalse("a stem spelling one of our own namespaces was accepted", opened.editor.stopCellEditing())
            assertEquals(StemRejection.RESERVED_NAMESPACE, dialog.rejection)

            opened.stem.text = "  theFilter  "
            assertTrue("a valid stem was refused", opened.editor.stopCellEditing())
            assertEquals("theFilter", opened.editor.cellEditorValue)
            assertNull("the reason outlived the edit it was about", opened.stem.toolTipText)
            assertNull("the dialog's error line outlived the edit it was about", dialog.rejection)
        }
    }

    /**
     * **Clearing the editor is the way back to the default stem**, driven the way the platform
     * drives it — the edit is stopped, and what the editor hands back is what the table is set to.
     * Empty is not a refusal: [com.snippetveil.core.stemRejection] accepts it, so the edit commits
     * and the row re-mints under its own namespace.
     */
    fun `test clearing the editor commits and reverts the row to its default stem`() {
        assertTheHarnessResolves()
        val analysis = analysisOf("class Ledger { <selection>void settle(int filter) { int x = filter; }</selection> }")

        withDialog(PreviewDialog.forCopy(project, analysis)) { dialog ->
            val table = tableIn(dialog)
            val original = table.getValueAt(rowOf(table, "filter"), PLACEHOLDER_COLUMN)

            commitStem(table, rowOf(table, "filter"), "theFilter")
            assertEquals("theFilter" + numberOf(original.toString()), table.getValueAt(rowOf(table, "filter"), PLACEHOLDER_COLUMN))

            commitStem(table, rowOf(table, "filter"), "")

            assertEquals(original, table.getValueAt(rowOf(table, "filter"), PLACEHOLDER_COLUMN))
            assertNull("the reverted edit left a reason behind", dialog.rejection)
        }
    }

    /**
     * **The headline case, over real Java rather than a hand-written plan.** A type renamed in the
     * dialog renders `stem + number`; the **qualified key** is what carries it into the mapping; and
     * the next invocation over the same class comes back under the same word with no rename stated
     * anywhere — which is the whole of *stability comes from the ledger row and from nothing else*.
     *
     * `:core` asserts the rule over plan literals. What this adds is that the key the plugin's own
     * walk derives for a class is the key the rename lands on, which a plan written by a test cannot
     * show.
     */
    fun `test a renamed type is committed under its qualified key and comes back next time`() {
        assertTheHarnessResolves()
        val plan = planFor("Ledger.java", "class Ledger { <selection>Ledger settle() { return this; }</selection> }")
        val ledger = PlaceholderLedger.getInstance()

        var renamed = ""
        var delta: LedgerDelta? = null
        withDialog(PreviewDialog.forCopy(project, Analysis.of(plan, AnonymizationSettings.DEFAULTS, ledger.snapshotOf(project)))) { dialog ->
            val table = tableIn(dialog)
            val row = rowOf(table, "Ledger")

            assertTrue("a type minted by this invocation was not offered the rename", table.isCellEditable(row, PLACEHOLDER_COLUMN))
            commitStem(table, row, "FilterType")

            renamed = table.getValueAt(rowOf(table, "Ledger"), PLACEHOLDER_COLUMN).toString()
            assertEquals("FilterType", stemOf(renamed))
            assertTrue("the number went away: $renamed", numberOf(renamed).isNotEmpty())
            assertTrue("the rename is not in the render: " + dialog.analysis.result.text, renamed in dialog.analysis.result.text)
            delta = dialog.analysis.result.delta
        }

        // The copy is the commit point, and this is what it commits — the same delta [deliver] takes.
        ledger.commit(project, delta!!)

        val row = ledger.snapshotOf(project).placeholders.entries.single { it.value.original == "Ledger" }
        assertEquals("the rename did not reach the mapping", MintedName(renamed, "Ledger"), row.value)
        assertTrue("the class was filed under a key nothing qualified: " + row.key, "Ledger" in row.key)

        val again = Analysis.of(plan, AnonymizationSettings.DEFAULTS, ledger.snapshotOf(project))

        assertTrue("the rename did not survive: " + again.result.text, renamed in again.result.text)
        assertEquals(Renaming.ESTABLISHED, again.result.names.single { it.original == "Ledger" }.renaming)
    }

    /**
     * **Opening a row's editor and closing it again is not a rename**, and must not be answered with
     * a refusal — which is the ordinary way a user leaves a cell they only wanted to look at.
     *
     * The trap this pins: every default stem is one of the engine's own namespaces, so a row that
     * has not been renamed shows a word [StemRejection.RESERVED_NAMESPACE] refuses. That rule is
     * right about a namespace the user *chose* and says nothing about the one the engine put there,
     * and the difference between the two is whether anything was typed.
     */
    fun `test closing an untouched editor is accepted and changes nothing`() {
        assertTheHarnessResolves()
        val analysis = analysisOf("class Ledger { <selection>void settle(int filter) { int x = filter; }</selection> }")

        withDialog(PreviewDialog.forCopy(project, analysis)) { dialog ->
            val table = tableIn(dialog)
            val rendered = dialog.analysis.result.text

            // Every offered row, because the stem that would be refused is a different word on each
            // of them and a test over one row would pass while the rest stayed broken.
            for (row in (0 until table.rowCount).filter { table.isCellEditable(it, PLACEHOLDER_COLUMN) }) {
                val placeholder = table.getValueAt(row, PLACEHOLDER_COLUMN)
                val opened = openEditor(table, row)

                assertEquals("the editor did not open on the rendered stem", stemOf(placeholder.toString()), opened.stem.text)
                assertTrue("the editor refused the stem it opened with: $placeholder", opened.editor.stopCellEditing())
                assertNull("closing an untouched editor reported a reason", dialog.rejection)

                table.setValueAt(opened.editor.cellEditorValue, row, PLACEHOLDER_COLUMN)
                assertEquals("closing an untouched editor moved the placeholder", placeholder, table.getValueAt(row, PLACEHOLDER_COLUMN))
            }

            assertEquals("closing an untouched editor re-rendered the snippet", rendered, dialog.analysis.result.text)
        }
    }

    /**
     * Types [stem] into the row's editor and commits it the way `JTable` does — stop the edit, then
     * set the cell to what the editor hands back. A test that called `setValueAt` alone would skip
     * the half of this that validates.
     */
    private fun commitStem(table: JTable, row: Int, stem: String) {
        val opened = openEditor(table, row)
        opened.stem.text = stem
        assertTrue("the editor refused `$stem`", opened.editor.stopCellEditing())
        table.setValueAt(opened.editor.cellEditorValue, row, PLACEHOLDER_COLUMN)
    }

    /** The editor as a user opens it: the column's own, over the cell they double-clicked. */
    private fun openEditor(table: JTable, row: Int): OpenEditor {
        val editor = table.columnModel.getColumn(PLACEHOLDER_COLUMN).cellEditor
        val component = editor.getTableCellEditorComponent(
            table,
            table.getValueAt(row, PLACEHOLDER_COLUMN),
            false,
            row,
            PLACEHOLDER_COLUMN,
        )
        return OpenEditor(
            editor,
            descendantsOf(component as Container).filterIsInstance<JBTextField>().single(),
            descendantsOf(component).filterIsInstance<JBLabel>().single(),
        )
    }

    /** One open cell editor, and the two halves of it a test reads: the stem, and the fixed number. */
    private class OpenEditor(val editor: TableCellEditor, val stem: JBTextField, val number: JBLabel)

    private fun codeIn(component: Container): EditorTextField =
        descendantsOf(component).filterIsInstance<EditorTextField>().single()
}

/**
 * The four shapes the `Placeholder` column has to tell apart, held once so that the reduction
 * opening and the read-only one are read off the same table rather than off two that could drift.
 *
 * A row minted here, a row the ledger already held, an accessor whose name follows its field's, and
 * a literal — which has no key and therefore never had an offer to lose.
 */
private val FOUR_ROWS = listOf(
    MappedName("Ledger", "Type1", MappedKind.TYPE, key = "class:com.acme.Ledger", renaming = Renaming.OFFERED),
    MappedName("settle", "method2", MappedKind.METHOD, key = "method:com.acme.Ledger#settle", renaming = Renaming.ESTABLISHED),
    MappedName("getMerchantId", "getField3", MappedKind.METHOD, key = "method:com.acme.Payment#getMerchantId", renaming = Renaming.DERIVED),
    MappedName("merchant settlement failed", "str4", MappedKind.LITERAL),
)
