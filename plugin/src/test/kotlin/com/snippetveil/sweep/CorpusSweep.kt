package com.snippetveil.sweep

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaRecursiveElementWalkingVisitor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiLabeledStatement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.search.ProjectScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BareTestFixtureTestCase
import com.snippetveil.core.AnonymizationSettings
import com.snippetveil.core.LedgerSnapshot
import com.snippetveil.core.anonymize
import com.snippetveil.core.plus
import com.snippetveil.plugin.InternalLibrarySettings
import com.snippetveil.plugin.JavaPlanBuilder
import com.snippetveil.plugin.SnippetRequest
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * **The corpus sweep: real code in, findings out, and the code never moves.**
 *
 * Runs the anonymiser whole-file over every Java source in a real codebase and writes a triage list
 * of suspected leaks. It is run by a human, deliberately, and it is **never run in CI** — see
 * *Instrument, not test* below and `assertTheSweepIsNeverRunInCi` in the root `build.gradle.kts`.
 *
 * ### Why real code is load-bearing
 *
 * The committed fixture corpus is synthetic and stays that way. Synthetic fixtures contain only the
 * PSI shapes somebody already thought of, so they can only ever confirm the rules that exist.
 * **The entire value of real code is the shapes we did not think of** — and this is the only layer
 * in the project where a *missing* plan item is visible at all. See [LeakOracle] for why the check
 * is derived from the input rather than from the mapping, which is the difference between proving
 * that what the anonymiser did was done and proving that it did everything.
 *
 * ### Instrument, not test
 *
 * This cannot be green/red, and pretending otherwise is how it would die. The oracle is deliberately
 * blunt and false-positive-prone — it throws on genuine collisions with preserved library members —
 * so as a test it would be permanently red or permanently suppressed. As an instrument the
 * expectation is right: a human runs it, reads it, and what comes out is a **conclusion**. A new
 * rule, or a new *synthetic* fixture reproducing the shape it found.
 *
 * **A bug found here earns a synthetic fixture reproducing its shape, never the real code that
 * revealed it.** Running the anonymiser over a real codebase and committing the anonymised output as
 * the public fixture corpus is the obvious clever move and the worst idea available: it is exactly
 * circular, because if the anonymiser leaks then the leak is in a public repository, permanently, in
 * git history. The product's own failure mode, aimed at the repo.
 *
 * ### The hazard
 *
 * **The report contains the leak by construction.** It is a list of real identifiers from a real
 * proprietary codebase, and the natural instinct is to paste it into an issue to ask about it. It is
 * written **outside the repository tree entirely** — `.gitignore` stops `git add`, not a paste or a
 * screenshot, and a file being *inside* the repository is what makes those feel safe. Nothing but
 * counts and a path is printed to the console, for the same reason. See [sweepReportPath].
 *
 * ### Running it
 *
 * ```
 * ./gradlew corpusSweep -PsweepProject=/path/to/a/real/checkout
 * ```
 *
 * With no `-PsweepProject` the task is **skipped, not failed**, so public CI cannot demand it and a
 * contributor without a codebase to point it at is never blocked. `-PsweepReportDir` moves the
 * report; the default is `~/snippetveil-sweep`, and any location inside this repository or inside
 * the swept project is refused outright.
 */
class CorpusSweep : BareTestFixtureTestCase() {

