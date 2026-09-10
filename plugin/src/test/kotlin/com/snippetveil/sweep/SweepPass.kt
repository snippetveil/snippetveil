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
import com.snippetveil.plugin.JavaPlanBuilder
import com.snippetveil.plugin.PlanBuilder
import com.snippetveil.plugin.SnippetRequest
import org.jetbrains.kotlin.idea.KotlinFileType

/**
 * **One pass of the anonymiser over a project's own sources, whole-file, through one ledger** — the
 * sweep's loop, apart from the checkout it is pointed at, so that what it does to a file can be
 * asserted over a fixture. [CorpusSweep] is everything around it: finding the files, reading the
 * universe, writing the report.
 *
 * **Not part of the universe's construction**, and deliberately outside the classes
 * `LeakUniverseIndependenceTest` imports, which that test asserts: this runs the anonymiser by design,
 * and the oracle it is handed is what was built without it.
 *
 * @param oracle the leak check every output is read against
 * @param settings the product's own settings, as the target project has them configured
 * @param route the plan builder each file is handed to. [sweepRoute] everywhere but a fixture that
 *   manufactures the missing plan item the sweep exists to see.
 */
internal class SweepPass(
    private val project: Project,
    private val oracle: LeakOracle,
    private val settings: AnonymizationSettings,
    private val route: (PsiFile) -> PlanBuilder = ::sweepRoute,
) {

    /**
     * [files], each anonymised whole-file and its output read by the oracle, in the order given.
     *
     * @param files the Java and Kotlin source files to sweep, which is what [CorpusSweep] finds
     * @param pathOf how a file is named in the report
     * @param progress told how many files have been swept, after each one
     */
    fun over(files: List<VirtualFile>, pathOf: (VirtualFile) -> String, progress: (Int) -> Unit = {}): Swept {
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

        val counts = SweptCounts(
            java = files.count { it.fileType == JavaFileType.INSTANCE },
            kotlin = files.count { it.fileType == KotlinFileType.INSTANCE },
        )
        return Swept(counts, findings, failures, ledger)
    }
}

/**
 * **Which builder the sweep hands a file to.**
 *
 * Kotlin through the registered support — the route a user's `Copy Anonymized` takes, registration
 * included, so a Kotlin half the IDE never loaded is measured rather than stepped around. Java to its
 * builder directly, as the sweep always has.
 */
internal fun sweepRoute(file: PsiFile): PlanBuilder = if (file is PsiJavaFile) JavaPlanBuilder else DispatchingPlanBuilder

/**
 * What one pass made of its files.
 *
 * @param ledger the ledger the pass ended with — every placeholder minted across every file, which is
 *   what a real session would be holding afterwards
 */
internal class Swept(
    val counts: SweptCounts,
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
