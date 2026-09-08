package com.snippetveil.plugin

import com.intellij.lang.java.beans.PropertyKind
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.JdkOrderEntry
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiLabeledStatement
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiPackage
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiQualifiedNamedElement
import com.intellij.psi.PsiRecordComponent
import com.intellij.psi.PsiTypeParameter
import com.intellij.psi.util.PropertyUtilBase
import com.intellij.psi.util.PsiUtilCore
import com.snippetveil.core.AccessorEvidence
import com.snippetveil.core.OverrideRoot
import com.snippetveil.core.SymbolEvidence
import com.snippetveil.core.SymbolOrigin
import com.snippetveil.core.SymbolRole

/**
 * **Everything the plan reports about one declared symbol except its key** — what it is in the
 * grammar, where its declaring file lives, which package it belongs to, what it overrides.
 *
 * Every function here answers a question about a `PsiElement`, and none of them is about a
 * *language*. That is why they sit beside the two walks rather than inside either: both describe the
 * same Java-shaped symbols, and a second copy of a description is a description that can drift.
 *
 * **Keys are not here, and that is the division worth knowing.** How a symbol is *spelled in the
 * ledger* is [SymbolKeys]' single rule for both languages — one object asked one question, so that a
 * Kotlin declaration and a Java reference to it cannot reach two entries. This file consumes that
 * rule and never restates it; where evidence carries a key, it comes from [SymbolKeys].
 *
 * **Language-neutral is a constraint, not a description.** This file is reachable from the main
 * plugin descriptor, so nothing in it may name `org.jetbrains.kotlin.*` — see
 * `ShippedCodeArchitectureTest`. Where a Kotlin-only fact is needed the caller supplies it: see the
 * ownership function [overrideRootsOf] takes.
 */
internal object SymbolFacts {

    /**
     * Everything known about the declared symbol [symbol], as the plan reports it.
     *
     * The tail both walks share. The Java walk arrives here having decided *which* element a token's
     * symbol is — a constructor's identifier names its class, a record accessor's names its component
     * — and the Kotlin walk arrives here for a Java declaration it reached from Kotlin. What is left
     * is the same description in both cases, and it is written once so that it cannot come out
     * different.
     *
     * @param declaredName the name to report when the element has none of its own, which is what a
     *   reverse mapping has to hand back
     */
    fun evidenceOf(project: Project, symbol: PsiElement, declaredName: String): SymbolEvidence = SymbolEvidence(
        key = SymbolKeys.keyOf(symbol),
        role = roleOf(symbol),
        origin = originOf(project, symbol),
        declaredName = (symbol as? PsiNameIdentifierOwner)?.name ?: declaredName,
        qualifiedName = (symbol as? PsiQualifiedNamedElement)?.qualifiedName,
        packageName = packageNameOf(symbol),
        signature = (symbol as? PsiMethod)?.let(::signatureOf),
        overrideRoots = (symbol as? PsiMethod)?.let { method -> overrideRootsOf(method) { originOf(project, it) } }.orEmpty(),
        accessor = (symbol as? PsiMethod)?.let(::accessorEvidenceOf),
        keyIsQualified = SymbolKeys.keyIsQualified(symbol),
    )

    /**
     * What is reported for a name that **resolved to nothing** — normal rather than exceptional, since
     * red or incomplete code resolves to nothing and the snippet a developer is debugging is the
     * likely one.
     *
     * Keyed on the text, which is the only thing there is to key an unresolved name on. Two distinct
     * symbols spelled alike therefore share a placeholder — the reverse mapping stays well-defined,
     * since `Unknown1` still stands for exactly one *name*, which is all a reader can be handed back
     * about a name that resolved to nothing.
     *
     * The role of a name that did not resolve is not knowable, and no rule reads this one: the engine
     * takes the namespace off the origin precisely because the role would be an invention. It is
     * filled in rather than made nullable so that every other role stays a fact.
     */
    fun unresolvedEvidence(writtenName: String): SymbolEvidence = SymbolEvidence(
        key = "unresolved:" + writtenName,
        role = SymbolRole.TYPE,
        origin = SymbolOrigin.UNRESOLVED,
        declaredName = writtenName,
    )

