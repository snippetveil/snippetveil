package com.snippetveil.plugin.kotlin

import com.intellij.psi.PsiFile
import com.snippetveil.plugin.LanguageSupport
import com.snippetveil.plugin.PlanBuilder
import org.jetbrains.kotlin.psi.KtFile

/**
 * **Kotlin's half, registered from the optional descriptor because it can be absent.**
 *
 * `com.snippetveil-withKotlin.xml` is loaded exactly where the Kotlin plugin is running and this
 * plugin's K2 declaration was accepted, so this registration is present in that configuration and in
 * no other — which is what the gate reads to tell *offer* from *refuse*. Java's counterpart is
 * registered from the main descriptor and can never be absent; the asymmetry is the whole of why the
 * gate has a third outcome at all.
 *
 * **This class is why the registration is a bean.** Its constant pool names `org.jetbrains.kotlin.*`,
 * so loading it on an IDE with the Kotlin plugin switched off would fail to link — and the gate
 * therefore asks the registration's `extension` attribute rather than asking this class anything.
 * Reading an attribute instantiates nothing, which is what lets the availability question be asked in
 * the one configuration where the answer is *no*.
 *
 * This is layer two. The gate believed a file name; [claims] asks the tree.
 */
internal class KotlinSupport : LanguageSupport {

    /**
     * A Kotlin source file, and neither of the two other things a [KtFile] can be.
     *
     * A **script** is a `KtFile` and `build.gradle.kts` is the file a user is most likely to try
     * this on. The gate already refuses `.kts` silently — a script's secrets sit in strings, and its
     * top-level declarations would key the ledger off a file name — so claiming one here would
     * reopen that decision from the layer that is supposed to agree with it.
     *
     * A **decompiled** class is a `KtFile` too, read-only and owned by whoever shipped the jar; the
     * gate turned it away on its `.class` extension, and `isCompiled` is the tree's own answer to
     * the same question. Both layers are kept because they fail differently: the gate reads a name
     * and can be lied to, and this reads the tree and cannot.
     */
    override fun claims(file: PsiFile): Boolean = file is KtFile && !file.isScript() && !file.isCompiled

    override fun planBuilder(): PlanBuilder = KotlinPlanBuilder
}
