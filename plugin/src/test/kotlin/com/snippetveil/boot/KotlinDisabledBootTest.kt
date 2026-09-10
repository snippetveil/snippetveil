package com.snippetveil.boot

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.NotificationType
import com.snippetveil.plugin.GateVerdict
import com.snippetveil.plugin.JAVA_EXTENSION
import com.snippetveil.plugin.JavaSnippetTestCase
import com.snippetveil.plugin.KOTLIN_EXTENSION
import com.snippetveil.plugin.Unavailable
import com.snippetveil.plugin.clipboard
import com.snippetveil.plugin.gate
import com.snippetveil.plugin.setClipboard
import com.snippetveil.plugin.supportIsRegisteredFor

/**
 * **SnippetVeil on an IDE that does not have the Kotlin plugin — the half of the isolation guarantee
 * an architecture rule cannot reach.**
 *
 * The static rule in `ShippedCodeArchitectureTest` reads shipped bytecode and asserts that nothing
 * the main descriptor reaches names an `org.jetbrains.kotlin.*` type. That is the right instrument
 * for the failure it guards — a class that will not link — and it is blind to every other way the
 * isolation actually breaks: reflection, a service registration, an extension point wired from the
 * wrong descriptor. None of those is visible to a rule over packages, and all of them produce the
 * same symptom, which is a privacy tool that fails to load and takes Java anonymization down with a
 * language it never needed.
 *
 * So this boots one. The cell that runs it is `kotlinDisabledBoot` in `plugin/build.gradle.kts`,
 * which starts an IDE with `org.jetbrains.kotlin` **disabled through the platform plugin's own
 * `disablePlugin(...)`** — a hand-written `disabled_plugins.txt` is overwritten by `prepareSandbox`,
 * and the run then happens with Kotlin enabled and looks exactly like a pass.
 *
 * **A release gate, not a per-PR test.** It boots an IDE against the built distribution, which is the
 * subject the bytecode scan and `verifyPlugin` already have, and the architecture rule catches the
 * ordinary way isolation breaks at merge speed. `release.yml` runs it before anything is uploaded.
 *
 * **What it does not prove, stated so that nobody reads more into a green run than is there.** The
 * Kotlin plugin's jars are still on this cell's test classpath — a test task is given the platform's
 * classpath whatever the sandbox has disabled — so a class naming `org.jetbrains.kotlin.*` would
 * still link here. What is genuinely absent is the *plugin*: the platform has it switched off, the
 * optional descriptor was not loaded, and nothing is registered for `kt`. So this asserts the
 * product's behaviour in that configuration, and **linkage remains the architecture rule's claim**,
 * over bytecode, where it can be made without an IDE at all.
 *
 * **Kotlin's absence is asserted in [setUp]**, before any assertion about behaviour, because every
 * assertion below is worthless in the configuration this cell is supposed to be and is not: with the
 * plugin enabled the Java half passes for the ordinary reason, the refusal never fires, and a green
 * result would mean nothing at all. The complaint it is written as is shown red in
 * [KotlinDisabledBootDemonstrationTest], which runs in every ordinary cell — that is, in the one
 * configuration where this precondition must fail.
 */
class KotlinDisabledBootTest : JavaSnippetTestCase() {

    override fun setUp() {
        super.setUp()
        complaintAboutKotlinBeingLoaded(loadedPluginIds())?.let { fail(it) }
    }

    /**
     * **The Java half still works**, which is the whole point of declaring the Kotlin dependency
     * optional: a user who has switched the Kotlin plugin off keeps a working privacy tool.
     *
     * Through the action rather than through the builder — the gate, the dispatch, the walk, the
     * engine and the clipboard, on the classloader that has no Kotlin plugin on it.
     */
    fun `test the Java actions work with no Kotlin plugin in the IDE`() {
        assertTheHarnessResolves()
        myFixture.configureByText(
            "Ledger.java",
            """
            class Ledger {
                <selection>String describe(int amount) {
                    return String.valueOf(amount);
                }</selection>
            }
            """.trimIndent(),
        )

        val presentation = invokeCopyAnonymized()

        assertTrue("Copy Anonymized is not offered on a .java file with Kotlin switched off.", presentation.isEnabled)
        assertEquals(
            "String method1(int param2) {\n" +
                "        return String.valueOf(param2);\n" +
                "    }",
            clipboard(),
        )
    }

