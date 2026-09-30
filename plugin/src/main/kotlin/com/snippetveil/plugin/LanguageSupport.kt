package com.snippetveil.plugin

import com.intellij.openapi.extensions.CustomLoadingExtensionPointBean
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiFile
import com.snippetveil.core.SnippetPlan
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
 * *SnippetVeil's* Kotlin path loaded, which the plugin manager cannot answer — it knows only whether
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

    /**
     * **The file a stack-trace frame names, as this language ties a file's name to a symbol** — for
     * [outer], the top-level class a frame resolved to — or `null` where [outer] is not this
     * language's to answer for.
     *
     * Asked of every class a trace resolves, a library's and the JDK's included, and not only of
     * the source files [claims] would take: a frame's file name is printed whoever compiled it.
     * Which symbol the name is fixed to is the language's rule and not the trace's — a public class
     * named after the file in Java, the file facade in Kotlin — so it is answered here, beside the
     * rest of what the language decides.
     */
    fun traceFileOf(outer: PsiClass): TraceFile?
}

/**
 * **The file a frame was compiled from, and the symbol its name is fixed to.**
 *
 * @param fixedTo the class whose placeholder the file name renders, or `null` where the language
 *   ties the file's name to none — which renders a bare `Unknown`
 * @param stem the file's name without its extension: what the trace has to have printed for this to
 *   be the file it names
 * @param extension the extension the rendered name carries, read off the file rather than assumed —
 *   or `null` for none
 */
internal class TraceFile(val fixedTo: PsiClass?, val stem: String, val extension: String?) {

    /**
     * Whether [printed] — a file name as a frame printed it — names this file, **extension aside**:
     * a compiled library class resolves to its `.class`, and the trace prints the source it was
     * compiled from. Cut at the last `.`, so `Builders.common.kt` is the file `Builders.common`.
     */
    fun isNamedBy(printed: String): Boolean = printed.substringBeforeLast('.') == stem

    /** What the rendered name ends in: the extension with its `.`, or nothing. */
    val suffix: String get() = extension?.let { ".$it" }.orEmpty()

    companion object {

        /** The file called [name] — a stem and whatever follows its last `.` — fixed to [fixedTo]. */
        fun named(name: String, fixedTo: PsiClass?): TraceFile =
            TraceFile(fixedTo, name.substringBeforeLast('.'), name.substringAfterLast('.', "").ifEmpty { null })
    }
}

/**
 * **The file a frame of [outer] names**, answered by whichever registered language [outer] is — or
 * `null` where none is, which renders a bare `Unknown`.
 *
 * A Kotlin light class is Kotlin's to answer for, and where SnippetVeil's Kotlin path is not
 * registered nothing answers for it: that fails closed, into the bare `Unknown`.
 */
internal fun traceFileOf(outer: PsiClass): TraceFile? =
    LANGUAGE_SUPPORT.extensionList.asSequence()
        .map { it.instance }
        .firstNotNullOfOrNull { it.traceFileOf(outer) }

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

/**
 * **Layer two, as a [PlanBuilder]** — the builder chosen by real PSI, once the action is already
 * running and the language's classes are loaded by definition.
 *
 * A file that reached here and is claimed by nothing **throws**, and that is the designed answer
 * rather than a gap. It means the gate believed an extension that lied, and the fail-closed
 * guarantee takes it from there: the clipboard is left byte-identical, the ledger uncommitted, and
 * the user is told the operation failed rather than handed a file that was copied unchanged.
 */
internal object DispatchingPlanBuilder : PlanBuilder {

    /**
     * The registrations are walked as a sequence so that each implementation is instantiated only
     * until one claims the file. Instantiating a *registered* support is safe by construction — a
     * language's classes are on the classloader exactly when its registration is — so this is about
     * doing no work rather than about avoiding linkage; the gate is where linkage is avoided.
     */
    override fun build(request: SnippetRequest): SnippetPlan {
        val support = LANGUAGE_SUPPORT.extensionList.asSequence()
            .map { it.instance }
            .firstOrNull { it.claims(request.file) }
            ?: throw IllegalStateException(
                "No SnippetVeil language support claims ${request.file.name}; " +
                    "the source-file gate accepted its extension and its PSI is something else."
            )
        return support.planBuilder().build(request)
    }
}
