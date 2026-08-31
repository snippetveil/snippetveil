package com.snippetveil.plugin

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.BooleanTableCellRenderer
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.snippetveil.core.AnonymizationResult
import com.snippetveil.core.AnonymizationSettings
import com.snippetveil.core.MappedKind
import com.snippetveil.core.MappedName
import com.snippetveil.core.fidelityNotices
import java.awt.BorderLayout
import java.awt.Component
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.JTableHeader
import javax.swing.table.TableCellRenderer
import javax.swing.table.TableRowSorter

/**
 * **The preview — and the only place in the product a user can anonymize *less*.**
 *
 * It is not what protects anyone from a hasty copy. Every reduction the design authorises — the
 * per-item preserve on a name, keeping comments — lives **only** here and **only** for this
 * invocation, and that is what makes `Copy Anonymized` the maximally-anonymizing path *by
 * construction* rather than by discipline: the fast path reads no reduction because there is none to
 * read.
 *
 * ### Preserve is locked, and the unlock is the friction
 *
 * The per-item preserve reaches every keyed row rather than the `Unknown`s alone, and it reaches
 * them **only after an explicit unlock that warns first**. The unlock is locked again on every open,
 * there is no *don't warn me again*, and neither the unlock nor a tick is written anywhere: a sticky
 * unlock would be exactly the set-once-and-forgotten reduction the governing rule exists to
 * prevent.
 *
 * Why an unlock rather than a checkbox on every row: **the friction belongs at the moment of
 * reduction, where it is read.** A warning banner shown on every preview was rejected for the
 * opposite reason — *a warning shown on every invocation stops being read within a week* — and the
 * unlock does not have that failure mode, because it only ever fires when the user has actively
 * asked to reduce.
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
    private val project: Project,
    opening: Analysis,
    private val reducible: Boolean,
    private val files: MappingFiles,
) : DialogWrapper(project, true) {

    /**
     * The one persistent setting, carried across every re-render. It is the exception that proves
     * the rule: nothing in it can take the output back past what a library-preserving spine rule
     * already produced, which is why it may be read at all.
     */
    private val libraries = opening.settings.internalLibraries

    /**
     * The names this invocation is releasing, **empty on every open**. A reduction is per-invocation,
     * and this is the field that makes that structural rather than remembered.
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

    /**
     * **What the output gives no sign of** — one label per notice, and none at all on a snippet with
     * neither loss.
     *
     * Rebuilt rather than shown and hidden, because a notice is a sentence and there is no honest
     * empty state for one: a blank line where a disclosure would go is the noise the conditional
     * rule exists to avoid. It sits directly above the keep-comments tick, which is what the comment
     * notice is answered with.
     */
    private val notices = JPanel().also {
        it.layout = BoxLayout(it, BoxLayout.Y_AXIS)
        it.alignmentX = Component.LEFT_ALIGNMENT
    }

    /** Built once, however often it is asked for — the panes are the dialog's, not each caller's. */
    private val center: JComponent by lazy { assemble() }

    /**
     * **`Export Mapping…`, on both openings and reaching nothing but [analysis].**
     *
     * It is a button on this dialog rather than an entry in the `SnippetVeil` menu, and that is the
     * decision rather than a placement: a Tools-menu item has no invocation in front of it, so the
     * only thing it could mean is *dump the whole stored mapping* — the complete plaintext domain
     * glossary of the codebase, written outside the storage location this product chose so carefully
     * to keep it out of git and out of cloud sync. There is no such action here because there is
     * nowhere to invoke one from.
     *
     * On the left, where the platform puts an action that is neither the commit nor the way out.
     * Exporting is neither: it writes a file and leaves the dialog exactly where it was, so the user
     * can save the mapping and *then* copy — the order this ticket exists to make possible.
     *
     * **Disabled when there is nothing to write**, which the [rerender] keeps true as the user
     * reduces. See [exportable].
     *
     * **What it writes is this render, and on the reduction surface that is a render the copy can
     * still overtake.** [deliver] re-renders against the ledger as it stands at the moment of the
     * copy, so a second invocation that commits while this dialog is open moves the numbers, and a
     * file exported before that lands describes placeholders the copy never used. The cost is
     * under-recovery and never a wrong name — those placeholders are not in the reply either, since
     * they were never in the text that left — which is the direction everything here errs in. The
     * read-only re-open has no such window: it holds the invocation that was delivered.
     */
    private val export = object : AbstractAction("Export Mapping\u2026") {
        override fun actionPerformed(event: ActionEvent) = exportMapping(project, analysis, files)
    }.also { it.isEnabled = exportable() }

    /**
     * **The unlock, and the only way `Preserve` reaches a resolved name.**
     *
     * Link-styled and next to the table it acts on rather than a button in the dialog's row of
     * actions: it commits nothing, it copies nothing, and it changes one column of one table.
     *
     * Once unlocked it says so and stops being an offer — a live control reading *Unlock…* after the
     * unlock would be a lie about the state the row beside it is in. There is no way back to locked
     * inside one opening, and none is needed: the way back is closing the dialog, which is also the
     * only state this ticket guarantees.
     *
     * **The read-only opening never shows one** — [assemble] adds it to the reduction opening and to
     * nothing else, because a dialog over an invocation that has already left has no reduction to
     * unlock.
     */
    private val unlock = ActionLink(UNLOCK_LINK) { unlockPreserve() }

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
     *
     * **The `Preserve` unlock is covered by that**, and it is worth naming because it is the one a
     * reader would expect an exception for. An unlock that survived an opening is a reduction set
     * once and forgotten, which is the failure the governing rule exists to prevent — so it is not
     * here, it is not in any state holder, and the ticks under it are not either. It lives on
     * [MappingTableModel.unlocked], for as long as this dialog's table does.
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

    /**
     * The export, on both openings — the one control the read-only re-open keeps, because a file
     * that reverses a snippet is worth writing exactly when the snippet has already left.
     *
     * Public for the same reason [createActions] is: *"it is offered here, and only here"* is the
     * whole of what this ticket decided, and a guarantee nobody can assert is a guarantee nobody is
     * keeping.
     */
    public override fun createLeftSideActions(): Array<Action> = arrayOf(export)

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
            // On every header of this opening rather than on `Preserve` alone — see
            // [PreserveHeaderRenderer]. The tip is asked for on every render rather than fixed,
            // because what the column is for changes when the unlock does.
            val headers = PreserveHeaderRenderer(table.tableHeader, ::preserveTooltip)
            for (column in 0 until table.columnModel.columnCount) {
                table.columnModel.getColumn(column).headerRenderer = headers
            }
        }

        val splitter = JBSplitter(false, "SnippetVeil.Preview.Splitter", 0.6f)
        splitter.firstComponent = code

        // The unlock sits under the table rather than in the footer below the splitter: it acts on
        // one column of this table, where the footer's lines are statements about the whole
        // invocation.
        val mapping = JPanel(BorderLayout())
        mapping.add(JBScrollPane(table), BorderLayout.CENTER)
        if (reducible) {
            unlock.border = JBUI.Borders.emptyTop(6)
            mapping.add(unlock, BorderLayout.SOUTH)
        }
        splitter.secondComponent = mapping

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
        footer.add(notices)
        if (reducible) footer.add(commentsBox)
        showNotices()
        return footer
    }

    /**
     * The notices for whatever is currently rendered. Called as the footer is built and again on
     * every re-render, because both reductions the dialog offers can close a notice: keeping
     * comments empties the strip count, and preserving an unresolved name takes a placeholder out of
     * the output — and a disclosure that outlived the loss it discloses is a false statement about
     * the text in the pane beside it.
     */
    private fun showNotices() {
        notices.removeAll()
        for (notice in analysis.result.fidelityNotices()) {
            notices.add(JBLabel(notice).also { it.alignmentX = Component.LEFT_ALIGNMENT })
        }
        notices.revalidate()
        notices.repaint()
    }

    /**
     * A tick on a row, in or out. It reaches the engine as a **key** rather than as a name, which is
     * what keeps the reduction off the spine rule: the engine preserves a key it was going to
     * replace and ignores every other one, so a key aimed at a library symbol or at nothing at all
     * changes no character of the output.
     */
    private fun preserve(name: MappedName, released: Boolean) {
        val key = name.key ?: return
        if (released) preserved += key else preserved -= key
        rerender()
    }

    /**
     * **The warning, and then the column.** Nothing is preserved by unlocking — every box comes up
     * unticked, and the render is untouched until one is ticked — so what this buys is the sentence
     * in front of the reduction rather than the reduction.
     *
     * Cancel leaves it locked, which is the whole of the Cancel path: the flag is the only state
     * there is, and it is written after the answer rather than before it.
     */
    private fun unlockPreserve() {
        if (!confirmedUnlock(project)) return
        rows.unlocked = true
        unlock.text = UNLOCK_DONE
        unlock.isEnabled = false
    }

    /**
     * What the `Preserve` header says, which is a different sentence either side of the unlock —
     * locked, it explains the default and points at the unlock; unlocked, it says what a tick does.
     * See [PreserveHeaderRenderer].
     */
    private fun preserveTooltip(): String = if (rows.unlocked) UNLOCKED_TOOLTIP else LOCKED_TOOLTIP

    /** Both panes, the strip and the notices, from one more call to a pure function. Nothing else moves. */
    private fun rerender() {
        analysis = analysis.rendered(settings = settingsNow())
        code.text = analysis.result.text
        rows.showing = analysis.result.names
        strip.text = stripOf(analysis)
        export.isEnabled = exportable()
        showNotices()
    }

    /**
     * **Whether this render minted anything at all** — the question the export button answers, asked
     * of what is on screen rather than of what the dialog was handed, because a reduction can empty
     * the table and the button is a statement about the file it would write.
     *
     * An invocation that renamed nothing would export a header and no rows, under a balloon saying
     * the file reverses the snippet. That is the *`Show details`* rule read on this side: a count is
     * worth stating at zero, and an action offering to write nothing is a dead end dressed as an
     * offer.
     *
     * It asks [AnonymizationResult.mapping] rather than counting rows of its own, because that map
     * **is** what the file holds — `mappingCsv` writes its entries with the kind beside them — so
     * the offer and the file cannot come to disagree about whether there is anything to write.
     */
    private fun exportable(): Boolean = analysis.result.mapping.isNotEmpty()

    private fun settingsNow(): AnonymizationSettings = AnonymizationSettings(
        preservedSymbols = preserved.toSet(),
        keepComments = commentsBox.isSelected,
        internalLibraries = libraries,
    )

    companion object {

        /** The reduction surface: toggles, and a Copy button that is the commit point. */
        fun forCopy(project: Project, analysis: Analysis, files: MappingFiles = SavedMappingFiles): PreviewDialog =
            PreviewDialog(project, analysis, reducible = true, files = files)

        /** The balloon's `Show mapping`: the same dialog over an invocation that has already left. */
        fun forReview(project: Project, analysis: Analysis, files: MappingFiles = SavedMappingFiles): PreviewDialog =
            PreviewDialog(project, analysis, reducible = false, files = files)
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
 * `14 renamed · 3 unknown · 22 preserved` — every number every time, including the zeroes, because a
 * number that appeared only when it fired would make its absence unreadable. Preserved JDK and
 * third-party symbols are here rather than in the table: their preservation is deliberate and a
 * declared non-goal, so each would be a row the user can do nothing about.
 *
 * **The strip count is no longer one of them.** It became the comment fidelity notice, which
 * carries the same number and the split that makes it actionable — and a footer that said it twice
 * would read as a bug. The notices sit on their own lines below this one and follow the opposite
 * rule: nothing at all when the loss did not happen.
 *
 * *Selection expanded to whole tokens* is the exception among the counts, and it is conditional in
 * the other direction: always-on it is noise, and conditional it is information. It fires only when
 * snapping actually moved an end of the selection — the copy then contains text the user did not
 * select, and the pane beside this line is where they can see what. It stays a clause of this line
 * rather than a third notice because it is a fact about how the snippet was *cut*, which the pane
 * beside it shows in full; the notices are about what is missing from that pane.
 */
internal fun stripOf(analysis: Analysis): String {
    val counts = analysis.result.counts
    val strip = "${counts.replaced} renamed · ${counts.unknown} unknown · ${counts.preserved} preserved"
    return if (analysis.plan.selectionExpanded) "$strip · selection expanded to whole tokens" else strip
}

/**
 * **Rows are placeholders; preservation by design is a count.**
 *
 * Every symbol that received one is a row, `Unknown`s included, in first-occurrence order — that
 * ordering is the engine's, not this model's, which is what lets the sort start where the snippet
 * does. Library symbols preserved by the spine rule are not rows: listing them literally would
 * produce a table where `String`, `List` and `println` drown the fourteen rows that matter, and
 * there is nothing a user could do about one.
 *
 * **The `Preserve` column exists only where a reduction is possible, and its cells only where a
 * reduction is offered** — on `Unknown` rows while [unlocked] is false, and on every keyed row once
 * it is true. A literal row never has one: it has no key, and literal text is the most directly
 * sensitive content the product handles.
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

    /**
     * **Whether `Preserve` reaches the resolved rows** — `false` until [PreviewDialog] has shown the
     * warning and been told to go ahead, and `false` again on the next open because the dialog that
     * holds it is a new one.
     *
     * Nothing is ticked by setting it. The tick state is read off the render — a row with no
     * placeholder is a preserved row — so rows that were never preserved come up unticked without
     * anything having to unset them, and an `Unknown` already ticked keeps its tick.
     */
    var unlocked: Boolean = false
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
            // name is: no placeholder, and its own text in the output. A row that offers no
            // reduction has nothing here at all — see [PreserveRenderer].
            else -> if (offersPreserve(name)) name.placeholder == null else null
        }
    }

    override fun isCellEditable(row: Int, column: Int): Boolean =
        column == PRESERVE && offersPreserve(showing[row])

    /**
     * Whether this row offers the override. **The key decides**, not the kind: a preserve travels as
     * a key, so a row without one — a replaced literal — has nothing to hand back and is not offered
     * a box it could not act on. The kind decides only what is offered while the column is locked.
     */
    private fun offersPreserve(name: MappedName): Boolean =
        reducible && name.key != null && (unlocked || name.kind == MappedKind.UNKNOWN)

    override fun setValueAt(value: Any?, row: Int, column: Int) {
        if (column == PRESERVE) onPreserve(showing[row], value == true)
    }
}