    /**
     * **The refusal row, in the configuration it was written for.**
     *
     * Every other fixture reaches this outcome by taking the registration away from an IDE that has
     * the Kotlin plugin. This one has no Kotlin plugin: the optional descriptor was never loaded, so
     * nothing registered a support for `kt`, and the gate arrives at its third outcome the way a
     * user's IDE does. The sentence is asserted character for character, and the clipboard it
     * promises not to touch is checked rather than believed.
     */
    fun `test a kt file is refused with the exact sentence and an untouched clipboard`() {
        setClipboard(PREVIOUS_CLIPBOARD)
        myFixture.configureByText("Payment.kt", "<selection>class Payment</selection>")

        val presentation = invokeCopyAnonymized()

        assertTrue("The action was hidden on a .kt file, so the refusal can never be said.", presentation.isVisible)
        assertTrue("The action was disabled on a .kt file, so invoking it says nothing.", presentation.isEnabled)
        assertEquals(
            "Kotlin support is not available in this IDE — your clipboard was not changed.",
            notifications.single().content,
        )
        assertEquals(NotificationType.WARNING, notifications.single().type)
        assertEquals(PREVIOUS_CLIPBOARD, clipboard())
    }

    /**
     * **The one availability state this configuration can produce, and the only place it is
     * produced.**
     *
     * The gate tells its two causes apart by asking the platform whether the Kotlin plugin is
     * installed and whether it is switched off, and every fixture-based test in this repository runs
     * with it installed and on — so [Unavailable.PLUGIN_NOT_RUNNING] is an answer no fixture produces.
     * `UnavailableCauseTest` holds the mapping from those two answers to the cause, and
     * `KotlinUnavailableTest` the notification it raises by hand. Here the answers are real, computed
     * from the real plugin set, and the outcome decides which page the balloon's fix link opens:
     * **Plugins**, because an IDE without the Kotlin plugin has no Kotlin-owned settings page to point
     * at.
     *
     * Asserted as the verdict, not as the API behind it: which [Unavailable] a switched-off Kotlin
     * plugin produces is the subject, and it must not move when the probe does.
     */
    fun `test the missing plugin is reported as the plugin not running`() {
        val file = myFixture.configureByText("Payment.kt", "class Payment")

        assertEquals(GateVerdict.Refuse(Unavailable.PLUGIN_NOT_RUNNING), gate(file))

        assertFalse(
            "A support is registered for kt on an IDE with no Kotlin plugin, so the optional descriptor " +
                "was loaded after all and this cell is not the configuration it claims to be.",
            supportIsRegisteredFor(KOTLIN_EXTENSION),
        )
        assertTrue(
            "Java's support is missing too, so this is an IDE without SnippetVeil rather than one " +
                "without the Kotlin plugin.",
            supportIsRegisteredFor(JAVA_EXTENSION),
        )

        myFixture.configureByText("Payment.kt", "<selection>class Payment</selection>")
        invokeCopyAnonymized()

        assertEquals(
            listOf("Open Plugins"),
            notifications.single().actions.map { it.templatePresentation.text },
        )
    }

    private companion object {

        /** Something recognisable to leave on the clipboard, so that *unchanged* is readable. */
        const val PREVIOUS_CLIPBOARD = "the text the user copied before reaching for SnippetVeil"
    }
}

/** The Kotlin plugin's id, as a plain string — naming it costs nothing and links nothing. */
internal const val KOTLIN_PLUGIN_ID = "org.jetbrains.kotlin"

/**
 * Every plugin this IDE actually loaded, by id.
 *
 * Read from the loaded set rather than through the gate's own probe, and deliberately: a precondition
 * and the assertion it guards must not share an instrument, or a probe that went wrong would agree
 * with itself. `getLoadedPlugins` is internal API on newer platforms, which costs nothing here — test
 * classes are not shipped, so `verifyPlugin` never sees them.
 */
internal fun loadedPluginIds(): List<String> = PluginManagerCore.loadedPlugins.map { it.pluginId.idString }

/**
 * What is wrong with the **cell** when [loaded] still holds the Kotlin plugin, or `null` when the
 * IDE is the one this boot is about.
 *
 * A complaint rather than an assertion, so that it can be exercised in both directions from a cell
 * where Kotlin is running — see [KotlinDisabledBootDemonstrationTest]. It names the cell rather than
 * the product, because a Kotlin plugin that is still here means the disabling came apart, and every
 * assertion made afterwards would be measuring an ordinary IDE and passing for ordinary reasons.
 */
internal fun complaintAboutKotlinBeingLoaded(loaded: List<String>): String? =
    if (KOTLIN_PLUGIN_ID !in loaded) null
    else "$KOTLIN_PLUGIN_ID is loaded in a cell whose whole subject is an IDE that does not have it. " +
        "The disabling came apart — a hand-written disabled_plugins.txt is overwritten by " +
        "prepareSandbox, which is why this cell disables the plugin through the Gradle plugin's own " +
        "disablePlugin(...) — and everything asserted after this line would be an assertion about an " +
        "ordinary IDE, passing for ordinary reasons."
