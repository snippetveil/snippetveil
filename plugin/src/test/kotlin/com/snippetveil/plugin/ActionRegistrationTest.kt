package com.snippetveil.plugin

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Where the action appears, checked against a loaded descriptor rather than read off the XML.
 *
 * `plugin.xml` anchors the item relative to a platform group by id, and a group id is exactly the
 * sort of thing that gets renamed between IDE releases: the platform drops an `add-to-group` whose
 * anchor it cannot resolve, so a stale id ships an action that is registered and invisible. That
 * failure is silent everywhere except here — and this build runs its tests against two IDE versions
 * four years apart, which is the point of testing it at all.
 */
/**
 * **Where the actions appear, checked against a loaded descriptor rather than read off the XML.**
 *
 * `plugin.xml` anchors the submenu relative to a platform group by id, and a group id is exactly the
 * sort of thing that gets renamed between IDE releases: the platform drops an `add-to-group` whose
 * anchor it cannot resolve, so a stale id ships a menu that is registered and invisible. That
 * failure is silent everywhere except here — and this build runs its tests against two IDE versions
 * four years apart, which is the point of testing it at all.
 *
 * **One group with two parents is the thing under test.** The Tools menu and the editor popup show
 * the same submenu, so the assertion that matters is not *both contain these three items* — two
 * copies of a group would satisfy that and then drift — but *both contain the same group*.
 */
class ActionRegistrationTest : BasePlatformTestCase() {

    /**
     * The submenu is one group, and it is in both menus.
     *
     * Asserted by identity rather than by contents: two separately-registered groups holding the
     * same three references would pass a contents check on the day it was written and diverge the
     * first time somebody added a fourth action to one of them.
     */
    fun `test one SnippetVeil group is registered into both the Tools menu and the editor popup`() {
        val manager = ActionManager.getInstance()
        val group = manager.getAction(MENU)
        assertNotNull("The SnippetVeil submenu is not registered at all.", group)
        assertTrue("The SnippetVeil entry is not a submenu.", (group as ActionGroup).isPopup)

        assertTrue(
            "The submenu is missing from the Tools menu; its anchor was probably renamed.",
            MENU in childIdsOf(TOOLS_MENU),
        )
        assertTrue(
            "The submenu is missing from the editor popup; its anchor was probably renamed.",
            MENU in childIdsOf(IdeActions.GROUP_EDITOR_POPUP),
        )
    }

    /**
     * **All three items, in the order the workflow runs in**: anonymize, anonymize with a look
     * first, and the way back.
     */
    fun `test the submenu holds the three actions in workflow order`() {
        assertEquals(
            listOf(COPY_ANONYMIZED, ANONYMIZE_WITH_PREVIEW, DEANONYMIZE),
            childIdsOf(MENU),
        )

        val manager = ActionManager.getInstance()
        assertEquals("Copy Anonymized", manager.getAction(COPY_ANONYMIZED).templatePresentation.text)
        assertEquals("Anonymize with Preview\u2026", manager.getAction(ANONYMIZE_WITH_PREVIEW).templatePresentation.text)
        assertEquals("De-anonymize Clipboard", manager.getAction(DEANONYMIZE).templatePresentation.text)
    }

    /**
     * **Nothing of SnippetVeil's sits flat in the editor popup**, and that is the half of the
     * placement decision a contents check would miss.
     *
     * The submenu exists precisely so that an ungated item — `De-anonymize Clipboard` is offered on
     * every file type — does not appear in every editor popup in the IDE. A later `add-to-group`
     * that put any of the three back at the top level would undo that silently, because the menu
     * would still *work*; it would just be bigger everywhere.
     */
    fun `test no SnippetVeil action sits flat in the editor popup`() {
        val popup = childIdsOf(IdeActions.GROUP_EDITOR_POPUP)

        assertEquals(
            "a SnippetVeil action was added to the editor popup directly, outside the submenu",
            emptyList<String>(),
            popup.filter { it.startsWith("SnippetVeil.") && it != MENU },
        )
    }

    /** No default shortcut: every combination worth having is taken, differently, in four keymaps. */
    fun `test no action ships a keyboard shortcut`() {
        val manager = ActionManager.getInstance()
        assertEmpty(manager.getAction(COPY_ANONYMIZED).shortcutSet.shortcuts)
        assertEmpty(manager.getAction(ANONYMIZE_WITH_PREVIEW).shortcutSet.shortcuts)
        assertEmpty(manager.getAction(DEANONYMIZE).shortcutSet.shortcuts)
    }

    private fun childIdsOf(groupId: String): List<String> {
        val manager = ActionManager.getInstance()
        val group = checkNotNull(manager.getAction(groupId) as? ActionGroup) {
            "$groupId is not a registered action group."
        }
        return group.getChildren(null).mapNotNull { manager.getId(it) }
    }
}

/** The platform's own Tools menu group id, which has no `IdeActions` constant. */
private const val TOOLS_MENU = "ToolsMenu"

private const val MENU = "SnippetVeil.Menu"

private const val COPY_ANONYMIZED = "SnippetVeil.CopyAnonymized"

private const val ANONYMIZE_WITH_PREVIEW = "SnippetVeil.AnonymizeWithPreview"

private const val DEANONYMIZE = "SnippetVeil.DeanonymizeClipboard"
