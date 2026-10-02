package com.snippetveil.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **The stack-trace grammar, row by row** — what [parseTrace] admits, what it hands back for each
 * admitted row, and the lines that refuse the whole paste.
 *
 * Every fixture is synthetic and every `\t` is written as an escape, because a literal TAB in a
 * fixture is indistinguishable from spaces to a reader and to most editors' reformatting.
 */
class StackTraceTest {

    @Test
    fun `a bare header and a frame are read into a class, a message, a method and a file`() {
        val trace = read("com.acme.billing.PaymentDeclined: card refused\n\tat com.acme.billing.Ledger.settle(Ledger.java:42)")

        assertEquals(listOf("com.acme.billing.PaymentDeclined"), trace.exceptions.map { it.text })
        assertEquals(listOf("card refused"), trace.texts.map { trace.text.substring(it.start, it.end) })

        val frame = trace.frames.single()
        assertEquals("com.acme.billing.Ledger", frame.type.text)
        assertEquals("settle", frame.method?.text)
        assertEquals("Ledger.java", frame.file?.text)
        assertSpansAgree(trace)
    }

    @Test
    fun `the thread line keeps its keyword and its quotes, and the thread name is a text`() {
        val trace = read("Exception in thread \"billing-worker-3\" com.acme.Boom: it broke\n\tat com.acme.Job.run(Job.java:7)")

        assertEquals(listOf("com.acme.Boom"), trace.exceptions.map { it.text })
        assertEquals(
            listOf("billing-worker-3", "it broke"),
            trace.texts.map { trace.text.substring(it.start, it.end) },
        )
        assertTrue(trace.text.startsWith("Exception in thread \"billing-worker-3\" com.acme.Boom: "))
    }

    @Test
    fun `a thread name holding a quote is read up to the header that follows it`() {
        val trace = read("Exception in thread \"say \"hi\" now\" com.acme.Boom: a \"quoted\" message\n\tat com.acme.Job.run(Job.java:7)")

        assertEquals(listOf("com.acme.Boom"), trace.exceptions.map { it.text })
        assertEquals("say \"hi\" now", trace.text.substring(trace.texts[0].start, trace.texts[0].end))
    }

    @Test
    fun `a header with no message is read, with no text`() {
        val trace = read("java.lang.NullPointerException\n\tat com.acme.Job.run(Job.java:7)")

        assertEquals(listOf("java.lang.NullPointerException"), trace.exceptions.map { it.text })
        assertTrue(trace.texts.isEmpty())
    }

    @Test
    fun `Caused by keeps its keyword and reads the rest as a header`() {
        val trace = read(
            "com.acme.Outer: wrapped\n\tat com.acme.Job.run(Job.java:7)\n" +
                "Caused by: com.acme.Inner: the cause\n\tat com.acme.Store.save(Store.java:3)",
        )

        assertEquals(listOf("com.acme.Outer", "com.acme.Inner"), trace.exceptions.map { it.text })
        assertEquals(listOf("wrapped", "the cause"), trace.texts.map { trace.text.substring(it.start, it.end) })
        assertTrue("\nCaused by: com.acme.Inner: " in trace.text)
    }

    @Test
    fun `Suppressed keeps its keyword and its nesting, with frames and Caused by beneath it`() {
        val text = "com.acme.Outer: wrapped\n\tat com.acme.Job.run(Job.java:7)\n" +
            "\tSuppressed: com.acme.Closing: on close\n" +
            "\t\tat com.acme.Resource.close(Resource.java:9)\n" +
            "\tCaused by: com.acme.Deeper: below\n" +
            "\t\t... 1 more\n"
        val trace = read(text)

        assertEquals(listOf("com.acme.Outer", "com.acme.Closing", "com.acme.Deeper"), trace.exceptions.map { it.text })
        assertEquals(text, trace.text, "the nesting was not preserved")
        assertEquals(listOf("com.acme.Job", "com.acme.Resource"), trace.frames.map { it.type.text })
    }

    @Test
    fun `the elided-frames line is verbatim, its count included`() {
        val text = "com.acme.Outer: wrapped\n\tat com.acme.Job.run(Job.java:7)\n" +
            "Caused by: com.acme.Inner: the cause\n\tat com.acme.Store.save(Store.java:3)\n\t... 12 more"
        val trace = read(text)

        assertEquals(text, trace.text)
        assertTrue(trace.text.endsWith("\t... 12 more"))
    }

