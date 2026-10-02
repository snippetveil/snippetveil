package com.snippetveil.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.opentest4j.AssertionFailedError

/**
 * **A Kotlin coroutine trace, row by row** — the six marker rows `kotlinx.coroutines` writes into a
 * failing coroutine's trace, the creation block one of them heads, and the `DebugProbes` dump that is
 * refused and named as one.
 *
 * Every `\t` (U+0009) and every `\b` (U+0008) is written as an **escape**, in every fixture: a literal
 * BACKSPACE is invisible in an editor and erases the character before it in a terminal, so a fixture
 * that carried one literally would say nothing a reader could check. The legacy fixtures go through
 * [legacy], which fails when one does not actually hold a U+0008 — a legacy row with its backspaces
 * lost is the terminal rendering, and a test over it would be testing the other row.
 */
class CoroutineTraceTest {

    // ---------------------------------------------------------------------------------------------
    // The modern pair: anchored on the name, the parentheses wildcarded.
    // ---------------------------------------------------------------------------------------------

    /**
     * **`_BOUNDARY` is admitted wherever the library's own line number put it**, and it is verbatim
     * and reports no name: `<N>` is `CoroutineDebugging.kt`'s own source line, which has moved between
     * releases, so the row anchors on the name and not on the number.
     */
    @Test
    fun `a boundary marker at two different line numbers is admitted verbatim and names nothing`() {
        val text = "java.lang.IllegalStateException: boom\n" +
            "\tat com.acme.billing.Ledger.settle(Ledger.kt:42)\n" +
            "\tat _COROUTINE._BOUNDARY._(CoroutineDebugging.kt:46)\n" +
            "\tat com.acme.billing.Ledger.post(Ledger.kt:12)\n" +
            "\tat _COROUTINE._BOUNDARY._(CoroutineDebugging.kt:42)\n" +
            "\tat com.acme.billing.Main.run(Main.kt:3)"
        val trace = read(text)

        assertEquals(text, trace.text, "a marker was not kept verbatim")
        assertEquals(listOf("com.acme.billing.Ledger", "com.acme.billing.Ledger", "com.acme.billing.Main"), trace.frames.map { it.type.text })
        assertNoNameIn(trace, "_COROUTINE", "_BOUNDARY", "CoroutineDebugging")
    }

    /**
     * **Row precedence: the marker rows are matched ahead of the frame row.** A modern marker has
     * exactly a frame's shape, and read as one it would report `_COROUTINE._BOUNDARY` as a class, `_`
     * as a method and `CoroutineDebugging.kt` as a file — and render `Unknown1.Unknown2(Unknown3)`,
     * which contradicts *`kotlinx.*` is preserved* and inflates the `Unknown` count. This goes red the
     * moment the frame row is tried first.
     */
    @Test
    fun `a modern marker is matched ahead of the frame row whose shape it has`() {
        for (marker in listOf(BOUNDARY, CREATION)) {
            val trace = read("com.acme.Boom: x\n\tat com.acme.Job.run(Job.kt:7)\n$marker")

            assertEquals(1, trace.frames.size, "the marker `$marker` was read as a frame: ${trace.frames.map { it.type.text }}")
            assertNoNameIn(trace, "_COROUTINE", "_BOUNDARY", "_CREATION", "_", "CoroutineDebugging.kt")
        }
    }

    /**
     * **The parentheses are wildcarded, over the locations a frame may print** — any file, with or
     * without a line, and the JVM's two fixed tokens — because the file is read off the library's own
     * frame and a shrunk build rewrites it.
     */
    @Test
    fun `a modern marker admits any location a frame could print`() {
        for (location in listOf("CoroutineDebugging.kt", "CoroutineDebugging.kt:1", "SourceFile:12", "Unknown Source", "Native Method")) {
            val line = "\tat _COROUTINE._BOUNDARY._($location)"
            val trace = read("com.acme.Boom: x\n\tat com.acme.Job.run(Job.kt:7)\n$line")

            assertEquals(line, trace.text.lines().last())
            assertEquals(1, trace.frames.size)
        }
    }