/**
 * **The `Preserve` header, saying who the column is for.**
 *
 * The column's common state is empty — everything resolved is the good case — and an empty column
 * under a header nobody explained reads as a control the user has lost rather than as the good news
 * it is. Two sentences answer both halves of the question actually asked: what the override is, and
 * what an empty column means. It is a header renderer and nothing else — the cells say nothing
 * extra, because the rows that offer the override already show a checkbox and the rest **are** the
 * answer to the second sentence.
 *
 * **Installed on every header of the reduction opening, not on `Preserve` alone.** The header's
 * default renderer hands back one component for every column it draws, so a tip set on it and never
 * cleared is still on it when the next header is drawn — the tip would follow the shared component
 * onto `Original`. Every column has to pass through here for that to be impossible rather than
 * unlikely. (The read-only opening installs nothing, because it has no such column.)
 *
 * **What it takes away is its own sentence and nothing else.** Blanking the tip on the other three
 * headers would be this ticket editing columns it was not given: whatever the platform says about a
 * header — a truncated title, say — is that header's, and a renderer that clears the field wholesale
 * silences it. So the other columns are left as the delegate rendered them, unless what is on them
 * is the sentence this class put there.
 *
 * The column is read back through [JTable.convertColumnIndexToModel] because the platform hands a
 * header renderer the view index, and the columns are draggable. The delegate is asked of the header
 * on every render rather than captured, because the renderer it hands back is the current theme's —
 * and [tooltip] is asked on every render for the same kind of reason: the sentence changes when the
 * column is unlocked, and a tip captured at assembly would go on saying *unlock it* afterwards.
 *
 * **What counts as "ours" is either sentence**, which is why the clearing arm reads a set. A header
 * still carrying the locked tip after the unlock is one this class put there and has to take away.
 */
