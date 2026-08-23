package com.snippetveil.plugin

import com.intellij.openapi.util.Disposer
import com.intellij.ui.EditorTextField
import com.intellij.ui.components.JBLabel
import com.snippetveil.core.AnonymizationSettings
import com.snippetveil.core.LedgerSnapshot
import com.snippetveil.core.MappedKind
import com.snippetveil.core.MappedName
import java.awt.Container
import javax.swing.JCheckBox
import javax.swing.JTable
import javax.swing.JComponent

/**
 * The dialog, over real analyses — what it opens showing, and what it does not offer.
 *
 * It is built and never shown: everything asserted here is a property of the object rather than of
 * a window, which is also the claim the design makes about it — the read-only re-open is *the same
 * object re-rendered*, so anything that had to be seen to be checked would be a second code path.
 */
class PreviewDialogTest : JavaSnippetTestCase() {

    /**
     * **Keeping comments is unchecked on every open, and unchecked because the dialog is built that
     * way rather than because something reset it.**
     *
     * The stronger half is the second assertion: the dialog re-renders from its own controls the
     * moment it opens, so an analysis handed to it with a reduction already applied opens showing
     * the maximally-anonymized text anyway. A tick that survived one invocation would be exactly the
     * forgotten reduction the whole settings rule exists to prevent.
     */
    fun `test the reduction dialog opens with comments stripped whatever it was handed`() {
        assertTheHarnessResolves()
        val kept = analysisOf(
            """
            class Ledger {
                <selection>// reconcile against the merchant ledger
                void settle() {}</selection>
            }
            """.trimIndent(),
        ).rendered(settings = AnonymizationSettings(keepComments = true))

        assertTrue("the fixture kept no comment, so the reset has nothing to undo", "reconcile" in kept.result.text)

        withDialog(PreviewDialog.forCopy(project, kept)) { dialog ->
            assertFalse("the dialog opened with a reduction it was handed", dialog.analysis.settings.keepComments)
            assertFalse("the comment survived into the opening render: " + dialog.analysis.result.text,
                "reconcile" in dialog.analysis.result.text)
            assertEmpty(checkBoxesIn(dialog.createCenterPanel()).filter { it.isSelected })
        }
    }

    /**
     * **The read-only re-open shows no Copy button and no toggles.** By the time it can be opened
     * the delta is committed and the text has already left, so offering a reduction would offer to
     * change something that is gone.
     */
    fun `test the read-only re-open offers no copy and no toggles`() {
        assertTheHarnessResolves()
        val analysis = analysisOf("class Ledger { <selection>void settle() {}</selection> }")

        withDialog(PreviewDialog.forReview(project, analysis)) { dialog ->
            assertEmpty(checkBoxesIn(dialog.createCenterPanel()))
            assertEquals(
                listOf("Close"),
                dialog.createActions().map { it.getValue(javax.swing.Action.NAME) },
            )
        }

        // And the other half, because a check that cannot fail is a check that passes: the same
        // dialog opened to copy does offer both.
        withDialog(PreviewDialog.forCopy(project, analysis)) { dialog ->
            assertEquals(
                listOf("Keep comments and javadoc"),
                checkBoxesIn(dialog.createCenterPanel()).map { it.text },
            )
            assertTrue(
                "the reduction surface offers no way to copy: " + dialog.createActions().map { it.getValue(javax.swing.Action.NAME) },
                dialog.createActions().any { it.getValue(javax.swing.Action.NAME) == "Copy Anonymized" },
            )
        }
    }

