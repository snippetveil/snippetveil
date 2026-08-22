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

    /** No default shortcut: every combination worth having is taken, differently, in four keymaps. */
    fun `test the action ships no keyboard shortcut`() {
        assertEmpty(ActionManager.getInstance().getAction(COPY_ANONYMIZED).shortcutSet.shortcuts)
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
