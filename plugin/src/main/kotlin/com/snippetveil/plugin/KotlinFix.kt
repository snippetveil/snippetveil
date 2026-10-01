package com.snippetveil.plugin

import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project

/**
 * **The fix for a Kotlin-unavailable cause — the link's words and the page it opens**, held once,
 * because two surfaces offer it: the editor refusal's balloon, and the trace preview's Kotlin note.
 * Two copies of a link are two copies that can come to disagree about where they send the user.
 *
 * **Two destinations, because the two causes are two different problems.**
 *
 * [Unavailable.PLUGIN_NOT_RUNNING] — the Kotlin plugin is absent or disabled, so no Kotlin-owned
 * settings page exists to open. The **Plugins** page is a platform configurable and is the only
 * correct target: it is where the user enables or installs the thing that is missing.
 *
 * [Unavailable.PATH_NOT_ACTIVATED] — the Kotlin plugin *is* running and SnippetVeil's Kotlin path
 * still did not load, which in practice means K1 mode. Here a Kotlin-owned configurable exists,
 * precisely because the plugin is enabled, and it is where the mode is switched.
 *
 * [KOTLIN_CONFIGURABLE_ID] is read out of the Kotlin plugin's own `kotlin-core.xml`, where it is
 * the `applicationConfigurable` under `groupId="language"` — the *Languages & Frameworks →
 * Kotlin* page, which is also the page carrying the K1/K2 switch, and it is spelled the same on
 * every build this project runs against, floor included. Both pages are reached by id rather
 * than by class; [openSettingsAt] is where that is argued.
 */
internal object KotlinFix {

    /**
     * The Kotlin settings page — *Languages & Frameworks → Kotlin* — as a string id, which is how
     * the Kotlin plugin's own descriptor spells it. See [openSettingsAt] for why it cannot be a class.
     */
    private const val KOTLIN_CONFIGURABLE_ID = "preferences.language.Kotlin"

    /** The platform's **Plugins** page, spelled as `PlatformExtensions.xml` registers it. */
    private const val PLUGINS_CONFIGURABLE_ID = "preferences.pluginManager"

    /** What the link for [cause] reads. */
    fun linkFor(cause: Unavailable): String = pageFor(cause).link

    /** Opens the page the link for [cause] names. */
    fun open(project: Project?, cause: Unavailable) = openSettingsAt(pageFor(cause).id, project)

    /** A link's words and the configurable id it opens — one row per cause, so the two cannot drift. */
    private class Page(val link: String, val id: String)

    private fun pageFor(cause: Unavailable): Page = when (cause) {
        Unavailable.PLUGIN_NOT_RUNNING -> Page("Open Plugins", PLUGINS_CONFIGURABLE_ID)
        Unavailable.PATH_NOT_ACTIVATED -> Page("Open Kotlin settings", KOTLIN_CONFIGURABLE_ID)
    }

    /**
     * Settings, opened at the page with [id] — selected by **id through a predicate** rather than by
     * a configurable class, and that is forced twice over.
     *
     * The Kotlin page's class is Kotlin-owned, and naming it would put a Kotlin type on a code path
     * whose whole premise is that Kotlin types may be absent. The Plugins page's class is the
     * platform's `PluginManagerConfigurable`, which is marked internal — the plugin verifier fails
     * the build on a reference to it, and it is the sort of class that gets moved between releases.
     * An id is neither: it is text, it links nothing, and one that stops resolving opens Settings at
     * its root, which is degraded rather than wrong.
     */
    private fun openSettingsAt(id: String, project: Project?) {
        ShowSettingsUtil.getInstance().showSettingsDialog(
            project,
            { it is SearchableConfigurable && it.id == id },
            null,
        )
    }
}
