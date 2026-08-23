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
class ActionRegistrationTest : BasePlatformTestCase() {

    fun `test Copy Anonymized sits in the editor popup and under Tools`() {
        val action = ActionManager.getInstance().getAction(COPY_ANONYMIZED)
        assertNotNull("The action is not registered at all.", action)
        assertEquals("Copy Anonymized", action.templatePresentation.text)

        assertTrue(
            "The action is missing from the editor popup; its anchor group was probably renamed.",
            COPY_ANONYMIZED in childIdsOf(IdeActions.GROUP_EDITOR_POPUP),
        )
        assertTrue(
            "The action is missing from Tools > SnippetVeil.",
            COPY_ANONYMIZED in childIdsOf("SnippetVeil.ToolsMenu"),
        )
    }

    /**
     * **The preview sits beside the fast path, not inside it.** Two flat items rather than one that
     * always asks: preview-first taxes the common case to protect against a risk that does not exist
     * on that path, and a submenu would bury the item that is the only place a reduction lives.
     */
    fun `test Anonymize with Preview sits beside it in both places`() {
        val action = ActionManager.getInstance().getAction(ANONYMIZE_WITH_PREVIEW)
        assertNotNull("The preview action is not registered at all.", action)
        assertEquals("Anonymize with Preview\u2026", action.templatePresentation.text)

        val popup = childIdsOf(IdeActions.GROUP_EDITOR_POPUP)
        assertTrue(
            "The preview is missing from the editor popup; its anchor was probably renamed.",
            ANONYMIZE_WITH_PREVIEW in popup,
        )
        assertEquals(
            "The preview does not sit directly after the action it is the alternative to.",
            popup.indexOf(COPY_ANONYMIZED) + 1,
            popup.indexOf(ANONYMIZE_WITH_PREVIEW),
        )
        assertTrue(
            "The preview is missing from Tools > SnippetVeil.",
            ANONYMIZE_WITH_PREVIEW in childIdsOf("SnippetVeil.ToolsMenu"),
        )
    }

    /**
     * **The reversal is under Tools and nowhere else, and that absence is the assertion.**
     *
     * It is invoked *after* pasting a reply — into a scratch buffer, a Markdown file, a commit
     * message — so it is available on every file type, and an item that is available everywhere and
     * reads no source is exactly the item that must not be in the editor popup. Both halves are
     * checked, because *present under Tools* alone is satisfied by an action that is also in every
     * popup in the IDE.
     */
    fun `test De-anonymize Clipboard sits under Tools and not in the editor popup`() {
        val action = ActionManager.getInstance().getAction(DEANONYMIZE)
        assertNotNull("The reversal action is not registered at all.", action)
        assertEquals("De-anonymize Clipboard", action.templatePresentation.text)

        assertTrue(
            "The reversal is missing from Tools > SnippetVeil.",
            DEANONYMIZE in childIdsOf("SnippetVeil.ToolsMenu"),
        )
        assertFalse(
            "The reversal reached the editor popup, where it would sit on every file in the IDE.",
            DEANONYMIZE in childIdsOf(IdeActions.GROUP_EDITOR_POPUP),
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

private const val COPY_ANONYMIZED = "SnippetVeil.CopyAnonymized"

private const val ANONYMIZE_WITH_PREVIEW = "SnippetVeil.AnonymizeWithPreview"

private const val DEANONYMIZE = "SnippetVeil.DeanonymizeClipboard"