    /**
     * **`Preserve` appears on `Unknown` rows and on no other row.** The override was given to
     * unresolved names specifically and **it must not creep**: a preserve checkbox on every row *is*
     * the free-text preserve list this design already rejected, built out of clicks instead of text.
     *
     * The cell is empty rather than an unticked box on every other row — a box nobody may tick reads
     * as an offer the user has done something wrong to lose.
     */
    fun `test preserve is offered on Unknown rows and on no other row`() {
        val rows = listOf(
            MappedName("Ledger", "Type1", MappedKind.TYPE, key = "class:com.acme.Ledger"),
            MappedName("MissingType", "Unknown2", MappedKind.UNKNOWN, key = "unresolved:MissingType"),
            MappedName("merchant settlement failed", "str3", MappedKind.LITERAL),
        )
        val model = MappingTableModel(rows, reducible = true) { _, _ -> }

        assertEquals(listOf(false, true, false), rows.indices.map { model.isCellEditable(it, PRESERVE_COLUMN) })
        assertEquals(listOf(null, false, null), rows.indices.map { model.getValueAt(it, PRESERVE_COLUMN) })
    }

    /**
     * The table is `Original | Placeholder | Kind`, and the read-only re-open has no fourth column at
     * all — the tick is not disabled there, it is absent, because there is nothing left to reduce.
     */
    fun `test the columns are the three the table is for, and preserve only where a reduction is possible`() {
        val rows = listOf(MappedName("Ledger", "Type1", MappedKind.TYPE, key = "class:com.acme.Ledger"))

        val reducible = MappingTableModel(rows, reducible = true) { _, _ -> }
        val review = MappingTableModel(rows, reducible = false) { _, _ -> }

        assertEquals(4, reducible.columnCount)
        assertEquals(3, review.columnCount)
        assertEquals(
            listOf("Original", "Placeholder", "Kind", "Preserve"),
            (0 until reducible.columnCount).map { reducible.getColumnName(it) },
        )
        assertEquals(listOf("Ledger", "Type1", "Type"), (0 until review.columnCount).map { review.getValueAt(0, it) })
    }

    /**
     * A preserved unresolved name is still a row, with the tick that preserved it and nothing in the
     * placeholder column — it stands for itself, and a row mapping a name to itself maps nothing.
     */
    fun `test a preserved row keeps its tick and shows no placeholder`() {
        val rows = listOf(MappedName("MissingType", null, MappedKind.UNKNOWN, key = "unresolved:MissingType"))
        val model = MappingTableModel(rows, reducible = true) { _, _ -> }

        assertEquals(listOf("MissingType", "—", "Unknown", true), (0 until 4).map { model.getValueAt(0, it) })
    }

    /**
     * The counts strip, and the one clause that is conditional. *Selection expanded to whole tokens*
     * is information when it fired and noise when it did not, which is a rule about both cases.
     */
    fun `test the counts strip states every number and discloses snapping only when it fired`() {
        assertTheHarnessResolves()
        val whole = analysisOf(
            """
            class Ledger {
                <selection>// reconcile the ledger
                void settle(MissingType m) {}</selection>
            }
            """.trimIndent(),
        )

        assertEquals("2 renamed · 1 unknown · 0 preserved", stripOf(whole))

        val cut = analysisOf(
            """
            class Ledger {
                void settle() {
                    int mer<selection>chantReference</selection> = 0;
                }
            }
            """.trimIndent(),
        )

        assertEquals(
            "1 renamed · 0 unknown · 0 preserved · selection expanded to whole tokens",
            stripOf(cut),
        )
    }

    /**
     * **Unsorted is first occurrence, and a header click re-sorts.**
     *
     * The default is the engine's own order, which is the order the snippet introduces the names —
     * so the table reads top-to-bottom against the pane beside it. The re-sort is what makes a long
     * table usable at all, and it is a view over the same rows: the render is untouched by it.
     */
    fun `test rows start in first-occurrence order and a header click re-sorts them`() {
        assertTheHarnessResolves()
        val analysis = analysisOf(
            """
            class Ledger {
                <selection>void settle(int amount) { int balance = amount; }</selection>
            }
            """.trimIndent(),
        )

        withDialog(PreviewDialog.forCopy(project, analysis)) { dialog ->
            val table = tableIn(dialog.createCenterPanel())

            assertEquals(
                listOf("settle", "amount", "balance"),
                (0 until table.rowCount).map { table.getValueAt(it, 0) },
            )

            table.rowSorter.toggleSortOrder(0)

            assertEquals(
                listOf("amount", "balance", "settle"),
                (0 until table.rowCount).map { table.getValueAt(it, 0) },
            )
        }
    }

