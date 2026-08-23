package com.snippetveil.plugin

import com.intellij.notification.NotificationDisplayType
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer

/**
 * **Shown once, ever** — the property the whole first-run notice consists of.
 *
 * The surface is deliberately invisible: no toolbar button, no tool window, no default shortcut and
 * no menu entry outside a Java editor. This balloon is the entire mitigation for that, which puts it
 * in an awkward place — a notice that never fires leaves the plugin undiscoverable, and one that
 * fires twice is a plugin that nags. Both directions are tested here.
 */
class FirstRunNoticeTest : JavaSnippetTestCase() {

    /** The balloon, and what it says: an instruction, not a description. */
    fun `test the first run says what to do and offers the settings page`() {
        announceInstallation(project)

        val balloon = notifications.single()
        assertEquals("SnippetVeil is installed", balloon.title)
        assertTrue("the balloon does not say what to do: ${balloon.content}", "Copy Anonymized" in balloon.content)
        assertEquals(NotificationType.INFORMATION, balloon.type)
        assertEquals(listOf("Open settings"), balloon.actions.map { it.templatePresentation.text })
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