    @Test
    fun `Native Method and Unknown Source are fixed tokens with no file in them`() {
        val trace = read(
            "com.acme.Boom: x\n" +
                "\tat jdk.internal.reflect.NativeMethodAccessorImpl.invoke0(Native Method)\n" +
                "\tat com.acme.Generated.call(Unknown Source)",
        )

        assertEquals(listOf(null, null), trace.frames.map { it.file })
        assertEquals(listOf("invoke0", "call"), trace.frames.map { it.method?.text })
    }

    @Test
    fun `a frame with no line number is admitted`() {
        val trace = read("com.acme.Boom: x\n\tat com.acme.Main.main(Main.java)")

        assertEquals("Main.java", trace.frames.single().file?.text)
    }

    @Test
    fun `a file name with no extension is admitted, which is the shape SnippetVeil's own output has`() {
        val trace = read("com.pkg1.Type2: str3\n\tat Unknown4.Unknown5(Unknown6:12)")

        assertEquals("Unknown4", trace.frames.single().type.text)
        assertEquals("Unknown6", trace.frames.single().file?.text)
    }

    @Test
    fun `an inner class keeps its dollar sign in the class it reports`() {
        val trace = read("com.acme.Boom: x\n\tat com.acme.Foo\$Bar.baz(Foo.java:12)")

        assertEquals("com.acme.Foo\$Bar", trace.frames.single().type.text)
        assertEquals("Foo.java", trace.frames.single().file?.text)
    }

    @Test
    fun `a constructor or an initializer reports no method, because the JVM's own name is not a name`() {
        val trace = read(
            "com.acme.Boom: x\n\tat com.acme.Ledger.<init>(Ledger.java:5)\n\tat com.acme.Ledger.<clinit>(Ledger.java:2)",
        )

        assertEquals(listOf(null, null), trace.frames.map { it.method })
        assertEquals(listOf("com.acme.Ledger", "com.acme.Ledger"), trace.frames.map { it.type.text })
    }

    /**
     * **`invokeSuspend` reports no method either**: a coroutine's state machine is a generated class
     * and the language, not the developer, fixed what its method is called. It is silent for the
     * reason `it` and `component1` are in a snippet, and it stays verbatim.
     */
    @Test
    fun `a coroutine's invokeSuspend reports no method, because the language fixed its spelling`() {
        val trace = read("com.acme.Boom: x\n\tat com.acme.Ledger\$charge\$1.invokeSuspend(Ledger.kt:42)")

        assertEquals(null, trace.frames.single().method)
        assertEquals("com.acme.Ledger\$charge\$1", trace.frames.single().type.text)
        assertEquals("\tat com.acme.Ledger\$charge\$1.invokeSuspend(Ledger.kt:42)", trace.text.lines().last())
    }

    /**
     * **The prefix is dropped, and the output is deliberately not the input.** A named project module
     * is usually the organisation's package prefix verbatim, and the prefix is deployment metadata
     * rather than a program symbol — so it has no placeholder and no ledger row, and the cost of
     * that is that a trace does not come back byte for byte.
     */
    @Test
    fun `module and classloader prefixes are dropped, and the trace is not byte-faithful because of it`() {
        val prefixed = "com.acme.Boom: x\n" +
            "\tat java.base/java.lang.Thread.run(Thread.java:840)\n" +
            "\tat java.base@21.0.1/java.lang.Thread.run(Thread.java:1583)\n" +
            "\tat app//com.acme.Job.run(Job.java:7)\n" +
            "\tat com.acme.billing/com.acme.billing.Ledger.settle(Ledger.java:42)\n" +
            "\tat billing-worker//com.acme.billing.Ledger.settle(Ledger.java:42)\n" +
            "\tat com.acme.loader/billing@2.1/com.acme.billing.Ledger.settle(Ledger.java:42)"
        val trace = read(prefixed)

        assertEquals(
            "com.acme.Boom: x\n" +
                "\tat java.lang.Thread.run(Thread.java:840)\n" +
                "\tat java.lang.Thread.run(Thread.java:1583)\n" +
                "\tat com.acme.Job.run(Job.java:7)\n" +
                "\tat com.acme.billing.Ledger.settle(Ledger.java:42)\n" +
                "\tat com.acme.billing.Ledger.settle(Ledger.java:42)\n" +
                "\tat com.acme.billing.Ledger.settle(Ledger.java:42)",
            trace.text,
        )
        assertTrue(trace.text != prefixed, "the prefix-dropping is lossy by decision, and this trace came back whole")
        assertSpansAgree(trace)
    }

