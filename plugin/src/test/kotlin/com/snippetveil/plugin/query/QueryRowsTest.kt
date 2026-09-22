package com.snippetveil.plugin.query

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.JDOMUtil
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.psi.SqlFile
import com.intellij.util.xmlb.XmlSerializer
import com.snippetveil.core.AnonymizationSettings
import com.snippetveil.core.LedgerSnapshot
import com.snippetveil.core.MappedKind
import com.snippetveil.core.NameCounts
import com.snippetveil.core.Renaming
import com.snippetveil.core.SnippetPlan
import com.snippetveil.core.SourceLanguage
import com.snippetveil.core.SqlKeys
import com.snippetveil.core.SymbolOccurrence
import com.snippetveil.core.SymbolRole
import com.snippetveil.core.Unrestored
import com.snippetveil.core.UnrestoredReason
import com.snippetveil.core.stemOf
import com.snippetveil.plugin.Analysis
import com.snippetveil.plugin.FirstRunNotice
import com.snippetveil.plugin.InjectedFragment
import com.snippetveil.plugin.InternalLibrarySettings
import com.snippetveil.plugin.KIND_COLUMN
import com.snippetveil.plugin.PLACEHOLDER_COLUMN
import com.snippetveil.plugin.PRESERVE_COLUMN
import com.snippetveil.plugin.PlaceholderLedger
import com.snippetveil.plugin.PlaceholderSidecar
import com.snippetveil.plugin.PreviewDialog
import com.snippetveil.plugin.RENAME_TOOLTIP
import com.snippetveil.plugin.UNLOCK_LINK
import com.snippetveil.plugin.answerDialogsWith
import com.snippetveil.plugin.cellTooltipAt
import com.snippetveil.plugin.clipboard
import com.snippetveil.plugin.commitStem
import com.snippetveil.plugin.reversalFor
import com.snippetveil.plugin.rowOf
import com.snippetveil.plugin.stripOf
import com.snippetveil.plugin.symbols
import com.snippetveil.plugin.tableIn
import com.snippetveil.plugin.unlockIn
import com.snippetveil.plugin.withDialog
import javax.swing.JTable

/**
 * **A decomposed query's rows, in the preview and on the way back** — what a `table1` row offers, what
 * it does not, and what the return leg makes of it once the sidecar has forgotten the snippet.
 *
 * Nothing here is new machinery. A query row is a row minted by this invocation against a key of its
 * own, so it takes the states every such row already has; what these fixtures pin is that the real
 * decomposition lands on them, over real injected SQL, rather than on something a plan literal said.
 *
 *  - **The rows.** The kind column reads `table`, `col` and `schema`, and rows sort by first occurrence.
 *  - **`Preserve` reaches a query row through the unlock only.** There is no query `Unknown`, so the
 *    default tick has nothing to sit on; the unlock is locked again on every open, and neither it nor
 *    a tick is written anywhere. The fast path reads no reduction at all.
 *  - **The literal exclusion does not reach a query row** — and the enclosing redacted literal of a
 *    fragment that fell back is still not preservable.
 *  - **A query row is renamable**, and the rename lasts the invocation: its key is unqualified, so
 *    the next snippet re-mints `table`. The engine refuses a `$`.
 *  - **The counts strip does not move.** No entry and no `(n from SQL)` split.
 *  - **The return leg recognises the new placeholders** by their namespace, and a renamed one by the
 *    minted-stems set, with no sibling spellings written for either.
 */
internal class QueryRowsTest : QuerySnippetTestCase() {