private class PreserveHeaderRenderer(
    private val header: JTableHeader,
    private val tooltip: () -> String,
) : TableCellRenderer {

    override fun getTableCellRendererComponent(
        table: JTable,
        value: Any?,
        selected: Boolean,
        focused: Boolean,
        row: Int,
        column: Int,
    ) = header.defaultRenderer.getTableCellRendererComponent(table, value, selected, focused, row, column).also {
        if (it is JComponent) {
            when {
                table.convertColumnIndexToModel(column) == PRESERVE -> it.toolTipText = tooltip()
                it.toolTipText in PRESERVE_TOOLTIPS -> it.toolTipText = null
            }
        }
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

/**
 * **What the `Preserve` header says while the column is locked**, which is the state every opening
 * starts in.
 *
 * Two sentences, and they answer the two halves of the question a locked column actually raises: why
 * most rows have no box, and what to do about it if you wanted one. The second half is the one the
 * unlock made sayable: while the override reached unresolved names alone the answer was *nothing*,
 * and the sentence this replaced — *"only names SnippetVeil could not resolve can be preserved"* —
 * is now simply false.
 */
internal const val LOCKED_TOOLTIP =
    "By default only names SnippetVeil could not resolve can be preserved. " +
        "Unlock Preserve for resolved names to tick any name in this table."

/**
 * **And what it says once it is unlocked**: what a tick does, in the plainest terms there are.
 *
 * Not a repeat of the unlock warning. The warning is the decision and it has been made; this is the
 * label on the column that decision opened, and it has to be true of every box in it.
 */
private const val UNLOCKED_TOOLTIP = "Ticked names are emitted exactly as written in your code."

/**
 * Both sentences this file puts on a header — read by the renderer's clearing arm, which has to
 * recognise its own tip whichever state it was written in. See [PreserveHeaderRenderer].
 */
private val PRESERVE_TOOLTIPS = setOf(LOCKED_TOOLTIP, UNLOCKED_TOOLTIP)

/** The unlock, before and after. See [PreviewDialog.unlock]. */
internal const val UNLOCK_LINK = "Unlock Preserve for resolved names\u2026"

private const val UNLOCK_DONE = "Preserve unlocked for this preview"

/**
 * **The unlock's title**, phrased as the question the click asked rather than as a label. It is the
 * one moment the product asks a user to confirm a reduction, and *`Preserve`* alone would name the
 * column without naming the decision.
 */
private const val UNLOCK_TITLE = "Unlock Preserve for all names?"

/**
 * **What unlocking means, said as consequence rather than as caution.**
 *
 * Three sentences and none of them is *are you sure*: the first says where a preserved name goes,
 * the second says what SnippetVeil stops doing, and the third gives a test a person can actually
 * apply to the name in front of them. A user who reads only the last sentence has still been told
 * the thing that matters.
 */
private const val UNLOCK_MESSAGE =
    "Preserved names are sent exactly as written in your code. SnippetVeil will not conceal a name " +
        "you tick.\n\nOnly preserve names you would be comfortable typing into the chat yourself."

/** The button that carries the unlock out — named for the decision, not `OK`. */
private const val UNLOCK_CONFIRM = "I understand, unlock"

private const val UNLOCK_CANCEL = "Cancel"

/**
 * The warning, asked of the user. Separate from [PreviewDialog.unlockPreserve] for the reason
 * [MappingReset] separates its own: the question is a modal window and the consequence is not, and a
 * Cancel has to leave *nothing* changed — which is easiest to be sure of when the only writer runs
 * after the only question.
 */
private fun confirmedUnlock(project: Project): Boolean = Messages.showYesNoDialog(
    project,
    UNLOCK_MESSAGE,
    UNLOCK_TITLE,
    UNLOCK_CONFIRM,
    UNLOCK_CANCEL,
    Messages.getWarningIcon(),
) == Messages.YES