    @Test
    fun `sweep a real codebase`() {
        val target = System.getProperty(TARGET_PROPERTY)

        // Skipped, not failed. The Gradle task skips too, so this is the case where somebody ran the
        // class directly; both say the same thing, and neither says "broken".
        assumeTrue(
            "No -P$TARGET_PROPERTY was given, so there is no codebase to sweep.",
            !target.isNullOrBlank(),
        )

        // The rules prove they can fail before anything is reported, because the only thing worse
        // than a missing finding is a report that says "clean" from an oracle that had stopped
        // looking. See LeakOracle.proveTheRulesCanFail.
        say("Oracle self-proof: ${LeakOracle.proveTheRulesCanFail()} rule(s) demonstrated red and green.")

        val targetPath = Paths.get(target).toAbsolutePath().normalize()
        check(Files.isDirectory(targetPath)) { "$targetPath is not a directory, so there is no project to open." }

        val report = sweepReportPath(
            reportDirectory = Paths.get(System.getProperty(REPORT_DIRECTORY_PROPERTY) ?: defaultReportDirectory()),
            fileName = "snippetveil-sweep-${LocalDateTime.now().format(STAMP)}.txt",
            forbidden = buildMap {
                System.getProperty(REPOSITORY_PROPERTY)?.let { put("the SnippetVeil repository", Paths.get(it)) }
                put("the swept project", targetPath)
            },
        )

        say("Opening $targetPath …")
        val project = PlatformTestUtil.loadAndOpenProject(targetPath, testRootDisposable)
        attachTheRunningJdkUnderTheNameTheProjectExpects(project)

        val rendered = sweep(project, targetPath)

        Files.createDirectories(report.parent)
        Files.writeString(report, rendered)
        say("")
        say("The report is at: $report")
        say("It lists real identifiers from $targetPath. Do not paste it anywhere.")
    }

    /** Everything between an open project and the rendered report, kept out of the test method so that it reads as the sequence it is. */
    private fun sweep(project: Project, targetPath: Path): String {
        val files = javaSourcesOf(project, targetPath)
        check(files.isNotEmpty()) { "No Java source files were found under $targetPath. Nothing would be swept." }
        say("${files.size} Java source file(s) in project content.")

        val declarations = smartly(project) { declarationsIn(project, files) }
        val sharedWithLibraries = smartly(project) { namesTheLibrariesAlsoDeclare(project, declarations.names) }
        val oracle = LeakOracle.over(
            declaredInProjectSources = declarations.names,
            declaredByLibraries = sharedWithLibraries,
            topLevelPackageSegments = declarations.topLevelPackageSegments,
        )
        val universe = UniverseSize(
            owned = oracle.size,
            declared = declarations.names.size,
            sharedWithLibraries = sharedWithLibraries.size,
            topLevelSegments = declarations.topLevelPackageSegments.size,
        )
        say("Name universe: ${universe.owned} project-owned of ${universe.declared} declared.")

        // The product's own settings, read rather than assumed: the internal-library prefix list is
        // the one thing a real checkout can have configured that changes what the output contains.
        val settings = AnonymizationSettings(internalLibraries = InternalLibrarySettings.of(project).policy)

        // Carried across files, which is what a real session does: the ledger is what makes a
        // placeholder mean the same thing in the second paste as in the first, and a sweep that
        // reset it per file would be exercising a mode the product does not have.
        var ledger = LedgerSnapshot.EMPTY
        val findings = mutableListOf<FileFindings>()
        val failures = mutableListOf<SweepFailure>()

        files.forEachIndexed { index, file ->
            if (index > 0 && index % 200 == 0) say("  … $index/${files.size}")
            val where = relativeTo(targetPath, file)

            // **A throw is a finding, not an outage.** It is the shape nobody thought of, arriving
            // as a stack trace instead of as a surviving name — and losing a whole sweep of a real
            // codebase to one PSI edge case would make this an instrument nobody finishes running.
            // Recorded and carried on with; the ledger is untouched, because a throw commits nothing.
            val text = try {
                smartly(project) {
                    val psi = PsiManager.getInstance(project).findFile(file) as? PsiJavaFile ?: return@smartly null
                    // No selection at all is the whole file, which is what the ticket asks for and
                    // what the production path already means by an empty range list.
                    val result = anonymize(JavaPlanBuilder.build(SnippetRequest(project, psi, emptyList())), settings, ledger)
                    ledger += result.delta
                    result.text
                }
            } catch (failure: Throwable) {
                failures += SweepFailure(where, "${failure::class.java.name}: ${failure.message}")
                return@forEachIndexed
            } ?: return@forEachIndexed

            val survivors = oracle.survivorsIn(text)
            if (survivors.isNotEmpty()) findings += FileFindings(where, survivors)
        }

        // Counts only. The names are the leak, and the console is the easiest thing in the world to
        // copy out of — so is an exception message, which can name the symbol it choked on.
        say("Files with findings: ${findings.size}. Distinct names surviving: ${findings.sumOf { it.survivors.size }}.")
        say("Files that could not be swept: ${failures.size}.")

        return SweepReport(
            startedAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            targetProject = targetPath.toString(),
            filesSwept = files.size,
            universe = universe,
            findings = findings,
            failures = failures,
        ).render()
    }

