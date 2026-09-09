package com.snippetveil.plugin

import com.intellij.notification.NotificationDisplayType
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.components.Service
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer

/**
 * **Shown once, ever** — the property the whole first-run notice consists of.
 *
 * The surface is deliberately invisible: no toolbar button, no tool window, no default shortcut and
 * no menu entry outside a source file this plugin anonymizes. This balloon is the entire mitigation
 * for that, which puts it in an awkward place — a notice that never fires leaves the plugin
 * undiscoverable, and one that fires twice is a plugin that nags. Both directions are tested here.
 */
class FirstRunNoticeTest : JavaSnippetTestCase() {

    /**
     * The balloon, and what it says: an instruction, not a description.
     *
     * **The sentence is asserted whole**, character for character. It is the only thing this plugin
     * ever says unprompted and it names two languages, a gesture and a menu item — each of which is a
     * claim that can quietly stop being true — so a check for a word it happens to contain is not a
     * check on the sentence. The one that shipped matched `"Copy Anonymized"`, which the half that
     * never changes satisfies on its own.
     */
    fun `test the first run says what to do and offers the settings page`() {
        announceInstallation(project)

        val balloon = notifications.single()
        assertEquals("SnippetVeil is installed", balloon.title)
        assertEquals(
            "Select Java or Kotlin code, then right-click \u2192 <b>Copy Anonymized</b>.",
            balloon.content,
        )
        assertEquals(NotificationType.INFORMATION, balloon.type)
        assertEquals(listOf("Open settings"), balloon.actions.map { it.templatePresentation.text })
    }

    /**
     * **The path the balloon names, against the path the action actually has** — read off the
     * registration rather than matched as a substring.
     *
     * A substring check cannot tell a true path from a false one: `"Copy Anonymized"` appears in a
     * sentence that sends the user to the right item and in one that sends them to the wrong menu,
     * and the sentence's own words are the only evidence either way. So the ancestry is derived —
     * from the editor popup down to the action, through the groups `plugin.xml` registers — and the
     * balloon's claim is checked against it.
     *
     * **The claim is currently a strict subset of the truth, and that is a known defect this test
     * pins rather than fixes.** The four items live in a `SnippetVeil` submenu, so the real gesture is
     * right-click → **SnippetVeil** → **Copy Anonymized**, and the balloon names only the last step.
     * It is a false statement the product makes about its own menu; it is false in pure Java, has
     * nothing to do with which languages are supported, and correcting it is a change with its own
     * reasoning rather than a rider on a language edit.
     *
     * Two assertions, and the second is the one that will go red when it is fixed — deliberately, so
     * that the fix arrives here and updates the sentence this test pins, instead of leaving a second
     * copy of the old claim behind:
     *
     *  1. **Every step the balloon names is real, and in order.** A sentence naming a menu item that
     *     does not exist, or naming them the wrong way round, fails here.
     *  2. **Exactly one step is missing, and it is the submenu.** A balloon that dropped a second
     *     step, or that lost the item itself, is a different sentence from the one on record.
     */
    fun `test the balloon's menu path is a real path to the action`() {
        announceInstallation(project)

        val ancestry = menuAncestryOf(COPY_ANONYMIZED)
        val stated = pathStatedIn(notifications.single().content)

        assertEquals(
            "The balloon sends the user somewhere the action is not: it names $stated, and the item's " +
                "own ancestry under the editor popup is $ancestry.",
            stated,
            ancestry.filter { it in stated },
        )
        assertEquals(
            "The balloon's menu path is no longer the known-incomplete one on record. If the submenu " +
                "step has been added, this test and the sentence it pins above are what say so.",
            listOf(SUBMENU),
            ancestry - stated.toSet(),
        )
    }

