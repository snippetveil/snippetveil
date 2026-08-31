package com.snippetveil.plugin

import com.intellij.openapi.ui.Messages
import com.intellij.ui.EditorTextField
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.snippetveil.core.AnonymizationSettings
import com.snippetveil.core.LedgerSnapshot
import com.snippetveil.core.MappedKind
import com.snippetveil.core.MappedName
import java.awt.Container
import javax.swing.JCheckBox
import javax.swing.JTable
import javax.swing.table.TableCellRenderer
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
     * **Locked, `Preserve` appears on `Unknown` rows and on no other row** — which is today's
     * behaviour and the state every opening starts in.
     *
     * The cell is empty rather than an unticked box on every other row — a box nobody may tick reads
     * as an offer the user has done something wrong to lose.
     */
    fun `test preserve is offered on Unknown rows and on no other row while it is locked`() {
        val model = MappingTableModel(THREE_ROWS, reducible = true) { _, _ -> }

        assertEquals(
            listOf(false, true, false),
            THREE_ROWS.indices.map { model.isCellEditable(it, PRESERVE_COLUMN) },
        )
        assertEquals(
            listOf(null, false, null),
            THREE_ROWS.indices.map { model.getValueAt(it, PRESERVE_COLUMN) },
        )
    }

    /**
     * **Unlocked, every row with a key is preservable — and the literal still is not.**
     *
     * The key is what decides rather than the kind, and the literal row is the whole reason that
     * matters: a preserve travels as a key, a literal has none, and literal text is the most
     * directly sensitive content the product handles. Offering a box there would be an offer that
     * could do nothing, on the one row where a user would most want it to.
     *
     * Every box comes up unticked, because a tick is read off the render and nothing was preserved
     * by unlocking.
     */
    fun `test unlocking offers preserve on every keyed row and never on a literal`() {
        val model = MappingTableModel(THREE_ROWS, reducible = true) { _, _ -> }

        model.unlocked = true

        assertEquals(
            listOf(true, true, false),
            THREE_ROWS.indices.map { model.isCellEditable(it, PRESERVE_COLUMN) },
        )
        assertEquals(
            listOf(false, false, null),
            THREE_ROWS.indices.map { model.getValueAt(it, PRESERVE_COLUMN) },
        )
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
     * **The `Preserve` header says who the column is for, and what an empty one means.**
     *
     * On the common snippet — everything resolved — the column renders a header and nothing under
     * it, which is a truthful signal that reads as a broken dialog to anyone who has not been told
     * what it is. The two sentences are the telling: the override belongs to unresolved names by
     * default, and the unlock is where an empty column stops being the end of it.
     *
     * **No other header carries a tip**, and that half is asserted rather than left to the
     * implementation: the header's default renderer hands back one component for every column, so a
     * tip set on it for `Preserve` alone would still be on it when `Original` is drawn.
     *
     * And it belongs to the column rather than to the position, which is the other half a shared
     * renderer gets wrong: the platform hands a header renderer the *view* index, and these columns
     * can be dragged.
     */
    fun `test the Preserve header says who it is for and no other header says anything`() {
        assertTheHarnessResolves()
        val analysis = analysisOf("class Ledger { <selection>void settle() {}</selection> }")
        val tip = "By default only names SnippetVeil could not resolve can be preserved. " +
            "Unlock Preserve for resolved names to tick any name in this table."

        withDialog(PreviewDialog.forCopy(project, analysis)) { dialog ->
            val table = tableIn(dialog)

            assertEquals(
                (0 until table.columnCount).map { if (it == PRESERVE_COLUMN) tip else null },
                headerTooltipsIn(table),
            )

            table.moveColumn(PRESERVE_COLUMN, 0)

            assertEquals(
                (0 until table.columnCount).map { if (it == 0) tip else null },
                headerTooltipsIn(table),
            )
        }
    }

    /**
     * **The tip the dialog takes away is its own sentence and nothing else.**
     *
     * Every header goes through the same renderer, because the shared component the platform draws
     * them with is how a tip set for one column ends up on the next. That is a reason to *clear* the
     * field, and clearing it wholesale would silence whatever the platform had to say about the
     * other three headers — this ticket editing columns it was not given. What is cleared is the
     * dialog's own sentence, left behind on a component nobody re-set.
     *
     * The stand-in is installed after the dialog is assembled, which is the second claim: the
     * delegate is asked of the header on every render rather than captured, so the tips a user sees
     * are the current theme's.
     */
    fun `test a header tip that is not ours survives, and Preserve still says its own`() {
        assertTheHarnessResolves()
        val analysis = analysisOf("class Ledger { <selection>void settle() {}</selection> }")
        val tip = "By default only names SnippetVeil could not resolve can be preserved. " +
            "Unlock Preserve for resolved names to tick any name in this table."

        withDialog(PreviewDialog.forCopy(project, analysis)) { dialog ->
            val table = tableIn(dialog)
            // One component for every header, the way the platform's own renderer works — and it
            // says its piece on each render, which is what tells a live tip apart from a stale one.
            val shared = JBLabel()
            table.tableHeader.defaultRenderer = TableCellRenderer { _, value, _, _, _, _ ->
                shared.also { it.toolTipText = "the whole $value, which did not fit" }
            }

            assertEquals(
                (0 until table.columnCount).map {
                    if (it == PRESERVE_COLUMN) tip else "the whole ${table.getColumnName(it)}, which did not fit"
                },
                headerTooltipsIn(table),
            )
        }
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

    /**
     * **The reduction opening opens locked, and behaves exactly as it did before the unlock existed.**
     *
     * The link is the whole of what is new until it is clicked: the resolved rows carry no box, the
     * header still explains the default, and the render is the maximally-anonymized one. This is the
     * test that says *today's behaviour survives*, so the unlock is an addition rather than a change
     * of default.
     */
    fun `test the reduction opening is locked on open and offers the unlock`() {
        assertTheHarnessResolves()
        val analysis = analysisOf("class Ledger { <selection>void settle(MissingType m) {}</selection> }")

        withDialog(PreviewDialog.forCopy(project, analysis)) { dialog ->
            val table = tableIn(dialog)

            assertEquals("Unlock Preserve for resolved names\u2026", unlockIn(dialog).text)
            assertTrue("the unlock is not offered", unlockIn(dialog).isEnabled)
            // settle, MissingType, m — and only the unresolved one is offered a box.
            assertEquals(
                listOf(false, true, false),
                (0 until table.rowCount).map { table.isCellEditable(it, PRESERVE_COLUMN) },
            )
            assertEquals(LOCKED_TOOLTIP, headerTooltipsIn(table)[PRESERVE_COLUMN])
        }
    }

    /** The read-only re-open offers no unlock, because it has no reduction to unlock. */
    fun `test the read-only re-open offers no unlock`() {
        assertTheHarnessResolves()
        val analysis = analysisOf("class Ledger { <selection>void settle() {}</selection> }")

        withDialog(PreviewDialog.forReview(project, analysis)) { dialog ->
            assertEmpty(descendantsOf(dialog.createCenterPanel()).filterIsInstance<ActionLink>())
        }
    }

    /**
     * **Unlocking warns first, and the warning says what a preserved name is** — the one moment the
     * product asks a user to confirm a reduction, so the sentence it asks with is asserted rather
     * than left to whoever edits it next.
     *
     * After it, the resolved rows carry boxes, every one of them unticked: nothing was preserved by
     * unlocking, and the render is untouched until a box is ticked.
     */
    fun `test unlocking warns and then offers preserve on every keyed row, all unticked`() {
        assertTheHarnessResolves()
        val analysis = analysisOf("class Ledger { <selection>void settle(int amount) {}</selection> }")
        var warning: String? = null
        answerDialogsWith(testRootDisposable, Messages.YES) { warning = it }

        withDialog(PreviewDialog.forCopy(project, analysis)) { dialog ->
            val table = tableIn(dialog)
            val before = dialog.analysis.result.text

            unlockIn(dialog).doClick()

            assertEquals(
                "Preserved names are sent exactly as written in your code. SnippetVeil will not " +
                    "conceal a name you tick.\n\n" +
                    "Only preserve names you would be comfortable typing into the chat yourself.",
                warning,
            )
            assertEquals(
                listOf(true, true),
                (0 until table.rowCount).map { table.isCellEditable(it, PRESERVE_COLUMN) },
            )
            assertEquals(
                listOf(false, false),
                (0 until table.rowCount).map { table.getValueAt(it, PRESERVE_COLUMN) },
            )
            assertEquals("unlocking changed the render", before, dialog.analysis.result.text)
            assertEquals(
                "Ticked names are emitted exactly as written in your code.",
                headerTooltipsIn(table)[PRESERVE_COLUMN],
            )
            assertEquals("Preserve unlocked for this preview", unlockIn(dialog).text)
        }
    }

    /** **Cancel leaves it locked**, which is the whole of the Cancel path: nothing else moves. */
    fun `test cancelling the unlock leaves preserve locked`() {
        assertTheHarnessResolves()
        val analysis = analysisOf("class Ledger { <selection>void settle(int amount) {}</selection> }")
        answerDialogsWith(testRootDisposable, Messages.NO)

        withDialog(PreviewDialog.forCopy(project, analysis)) { dialog ->
            val table = tableIn(dialog)

            unlockIn(dialog).doClick()

            assertEquals(
                listOf(false, false),
                (0 until table.rowCount).map { table.isCellEditable(it, PRESERVE_COLUMN) },
            )
            assertEquals(LOCKED_TOOLTIP, headerTooltipsIn(table)[PRESERVE_COLUMN])
            assertEquals(UNLOCK_LINK, unlockIn(dialog).text)
            assertTrue("cancelling took the unlock away", unlockIn(dialog).isEnabled)
        }
    }

    /**
     * **A second open is locked again**, and locked because the dialog is constructed that way rather
     * than because anything reset it. A sticky unlock is the set-once-and-forgotten reduction the
     * governing rule exists to prevent, and it is the one thing about this feature that could leak
     * silently.
     */
    fun `test the unlock does not survive into the next opening`() {
        assertTheHarnessResolves()
        val analysis = analysisOf("class Ledger { <selection>void settle(int amount) {}</selection> }")
        answerDialogsWith(testRootDisposable, Messages.YES)

        withDialog(PreviewDialog.forCopy(project, analysis)) { dialog ->
            unlockIn(dialog).doClick()
            assertTrue("the first opening never unlocked", tableIn(dialog).isCellEditable(0, PRESERVE_COLUMN))
        }

        withDialog(PreviewDialog.forCopy(project, analysis)) { dialog ->
            val table = tableIn(dialog)

            assertFalse("the second opening inherited the unlock", table.isCellEditable(0, PRESERVE_COLUMN))
            assertEquals(UNLOCK_LINK, unlockIn(dialog).text)
            assertEquals(LOCKED_TOOLTIP, headerTooltipsIn(table)[PRESERVE_COLUMN])
        }
    }

    /**
     * The same round trip the `Unknown` tick has, now on a resolved row: the ticked name is emitted
     * as written, the row keeps its tick and loses its placeholder, and un-ticking puts the
     * placeholder back. Nothing is committed by any of it.
     */
    fun `test ticking preserve on a resolved row emits that name verbatim`() {
        assertTheHarnessResolves()
        val analysis = analysisOf("class Ledger { <selection>void settle(int filter) { int x = filter; }</selection> }")
        answerDialogsWith(testRootDisposable, Messages.YES)

        withDialog(PreviewDialog.forCopy(project, analysis)) { dialog ->
            val panel = dialog.createCenterPanel()
            val table = tableIn(panel)
            val before = PlaceholderLedger.getInstance().snapshotOf(project)
            unlockIn(dialog).doClick()
            val row = (0 until table.rowCount).single { table.getValueAt(it, 0) == "filter" }

            table.setValueAt(true, row, PRESERVE_COLUMN)

            assertTrue("the preserved name is not in the render: " + dialog.analysis.result.text,
                "filter" in dialog.analysis.result.text)
            assertTrue("the code pane still shows the old render", "filter" in codeIn(panel).text)
            assertEquals("—", table.getValueAt(row, 1))
            assertEquals(true, table.getValueAt(row, PRESERVE_COLUMN))

            // And back: the reduction is a tick, not a decision.
            table.setValueAt(false, row, PRESERVE_COLUMN)
            assertFalse("un-ticking did not put the placeholder back: " + dialog.analysis.result.text,
                "filter" in dialog.analysis.result.text)

            val after = PlaceholderLedger.getInstance().snapshotOf(project)
            assertEquals("a tick named a symbol", before.placeholders, after.placeholders)
            assertEquals("a tick burnt a number", before.nextNumber, after.nextNumber)
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

    private fun checkBoxesIn(component: Container): List<JCheckBox> =
        component.components.flatMap {
            when (it) {
                is JCheckBox -> listOf(it)
                is JComponent -> checkBoxesIn(it)
                else -> emptyList()
            }
        }
}

/**
 * A resolved row, an `Unknown` row and a replaced literal — the three shapes the `Preserve` column
 * has to tell apart, held once so that the locked case and the unlocked case are read off the same
 * table rather than off two that could drift.
 */
private val THREE_ROWS = listOf(
    MappedName("Ledger", "Type1", MappedKind.TYPE, key = "class:com.acme.Ledger"),
    MappedName("MissingType", "Unknown2", MappedKind.UNKNOWN, key = "unresolved:MissingType"),
    MappedName("merchant settlement failed", "str3", MappedKind.LITERAL),
)