    /**
     * Every Java file in the target's **own source content** — which is what the oracle's universe is
     * built from and what the sweep anonymizes, so the two can never be about different files.
     *
     * **Found on disk rather than by walking the project index**, because the sweep is pointed at a
     * live working tree: the VFS serves a directory listing it cached the last time something looked,
     * and a file written since is invisible to `iterateContent`. Reporting on a checkout as it stood
     * an hour ago is the one kind of wrong answer this instrument must not give. What the *project*
     * says is still authoritative for whether a file counts — hence the source-content filter, which
     * is what keeps build output, generated sources and `.git` out of the universe.
     */
    private fun javaSourcesOf(project: Project, targetPath: Path): List<VirtualFile> {
        // The disk walk and the refresh happen **outside a read action**: a synchronous VFS refresh
        // under the read lock deadlocks, and the platform says so out loud. Only the question of
        // whether a file is source content needs the lock, so only that part takes it.
        val fileSystem = LocalFileSystem.getInstance()
        val onDisk = Files.walk(targetPath).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".java") }
                .filter { path -> generateSequence(path.parent) { it.parent }.none { it.fileName?.toString() == ".git" } }
                .toList()
        }
        // Asked of the VFS one path at a time. `refreshAndFindFileByNioFile` refreshes exactly the
        // path it is given, so a file written since the IDE last looked at its directory is found —
        // where a recursive refresh of the root is not enough, because a directory whose listing is
        // already cached is not necessarily re-read.
        val known = onDisk.mapNotNull { fileSystem.refreshAndFindFileByNioFile(it) }

        val index = ProjectRootManager.getInstance(project).fileIndex
        return smartly(project) {
            known.filter { it.fileType == JavaFileType.INSTANCE && index.isInSourceContent(it) }.sortedBy { it.path }
        }
    }

    /**
     * **The identifier universe, built without the anonymiser's own walk.**
     *
     * A second, independent reading of the same files: this one visits **declarations** and asks each
     * for its name, where [JavaPlanBuilder] walks **references** and resolves them. Nothing here
     * consults a plan, a mapping or a result, which is the property the whole instrument rests on —
     * see [LeakOracle.over].
     *
     * Over-inclusive on purpose. Every [PsiNameIdentifierOwner] is a declaration in Java's grammar —
     * classes, methods, fields, parameters, locals, type parameters, record components — and a label
     * is added explicitly rather than relied upon, because whether a labelled statement implements
     * that interface is a platform detail and not a thing this file should have an opinion about. A
     * name collected here that the anonymiser was never going to touch costs a human a minute; a name
     * missed costs the product its core promise.
     */
    private fun declarationsIn(project: Project, files: List<VirtualFile>): Declarations {
        val names = sortedSetOf<String>()
        val topLevelSegments = sortedSetOf<String>()
        val manager = PsiManager.getInstance(project)

        files.forEach { file ->
            val psi = manager.findFile(file) as? PsiJavaFile ?: return@forEach

            // A package statement declares every segment of its own name, and the first segment is
            // the one the engine passes through by a positional rule of its own.
            psi.packageName.takeIf { it.isNotEmpty() }?.split('.')?.let { segments ->
                names += segments
                topLevelSegments += segments.first()
            }

            psi.accept(object : JavaRecursiveElementWalkingVisitor() {
                override fun visitElement(element: PsiElement) {
                    if (element is PsiNameIdentifierOwner) element.name?.let { names += it }
                    if (element is PsiLabeledStatement) names += element.labelIdentifier.text
                    super.visitElement(element)
                }
            })
        }
        return Declarations(names, topLevelSegments)
    }

    /**
     * The names in [candidates] that the JDK or a library also declares — the oracle's one structural
     * blind spot, measured rather than guessed at.
     *
     * Asked of the short-names index over the libraries scope, which is a different question from
     * anything the anonymiser asks and answers it without resolving a single reference. A project
     * class called `Builder` is subtracted here, and a genuine leak of a project class called
     * `Builder` is therefore invisible to this instrument. That is stated in the report.
     */
    private fun namesTheLibrariesAlsoDeclare(project: Project, candidates: Set<String>): Set<String> {
        val cache = PsiShortNamesCache.getInstance(project)
        val libraries = ProjectScope.getLibrariesScope(project)
        return candidates.filterTo(sortedSetOf()) { name ->
            cache.getClassesByName(name, libraries).isNotEmpty() ||
                cache.getFieldsByName(name, libraries).isNotEmpty() ||
                cache.getMethodsByName(name, libraries).isNotEmpty()
        }
    }

    /**
     * **The running JDK, attached to the application's SDK table under the name the project already
     * asks for — and never to the target project itself.**
     *
     * A real `.idea` names its SDK by a name the IDE's own configuration resolves, and this process
     * has no such configuration — so without this every `java.lang` reference resolves to nothing.
     * That is not a leak (an unresolved name fails closed and is anonymized) but it is a sweep of a
     * codebase the anonymiser could not see the JDK in, which is not the codebase anybody runs.
     *
     * The SDK goes into [ProjectJdkTable], which is application state, under the name the project
     * already asks for. **The target project is never written to** — it is somebody's real checkout,
     * and an instrument that dirtied `.idea/misc.xml` while reading would be one nobody runs twice.
     */
    private fun attachTheRunningJdkUnderTheNameTheProjectExpects(project: Project) {
        val wanted = ProjectRootManager.getInstance(project).projectSdkName ?: return
        if (ProjectJdkTable.getInstance().findJdk(wanted) != null) return

        WriteAction.runAndWait<RuntimeException> {
            val jdk = JavaSdk.getInstance().createJdk(wanted, System.getProperty("java.home"), false)
            ProjectJdkTable.getInstance().addJdk(jdk, testRootDisposable)
        }
        say("Attached the running JDK as '$wanted'; the project's own SDK is not configured in this process.")
    }

    /**
     * [work], in a read action, once the indexes are ready — the same conditions the production path
     * builds a plan under, which is `ReadAction.nonBlocking { … }.inSmartMode(project)`.
     */
    private fun <T> smartly(project: Project, work: () -> T): T =
        DumbService.getInstance(project).runReadActionInSmartMode<T>(work)

    private fun relativeTo(root: Path, file: VirtualFile): String =
        runCatching { root.relativize(Paths.get(file.path)).toString() }.getOrDefault(file.path)

    /** Progress, and never a name. See the hazard note on this class. */
    private fun say(line: String) = println("[sweep] $line")

    private class Declarations(val names: Set<String>, val topLevelPackageSegments: Set<String>)

    private companion object {

        /** Set by the `corpusSweep` Gradle task from `-PsweepProject`. */
        const val TARGET_PROPERTY = "snippetveil.sweep.project"

        /** Set from `-PsweepReportDir`, and defaulted below. */
        const val REPORT_DIRECTORY_PROPERTY = "snippetveil.sweep.reportDirectory"

        /**
         * This repository's root, handed in by the Gradle task rather than worked out here — a
         * process that guessed at where it was running from could guess the report into the tree it
         * is supposed to stay out of.
         */
        const val REPOSITORY_PROPERTY = "snippetveil.sweep.repository"

        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        /**
         * `~/snippetveil-sweep`: outside every checkout, and somewhere a human can find without being
         * told twice. Deliberately not a temp directory, which is both easy to lose and easy to
         * forget is still there.
         */
        fun defaultReportDirectory(): String = System.getProperty("user.home") + "/snippetveil-sweep"
    }
}
