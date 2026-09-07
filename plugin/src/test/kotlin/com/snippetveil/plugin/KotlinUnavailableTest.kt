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
 * **Why this fires in this build at all.** The optional descriptor is empty on purpose — no Kotlin
 * plan builder exists yet — so nothing registers a support for `kt`, and every `.kt` file takes the
 * refusal. That is the structure being built here rather than a temporary state to work around: when
 * the Kotlin builder lands it registers, the same gate returns `Offer`, and these tests move to a
 * fixture that unregisters it. Until then this is the product's real behaviour on a `.kt` file, and
 * it is the behaviour a user on an IDE with Kotlin switched off will keep seeing afterwards.
 *
 * The fixture runs with the Kotlin plugin present and enabled, so the cause here is always
 * [Unavailable.PATH_NOT_ACTIVATED]. The other cause is asserted directly against the notification,
 * because an IDE with the Kotlin plugin switched off is an environment rather than an input.
 */
class KotlinUnavailableTest : JavaSnippetTestCase() {

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

    private companion object {

        /** Something recognisable to leave on the clipboard, so that *unchanged* is readable. */
        const val PREVIOUS_CLIPBOARD = "the text the user copied before reaching for SnippetVeil"
    }
}