    fun `test query rows read table, col and schema, in first-occurrence order`() {
        assertTheQueryHarnessHolds()
        val analysis = analysisOver(AROUND_A_QUERY)

        withDialog(PreviewDialog.forCopy(project, analysis)) { dialog ->
            val table = tableIn(dialog)

            assertEquals(
                listOf(
                    "run" to "method",
                    "query" to "local",
                    "state" to "col",
                    "billing" to "schema",
                    "customers" to "table",
                    "count" to "local",
                ),
                (0 until table.rowCount).map { table.getValueAt(it, 0) to table.getValueAt(it, KIND_COLUMN) },
            )
            assertEquals(
                "the rows are not in the order the placeholders appear in the pane",
                (0 until table.rowCount).map { table.getValueAt(it, PLACEHOLDER_COLUMN).toString() },
                (0 until table.rowCount)
                    .map { table.getValueAt(it, PLACEHOLDER_COLUMN).toString() }
                    .sortedBy { placeholder -> Regex("""\b$placeholder\b""").find(dialog.analysis.result.text)!!.range.first },
            )
        }
    }

    /**
     * **Locked, a query row has no box; unlocked, it has one, unticked; reopened, it is locked
     * again.** There is no query `Unknown` for the default tick to sit on — the fixture asserts so —
     * so the unlock is the only route there is, and it is the route whose friction is the point.
     */
    fun `test a query row offers no Preserve tick until the unlock, and is locked again on reopen`() {
        assertTheQueryHarnessHolds()
        val analysis = analysisOver(A_QUERY)
        assertEquals("a query produced an Unknown", 0, analysis.result.counts.unknown)
        assertFalse("a query row is an Unknown", analysis.result.names.any { it.kind == MappedKind.UNKNOWN })
        val warnings = mutableListOf<String>()
        answerDialogsWith(testRootDisposable, Messages.YES) { warnings += it }

        withDialog(PreviewDialog.forCopy(project, analysis)) { dialog ->
            val table = tableIn(dialog)
            val rows = 0 until table.rowCount

            assertEquals(QUERY_NAMES, rows.map { table.getValueAt(it, 0) })
            assertEquals("a query row is preservable while locked", rows.map { false }, rows.map { table.isCellEditable(it, PRESERVE_COLUMN) })
            assertEquals("a locked query row shows a box", rows.map { null }, rows.map { table.getValueAt(it, PRESERVE_COLUMN) })

            assertEquals("something warned before the unlock was asked for", emptyList<String>(), warnings)
            unlockIn(dialog).doClick()

            assertEquals("the unlock did not warn exactly once before opening the column", 1, warnings.size)
            assertEquals(rows.map { true }, rows.map { table.isCellEditable(it, PRESERVE_COLUMN) })
            assertEquals(rows.map { false }, rows.map { table.getValueAt(it, PRESERVE_COLUMN) })

            table.setValueAt(true, rowOf(table, "customers"), PRESERVE_COLUMN)
            assertEquals("\"SELECT col1 FROM schema2.customers\"", dialog.analysis.result.text)
        }

        // A fresh analysis, as the next invocation would hand over: the first dialog's state has to
        // be gone because the dialog is, and not because this one was handed the same object.
        withDialog(PreviewDialog.forCopy(project, analysisOver(A_QUERY))) { dialog ->
            val table = tableIn(dialog)

            assertEquals(UNLOCK_LINK, unlockIn(dialog).text)
            assertFalse("the reopened preview inherited the unlock", table.isCellEditable(rowOf(table, "customers"), PRESERVE_COLUMN))
            assertEquals("the reopened preview inherited the tick", "\"SELECT col1 FROM schema2.table3\"", dialog.analysis.result.text)
        }
    }