    /**
     * **Anchored on the name, and only the name is fixed**: a marker that is not one of the two the
     * library writes, or whose parentheses hold what no frame prints, is outside the vocabulary. It
     * is not quietly read as a frame instead, either — a line that looks like a marker and is not one
     * is not a trace this grammar knows.
     */
    @Test
    fun `a marker-shaped line the library does not write refuses`() {
        refused("com.acme.Boom: x\n\tat com.acme.Job.run(Job.kt:7)\n\tat _COROUTINE._BOUNDARY._(com.acme.Secret notes)")
        refused("com.acme.Boom: x\n\tat com.acme.Job.run(Job.kt:7)\n\tat _COROUTINE._BOUNDARY._(CoroutineDebugging.kt:42) trailing")
    }

    /**
     * **`_CREATION` heads a creation block, and the block is ordinary frames**: they are reported like
     * the call stack's, so the plugin resolves and mints them exactly as it does those.
     */
    @Test
    fun `the frames under a creation marker are reported like the call stack's`() {
        val text = "java.lang.IllegalStateException: boom\n" +
            "\tat com.acme.billing.Ledger.settle(Ledger.kt:42)\n" +
            "\tat _COROUTINE._CREATION._(CoroutineDebugging.kt:69)\n" +
            "\tat kotlin.coroutines.intrinsics.IntrinsicsKt__IntrinsicsJvmKt.createCoroutineUnintercepted(IntrinsicsJvm.kt:122)\n" +
            "\tat kotlinx.coroutines.intrinsics.CancellableKt.startCoroutineCancellable(Cancellable.kt:30)\n" +
            "\tat kotlinx.coroutines.BuildersKt__Builders_commonKt.launch(Builders.common.kt:56)\n" +
            "\tat com.acme.billing.Scheduler.start(Scheduler.kt:18)"
        val trace = read(text)

        assertEquals(text, trace.text)
        assertEquals(
            listOf(
                "com.acme.billing.Ledger",
                "kotlin.coroutines.intrinsics.IntrinsicsKt__IntrinsicsJvmKt",
                "kotlinx.coroutines.intrinsics.CancellableKt",
                "kotlinx.coroutines.BuildersKt__Builders_commonKt",
                "com.acme.billing.Scheduler",
            ),
            trace.frames.map { it.type.text },
        )
        assertEquals("Builders.common.kt", trace.frames[3].file?.text)
    }

    /** **A marker nests like a frame does**, under a `Suppressed:` or a `Caused by:` beneath one. */
    @Test
    fun `a marker is admitted at a frame's nesting under Suppressed`() {
        val text = "com.acme.Outer: wrapped\n\tat com.acme.Job.run(Job.kt:7)\n" +
            "\tSuppressed: com.acme.Closing: on close\n" +
            "\t\tat com.acme.Resource.close(Resource.kt:9)\n" +
            "\t\tat _COROUTINE._BOUNDARY._(CoroutineDebugging.kt:46)\n" +
            "\t\t(Coroutine boundary)\n"

        assertEquals(text, read(text).text)
    }

    /**
     * **A space-indented coroutine trace is read like a tab-indented one**: every marker row, both
     * renderings of the legacy pair included, is admitted under spaces and no-break spaces, verbatim,
     * and still reports no name.
     */
    @Test
    fun `every marker row is admitted when the trace is indented with spaces or no-break spaces`() {
        for (indent in listOf("    ", "  ", "\u00A0\u00A0", " \t")) {
            for (marker in listOf(BOUNDARY, CREATION) + LEGACY_ROWS) {
                val text = "com.acme.Outer: wrapped\n${indent}at com.acme.Job.run(Job.kt:7)\n" +
                    marker.replace("\t", indent) + "\n" +
                    "${indent}Suppressed: com.acme.Closing: on close\n" +
                    "$indent${indent}at com.acme.Resource.close(Resource.kt:9)\n" +
                    "$indent$indent${marker.removePrefix("\t")}\n" +
                    "Caused by: com.acme.Inner: the cause\n" +
                    "${indent}at com.acme.Store.save(Store.kt:3)\n" +
                    "$indent... 3 more"
                val trace = read(text)

                assertEquals(text, trace.text, "the marker ${visible(marker)} under ${visible(indent)} was not kept verbatim")
                assertEquals(listOf("com.acme.Job", "com.acme.Resource", "com.acme.Store"), trace.frames.map { it.type.text })
                assertNoNameIn(trace, "_COROUTINE", "_BOUNDARY", "_CREATION", "_", "CoroutineDebugging.kt")
            }
        }
    }

