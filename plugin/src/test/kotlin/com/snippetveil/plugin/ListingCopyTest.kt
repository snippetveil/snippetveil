package com.snippetveil.plugin

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert

/**
 * **The listing, as the IDE loaded it** — the description the Marketplace shows, read out of the
 * descriptor this plugin was installed from.
 *
 * README.md is where the listing is written, and `assertTheListingCopyIsTheReadme` holds the shipped
 * description to it word for word. What no build rule sees is whether a sentence in it is still
 * *true of the product*, and for the one sentence that names a menu path that takes the registered
 * actions, which exist only here, in a running IDE.
 */
class ListingCopyTest : BasePlatformTestCase() {

    /**
     * **The path the listing names, against the path the action actually has** — the check the
     * balloon's path is under, over the same derived ancestry. [assertMenuPathIsReal] says how, and
     * why it is two assertions.
     *
     * **This sentence was wrong, and nothing read it against the menu.** When the four items moved
     * into a `SnippetVeil` submenu, the listing went on saying *right-click, **Copy Anonymized***,
     * one step short — the same omission the balloon made, which went red here and was corrected
     * while the listing stayed as it was. It was found in review, for snippetveil/snippetveil#103,
     * and this is what makes the next menu rearrangement go red on both surfaces rather than one.
     *
     * **A test rather than a rule in the build**, though every other check on the listing is a
     * Gradle task. The ancestry is a fact about the action system — one group in two homes, its
     * references resolved — and this suite already derives it; a build rule would need a second
     * derivation of the menu out of `plugin.xml`, and two derivations of one fact are two things to
     * keep in step. Reading the description the IDE loaded, rather than README.md, also means no
     * Markdown is parsed for it: the description is HTML, as the balloon is, and is read the same
     * way. `assertTheListingCopyIsTheReadme` is what makes that the README's sentence.
     */
    fun `test the listing's menu path is a real path to the action`() {
        assertMenuPathIsReal("The listing", listing(), COPY_ANONYMIZED)
    }

    /**
     * **The check fails on both of the wrong sentences it exists for.** The one the listing carried
     * names no path at all, and the one-step-short arrowed form is the balloon's old sentence; a
     * check whose red path is never exercised decays into one that always passes.
     */
    fun `test the check fails on a path with the submenu missing`() {
        val noPath = failureOf(
            "The old listing",
            "<li>Select Java or Kotlin code, right-click, <b>Copy Anonymized</b>. The anonymized text is on your clipboard.</li>",
        )
        assertTrue(noPath, "names no arrowed menu path" in noPath)

        val stepShort = failureOf(
            "The old balloon",
            "Select Java or Kotlin code, then right-click \u2192 <b>Copy Anonymized</b>.",
        )
        assertTrue(stepShort, "skips a level" in stepShort)
    }

    /**
     * What [assertMenuPathIsReal] says about [text], which it has to refuse.
     *
     * A method of its own rather than inline in the test above, because a lambda compiled inside a
     * method whose name starts with `test` becomes a synthetic method whose name does too, and
     * JUnit 3 collects it as a test and fails it for not being public.
     */
    private fun failureOf(surface: String, text: String): String =
        Assert.assertThrows(AssertionError::class.java) { assertMenuPathIsReal(surface, text, COPY_ANONYMIZED) }
            .message.orEmpty()

    /** The loaded plugin's description, which is the listing copy patched in at build time. */
    private fun listing(): String {
        val plugin = checkNotNull(PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))) {
            "$PLUGIN_ID is not loaded, so there is no listing to read."
        }
        return checkNotNull(plugin.description?.takeIf { it.isNotBlank() }) {
            "The loaded descriptor carries no description. The listing copy is patched in at build " +
                "time, so this is a descriptor that was not built, and nothing here is being checked."
        }
    }
}

/** The descriptor's `<id>`. */
private const val PLUGIN_ID = "com.snippetveil"

/** The item the listing names, by the id `plugin.xml` registers it under. */
private const val COPY_ANONYMIZED = "SnippetVeil.CopyAnonymized"
