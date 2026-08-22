package com.snippetveil.plugin

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service

/**
 * **The product's one persistent setting, from the outside**: where it is stored, what it defaults
 * to, and what the engine is handed when a project has said something.
 *
 * The classification itself is not tested here and deliberately so — it is a pure rule over a plan
 * literal, tested in `:core` at millisecond speed with no IDE involved. What cannot be tested there
 * is that the list is per-project, that its defaults are the ones the ticket asked for, and that the
 * action actually reads it. That is what is here.
 */
class InternalLibrarySettingsTest : JavaSnippetTestCase() {

    /**
     * **Project-level, because the list is inherently per-repository**: a different employer means a
     * different group id, and a global list would carry the last employer's vocabulary into the next
     * one's project.
     *
     * Asserted on the declaration rather than on a side effect, because the declaration *is* the
     * registration: a light service is scoped by its `@Service` level and by nothing else, so a
     * change to application level is a change to this line and would be invisible in any behaviour a
     * single-project fixture can observe.
     */
    fun `test the prefix list is a project-level service`() {
        assertEquals(
            listOf(Service.Level.PROJECT),
            InternalLibrarySettings::class.java.getAnnotation(Service::class.java).value.toList(),
        )

        val settings = InternalLibrarySettings.of(project)
        assertNotNull(settings)
        assertSame("The platform handed out two instances for one project.", settings, project.service<InternalLibrarySettings>())
    }

    /**
     * Auto-detect is on out of the box and the list is empty, which is the whole of what a project
     * that has never opened the settings gets: the root-package heuristic, and nothing a human had
     * to say first.
     */
    fun `test auto-detect defaults to on with nothing added or removed`() {
        val policy = InternalLibrarySettings.of(project).policy

        assertTrue(policy.autoDetectRootPackage)
        assertEmpty(policy.internalPrefixes.toList())
        assertEmpty(policy.thirdPartyPrefixes.toList())
    }

    /** What the platform loaded off disk is what the engine is handed, one field at a time. */
    fun `test the stored state is what the engine is handed`() {
        val settings = InternalLibrarySettings.of(project)
        settings.loadState(
            InternalLibrarySettings.State().apply {
                autoDetectRootPackage = false
                internalPrefixes = mutableListOf("io.acmecorp")
                thirdPartyPrefixes = mutableListOf("io.acmecorp.oss")
            },
        )

        val policy = settings.policy

        assertFalse(policy.autoDetectRootPackage)
        assertEquals(setOf("io.acmecorp"), policy.internalPrefixes)
        assertEquals(setOf("io.acmecorp.oss"), policy.thirdPartyPrefixes)
    }

    /**
     * **The heuristic, end to end and on by default.** The analysed file's root package is
     * `org.junit`, so the real jar attached to this fixture is — for the length of this test — the
     * company's own shared artifact, and its names come back as placeholders.
     *
     * This is the same jar every other test in this package relies on being *preserved*, which is
     * what makes it the right fixture: nothing about the library changed, only the package the file
     * asking about it lives in.
     */
    fun `test a library symbol under the file's own root package is anonymized`() {
        assertTheHarnessResolves()
        myFixture.configureByText(
            "Probe.java",
            """
            package org.junit.probe;

            import org.junit.Assert;

            class Probe {
                void check(String value) {
                    <selection>Assert.assertNotNull(value);</selection>
                }
            }
            """.trimIndent(),
        )

        invokeCopyAnonymized()

        assertEquals("Type1.method2(param3);", clipboard())
    }

    /**
     * **The added half of the list, end to end.** The file's root package says nothing about
     * `org.junit`, so only a human saying so pulls it in — which is the shape the heuristic cannot
     * see: shared artifacts published under a different group id from the repository consuming them.
     */
    fun `test an added prefix pulls a library symbol into the anonymized set`() {
        assertTheHarnessResolves()
        InternalLibrarySettings.of(project).loadState(
            InternalLibrarySettings.State().apply { internalPrefixes = mutableListOf("org.junit") },
        )
        myFixture.configureByText(
            "Probe.java",
            """
            package com.acme.billing;

            import org.junit.Assert;

            class Probe {
                void check(String value) {
                    <selection>Assert.assertNotNull(value);</selection>
                }
            }
            """.trimIndent(),
        )

        invokeCopyAnonymized()

        assertEquals("Type1.method2(param3);", clipboard())
    }

    /**
     * And without it, the same library is preserved — which is the assertion that keeps the two
     * above from passing because everything is anonymized.
     */
    fun `test a library outside every prefix is preserved`() {
        assertTheHarnessResolves()
        myFixture.configureByText(
            "Probe.java",
            """
            package com.acme.billing;

            import org.junit.Assert;

            class Probe {
                void check(String value) {
                    <selection>Assert.assertNotNull(value);</selection>
                }
            }
            """.trimIndent(),
        )

        invokeCopyAnonymized()

        assertEquals("Assert.assertNotNull(param1);", clipboard())
    }
}
