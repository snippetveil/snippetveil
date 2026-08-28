package com.snippetveil.plugin

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.snippetveil.core.AnonymizationSettings
import com.snippetveil.core.LedgerSnapshot
import com.snippetveil.core.SnippetPlan
import com.snippetveil.core.SymbolOccurrence
import java.io.File

/**
 * A fixture whose classpath is the one thing these tests cannot do without.
 *
 * **The bundled mock JDK is not on this build's test classpath.** The IntelliJ test framework ships
 * a stub JDK for tests that need `java.lang.String` to exist and little else, resolved from a source
 * checkout of the platform that this project does not have. With no SDK attached, every reference in
 * every fixture resolves to `null` — and an assertion about *what* a name was classified as cannot
 * tell that apart from the rules working. That failure mode is silent, total, and worth a base
 * class: unresolved now fails closed, so the whole suite would go green over a plan that had
 * classified the JDK, every library and all of the user's own code as one undifferentiated
 * `Unknown`.
 *
 * So the running JDK is attached with [JavaSdk.createJdk], and a **real jar** with
 * [PsiTestUtil.addLibrary] — a genuine third-party library rather than a stub, because
 * `LIBRARY`-origin classification is itself under test and a fake library would only prove that the
 * fake was classified.
 *
 * [assertTheHarnessResolves] is called by every test that depends on resolution, for the same reason
 * every trust check in this repository asserts its own coverage.
 */
abstract class JavaSnippetTestCase : LightJavaCodeInsightFixtureTestCase() {

    override fun getProjectDescriptor(): LightProjectDescriptor = REAL_CLASSPATH

    /**
     * A file naming one project class, one JDK class and one library class, and the assertion that
     * the fixture can still tell the three apart. Without it every assertion in this package would
     * hold vacuously the day the SDK stops being attached.
     */
    protected fun assertTheHarnessResolves() {
        myFixture.addFileToProject(HARNESS_PROBE_PATH, "package com.acme.probe; public class Owned {}")
        val probe = myFixture.addFileToProject(
            "Harness.java",
            """
            class Harness {
                com.acme.probe.Owned project;
                java.lang.String jdk;
                org.junit.Test library;
            }
            """.trimIndent(),
        )

        for (name in listOf("com.acme.probe.Owned", "java.lang.String", "org.junit.Test")) {
            val offset = probe.text.indexOf(name) + name.lastIndexOf('.') + 1
            val resolved = probe.findReferenceAt(offset)?.resolve()
            assertNotNull("$name did not resolve; the fixture has no usable classpath", resolved)
        }
    }

    /**
     * A class in [packageName], which is what puts that package in **project content**.
     *
     * Not decoration, and not always a class the snippet names: a `PsiPackage` is declared in no
     * file, so its origin is read off the directories behind it. A fixture that never writes a file
     * into `com/acme/billing` has no such directory, the package classifies as nobody's, and an
     * assertion about how project packages rename would be an assertion about the fixture.
     */
    protected fun addClassInPackage(packageName: String, className: String) {
        myFixture.addFileToProject(
            packageName.replace('.', '/') + "/" + className + ".java",
            "package $packageName; public class $className {}",
        )
    }

    /**
     * Runs the action the way the IDE would — `update` first, then `actionPerformed` only if the
     * presentation came back enabled — and waits for the background analysis to land.
     *
     * **The balloons from any earlier invocation are dropped first**, which is what lets a test
     * invoke the action twice: [awaitBackgroundWork] waits for a balloon to appear, and one already
     * sitting in the list would satisfy that wait before the second analysis had even started.
     * Dropping them here rather than counting them is the version that cannot race — the platform is
     * free to deliver the first balloon before or after `testAction` returns, so any count taken
     * afterwards is a guess.
     *
     * @return the presentation `update` produced, which is what the Java-file gate is asserted on
     */
    protected fun invokeCopyAnonymized(action: CopyAnonymizedAction = CopyAnonymizedAction()): Presentation {
        raised.clear()
        return myFixture.testAction(action).also { if (it.isEnabled) awaitBackgroundWork() }
    }

    /**
     * **De-anonymize Clipboard**, over a stand-in for the clipboard, and **without waiting for
     * anything**: the reversal is two map lookups per word with no PSI and no index behind it, so it
     * runs to completion on the EDT before `testAction` returns. There is no background thread here
     * to race.
     *
     * The balloons from any earlier invocation are dropped first, for the reason
     * [invokeCopyAnonymized] drops them: a test that copies and then reverses would otherwise assert
     * against the copy's balloon.
     *
     * @return the presentation `update` produced, which is what *available on every file type* is
     *   asserted on
     */
    internal fun invokeDeanonymize(clipboard: Clipboard): Presentation {
        raised.clear()
        return myFixture.testAction(DeanonymizeClipboardAction(clipboard))
    }

