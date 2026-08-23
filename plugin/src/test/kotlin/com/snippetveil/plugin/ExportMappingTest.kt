package com.snippetveil.plugin

import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.snippetveil.core.AnonymizationSettings
import com.snippetveil.core.LedgerSnapshot
import java.awt.Component
import java.awt.Container
import java.awt.event.ActionEvent
import java.io.IOException
import javax.swing.Action
import javax.swing.JTable

/**
 * **`Export Mapping…`** — the file that keeps one conversation decodable past the horizon.
 *
 * The format is `:core`'s and is asserted there, over values. What can only be said here is what the
 * *export* does: what it writes, what it says afterwards, what it leaves alone, and — the criterion
 * the whole feature exists for — that the file it wrote reverses the snippet.
 */
class ExportMappingTest : JavaSnippetTestCase() {

    /**
     * **This invocation's table, and the sentence that says what the file is.**
     *
     * The balloon is one line and the second half of it is load-bearing: the file is a reversal key,
     * so a message that said only *"Mapping saved"* would leave the user with a plaintext glossary
     * of the snippet in `~/Downloads` and no reason to think of it as one.
     */
    fun `test the export writes this invocation's mapping and says what the file is`() {
        assertTheHarnessResolves()
        val files = FakeMappingFiles()

        exportMapping(project, analysisOf(SNIPPET), files)

        assertEquals(
            "Placeholder,Original,Kind\r\n" +
                "method1,settle,method\r\n" +
                "param2,amount,param\r\n",
            files.written,
        )
        assertEquals(
            "Mapping saved. This file reverses the anonymized snippet.",
            notifications.single().content,
        )
    }

    /**
     * **A cancelled chooser writes nothing and says nothing.** The user closed a dialog; a balloon
     * reporting that would be the tool announcing its own no-op, and it would sit in the same place
     * and the same colour as the one that reports a key being written to disk.
     */
    fun `test a cancelled save writes nothing and says nothing`() {
        assertTheHarnessResolves()
        val files = FakeMappingFiles(cancelled = true)

        exportMapping(project, analysisOf(SNIPPET), files)

        assertNull(files.written)
        assertEmpty(notifications)
    }

    /**
     * **A write that failed says so, and says what was lost rather than what the disk did.**
     *
     * It states that the mapping was not saved rather than that no file exists: a write that fails
     * partway leaves a truncated file behind, and a message that must not contain a lie may not say
     * the one thing this code cannot check.
     */
    fun `test a failed write says the mapping was not saved`() {
        assertTheHarnessResolves()

        exportMapping(project, analysisOf(SNIPPET), FakeMappingFiles(failing = true))

        val balloon = notifications.single()
        assertEquals("Mapping export failed — the mapping was not saved.", balloon.content)
        assertEquals(NotificationType.ERROR, balloon.type)
    }

    /**
     * **Nothing about an export reaches the clipboard**, and that is the refusal this whole feature
     * is shaped around rather than an incidental fact about a file writer.
     *
     * *Copy Anonymized + Mapping* does not exist and must not be built: it would put the
     * deanonymization key on the same clipboard as the anonymized code, and the overwhelmingly
     * likely next keystroke is a paste into the AI chat — which hands over everything the plugin
     * just concealed. The export is the affordance that answered that demand, so an export that
     * touched the clipboard would have quietly built the thing it replaced.
     */
    fun `test exporting leaves the clipboard exactly as it was`() {
        assertTheHarnessResolves()
        setClipboard("something the user copied earlier")

        exportMapping(project, analysisOf(SNIPPET), FakeMappingFiles())

        assertEquals("something the user copied earlier", clipboard())
    }

    /**
     * **Both openings offer it, and it is the only place either of them offers it from.**
     *
     * The read-only re-open matters more than the reduction surface does here: `Copy Anonymized` is
     * the fast path and shows no dialog at all, so the balloon's `Show mapping` is where an
     * invocation that has already left is reachable — and an export offered only where the user
     * stopped to look would miss every invocation that did not stop.
     */
    fun `test both openings offer Export Mapping`() {
        assertTheHarnessResolves()
        val analysis = analysisOf(SNIPPET)

        for (dialog in listOf(PreviewDialog.forCopy(project, analysis), PreviewDialog.forReview(project, analysis))) {
            withDialog(dialog) {
                assertEquals(listOf("Export Mapping\u2026"), it.createLeftSideActions().map(::nameOf))
            }
        }
    }

