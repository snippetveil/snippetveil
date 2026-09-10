package com.snippetveil.sweep

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.snippetveil.core.AnonymizationSettings
import com.snippetveil.core.LedgerSnapshot
import com.snippetveil.core.anonymize
import com.snippetveil.core.plus
import com.snippetveil.plugin.DispatchingPlanBuilder
import com.snippetveil.plugin.GateVerdict
import com.snippetveil.plugin.JavaPlanBuilder
import com.snippetveil.plugin.PlanBuilder
import com.snippetveil.plugin.SnippetRequest
import com.snippetveil.plugin.gate
import org.jetbrains.kotlin.idea.KotlinFileType

/**
 * **One pass of the anonymiser over a project's own sources, whole-file, through one ledger** — the
 * sweep's loop, apart from the checkout it is pointed at, so that what it does to a file can be
 * asserted over a fixture. [CorpusSweep] is everything around it: finding the files, reading the
 * universe, writing the report.
 *
 * **Not part of the universe's construction**, and deliberately outside the classes
 * `LeakUniverseIndependenceTest` imports: this runs the anonymiser by design, and the oracle it is
 * handed is what was built without it.
 *
 * @param oracle the leak check every output is read against
 * @param settings the product's own settings, as the target project has them configured
 * @param route the plan builder each file is handed to. [shippedRoute] everywhere but a fixture that
 *   manufactures the missing plan item the sweep exists to see.
 */
internal class SweepPass(
    private val project: Project,
    private val oracle: LeakOracle,
    private val settings: AnonymizationSettings,
    private val route: (PsiFile) -> PlanBuilder = ::shippedRoute,
) {

    /**
     * [files], each anonymised whole-file and its output read by the oracle, in the order given.
     *
     * @param files Java and Kotlin source files, and nothing else — the two languages the report
     *   counts, so a third is refused rather than counted as either
     * @param pathOf how a file is named in the report
     * @param progress told how many files have been swept, after each one
     */
    fun over(files: List<VirtualFile>, pathOf: (VirtualFile) -> String, progress: (Int) -> Unit = {}): Swept {
        val strangers = files.filterNot { it.fileType == JavaFileType.INSTANCE || it.fileType == KotlinFileType.INSTANCE }
        require(strangers.isEmpty()) { "${strangers.size} file(s) handed to the sweep are neither Java nor Kotlin." }

        // Carried across files, which is what a real session does: the ledger is what makes a
        // placeholder mean the same thing in the second paste as in the first, and a sweep that
        // reset it per file would be exercising a mode the product does not have. **One ledger for
        // both languages**, because a Kotlin declaration and a Java reference to it share a
        // placeholder in a real session too.
        var ledger = LedgerSnapshot.EMPTY
        val findings = mutableListOf<FileFindings>()
        val failures = mutableListOf<SweepFailure>()

        files.forEachIndexed { index, file ->
            // **A throw is a finding, not an outage.** It is the shape nobody thought of, arriving
            // as a stack trace instead of as a surviving name — and losing a whole sweep of a real
            // codebase to one PSI edge case would make this an instrument nobody finishes running.
            // Recorded and carried on with; the ledger is untouched, because a throw commits nothing.
            //
            // **The scan is inside this too, not only the anonymisation.** Everything the loop does
            // to one file is in here, so that whatever a file can do to this instrument costs that
            // file and no other. A guard drawn tighter than the unit of work is a guard with an
            // outage on the other side of it.
            try {
                val text = smartly(project) {
                    val psi = PsiManager.getInstance(project).findFile(file)
                        ?: error("The IDE handed over no PSI for this file, so it could not be anonymised.")
                    // No selection at all is the whole file, which is what the production path
                    // already means by an empty range list.
                    val result = anonymize(route(psi).build(SnippetRequest(project, psi, emptyList())), settings, ledger)
                    ledger += result.delta
                    result.text
                }

                val survivors = oracle.survivorsIn(text)
                if (survivors.isNotEmpty()) findings += FileFindings(pathOf(file), survivors)
            } catch (failure: Throwable) {
                failures += SweepFailure(pathOf(file), "${failure::class.java.name}: ${failure.message}")
            }
            progress(index + 1)
        }

        val java = files.count { it.fileType == JavaFileType.INSTANCE }
        return Swept(SweptFiles(java = java, kotlin = files.size - java), findings, failures, ledger)
    }
}

/**
 * **Refuses the whole sweep if any Kotlin file in [files] cannot be anonymised in this IDE** — asked of
 * the gate a user's `Copy Anonymized` asks, so that *cannot be anonymised here* means exactly what it
 * means to a user.
 *
 * In a sweep that is a K1 session — the Kotlin plugin's default on the floor platform, which every
 * default run is on — or a Kotlin plugin switched off. **Refused rather than swept around**: a report
 * over the Java half would say nothing of a Kotlin half nobody anonymised, and silence about it is the
 * one answer the coverage habit rules out. A target with no Kotlin in it has nothing to refuse, so a
 * Java codebase still sweeps on the floor.
 *
 * Counts only, never a path: a file name is a real identifier too.
 */
internal fun refuseKotlinThatCannotBeSwept(project: Project, files: List<VirtualFile>) {
    val refused = smartly(project) {
        files.filter { it.fileType == KotlinFileType.INSTANCE }
            .map { gate(PsiManager.getInstance(project).findFile(it)) }
            .filter { it != GateVerdict.Offer }
    }
    check(refused.isEmpty()) {
        val causes = refused.map { (it as? GateVerdict.Refuse)?.cause ?: "no PSI" }.distinct()
        "${refused.size} Kotlin file(s) in this project cannot be anonymised in this IDE ($causes): " +
            "SnippetVeil's Kotlin support is not registered, which means the Kotlin plugin is running in " +
            "K1 — its default on the floor platform — or is switched off. Sweeping the Java half alone " +
            "would report on a Kotlin half nobody anonymised, so nothing is swept. Run it where the " +
            "Kotlin plugin runs K2: ./gradlew corpusSweep -PplatformProfile=k2 -PsweepProject=…"
    }
}

/**
 * **The route a user's `Copy Anonymized` takes**, file by file.
 *
 * Java to its builder directly, as the sweep always has; Kotlin through the registered support, which
 * is the route that ships — registration included, so a Kotlin half the IDE never loaded is measured
 * rather than stepped around.
 */
internal fun shippedRoute(file: PsiFile): PlanBuilder = if (file is PsiJavaFile) JavaPlanBuilder else DispatchingPlanBuilder

/**
 * What one pass made of its files.
 *
 * @param ledger the ledger the pass ended with — every placeholder minted across every file, which is
 *   what a real session would be holding afterwards
 */
internal class Swept(
    val files: SweptFiles,
    val findings: List<FileFindings>,
    val failures: List<SweepFailure>,
    val ledger: LedgerSnapshot,
)

/**
 * [work], in a read action, once the indexes are ready — the same conditions the production path
 * builds a plan under, which is `ReadAction.nonBlocking { … }.inSmartMode(project)`.
 */
internal fun <T> smartly(project: Project, work: () -> T): T =
    DumbService.getInstance(project).runReadActionInSmartMode<T>(work)