    /**
     * **Neither the unlock nor a tick is written down, and the fast path reads no reduction.** A
     * preview that unlocked, ticked a table and was *confirmed* — the reduction actually left — is
     * followed by a `Copy Anonymized` whose text is exactly what the defaults render against the
     * mapping as it then stands: the table replaced, the tick nowhere. The next preview opens locked,
     * with nothing ticked, and nothing the copy committed names the ticked key.
     *
     * A cancelled preview is the other half: it moves nothing the fast path could read either.
     */
    fun `test neither the unlock nor a tick is persisted, and the fast path is identical whatever a preview did`() {
        assertTheQueryHarnessHolds()
        answerDialogsWith(testRootDisposable, Messages.YES)
        val ticked = SqlKeys.named(SymbolRole.TABLE, "customers")

        for (confirm in listOf(true, false)) {
            PlaceholderLedger.getInstance().loadState(PlaceholderLedger.State())
            PlaceholderSidecar.getInstance(project).loadState(PlaceholderSidecar.State())
            myFixture.configureByText("Repository.java", oneQuery(A_QUERY))
            var released: String? = null
            invokeWithPreview { project, analysis ->
                previewing(project, analysis, copy = confirm) { dialog, table ->
                    unlockIn(dialog).doClick()
                    table.setValueAt(true, rowOf(table, "customers"), PRESERVE_COLUMN)
                    released = dialog.analysis.result.text
                }
            }
            if (confirm) {
                awaitBackgroundWork()
                assertEquals("the confirmed preview did not deliver its reduction", released, clipboard())
                assertTrue("the fixture ticked nothing: $released", "customers" in released!!)
            } else {
                awaitEvents("the preview was never opened") { released != null }
            }

            val written = persistedState()
            assertFalse("the ticked key was written down (confirmed: $confirm):\n$written", ticked in written)
            assertFalse("the ticked name was written down (confirmed: $confirm):\n$written", "customers" in written)

            val ledger = PlaceholderLedger.getInstance()

            val plan = planFor("Repository.java", oneQuery(A_QUERY))
            val expected = Analysis.of(plan, AnonymizationSettings.DEFAULTS, ledger.snapshotOf(project)).result.text
            invokeCopyAnonymized()

            assertEquals("the fast path read a reduction (confirmed: $confirm)", expected, clipboard())
            assertFalse("the ticked table went out on the fast path: ${clipboard()}", "customers" in clipboard())

            myFixture.configureByText("Repository.java", oneQuery(A_QUERY))
            var opened: AnonymizationSettings? = null
            var locked: Boolean? = null
            invokeWithPreview { project, analysis ->
                previewing(project, analysis, copy = false) { dialog, table ->
                    opened = dialog.analysis.settings
                    locked = !table.isCellEditable(rowOf(table, "customers"), PRESERVE_COLUMN) && unlockIn(dialog).text == UNLOCK_LINK
                }
            }
            awaitEvents("the next preview was never opened") { locked != null }
            assertEquals("the next preview opened with a tick", emptySet<String>(), opened!!.preservedSymbols)
            assertTrue("the next preview opened unlocked", locked!!)
        }
    }

    /**
     * **The literal exclusion is about the whole literal, and still holds of it.** A fragment that
     * fell back is one redacted literal, which has no key to travel as — so even unlocked, its row
     * offers no box, while a Java row beside it does.
     */
    fun `test the enclosing redacted literal of a fallen-back fragment is still non-preservable`() {
        assertTheQueryHarnessHolds()
        val fallingBack = "SELECT id FROM customers WHERE id = ?"
        val analysis = analysisOver(AROUND_A_QUERY.replace(A_QUERY, fallingBack))
        assertFalse("the fragment decomposed", analysis.plan.symbols().any { it.language == SourceLanguage.SQL })
        answerDialogsWith(testRootDisposable, Messages.YES)

        withDialog(PreviewDialog.forCopy(project, analysis)) { dialog ->
            val table = tableIn(dialog)
            val literal = (0 until table.rowCount).single { table.getValueAt(it, KIND_COLUMN) == MappedKind.LITERAL.label }
            assertEquals(fallingBack, table.getValueAt(literal, 0))

            unlockIn(dialog).doClick()

            assertFalse("the redacted literal is preservable", table.isCellEditable(literal, PRESERVE_COLUMN))
            assertNull("the redacted literal shows a box", table.getValueAt(literal, PRESERVE_COLUMN))
            assertTrue("the control failed: a Java row is not preservable either", table.isCellEditable(rowOf(table, "run"), PRESERVE_COLUMN))
        }
    }

