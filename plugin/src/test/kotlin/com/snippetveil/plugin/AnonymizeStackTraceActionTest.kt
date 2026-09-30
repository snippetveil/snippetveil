package com.snippetveil.plugin

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.Disposer
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.TestActionEvent
import com.intellij.ui.EditorTextField
import com.snippetveil.core.MappedKind
import com.snippetveil.core.REMAINDER_ORIGINAL
import com.snippetveil.core.SymbolOccurrence
import com.snippetveil.core.SymbolOrigin
import com.snippetveil.core.TraceReading
import com.snippetveil.core.deanonymize
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
     * **A printed file name that is not the resolved file's is a bare `Unknown`**, rather than the
     * placeholder of a class whose file the trace never printed.
     */
    fun `test a file name that does not match the resolved file renders a bare Unknown`() {
        addPayoutsProject()
        val trace = "com.acme.payouts.PayoutRejected: x\n" +
            "\tat org.junit.Assert.fail(Assert.java:89)\n" +
            "\tat com.acme.payouts.PayoutLedger.settle(Elsewhere.java:42)"

        val output = invokeAndCapture(trace).result.text

        assertTrue(output, Regex("""\(Unknown\d+:42\)$""").containsMatchIn(output))
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
        val oracle = LeakOracle.overTrace(TRACE, declaredByTheJdkAndLibraries(TRACE))

        assertTrue(
            "the oracle cannot fail: the trace itself came back clean",
            oracle.survivorsIn(TRACE).map { it.name }.containsAll(listOf("PayoutLedger", "Batch", "payouts", "acme")),
        )

        val output = invokeAndCapture(TRACE).result.text
        val survivors = oracle.survivorsIn(output)
        assertEquals(
            "a name the trace carries survived: ${survivors.map { "${it.name} at line ${it.line}: ${it.text}" }}",
            KNOWN_SURVIVORS,
            survivors.map { it.name }.toSet(),
        )
    }

    /**
     * **A generated class resolves as far as the platform accepts it, and no further**: the owning
     * class renders its placeholder, what the compiler added renders one `Unknown`, and a coroutine's
     * `invokeSuspend` stays as the language spelled it. A method the compiler suffixed does the same
     * at the method position.
     */
    fun `test a generated class and a generated method resolve by their longest prefix`() {
        addPayoutsProject()
        addBillingProject()

        val output = invokeAndCapture(GENERATED_TRACE).result.text
        val service = placeholderOf("class:com.acme.billing.BillingService")
        val charge = placeholderOf("method:class:com.acme.billing.BillingService#charge")

        assertTrue(output, Regex("""\.$service\${'$'}Unknown\d+\.invokeSuspend\($service\.java:42\)\n""").containsMatchIn(output))
        assertTrue(output, Regex("""\.$service\.$charge\${'$'}Unknown\d+\($service\.java:40\)\n""").containsMatchIn(output))
    }

    /**
     * **Longest first, at both positions.** `BillingService$Charge$retry$1` has two prefixes the index
     * accepts, and the longer one — the nested class — is the one rendered; `charge$retry$suspendImpl`
     * has two methods it could be read as, and the longer one wins. A shorter split would drop more
     * into `Unknown` and never print more, and no split prints a character of what was left.
     */
    fun `test the longest prefix the index accepts is the one chosen`() {
        addPayoutsProject()
        addBillingProject()

        val output = invokeAndCapture(GENERATED_TRACE).result.text
        val service = placeholderOf("class:com.acme.billing.BillingService")
        val nested = placeholderOf("class:com.acme.billing.BillingService.Charge")
        val chargeRetry = placeholderOf("method:class:com.acme.billing.BillingService#charge\$retry")

        assertTrue(output, Regex("""\.$service\${'$'}$nested\${'$'}Unknown\d+\.Unknown\d+\($service\.java:30\)\n""").containsMatchIn(output))
        assertTrue(output, Regex("""\.$service\.$chargeRetry\${'$'}Unknown\d+\($service\.java:20\)\n""").containsMatchIn(output))
        for (word in REMAINDER_WORDS) assertFalse("`$word` reached the output:\n$output", word in output)
    }

    /**
     * **No text from a remainder reaches the output or the preview's table** — the rows are still
     * there, since their placeholders are in the output, but they say the name was generated and not
     * what it was. The text is in the mapping a reply decodes against, and nowhere a person reads.
     */
    fun `test no part of a remainder appears in the output or the preview model`() {
        addPayoutsProject()
        addBillingProject()

        val result = invokeAndCapture(GENERATED_TRACE).result
        val table = MappingTableModel(result.names, reducible = true, onPreserve = { _, _ -> }, onRename = { _, _ -> })
            .also { it.unlocked = true }
        val cells = (0 until table.rowCount).flatMap { row -> (0 until table.columnCount).map { table.getValueAt(row, it)?.toString().orEmpty() } }

        assertTrue("the fixture left nothing over", result.names.any { it.original == REMAINDER_ORIGINAL })
        for (word in REMAINDER_WORDS) assertFalse("`$word` reached the output:\n${result.text}", word in result.text)
        // Whole remainders, and the word only a remainder carries: `charge` and `retry` are declared
        // methods as well, and the preview shows a declared method's own name on its own row.
        for (remainder in REMAINDERS) {
            assertTrue("`$remainder` reached the preview's table: $cells", cells.none { remainder in it })
            assertTrue("`$remainder` reached the unknowns: ${result.unknowns.map { it.name }}", result.unknowns.none { remainder in it.name })
            assertTrue("`$remainder` reached a row's key: ${result.names.map { it.key }}", result.names.none { remainder in it.key.orEmpty() })
        }
        assertTrue("the remainder is not in the mapping a reply decodes against", "charge\$1" in result.mapping.values)
    }

    /** **One `Unknown`, not two**: the owning class resolved, so what was left is the frame's only unknown. */
    fun `test a partially resolved name contributes exactly one to the unknown count`() {
        addPayoutsProject()
        addBillingProject()
        val trace = "com.acme.payouts.PayoutRejected: x\n" +
            "\tat com.acme.billing.BillingService\$charge\$1.invokeSuspend(BillingService.java:42)\n" +
            "\tat org.junit.Assert.fail(Assert.java:89)"

        val result = invokeAndCapture(trace).result

        assertEquals("unknowns: ${result.unknowns.map { it.key }}", 1, result.counts.unknown)
    }

    /**
     * **A remainder has no *Preserve*, and a whole-frame `Unknown` keeps it** — asserted on the
     * result, which is what the engine enforces, rather than on the dialog, which only offers it.
     */
    fun `test a remainder is not preservable and a whole-frame unknown is`() {
        addPayoutsProject()
        addBillingProject()

        val unknowns = invokeAndCapture(GENERATED_TRACE).result.unknowns
        val remainder = unknowns.first { it.name == REMAINDER_ORIGINAL }
        val ghost = unknowns.single { it.name == "com.acme.payouts.PayoutGhost" }

        assertFalse("a remainder is preservable: ${remainder.key}", remainder.preservable)
        assertTrue("a whole-frame unknown lost its preserve", ghost.preservable)
    }

    /**
     * **De-anonymizing gives the trace back**: `Type1$Unknown2`, `method3$Unknown4` and a bare
     * `Unknown5` file name each restore exactly, off the tables the copy recorded — the prefixes
     * aside, which were dropped on purpose.
     */
    fun `test the anonymized trace de-anonymizes to the trace it was made from`() {
        addPayoutsProject()
        addBillingProject()

        invokeAndCapture(GENERATED_TRACE)
        val copied = clipboard()
        val reversal = deanonymize(
            copied,
            PlaceholderSidecar.getInstance(project).window(),
            PlaceholderLedger.getInstance().snapshotOf(project),
        )

        assertTrue("there is no bare file-name Unknown to restore:\n$copied", Regex("""\(Unknown\d+:9\)""").containsMatchIn(copied))
        assertEquals((parseTrace(GENERATED_TRACE) as TraceReading.Read).trace.text, reversal.text)
        assertEmpty(reversal.unrestored)
    }

    /**
     * **The leak check splits on `$`**, so it finds `charge` inside `$charge$1` — asserted on the input,
     * so that a clean output means the remainder was replaced rather than that nobody looked.
     */
    fun `test the leak oracle finds the names inside a remainder and none survives`() {
        addPayoutsProject()
        addBillingProject()
        val oracle = LeakOracle.overTrace(GENERATED_TRACE, declaredByTheJdkAndLibraries(GENERATED_TRACE))

        val input = oracle.survivorsIn(GENERATED_TRACE)
        assertTrue(
            "the oracle cannot see inside a remainder: ${input.map { it.name }}",
            input.filter { "\$charge\$1" in it.text }.map { it.name }.contains("charge"),
        )

        val output = invokeAndCapture(GENERATED_TRACE).result.text
        val survivors = oracle.survivorsIn(output)
        assertEquals(
            "a name the trace carries survived: ${survivors.map { "${it.name} at line ${it.line}: ${it.text}" }}",
            GENERATED_SURVIVORS,
            survivors.map { it.name }.toSet(),
        )
    }

    /**
     * **The oracle's one subtraction, read off the classpath rather than typed out**: every frame and
     * header class the fixture resolves to the JDK or a library contributes its package segments, its
     * class names and the frame's method, where that class declares it.
     */
    private fun declaredByTheJdkAndLibraries(trace: String): Set<String> {
        val reading = parseTrace(trace) as TraceReading.Read
        val facade = JavaPsiFacade.getInstance(project)
        val scope = GlobalSearchScope.allScope(project)
        val declared = mutableSetOf<String>()
        val named = reading.trace.exceptions.map { it to null } + reading.trace.frames.map { it.type to it.method }
        for ((type, method) in named) {
            val found = facade.findClass(type.text.replace('$', '.'), scope) ?: continue
            val origin = originInTheFixture(project, found)
            if (origin != FixtureOrigin.JDK && origin != FixtureOrigin.LIBRARY) continue
            declared += type.text.split('.', '$')
            if (method != null && found.findMethodsByName(method.text, false).isNotEmpty()) declared += method.text
        }
        return declared
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
     * **Both refusals leave the clipboard byte-identical**, each in its own words: a `DebugProbes`
     * dump — `dumpCoroutines` output and a `printJob` tree — is named as a dump, and a paste that is
     * no trace at all, `[CIRCULAR REFERENCE: …]` among them, gets the generic message. Byte-identical
     * is asserted on the clipboard the action read, which is never written, and on the system one.
     * Both are warnings with no report link: the product is working, and describing its input.
     */
    fun `test both refusals leave the clipboard byte-identical, each in its own words`() {
        addPayoutsProject()
        val refusals = listOf(
            COROUTINE_DUMP to DUMP_MESSAGE,
            JOB_TREE to DUMP_MESSAGE,
            CIRCULAR_TRACE to "Clipboard is not a stack trace \u2014 select the trace only. Your clipboard was not changed.",
        )

        for ((paste, message) in refusals) {
            setClipboard(paste)
            val read = FakeClipboard(paste)

            var opened = false
            invoke(read) { _, analysis -> analysis.also { opened = true } }
            awaitBackgroundWork()

            assertFalse("a refused paste opened the preview:\n$paste", opened)
            assertFalse("a refusal wrote the clipboard it read:\n$paste", read.written)
            assertEquals("a refusal changed the clipboard it read", paste, read.text)
            assertEquals("a refusal changed the system clipboard", paste, clipboard())

            val balloon = notifications.single()
            assertEquals(NotificationType.WARNING, balloon.type)
            assertEmpty(balloon.actions)
            assertEquals(message, balloon.content)
            assertFalse("the refusal quotes the paste: ${balloon.content}", "Ledger" in balloon.content)
        }
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
        // The frames' classes only: a header or a package segment resolving is not a frame being
        // classified, and a check that counted them would pass on a trace whose every frame fell to
        // `Unknown`.
        val frames = (parseTrace(analysis.plan.text) as TraceReading.Read).trace.frames.map { it.type }
        val origins = analysis.plan.occurrences.filterIsInstance<SymbolOccurrence>()
            .filter { occurrence -> frames.any { occurrence.start >= it.start && occurrence.end <= it.end } }
            .map { it.symbol.origin }
            .toSet()
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

    /**
     * A public class with a nested class and two methods, one of whose names carries a `$` — so that
     * a generated name has more than one prefix the index accepts, at both positions. Nothing named
     * `charge$1`, `retry$1` or `suspendImpl` is declared: those are what a compiler adds.
     */
    private fun addBillingProject() {
        myFixture.addFileToProject(
            "com/acme/billing/BillingService.java",
            """
            package com.acme.billing;

            public class BillingService {
                public void charge() {}
                public void charge${'$'}retry() {}
                public static class Charge {
                    void retry() {}
                }
            }
            """.trimIndent(),
        )
    }

    private fun placeholderOf(key: String): String =
        PlaceholderLedger.getInstance().snapshotOf(project).placeholders.getValue(key).placeholder

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
 * **What survives the oracle by design, each one adjudicated** — the leak check's known false
 * positives on this trace, pinned so that a new survivor goes red.
 *
 * `com` is the top-level package segment, which the engine passes through by a positional rule and
 * which the source oracle reports for the same reason (CONTRIBUTING.md, *One subtraction*). `init`
 * is the JVM's `<init>`, which names nothing of anybody's. The rest are the words the JDK's trace
 * printer writes itself: `Exception in thread`, `at`, `Caused by`, `... more`.
 */
private val KNOWN_SURVIVORS = setOf("com", "init", "Exception", "in", "thread", "at", "Caused", "by", "more")

/** The dump's refusal, in the words the ticket fixes. */
private const val DUMP_MESSAGE = "That is a DebugProbes coroutine dump, not a stack trace. SnippetVeil can anonymize the " +
    "exception trace a coroutine produces, but not a dump. Your clipboard was not changed."

/** `DebugProbes.dumpCoroutines` output, a `_CREATION` block and all — every frame in it a frame. */
private val COROUTINE_DUMP = listOf(
    "Coroutines dump 2026/09/30 12:00:01",
    "",
    "Coroutine \"coroutine#2\":DeferredCoroutine{Active}@1b68b9a4, state: SUSPENDED",
    "\tat kotlinx.coroutines.DelayKt.delay(Delay.kt:170)",
    "\tat com.acme.payouts.PayoutLedger.settle(PayoutLedger.java:42)",
    "\tat _COROUTINE._CREATION._(CoroutineDebugging.kt:69)",
    "\tat com.acme.payouts.PayoutRelay.forward(PayoutRelay.java:5)",
).joinToString("\n")

/** A `DebugProbes.printJob` tree, whose indentation is its parent/child structure. */
private val JOB_TREE = listOf(
    "\"coroutine#1\":BlockingCoroutine{Active}@3a4afd8d, continuation is RUNNING at line com.acme.payouts.PayoutLedger.settle(PayoutLedger.java:42)",
    "\t\"coroutine#2\":DeferredCoroutine{Active}@1b68b9a4, continuation is SUSPENDED at line kotlinx.coroutines.DelayKt.delay(Delay.kt:170)",
).joinToString("\n")

/** A trace carrying `[CIRCULAR REFERENCE: …]`, which stays refused under the generic message. */
private val CIRCULAR_TRACE = listOf(
    "com.acme.payouts.PayoutRejected: x",
    "\tat com.acme.payouts.PayoutLedger.settle(PayoutLedger.java:42)",
    "Caused by: java.sql.SQLException: y",
    "\tat com.acme.payouts.PayoutLedger.settle(PayoutLedger.java:40)",
    "\t[CIRCULAR REFERENCE: com.acme.payouts.PayoutRejected: x]",
).joinToString("\n")

/** What a user's clipboard held before an invocation that must not touch it. */
private const val PREVIOUS_CLIPBOARD = "the trace the user copied a minute ago"

/**
 * A synthetic trace of generated names over the fixture project: a coroutine's state machine, a
 * method the compiler suffixed, a generated class under a nested class, a generated method under a
 * method whose own name carries a `$`, a frame whose class does not resolve at all, and a library
 * and a JDK frame for the harness to classify and the leak check to subtract.
 */
private val GENERATED_TRACE = listOf(
    "com.acme.payouts.PayoutRejected: x",
    "\tat com.acme.billing.BillingService\$charge\$1.invokeSuspend(BillingService.java:42)",
    "\tat com.acme.billing.BillingService.charge\$suspendImpl(BillingService.java:40)",
    "\tat com.acme.billing.BillingService\$Charge\$retry\$1.run(BillingService.java:30)",
    "\tat com.acme.billing.BillingService.charge\$retry\$suspendImpl(BillingService.java:20)",
    "\tat com.acme.payouts.PayoutGhost.haunt(PayoutGhost.java:9)",
    "\tat org.junit.Assert.fail(Assert.java:89)",
    "\tat java.base/java.lang.Thread.run(Thread.java:840)",
).joinToString("\n")

/** Every remainder in [GENERATED_TRACE]: what is left of each name after its longest resolved prefix. */
private val REMAINDERS = listOf("charge\$1", "retry\$1", "suspendImpl")

/** Every word a remainder in [GENERATED_TRACE] carries, none of which the output may. */
private val REMAINDER_WORDS = listOf("charge", "retry", "suspendImpl")

/**
 * **What survives the oracle on [GENERATED_TRACE] by design**: `com`, for the reason it does on the
 * other trace, and `invokeSuspend`, the method the language compiles every coroutine into — silent
 * exactly as `it` and `component1` are in a snippet. The fixture has no Kotlin runtime attached to
 * declare it, so it is adjudicated here rather than subtracted.
 */
private val GENERATED_SURVIVORS = setOf("com", "invokeSuspend", "at")
