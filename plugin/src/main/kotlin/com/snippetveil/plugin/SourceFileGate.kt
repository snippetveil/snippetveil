package com.snippetveil.plugin

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.extensions.PluginId
import com.intellij.psi.PsiFile

/**
 * **The source-file gate: what kind of file is this, and can we anonymize it here?**
 *
 * One gate, language-neutral: a third language is a row in [ACCEPTED] and a registration, rather
 * than a gate of its own. It is not free — the availability half below is written about Kotlin,
 * because Kotlin is the only language whose support can be absent — but the *classification* half is
 * the closed set and nothing else.
 * Per-language gates were rejected: a registry of gates that actions consult *is* the language-policy
 * seam, deferred until the first **non-JVM** language — it would arrive early, on the surface with
 * least to gain, paid for by a second language that shares the ledger, the key grammar and every
 * rule with the first.
 *
 * ### Three outcomes, not two
 *
 * | Outcome | When |
 * |---|---|
 * | [Offer] | a supported language, its support loaded |
 * | [Absent] | not a supported language at all — **no menu item** |
 * | [Refuse] | a supported language whose support is unavailable *in this IDE right now* |
 *
 * The distinguishing test is **would this file be anonymized on a correctly-configured IDE?** If yes
 * and it is not, say so. If no, stay silent. That is what keeps [Refuse] from rebuilding the dead
 * menu item [Absent] exists to avoid: a user never compares two similar-looking negatives, because
 * one of them is not there.
 *
 * ### Why the predicate is an extension and not a language
 *
 * **This is forced, not preferred.** [Refuse] has to be evaluable on a build where no Kotlin class
 * can be touched — the optional descriptor did not load, so `KotlinLanguage`, `KtFile` and everything
 * behind `org.jetbrains.kotlin` are absent from the classloader. That eliminates both obvious
 * predicates:
 *
 * - **PSI language** (`file.language is KotlinLanguage`) is unevaluable in exactly the case it is
 *   needed for. Asking *"is this Kotlin?"* with a Kotlin class is the linkage whose failure mode is
 *   *the plugin does not load*.
 * - **`FileType` / `FileTypeRegistry`** is no better. With the Kotlin plugin disabled, `.kt` has no
 *   registered Kotlin file type and comes back plain-text or unknown — so the gate would conclude
 *   *not a supported language* and fall into [Absent], which is precisely the false reassurance
 *   [Refuse] exists to prevent.
 *
 * **Accepted cost:** an extension is a weaker claim than a PSI type, so a file whose extension lies
 * reaches the action and is refused one layer later, by the builder rather than by the gate. That
 * fails closed — the clipboard is never written on a path that threw.
 *
 * ### Two layers, named separately
 *
 * This is layer one, and it decides **which of the three outcomes**. Layer two is builder dispatch:
 * once the action is running, the plan builder is chosen by real PSI. The extension got us to the
 * door; PSI decides what happens inside.
 *
 * ### What the gate does not answer
 *
 * Exactly one question — *what kind of file is this?* Ownership, keying, target platform and
 * resolvability are decided downstream, where the evidence for them exists. A file-type gate that
 * starts consulting module structure is a second classifier competing with the spine rule and the
 * key rule, and it will disagree with them. Three things fall out rather than being ruled on:
 *
 * - **Generated sources get no special case.** A pure extension test cannot consult
 *   `ProjectFileIndex` without ceasing to be one, so a generated `.java`/`.kt` is offered like any
 *   other file — and excluding generated code as *"not the user's"* is wrong in the dangerous
 *   direction, since kapt and KSP names are derived from the user's own (`Payment_Factory`), so
 *   preserving them prints the very identifier anonymization exists to remove, beside a renamed
 *   `Payment` in the same snippet. `isInGeneratedSources` is consulted nowhere in this product.
 * - **A `.kt` in a KMP native or JS module is offered**, and the gate never consults the module.
 *   Non-JVM modules are the handled case in the keying layer, not the broken one. Refusing would
 *   also be self-defeating: target platform is a Kotlin concept, so a module check drags Kotlin
 *   classes back into the gate.
 * - **Decompiled editors get nothing.** A decompiled class is backed by a `.class` virtual file,
 *   which is not in the accepted set — so no menu item appears, with no `isCompiled` check and no
 *   Kotlin type reference. This is a deliberate change: a decompiled Java file *is* a `PsiJavaFile`,
 *   so the previous PSI-typed gate offered the action there. **Library sources attached** are the
 *   opposite case and stay offered: navigation lands in a real `.java`/`.kt` inside a jar, every
 *   symbol is library-owned, and the result is a truthful `0 names replaced`.
 */
internal sealed interface GateVerdict {

    /** A supported language whose support is loaded. The menu item appears and works. */
    data object Offer : GateVerdict

    /** Not a supported language. **No menu item at all** — silence is the stronger signal. */
    data object Absent : GateVerdict

    /**
     * A supported language whose support is unavailable here. The item is **present and enabled**,
     * and refuses when invoked.
     *
     * **Disabled-but-visible was rejected** because it is silent on the invoke path that matters: a
     * user with a rebound shortcut presses the key, nothing happens, and their next paste is the
     * previous clipboard — the product's highest-severity failure reproduced exactly, and here the
     * user's expectation was *correct*.
     */
    data class Refuse(val cause: Unavailable) : GateVerdict
}

/**
 * **Why a supported language is not available**, and the two answers differ in what the user has to
 * do about it — which is the whole reason they are told apart.
 *
 * Both are decided without touching a Kotlin class, which is not a nicety: the case only exists when
 * Kotlin classes are absent from the classloader.
 */
internal enum class Unavailable {

