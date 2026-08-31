package com.snippetveil.trust

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * The red paths of the two persistence rules, baked in rather than observed once.
 *
 * A rule that has never been seen to fail is one edit from proving nothing, so each rule here is
 * pointed at a class written to be flagged **and** at one written to look like a violation and not
 * be one. [PersistsCommentRetention] and [PersistsAPreserveList] must be flagged and
 * [PersistsAnIncrease] must not; [RoamsItsState] must be flagged and [KeepsItsStateLocal] must not.
 *
 * All five are test scope, so `SHIPPED_CLASSES` excludes them and none is registered as a service
 * anywhere: they are read as bytecode by a rule and never instantiated by the platform.
 */
@State(name = "SnippetVeilFixture", storages = [Storage("snippetveil-fixture.xml")])
internal class PersistsCommentRetention : PersistentStateComponent<PersistsCommentRetention.Settings> {

    /**
     * The shape the mistake would actually take. Nobody would put the flag on the component; it goes
     * on the serialized state class, which is a plain bean the platform reads by reflection — so a
     * rule that only looked at the component itself would miss every real version of this.
     */
    internal class Settings {
        var keepComments: Boolean = false
    }

    private var settings = Settings()

    override fun getState(): Settings = settings

    override fun loadState(state: Settings) {
        settings = state
    }
}

/**
 * The same mistake made with the other reduction: a preserve list written to disk.
 *
 * It is the shape the design has refused from the start — *a settings file that becomes a plaintext
 * domain glossary committed to the repo* — and since the override reached resolved names it is a
 * list that could hold every class in the codebase rather than the handful the IDE failed on.
 */
@State(name = "SnippetVeilPreserveFixture", storages = [Storage("snippetveil-fixture.xml")])
internal class PersistsAPreserveList : PersistentStateComponent<PersistsAPreserveList.Settings> {

    internal class Settings {
        var preservedSymbols: MutableList<String> = mutableListOf()
    }

    private var settings = Settings()

    override fun getState(): Settings = settings

    override fun loadState(state: Settings) {
        settings = state
    }
}

/**
 * A persistent setting that only ever **increases** anonymization, which the rule must leave alone.
 *
 * It is here because banning persistence outright would be the easy rule and the wrong one: the
 * spine rule permits a persistent setting, and forbids only a persistent *reduction*. A check that
 * failed on this would be the kind of noise that teaches people to suppress a check.
 */
@State(name = "SnippetVeilIncreaseFixture", storages = [Storage("snippetveil-fixture.xml")])
internal class PersistsAnIncrease : PersistentStateComponent<PersistsAnIncrease.Settings> {

    internal class Settings {
        var alwaysRedactNumbers: Boolean = false
    }

    private var settings = Settings()

    override fun getState(): Settings = settings

    override fun loadState(state: Settings) {
        settings = state
    }
}

/**
 * A `@State` that says nothing about roaming, which is how the mistake is actually made: leaving
 * [RoamingType] at its default puts the file on JetBrains' servers for anyone with settings sync on.
 *
 * The rule must flag it. A check that only caught someone writing `RoamingType.DEFAULT` out in full
 * would catch nobody, because nobody writes it.
 */
@State(name = "SnippetVeilRoamingFixture", storages = [Storage("snippetveil-fixture.xml")])
internal class RoamsItsState : PersistentStateComponent<RoamsItsState.Settings> {

    internal class Settings {
        var prefixes: MutableList<String> = mutableListOf()
    }

    private var settings = Settings()

    override fun getState(): Settings = settings

    override fun loadState(state: Settings) {
        settings = state
    }
}

/** Persistent state that stays on the machine, which the rule must leave alone. */
@State(
    name = "SnippetVeilLocalFixture",
    storages = [Storage("snippetveil-fixture.xml", roamingType = RoamingType.DISABLED)],
)
internal class KeepsItsStateLocal : PersistentStateComponent<KeepsItsStateLocal.Settings> {

    internal class Settings {
        var prefixes: MutableList<String> = mutableListOf()
    }

    private var settings = Settings()

    override fun getState(): Settings = settings

    override fun loadState(state: Settings) {
        settings = state
    }
}
