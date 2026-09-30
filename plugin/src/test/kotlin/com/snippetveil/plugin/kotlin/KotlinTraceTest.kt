package com.snippetveil.plugin.kotlin

import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.LightProjectDescriptor
import com.snippetveil.core.REMAINDER_ORIGINAL
import com.snippetveil.core.SymbolOrigin
import com.snippetveil.core.TraceReading
import com.snippetveil.core.parseTrace
import com.snippetveil.plugin.Analysis
import com.snippetveil.plugin.AnonymizeStackTraceAction
import com.snippetveil.plugin.FakeClipboard
import com.snippetveil.plugin.PlaceholderLedger
import com.snippetveil.plugin.Previews
import com.snippetveil.plugin.SymbolFacts
import com.snippetveil.plugin.attachJar
import java.io.File

/**
 * **A Kotlin coroutine trace, end to end** — the markers `kotlinx.coroutines` writes into it, the
 * `kotlinx.*` frames that make up most of it, the creation block, and the file facade a Kotlin
 * frame's file name renders.
 *
 * What the grammar admits is `:core`'s and is asserted there, row by row. What is asserted here is
 * what only a running IDE can say: that `kotlinx.*` resolves and classifies as a library and is kept
 * as printed, that a marker reaches no resolver and counts as nothing, and which symbol a Kotlin
 * file's name is fixed to.
 *
 * **The coroutines library is attached as a real jar**, beside the stdlib — see
 * `kotlinFixtureCoroutines` in `plugin/build.gradle.kts` — and every invocation here first asserts
 * that each `kotlinx.*` class its trace names resolved to a library: a fixture whose `kotlinx.*`
 * stopped resolving — a jar too new for the compiler reading its metadata, say — would render every
 * such frame `Unknown`, and nothing would be printed that should not be. See
 * [complaintAboutTheCoroutinesLibrary].
 */
internal class KotlinTraceTest : KotlinSnippetTestCase() {

    override fun getProjectDescriptor(): LightProjectDescriptor = COROUTINES_CLASSPATH

    /**
     * **The file facade**: a `.kt` holding a top-level function and a class, with a frame from each,
     * renders two different class placeholders and one file name — the facade's, with the file's real
     * extension. Rendering the frame's class instead would give one real `Billing.kt` two names.
     */
    fun `test a file holding a facade and a class renders two class placeholders and one file name`() {
        assertTheFacadeBehaviourIsPinned()
        addBillingProject()

        val output = invokeAndCapture(FACADE_TRACE).result.text
        val facade = placeholderOf("class:com.acme.billing.BillingKt")
        val payment = placeholderOf("class:com.acme.billing.Payment")
        val settle = placeholderOf("method:class:com.acme.billing.BillingKt#settle")
        val pay = placeholderOf("method:class:com.acme.billing.Payment#pay")

        assertFalse("the facade and the class share a placeholder", facade == payment)
        assertTrue(output, ".$facade.$settle($facade.kt:3)\n" in output)
        assertTrue(output, ".$payment.$pay($facade.kt:6)\n" in output)
        assertFalse("the class's own placeholder named the file: $output", "($payment." in output)
    }

    /**
     * **A `.kt` with no top-level callable has no facade**, so its frames render a bare `Unknown`
     * file name with no extension, beside a class that resolved correctly — truthful, and visibly
     * degraded. Not the class's own placeholder, which would make `Refund.kt` look named after it.
     */
    fun `test a frame from a kt with no top-level callable renders a bare Unknown file name`() {
        assertTheFacadeBehaviourIsPinned()
        addBillingProject()

        val result = invokeAndCapture(FACADE_TRACE).result
        val refund = placeholderOf("class:com.acme.billing.Refund")
        val line = result.text.lines().single { ".$refund." in it }

        assertTrue("the file name is not a bare Unknown: $line", Regex("""\(Unknown\d+:9\)$""").containsMatchIn(line))
        assertFalse("the file name rendered the class's own placeholder: $line", "($refund" in line)
        assertTrue("the file name is not an Unknown row: ${result.unknowns.map { it.name }}", "Refund.kt" in result.unknowns.map { it.name })
    }