    /**
     * The Kotlin plugin is not installed, or is installed and disabled. Nothing Kotlin-owned exists
     * to point the user at, so the fix opens the **Plugins** page — a platform configurable, and the
     * only correct target when a Kotlin-owned configurable id does not exist.
     */
    PLUGIN_NOT_RUNNING,

    /**
     * The Kotlin plugin is running, and SnippetVeil's Kotlin path still did not activate. In
     * practice that means **K1 mode**: this plugin declares `supportsK1="false"`, so the platform
     * skips the optional descriptor with a log warning and nothing on screen.
     *
     * The fix opens **Settings → Languages & Frameworks → Kotlin**, whose configurable id exists
     * precisely because the plugin *is* enabled.
     */
    PATH_NOT_ACTIVATED,
}

/**
 * The accepted extension set, and it is exactly two.
 *
 * A closed set rather than a registry: adding a language is a row here plus a plan builder, and
 * nothing consults a per-language gate to find out what is supported.
 */
private const val JAVA = "java"
private const val KOTLIN = "kt"

/**
 * **`.kts` is not in the set, and is refused silently.**
 *
 * Three findings, in the order they bite. A script's actual secrets — internal repo URLs, group
 * coordinates, module names — sit **in strings** rather than in project symbols, so even in the good
 * case the tool would do very little while implying it had done a lot. A script's top-level
 * declarations carry script-derived qualified names (`Build_gradle.foo`), which become **persisted
 * ledger keys derived from a file name** — rename the script and every symbol in it quietly acquires
 * a second placeholder, permanently, because the ledger is append-only. And a `build.gradle.kts`
 * outside content would preserve everything and copy out essentially unchanged.
 *
 * Refused **silently** rather than with a stated reason: under the distinguishing test a `.kts` would
 * not be anonymized on a correctly-configured IDE either, so a message here would rebuild the dead
 * menu item [GateVerdict.Absent] exists to avoid.
 *
 * **Known, accepted surprise:** the listing says *Kotlin*, and `.kts` is Kotlin, so a user
 * right-clicking in `build.gradle.kts` finds nothing. That is handled in the copy, not in the gate.
 */
private val ACCEPTED = setOf(JAVA, KOTLIN)

/**
 * The extension point each language's half registers itself through, and the reason the gate can tell
 * [Unavailable.PLUGIN_NOT_RUNNING] from [Unavailable.PATH_NOT_ACTIVATED] at all.
 *
 * `PluginManagerCore` answers whether the *Kotlin plugin* is running. It cannot answer whether
 * *SnippetVeil's* Kotlin path loaded, and those come apart in K1 mode. Presence of a registration
 * here is the second fact, and reading presence does not instantiate the implementation — so the gate
 * learns that a Kotlin builder exists without loading a class that names a Kotlin type.
 *
 * **Presence is read per language, not in aggregate.** Java's support is registered from the main
 * descriptor and can never be absent, so *is anything registered?* is answered `yes` on an IDE with
 * Kotlin switched off — it is the one question that cannot distinguish the case this exists for.
 * That is what [LanguageSupportBean.extension] is for, and why the extension point takes a bean.
 */
internal val LANGUAGE_SUPPORT = ExtensionPointName<LanguageSupportBean>("com.snippetveil.languageSupport")

/** The Kotlin plugin's id, as a plain string — naming it costs nothing and links nothing. */
private const val KOTLIN_PLUGIN_ID = "org.jetbrains.kotlin"

/**
 * The gate, over a file.
 *
 * The extension is read from the virtual file where there is one, and from the file's own name
 * otherwise — both are text, and neither consults a language.
 */
internal fun gate(file: PsiFile?): GateVerdict {
    val extension = file?.extensionOf() ?: return GateVerdict.Absent
    if (extension !in ACCEPTED) return GateVerdict.Absent
    if (extension == JAVA) return GateVerdict.Offer

    // Kotlin: supported, so the only question left is whether its support is here.
    return if (supportIsRegisteredFor(KOTLIN)) GateVerdict.Offer
    else GateVerdict.Refuse(whyKotlinIsMissing())
}

/**
 * Whether a language support is registered **for this extension** — the availability half of the
 * gate, and the only part of it that can come back differently on two IDEs.
 *
 * Asked of the registration's [attribute][LanguageSupportBean.extension] rather than of the
 * implementation, because the implementation is the class that may not link. Compared as text and
 * case-insensitively, for the same reason the file's own extension is.
 */
private fun supportIsRegisteredFor(extension: String): Boolean =
    LANGUAGE_SUPPORT.extensionList.any { it.extension.equals(extension, ignoreCase = true) }

/**
 * Lower-cased, because an extension is compared as text and `Foo.JAVA` is a Java file on a
 * case-insensitive filesystem.
 */
private fun PsiFile.extensionOf(): String =
    (virtualFile?.extension ?: name.substringAfterLast('.', "")).lowercase()

/**
 * The two causes, told apart by the one platform API that can answer without a Kotlin class.
 *
 * The order matters: *not running* is the answer that makes *did not activate* impossible, so it is
 * asked first and the second is what remains.
 *
 * *Running* is read as **loaded**, which is the question with an unambiguous answer: a plugin that is
 * not installed and one that is installed and switched off are the same problem to the user and take
 * the same fix, and the loaded set is where both are absent.
 */
private fun whyKotlinIsMissing(): Unavailable {
    val kotlin = PluginId.getId(KOTLIN_PLUGIN_ID)
    return if (PluginManagerCore.loadedPlugins.none { it.pluginId == kotlin }) Unavailable.PLUGIN_NOT_RUNNING
    else Unavailable.PATH_NOT_ACTIVATED
}
