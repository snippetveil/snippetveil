package com.snippetveil.plugin

import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.testFramework.LightProjectDescriptor
import org.junit.Assert

/**
 * **[JavaSnippetTestCase.assertTheHarnessResolves], shown red — and it is the *origin* half that is
 * shown red here.**
 *
 * The probe used to assert that a project class, a JDK class and a library class **resolve**, while
 * the rule it implements is the stronger one: they have to resolve *to* project, JDK and library
 * origin, so that the discriminator between the last two is genuinely exercised. A classpath that
 * had drifted such that the library arrived as project content resolved every name perfectly and
 * passed — with the classification the probe exists to protect untested. That is the gap this
 * fixture stands in.
 *
 * The break is the one a fixture really drifts into: **no jar, and a stub of the library written
 * into project content**. It is the same argument the base class's own header makes about why the
 * library there is a real jar — a fake library only ever proves that the fake was classified.
 */
class HarnessOriginTest : JavaSnippetTestCase() {

    override fun getProjectDescriptor(): LightProjectDescriptor = WITHOUT_THE_REAL_JAR

    /**
     * **The stub, red.** Everything resolves, and the probe still fails — which is the whole of what
     * asserting origin bought.
     */
    fun `test the harness assertion fails when the library is a stub in project content`() {
        myFixture.addFileToProject("org/junit/Test.java", "package org.junit; public @interface Test {}")

        val failure = Assert.assertThrows(AssertionError::class.java) { assertTheHarnessResolves() }

        assertTrue(
            "The complaint did not name the origin the fixture came back with: ${failure.message}",
            failure.message.orEmpty().contains("org.junit.Test resolved to PROJECT where the fixture has to make it LIBRARY"),
        )
    }

    /**
     * **The older failure, still red.** A classpath with nothing on it at all resolves nothing, and
     * the complaint says what that costs: unresolved fails closed, so every name in every snippet
     * would be renamed and the suite would go green over it.
     */
    fun `test the harness assertion fails when there is no library at all`() {
        val failure = Assert.assertThrows(AssertionError::class.java) { assertTheHarnessResolves() }

        assertTrue(
            "The complaint did not say that the library reference failed to resolve: ${failure.message}",
            failure.message.orEmpty().contains("org.junit.Test did not resolve"),
        )
    }

    /**
     * And the complaint says nothing when the fixture is healthy — in every direction, because a
     * precondition that fired on a working classpath is one somebody has to route around.
     */
    fun `test the complaint is silent on a fixture that classifies correctly`() {
        for ((name, expected) in HARNESS_PROBES) {
            assertNull(
                "The origin complaint fired on a fixture that classified $name as $expected.",
                complaintAboutFixtureOrigin(name, expected, expected),
            )
        }
        assertNotNull(
            "The origin complaint said nothing about a JDK class arriving as a library.",
            complaintAboutFixtureOrigin("java.lang.String", FixtureOrigin.JDK, FixtureOrigin.LIBRARY),
        )
    }
}

/**
 * The real classpath **with no jar on it** — the deliberately broken one, and a class of its own so
 * that the light fixture cannot hand it the project the working descriptor built, which is the
 * hazard [RealClasspath.attachLibraries] is written around.
 */
private val WITHOUT_THE_REAL_JAR: LightProjectDescriptor = WithoutTheRealJar()

private class WithoutTheRealJar : RealClasspath() {

    override fun attachLibraries(model: ModifiableRootModel) = Unit
}