    /**
     * **Toggling a control re-renders both panes, and commits nothing.**
     *
     * The re-render is free rather than clever: the engine is a pure function over an immutable
     * snapshot, so a changed tick is one more call with different settings. What makes it safe is
     * the second half — the ledger is not touched by any of them, and the button is still the only
     * commit point.
     *
     * Driven through the widget rather than the field behind it, because *"the tick re-renders"* is
     * a claim about the thing the user clicks.
     */
    fun `test ticking keep comments re-renders both panes and commits nothing`() {
        assertTheHarnessResolves()
        val analysis = analysisOf(
            """
            class Ledger {
                <selection>// reconcile against the merchant ledger
                void settle() {}</selection>
            }
            """.trimIndent(),
        )

        withDialog(PreviewDialog.forCopy(project, analysis)) { dialog ->
            val panel = dialog.createCenterPanel()
            val before = PlaceholderLedger.getInstance().snapshotOf(project)

            checkBoxesIn(panel).single().doClick()

            assertTrue("the comment did not come back into the render", "reconcile" in dialog.analysis.result.text)
            assertTrue("the code pane still shows the old render", "reconcile" in codeIn(panel).text)
            assertEquals("1 renamed · 0 unknown · 0 preserved", stripOf(dialog.analysis))

            val after = PlaceholderLedger.getInstance().snapshotOf(project)
            assertEquals("a toggle named a symbol", before.placeholders, after.placeholders)
            assertEquals("a toggle burnt a number", before.nextNumber, after.nextNumber)
        }
    }

    /**
     * The same, through the row's own tick: a preserved unresolved name is emitted verbatim, its row
     * loses its placeholder and keeps the tick that did it, and the numbers after it move up —
     * nothing was allocated for a name that is standing for itself.
     */
    fun `test ticking preserve on an Unknown row emits that name verbatim`() {
        assertTheHarnessResolves()
        val analysis = analysisOf("class Ledger { <selection>void settle(MissingType m) {}</selection> }")

        withDialog(PreviewDialog.forCopy(project, analysis)) { dialog ->
            val panel = dialog.createCenterPanel()
            val table = tableIn(panel)
            val unknown = (0 until table.rowCount).single { table.getValueAt(it, 2) == "Unknown" }
            val before = PlaceholderLedger.getInstance().snapshotOf(project)

            table.setValueAt(true, unknown, PRESERVE_COLUMN)

            assertTrue("the preserved name is not in the render: " + dialog.analysis.result.text,
                "MissingType" in dialog.analysis.result.text)
            assertTrue("the code pane still shows the old render", "MissingType" in codeIn(panel).text)
            assertEquals("—", table.getValueAt(unknown, 1))
            assertEquals(true, table.getValueAt(unknown, PRESERVE_COLUMN))

            // And back: the reduction is a tick, not a decision.
            table.setValueAt(false, unknown, PRESERVE_COLUMN)
            assertFalse("un-ticking did not put the placeholder back: " + dialog.analysis.result.text,
                "MissingType" in dialog.analysis.result.text)

            val after = PlaceholderLedger.getInstance().snapshotOf(project)
            assertEquals("a tick named a symbol", before.placeholders, after.placeholders)
            assertEquals("a tick burnt a number", before.nextNumber, after.nextNumber)
        }
    }

