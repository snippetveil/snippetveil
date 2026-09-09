package com.snippetveil.plugin

import com.intellij.notification.NotificationType

/**
 * **The source-file gate's third outcome, on the path that matters: invoke.**
 *
 * The refusal exists because *disabled but visible* is silent exactly where silence is dangerous — a
 * user with a rebound shortcut presses the key, nothing happens, and their next paste is the previous
 * clipboard. So the item is present and enabled on a `.kt` whose support did not load, and it speaks
 * when used; the assertions below are that sentence, its level, its links, and the clipboard it did
 * not touch.
 *
 * **Why this fires here, now that the registration exists.** `com.snippetveil-withKotlin.xml`
 * registers a support for `kt`, so on an ordinary IDE with the Kotlin plugin running in K2 a `.kt`
 * file is offered and nothing below would happen at all. The refusal did not stop being real when
 * that line landed — it is what a user gets on an IDE with the Kotlin plugin switched off, and on one
 * running K1, where the platform skips that descriptor on the strength of this plugin's own
 * `supportsK1="false"` — so these tests moved onto [KotlinUnregisteredTestCase], which reproduces the
 * one fact the gate reads: no support is registered for `kt`.
 *
 * The fixture runs with the Kotlin plugin itself present and enabled, so the cause here is always
 * [Unavailable.PATH_NOT_ACTIVATED] — which is exactly the K1 user's cause. The other cause is
 * asserted directly against the notification, because an IDE with the Kotlin plugin switched off is
 * an environment rather than an input.
 */
class KotlinUnavailableTest : KotlinUnregisteredTestCase() {

    /**
     * **Present and enabled, which is what makes the refusal reachable.**
     *
     * The gate's other negative removes the item entirely; this one must leave it there, or the
     * invocation that raises the message never happens and the user is back to a shortcut that does
     * nothing.
     */
    fun `test the menu item is present and enabled on a kt file`() {
        myFixture.configureByText("Payment.kt", "<selection>class Payment</selection>")

        val presentation = invokeCopyAnonymized()

        assertTrue("The action was hidden on a .kt file, so the refusal can never be said.", presentation.isVisible)
        assertTrue("The action was disabled on a .kt file, so invoking it says nothing.", presentation.isEnabled)
    }

    /**
     * **The sentence, character for character, and the clipboard clause is half of it.**
     *
     * Without that clause a user reads *unavailable*, shrugs, and pastes — and what comes out is
     * whatever was on the clipboard before, which is the highest-severity failure this product has.
     * Warning rather than error because nothing is broken, and rather than information because the
     * user asked for something and did not get it.
     */
    fun `test the refusal says the exact sentence at warning level`() {
        myFixture.configureByText("Payment.kt", "<selection>class Payment</selection>")

        invokeCopyAnonymized()

        val balloon = notifications.single()
        assertEquals(
            "Kotlin support is not available in this IDE — your clipboard was not changed.",
            balloon.content,
        )
        assertEquals(NotificationType.WARNING, balloon.type)
    }

    /**
     * **A fix link, and no report link.**
     *
     * The product is working correctly and describing its configuration, so there is no defect to
     * report — a report link here would collect issues about a disabled plugin, filed against the
     * plugin that noticed. Every other balloon in this product that says something went wrong offers
     * `Report an issue`, which is exactly why its absence has to be asserted rather than assumed.
     */
    fun `test the refusal offers a fix link and no report link`() {
        myFixture.configureByText("Payment.kt", "<selection>class Payment</selection>")

        invokeCopyAnonymized()

        val links = notifications.single().actions.map { it.templatePresentation.text }
        assertEquals(listOf("Open Kotlin settings"), links)
    }

