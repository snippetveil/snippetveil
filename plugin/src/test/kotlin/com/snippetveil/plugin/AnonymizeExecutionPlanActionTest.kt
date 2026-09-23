package com.snippetveil.plugin

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.TestActionEvent
import com.intellij.ui.EditorTextField
import com.snippetveil.core.AnonymizationSettings
import com.snippetveil.core.LedgerSnapshot
import com.snippetveil.core.PlanReading
import com.snippetveil.core.parsePlan
import java.awt.Container
import javax.swing.Action
import javax.swing.JCheckBox

/**
 * **The execution-plan action, end to end** — the clipboard, the refusal, the preview and the
 * balloon.
 *
 * Every claim here is about the *invocation* rather than about a widget: the clipboard is read on
 * invoke and nowhere else, a refusal leaves it byte-identical and quotes none of it, and the preview
 * is the only way an anonymized plan ever reaches it. What the parse itself makes of a plan is
 * `:core`'s, and is asserted there against strings with no platform behind them.
 */
class AnonymizeExecutionPlanActionTest : JavaSnippetTestCase() {

    /**
     * **The happy path: the names go, the numbers stay, and the clipboard holds what the preview
     * showed.**
     *
     * The last of those is the one a preview cannot be wrong about — a dialog showing one text while
     * the clipboard takes another is the single failure that would make the whole surface worthless.
     */
    fun `test a plan on the clipboard is anonymized through the preview`() {
        setClipboard(PLAN)

        var shown: String? = null
        invokePlan(FakeClipboard(PLAN)) { _, analysis -> analysis.also { shown = it.result.text } }
        awaitBackgroundWork()

        val copied = clipboard()
        assertEquals("the preview showed one text and the clipboard got another", shown, copied)
        assertFalse("a relation name is still in the copy:\n$copied", "visits" in copied)
        assertFalse("an index name is still in the copy:\n$copied", "visits_pkey" in copied)
        assertFalse("a schema name is still in the copy:\n$copied", "billing" in copied)
        assertFalse("a value the planner printed is still in the copy:\n$copied", "'open'" in copied)
        assertTrue("the costs did not survive verbatim:\n$copied", "(cost=0.29..8.30 rows=1 width=36)" in copied)
        assertTrue("the timing did not survive verbatim:\n$copied", "Execution Time: 0.456 ms" in copied)
    }

    /**
     * **The balloon carries the same two numbers the strip does, and no adjective** — and no
     * `unknown`, because a plan resolves nothing and a count with no population is absent rather
     * than printed as a zero that can never be anything else.
     */
    fun `test the balloon and the strip carry the two numbers this invocation has`() {
        invokePlan(FakeClipboard(PLAN)) { _, analysis -> analysis }
        awaitBackgroundWork()

        val balloon = notifications.single()
        assertEquals("Anonymized execution plan copied", balloon.title)
        assertEquals(NotificationType.INFORMATION, balloon.type)
        assertEquals("9 names replaced · 1 preserved", balloon.content.substringBefore("<br>"))
        assertFalse("the balloon counts what a plan cannot have: " + balloon.content, "unknown" in balloon.content)
    }

    /**
     * **The strip shows exactly `renamed` and `preserved`.** The unknown and comments-stripped
     * entries are omitted rather than shown as zeros: this action has no population for either, and
     * a zero standing where nothing can ever be counted is unreadable in the other direction.
     */
    fun `test the plan strip shows two entries and omits the ones with no population`() {
        val strip = Subject.PLAN.strip(planAnalysis())

        assertEquals("9 renamed · 1 preserved", strip)
        assertFalse("the plan strip counts what it cannot have: $strip", "unknown" in strip)
        assertFalse("the plan strip mentions a comment: $strip", "comment" in strip)
    }