    /**
     * **A coroutine trace, whole**: every marker verbatim — the modern pair at two line numbers each,
     * and both renderings of the legacy pair — every `kotlinx.*` frame as printed, file name
     * included, and the creation block's own frames anonymized like the call stack's. **No marker is
     * an `Unknown`**: the only unknowns are what a generated class's name left over.
     */
    fun `test a coroutine trace keeps its markers and kotlinx frames and anonymizes its creation block`() {
        addBillingProject()

        val result = invokeAndCapture(COROUTINE_TRACE).result
        val output = result.text
        val lines = output.lines()

        for (marker in MARKERS) {
            assertTrue("the marker `${visible(marker)}` was not kept verbatim:\n${visible(output)}", marker in lines)
        }
        for (line in COROUTINE_TRACE.lines().filter { it.startsWith("\tat kotlinx.") }) {
            assertTrue("the kotlinx frame `$line` was not kept as printed:\n$output", line in lines)
        }

        val creation = lines.dropWhile { !it.startsWith("\tat _COROUTINE._CREATION.") }
        val payment = placeholderOf("class:com.acme.billing.Payment")
        val facade = placeholderOf("class:com.acme.billing.BillingKt")
        assertTrue("the creation block's project frame was not anonymized:\n$output", creation.any { ".$facade." in it })
        assertTrue(output, lines.any { ".$payment\$Unknown" in it })
        for (word in listOf("acme", "billing", "Billing", "Payment", "settle", "pay\$1")) {
            assertFalse("`$word` survived:\n$output", Regex("""\b${Regex.escape(word)}\b""").containsMatchIn(output))
        }

        assertTrue(
            "something other than a remainder is unknown: ${result.unknowns.map { it.name }}",
            result.unknowns.all { it.name == REMAINDER_ORIGINAL },
        )
        assertTrue("the anonymized trace is not a trace the action reads:\n$output", parseTrace(output) is TraceReading.Read)
    }

    /**
     * **The harness's precondition, shown red**: the complaint fires on a `kotlinx.*` class that did
     * not resolve and on one that resolved into project content — the two ways a broken attachment
     * would make the output look cleaner than a correct one — and is silent on a library's.
     */
    fun `test the coroutines precondition fires on an unresolved or a project-owned kotlinx class`() {
        assertNotNull(complaintAboutTheCoroutinesLibrary("kotlinx.coroutines.DispatchedTask", null))
        assertNotNull(complaintAboutTheCoroutinesLibrary("kotlinx.coroutines.DispatchedTask", SymbolOrigin.IN_CONTENT))
        assertNull(complaintAboutTheCoroutinesLibrary("kotlinx.coroutines.DispatchedTask", SymbolOrigin.LIBRARY))
    }

    /**
     * **Every `kotlinx.*` class [trace]'s frames name resolves, and to a library** — asserted before
     * anything is believed about the output. Read off the trace itself, so the check cannot drift
     * from the fixture it guards.
     */
    private fun assertTheCoroutinesLibraryIsALibrary(trace: String) {
        val facade = JavaPsiFacade.getInstance(project)
        val scope = GlobalSearchScope.allScope(project)
        val named = (parseTrace(trace) as TraceReading.Read).trace.frames.map { it.type.text }.filter { it.startsWith("kotlinx.") }
        assertFalse("the fixture names no kotlinx.* frame, so nothing here is about the library", named.isEmpty())
        for (name in named) {
            val found = facade.findClass(name.replace('$', '.'), scope)
            complaintAboutTheCoroutinesLibrary(name, found?.let { SymbolFacts.originOf(project, it) })?.let { fail(it) }
        }
    }

    /** Invokes over [trace], lets the preview through unchanged, and returns what it was shown. */
    private fun invokeAndCapture(trace: String): Analysis {
        assertTheCoroutinesLibraryIsALibrary(trace)
        var shown: Analysis? = null
        dropEarlierBalloons()
        myFixture.testAction(AnonymizeStackTraceAction(FakeClipboard(trace), Previews { _, analysis -> analysis.also { shown = it } }))
        awaitBackgroundWork()
        return checkNotNull(shown) { "the trace was refused: ${notifications.map { it.content }}" }
    }

