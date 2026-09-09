package com.snippetveil.plugin.kotlin

/**
 * **The Kotlin harness asserting itself, and nothing else.**
 *
 * Every assertion here is about the fixture rather than about the product: no `.kt` file is walked
 * and no plan builder is asked for anything. What reaches a user's IDE is asserted in
 * [KotlinSupportTest], through the registration in `com.snippetveil-withKotlin.xml`; this is the
 * ground it stands on.
 *
 * The order they are written in is the order they fail in usefully. A fixture with no standard
 * library resolves nothing, so every assertion after the first would fail for a reason that is not
 * its own; a session in the wrong mode makes the three below it measurements of a configuration this
 * plugin declares unsupported.
 *
 * Each is shown red in [KotlinHarnessDemonstrationTest]. An assertion nobody has seen fail is a
 * comment.
 */
internal class KotlinHarnessTest : KotlinSnippetTestCase() {

    /**
     * The Java probes still hold on the Kotlin classpath, which is the Java one with a jar added.
     *
     * Here rather than assumed: the standard library is attached to the same module the JDK and the
     * real jar are, and *adding a library changed how the other two classify* is precisely the drift
     * `assertTheHarnessResolves` was widened to catch.
     */
    fun `test the java probes still classify on the kotlin classpath`() {
        assertTheHarnessResolves()
    }

    fun `test a kotlin standard library member resolves to library origin`() {
        assertTheKotlinStandardLibraryIsALibrary()
    }

    fun `test the analysis session is K2`() {
        assertTheSessionIsK2()
    }

    fun `test a java reference to a kotlin declaration reaches a light element`() {
        assertJavaReachesKotlinThroughALightClass()
    }

    fun `test the facade behaviour is the one pinned here`() {
        assertTheFacadeBehaviourIsPinned()
    }
}