    /** **A marker is not a frame**, so a paste of markers alone is a trace with no frames in it. */
    @Test
    fun `a trace whose only rows under the header are markers refuses`() {
        refused("com.acme.Boom: x\n$BOUNDARY\n\t(Coroutine creation stacktrace)")
    }

    // ---------------------------------------------------------------------------------------------
    // The legacy pair: whole-line fixed tokens, in both renderings.
    // ---------------------------------------------------------------------------------------------

    /**
     * **Both legacy renderings are admitted, verbatim**: the backspace form the clipboard holds and the
     * parenthesised form a terminal shows. `kotlinx.coroutines` renamed its markers in 1.7.0, and the
     * older artifacts are still pinned in Android and legacy projects.
     */
    @Test
    fun `both renderings of both legacy markers are admitted verbatim`() {
        for (marker in LEGACY_ROWS) {
            val text = "com.acme.Boom: x\n\tat com.acme.Job.run(Job.kt:7)\n$marker\n\tat com.acme.Main.main(Main.kt:3)"
            val trace = read(text)

            assertEquals(text, trace.text, "the legacy marker was not kept verbatim: ${visible(marker)}")
            assertEquals(listOf("com.acme.Job", "com.acme.Main"), trace.frames.map { it.type.text })
            assertEquals(listOf("x"), trace.texts.map { trace.text.substring(it.start, it.end) }, "the legacy marker was read as a text: ${visible(marker)}")
        }
    }

    /**
     * **A whole-line literal, with no variable part**: the legacy markers carry no class name to
     * resolve, so they are matched the way `(Native Method)` is, and nothing near them is admitted.
     */
    @Test
    fun `a line that is almost a legacy marker refuses`() {
        val near = listOf(
            // The backspace form with its backspaces lost — neither rendering.
            "\tat (Coroutine boundary.()",
            // One backspace short.
            legacy("\tat \b\b(Coroutine boundary.\b(\b)"),
            // A variable part where the literal has none.
            "\t(Coroutine boundary of com.acme.Job)",
            "\t(Coroutine boundary) ",
            "\t(coroutine boundary)",
        )
        for (line in near) refused("com.acme.Boom: x\n\tat com.acme.Job.run(Job.kt:7)\n$line")
    }

    /**
     * **The harness's own check, shown red**: a legacy fixture whose U+0008 went missing — a
     * reformat, a copy through a terminal — fails [legacy] rather than quietly testing the other row.
     */
    @Test
    fun `a legacy fixture without a backspace in it fails the harness`() {
        assertThrows<AssertionFailedError> { legacy("\tat (Coroutine boundary.()") }
        assertThrows<AssertionFailedError> { legacy("\t(Coroutine boundary)") }
    }

    // ---------------------------------------------------------------------------------------------
    // The DebugProbes dump: refused, and named as a dump.
    // ---------------------------------------------------------------------------------------------

    /**
     * **Every shape a `DebugProbes` dump is handed over in is refused as a dump** — `dumpCoroutines`
     * output with and without its creation blocks, cut short, in Windows line endings, and each form
     * of the `printJob` / `jobToString` tree — and never read as a trace, `_CREATION` frames and all.
     */
    @Test
    fun `every DebugProbes dump shape refuses as a dump`() {
        for ((name, dump) in DUMPS) {
            assertTrue(
                parseTrace(dump) === TraceReading.CoroutineDump,
                "the `$name` dump was not refused as a dump: ${parseTrace(dump)}\n$dump",
            )
        }
    }

    /**
     * **A dump whose tabs were turned into spaces is still a dump**, and refused as one rather than
     * read as a trace or refused under the generic message.
     */
    @Test
    fun `every DebugProbes dump shape indented with spaces refuses as a dump`() {
        for ((name, dump) in DUMPS) {
            for (indent in listOf("    ", "\u00A0\u00A0")) {
                val spaced = dump.replace("\t", indent)
                assertTrue(
                    parseTrace(spaced) === TraceReading.CoroutineDump,
                    "the `$name` dump indented with ${visible(indent)} was not refused as a dump: ${parseTrace(spaced)}\n${visible(spaced)}",
                )
            }
        }
    }