    /**
     * **A query row is renamable, and the rename lasts the invocation.** The row is minted by this
     * invocation against a key of its own, so it takes the ordinary editable state; the key is
     * unqualified, so nothing carries the stem to the next snippet, which re-mints `table` — asserted
     * rather than assumed, through a real copy of each.
     */
    fun `test a query row's placeholder is editable, and a renamed row re-mints under the default stem next time`() {
        assertTheQueryHarnessHolds()
        myFixture.configureByText("Repository.java", oneQuery(A_QUERY))

        invokeWithPreview { project, analysis ->
            previewing(project, analysis) { _, table ->
                val row = rowOf(table, "customers")
                assertTrue("a query row is not renamable", table.isCellEditable(row, PLACEHOLDER_COLUMN))
                assertEquals(RENAME_TOOLTIP, cellTooltipAt(table, row, PLACEHOLDER_COLUMN))
                commitStem(table, row, "OrdersTable")
            }
        }
        awaitBackgroundWork()
        assertTrue("the rename did not reach the clipboard: ${clipboard()}", Regex("""\bOrdersTable\d+\b""") in clipboard())

        val next = analysisOver(A_QUERY, ledgered = true)
        val row = next.result.names.single { it.original == "customers" }
        assertEquals("table", stemOf(row.placeholder!!))
        assertEquals(Renaming.OFFERED, row.renaming)

        myFixture.configureByText("Repository.java", oneQuery(A_QUERY))
        invokeCopyAnonymized()
        assertFalse("the rename outlived its invocation: ${clipboard()}", "OrdersTable" in clipboard())
        assertTrue("the next snippet did not re-mint the table: ${clipboard()}", Regex("""\btable\d+\b""") in clipboard())
    }

    /**
     * **The engine refuses a `$` on a query row**, whatever the editor would have said: handed the
     * stem directly, it falls back to `table` and records no word. The editor refusing it too is the
     * same rule read on the other side.
     */
    fun `test a stem containing a dollar is refused for a query row by the engine`() {
        assertTheQueryHarnessHolds()
        val analysis = analysisOver(A_QUERY)
        val key = analysis.result.names.single { it.original == "customers" }.key!!

        val refused = analysis.rendered(settings = AnonymizationSettings(renamedStems = mapOf(key to "my\$table")))

        assertEquals("\"SELECT col1 FROM schema2.table3\"", refused.result.text)
        assertEquals(emptySet<String>(), refused.result.delta.mintedStems)

        val accepted = analysis.rendered(settings = AnonymizationSettings(renamedStems = mapOf(key to "myTable")))
        assertEquals("the control failed: no stem reaches a query row", "\"SELECT col1 FROM schema2.myTable3\"", accepted.result.text)
    }

    /**
     * **The counts strip is byte-identical with and without a decomposed query** — the same snippet,
     * its query's names once carrying their SQL provenance and once carrying none. A strip that
     * could tell the two apart is one whose clause a user in an IDE that does not decompose would
     * never see, so its absence would be the in-product signal that a query went out whole.
     *
     * **What this does not claim**: that a query which *fell back* counts the same as one that
     * decomposed. It does not — three names renamed are three names counted, and a query replaced
     * whole names none — and that is a number, not a clause. The counts read no occurrence's language
     * today, so this passes by construction; it is here for the day something makes them read it.
     */
    fun `test the counts strip is byte-identical with and without a decomposed query`() {
        assertTheQueryHarnessHolds()
        val plan = planFor("Repository.java", AROUND_A_QUERY)
        assertTrue("the query did not decompose", plan.symbols().any { it.language == SourceLanguage.SQL })

        val decomposed = Analysis.of(plan, AnonymizationSettings.DEFAULTS, PlaceholderLedger.getInstance().snapshotOf(project))
        val plain = Analysis.of(withoutQueryProvenance(plan), AnonymizationSettings.DEFAULTS, PlaceholderLedger.getInstance().snapshotOf(project))
        assertEquals("the fixture changed more than provenance", decomposed.result.text, plain.result.text)

        assertEquals(stripOf(plain), stripOf(decomposed))
        // `String` is the one preserved name, and the query's three are among the six renamed.
        assertEquals("6 renamed · 0 unknown · 1 preserved", stripOf(decomposed))
    }

