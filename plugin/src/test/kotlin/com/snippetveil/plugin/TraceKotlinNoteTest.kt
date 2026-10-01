package com.snippetveil.plugin

/**
 * **The trace preview's Kotlin note, in the configuration a K1 user is in** — the Kotlin plugin
 * running and SnippetVeil's Kotlin path not activated.
 *
 * `Anonymize Stack Trace…` reads the clipboard, so the source-file gate never reaches it and its
 * refusal cannot fire. What a K1 user gets instead is a trace that anonymizes and Kotlin frames that
 * quietly fail to resolve — honest about *what* failed, in the `unknown` count, and silent about
 * *why*. The note is that why, said as a possible cause beside the number.
 *
 * **The predicate is a configuration fact and a count, and nothing read off the trace.** There is no
 * *"is this a Kotlin trace?"*: a `.kt` in a file position deciding what the preview says would be a
 * text-keyed judgment. So every trace here is pure Java, and the note fires on one anyway — which is
 * the decision, not a gap.
 *
 * The plugin-not-running half is `KotlinDisabledBootTest`'s, because only an IDE without the Kotlin
 * plugin can produce it; the Kotlin-available half is `KotlinTraceTest`'s, in the cells where
 * SnippetVeil's Kotlin path loads.
 */
class TraceKotlinNoteTest : KotlinUnregisteredTestCase() {

    /**
     * **One unresolved frame in a pure-Java trace, and the note names K1 with the Kotlin settings
     * link** — the same page the editor refusal opens for the same cause, under the same words.
     */
    fun `test a trace with an unknown names K1 and links Kotlin settings`() {
        addLedgerProject()

        val analysis = captureTrace(ONE_UNRESOLVED_FRAME)

        assertTrue("the trace resolved every name, so the note has nothing to stand beside", analysis.result.counts.unknown > 0)
        withDialog(PreviewDialog.forTrace(project, analysis)) { dialog ->
            assertEquals(listOf(K1_NOTE, "Open Kotlin settings"), kotlinNoteIn(dialog))
        }
    }

    /**
     * **A note, not a refusal, and not a balloon.** The trace was anonymized and copied exactly as it
     * is everywhere else, and the one balloon is the copy's: nothing about the configuration is said
     * outside the preview, and nothing offers a report link over a plugin working as designed.
     */
    fun `test the note says nothing about the clipboard, offers no report and asserts no cause`() {
        addLedgerProject()

        val analysis = captureTrace(ONE_UNRESOLVED_FRAME)

        assertEquals("Anonymized stack trace copied", notifications.single().title)
        withDialog(PreviewDialog.forTrace(project, analysis)) { dialog ->
            val note = kotlinNoteIn(dialog)
            assertFalse("the note carries a clipboard clause: $note", note.any { "clipboard" in it.lowercase() })
            assertFalse("the note offers a report link: $note", note.any { "report" in it.lowercase() })
            assertTrue("the note claims the cause rather than naming a possible one: $note", "possible" in note.first())
        }
    }

    /** **Zero unknown, no note** — the configuration alone is not something this trace has to hear about. */
    fun `test a trace with no unknown carries no note`() {
        addLedgerProject()

        val analysis = captureTrace(ALL_RESOLVED)

        assertEquals("a name in the fully resolved trace came back unknown", 0, analysis.result.counts.unknown)
        withDialog(PreviewDialog.forTrace(project, analysis)) { dialog ->
            assertEmpty(kotlinNoteIn(dialog))
        }
    }

    private fun addLedgerProject() {
        myFixture.addFileToProject(
            "com/acme/payouts/PayoutLedger.java",
            "package com.acme.payouts;\n\npublic class PayoutLedger {\n    public void settle() {}\n}",
        )
    }
}

/** The note for an IDE whose Kotlin plugin runs and whose SnippetVeil Kotlin path did not activate. */
private const val K1_NOTE = "SnippetVeil's Kotlin support is not active, which usually means the Kotlin plugin is in " +
    "K1 mode. Kotlin frames cannot resolve without it — one possible reason a name here is unknown."

/** A pure-Java trace with one frame whose class the project does not declare. */
private val ONE_UNRESOLVED_FRAME = listOf(
    "java.lang.IllegalStateException: refused",
    "\tat com.acme.payouts.PayoutLedger.settle(PayoutLedger.java:42)",
    "\tat com.acme.payouts.PayoutGhost.haunt(PayoutGhost.java:9)",
    "\tat java.lang.Thread.run(Thread.java:840)",
).joinToString("\n")

/** The same trace without its unresolved frame, so every name in it resolves. */
private val ALL_RESOLVED = listOf(
    "java.lang.IllegalStateException: refused",
    "\tat com.acme.payouts.PayoutLedger.settle(PayoutLedger.java:42)",
    "\tat java.lang.Thread.run(Thread.java:840)",
).joinToString("\n")
