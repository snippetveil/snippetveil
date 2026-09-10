package com.snippetveil.sweep

import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaRecursiveElementWalkingVisitor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiLabeledStatement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFileAnnotationList
import org.jetbrains.kotlin.psi.KtLabeledExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/**
 * **The declaration walk, built without the anonymiser's own walk** — over Java and Kotlin sources
 * alike.
 *
 * A second, independent reading of the same files: this one visits **declarations** and reads their
 * text, where the plan builders walk **references** and resolve them. Nothing here consults a plan, a
 * mapping or a result, and nothing is resolved — not a reference, not a light class, not an analysis
 * session — which is the property the whole instrument rests on. `LeakUniverseIndependenceTest` holds
 * it over this class's bytecode.
 *
 * Over-inclusive on purpose. A name collected here that the anonymiser was never going to touch costs
 * a human a minute; a name missed costs the product its core promise.
 *
 * **Both languages, because the two halves cannot land apart.** Reading `.kt` files without closing
 * over sibling spellings still misses every name Kotlin writes that its declarations do not, and
 * closing over sibling spellings while reading only `.java` files derives almost nothing new. Each
 * [Declaration] this returns carries exactly the text [SourceSpellings] needs to derive the rest.
 */
internal object SourceDeclarations {

    /**
     * What the walk read out of the files it was given.
     *
     * @param declarations every declaration in them
     * @param javaFiles how many were read as Java
     * @param kotlinFiles how many were read as Kotlin — counted as read rather than as found, because
     *   a `.kt` file the IDE will not hand over as Kotlin is a file whose names never reached the
     *   universe, and a count of files found would report it as covered
     */
    class Reading(val declarations: List<Declaration>, val javaFiles: Int, val kotlinFiles: Int)

    /**
     * Every declaration in [files]: Java and Kotlin source files, and nothing else — a script and a
     * decompiled class are both a `KtFile`, and neither is the project's source.
     */
    fun of(project: Project, files: List<VirtualFile>): Reading {
        val manager = PsiManager.getInstance(project)
        var javaFiles = 0
        var kotlinFiles = 0
        val declarations = files.flatMap { file ->
            when (val psi = manager.findFile(file)) {
                is PsiJavaFile -> javaDeclarationsIn(psi).also { javaFiles++ }
                is KtFile -> if (psi.isScript() || psi.isCompiled) {
                    emptyList()
                } else {
                    kotlinDeclarationsIn(psi, moduleOf(project, file)).also { kotlinFiles++ }
                }
                else -> emptyList()
            }
        }
        return Reading(declarations, javaFiles, kotlinFiles)
    }