    /**
     * **What is exported is what is on screen, not what the dialog was handed.**
     *
     * The preview is the one place a user may anonymize *less*, so the two can differ — and the file
     * is a key to the text that gets copied. A file describing a render the user changed their mind
     * about would decode a snippet nobody has.
     *
     * The preserved row is gone from the file **and the number after it moved up**, which is the
     * stronger half: nothing was allocated for a name standing for itself, so a file written off the
     * opening render would disagree with the copied text on every row below the tick.
     */
    fun `test the export writes the render as it stands, reductions included`() {
        assertTheHarnessResolves()
        val files = FakeMappingFiles()
        val analysis = analysisOf("class Ledger { <selection>void settle(MissingType m) {}</selection> }")

        withDialog(PreviewDialog.forCopy(project, analysis, files)) { dialog ->
            val table = tableIn(dialog)
            val unknown = (0 until table.rowCount).single { table.getValueAt(it, 2) == "Unknown" }
            table.setValueAt(true, unknown, PRESERVE_COLUMN)

            export(dialog)
        }

        assertEquals(
            "the preserved name is in the file, which means the file describes a render nobody copied",
            "Placeholder,Original,Kind\r\nmethod1,settle,method\r\nparam2,m,param\r\n",
            files.written,
        )
    }

    /**
     * **An invocation that minted no placeholders has nothing to export, and the button says so by
     * being disabled** — the same rule `Show details` follows on the reversal balloon: a number is
     * worth stating at zero, and an action offering to write nothing is a dead end dressed as an
     * offer. The file it would write is a header and no rows, under a balloon claiming it reverses
     * the snippet.
     */
    fun `test the export is not offered when this invocation minted no placeholder`() {
        assertTheHarnessResolves()
        val nothing = analysisOf("class Ledger { void settle() { <selection>String.valueOf(1);</selection> } }")

        withDialog(PreviewDialog.forCopy(project, nothing)) { dialog ->
            assertEmpty(dialog.analysis.result.mapping.entries)
            assertFalse("an export was offered over an empty mapping", dialog.createLeftSideActions().single().isEnabled)
        }
    }

    /**
     * **A reduction that empties the mapping takes the offer away with it**, because the button is a
     * statement about what is on screen and the screen re-renders under every tick.
     */
    fun `test preserving the last placeholder takes the export away`() {
        assertTheHarnessResolves()
        val analysis = analysisOf("class Ledger { void settle() { <selection>new MissingType();</selection> } }")

        withDialog(PreviewDialog.forCopy(project, analysis)) { dialog ->
            val export = dialog.createLeftSideActions().single()
            assertTrue("nothing was minted, so the fixture asserts nothing", export.isEnabled)

            tableIn(dialog).setValueAt(true, 0, PRESERVE_COLUMN)

            assertFalse("the offer outlived the mapping it would have written", export.isEnabled)
        }
    }

    /**
     * **The criterion the whole feature exists for: the file reverses the snippet.**
     *
     * Exported from the preview, before the copy, which is the order a user works in — and then the
     * file is applied to the anonymized text *by hand*, by a three-line substitution that knows
     * nothing but the rows. That is what a user has left once the invocation falls past the sidecar's
     * horizon and the plugin can no longer decode it for them: this file and a reader.
     *
     * The second assertion ties it to the product rather than to the fixture. `De-anonymize
     * Clipboard` is run over the same text while the tables are still intact, and the two have to
     * agree — the file is only worth keeping if it says what the reversal would have said.
     *
     * The literal is in the fixture on purpose, twice over: its text lives in the sidecar and nowhere
     * durable, so it is exactly the half of the mapping this file exists to preserve — and it carries
     * a comma, which is the character that would split a row in two if the quoting were not there.
     */
    fun `test the file it wrote reverses the snippet, and reverses it the way De-anonymize Clipboard does`() {
        assertTheHarnessResolves()
        myFixture.configureByText("Ledger.java", ROUND_TRIP)
        val files = FakeMappingFiles()

        invokeWithPreview(previews = { project, analysis ->
            val dialog = PreviewDialog.forCopy(project, analysis, files)
            try {
                export(dialog)
                dialog.analysis
            } finally {
                Disposer.dispose(dialog.disposable)
            }
        })
        awaitBackgroundWork()

        val anonymized = clipboard()
        assertFalse("nothing was anonymized, so this asserts nothing", anonymized == selectionOf(ROUND_TRIP))

        val rows = readCsv(checkNotNull(files.written) { "nothing was exported at all" }).drop(1).associate { it[0] to it[1] }
        assertTrue(
            "the literal's text is not in the file, and it is the half of the mapping nothing durable holds",
            "settlement failed, unreconciled" in rows.values,
        )
        assertEquals(
            "the file does not put the snippet back",
            selectionOf(ROUND_TRIP),
            byHand(rows, anonymized),
        )

        restoreClipboard(project)
        assertEquals("the file and the product disagree about the same reply", clipboard(), byHand(rows, anonymized))
    }