    /**
     * **A clipboard that is not a plan refuses, and the clipboard is byte-identical afterwards** —
     * not rewritten with the same string, which is a different behaviour that leaves the same bytes.
     *
     * Warning rather than error, and with no `Report an issue` link: a correctly-behaving plugin
     * truthfully refusing input is not a defect, and a report link here would collect issues about a
     * paste that was never a plan.
     */
    fun `test a clipboard that is not a plan refuses, warns, and is left byte-identical`() {
        setClipboard(PRIVATE_QUERY)

        var opened = false
        invokePlan(FakeClipboard(PRIVATE_QUERY)) { _, analysis -> analysis.also { opened = true } }
        awaitBackgroundWork()

        assertFalse("a refused paste opened the preview", opened)
        assertEquals("the refusal changed the clipboard", PRIVATE_QUERY, clipboard())

        val balloon = notifications.single()
        assertEquals(NotificationType.WARNING, balloon.type)
        assertEmpty(balloon.actions)
        assertEquals(
            "Clipboard is not an execution plan SnippetVeil can read — copy the whole plan from " +
                "its first line, without the query. Your clipboard was not changed.",
            balloon.content,
        )
    }

    /**
     * **The refusal quotes nothing of what was on the clipboard**, and it cannot: the verdict the
     * engine hands back carries no `String` at all, so there is nowhere a line of the user's own text
     * could have travelled from.
     *
     * Asserted twice over, because the two halves catch different mistakes. The same sentence comes
     * back whatever the input was — so nothing in it can be derived from the input — and none of the
     * names the input carried appears anywhere in it.
     */
    fun `test the refusal quotes nothing from the clipboard`() {
        val private = refusalOver(PRIVATE_QUERY)
        val other = refusalOver("a screenshot caption somebody copied by mistake")

        assertEquals("the refusal varies with the input, so something of it reached the message", private, other)
        for (name in PRIVATE_NAMES) {
            assertFalse("the refusal quotes `$name`: $private", name in private)
        }
    }

    /**
     * **A plan this product recognises and cannot read soundly names the engine's own better
     * option** — and it is a different balloon from the general refusal, which is the point.
     *
     * The text format prints a handful of rows with raw unquoted names in them; the same plan as
     * JSON escapes every one. Telling the user *copy the whole plan from its first line* here would
     * be advice that does not work on input where they already did, so the two sentences are asserted
     * as a pair rather than one at a time.
     */
    fun `test a plan carrying a raw-name row names the engine option that would work`() {
        val raw = "$PLAN\nSettings: work_mem = '8MB'"
        setClipboard(raw)

        var opened = false
        invokePlan(FakeClipboard(raw)) { _, analysis -> analysis.also { opened = true } }
        awaitBackgroundWork()

        assertFalse("a refused paste opened the preview", opened)
        assertEquals("the refusal changed the clipboard", raw, clipboard())

        val balloon = notifications.single()
        assertEquals(NotificationType.WARNING, balloon.type)
        assertEmpty(balloon.actions)
        assertEquals(
            "SnippetVeil cannot read this plan soundly — re-run the same statement with " +
                "EXPLAIN (FORMAT JSON) and copy that instead. Your clipboard was not changed.",
            balloon.content,
        )
        for (name in listOf("work_mem", "visits", "billing")) {
            assertFalse("the refusal quotes `$name`: ${balloon.content}", name in balloon.content)
        }
    }

