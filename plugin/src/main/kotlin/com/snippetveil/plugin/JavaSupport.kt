package com.snippetveil.plugin

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.lang.java.JavaLanguage
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiModifier

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

    /**
     * **The public top-level class the file is named after**, with the real extension of the
     * resolved file — or no class at all where the file is fixed to none, such as a package-private
     * class alone in a file its name is not.
     *
     * The class's navigation element rather than the class, so that a library class with sources
     * attached names its `.java` rather than the `.class` it was compiled to. A class the JVM loaded
     * from Kotlin is not Java's, whatever file PSI it wears, and is left to Kotlin.
     */
    override fun traceFileOf(outer: PsiClass): TraceFile? {
        if (outer.language != JavaLanguage.INSTANCE) return null
        val source = (outer.navigationElement as? PsiClass) ?: outer
        val file = source.containingFile as? PsiJavaFile ?: return null
        val virtualFile = file.virtualFile ?: return null
        val stem = virtualFile.nameWithoutExtension
        val fixedTo = file.classes.firstOrNull { it.hasModifierProperty(PsiModifier.PUBLIC) && it.name == stem }
        return TraceFile(fixedTo, stem, virtualFile.extension)
    }
}
