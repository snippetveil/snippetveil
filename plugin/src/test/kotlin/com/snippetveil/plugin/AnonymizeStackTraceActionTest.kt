package com.snippetveil.plugin

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.TestActionEvent
import com.intellij.ui.EditorTextField
import com.snippetveil.core.MappedKind
import com.snippetveil.core.SymbolOccurrence
import com.snippetveil.core.SymbolOrigin
import com.snippetveil.core.TraceReading
import com.snippetveil.core.parseTrace
import com.snippetveil.sweep.LeakOracle
import java.awt.Container
import javax.swing.Action
import javax.swing.JCheckBox

/**
 * **The stack-trace action, end to end** — the clipboard, the refusal, the resolution, the preview
 * and the balloon.
 *
 * What the grammar admits is `:core`'s and is asserted there, row by row, against strings with no
 * platform behind them. What is asserted here is what only a running IDE can say: which frames the
 * index calls the project's own, that they come out as the placeholders a snippet of the same classes
 * gets, and that nothing reaches the clipboard except through the preview.
 *
 * **Every fixture here is synthetic**, and every invocation that is about the output first asserts
 * that it classified at least one frame project-owned and at least one library-owned — see
 * [assertTheTraceResolved]. A fixture whose classes stopped resolving would otherwise pass on a trace
 * rendered entirely as `Unknown`, which is the cleanest-looking output there is.
 */
class AnonymizeStackTraceActionTest : JavaSnippetTestCase() {

    /**
     * **The happy path, row by row of the output**: project frames renamed, library and JDK frames
     * verbatim, an unresolved frame a whole `Unknown`, the prefixes gone, and the clipboard holding
     * exactly what the preview showed.
     */
    fun `test a trace on the clipboard is anonymized through the preview`() {
        addPayoutsProject()
        setClipboard(TRACE)

        val shown = invokeAndCapture(TRACE)
        val copied = clipboard()
        assertEquals("the preview showed one text and the clipboard got another", shown.result.text, copied)
        assertTheTraceResolved(shown)

        val placeholders = PlaceholderLedger.getInstance().snapshotOf(project).placeholders
        val ledger = placeholders.getValue("class:com.acme.payouts.PayoutLedger").placeholder
        val settle = placeholders.getValue("method:class:com.acme.payouts.PayoutLedger#settle").placeholder
        val batch = placeholders.getValue("class:com.acme.payouts.PayoutLedger.Batch").placeholder
        val acme = placeholders.getValue("package:com.acme").placeholder
        val payouts = placeholders.getValue("package:com.acme.payouts").placeholder

        assertTrue(copied, "\tat com.$acme.$payouts.$ledger.$settle($ledger.java:42)\n" in copied)
        assertTrue("the inner class's file is not its outer class's:\n$copied", "\$$batch." in copied)
        assertTrue("the inner class's file is not its outer class's:\n$copied", "($ledger.java:88)" in copied)
        assertTrue("a library frame was not preserved:\n$copied", "\tat org.junit.Assert.fail(Assert.java:89)\n" in copied)
        assertTrue("a JDK frame was not preserved:\n$copied", "\tat java.lang.Thread.run(Thread.java:840)\n" in copied)
        assertTrue("a constructor frame lost its JVM name:\n$copied", ".<init>($ledger.java:10)\n" in copied)
        assertTrue("the elided count was not kept:\n$copied", copied.endsWith("\n\t... 6 more"))
        assertTrue(
            "an unresolved frame is not a whole Unknown, class and method:\n$copied",
            Regex("""\n\tat Unknown\d+\.Unknown\d+\(Unknown\d+:9\)\n""").containsMatchIn(copied),
        )
    }

    /**
     * **A frame renders as the placeholder the user's snippet already carries** — which is the whole
     * point of the action. Asserted from both ends: a snippet copied first has its names reused by
     * the trace, and those are the names the ledger holds for the classes.
     */
    fun `test project frames render as the same placeholders a snippet of those classes gets`() {
        addPayoutsProject()
        myFixture.configureByText(
            "Caller.java",
            "package com.acme.payouts;\n\nclass Caller {\n    void go(PayoutLedger ledger) {\n        ledger.settle();\n    }\n}",
        )
        invokeCopyAnonymized()
        val snippet = clipboard()

        val trace = invokeAndCapture(TRACE).result.text
        val placeholders = PlaceholderLedger.getInstance().snapshotOf(project).placeholders
        val ledger = placeholders.getValue("class:com.acme.payouts.PayoutLedger").placeholder
        val settle = placeholders.getValue("method:class:com.acme.payouts.PayoutLedger#settle").placeholder

        assertTrue("the snippet does not carry the class's placeholder:\n$snippet", "($ledger " in snippet)
        assertTrue("the snippet does not carry the method's placeholder:\n$snippet", ".$settle();" in snippet)
        assertTrue("the trace named the frame differently from the snippet:\n$trace", ".$ledger.$settle($ledger.java:42)" in trace)
    }

