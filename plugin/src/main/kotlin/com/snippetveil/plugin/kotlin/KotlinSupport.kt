package com.snippetveil.plugin.kotlin

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.compiled.ClsFileImpl
import com.intellij.psi.impl.java.stubs.PsiClassStub
import com.snippetveil.plugin.LanguageSupport
import com.snippetveil.plugin.PlanBuilder
import com.snippetveil.plugin.TraceFile
import org.jetbrains.kotlin.asJava.findFacadeClass
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

    /**
     * **The file facade**, with the resolved file's real extension — because Kotlin does not tie a
     * file's name to a class.
     *
     * `Billing.kt` holding `fun settle` and `class Payment` renders `BillingKt`'s placeholder beside a
     * frame of either: rendering the frame's own class instead would give one real file two rendered
     * names, and would make a `Utils.kt` holding `class Payment` look as if it were named after the
     * class. The facade is an ordinary project class with a `Type` placeholder, so this adds no new
     * symbol kind. **A `.kt` with no top-level callable has no facade light class**, and its frames
     * render a bare `Unknown` file name beside a correctly resolved class — truthful, and visibly
     * degraded. `KotlinSnippetTestCase.assertTheFacadeBehaviourIsPinned` holds the platform to that.
     *
     * **A compiled Kotlin class names the source its bytecode records**, since a library `.class`
     * carries no file facade to find — `kotlinx.coroutines.intrinsics.CancellableKt` was compiled from
     * `Cancellable.kt`, and a class and its file's facade are two `.class` files that nothing links.
     * It is fixed to the frame's own class there, which is a library's and is kept as printed.
     */
    override fun traceFileOf(outer: PsiClass): TraceFile? {
        val file = outer.navigationElement.containingFile as? KtFile ?: return null
        if (!file.isCompiled) {
            val virtualFile = file.virtualFile ?: return null
            return TraceFile(file.findFacadeClass(), virtualFile.nameWithoutExtension, virtualFile.extension)
        }
        val recorded = recordedSourceOf(outer) ?: return null
        return TraceFile(outer, recorded.substringBeforeLast('.'), recorded.substringAfterLast('.', "").ifEmpty { null })
    }

    /**
     * The source file name [compiled]'s bytecode records — its `SourceFile` attribute, read the way
     * the platform reads it for a Java `.class` — or `null` where it records none.
     */
    private fun recordedSourceOf(compiled: PsiClass): String? {
        val classFile = compiled.containingFile?.virtualFile ?: return null
        val stub = ClsFileImpl.buildFileStub(classFile, classFile.contentsToByteArray()) ?: return null
        return stub.childrenStubs.filterIsInstance<PsiClassStub<*>>().firstOrNull()?.sourceFileName
    }
}