    /**
     * A method's parameter types, which is evidence and deliberately not part of its key: overloads
     * share a name in source, so they share a placeholder, and the engine collapses them by ignoring
     * this.
     */
    fun signatureOf(method: PsiMethod): String =
        method.parameterList.parameters.joinToString(",", "(", ")") { it.type.canonicalText }





    /**
     * What a symbol is. **Total, on purpose.**
     *
     * This used to return `null` for a shape it did not recognise, and the caller dropped the
     * occurrence — which copied the name through verbatim. That is precisely the fail-open this
     * action exists to prevent, and it was not hypothetical: a record component is a named,
     * project-owned symbol that is a `PsiVariable` and **not** a `PsiField`, so
     * `record Payment(String merchantRef)` put `merchantRef` on the clipboard intact.
     *
     * Hence the `else`, and hence that it conceals. **The role is cosmetic and a missing placeholder
     * is a leak** — the role only decides how cheaply a human maps the AI's reply back onto real
     * code, whereas a name that never became a placeholder is the whole failure. `FIELD` is the
     * fallback because every other named thing a `PsiIdentifier` resolves to in Java is a member of
     * a type, and `field1` is the least surprising way to read one.
     */
    fun roleOf(symbol: PsiElement): SymbolRole = when (symbol) {
        // Before PsiClass, which a type parameter is one of. Its own kind: `<T>` carries no domain
        // and `<REQ extends MerchantRequest>` does, and preserving by name length is inspecting the
        // text — so both are anonymized, and the prefix is what says which was which.
        is PsiTypeParameter -> SymbolRole.TYPE_PARAMETER
        // An annotation type before a plain one: `@interface` is a class declaration and reads as
        // nothing of the kind.
        is PsiClass -> if (symbol.isAnnotationType) SymbolRole.ANNOTATION else SymbolRole.TYPE

        // One segment of a package name. Never the whole name: the engine renames these one at a
        // time so that same-package and different-package survive the rename.
        is PsiPackage -> SymbolRole.PACKAGE

        // An annotation type's member is a method in the bytecode and an attribute everywhere it is
        // written, and what is written is what a reader has to map back. Asked of the declaring
        // type rather than of `PsiAnnotationMethod`, which every *compiled* method implements.
        is PsiMethod -> if (symbol.containingClass?.isAnnotationType == true) {
            SymbolRole.ATTRIBUTE
        } else {
            SymbolRole.METHOD
        }

        is PsiField -> SymbolRole.FIELD

        // A record component is compiled to a private final field of the same name, and Java forces
        // the component, that field and the accessor to agree. Naming it a field here is the reading
        // that stays true when the ticket for forced sharing collapses all three onto one symbol.
        is PsiRecordComponent -> SymbolRole.FIELD

        is PsiParameter -> SymbolRole.PARAMETER
        is PsiLocalVariable -> SymbolRole.LOCAL

        // A label is not a variable, and `break local7;` reads as though it were.
        is PsiLabeledStatement -> SymbolRole.LABEL
        else -> SymbolRole.FIELD
    }