    /**
     * **The output a MySQL user gets without asking for anything is refused, and the sentence names
     * that shape rather than another.**
     *
     * It is the product's least comfortable fact, so it is asserted plainly: the default `EXPLAIN`
     * lands on the tabular sentence and the `TREE` output lands on its own, and **they are asserted
     * as a pair** — one message covering both would have to describe neither in order to be true of
     * both, and that is exactly the wording a later change would drift into.
     */
    fun `test MySQL's default output and its TREE output are refused in their own words`() {
        val said = mapOf(
            MYSQL_TABULAR to "SnippetVeil cannot safely anonymize MySQL's tabular EXPLAIN output. " +
                "Run EXPLAIN FORMAT=JSON and copy that instead. Your clipboard was not changed.",
            MYSQL_TREE to "SnippetVeil cannot safely anonymize MySQL's TREE format. " +
                "Run EXPLAIN FORMAT=JSON and copy that instead. Your clipboard was not changed.",
        )

        for ((paste, sentence) in said) {
            setClipboard(paste)

            var opened = false
            invokePlan(FakeClipboard(paste)) { _, analysis -> analysis.also { opened = true } }
            awaitBackgroundWork()

            assertFalse("a refused paste opened the preview", opened)
            assertEquals("the refusal changed the clipboard", paste, clipboard())

            val balloon = notifications.single()
            assertEquals(NotificationType.WARNING, balloon.type)
            assertEmpty(balloon.actions)
            assertEquals(sentence, balloon.content)
            for (name in listOf("visits_by_owner", "owner_id")) {
                assertFalse("the refusal quotes `$name`: ${balloon.content}", name in balloon.content)
            }
        }
    }

    /**
     * **A MariaDB paste is refused in MariaDB's own words, and the sentence offers nothing.**
     *
     * The absence of a recourse clause is the assertion, and it is asserted against MySQL's sentence
     * rather than on its own: the two engines print near-identical plans, and a MariaDB user told to
     * run `EXPLAIN FORMAT=JSON` would have been handed advice about an engine they are not running.
     */
    fun `test a MariaDB paste is refused with no recourse and never in MySQL's words`() {
        setClipboard(MARIADB_TABULAR)

        var opened = false
        invokePlan(FakeClipboard(MARIADB_TABULAR)) { _, analysis -> analysis.also { opened = true } }
        awaitBackgroundWork()

        assertFalse("a refused paste opened the preview", opened)
        assertEquals("the refusal changed the clipboard", MARIADB_TABULAR, clipboard())

        val balloon = notifications.single()
        assertEquals(NotificationType.WARNING, balloon.type)
        assertEmpty(balloon.actions)
        assertEquals(
            "SnippetVeil does not anonymize MariaDB plans in any format. Your clipboard was not changed.",
            balloon.content,
        )
        assertFalse("a MariaDB paste was given MySQL's recourse: " + balloon.content, "FORMAT=JSON" in balloon.content)
        assertFalse("a MariaDB paste was told about MySQL: " + balloon.content, "MySQL" in balloon.content)
    }

    /**
     * **A mid-tree paste and a query above the plan both refuse** — the pair of mistakes the head
     * anchor exists for, and the pair the message is written to answer.
     */
    fun `test a mid-tree paste and a query above the plan both refuse`() {
        for (paste in listOf(PLAN.lines().drop(1).joinToString("\n"), "SELECT v.id FROM visits v;\n$PLAN")) {
            setClipboard(paste)
            invokePlan(FakeClipboard(paste)) { _, analysis -> analysis }
            awaitBackgroundWork()

            assertEquals("this paste was anonymized, and is not a whole plan:\n$paste", paste, clipboard())
            assertEquals(NotificationType.WARNING, notifications.single().type)
        }
    }

    /**
     * **A cancelled preview reaches nothing.** No clipboard write, therefore no commit, therefore no
     * number burnt — the guarantee `Anonymize with Preview…` gives, on the action where the preview
     * is the only path there is.
     */
    fun `test a cancelled preview leaves the clipboard and the mapping untouched`() {
        setClipboard(PREVIOUS_CLIPBOARD)
        val before = PlaceholderLedger.getInstance().snapshotOf(project)

        var cancelled = false
        invokePlan(FakeClipboard(PLAN)) { _, _ ->
            cancelled = true
            null
        }
        awaitEvents("the preview was never opened") { cancelled }

        assertEquals("a cancelled preview reached the clipboard", PREVIOUS_CLIPBOARD, clipboard())

        val after = PlaceholderLedger.getInstance().snapshotOf(project)
        assertEquals("a cancelled preview burnt a number", before.nextNumber, after.nextNumber)
        assertEquals("a cancelled preview named a symbol", before.placeholders, after.placeholders)
        assertEmpty(notifications)
    }

