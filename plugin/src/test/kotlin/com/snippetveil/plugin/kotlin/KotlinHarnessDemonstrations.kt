package com.snippetveil.plugin.kotlin

import com.intellij.testFramework.LightProjectDescriptor
import com.snippetveil.plugin.FixtureOrigin
import com.snippetveil.plugin.RealClasspath
import com.snippetveil.plugin.complaintAboutFixtureOrigin
import org.jetbrains.kotlin.idea.base.plugin.KotlinPluginMode
import org.junit.Assert
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **[KotlinSnippetTestCase]'s preconditions, shown red over a fixture with halves missing.** An
 * assertion nobody has seen fail is a comment, and these are assertions whose whole purpose is to
 * fire on a day nobody is looking.
 *
 * The fixture is the deliberately broken one, in both of the ways a Kotlin fixture actually breaks:
 * the **Kotlin standard library is left off**, and the **Kotlin declaration the bridge reaches for is
 * never written**. Both fail in the green direction if nobody asserts them. A fixture with no
 * standard library renames *everything*, because unresolved fails closed into the `Unknown`
 * namespace — so the leak oracle comes back maximally green over a harness that has stopped working.
 * A fixture with no bridge makes every assertion resting on it hold vacuously, and vacuous is read
 * afterwards as evidence that something held.
 *
 * The failures no fixture can produce — a K1 session, a facade the platform does not make, a
 * standard library arriving as project content — are shown red in [KotlinHarnessComplaintTest].
 */
internal class KotlinHarnessMissingHalvesTest : KotlinSnippetTestCase() {

    override fun getProjectDescriptor(): LightProjectDescriptor = WITHOUT_THE_STANDARD_LIBRARY

    /** The other half this fixture leaves out, which is what makes the bridge assertion fire. */
    override fun writeTheKotlinDeclarationTheBridgeReachesFor() = Unit

    /**
     * **The fail-green case, red.** Nothing resolves, and the complaint says the classpath rather
     * than the code under test is what is wrong.
     */
    fun `test the standard library assertion fails when no standard library is attached`() {
        val failure = Assert.assertThrows(AssertionError::class.java) { assertTheKotlinStandardLibraryIsALibrary() }

        assertTrue(
            failure.message.orEmpty().contains("kotlin.random.Random did not resolve"),
            "The complaint did not say that a kotlin.* name failed to resolve: ${failure.message}",
        )
    }

    /**
     * **The vacuous case, red.** The Java file is there and the Kotlin declaration it names is not,
     * which is the shape of a fixture that has quietly stopped bridging the two languages.
     */
    fun `test the bridge assertion fails when the kotlin declaration is missing`() {
        val failure = Assert.assertThrows(AssertionError::class.java) { assertJavaReachesKotlinThroughALightClass() }

        assertTrue(
            failure.message.orEmpty().contains("did not resolve"),
            "The complaint did not say that the Java reference failed to reach the declaration: ${failure.message}",
        )
    }
}

/**
 * **The same preconditions, shown red where no fixture can break them.**
 *
 * A K1 session is a property of which IDE the cell runs, and this cell runs a build whose Kotlin
 * plugin defaults to K2. A facade the platform does not make cannot be manufactured either — pinning
 * its behaviour is the point, and a fixture that could fake one would be pinning the fake. And a
 * `kotlin.*` declaration written into project content **does not resolve at all**: that was measured
 * here, against a fixture holding `package kotlin.random; class Random`, where the ordinary project
 * class beside it resolved and classified `PROJECT` while the standard-library name stayed
 * unresolved. The analysis will not take a `kotlin.*` declaration from project sources, so the
 * mirror failure cannot be built out of that probe — though the Java probe does show it red over a
 * real fixture, in `HarnessOriginTest`, against the same complaint.
 *
 * So every precondition separates the observation from the complaint about it, and here it is the
 * complaint that is exercised — **in both directions**, because a rule that flags the healthy case
 * too is one somebody eventually has to suppress.
 *
 * A plain test rather than a fixture-based one: none of this needs an IDE, and a fixture it never
 * used would be one the reader has to account for.
 */
internal class KotlinHarnessComplaintTest {

    @Test
    fun `a K1 session is complained about and a K2 one is not`() {
        val complaint = complaintAboutTheSessionMode(KotlinPluginMode.K1).orEmpty()

        assertTrue(
            "K1" in complaint && "supportsK1" in complaint,
            "The session assertion said nothing about a K1 session, which is the mode this plugin " +
                "declares it does not support: $complaint",
        )
        assertNull(
            complaintAboutTheSessionMode(KotlinPluginMode.K2),
            "The session assertion flagged a K2 session, which is the only mode it may accept.",
        )
    }

    @Test
    fun `a kotlin name arriving as project content is complained about`() {
        val complaint = complaintAboutFixtureOrigin(
            "kotlin.random.Random",
            FixtureOrigin.LIBRARY,
            FixtureOrigin.PROJECT,
        ).orEmpty()

        assertTrue(
            "resolved to PROJECT where the fixture has to make it LIBRARY" in complaint,
            "The origin complaint said nothing about a kotlin.* name arriving as project content: $complaint",
        )
    }

    @Test
    fun `both ways of missing the bridge are complained about and reaching it is not`() {
        val unresolved = complaintAboutTheBridge(resolvedType = null, isLightElement = false).orEmpty()
        val notLight = complaintAboutTheBridge("com.intellij.psi.impl.source.PsiClassImpl", false).orEmpty()

        assertTrue(
            "did not resolve" in unresolved && BRIDGE_PROBE in unresolved,
            "The bridge assertion said nothing about a reference that did not resolve: $unresolved",
        )
        assertTrue(
            "not a light element" in notLight,
            "The bridge assertion said nothing about a non-light resolution: $notLight",
        )
        assertNull(
            complaintAboutTheBridge("org.jetbrains.kotlin.asJava.classes.KtLightClass", true),
            "The bridge assertion flagged a Java reference that did reach a light element.",
        )
    }

    @Test
    fun `the facade pin is complained about in both directions`() {
        val appeared = complaintAboutTheFacades(
            facadeWithoutCallables = "a facade nobody expected",
            facadeWithCallable = "the facade that belongs",
        ).orEmpty()
        val vanished = complaintAboutTheFacades(facadeWithoutCallables = null, facadeWithCallable = null).orEmpty()

        assertTrue(
            "no top-level callables" in appeared && "change in the platform" in appeared,
            "The pin said nothing about a facade appearing where it is pinned absent: $appeared",
        )
        assertTrue(
            "has no facade light class" in vanished,
            "The pin said nothing about the facade that must exist going missing: $vanished",
        )
        assertNull(
            complaintAboutTheFacades(facadeWithoutCallables = null, facadeWithCallable = "the facade that belongs"),
            "The pin flagged the behaviour it exists to record.",
        )
    }
}

/**
 * The Kotlin fixture's classpath **without the Kotlin standard library**, and a class of its own so
 * the light fixture cannot hand it the project a working descriptor built — see
 * [RealClasspath.attachLibraries], which is where that hazard is written down.
 */
private val WITHOUT_THE_STANDARD_LIBRARY: LightProjectDescriptor = WithoutTheStandardLibrary()

private class WithoutTheStandardLibrary : RealClasspath()