    /**
     * **The spine rule's evidence: anonymize a symbol iff its declaring file is project-owned.**
     *
     * Ownership is a `VirtualFile` question, and the platform facts that decide how it is asked are
     * worth stating, because three of them are traps:
     *  - [PsiUtilCore.getVirtualFile] rather than `containingFile.virtualFile`, because it handles
     *    in-memory copies and returns `null` for light elements instead of throwing.
     *  - `isInContent`, never `isInSource` — the latter includes *library sources*, so an attached
     *    source jar would classify as project code.
     *  - **Never `instanceof PsiCompiledElement`.** An attached source jar makes a library symbol
     *    resolve to source PSI, so compiled-ness says nothing at all about who owns the code.
     *
     * Generated sources are content, and therefore project-owned, which is the right answer: they
     * are generated *from* the project's own domain. `isInContent` is project-wide, so a
     * multi-module project needs no rule of its own.
     */
    fun originOf(project: Project, symbol: PsiElement): SymbolOrigin {
        // A package declares nothing and lives in no file, so the question is asked of the
        // directories behind it instead. Left to fall through, the light-element branch below would
        // read the missing file as *fail closed* and rename `util` in `java.util.List`.
        if (symbol is PsiPackage) return packageOriginOf(project, symbol)

        // Project-owned by construction: none of these can be declared anywhere but in the file
        // under analysis, so there is no file to classify and no index to ask.
        if (symbol is PsiLocalVariable || symbol is PsiParameter ||
            symbol is PsiTypeParameter || symbol is PsiLabeledStatement
        ) {
            return SymbolOrigin.IN_CONTENT
        }

        // A light element — an IDE-synthesized member with no file of its own. Fail closed: it was
        // synthesized from something, and in this codebase's Java the something is a project class.
        return originOfFile(project, PsiUtilCore.getVirtualFile(symbol))
    }

    /**
     * Where [file] lives, as the three facts the spine rule is stated over — and
     * [SymbolOrigin.IN_CONTENT] for no file at all, which is the fail-closed direction.
     *
     * Split out of [originOf] rather than inlined into it because the Kotlin walk asks the same
     * question of a file it reached another way: a light element's ownership comes from the Kotlin
     * file behind it, and once that file is in hand the classification is this one, unchanged.
     */
    fun originOfFile(project: Project, file: VirtualFile?): SymbolOrigin {
        val virtualFile = file ?: return SymbolOrigin.IN_CONTENT

        val index = ProjectFileIndex.getInstance(project)
        return when {
            index.isInContent(virtualFile) -> SymbolOrigin.IN_CONTENT
            index.getOrderEntriesForFile(virtualFile).any { it is JdkOrderEntry } -> SymbolOrigin.JDK
            else -> SymbolOrigin.LIBRARY
        }
    }

    /**
     * Where a package lives, asked of **every directory the package has**, because that is the only
     * file-level thing a `PsiPackage` has: it is not declared anywhere, and
     * [PsiUtilCore.getVirtualFile] answers `null` for it.
     *
     * Any directory in project content makes the whole package the project's, and that is the
     * fail-closed direction rather than a tie-break. A package split across a source root and a jar
     * — the project's own `com.acme` alongside a shaded `com.acme` from a dependency — is a package
     * the project contributes domain-named subpackages and classes to, and the segment naming it is
     * the project's word.
     *
     * A package with no directories at all — a package prefix, and nothing else in practice — is
     * reported as the project's for the same reason: an unclassifiable name is not one the spine
     * rule may preserve. **Not [SymbolOrigin.UNRESOLVED]**, which would be a false claim rather than
     * a safe one: the reference resolved, and *the IDE could not resolve this* is a sentence the
     * balloon shows a user and the preview lets them act on.
     */
    fun packageOriginOf(project: Project, symbol: PsiPackage): SymbolOrigin {
        val index = ProjectFileIndex.getInstance(project)
        val directories = symbol.directories.map { it.virtualFile }
        return when {
            directories.any { index.isInContent(it) } -> SymbolOrigin.IN_CONTENT
            directories.isEmpty() -> SymbolOrigin.IN_CONTENT
            directories.any { file -> index.getOrderEntriesForFile(file).any { it is JdkOrderEntry } } ->
                SymbolOrigin.JDK
            else -> SymbolOrigin.LIBRARY
        }
    }

