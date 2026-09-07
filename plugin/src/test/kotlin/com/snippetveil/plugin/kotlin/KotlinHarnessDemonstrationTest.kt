package com.snippetveil.plugin.kotlin

import com.intellij.testFramework.LightProjectDescriptor
import com.snippetveil.core.SymbolOrigin
import com.snippetveil.plugin.RealClasspath
import com.snippetveil.plugin.complaintAboutFixtureOrigin
import org.jetbrains.kotlin.idea.base.plugin.KotlinPluginMode
import org.junit.Assert

/**
 * **Every precondition in [KotlinSnippetTestCase], shown red.** An assertion nobody has seen fail is
 * a comment, and these are assertions whose whole purpose is to fire on a day nobody is looking.
 *
 * The fixture this class runs on **is the deliberately broken one**: the Java classpath with the
 * Kotlin standard library left off, which is the trap the standard-library precondition exists for.
 * A fixture like it renames *everything*, because unresolved fails closed into the `Unknown`
 * namespace — so the leak oracle would come back maximally green over a harness that had stopped
 * working, and no ordinary assertion would say a word.
 *
 * **Some of these failures cannot be produced by a fixture at all, and are shown red over the
 * complaint itself.** A K1 session is a property of which IDE the cell runs, and this cell runs a
 * build whose Kotlin plugin defaults to K2. A facade the platform does not make cannot be
 * manufactured either — pinning its behaviour is the point, and a fixture that could fake one would
 * be pinning the fake. So every precondition here separates the observation from the complaint about
 * it, and where the observation cannot be broken it is the complaint that is exercised — in both
 * directions, because a rule that flags the healthy case too is one somebody has to suppress.
 */
internal class KotlinHarnessDemonstrationTest : KotlinSnippetTestCase() {

    override fun getProjectDescriptor(): LightProjectDescriptor = WITHOUT_THE_STANDARD_LIBRARY

    /**
     * **The fail-green case, red.** Nothing resolves, and the complaint says the classpath rather
     * than the code under test is what is wrong.
     */
    fun `test the standard library assertion fails when no standard library is attached`() {
        val failure = Assert.assertThrows(AssertionError::class.java) { assertTheKotlinStandardLibraryIsALibrary() }

        assertTrue(
            "The complaint did not say that a kotlin.* name failed to resolve: ${failure.message}",
            failure.message.orEmpty().contains("kotlin.random.Random did not resolve"),
        )
    }

    /**
     * **The mirror failure, red** — a `kotlin.*` name that arrived as project content, which resolves
     * perfectly and would make every standard-library name a candidate for renaming, with the spine
     * rule under test against a fixture that lies about which files are the project's.
     *
     * Over the complaint rather than over a fixture, and the reason is a platform fact worth
     * recording: **a `kotlin.*` declaration written into project content does not resolve at all.**
     * A fixture holding `package kotlin.random; class Random` was measured here — the project class
     * beside it resolves and classifies `IN_CONTENT`, and the standard-library name stays
     * unresolved, because the analysis will not take a `kotlin.*` declaration from project sources.
     * So the mirror cannot be built out of this probe, and the complaint is exercised directly.
     *
     * It is the same complaint `assertTheHarnessResolves` uses, and `HarnessOriginTest` does show it
     * red over a real fixture whose library arrived as project content — a Java one, where the
     * platform allows it.
     */
    fun `test the standard library assertion fails when a kotlin name is project content`() {
        val complaint = complaintAboutFixtureOrigin(
            "kotlin.random.Random",
            SymbolOrigin.LIBRARY,
            SymbolOrigin.IN_CONTENT,
        ).orEmpty()

        assertTrue(
            "The origin complaint said nothing about a kotlin.* name arriving as project content: $complaint",
            "resolved to IN_CONTENT where the fixture has to make it LIBRARY" in complaint,
        )
    }

    /** **A K1 session, red** — and a K2 one left alone. */
    fun `test the session assertion fails on a K1 session and passes on a K2 one`() {
        val complaint = complaintAboutTheSessionMode(KotlinPluginMode.K1).orEmpty()

        assertTrue(
            "The session assertion said nothing about a K1 session, which is the mode this plugin " +
                "declares it does not support: $complaint",
            "K1" in complaint && "supportsK1" in complaint,
        )
        assertNull(
            "The session assertion flagged a K2 session, which is the only mode it may accept.",
            complaintAboutTheSessionMode(KotlinPluginMode.K2),
        )
    }

    /**
     * **The bridge, red in both of the ways it can be absent** — and silent when a Java reference
     * really did reach a light element.
     *
     * Vacuity is the failure being guarded: an assertion resting on a bridge that is not there
     * passes, says nothing, and is read afterwards as evidence that it held.
     */
    fun `test the bridge assertion fails when nothing resolves and when nothing is light`() {
        val unresolved = complaintAboutTheBridge(resolvedType = null, isLightElement = false).orEmpty()
        val notLight = complaintAboutTheBridge("com.intellij.psi.impl.source.PsiClassImpl", false).orEmpty()

        assertTrue("The bridge assertion said nothing about a reference that did not resolve: $unresolved",
            "did not resolve" in unresolved && BRIDGE_PROBE in unresolved)
        assertTrue("The bridge assertion said nothing about a non-light resolution: $notLight",
            "not a light element" in notLight)
        assertNull(
            "The bridge assertion flagged a Java reference that did reach a light element.",
            complaintAboutTheBridge("org.jetbrains.kotlin.asJava.classes.KtLightClass", true),
        )
    }

    /**
     * **The pin, red in both directions.** A platform that starts making facades for a file with no
     * top-level callables fails here naming the platform; so does one that stops making them at all,
     * which is the half that keeps the pin from being satisfied by a Kotlin plugin that had given up
     * on light classes entirely.
     */
    fun `test the facade pin fails in both directions`() {
        val appeared = complaintAboutTheFacades("a facade nobody expected", "the facade that belongs").orEmpty()
        val vanished = complaintAboutTheFacades(null, null).orEmpty()

        assertTrue("The pin said nothing about a facade appearing where it is pinned absent: $appeared",
            "no top-level callables" in appeared && "change in the platform" in appeared)
        assertTrue("The pin said nothing about the facade that must exist going missing: $vanished",
            "has no facade light class" in vanished)
        assertNull(
            "The pin flagged the behaviour it exists to record.",
            complaintAboutTheFacades(null, "the facade that belongs"),
        )
    }
}

/**
 * The Kotlin fixture's classpath **without the Kotlin standard library** — the deliberately broken
 * one, and a class of its own so the light fixture cannot hand it the project a working descriptor
 * built. See [RealClasspath.attachLibraries].
 */
private val WITHOUT_THE_STANDARD_LIBRARY: LightProjectDescriptor = WithoutTheStandardLibrary()

private class WithoutTheStandardLibrary : RealClasspath()
