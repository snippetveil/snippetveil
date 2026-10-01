package com.snippetveil.sweep

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.ProjectScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.util.PsiUtilCore
import com.snippetveil.core.AnonymizationSettings
import com.snippetveil.core.LedgerDelta
import com.snippetveil.core.LedgerSnapshot
import com.snippetveil.core.SnippetPlan
import com.snippetveil.core.StackTrace
import com.snippetveil.core.SymbolOccurrence
import com.snippetveil.core.SymbolOrigin
import com.snippetveil.core.TraceFrame
import com.snippetveil.core.TraceReading
import com.snippetveil.core.anonymize
import com.snippetveil.core.parseTrace
import com.snippetveil.core.plus
import com.snippetveil.plugin.TracePlanBuilder

/**
 * **One pass of `Anonymize Stack Trace…`'s core path over a file of traces, through one ledger** — the
 * trace sweep's loop, apart from the file and the project it is pointed at, so that what it counts and
 * what it finds can be asserted over synthetic input. [TraceSweep] is everything around it.
 *
 * The core path is the action's, minus the clipboard and the preview: [parseTrace] decides whether a
 * trace is one, [TracePlanBuilder] resolves its names against the project, and the engine renders it
 * under the product's own settings. Every trace's output is then read by the trace leak oracle — see
 * [LeakOracle.overTrace] — over **that trace's own sub-tokens**, less what the JDK and the libraries
 * declare.
 *
 * @param settings the product's settings, as the project has them configured
 * @param route the plan builder each trace is handed to. [TracePlanBuilder] everywhere but a fixture
 *   that manufactures the missing plan item the sweep exists to see.
 */
internal class TracePass(
    private val project: Project,
    private val settings: AnonymizationSettings,
    private val route: (Project, StackTrace) -> SnippetPlan = TracePlanBuilder::build,
) {

    /**
     * [traces], each refused or anonymized and read by the oracle, in file order.
     *
     * @param progress told how many traces have been read, after each one
     */
    fun over(traces: List<TraceEntry>, progress: (Int) -> Unit = {}): TraceSwept {
        // Carried across traces, which is what a session does: a class two traces name takes one
        // placeholder in both, and a sweep that reset it per trace would exercise a mode the product
        // does not have.
        var ledger = LedgerSnapshot.EMPTY
        val refused = linkedMapOf<TraceRefusal, MutableList<Int>>()
        var frames = FrameCounts()
        val findings = mutableListOf<TraceFindings>()
        val failures = mutableListOf<TraceFailure>()

        traces.forEachIndexed { index, entry ->
            val ordinal = index + 1
            when (val reading = parseTrace(entry.text)) {
                TraceReading.NotATrace -> refused.getOrPut(TraceRefusal.NOT_A_TRACE, ::mutableListOf) += entry.line
                TraceReading.CoroutineDump -> refused.getOrPut(TraceRefusal.COROUTINE_DUMP, ::mutableListOf) += entry.line
                is TraceReading.Read -> try {
                    // **A throw is a finding, not an outage**, exactly as in the source half: it is
                    // the shape nobody thought of, and it costs this trace and no other. The ledger is
                    // untouched by it, because a throw commits nothing.
                    val tokens = LeakOracle.subTokensOf(entry.text)
                    val read = smartly(project) {
                        val plan = route(project, reading.trace)
                        val result = anonymize(plan, settings, ledger)
                        Anonymized(
                            frames = reading.trace.frames.map { classificationOf(plan, it) }.fold(FrameCounts(), FrameCounts::plus),
                            output = result.text,
                            delta = result.delta,
                            declared = declaredByTheJdkAndLibraries(project, reading.trace, tokens),
                        )
                    }
                    ledger += read.delta
                    frames += read.frames

                    // A trace whose every sub-token the JDK or a library declares has nothing to look
                    // for, and the oracle refuses to be built over nothing — rightly, since an empty
                    // universe reports every output clean. It is read and counted, and has no hits.
                    if (tokens.any { it !in read.declared }) {
                        val survivors = LeakOracle.overTrace(entry.text, read.declared).survivorsIn(read.output)
                        if (survivors.isNotEmpty()) findings += TraceFindings(ordinal, entry.line, survivors)
                    }
                } catch (failure: Throwable) {
                    failures += TraceFailure(ordinal, entry.line, "${failure::class.java.name}: ${failure.message}")
                }
            }
            progress(ordinal)
        }

        return TraceSwept(traces.size, refused, frames, findings, failures)
    }

    /** What the core path made of one trace, carried out of the read action. */
    private class Anonymized(val frames: FrameCounts, val output: String, val delta: LedgerDelta, val declared: Set<String>)
}