    /**
     * **Two causes, two destinations, and one signal cannot tell them apart** — which is the reason
     * the gate asks two questions rather than one.
     *
     * A Kotlin plugin that is not running has no Kotlin-owned settings page to open, so the fix is
     * the platform's **Plugins** page; a Kotlin plugin that *is* running while our path did not
     * activate is K1 mode, and the page that switches it exists precisely because the plugin is
     * enabled. Pointing at the wrong one is worse than pointing at none.
     *
     * Asserted against the notification directly, because only one of the two causes is reachable
     * from a fixture: this IDE has the Kotlin plugin, and no test can take it away.
     */
    fun `test the fix link differs by cause`() {
        dropEarlierBalloons()

        SnippetVeilNotifications.kotlinUnavailable(project, Unavailable.PLUGIN_NOT_RUNNING)
        SnippetVeilNotifications.kotlinUnavailable(project, Unavailable.PATH_NOT_ACTIVATED)

        assertEquals(
            listOf(listOf("Open Plugins"), listOf("Open Kotlin settings")),
            notifications.map { balloon -> balloon.actions.map { it.templatePresentation.text } },
        )
        assertTrue(
            "A refusal said something other than the one sentence.",
            notifications.all { it.type == NotificationType.WARNING },
        )
    }

    /**
     * **The clipboard clause, checked rather than promised.**
     *
     * The message is only true because the refusal happens before anything is read, and *before
     * anything is read* is a property of where the check sits in the invocation — one edit away from
     * being false while the sentence still claims it.
     */
    fun `test a refusal leaves the clipboard byte-identical`() {
        setClipboard(PREVIOUS_CLIPBOARD)
        myFixture.configureByText("Payment.kt", "<selection>class Payment</selection>")

        invokeCopyAnonymized()

        assertEquals(PREVIOUS_CLIPBOARD, clipboard())
    }

    /**
     * **Both actions that read the editor refuse, and the preview refuses without opening.**
     *
     * The check sits on the invocation rather than inside the dialog, so the modal never appears —
     * a refusal shown behind a preview would be a refusal the user has to dismiss a window to read.
     */
    fun `test the preview action refuses without opening the dialog`() {
        myFixture.configureByText("Payment.kt", "<selection>class Payment</selection>")

        var opened = false
        invokeWithPreview { _, analysis -> analysis.also { opened = true } }

        assertFalse("The preview dialog opened on a file whose support did not load.", opened)
        assertEquals(
            "Kotlin support is not available in this IDE — your clipboard was not changed.",
            notifications.single().content,
        )
    }

    /**
     * **The gate's own verdict in this configuration**, rather than what the user was told about it.
     *
     * Everything above asserts the sentence and the clipboard; this asserts the decision they follow
     * from, and it is the one row of the table that moves between configurations — offered where
     * SnippetVeil's Kotlin path loaded, refused with a stated cause where it did not, and **never
     * silently absent** in either.
     */
    fun `test the gate refuses a kt file where no support is registered for it`() {
        val file = myFixture.configureByText("Payment.kt", "class Payment")

        assertEquals(GateVerdict.Refuse(Unavailable.PATH_NOT_ACTIVATED), gate(file))
    }

    /**
     * **The availability signal is asked per language, and asking it in aggregate is the way to get
     * this wrong without noticing.**
     *
     * Java's support is registered from the main descriptor on every IDE there is, so *is anything
     * registered?* comes back `yes` in exactly this configuration — the gate would offer every `.kt`
     * file, dispatch would find nothing that claims it, and the user would get a thrown error with a
     * report link instead of the sentence describing their configuration. That is the third outcome
     * quietly deleted, and every other assertion in this file still passes.
     *
     * The registration is read off [LanguageSupportBean.extension], which is a string in the
     * descriptor: reading it instantiates no implementation and therefore links no Kotlin class.
     */
    fun `test the availability signal is asked per language rather than in aggregate`() {
        assertTrue(
            "Nothing at all is registered here, so this fixture cannot tell an aggregate signal from a " +
                "per-language one and the assertion below holds for the wrong reason.",
            LANGUAGE_SUPPORT.extensionList.isNotEmpty(),
        )

        assertEquals(
            "A .kt file was offered while nothing was registered to build a plan for it.",
            GateVerdict.Refuse(Unavailable.PATH_NOT_ACTIVATED),
            gate(myFixture.configureByText("Payment.kt", "class Payment")),
        )
    }

    private companion object {

        /** Something recognisable to leave on the clipboard, so that *unchanged* is readable. */
        const val PREVIOUS_CLIPBOARD = "the text the user copied before reaching for SnippetVeil"
    }
}