    /**
     * `Billing.kt` holds a top-level function and a class, so it has a facade; `Refund.kt` holds only
     * a class, so it has none. Nothing named `pay$1` is declared: that is what a compiler adds.
     */
    private fun addBillingProject() {
        myFixture.addFileToProject(
            "com/acme/billing/Billing.kt",
            """
            package com.acme.billing

            fun settle() {}

            class Payment {
                suspend fun pay() {}
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "com/acme/billing/Refund.kt",
            """
            package com.acme.billing

            class Refund {
                fun issue() {}
            }
            """.trimIndent(),
        )
    }

    private fun placeholderOf(key: String): String =
        PlaceholderLedger.getInstance().snapshotOf(project).placeholders.getValue(key).placeholder
}

/** [text] with its control characters named, so a failure message can be read. */
private fun visible(text: String): String = text.replace("\b", "\\b").replace("\t", "\\t")

/**
 * A frame of the facade, one of the class in the same file, one of a class in a file with no facade,
 * and a `kotlinx.*` frame, which a trace with no library frame in it would not be.
 */
private val FACADE_TRACE = listOf(
    "java.lang.IllegalStateException: x",
    "\tat com.acme.billing.BillingKt.settle(Billing.kt:3)",
    "\tat com.acme.billing.Payment.pay(Billing.kt:6)",
    "\tat com.acme.billing.Refund.issue(Refund.kt:9)",
    "\tat kotlinx.coroutines.DispatchedTask.run(DispatchedTask.kt:108)",
).joinToString("\n")

/** Every marker row, as [COROUTINE_TRACE] carries it — the escapes written, never the bytes. */
private val MARKERS = listOf(
    "\tat _COROUTINE._BOUNDARY._(CoroutineDebugging.kt:46)",
    "\tat _COROUTINE._BOUNDARY._(CoroutineDebugging.kt:42)",
    "\tat _COROUTINE._CREATION._(CoroutineDebugging.kt:69)",
    "\tat \b\b\b(Coroutine boundary.\b(\b)",
    "\t(Coroutine creation stacktrace)",
)

/**
 * A synthetic coroutine trace over the fixture project: a state machine of a project class, the
 * boundary marker at two line numbers, `kotlinx.*` frames — a class, a facade, a multifile part and
 * a nested class — a legacy boundary in its backspace form, and a creation block headed by the
 * modern marker and by the legacy one's terminal rendering, with a project frame in it.
 */
private val COROUTINE_TRACE = listOf(
    "Exception in thread \"main\" java.lang.IllegalStateException: payout refused",
    "\tat com.acme.billing.Payment\$pay\$1.invokeSuspend(Billing.kt:6)",
    MARKERS[0],
    "\tat com.acme.billing.BillingKt.settle(Billing.kt:3)",
    MARKERS[1],
    "\tat kotlinx.coroutines.DispatchedTask.run(DispatchedTask.kt:108)",
    "\tat kotlinx.coroutines.scheduling.CoroutineScheduler\$Worker.run(CoroutineScheduler.kt:584)",
    MARKERS[3],
    "\tat kotlinx.coroutines.intrinsics.CancellableKt.startCoroutineCancellable(Cancellable.kt:30)",
    MARKERS[2],
    "\tat kotlinx.coroutines.BuildersKt__Builders_commonKt.launch(Builders.common.kt:56)",
    "\tat kotlinx.coroutines.BuildersKt.launch(Unknown Source)",
    MARKERS[4],
    "\tat com.acme.billing.BillingKt.settle(Billing.kt:3)",
).joinToString("\n")

/**
 * What is wrong with the fixture when the `kotlinx.*` class [name] resolved to [origin] — `null` for
 * nothing — or `null` when nothing is.
 */
internal fun complaintAboutTheCoroutinesLibrary(name: String, origin: SymbolOrigin?): String? = when (origin) {
    SymbolOrigin.LIBRARY -> null
    null -> "`$name` does not resolve, so kotlinx-coroutines is not attached to this fixture — or its metadata " +
        "is newer than the compiler reading it — and every kotlinx.* frame would render as Unknown."
    else -> "`$name` resolved to $origin, not to a library, so a kotlinx.* frame here is not being kept for the " +
        "reason the output claims."
}

private val COROUTINES_CLASSPATH: LightProjectDescriptor = KotlinCoroutinesClasspath()

/** The Kotlin fixture's classpath with `kotlinx-coroutines` on it. A class of its own: see [KotlinClasspath]. */
private class KotlinCoroutinesClasspath : KotlinClasspath() {

    override fun attachLibraries(model: ModifiableRootModel) {
        super.attachLibraries(model)
        attachJar(model, "kotlinx-coroutines", coroutinesJar())
    }
}

/** The coroutines jar the build resolved, or a failure that says the wiring is missing. */
private fun coroutinesJar(): String {
    val named = System.getProperty(COROUTINES_PROPERTY)
        ?: error(
            "-D$COROUTINES_PROPERTY is not set, so kotlinx-coroutines cannot be attached and every kotlinx.* " +
                "frame would resolve to nothing. The `test` task in plugin/build.gradle.kts sets it from the " +
                "`kotlinFixtureCoroutines` configuration."
        )
    check(File(named).isFile) { "$COROUTINES_PROPERTY names $named, which is not a file." }
    return named
}

private const val COROUTINES_PROPERTY = "snippetveil.kotlin.coroutinesJar"