    // ---------------------------------------------------------------------------------------------
    // Indentation: a leading run of tab, space and no-break space, in any mix.
    // ---------------------------------------------------------------------------------------------

    /**
     * **A trace copied out of a chat, a tracker or a web page has lost its tabs**, to spaces or to
     * no-break spaces, and it is the same trace: the same exceptions, frames and texts, and its
     * indentation comes back byte for byte, whichever it was.
     */
    @Test
    fun `the same trace indented with tabs, spaces, no-break spaces or a mix reads alike and keeps its indentation`() {
        val expected = read(indented("\t"))
        for ((name, indent) in INDENTS) {
            val text = indented(indent)
            val trace = read(text)

            assertEquals(text, trace.text, "the $name indentation was not kept byte for byte")
            assertEquals(expected.exceptions.map { it.text }, trace.exceptions.map { it.text }, name)
            assertEquals(expected.frames.map { listOf(it.type.text, it.method?.text, it.file?.text) }, trace.frames.map { listOf(it.type.text, it.method?.text, it.file?.text) }, name)
            assertEquals(expected.texts.map { expected.text.substring(it.start, it.end) }, trace.texts.map { trace.text.substring(it.start, it.end) }, name)
            assertSpansAgree(trace)
        }
    }

    /**
     * **Nesting depth is not computed from spaces**: the grammar asks only whether a line is
     * indented, so a `Suppressed:` block nested two spaces deeper or not deeper at all is read, and
     * each line keeps what was pasted.
     */
    @Test
    fun `a space-indented Suppressed block is read whatever depth its lines are pasted at`() {
        val text = "com.acme.Outer: wrapped\n  at com.acme.Job.run(Job.java:7)\n" +
            "  Suppressed: com.acme.Closing: on close\n" +
            " at com.acme.Resource.close(Resource.java:9)\n" +
            "\t \u00A0... 1 more\n"
        val trace = read(text)

        assertEquals(text, trace.text)
        assertEquals(listOf("com.acme.Outer", "com.acme.Closing"), trace.exceptions.map { it.text })
    }

    /** Spaces widen which characters indent a line, never which lines are admitted. */
    @Test
    fun `Suppressed at column 0 still refuses when the rest of the trace is space-indented`() {
        refused("com.acme.Boom: x\n    at com.acme.Job.run(Job.java:7)\nSuppressed: com.acme.Other: y\n        at com.acme.Job.run(Job.java:7)")
    }

    @Test
    fun `Windows line endings are kept, and a trailing line break is admitted`() {
        val text = "com.acme.Boom: x\r\n\tat com.acme.Job.run(Job.java:7)\r\n"
        val trace = read(text)

        assertEquals(text, trace.text)
        assertEquals("x", trace.text.substring(trace.texts.single().start, trace.texts.single().end))
        assertSpansAgree(trace)
    }

    // ---------------------------------------------------------------------------------------------
    // Refusals: any line outside the vocabulary refuses the whole paste.
    // ---------------------------------------------------------------------------------------------

    /**
     * **A log line around the trace refuses the paste**, rather than being passed through. One
     * log-prefix line already carries a class name, and passing unrecognised lines through verbatim
     * would be free-text anonymization that fails in the leaking direction.
     */
    @Test
    fun `a log line above the trace refuses the whole paste`() {
        refused(
            "2026-09-30 12:00:01 ERROR c.a.b.BillingService - settlement failed\n" +
                "com.acme.Boom: x\n\tat com.acme.Job.run(Job.java:7)",
        )
    }

    @Test
    fun `a log line below the trace refuses the whole paste`() {
        refused("com.acme.Boom: x\n\tat com.acme.Job.run(Job.java:7)\n2026-09-30 12:00:02 INFO retrying")
    }

    @Test
    fun `an indented line that is not part of a trace refuses, whatever it is indented with`() {
        for (indent in INDENTS.values) {
            refused("com.acme.Boom: x\n${indent}at com.acme.Job.run(Job.java:7)\n${indent}INFO started")
        }
    }

    @Test
    fun `a header indented with spaces or no-break spaces refuses, as one indented with a tab does`() {
        for (indent in INDENTS.values) {
            refused("${indent}com.acme.Boom: x\n${indent}at com.acme.Job.run(Job.java:7)")
        }
    }