    /**
     * **The item is always enabled, and `update` does not read the clipboard.**
     *
     * Enablement is a question about the context and never about the content: an item that greyed
     * itself out would be reporting what is on the clipboard to anybody who opened the menu, on the
     * EDT, over a clipboard another process may own. The read count is what makes that assertable —
     * a clipboard that came back unchanged cannot show a read that happened.
     */
    fun `test the item is enabled on any file and reads the clipboard only on invoke`() {
        myFixture.configureByText("notes.md", "a file this plugin does not anonymize")
        val clipboard = FakeClipboard(PRIVATE_QUERY)

        val action = AnonymizeExecutionPlanAction(clipboard, Previews { _, _ -> null })
        val event = TestActionEvent.createTestEvent(action)
        action.update(event)

        assertTrue("the plan item is not offered here", event.presentation.isEnabledAndVisible)
        assertEquals("update read the clipboard", 0, clipboard.reads)

        invokePlan(clipboard) { _, analysis -> analysis }
        awaitBackgroundWork()
        assertEquals("invoke did not read the clipboard exactly once", 1, clipboard.reads)
    }

    /**
     * **`DumbAware`, which is the opposite of the two actions over source and for the stated
     * reason**: those resolve, and resolution is index-dependent; this resolves nothing, so there is
     * no answer indexing could improve and nothing to grey out.
     */
    fun `test the action is DumbAware`() {
        assertTrue(
            "the plan action waits for indexing it does not use",
            AnonymizeExecutionPlanAction() is DumbAware,
        )
    }

    /**
     * **The dialog a plan opens in**: the button names what it copies, the pane highlights nothing,
     * the comments tick is absent rather than greyed out, and the export is where it always was.
     */
    fun `test the plan preview copies, highlights nothing, offers no comments tick and keeps the export`() {
        var buttons: List<String?> = emptyList()
        var ticks: List<String> = emptyList()
        var left: List<String?> = emptyList()
        var pane: FileType? = null

        val dialog = PreviewDialog.forPlan(project, planAnalysis())
        try {
            val panel = dialog.createCenterPanel()
            buttons = dialog.createActions().map { it.getValue(Action.NAME) as? String }
            ticks = checkBoxesIn(panel).map { it.text }
            left = dialog.createLeftSideActions().map { it.getValue(Action.NAME) as? String }
            pane = codeIn(panel).fileType
        } finally {
            Disposer.dispose(dialog.disposable)
        }

        assertTrue("the plan preview has no copy button: $buttons", "Copy Anonymized Plan" in buttons)
        assertEmpty("the plan preview offers a comments tick over a plan that has none", ticks)
        assertEquals(listOf("Export Mapping…"), left)
        assertEquals("the plan pane is highlighted as something", PlainTextFileType.INSTANCE, pane)
    }

    /** The refusal this paste produced, as the user reads it. */
    private fun refusalOver(pasted: String): String {
        invokePlan(FakeClipboard(pasted)) { _, analysis -> analysis }
        awaitBackgroundWork()
        return notifications.single().content
    }

    /**
     * The action, over a clipboard and a dialog a test owns — run the way the IDE would, with
     * `update` first, and with the earlier balloons dropped so that [awaitBackgroundWork] is about
     * the invocation in hand.
     */
    private fun invokePlan(clipboard: Clipboard, previews: Previews): Presentation {
        dropEarlierBalloons()
        return myFixture.testAction(AnonymizeExecutionPlanAction(clipboard, previews))
    }