    /**
     * **Both notices ride the preview as well as the balloon, and neither shows when its loss did not
     * happen.** They sit below the counts and directly above the tick that answers the comment one,
     * which is the whole of what makes the disclosure actionable rather than only honest.
     */
    fun `test the preview discloses both losses and says nothing about the ones that did not happen`() {
        assertTheHarnessResolves()
        val both = analysisOf(
            """
            class Ledger {
                <selection>int balance;

                void settle(int balance) {
                    // this.total = 1;
                    this.balance = balance;
                }</selection>
            }
            """.trimIndent(),
        )

        withDialog(PreviewDialog.forCopy(project, both)) { dialog ->
            assertEquals(
                listOf(
                    "field1 and param3 were the same name",
                    "1 comment stripped, 1 of them commented-out code",
                ),
                noticesIn(dialog),
            )
        }

        val clean = analysisOf("class Ledger { <selection>void settle(int amount) {}</selection> }")

        withDialog(PreviewDialog.forCopy(project, clean)) { dialog ->
            assertEmpty(noticesIn(dialog))
        }
    }

    /**
     * **A reduction closes the notice it answers.** Keeping comments is what the comment notice is
     * *for*, so the tick has to take the sentence away with the loss — a disclosure that outlived the
     * loss it discloses would be a false statement about the text in the pane beside it.
     */
    fun `test keeping comments takes the comment notice away with the strip`() {
        assertTheHarnessResolves()
        val analysis = analysisOf(
            """
            class Ledger {
                <selection>// reconcile against the merchant ledger
                void settle() {}</selection>
            }
            """.trimIndent(),
        )

        withDialog(PreviewDialog.forCopy(project, analysis)) { dialog ->
            val panel = dialog.createCenterPanel()
            assertEquals(listOf("1 comment stripped, 0 of them commented-out code"), noticesIn(dialog))

            checkBoxesIn(panel).single().doClick()

            assertEmpty(noticesIn(dialog))
        }
    }

    /**
     * The read-only re-open carries them too. It is the balloon's `Show mapping`, and what it shows
     * is the invocation that left — the notices describe that same text, and nothing about them is a
     * reduction the way back could offer.
     */
    fun `test the read-only re-open carries the notices`() {
        assertTheHarnessResolves()
        val analysis = analysisOf(
            """
            class Ledger {
                <selection>// this.total = 1;
                void settle() {}</selection>
            }
            """.trimIndent(),
        )

        withDialog(PreviewDialog.forReview(project, analysis)) { dialog ->
            assertEquals(
                listOf("1 comment stripped, 1 of them commented-out code"),
                noticesIn(dialog),
            )
        }
    }

    /** An analysis of the fixture, against an empty ledger, with nothing reduced. */
    private fun analysisOf(source: String): Analysis = Analysis.of(
        planFor("Ledger.java", source),
        AnonymizationSettings.DEFAULTS,
        LedgerSnapshot.EMPTY,
    )

    private fun withDialog(dialog: PreviewDialog, assertions: (PreviewDialog) -> Unit) {
        try {
            assertions(dialog)
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }

    private fun codeIn(component: Container): EditorTextField = descendantsOf(component).filterIsInstance<EditorTextField>().single()

    /**
     * The notices as a reader sees them — every label in the footer that is not the counts strip.
     * Read off the rendered components rather than off the analysis, because *"the preview carries
     * them"* is a claim about what is on screen.
     *
     * The strip is told apart by being the strip — the exact string [stripOf] produced for what is
     * rendered — rather than by anything about how it is worded, so a reworded strip stays a strip
     * here instead of quietly becoming a third notice.
     */
    private fun noticesIn(dialog: PreviewDialog): List<String> =
        descendantsOf(dialog.createCenterPanel())
            .filterIsInstance<JBLabel>()
            .map { it.text }
            .filterNot { it == stripOf(dialog.analysis) }

    private fun tableIn(component: Container): JTable = descendantsOf(component).filterIsInstance<JTable>().single()

    private fun descendantsOf(component: Container): List<java.awt.Component> =
        component.components.flatMap { listOf(it) + if (it is Container) descendantsOf(it) else emptyList() }

    private fun checkBoxesIn(component: Container): List<JCheckBox> =
        component.components.flatMap {
            when (it) {
                is JCheckBox -> listOf(it)
                is JComponent -> checkBoxesIn(it)
                else -> emptyList()
            }
        }
}

private const val PRESERVE_COLUMN = 3