    /**
     * **De-anonymize Clipboard and Paste**, over the same stand-in, and without waiting for anything
     * for the reason [invokeDeanonymize] does not: the reversal is map lookups and the insert is one
     * write command, both on the EDT before `testAction` returns.
     *
     * It goes through `testAction` rather than calling `actionPerformed` directly **because the gate
     * is half the behaviour here**. This action is the one in the submenu that can be disabled, and
     * a helper that skipped `update` would let a test assert an insert the IDE would never have
     * performed.
     *
     * @return the presentation `update` produced, which is what *greyed out without a writable
     *   editor* is asserted on
     */
    internal fun invokeDeanonymizeAndPaste(clipboard: Clipboard): Presentation {
        raised.clear()
        return myFixture.testAction(DeanonymizeClipboardAndPasteAction(clipboard))
    }

    /**
     * The preview action, over a stand-in for the dialog — and **without waiting for anything**,
     * because what there is to wait for differs by case: a confirmed preview raises a balloon and a
     * cancelled one raises nothing at all. The caller says which.
     */
    internal fun invokeWithPreview(plans: PlanBuilder = JavaPlanBuilder, previews: Previews): Presentation {
        raised.clear()
        return myFixture.testAction(AnonymizeWithPreviewAction(plans, previews))
    }

    /**
     * Drains the pooled thread the analysis runs on and the event queue its result comes back
     * through. The action returns before either has happened, so an assertion made without this
     * would be racing a thread it cannot see.
     *
     * A test that invokes the action twice goes through [invokeCopyAnonymized], which drops the
     * earlier balloons so that this wait is about the invocation in hand.
     */
    protected fun awaitBackgroundWork() = awaitEvents(
        "The analysis produced no notification within 60s; nothing came back from the background thread.",
    ) { notifications.isNotEmpty() }

    /**
     * Pumps the event queue until [until] holds, because the analysis runs on a background thread and
     * comes back through `invokeLater`.
     *
     * A condition rather than only the balloon: an invocation the user cancelled in the preview
     * produces no notification at all, and *nothing was said* is exactly what that case has to
     * assert. Waiting on the balloon there would hang for a minute and then fail for the wrong
     * reason.
     */
    protected fun awaitEvents(complaint: String, until: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            if (until()) return
            Thread.sleep(5)
        }
        fail(complaint)
    }

    /**
     * The plan the production walk builds for [text], whose `<selection>` markers say what is
     * selected. The plan's text is the snapped selection verbatim, which is what every offset in it
     * indexes into.
     *
     * The production walk, not a copy of it: a reimplementation here could pass while the action's
     * own reading of the selection drifted out from under it.
     */
    protected fun planFor(path: String, text: String): SnippetPlan {
        val file = myFixture.configureByText(path.substringAfterLast('/'), text)
        return JavaPlanBuilder.build(SnippetRequest(project, file, selectedRangesOf(myFixture.editor)))
    }

    /**
     * An analysis of [source], against an empty ledger and with nothing reduced — the plan the
     * production walk builds, run through the real engine.
     *
     * Here rather than in each test that wants one, because it is the opening state every surface
     * over an invocation is asserted against, and two copies of it are two files that can drift on
     * what *"nothing reduced"* means.
     */
    internal fun analysisOf(source: String, path: String = "Ledger.java"): Analysis = Analysis.of(
        planFor(path, source),
        AnonymizationSettings.DEFAULTS,
        LedgerSnapshot.EMPTY,
    )

    /**
     * Every balloon the most recent invocation raised, in order — the action's only observable side
     * effect. See [invokeCopyAnonymized] for why an earlier invocation's are not kept.
     */
    protected val notifications: List<Notification> get() = raised

    /**
     * Drops the balloons raised so far, which the `invoke…` helpers do for their own callers.
     *
     * Exposed for the tests that drive a function directly rather than through an action — the
     * insert-failure arm is only reachable that way, because `update` refuses the document that
     * triggers it.
     */
    protected fun dropEarlierBalloons() = raised.clear()

    private val raised = mutableListOf<Notification>()

    override fun setUp() {
        super.setUp()

        // **The light fixture reuses one project across test methods and across classes**, and the
        // prefix list is state on that project — so a test that sets one would otherwise be visible
        // to every test that ran after it, in whatever order the runner chose. Reset here rather
        // than in the one class that writes it: the hazard belongs to the shared project, not to the
        // test that happens to exercise it.
        InternalLibrarySettings.of(project).loadState(InternalLibrarySettings.State())

        // The mapping is application-level state, so it is shared by every test in this JVM and not
        // only by the tests sharing the light project — a stronger version of the same hazard, and
        // reset in the same place for the same reason.
        PlaceholderLedger.getInstance().loadState(PlaceholderLedger.State())

        // And the sidecar, which is project-level state on the same shared light project — the
        // hazard the prefix list has, over a window that a test recording fifty pastes would
        // otherwise hand to whatever ran next.
        PlaceholderSidecar.getInstance(project).loadState(PlaceholderSidecar.State())

        // And the first-run record, which is application-level and says *once ever*: without this,
        // the first test in this JVM to ask for it would be the only one that could ever see it.
        FirstRunNotice.getInstance().loadState(FirstRunNotice.State())

        project.messageBus.connect(testRootDisposable).subscribe(
            Notifications.TOPIC,
            object : Notifications {
                override fun notify(notification: Notification) {
                    if (notification.groupId == "SnippetVeil") raised += notification
                }
            },
        )
    }
}