    /**
     * **The predicate is two fixed library literals, and nothing else.** A block header on its own —
     * `Coroutine "…", state: …` — carries a class name there is nothing to resolve from, and it is not
     * one of them: a paste of blocks without the dump's first line is refused under the generic
     * message. Widening the predicate to match it would be a grammar with user text in it.
     */
    @Test
    fun `a dump's block without the dump's first line refuses under the generic message`() {
        refused(
            "Coroutine \"coroutine#2\":DeferredCoroutine{Active}@1b68b9a4, state: SUSPENDED\n" +
                "\tat kotlinx.coroutines.DelayKt.delay(Delay.kt:170)\n" +
                "\tat com.acme.billing.Ledger\$settle\$1.invokeSuspend(Ledger.kt:42)",
        )
    }

    /**
     * **The dump predicate names what was handed over, and only once the trace is refused.** A trace
     * whose message happens to hold the dump's words is a trace, and it is read.
     */
    @Test
    fun `a trace whose message holds a dump's words is still a trace`() {
        val trace = read("java.lang.IllegalStateException: continuation is SUSPENDED at line 7\n\tat com.acme.Job.run(Job.kt:7)")

        assertEquals(listOf("java.lang.IllegalStateException"), trace.exceptions.map { it.text })
    }

    /** **`[CIRCULAR REFERENCE: …]` stays refused**, under the generic message and not the dump's. */
    @Test
    fun `a circular reference line refuses under the generic message`() {
        refused(
            "com.acme.Outer: wrapped\n\tat com.acme.Job.run(Job.kt:7)\n" +
                "Caused by: com.acme.Inner: the cause\n\tat com.acme.Store.save(Store.kt:3)\n" +
                "\t[CIRCULAR REFERENCE: com.acme.Outer: wrapped]",
        )
    }

    private fun read(text: String): StackTrace {
        val reading = parseTrace(text)
        assertTrue(reading is TraceReading.Read, "this trace was refused (${reading}):\n${visible(text)}")
        return (reading as TraceReading.Read).trace
    }

    private fun refused(text: String) {
        assertTrue(parseTrace(text) === TraceReading.NotATrace, "this paste was not refused as not a trace:\n${visible(text)}")
    }

    /** No name the reading reports is any of [words], nor holds one of them as a segment. */
    private fun assertNoNameIn(trace: StackTrace, vararg words: String) {
        val names = trace.exceptions + trace.frames.flatMap { listOfNotNull(it.type, it.method, it.file) }
        for (name in names) {
            val segments = name.text.split('.', '$')
            assertTrue(words.none { it == name.text || it in segments }, "the reading reported `${name.text}` as a name")
        }
    }
}

/** A modern boundary marker, as `kotlinx.coroutines` 1.7.0 and later writes it. */
private const val BOUNDARY = "\tat _COROUTINE._BOUNDARY._(CoroutineDebugging.kt:46)"

/** A modern creation marker. */
private const val CREATION = "\tat _COROUTINE._CREATION._(CoroutineDebugging.kt:69)"

/**
 * **A legacy fixture, checked to be one**: it has to hold a U+0008, or it is the terminal rendering
 * of a marker rather than the marker, and the assertion says which fixture lost it.
 */
private fun legacy(fixture: String): String {
    assertTrue('\u0008' in fixture, "this legacy fixture holds no U+0008, so it is not the backspace form: ${visible(fixture)}")
    return fixture
}

/**
 * The four legacy rows: the backspace form a copy holds, and the form a terminal renders it as. The
 * parenthesised pair holds no U+0008 by definition and does not go through [legacy].
 */
private val LEGACY_ROWS = listOf(
    legacy("\tat \b\b\b(Coroutine boundary.\b(\b)"),
    legacy("\tat \b\b\b(Coroutine creation stacktrace.\b(\b)"),
    "\t(Coroutine boundary)",
    "\t(Coroutine creation stacktrace)",
)