    /**
     * **The file name renders the public top-level class's placeholder, or a bare `Unknown` with no
     * extension where the file's name is fixed to nothing** — the package-private class in its own
     * file, and the frame whose class did not resolve at all.
     */
    fun `test a file fixed to no public class renders a bare Unknown with no extension`() {
        addPayoutsProject()

        val result = invokeAndCapture(TRACE).result
        val relay = PlaceholderLedger.getInstance().snapshotOf(project).placeholders
            .getValue("class:com.acme.payouts.PayoutRelay").placeholder
        val line = result.text.lines().single { ".$relay." in it }

        assertTrue("a package-private class's file name kept an extension: $line", Regex("""\(Unknown\d+:5\)$""").containsMatchIn(line))
        assertFalse("the file name rendered the class's own placeholder: $line", "($relay" in line)
        assertTrue(
            "the file names are not Unknown rows: ${result.unknowns.map { it.name }}",
            result.unknowns.map { it.name }.containsAll(listOf("PayoutRelay.java", "PayoutGhost.java")),
        )
    }

    /**
     * **The thread name and the messages are `str` literals** — `main` included, and a library
     * exception's message included, because ownership belongs to symbols and nothing here owns the
     * text. Asserted on the result's own rows rather than by a leak check, which would be green on a
     * message that happened to be rendered some other way.
     */
    fun `test the thread name and every message are redacted as str literals`() {
        addPayoutsProject()

        val names = invokeAndCapture(TRACE).result.names.filter { it.kind == MappedKind.LITERAL }
        val byOriginal = names.associate { it.original to it.placeholder.orEmpty() }

        for (text in listOf("main", "payout 7731 refused for tenant acme-eu", "connection to vault.acme.internal refused")) {
            assertTrue("`$text` is not a str literal: $byOriginal", byOriginal[text]?.matches(Regex("""str\d+""")) == true)
        }
    }

    /**
     * **The action's output reads as a trace**, so the action can read its own output: the bare
     * `Unknown` file name renders a parenthesis with no extension, and the grammar has to admit it.
     */
    fun `test the action's output parses as a stack trace`() {
        addPayoutsProject()

        val output = invokeAndCapture(TRACE).result.text

        assertTrue("the action's own output is not a trace it reads:\n$output", parseTrace(output) is TraceReading.Read)
    }

    /** **A frame with no line number** — `javac -g:source` — is admitted and anonymized. */
    fun `test a frame with no line number is anonymized`() {
        addPayoutsProject()
        val trace = "com.acme.payouts.PayoutRejected: x\n" +
            "\tat org.junit.Assert.fail(Assert.java)\n" +
            "\tat com.acme.payouts.PayoutLedger.settle(PayoutLedger.java)"

        val output = invokeAndCapture(trace).result.text
        val ledger = PlaceholderLedger.getInstance().snapshotOf(project).placeholders
            .getValue("class:com.acme.payouts.PayoutLedger").placeholder

        assertTrue(output, output.endsWith("($ledger.java)"))
        assertTrue(output, "\tat org.junit.Assert.fail(Assert.java)\n" in output)
    }

    /**
     * **Module and classloader prefixes do not reach the clipboard**, and the output is therefore not
     * the input with names swapped — the lossiness is the decision, and it is asserted as one.
     */
    fun `test module and classloader prefixes are absent from the output`() {
        addPayoutsProject()

        val output = invokeAndCapture(TRACE).result.text

        for (prefix in listOf("java.base/", "app//", "payouts-worker//", "com.acme.payouts/")) {
            assertFalse("the prefix `$prefix` reached the output:\n$output", prefix in output)
        }
        assertFalse("no `/` should survive anywhere in this trace:\n$output", '/' in output)
    }

    /**
     * **The leak check, over a universe taken from the trace itself**: every sub-token it carries,
     * split on `.`, `$` and `/`, minus what the JDK and the libraries declare — never the fixture
     * project's declarations, which would share the resolver's blind spots.
     */
    fun `test nothing the trace names survives unless the JDK or a library declares it`() {
        addPayoutsProject()
        val oracle = LeakOracle.overTrace(TRACE, DECLARED_BY_THE_JDK_AND_LIBRARIES)

        assertTrue(
            "the oracle cannot fail: the trace itself came back clean",
            oracle.survivorsIn(TRACE).map { it.name }.containsAll(listOf("PayoutLedger", "Batch", "payouts", "acme")),
        )

        val output = invokeAndCapture(TRACE).result.text
        val survivors = oracle.survivorsIn(output)
        assertEmpty(survivors.map { "${it.name} at line ${it.line}: ${it.text}" })
    }

