package com.snippetveil.trust

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * The red path of [CommentRetentionIsNeverPersistedTest], baked in rather than observed once.
 *
 * The rule it proves is *persistent settings may only ever increase anonymization; any reduction is
 * per-invocation and visible in the preview* — and today **this repository ships no persistent state
 * at all**, so the rule has nothing of its own to be right about. A rule that matches nothing passes,
 * and a rule that has never been seen to fail is one edit from proving nothing. These two give it
 * something on each side of the line.
 *
 * [PersistsCommentRetention] must be flagged and [PersistsAnIncrease] must not. Both are test scope,
 * so `SHIPPED_CLASSES` excludes them and neither is registered as a service anywhere: they are read
 * as bytecode by a rule and never instantiated by the platform.
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
