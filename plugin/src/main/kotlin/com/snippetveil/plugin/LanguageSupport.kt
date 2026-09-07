package com.snippetveil.plugin

import com.intellij.openapi.extensions.CustomLoadingExtensionPointBean
import com.intellij.psi.PsiFile
import com.intellij.util.xmlb.annotations.Attribute

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
 * **Presence is the availability signal.** The [gate]'s third outcome needs to know whether
 * *SnippetVeil's* Kotlin path loaded, which `PluginManagerCore` cannot answer — it knows only whether
 * the Kotlin *plugin* is running, and the two come apart in K1 mode. Reading whether a support is
 * registered **for that language** answers it exactly: registrations are [beans][LanguageSupportBean]
 * carrying the extension they handle, so the question is asked of an attribute and no implementation
 * is instantiated to answer it. Asking therefore costs no linkage.
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

/**
 * **The registration, which is a fact the gate can read without loading the class it names.**
 *
 * A bean rather than a bare `interface=` extension point, and the [extension] attribute is the whole
 * reason. The gate's question is not *is any language support registered?* — Java's always is, so
 * that question is answered `yes` on an IDE with Kotlin switched off, and the third outcome would be
 * unreachable. The question is *is *Kotlin's* registered?*, and this attribute is what makes it
 * askable.
 *
 * **Reading it instantiates nothing.** [implementationClass] stays a string until something asks for
 * [instance], so the gate learns that a Kotlin builder exists — or does not — without touching a
 * class whose constant pool names `org.jetbrains.kotlin.*`. That is the property the whole optional
 * dependency rests on, and it is why the availability signal is registration rather than a call.
 *
 * The extension is stated here rather than derived from the implementation, for the same reason the
 * gate tests a name: deriving it would mean asking the implementation, and asking is the linkage.
 */
internal class LanguageSupportBean : CustomLoadingExtensionPointBean<LanguageSupport>() {

    /**
     * The file extension this support handles, lower case, as the gate's closed set spells it —
     * `java` or `kt`.
     */
    @Attribute("extension")
    @JvmField
    var extension: String? = null

    @Attribute("implementationClass")
    @JvmField
    var implementationClass: String? = null

    override fun getImplementationClassName(): String? = implementationClass
}
