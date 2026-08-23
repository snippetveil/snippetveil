package com.snippetveil.plugin

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.snippetveil.core.InternalLibraries

/**
 * **Which library packages hold the company's own code — the one setting SnippetVeil persists.**
 *
 * The rule governing every setting this product will ever have is that *persistent settings may only
 * ever increase anonymization; any reduction is per-invocation and visible in the preview.* This one
 * survives that rule rather than being an exception to it: before the internal-library rule existed
 * every library symbol was preserved, and nothing that can be written here takes the output back
 * past that baseline. The removals only give back what the additions and the root-package heuristic
 * claimed. See [InternalLibraries] for the classification itself, which lives in `:core` where a
 * test can reach it without an IDE.
 *
 * ### Project-level, and that is not a default
 *
 * The list is inherently per-repository: a different employer means a different group id, and one
 * global list would carry the last employer's vocabulary into the next one's project. So it is a
 * project service, and the state goes in the project's own `.idea` rather than in the IDE config
 * directory.
 *
 * ### [RoamingType.DISABLED], and that is not a default either
 *
 * Settings sync would put this file on JetBrains' servers, and **the list itself literally is the
 * employer's group id** — the single most identifying string the product handles. Turning roaming
 * off makes *no `@State` in SnippetVeil is roamable* an absolute claim rather than a per-file
 * judgement, which is a claim a reader can check with one grep and one test
 * (`NoPersistentStateIsRoamableTest`).
 *
 * The settings page that edits it is [SnippetVeilConfigurable], and it reaches the state through
 * [configuration] rather than through [State]: the page is handed a value it can compare, and the
 * bean the platform serializes stays this class's own business.
 */
@Service(Service.Level.PROJECT)
@State(
    name = "SnippetVeilInternalLibraries",
    storages = [Storage("snippetveil.xml", roamingType = RoamingType.DISABLED)],
)
internal class InternalLibrarySettings : PersistentStateComponent<InternalLibrarySettings.State> {

    /**
     * The serialized bean, which is the shape the platform reads and writes by reflection.
     *
     * **[State.autoDetectRootPackage] is persisted, and that is worth defending rather than
     * assuming**, because switching it off is a reduction against what the plugin does out of the
     * box. It clears the rule under the same argument the removals clear it: the baseline is a spine
     * rule that preserved every library symbol, and off is exactly that baseline. It also adds no
     * capability the removals do not already have — a project can disable the heuristic today by
     * putting its own root package in [State.thirdPartyPrefixes], since a tie goes to the removal —
     * so this is a more direct spelling of a switch the ticket authorised, not a second knob.
     *
     * Mutable `var`s and a `MutableList` because that is what `XmlSerializer` requires, rather than
     * because anything here mutates them: [policy] copies the lists on the way out, so the engine
     * is handed the immutable value it expects and no caller can reach back through it.
     */
    class State {
        var autoDetectRootPackage: Boolean = true
        var internalPrefixes: MutableList<String> = mutableListOf()
        var thirdPartyPrefixes: MutableList<String> = mutableListOf()
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    /** What this project's list means to the engine, as the value type `:core` reads. */
    val policy: InternalLibraries
        get() = InternalLibraries(
            autoDetectRootPackage = state.autoDetectRootPackage,
            internalPrefixes = state.internalPrefixes.toSet(),
            thirdPartyPrefixes = state.thirdPartyPrefixes.toSet(),
        )

    /**
     * **What the settings page reads and writes** — the same three fields as [State], as a value
     * that can be compared.
     *
     * The page needs to answer *is this modified?* on every keystroke, and [State] is a mutable
     * bean with no equality: comparing two of them compares identities, which is always false and
     * would leave the `Apply` button permanently lit. Handing out a value closes that, and it keeps
     * the bean the platform serializes from being passed around as the product's edit model — the
     * setter below is the only writer, so a page that forgot to copy a list cannot reach back into
     * stored state through one it was handed.
     *
     * [policy] stays separate rather than being derived from this: it is what `:core` reads, in
     * `:core`'s own type, and the two would only look alike until the page grows a field the engine
     * does not have.
     */
    var configuration: PrefixConfiguration
        get() = PrefixConfiguration(
            autoDetectRootPackage = state.autoDetectRootPackage,
            internalPrefixes = state.internalPrefixes.toList(),
            thirdPartyPrefixes = state.thirdPartyPrefixes.toList(),
        )
        set(value) {
            state = State().also {
                it.autoDetectRootPackage = value.autoDetectRootPackage
                it.internalPrefixes = value.internalPrefixes.toMutableList()
                it.thirdPartyPrefixes = value.thirdPartyPrefixes.toMutableList()
            }
        }

    companion object {
        /** This project's list. A light service, so the platform creates it on first ask. */
        fun of(project: Project): InternalLibrarySettings = project.service()
    }
}

/**
 * **The one persistent knob and its prefix list, as one comparable value.**
 *
 * Ordered lists rather than the sets [InternalLibraries] holds, because this is what a table on a
 * settings page shows and a table has rows in an order the user put them in. The engine does not
 * care — [InternalLibrarySettings.policy] takes the sets — so the ordering is presentation, kept
 * where presentation is.
 *
 * @param autoDetectRootPackage the root-package heuristic, which is the knob
 * @param internalPrefixes prefixes the user added: library packages that are the company's own code
 * @param thirdPartyPrefixes prefixes the user took back: packages the heuristic claimed and should
 *   not have. A prefix in both lists is a removal, which is [InternalLibraries]' rule and not this
 *   type's — a value that resolved the tie here would be deciding classification on a settings page.
 */
internal data class PrefixConfiguration(
    val autoDetectRootPackage: Boolean = true,
    val internalPrefixes: List<String> = emptyList(),
    val thirdPartyPrefixes: List<String> = emptyList(),
)
