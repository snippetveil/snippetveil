package com.snippetveil.plugin

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
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
     * Runs the action the way the IDE would — `update` first, then `actionPerformed` only if the
     * presentation came back enabled — and waits for the background analysis to land.
     *
     * @return the presentation `update` produced, which is what the Java-file gate is asserted on
     */
    protected fun invokeCopyAnonymized(action: CopyAnonymizedAction = CopyAnonymizedAction()) =
        myFixture.testAction(action).also { if (it.isEnabled) awaitBackgroundWork() }

    /**
     * Drains the pooled thread the analysis runs on and the event queue its result comes back
     * through. The action returns before either has happened, so an assertion made without this
     * would be racing a thread it cannot see.
     */
    protected fun awaitBackgroundWork() {
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            if (notifications.isNotEmpty()) return
            Thread.sleep(5)
        }
        fail("The analysis produced no notification within 60s; nothing came back from the background thread.")
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

    /** Every balloon this test has raised, in order. The action's only observable side effect. */
    protected val notifications: List<Notification> get() = raised

    private val raised = mutableListOf<Notification>()

    override fun setUp() {
        super.setUp()
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
