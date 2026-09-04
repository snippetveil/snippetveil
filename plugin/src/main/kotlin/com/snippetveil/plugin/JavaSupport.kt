package com.snippetveil.plugin

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.snippetveil.core.SnippetPlan

/**
 * **Java's half, registered from the main descriptor because it can never be absent.**
 *
 * `com.intellij.java` is a required dependency, so there is no configuration in which this class is
 * missing and no configuration in which a `.java` file is refused — which is why the gate's third
 * outcome is unreachable for Java and exists only for Kotlin.
 *
 * This is layer two. The gate believed a file name; [claims] asks the tree.
 */
internal class JavaSupport : LanguageSupport {

    /**
     * A Java source file, and nothing else — **not** a decompiled `.class`, which is also a
     * [PsiJavaFile], read-only, and owned by whoever shipped the jar.
     *
     * The gate already turned that away on its extension, so this is the two layers agreeing rather
     * than one of them carrying the decision alone. They are kept as two because they fail
     * differently: the gate is a name and can be lied to, and this is the tree and cannot.
     */
    override fun claims(file: PsiFile): Boolean =
        file is PsiJavaFile && file.fileType == JavaFileType.INSTANCE

    override fun planBuilder(): PlanBuilder = JavaPlanBuilder
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

    override fun build(request: SnippetRequest): SnippetPlan {
        val support = LANGUAGE_SUPPORT.extensionList.firstOrNull { it.claims(request.file) }
            ?: throw IllegalStateException(
                "No SnippetVeil language support claims ${request.file.name}; " +
                    "the source-file gate accepted its extension and its PSI is something else."
            )
        return support.planBuilder().build(request)
    }
}