    /** **A no-break space is indentation and nothing else**: one between `at` and the frame is not normalised. */
    @Test
    fun `a no-break space anywhere but the indentation refuses`() {
        refused("com.acme.Boom: x\n\tat\u00A0com.acme.Job.run(Job.java:7)")
        refused("com.acme.Boom: x\n\tat com.acme.Job.run(Job.java:7)\n\t...\u00A01 more")
    }

    @Test
    fun `a frame with anything after its closing bracket refuses`() {
        refused("com.acme.Boom: x\n\tat com.acme.Job.run(Job.java:7) ~[billing.jar:1.0]")
    }

    @Test
    fun `a paste starting mid-trace refuses`() {
        refused("\tat com.acme.Job.run(Job.java:7)\n\tat com.acme.Main.main(Main.java:3)")
    }

    @Test
    fun `a header with no frames under it anywhere refuses`() {
        refused("com.acme.Boom: x")
    }

    @Test
    fun `a message running onto a second line refuses`() {
        refused("com.acme.Boom: first line\nsecond line of the message\n\tat com.acme.Job.run(Job.java:7)")
    }

    @Test
    fun `a blank line inside the trace refuses`() {
        refused("com.acme.Boom: x\n\n\tat com.acme.Job.run(Job.java:7)")
    }

    @Test
    fun `an empty clipboard refuses`() {
        refused("")
    }

    @Test
    fun `a frame whose class is not a Java name refuses`() {
        refused("com.acme.Boom: x\n\tat com.acme.Foo\$\$Lambda/0x0000000800c02a00.run(Unknown Source)")
    }

    @Test
    fun `Suppressed at the top level refuses, because it is only ever printed nested`() {
        refused("com.acme.Boom: x\n\tat com.acme.Job.run(Job.java:7)\nSuppressed: com.acme.Other: y")
    }

    @Test
    fun `an elided-frames line with no count refuses`() {
        refused("com.acme.Boom: x\n\tat com.acme.Job.run(Job.java:7)\n\t... more")
    }

    /**
     * One trace with every indented row: frames, `Caused by:` at column 0 and indented under
     * `Suppressed:`, nested frames, a frame-free `... N more`. Each indented line takes [indent] once,
     * and the nested lines twice, as the JVM prints them.
     */
    private fun indented(indent: String): String =
        "Exception in thread \"main\" com.acme.Outer: wrapped\n" +
            "${indent}at com.acme.Job.run(Job.java:7)\n" +
            "${indent}at java.lang.Thread.run(Thread.java:840)\n" +
            "${indent}Suppressed: com.acme.Closing: on close\n" +
            "$indent${indent}at com.acme.Resource.close(Resource.java:9)\n" +
            "$indent${indent}... 1 more\n" +
            "Caused by: com.acme.Inner: the cause\n" +
            "${indent}at com.acme.Store.save(Store.java:3)\n" +
            "${indent}... 2 more\n"

    private fun read(text: String): StackTrace {
        val reading = parseTrace(text)
        assertTrue(reading is TraceReading.Read, "this trace was refused:\n$text")
        return (reading as TraceReading.Read).trace
    }

    private fun refused(text: String) {
        assertTrue(parseTrace(text) === TraceReading.NotATrace, "this paste was admitted as a trace:\n$text")
    }

    /** Every span the reading reports indexes into its own text and names what it says it names. */
    private fun assertSpansAgree(trace: StackTrace) {
        for (name in trace.exceptions + trace.frames.flatMap { listOfNotNull(it.type, it.method, it.file) }) {
            assertEquals(name.text, trace.text.substring(name.start, name.end))
        }
        assertNull(trace.texts.firstOrNull { it.start > it.end || it.end > trace.text.length })
    }
}

/**
 * **Every indentation a copied trace arrives with**: the JVM's tab, the spaces a chat client, a
 * tracker or rendered Markdown turns it into, the no-break spaces an HTML copy can, and a mix. Every
 * U+00A0 is written as an escape, for the reason every tab is.
 */
private val INDENTS = mapOf(
    "tab" to "\t",
    "four spaces" to "    ",
    "two spaces" to "  ",
    "no-break spaces" to "\u00A0\u00A0\u00A0\u00A0",
    "a mix" to " \t\u00A0 ",
)
