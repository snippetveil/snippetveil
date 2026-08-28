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
 * the same submenu, so the assertion that matters is not *both contain these four items* — two
 * copies of a group would satisfy that and then drift — but *both contain the same group*.
 */
class ActionRegistrationTest : BasePlatformTestCase() {

    /**
     * The submenu is one group, and it is in both menus.
     *
     * Asserted by identity rather than by contents: two separately-registered groups holding the
     * same references would pass a contents check on the day it was written and diverge the first
     * time somebody added an action to one of them.
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
     * **All four items, in the order the workflow runs in**: anonymize, anonymize with a look first,
     * and the two ways back — the reversal, then the reversal that also lands it.
     *
     * The pasting variant sits directly after the reversal rather than at the end for a reason the
     * order carries on its own: they are one operation with two destinations, and a row between them
     * would read as a fourth stage of the workflow instead of a second delivery of the third.
     */
    fun `test the submenu holds the four actions in workflow order`() {
        assertEquals(
            listOf(COPY_ANONYMIZED, ANONYMIZE_WITH_PREVIEW, DEANONYMIZE, DEANONYMIZE_AND_PASTE),
            childIdsOf(MENU),
        )

        val manager = ActionManager.getInstance()
        assertEquals("Copy Anonymized", manager.getAction(COPY_ANONYMIZED).templatePresentation.text)
        assertEquals("Anonymize with Preview\u2026", manager.getAction(ANONYMIZE_WITH_PREVIEW).templatePresentation.text)
        assertEquals("De-anonymize Clipboard", manager.getAction(DEANONYMIZE).templatePresentation.text)
        assertEquals("De-anonymize Clipboard and Paste", manager.getAction(DEANONYMIZE_AND_PASTE).templatePresentation.text)
    }

    /**
     * **Nothing of SnippetVeil's sits flat in the editor popup**, and that is the half of the
     * placement decision a contents check would miss.
     *
     * The submenu exists precisely so that an ungated item — `De-anonymize Clipboard` is offered on
     * every file type, and `De-anonymize Clipboard and Paste` on every writable one — does not
     * appear in every editor popup in the IDE. A later `add-to-group` that put any of the four back
     * at the top level would undo that silently, because the menu would still *work*; it would just
     * be bigger everywhere.
     */
    fun `test no SnippetVeil action sits flat in the editor popup`() {
        val popup = childIdsOf(IdeActions.GROUP_EDITOR_POPUP)

        assertEquals(
            "a SnippetVeil action was added to the editor popup directly, outside the submenu",
            emptyList<String>(),
            popup.filter { it.startsWith("SnippetVeil.") && it != MENU },
        )
    }

    /**
     * **These five ids are every action this plugin registers** — which is how *"`Export Mapping…` is
     * reachable from the preview and from nowhere else"* is checked rather than asserted.
     *
     * The export is a button on the preview dialog and deliberately not a menu item: a Tools-menu
     * entry has no invocation in front of it, so the only thing it could mean is *dump the whole
     * stored mapping* — the complete plaintext domain glossary of the codebase, written wherever a
     * chooser last pointed and outside the storage location this product chose so carefully to keep
     * it out of git and out of cloud sync. That is refused, and a refusal nothing checks is a
     * refusal that lasts until the next contributor reaches for the obvious placement.
     *
     * It carries a second refusal for the same reason. **`Reset Mappings…` is a button on the
     * settings page and not an action**: *explicit* is better served by a button standing next to the
     * numbers a person needs in order to decide than by a menu item one row from `Copy Anonymized`,
     * and the accepted cost — not reachable from Find Action — was taken deliberately. An id
     * acquired here is how that would be undone.
     *
     * Asserted over every id in the namespace rather than over the submenu's children, because an
     * action registered outside the submenu is exactly the shape that would slip past a check on the
     * submenu.
     */
    fun `test the plugin registers these actions and no others`() {
        assertEquals(
            listOf(ANONYMIZE_WITH_PREVIEW, COPY_ANONYMIZED, DEANONYMIZE, DEANONYMIZE_AND_PASTE, MENU),
            ActionManager.getInstance().getActionIdList("SnippetVeil").sorted(),
        )
    }

    /** No default shortcut: every combination worth having is taken, differently, in four keymaps. */
    fun `test no action ships a keyboard shortcut`() {
        val manager = ActionManager.getInstance()
        assertEmpty(manager.getAction(COPY_ANONYMIZED).shortcutSet.shortcuts)
        assertEmpty(manager.getAction(ANONYMIZE_WITH_PREVIEW).shortcutSet.shortcuts)
        assertEmpty(manager.getAction(DEANONYMIZE).shortcutSet.shortcuts)

        // The one most likely to acquire one: a single-gesture paste is exactly the item somebody
        // reaches for a binding on, and the decision not to ship one is not reopened by a new row.
        assertEmpty(manager.getAction(DEANONYMIZE_AND_PASTE).shortcutSet.shortcuts)
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

private const val DEANONYMIZE_AND_PASTE = "SnippetVeil.DeanonymizeClipboardAndPaste"