/** A text block's delimiter, spelled once: a raw Kotlin string cannot hold three quotes. */
internal const val FENCE = "\"\"\""

private const val HARNESS_PROBE_PATH = "com/acme/probe/Owned.java"

/**
 * The running JDK and one real jar, in place of the mock JDK this build cannot see.
 *
 * JUnit 4 is the library only because it is already on the test classpath and
 * [PathManager.getJarPathForClass] can point at the jar it came from without anything having to know
 * where Gradle put it.
 */
private val REAL_CLASSPATH: LightProjectDescriptor = object : DefaultLightProjectDescriptor() {

    override fun getSdk(): Sdk = JavaSdk.getInstance().createJdk(
        "java-" + System.getProperty("java.specification.version"),
        System.getProperty("java.home"),
        false,
    )

    override fun configureModule(module: Module, model: ModifiableRootModel, entry: ContentEntry) {
        super.configureModule(module, model, entry)
        val jar = File(JUNIT4_JAR)
        PsiTestUtil.addLibrary(model, "junit4", jar.parent, jar.name)
    }
}

private val JUNIT4_JAR: String = PathManager.getJarPathForClass(org.junit.Test::class.java)
    ?: error("JUnit 4 is not on the test classpath as a jar, so no real library can be attached.")

/** The plan's symbol occurrences, in document order. */
internal fun SnippetPlan.symbols(): List<SymbolOccurrence> = occurrences.filterIsInstance<SymbolOccurrence>()

/**
 * A project-owned annotation that is itself meta-annotated by a third-party one.
 *
 * The meta-annotation is the point of the fixture rather than decoration: it is the shape the
 * rejected carve-out — *preserve a project annotation meta-annotated by a library annotation* —
 * would have keyed on, and this one is anonymized like any other project type.
 */
internal val PROJECT_ANNOTATION = """
    package com.acme.audit;

    import java.lang.annotation.Retention;
    import java.lang.annotation.RetentionPolicy;
    import org.junit.Ignore;

    @Ignore
    @Retention(RetentionPolicy.RUNTIME)
    public @interface AuditLogged {
        String action();
        String scope() default "";
    }
""".trimIndent()

/*
 * The reversal fixtures, shared rather than copied.
 *
 * Two test classes now assert the same round trip from opposite ends — `De-anonymize Clipboard`
 * over the clipboard and `De-anonymize Clipboard and Paste` over a document — and a snippet
 * duplicated into both would let the two halves of one claim drift apart on what was anonymized.
 */

/**
 * A clipboard a test owns, and can make fail.
 *
 * [written] rather than a comparison against [text], because *"the clipboard was not rewritten"* and
 * *"the clipboard was rewritten with the same string"* are different behaviours that leave the same
 * string behind, and it is the first of the two that is the guarantee.
 *
 * **Both directions fail on demand, and which one a test needs says which action it is testing.**
 * `De-anonymize Clipboard` writes, so its failure is [failWrite]; `De-anonymize Clipboard and Paste`
 * only ever reads, so a clipboard it cannot fail on read has no failure path to assert at all.
 */
internal class FakeClipboard(
    var text: String?,
    private val failWrite: Boolean = false,
    private val failRead: Boolean = false,
) : Clipboard {

    var written: Boolean = false
        private set

    override fun read(): String? {
        if (failRead) throw IllegalStateException("the system clipboard is owned by another process")
        return text
    }

    override fun write(text: String) {
        if (failWrite) throw IllegalStateException("the system clipboard is owned by another process")
        this.text = text
        written = true
    }
}

internal const val REVERSAL_LEDGER = "Ledger.java"

/**
 * A snippet with no comment in it, deliberately: comments are stripped by default and no reversal
 * restores destroyed information, so a fixture with one would make the round trip assert the
 * strip rather than the reversal. That the default path is lossy is asserted where it belongs, in
 * `:core`'s round-trip test.
 */
internal val REVERSAL_SNIPPET = """
    class Ledger {
        <selection>int settle(int amount) {
            int owed = amount;
            return owed;
        }</selection>
    }
""".trimIndent()

/**
 * What `<selection>` marks, which is what the copy took and therefore what either reversal must
 * return — to the clipboard or to a document.
 *
 * Named apart from the same helper in `ExportMappingTest`, which is deliberately its own: that file
 * asserts the exported *file* reverses a snippet, and shares no fixture with the two actions here.
 */
internal fun selectionIn(text: String): String =
    text.substringAfter("<selection>").substringBefore("</selection>")