    /** Clicks the dialog's own `Export Mapping…` button, the way a user reaches it. */
    private fun export(dialog: PreviewDialog) = dialog.createLeftSideActions()
        .single { nameOf(it) == "Export Mapping\u2026" }
        .actionPerformed(ActionEvent(dialog, ActionEvent.ACTION_PERFORMED, ""))

    private fun nameOf(action: Action): String = action.getValue(Action.NAME) as String

    /**
     * The rows applied to [text] the way a reader with the file open would apply them — whole words
     * only, exactly as the reversal contract says, and nothing else touched.
     */
    private fun byHand(rows: Map<String, String>, text: String): String =
        Regex("""[\p{L}\p{N}_$]+""").replace(text) { rows[it.value] ?: it.value }

    /**
     * A CSV reader, to RFC 4180 — the independent half of *properly quoted*, at the surface a user's
     * own spreadsheet would sit. The format itself is `:core`'s and is asserted there.
     */
    private fun readCsv(csv: String): List<List<String>> {
        val records = mutableListOf<List<String>>()
        val fields = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var at = 0

        while (at < csv.length) {
            val char = csv[at]
            when {
                quoted && char == '"' && csv.getOrNull(at + 1) == '"' -> { field.append('"'); at++ }
                char == '"' -> quoted = !quoted
                quoted -> field.append(char)
                char == ',' -> { fields += field.toString(); field.clear() }
                char == '\r' && csv.getOrNull(at + 1) == '\n' -> {
                    fields += field.toString()
                    field.clear()
                    records += fields.toList()
                    fields.clear()
                    at++
                }
                else -> field.append(char)
            }
            at++
        }

        assertTrue("the file ended mid-record: `$csv`", field.isEmpty() && fields.isEmpty())
        return records
    }

    private fun tableIn(dialog: PreviewDialog): JTable =
        descendantsOf(dialog.createCenterPanel()).filterIsInstance<JTable>().single()

    private fun descendantsOf(component: Container): List<Component> =
        component.components.flatMap { listOf(it) + if (it is Container) descendantsOf(it) else emptyList() }

    private fun withDialog(dialog: PreviewDialog, assertions: (PreviewDialog) -> Unit) {
        try {
            assertions(dialog)
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }

    /** An analysis of the fixture, against an empty ledger, with nothing reduced. */
    private fun analysisOf(source: String): Analysis = Analysis.of(
        planFor("Ledger.java", source),
        AnonymizationSettings.DEFAULTS,
        LedgerSnapshot.EMPTY,
    )
}

private const val PRESERVE_COLUMN = 3

/**
 * A snippet with a local and a string literal in it — the two halves of a mapping that live in the
 * bounded sidecar and in nothing durable, which is what the export is for.
 *
 * No comment in it, deliberately: comments are stripped by default and no reversal restores
 * destroyed information, so a fixture with one would make this assert the strip rather than the
 * round trip.
 */
private val ROUND_TRIP = """
    class Ledger {
        <selection>int settle(int amount) {
            int owed = amount;
            log("settlement failed, unreconciled");
            return owed;
        }</selection>

        void log(String message) {}
    }
""".trimIndent()

/** What `<selection>` marks, which is what the copy took and therefore what the file must put back. */
private fun selectionOf(text: String): String =
    text.substringAfter("<selection>").substringBefore("</selection>")

/** A snippet whose two names are what every assertion above counts on. */
private const val SNIPPET = "class Ledger { <selection>void settle(int amount) {}</selection> }"

/**
 * The save dialog and the write behind it, or whatever a test puts in their place — the seam the
 * export's guarantees are asserted at.
 *
 * *"A cancelled save writes nothing and says nothing"* and *"a failed write says so"* are claims
 * about this feature, and neither is assertable against a modal file chooser or a real disk.
 */
internal class FakeMappingFiles(
    private val cancelled: Boolean = false,
    private val failing: Boolean = false,
) : MappingFiles {

    var written: String? = null
        private set

    override fun save(project: Project, csv: String): Boolean {
        if (failing) throw IOException("the volume is read-only")
        if (cancelled) return false
        written = csv
        return true
    }
}