    /**
     * **The refused `(n from SQL)` split** — named here so that nobody adds it believing it an
     * omission. No count carries a query-sourced breakdown: [NameCounts] holds three numbers and
     * nothing else, and neither the strip nor the balloon says anything about a query.
     */
    fun `test no count carries the refused n-from-SQL split`() {
        assertTheQueryHarnessHolds()
        assertEquals(
            listOf("preserved", "replaced", "unknown"),
            NameCounts::class.java.declaredFields.map { it.name }.sorted(),
        )

        val analysis = analysisOver(AROUND_A_QUERY)
        assertTrue("the query did not decompose", analysis.plan.symbols().any { it.language == SourceLanguage.SQL })
        assertTrue("the strip carries a clause: ${stripOf(analysis)}", COUNTS_ONLY.matches(stripOf(analysis)))

        myFixture.configureByText("Repository.java", AROUND_A_QUERY)
        invokeCopyAnonymized()
        val counts = notifications.single().content.substringBefore("<br>")
        assertTrue("the balloon's counts carry a clause: $counts", Regex("""\d+ names replaced · \d+ unknown · \d+ preserved""").matches(counts))
    }

    /**
     * **The return leg admits `table`, `col` and `schema` by arriving.** A copied query's names are
     * never ledgered, so once the sidecar has forgotten the snippet the only thing that can tell a
     * reply's `table3` from a word of the model's own is the shape recogniser — and each of the three
     * is reported, not passed over.
     *
     * And **no sibling spelling is written for any of them**: nothing derives an accessor from a
     * table name, so the mapping holds no row at all for this snippet.
     */
    fun `test each query namespace is recognised once the sidecar has forgotten, and no sibling row is written`() {
        assertTheQueryHarnessHolds()
        myFixture.configureByText("Repository.java", oneQuery(A_QUERY))
        invokeCopyAnonymized()
        assertEquals("\"SELECT col1 FROM schema2.table3\"", clipboard())

        val ledger = PlaceholderLedger.getInstance().snapshotOf(project)
        assertEquals("a query placeholder wrote a mapping row", emptyMap<String, Any>(), ledger.placeholders)

        PlaceholderSidecar.getInstance(project).loadState(PlaceholderSidecar.State())
        // `rows4` is placeholder-shaped and in no namespace: the control that *admitted* is about
        // the three namespaces, not about any word that ends in digits.
        val back = reversalFor(project, "Filter col1 before joining table3 in schema2, not rows4.")

        assertEquals(emptyList<String>(), back.restored)
        assertEquals(
            listOf("col1", "table3", "schema2").map { Unrestored(it, UnrestoredReason.EVICTED) },
            back.unrestored,
        )
    }

    /**
     * **A renamed query row round-trips through the minted-stems set.** Its key was never ledgered,
     * and the sidecar is emptied before the reply arrives — so `OrdersTable1` is in neither table, and
     * the recorded word is the one reason it is recognised as this project's rather than pasted into
     * source as a name somebody chose. A fresh invocation in between does not take the word away.
     */
    fun `test a renamed query row round-trips through the minted stems from a fresh invocation with the sidecar empty`() {
        assertTheQueryHarnessHolds()
        myFixture.configureByText("Repository.java", oneQuery(A_QUERY))
        invokeWithPreview { project, analysis ->
            previewing(project, analysis) { _, table -> commitStem(table, rowOf(table, "customers"), "OrdersTable") }
        }
        awaitBackgroundWork()
        val renamed = Regex("""\bOrdersTable\d+\b""").find(clipboard())?.value
        assertNotNull("the rename did not reach the clipboard: ${clipboard()}", renamed)
        assertTrue("the stem was not recorded", "OrdersTable" in PlaceholderLedger.getInstance().snapshotOf(project).mintedStems)

        myFixture.configureByText("Repository.java", oneQuery(A_QUERY))
        invokeCopyAnonymized()
        PlaceholderSidecar.getInstance(project).loadState(PlaceholderSidecar.State())

        val back = reversalFor(project, "Add an index on $renamed.")

        assertEquals(listOf(Unrestored(renamed!!, UnrestoredReason.EVICTED)), back.unrestored)
        assertEquals("Add an index on $renamed.", back.text)
    }