/**
 * **How [frame]'s class was classified**, read off the plan the core path built: a remainder anywhere
 * in the class name is a partial `Unknown`, and otherwise the class itself — the last name in it — is
 * the project's, a library's or the JDK's, or `Unknown`.
 */
internal fun classificationOf(plan: SnippetPlan, frame: TraceFrame): FrameCounts {
    val inName = plan.occurrences.filterIsInstance<SymbolOccurrence>()
        .filter { it.start >= frame.type.start && it.end <= frame.type.end }
    if (inName.any { it.symbol.remainder }) return FrameCounts(partialUnknown = 1)
    return when (inName.maxByOrNull { it.start }?.symbol?.origin) {
        SymbolOrigin.IN_CONTENT -> FrameCounts(projectOwned = 1)
        SymbolOrigin.LIBRARY, SymbolOrigin.JDK -> FrameCounts(libraryOwned = 1)
        SymbolOrigin.UNRESOLVED, null -> FrameCounts(unknown = 1)
    }
}

/**
 * **The oracle's one subtraction for a trace**: the sub-tokens of [trace] the JDK or a library declares.
 *
 * Three sources, and each of them only ever subtracts — so a question answered too narrowly costs a
 * false positive and never a miss:
 *
 *  - **Every header and frame class that resolves into a library or the JDK**, by the longest prefix
 *    of its binary name that does, contributes its package segments and class names, and the frame's
 *    method where that class declares it. That is where `java`, `lang` and `junit` come from, which
 *    no short-name index holds.
 *  - **Every sub-token a library declares a class, method or field by**, asked of the short-names index
 *    over the libraries scope exactly as the source half asks it — `invokeSuspend` in a frame of the
 *    project's own coroutine, which the language fixed and the frame's own class does not declare.
 *  - **The words the JVM and the coroutine library write into a trace themselves** — see
 *    [TRACE_PRINTER_WORDS].
 *
 * Asked of the index directly rather than read off the plan, so that the universe stays derived from
 * the input and never from what the anonymiser's walk concluded.
 */
private fun declaredByTheJdkAndLibraries(project: Project, trace: StackTrace, tokens: Set<String>): Set<String> {
    val facade = JavaPsiFacade.getInstance(project)
    val scope = GlobalSearchScope.allScope(project)
    val index = ProjectFileIndex.getInstance(project)

    fun libraryClass(binaryName: String): PsiClass? =
        (facade.findClass(binaryName, scope) ?: facade.findClass(binaryName.replace('$', '.'), scope))
            ?.takeIf { found -> PsiUtilCore.getVirtualFile(found)?.let { !index.isInContent(it) && index.isInLibrary(it) } == true }

    val declared = TRACE_PRINTER_WORDS.toMutableSet()
    val named = trace.exceptions.map { it to null } + trace.frames.map { it.type to it.method }
    for ((type, method) in named) {
        val written = type.text
        val candidates = listOf(written) + written.indices.reversed().filter { written[it] == '$' && it > 0 }.map { written.substring(0, it) }
        for (candidate in candidates) {
            val found = libraryClass(candidate) ?: continue
            declared += candidate.split('.', '$')
            if (candidate == written && method != null && found.findMethodsByName(method.text, true).isNotEmpty()) declared += method.text
            break
        }
    }

    val cache = PsiShortNamesCache.getInstance(project)
    val libraries = ProjectScope.getLibrariesScope(project)
    tokens.filterTo(declared) {
        cache.getClassesByName(it, libraries).isNotEmpty() ||
            cache.getMethodsByName(it, libraries).isNotEmpty() ||
            cache.getFieldsByName(it, libraries).isNotEmpty()
    }
    return declared
}

/**
 * **The words a trace's printers write themselves**, and nobody's names: the JDK's `Exception in
 * thread`, `Caused by:`, `Suppressed:`, `at`, `... N more`, `(Native Method)` and `(Unknown Source)`;
 * the JVM's `<init>` and `<clinit>`; and the coroutine library's markers. They are in every trace and
 * in every output, and the JDK and the library declare them in the only sense a printed word can be
 * declared — which is why [LeakOracle.overTrace] names them among what it subtracts.
 */
private val TRACE_PRINTER_WORDS = setOf(
    "Exception", "in", "thread", "Caused", "by", "Suppressed", "at", "more", "Native", "Method", "Unknown", "Source",
    "init", "clinit",
    "_COROUTINE", "_BOUNDARY", "_CREATION", "_", "CoroutineDebugging", "kt", "Coroutine", "boundary", "creation", "stacktrace",
)
