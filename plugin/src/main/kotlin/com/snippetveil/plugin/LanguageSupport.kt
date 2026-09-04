package com.snippetveil.plugin

import com.intellij.psi.PsiFile

/**
 * **A language's half of the product, registered from wherever that language's classes are safe to
 * load.**
 *
 * Java's support is compiled into the main descriptor and is always present — `com.intellij.java` is
 * a required dependency, so Java support cannot fail to load and there is no configuration in which
 * a `.java` file is refused. Kotlin's is registered from the optional descriptor, which the platform
 * loads only where the Kotlin plugin is running and this plugin's K2 declaration was accepted.
 *
 * **This interface names no language type, and that is the point.** It is reachable from the main
 * descriptor, so a class it mentions must link on an IDE with Kotlin disabled. [PsiFile] and
 * [PlanBuilder] are both platform-or-ours; nothing here reaches `org.jetbrains.kotlin.*`.
 *
 * **Presence is the availability signal.** [SourceFileGate]'s third outcome needs to know whether
 * *SnippetVeil's* Kotlin path loaded, which `PluginManagerCore` cannot answer — it knows only whether
 * the Kotlin *plugin* is running, and the two come apart in K1 mode. Reading whether an extension is
 * registered answers it exactly, and reading presence does not instantiate the implementation, so
 * asking the question costs no linkage.
 *
 * ### Builder dispatch is layer two
 *
 * The gate decides on an extension because it must work where no Kotlin class can be touched. By the
 * time [claims] is called the language's own classes are loaded by definition — the implementation is
 * registered — so this layer is free to ask real PSI, and should: the extension got the invocation to
 * the door, and PSI decides what happens inside.
 */
internal interface LanguageSupport {

    /**
     * Whether this support handles [file], decided on **real PSI** rather than on the file's name.
     *
     * A file whose extension lies is the case this exists to catch: it reaches the action, because
     * the gate believed the name, and is turned away here instead — one layer later, and still
     * before anything is written.
     */
    fun claims(file: PsiFile): Boolean

    /** The walk that turns a file of this language into the pure description the engine works from. */
    fun planBuilder(): PlanBuilder
}