    /**
     * Every [PsiNameIdentifierOwner] is a declaration in Java's grammar — classes, methods, fields,
     * parameters, locals, type parameters, record components — and a label is added explicitly rather
     * than relied upon, because whether a labelled statement implements that interface is a platform
     * detail and not a thing this file should have an opinion about. A method is recorded as one,
     * because Kotlin writes a getter-shaped method as a property.
     */
    private fun javaDeclarationsIn(file: PsiJavaFile): List<Declaration> {
        val found = packageSegmentsOf(file.packageName).toMutableList()
        file.accept(object : JavaRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                when (element) {
                    is PsiMethod -> found += Declaration.JavaMethod(element.name)
                    is PsiNameIdentifierOwner -> element.name?.let { found += Declaration.Written(it) }
                }
                if (element is PsiLabeledStatement) found += Declaration.Written(element.labelIdentifier.text)
                super.visitElement(element)
            }
        })
        return found
    }

    /**
     * Every named declaration's **name identifier**, as written — never `name`, which the platform
     * fills in for declarations the source does not name. An unnamed companion is `Companion` to the
     * platform and nothing to its source text, and the language's `it` and `component1` are declared
     * nowhere at all; reading identifiers is what keeps all three out.
     *
     * @param module the IDE's name for the module holding the file — the one fact an `internal`
     *   spelling needs that is not declaration text
     */
    private fun kotlinDeclarationsIn(file: KtFile, module: String?): List<Declaration> {
        val found = packageSegmentsOf(file.packageDirective?.qualifiedName.orEmpty()).toMutableList()

        // A facade exists exactly where a file has a top-level function or property, and its name is
        // the file's, or the `@file:JvmName` that replaces it.
        if (file.declarations.any { it is KtNamedFunction || it is KtProperty }) {
            found += Declaration.KotlinFacade(file.name, jvmNameIn(file.fileAnnotationList?.annotationEntries.orEmpty()))
        }

        file.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                when (element) {
                    is KtNamedDeclaration -> kotlinDeclarationOf(element, module)?.let { found += it }
                    // Named, and not a `KtNamedDeclaration`: an import alias is the case a Kotlin file
                    // has — `import com.acme.billing.Payment as Pay` — and `Pay` is a spelling the file
                    // writes for a declaration, which is what the closure's rule counts.
                    is PsiNameIdentifierOwner ->
                        element.nameIdentifier?.text?.removeSurrounding("`")?.let { found += Declaration.Written(it) }
                }
                if (element is KtLabeledExpression) element.getLabelName()?.let { found += Declaration.Written(it) }
                if (element is KtAnnotationEntry && element.parent !is KtFileAnnotationList) {
                    jvmNameIn(listOf(element))?.let { name ->
                        val owner = PsiTreeUtil.getParentOfType(element, KtNamedDeclaration::class.java)
                        found += Declaration.JvmName(name, of = owner?.nameIdentifier?.text.orEmpty())
                    }
                }
                super.visitElement(element)
            }
        })
        return found
    }

    /** The row [declaration] belongs to, or `null` for one its source text does not name. */
    private fun kotlinDeclarationOf(declaration: KtNamedDeclaration, module: String?): Declaration? {
        val name = declaration.nameIdentifier?.text?.removeSurrounding("`") ?: return null
        val internalIn = module.takeIf { declaration.hasModifier(KtTokens.INTERNAL_KEYWORD) }
        return when {
            declaration is KtNamedFunction -> Declaration.KotlinFunction(name, internalIn)
            declaration is KtProperty && !declaration.isLocal -> Declaration.KotlinProperty(name, declaration.isVar, internalIn)
            declaration is KtParameter && declaration.hasValOrVar() -> Declaration.KotlinProperty(
                name,
                mutable = declaration.valOrVarKeyword?.node?.elementType == KtTokens.VAR_KEYWORD,
                internalIn = internalIn,
            )
            declaration is KtObjectDeclaration && !declaration.isCompanion() && !declaration.isObjectLiteral() ->
                Declaration.KotlinObject(name)
            else -> Declaration.Written(name)
        }
    }

    /**
     * The name a `@JvmName` among [entries] gives, read off the annotation's text — its short name
     * and its string argument, and nothing resolved to decide either. An interpolated argument is not
     * a name anybody can read off the page, and is skipped — and so is a constant, which would have to
     * be resolved to be read. Both are stated as blind spots in CONTRIBUTING.md's known limits.
     */
    private fun jvmNameIn(entries: List<KtAnnotationEntry>): String? {
        val entry = entries.firstOrNull { it.shortName?.asString() == "JvmName" } ?: return null
        val argument = entry.valueArguments.firstOrNull()?.getArgumentExpression() as? KtStringTemplateExpression ?: return null
        if (argument.hasInterpolation()) return null
        return argument.entries.joinToString("") { it.text }.takeIf { it.isNotEmpty() }
    }

    /**
     * A package statement declares every segment of its own name — **the first one included**. The
     * engine passes that segment through by a positional rule, so it will be reported in every file
     * the sweep touches; it is a known false positive a human adjudicates, and not a name this walk
     * gets to drop. See [LeakOracle.over].
     */
    private fun packageSegmentsOf(packageName: String): List<Declaration> =
        packageName.takeIf { it.isNotEmpty() }?.split('.').orEmpty().map(Declaration::Written)

    private fun moduleOf(project: Project, file: VirtualFile): String? = ModuleUtilCore.findModuleForFile(file, project)?.name
}