    /** [PLAN] as the action would analyse it: the engine's own parse, the defaults, an empty ledger. */
    private fun planAnalysis(): Analysis {
        val reading = parsePlan(PLAN)
        assertTrue("the fixture is not a plan the engine reads", reading is PlanReading.Read)
        return Analysis.of(
            (reading as PlanReading.Read).plan,
            AnonymizationSettings.DEFAULTS,
            LedgerSnapshot.EMPTY,
        )
    }

    private fun codeIn(component: Container): EditorTextField =
        descendantsOf(component).filterIsInstance<EditorTextField>().single()

    private fun checkBoxesIn(component: Container): List<JCheckBox> =
        descendantsOf(component).filterIsInstance<JCheckBox>()
}

/**
 * A copied `EXPLAIN ANALYZE`: two relations, one of them schema-qualified, both aliased, an index, a
 * cast and the two timing fields — which is the shape that lands on a clipboard in practice.
 */
private val PLAN = """
    Nested Loop  (cost=0.29..16.97 rows=2 width=68) (actual time=0.028..0.031 rows=1 loops=1)
      ->  Index Scan using visits_pkey on visits v  (cost=0.29..8.30 rows=1 width=36)
            Index Cond: (id = 42)
            Filter: ((status)::text = 'open'::text)
      ->  Seq Scan on billing.invoices i  (cost=0.00..8.65 rows=1 width=32)
            Filter: (i.visit_id = v.id)
    Planning Time: 0.123 ms
    Execution Time: 0.456 ms
""".trimIndent()

/** A paste that is not a plan, and that carries names nothing may repeat back. */
private val PRIVATE_QUERY = """
    SELECT v.merchant_secret, v.tenant_id
      FROM acme_billing.hidden_ledger v
     WHERE v.status = 'settled'
""".trimIndent()

/** What a user's clipboard held before an invocation that must not touch it. */
private const val PREVIOUS_CLIPBOARD = "the raw plan the user copied a minute ago"

/** The names [PRIVATE_QUERY] carries — the words a refusal may not contain. */
private val PRIVATE_NAMES = listOf(
    "merchant_secret", "tenant_id", "acme_billing", "hidden_ledger", "settled", "SELECT",
)

/**
 * **What a MySQL user gets from `EXPLAIN` without asking for anything** — the traditional table,
 * inside the bordered frame the client's default terminator draws around it.
 */
private val MYSQL_TABULAR = """
    +----+-------------+-------+------------+------+------------------+------------------+---------+-------+------+----------+-------+
    | id | select_type | table | partitions | type | possible_keys    | key              | key_len | ref   | rows | filtered | Extra |
    +----+-------------+-------+------------+------+------------------+------------------+---------+-------+------+----------+-------+
    |  1 | SIMPLE      | v     | NULL       | ref  | visits_by_owner  | visits_by_owner  | 4       | const |    2 |   100.00 | NULL  |
    +----+-------------+-------+------------+------+------------------+------------------+---------+-------+------+----------+-------+
""".trimIndent()

/** MySQL's `TREE` output, whose aliases and index names are appended with nothing around them. */
private val MYSQL_TREE = """
    -> Sort: shop.v.created_at  (cost=0.85 rows=2)
        -> Index lookup on v using visits_by_owner (owner_id = 42)  (cost=0.70 rows=2)
""".trimIndent()

/** MariaDB's `EXPLAIN`, which prints neither of the two columns MySQL's table has. */
private val MARIADB_TABULAR = """
    +------+-------------+-------+------+------------------+------------------+---------+-------+------+-------+
    | id   | select_type | table | type | possible_keys    | key              | key_len | ref   | rows | Extra |
    +------+-------------+-------+------+------------------+------------------+---------+-------+------+-------+
    |    1 | SIMPLE      | v     | ref  | visits_by_owner  | visits_by_owner  | 4       | const |    2 |       |
    +------+-------------+-------+------+------------------+------------------+---------+-------+------+-------+
""".trimIndent()