    /**
     * The real dialog over [analysis], standing where the modal window stands: [act] is what the user
     * does in it, and then they press Copy — or, when [copy] is `false`, Cancel. What comes back is
     * what the window would have handed the action: the analysis on screen, or `null`.
     */
    private fun previewing(
        project: Project,
        analysis: Analysis,
        copy: Boolean = true,
        act: (PreviewDialog, JTable) -> Unit,
    ): Analysis? {
        val dialog = PreviewDialog.forCopy(project, analysis)
        try {
            act(dialog, tableIn(dialog))
            return dialog.analysis.takeIf { copy }
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }

    /**
     * [source] analysed as the action analyses it, with the one query in it asserted injected as SQL
     * first — against an empty mapping, or against the mapping as it stands when [ledgered].
     */
    private fun analysisOver(source: String, ledgered: Boolean = false): Analysis {
        val text = if ("<selection>" in source) source else oneQuery(source)
        val plan = planFor("Repository.java", text)
        assertEveryQueryInjectedAsSql()
        val ledger = if (ledgered) PlaceholderLedger.getInstance().snapshotOf(project) else LedgerSnapshot.EMPTY
        return Analysis.of(plan, AnonymizationSettings.DEFAULTS, ledger)
    }

    /** Every string literal in the fixture that holds a `SELECT` was injected as one SQL fragment. */
    private fun assertEveryQueryInjectedAsSql() {
        val hosts = PsiTreeUtil.findChildrenOfType(myFixture.file, PsiLanguageInjectionHost::class.java).filter { "SELECT" in it.text }
        assertFalse("the fixture holds no query", hosts.isEmpty())
        for (host in hosts) {
            val fragments = InjectedFragment.injectedInto(host)
            assertTrue("the IDE did not inject SQL into ${host.text}", fragments.size == 1 && fragments.single().file is SqlFile)
        }
    }

    /** [plan] with every SQL name retagged as Java — the same names, with no query behind them. */
    private fun withoutQueryProvenance(plan: SnippetPlan): SnippetPlan = SnippetPlan(
        plan.text,
        plan.occurrences.map {
            if (it is SymbolOccurrence && it.language == SourceLanguage.SQL) {
                SymbolOccurrence(it.start, it.end, it.text, it.symbol, SourceLanguage.JAVA, it.nameStart, it.nameEnd)
            } else {
                it
            }
        },
        plan.rootPackage,
        plan.selectionExpanded,
    )

    /**
     * Everything shipped that the platform writes to disk, as the platform would write it — through
     * `XmlSerializer`, which is what `@State` uses, so that what is asserted absent is absent from
     * the file rather than from a field.
     */
    private fun persistedState(): String = listOf(
        PlaceholderLedger.getInstance().state,
        PlaceholderSidecar.getInstance(project).state,
        InternalLibrarySettings.of(project).state,
        FirstRunNotice.getInstance().state,
    ).joinToString("\n") { JDOMUtil.write(XmlSerializer.serialize(it)) }
}

/** The query every fixture here decomposes: a column, a schema and a table, in that order. */
private const val A_QUERY = "SELECT state FROM billing.customers"

/** The originals [A_QUERY] produces rows for, in first-occurrence order. */
private val QUERY_NAMES = listOf("state", "billing", "customers")

/** A method holding [A_QUERY] between two Java locals, the whole method selected. */
private val AROUND_A_QUERY = """
    class Repository {
        <selection>void run() {
            // language=SQL
            String query = "$A_QUERY";
            int count = 0;
        }</selection>
    }
""".trimIndent()

/** The strip with nothing but its three counts — no clause, conditional or otherwise. */
private val COUNTS_ONLY = Regex("""\d+ renamed · \d+ unknown · \d+ preserved""")
