package com.snippetveil.plugin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * **The one time SnippetVeil speaks without being asked** — and the record that keeps it to once.
 *
 * The surface is deliberately invisible: no toolbar button, no tool window, no default keyboard
 * shortcut, and no menu entry outside a Java editor. Each of those is right on its own, and together
 * they make **install-and-never-notice** the realistic failure mode — a plugin that is present,
 * working, and never found. This is the whole of the mitigation: one balloon, on the first project
 * opened after installing, dismissible like any other.
 *
 * ### What was refused, and why it is a release constraint rather than a preference
 *
 * **A post-install web page is ruled out**, which also means the Marketplace listing must not be
 * configured with one. A user who installs a plugin whose proposition is *it makes no network
 * connections* and immediately watches their IDE fetch a page has been handed a counterexample on
 * day one. That the request comes from the platform rather than from this code does not help: the
 * claim is built on evidence a sceptic can check, and this hands that person a strike.
 *
 * **Tip of the Day** was skipped for a different reason — it is cheap and it fires long after *"what
 * was that thing I installed?"* has passed.
 */
@Service(Service.Level.APP)
@State(
    name = "SnippetVeilFirstRun",
    storages = [Storage("snippetveil-first-run.xml", roamingType = RoamingType.DISABLED)],
)
internal class FirstRunNotice : PersistentStateComponent<FirstRunNotice.State> {

    /**
     * The serialized bean, which is the shape the platform reads and writes by reflection.
     *
     * **It is not a setting, and nothing here is a reduction.** It records that a sentence was said
     * once; no value it can take changes what the anonymizer does, which is what keeps it clear of
     * the rule that persistent settings may only ever increase anonymization.
     */
    class State {
        var noticeShown: Boolean = false
    }

    @Volatile
    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    /**
     * **True exactly once in the life of an installation**, for the caller that is going to say the
     * sentence.
     *
     * It marks and answers in one step, rather than offering a *has it been shown* to read and a
     * *mark it shown* to write: two projects opening at once is the ordinary case on IDE startup,
     * and a read-then-write pair would show the balloon twice on exactly the machines that have
     * several projects open — which is to say, on the developer machines this ships to.
     *
     * **The stated limit**: this is remembered by being written to disk, and the platform decides
     * when that happens. An IDE that is killed between the first project opening and its next save
     * will say it again. That is the cheap direction of a failure whose expensive direction —
     * *never shown* — is the thing the notice exists to prevent.
     */
    @Synchronized
    fun claim(): Boolean {
        if (state.noticeShown) return false
        state = State().also { it.noticeShown = true }
        return true
    }

    companion object {
        /** The one record. A light service, so the platform creates it on first ask. */
        fun getInstance(): FirstRunNotice = ApplicationManager.getApplication().service()
    }
}

/**
 * Says it, if it has not been said.
 *
 * A plain function rather than a method on the activity below, so that *shown once, ever* is
 * observable without a coroutine and without the platform's startup machinery in the way — the
 * property under test is the claim, and the activity is only when it is asked for.
 */
internal fun announceInstallation(project: Project) {
    if (FirstRunNotice.getInstance().claim()) SnippetVeilNotifications.installed(project)
}

/**
 * The moment it is asked: a project finishing opening.
 *
 * **Project open rather than application start**, because the balloon names an action a user takes
 * in an editor and points at a project-level settings page; on a startup with no project there is
 * nowhere for either to land. The record it consults is application-level, so the *first* project to
 * open is the only one that says anything, however many follow it.
 */
internal class FirstRunAnnouncement : ProjectActivity {
    override suspend fun execute(project: Project) = announceInstallation(project)
}