    /**
     * **A line outside the vocabulary refuses the whole paste**, in the words the ticket fixes, and
     * the clipboard is byte-identical afterwards. Warning, with no report link: the product is
     * working, and describing its input.
     */
    fun `test a paste with a log line around the trace refuses and leaves the clipboard alone`() {
        val logged = "2026-09-30 12:00:01 ERROR c.a.p.PayoutLedger - settlement failed\n$TRACE"
        setClipboard(logged)

        var opened = false
        invoke(FakeClipboard(logged)) { _, analysis -> analysis.also { opened = true } }
        awaitBackgroundWork()

        assertFalse("a refused paste opened the preview", opened)
        assertEquals("the refusal changed the clipboard", logged, clipboard())

        val balloon = notifications.single()
        assertEquals(NotificationType.WARNING, balloon.type)
        assertEmpty(balloon.actions)
        assertEquals(
            "Clipboard is not a stack trace \u2014 select the trace only. Your clipboard was not changed.",
            balloon.content,
        )
        assertFalse("the refusal quotes the paste: ${balloon.content}", "PayoutLedger" in balloon.content)
    }

    /**
     * **A cancelled preview reaches nothing** — no clipboard write, so no commit, so no number burnt
     * and no symbol named. The preview is the only path there is, so this is the only way out of it.
     */
    fun `test a cancelled preview leaves the clipboard and the mapping untouched`() {
        addPayoutsProject()
        setClipboard(PREVIOUS_CLIPBOARD)
        val before = PlaceholderLedger.getInstance().snapshotOf(project)

        var cancelled = false
        invoke(FakeClipboard(TRACE)) { _, _ ->
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

    /** The balloon reads the three numbers a trace has, `unknown` among them, and names what left. */
    fun `test the balloon and the strip carry renamed, unknown and preserved`() {
        addPayoutsProject()

        val analysis = invokeAndCapture(TRACE)
        val counts = analysis.result.counts

        val balloon = notifications.single()
        assertEquals("Anonymized stack trace copied", balloon.title)
        assertEquals(
            "${counts.replaced} names replaced · ${counts.unknown} unknown · ${counts.preserved} preserved",
            balloon.content.substringBefore("<br>"),
        )
        assertEquals(
            "${counts.replaced} renamed · ${counts.unknown} unknown · ${counts.preserved} preserved",
            Subject.TRACE.strip(analysis),
        )
        assertTrue("this trace has unresolved names, and the count says none", counts.unknown > 0)
    }

    /**
     * **Always enabled, and `update` does not read the clipboard** — enablement is a question about
     * the context and never about the content.
     */
    fun `test the item is enabled on any file and reads the clipboard only on invoke`() {
        myFixture.configureByText("notes.md", "a file this plugin does not anonymize")
        val clipboard = FakeClipboard("not a trace")

        val action = AnonymizeStackTraceAction(clipboard, Previews { _, _ -> null })
        val event = TestActionEvent.createTestEvent(action)
        action.update(event)

        assertTrue("the trace item is not offered here", event.presentation.isEnabledAndVisible)
        assertEquals("update read the clipboard", 0, clipboard.reads)

        invoke(clipboard) { _, analysis -> analysis }
        awaitBackgroundWork()
        assertEquals("invoke did not read the clipboard exactly once", 1, clipboard.reads)
    }

    /**
     * **Not `DumbAware`**, unlike the plan action and for the opposite reason: this one resolves
     * through the index, so while indexing runs the platform greys it out rather than letting it
     * answer quietly wrong.
     */
    fun `test the action is not DumbAware`() {
        assertFalse("the trace action claims to work without the index it resolves through", DumbService.isDumbAware(AnonymizeStackTraceAction()))
    }

    /**
     * **The dialog a trace opens in**: the button names the trace, the pane highlights nothing, and
     * there is no keep-comments tick over text that has no comments.
     */
    fun `test the trace preview names the trace, highlights nothing and offers no comments tick`() {
        addPayoutsProject()
        val analysis = invokeAndCapture(TRACE)

        val dialog = PreviewDialog.forTrace(project, analysis)
        try {
            val panel = dialog.createCenterPanel()
            val buttons = dialog.createActions().map { it.getValue(Action.NAME) as? String }
            assertTrue("the trace preview's button does not name the trace: $buttons", "Copy Anonymized Trace" in buttons)
            assertEmpty("the trace preview offers a comments tick", checkBoxesIn(panel).map { it.text })
            assertEquals(PlainTextFileType.INSTANCE, codeIn(panel).fileType)
            assertEquals(listOf("Export Mapping…"), dialog.createLeftSideActions().map { it.getValue(Action.NAME) as? String })
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }

    /**
     * **The harness asserts its own classification** before any claim about the output is believed:
     * at least one frame the project owns, and at least one a library owns. A fixture whose classes
     * stopped resolving would render every frame `Unknown`, and every leak check would pass on it.
     */
    private fun assertTheTraceResolved(analysis: Analysis) {
        val origins = analysis.plan.occurrences.filterIsInstance<SymbolOccurrence>().map { it.symbol.origin }.toSet()
        assertTrue("no frame was classified project-owned, so the fixture resolves nothing: $origins", SymbolOrigin.IN_CONTENT in origins)
        assertTrue("no frame was classified library-owned, so the library is not attached: $origins", SymbolOrigin.LIBRARY in origins)
    }

    /** Invokes over [trace], lets the preview through unchanged, and returns what it was shown. */
    private fun invokeAndCapture(trace: String): Analysis {
        var shown: Analysis? = null
        invoke(FakeClipboard(trace)) { _, analysis -> analysis.also { shown = it } }
        awaitBackgroundWork()
        return checkNotNull(shown) { "the trace was refused: ${notifications.map { it.content }}" }.also(::assertTheTraceResolved)
    }

    private fun invoke(clipboard: Clipboard, previews: Previews): Presentation {
        dropEarlierBalloons()
        return myFixture.testAction(AnonymizeStackTraceAction(clipboard, previews))
    }

    /**
     * The fixture project: a public class with an inner class, an exception, and a package-private
     * class alone in a file its name is not fixed to. `PayoutGhost` is deliberately never declared.
     */
    private fun addPayoutsProject() {
        myFixture.addFileToProject(
            "com/acme/payouts/PayoutLedger.java",
            """
            package com.acme.payouts;

            public class PayoutLedger {
                public PayoutLedger() {}
                public void settle() {}
                public static class Batch {
                    void post() {}
                }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "com/acme/payouts/PayoutRejected.java",
            "package com.acme.payouts;\n\npublic class PayoutRejected extends RuntimeException {}",
        )
        myFixture.addFileToProject(
            "com/acme/payouts/PayoutRelay.java",
            "package com.acme.payouts;\n\nclass PayoutRelay {\n    void forward() {}\n}",
        )
    }

    private fun codeIn(component: Container): EditorTextField =
        descendantsOf(component).filterIsInstance<EditorTextField>().single()

    private fun checkBoxesIn(component: Container): List<JCheckBox> =
        descendantsOf(component).filterIsInstance<JCheckBox>()
}

/**
 * A synthetic trace over the fixture project: a thread line, project frames, an inner class, a
 * package-private class, an unresolved class, a library frame, JDK frames behind module and
 * classloader prefixes, a constructor, a library exception as the cause, and elided frames.
 */
private val TRACE = listOf(
    "Exception in thread \"main\" com.acme.payouts.PayoutRejected: payout 7731 refused for tenant acme-eu",
    "\tat com.acme.payouts.PayoutLedger.settle(PayoutLedger.java:42)",
    "\tat com.acme.payouts.PayoutLedger\$Batch.post(PayoutLedger.java:88)",
    "\tat com.acme.payouts.PayoutRelay.forward(PayoutRelay.java:5)",
    "\tat com.acme.payouts.PayoutGhost.haunt(PayoutGhost.java:9)",
    "\tat org.junit.Assert.fail(Assert.java:89)",
    "\tat java.base/java.lang.Thread.run(Thread.java:840)",
    "\tat app//com.acme.payouts.PayoutLedger.<init>(PayoutLedger.java:10)",
    "\tat payouts-worker//com.acme.payouts.PayoutLedger.settle(PayoutLedger.java:42)",
    "\tat com.acme.payouts/com.acme.payouts.PayoutLedger.settle(PayoutLedger.java:42)",
    "Caused by: java.sql.SQLException: connection to vault.acme.internal refused",
    "\tat com.acme.payouts.PayoutLedger.settle(PayoutLedger.java:40)",
    "\t... 6 more",
).joinToString("\n")

/**
 * **What the JDK and the libraries declare, among the trace's sub-tokens** — the oracle's one
 * subtraction. The packages, classes and methods of the preserved frames; `com`, which the JDK
 * declares as the root of `com.sun`; `init`, the JVM's own constructor name; and the words the JDK's
 * trace printer writes itself — `Exception in thread`, `at`, `Caused by`, `more`.
 */
private val DECLARED_BY_THE_JDK_AND_LIBRARIES = setOf(
    "java", "lang", "Thread", "run", "sql", "SQLException",
    "org", "junit", "Assert", "fail",
    "com", "init",
    "Exception", "in", "thread", "at", "Caused", "by", "more",
)

/** What a user's clipboard held before an invocation that must not touch it. */
private const val PREVIOUS_CLIPBOARD = "the trace the user copied a minute ago"