/** [text] with its control characters named, so a failure message can be read. */
private fun visible(text: String): String = text.replace("\b", "\\b").replace("\t", "\\t").replace("\u00A0", "\\u00A0")

/** A `dumpCoroutines` block's frames, which are ordinary frames, `_CREATION` among them. */
private const val DUMP_BLOCK = "Coroutine \"coroutine#2\":DeferredCoroutine{Active}@1b68b9a4, state: SUSPENDED\n" +
    "\tat kotlinx.coroutines.DelayKt.delay(Delay.kt:170)\n" +
    "\tat com.acme.billing.Ledger\$settle\$1.invokeSuspend(Ledger.kt:42)\n" +
    "\tat _COROUTINE._CREATION._(CoroutineDebugging.kt:69)\n" +
    "\tat kotlinx.coroutines.intrinsics.CancellableKt.startCoroutineCancellable(Cancellable.kt:30)\n" +
    "\tat com.acme.billing.Scheduler.start(Scheduler.kt:18)\n"

/** Every shape a `DebugProbes` dump is handed over in, named for the failure message. */
private val DUMPS = listOf(
    "dumpCoroutines, whole" to "Coroutines dump 2026/09/30 12:00:01\n\n" +
        "Coroutine \"coroutine#1\":BlockingCoroutine{Active}@3a4afd8d, state: RUNNING\n" +
        "\tat java.lang.Thread.getStackTrace(Thread.java:1610)\n" +
        "\tat com.acme.billing.MainKt.main(Main.kt:12)\n\n" +
        DUMP_BLOCK,
    "dumpCoroutines, its first line alone" to "Coroutines dump 2026/09/30 12:00:01",
    "dumpCoroutines, its first line and a trailing break" to "Coroutines dump 2026/09/30 12:00:01\n",
    "dumpCoroutines, in Windows line endings" to ("Coroutines dump 2026/09/30 12:00:01\n\n$DUMP_BLOCK").replace("\n", "\r\n"),
    "dumpCoroutines, with a legacy creation marker" to "Coroutines dump 2026/09/30 12:00:01\n\n" +
        "Coroutine \"coroutine#2\":DeferredCoroutine{Active}@1b68b9a4, state: SUSPENDED\n" +
        "\tat kotlinx.coroutines.DelayKt.delay(Delay.kt:170)\n" +
        "\t(Coroutine creation stacktrace)\n" +
        "\tat com.acme.billing.Scheduler.start(Scheduler.kt:18)",
    "printJob, one coroutine" to "\"coroutine#2\":DeferredCoroutine{Active}@1b68b9a4, continuation is SUSPENDED at line kotlinx.coroutines.DelayKt.delay(Delay.kt:170)\n",
    "printJob, a tree" to "\"coroutine#1\":BlockingCoroutine{Active}@3a4afd8d, continuation is RUNNING at line com.acme.billing.MainKt.main(Main.kt:12)\n" +
        "\t\"settlement#2\":StandaloneCoroutine{Active}@1b68b9a4, continuation is SUSPENDED at line com.acme.billing.Ledger\$settle\$1.invokeSuspend(Ledger.kt:42)\n" +
        "\t\t\"coroutine#3\":DeferredCoroutine{Active}@6d06d69c, continuation is SUSPENDED at line kotlinx.coroutines.DelayKt.delay(Delay.kt:170)\n",
    "printJob, a root with no continuation" to "BlockingCoroutine{Active}@3a4afd8d\n" +
        "\t\"coroutine#2\":StandaloneCoroutine{Active}@1b68b9a4, continuation is CREATED at line null\n",
    "printJob, a subtree cut from its root" to "\t\"coroutine#3\":DeferredCoroutine{Active}@6d06d69c, continuation is SUSPENDED at line kotlinx.coroutines.DelayKt.delay(Delay.kt:170)",
    "jobToString, in Windows line endings" to "\"coroutine#1\":BlockingCoroutine{Active}@3a4afd8d, continuation is RUNNING at line com.acme.billing.MainKt.main(Main.kt:12)\r\n" +
        "\t\"coroutine#2\":DeferredCoroutine{Active}@1b68b9a4, continuation is SUSPENDED at line kotlinx.coroutines.DelayKt.delay(Delay.kt:170)\r\n",
)