    /**
     * **The package a symbol belongs to** — `com.acme.billing` for the class
     * `com.acme.billing.Payment`, for its `merchantRef` field, and for the package segment written
     * `billing`, which belongs to itself.
     *
     * A fact about where the declaration sits, reported like every other fact: whether a package
     * prefix makes a library symbol the company's own is a policy, and it is applied in the engine
     * where a test can reach it without an IDE.
     *
     * Read off the **top-level** class's qualified name rather than off the symbol's own, because a
     * nested class's qualified name carries its owners — `com.acme.billing.Payment.Status` would
     * otherwise report a package of `com.acme.billing.Payment`, and a prefix rule reading it would
     * be matching a class name against a package one. The containing file is the fallback, which is
     * what answers for an anonymous or local class and for a member of one.
     */
    private fun packageNameOf(symbol: PsiElement): String? {
        // A package is not declared in a package; it *is* one. Every rule that reads this field is
        // asking which package a name is part of, and a package is part of itself.
        if (symbol is PsiPackage) return symbol.qualifiedName.takeIf { it.isNotEmpty() }

        val owner = symbol as? PsiClass ?: (symbol as? PsiMember)?.containingClass
        val topLevel = owner?.let { generateSequence(it) { nested -> nested.containingClass }.last() }
        topLevel?.qualifiedName?.substringBeforeLast('.', "")?.takeIf { it.isNotEmpty() }?.let { return it }

        return (symbol.containingFile as? PsiJavaFile)?.packageName?.takeIf { it.isNotEmpty() }
    }

    /**
     * The roots of [method]'s override chain: the methods it overrides that override nothing
     * themselves. Empty when it overrides nothing, which is most methods.
     *
     * `findDeepestSuperMethods()` rather than `findSuperMethods()`, because both rules that read
     * this are statements about the whole chain rather than about one link: a chain reaching a
     * framework type is name-constrained however many project classes sit between, and a chain keyed
     * by anything but its root splits an interface from its implementation.
     *
     * Reported as evidence and judged nowhere near here — the origins come back as the plain facts
     * they are, and whether a JDK root means *keep this name* is the engine's call.
     *
     * **[ownerOf] is handed in rather than called for**, because a root reached from a Kotlin walk
     * can be a light method whose ownership is read from its Kotlin origin rather than from a
     * virtual file — a question this file may not ask, since it is reachable from the main plugin
     * descriptor and may name no Kotlin type. Java hands in [originOf] and nothing changes for it.
     */
    fun overrideRootsOf(method: PsiMethod, ownerOf: (PsiElement) -> SymbolOrigin): List<OverrideRoot> =
        method.findDeepestSuperMethods().map {
            OverrideRoot(SymbolKeys.keyOf(it), ownerOf(it), packageNameOf(it), SymbolKeys.keyIsQualified(it))
        }

    /**
     * The field [method] reads or writes, when it is a JavaBeans accessor of one — and `null`
     * otherwise, which is the answer for the overwhelming majority of methods.
     *
     * **Matched on the name and the arity rather than on the body**, and that is the case this
     * exists for rather than a shortcut: with Lombok the accessor has no body, no declaration and no
     * `TextRange` at all. A body-reading match would find every accessor except the ones that need
     * it most.
     *
     * `getPropertyNameAndKind` is what keeps fluent accessors out. `merchantId()` is not a JavaBeans
     * accessor, so nothing is reported for it, and the engine leaves it an ordinary method —
     * deliberately, because nothing in Java forces a fluent accessor's name to track its field's.
     *
     * The field is found by [SymbolKeys.backingFieldOf], which is the one light-class call either
     * language makes on the way to a property's key — Java to key `getBar()` to the `bar` it reads,
     * Kotlin to key `val bar` to the field it compiles to. One call rather than two agreeing ones is
     * what makes a change in the platform's answer move both languages together; that function says
     * why, and why the lookup does not go up the hierarchy.
     */
    fun accessorEvidenceOf(method: PsiMethod): AccessorEvidence? {
        val owner = method.containingClass ?: return null
        val property = PropertyUtilBase.getPropertyNameAndKind(method.name) ?: return null
        val parameters = if (property.second == PropertyKind.SETTER) 1 else 0
        if (method.parameterList.parametersCount != parameters) return null

        val field = SymbolKeys.backingFieldOf(owner, property.first) ?: return null
        return AccessorEvidence(
            SymbolKeys.keyOf(field),
            field.name,
            property.second.prefix,
            SymbolKeys.keyIsQualified(field),
        )
    }
}
