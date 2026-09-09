package com.snippetveil.plugin

import com.intellij.testFramework.ExtensionTestUtil

/**
 * **The IDE where SnippetVeil's Kotlin path did not load, built by taking the registration away.**
 *
 * The gate's third outcome is not a state this product passes through on its way to being finished:
 * it is what a user gets on an IDE with the Kotlin plugin switched off, and on one running K1, where
 * the platform skips `com.snippetveil-withKotlin.xml` on the strength of this plugin's own
 * `supportsK1="false"`. Both are configurations a fixture cannot enter — a running test cannot
 * uninstall a plugin from the IDE it is running in, and the K1 one is a whole platform version — so
 * the fixture reproduces the one fact the gate actually reads: **no support is registered for `kt`.**
 *
 * That is the same signal in both real configurations and in this one, which is what makes the
 * assertions written on top of it worth the same as assertions made on an IDE that really lacks the
 * plugin. What it is *not* is the linkage half — nothing here proves that a class naming
 * `org.jetbrains.kotlin.*` would fail to load, because in this JVM it would not. That half belongs to
 * `ShippedCodeArchitectureTest` over shipped bytecode, and to the Kotlin-disabled boot in
 * `com.snippetveil.boot`, which is the one thing in this repository that really does run without the
 * plugin.
 *
 * **The masking asserts its own coverage**, for the reason every check in this repository does: a
 * fixture that quietly failed to take the registration away would leave every refusal assertion below
 * it green on a build where `.kt` files are offered — and on the floor cell, where the platform has
 * already skipped the optional descriptor, there is nothing to take away and the masking removes
 * nothing at all. So what is asserted is the state afterwards rather than the act: **`kt` is not
 * registered here, and `java` still is.**
 */
abstract class KotlinUnregisteredTestCase : JavaSnippetTestCase() {

    override fun setUp() {
        super.setUp()

        // Masked for the lifetime of this test, and restored by the fixture's own disposable: the
        // extension point is application-level state on a light fixture that other test classes
        // share, so leaving a support unregistered would be a hazard handed to whatever ran next.
        ExtensionTestUtil.maskExtensions(
            LANGUAGE_SUPPORT,
            LANGUAGE_SUPPORT.extensionList.filterNot { it.extension.equals(KOTLIN_EXTENSION, ignoreCase = true) },
            testRootDisposable,
        )

        assertFalse(
            "A support is still registered for kt, so this fixture is not the configuration it claims to be " +
                "and every refusal asserted on top of it would be asserting the opposite outcome.",
            LANGUAGE_SUPPORT.extensionList.any { it.extension.equals(KOTLIN_EXTENSION, ignoreCase = true) },
        )
        assertTrue(
            "Java's support went with Kotlin's. This fixture is an IDE without the Kotlin plugin, not an " +
                "IDE without SnippetVeil.",
            LANGUAGE_SUPPORT.extensionList.any { it.extension.equals(JAVA_EXTENSION, ignoreCase = true) },
        )
    }

    protected companion object {

        /**
         * The two accepted extensions, spelled as the descriptors spell them. Named here rather than
         * read from the gate's own constants: a fixture that took its expectations from the code
         * under test would agree with it about a typo.
         */
        const val JAVA_EXTENSION = "java"
        const val KOTLIN_EXTENSION = "kt"
    }
}