    /**
     * The menu steps [content] tells the user to take, in order — the names after the gesture, with
     * the emphasis the balloon renders them in taken off.
     *
     * The arrow is the separator the sentence itself uses, so this reads the sentence the way a user
     * does rather than the way it was written.
     */
    private fun pathStatedIn(content: String): List<String> = content
        .substringAfter("right-click \u2192")
        .split("\u2192")
        .map { it.replace(Regex("""<[^>]+>"""), "").trim().removeSuffix(".") }
        .filter { it.isNotEmpty() }

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
            "$actionId is not reachable from the editor popup at all, so the balloon's gesture names " +
                "nothing a user can do."
        }
    }

    /**
     * **A balloon that waits to be dismissed rather than one that fades**, and in a group the user
     * can switch off.
     *
     * The two properties are the whole of *dismissible* as it matters here. It is a `BALLOON` in
     * SnippetVeil's own notification group, so a user who does not want this plugin's balloons turns
     * off exactly these and nothing else — and closing it is a click on the balloon's own control,
     * which is the platform's, not ours.
     *
     * It stays until then rather than fading, which is what an action-carrying notification does by
     * default and is asserted here because it is *load-bearing* rather than incidental: this one
     * balloon is the entire mitigation for a surface that is otherwise invisible, and one that faded
     * while the user was looking at the file they just opened would have mitigated nothing.
     */
    fun `test the balloon waits to be dismissed and is in a group of its own`() {
        announceInstallation(project)

        assertTrue(
            "the first-run notice fades on its own, and it is the only thing that makes the plugin discoverable",
            notifications.single().isImportant,
        )
        assertEquals(
            NotificationDisplayType.BALLOON,
            NotificationGroupManager.getInstance().getNotificationGroup("SnippetVeil").displayType,
        )
    }

    /**
     * **And never again.** Not *not again this session* — the record is written to disk, so the
     * second ask here stands for every project opened on this machine from now on.
     */
    fun `test it is never shown a second time`() {
        announceInstallation(project)
        announceInstallation(project)
        announceInstallation(project)

        assertEquals("the first-run notice fired more than once", 1, notifications.size)
    }

    /**
     * The record survives a restart, which is what makes *once ever* mean what it says. Through
     * `XmlSerializer`, because that is the machinery `@State` itself uses: a hand-written check of
     * the bean would prove that the field exists rather than that the platform can write and read it.
     */
    fun `test the record survives being written out and read back`() {
        FirstRunNotice.getInstance().claim()

        val restarted = FirstRunNotice().also {
            it.loadState(
                XmlSerializer.deserialize(
                    XmlSerializer.serialize(FirstRunNotice.getInstance().state),
                    FirstRunNotice.State::class.java,
                ),
            )
        }

        assertFalse("a restart would say it again", restarted.claim())
    }

    /**
     * **The claim is one step**, because two projects opening at once is the ordinary case on IDE
     * startup: a *has it been shown* to read and a *mark it shown* to write would put the balloon on
     * screen twice on exactly the machines that keep several projects open.
     */
    fun `test the record is claimed exactly once`() {
        val notice = FirstRunNotice()

        assertTrue(notice.claim())
        assertFalse(notice.claim())
    }

    /** Application-level, because *once ever* is a fact about the installation and not about a project. */
    fun `test the record is application-level`() {
        assertEquals(
            listOf(Service.Level.APP),
            FirstRunNotice::class.java.getAnnotation(Service::class.java).value.toList(),
        )
    }

    /**
     * **It is asked for on project open**, and this is what says the registration in `plugin.xml` is
     * still there and still names this class. A notice nothing invokes is the failure mode with no
     * symptom: the plugin simply stays undiscovered.
     *
     * **Read off the descriptor rather than off the loaded extension point**, which is the opposite
     * of what `ActionRegistrationTest` does and is not a preference: enumerating
     * `com.intellij.postStartupActivity` instantiates every activity every loaded plugin registers,
     * and the bundled Java plugin registers one of the legacy type — so the platform's own type check
     * fails the enumeration before it reaches ours. What is left to check is the pair this test does
     * check: the descriptor names a class, and the name it spells is this class's real one, so a
     * rename that misses the XML goes red here.
     */
    fun `test the announcement is registered as a startup activity`() {
        val descriptor = checkNotNull(javaClass.classLoader.getResourceAsStream("META-INF/plugin.xml")) {
            "the plugin descriptor is not on the test classpath, so nothing here is being checked"
        }.use { JDOMUtil.load(it) }

        val registered = descriptor.getChildren("extensions")
            .flatMap { it.getChildren("postStartupActivity") }
            .mapNotNull { it.getAttributeValue("implementation") }

        assertEquals(
            "the first-run announcement is not the one activity this plugin registers",
            listOf(FirstRunAnnouncement::class.java.name),
            registered,
        )
    }
}

/** The item the balloon names, by the id `plugin.xml` registers it under. */
private const val COPY_ANONYMIZED = "SnippetVeil.CopyAnonymized"

/** The submenu the balloon does not name, by the text a user reads on it. */
private const val SUBMENU = "SnippetVeil"
