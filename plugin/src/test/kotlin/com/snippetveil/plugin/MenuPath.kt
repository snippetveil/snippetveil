package com.snippetveil.plugin

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.IdeActions
import org.junit.Assert

/**
 * **The menu path a sentence names, against the path the action actually has** — read off the
 * registration rather than matched as a substring. Two surfaces name the path, the first-run balloon
 * and the listing, and both are held to this: one derivation of the menu, read against each.
 *
 * A substring check cannot tell a true path from a false one: `"Copy Anonymized"` appears in a
 * sentence that sends the user to the right item and in one that sends them to the wrong menu, and
 * the sentence's own words are the only evidence either way. So the ancestry is derived — from the
 * editor popup down to the action, through the groups `plugin.xml` registers — and the sentence's
 * claim is checked against it. The editor popup, because right-click is the gesture both sentences
 * name.
 *
 * Two assertions, which together say the stated path *is* the registered one. They are kept apart
 * because they fail for different reasons and should say different things when they do:
 *
 *  1. **Every step the sentence names is real, and in order.** A sentence naming a menu item that
 *     does not exist, or naming them the wrong way round, fails here.
 *  2. **No step is missing.** A sentence that skips a level of the menu — the submenu, or one added
 *     above it later — fails here, naming the step it skipped.
 *
 * [surface] is what a failure calls the text. [text] is HTML, because both surfaces reach the user
 * as HTML, and the emphasis they render the steps in is taken off before the steps are compared.
 */
internal fun assertMenuPathIsReal(surface: String, text: String, actionId: String) {
    val ancestry = menuAncestryOf(actionId)
    val stated = menuPathStatedIn(surface, text)

    Assert.assertEquals(
        "$surface sends the user somewhere the action is not: it names $stated, and the item's own " +
            "ancestry under the editor popup is $ancestry.",
        stated,
        ancestry.filter { it in stated },
    )
    Assert.assertEquals(
        "$surface's menu path skips a level: it names $stated, and the item's own ancestry under the " +
            "editor popup is $ancestry. A user following it opens a menu without its next step in it.",
        emptyList<String>(),
        ancestry - stated.toSet(),
    )
}

/**
 * The menu steps [text] tells the user to take, in order — the names after the gesture, up to the
 * end of the sentence the gesture is in.
 *
 * The arrow is the separator the sentences themselves use, so this reads them the way a user does
 * rather than the way they were written. The sentence ends at its first full stop: no menu name here
 * contains one, and the ellipsis on `Anonymize with Preview…` is one character rather than three.
 *
 * **A text with no arrowed path after the gesture fails here, and says so**, rather than having the
 * rest of it read as one long menu step. *Right-click, **Copy Anonymized*** names a gesture and an
 * item with the submenu missing between them, and the useful thing to say about it is that it names
 * no path at all.
 */
private fun menuPathStatedIn(surface: String, text: String): List<String> {
    Assert.assertTrue(
        "$surface names no arrowed menu path after `right-click`, so there is no path in it to check.",
        RIGHT_CLICK in text,
    )
    return text
        .substringAfter(RIGHT_CLICK)
        .substringBefore(".")
        .split("\u2192")
        .map { it.replace(Regex("""<[^>]+>"""), "").trim() }
        .filter { it.isNotEmpty() }
}

/** The gesture, and the first arrow after it. */
private const val RIGHT_CLICK = "right-click \u2192"

/**
 * The menu names between the editor popup and [actionId], ending with the action's own — the
 * gesture written out, derived from what is registered.
 *
 * The walk descends only into this plugin's own groups. The platform's editor popup holds groups
 * belonging to every other plugin in the IDE, and asking one of those for its children outside a
 * real invocation is a question this test has no business asking; an action that had escaped the
 * submenu would still be found, because it would be a child of the popup itself.
 */
private fun menuAncestryOf(actionId: String): List<String> {
    val manager = ActionManager.getInstance()
    val popup = checkNotNull(manager.getAction(IdeActions.GROUP_EDITOR_POPUP) as? ActionGroup) {
        "The editor popup is not a registered action group, so no ancestry can be read from it."
    }

    fun descend(group: ActionGroup): List<String>? {
        for (child in group.getChildren(null)) {
            val id = manager.getId(child)
            if (id == actionId) return listOf(child.templatePresentation.text.orEmpty())
            if (child is ActionGroup && id.orEmpty().startsWith("SnippetVeil.")) {
                descend(child)?.let { return listOf(child.templatePresentation.text.orEmpty()) + it }
            }
        }
        return null
    }

    return checkNotNull(descend(popup)) {
        "$actionId is not reachable from the editor popup at all, so no gesture that starts with a " +
            "right-click names anything a user can do."
    }
}
