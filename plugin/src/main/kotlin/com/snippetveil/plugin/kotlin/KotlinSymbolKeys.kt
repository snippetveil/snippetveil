package com.snippetveil.plugin.kotlin

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.snippetveil.plugin.SymbolKeys
import org.jetbrains.kotlin.asJava.findFacadeClass
import org.jetbrains.kotlin.asJava.getRepresentativeLightMethod
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.fileClasses.JvmFileClassUtil
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtClassLikeDeclaration
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtParameterList
import org.jetbrains.kotlin.psi.KtPrimaryConstructor
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTypeParameter

/**
 * **The ledger key of a Kotlin declaration.**
 *
 * One rule, and it is not a fork:
 *
 * > The key of a Kotlin declaration is the key its light element would receive from
 * > [SymbolKeys.keyOf]. Where no light element exists, the key comes from the native `ClassId` /
 * > `CallableId`, spelled in [SymbolKeys]' own grammar.
 *
 * **The Java path does not change** — not its keys, not what it persists. Kotlin conforms to Java;
 * Java never learns about Kotlin. That is the acceptance criterion behind all the others: a ledger
 * already on a developer's disk gains Kotlin with **no migration**, because no key it holds is
 * spelled differently afterwards.
 *
 * The agreement is structural rather than maintained. It is the *same function* asked the *same
 * question* — [SymbolKeys.keyOf] over a light `PsiMethod` or `PsiField`, which is what a Java file
 * referring to that declaration would have reached anyway — and where the two languages must find a
 * property's backing field, it is literally [SymbolKeys.backingFieldOf], one call. Nothing here
 * recomputes a Java answer; it either hands Java PSI to the Java function or spells a native id in
 * the Java grammar.
 *
 * **Where a classifier is concerned the two coincide by construction.** `ClassId.asFqNameString()`
 * is character-for-character the Java qualified name, so a class, object, companion, nested type or
 * typealias is keyed natively with no normalisation step at all — and `KotlinSymbolKeyingTest`
 * asserts the native key **equal** to the one the same declaration's light class receives, rather
 * than trusting the coincidence.
 *
 * ### What diverges, stated rather than discovered
 *
 * - **`@JvmName` and `internal` manglings take the light element's name unmodified.**
 *   `demangleInternalName` exists and is deliberately not used: the shipped `keyOf` reads
 *   `PsiMethod.getName()`, and that is what a light method returns, so demangling would be a
 *   divergence from a key the Java builder already writes. The cost is taken explicitly — **the
 *   Gradle module name sits inside the key** of an `internal` member, so a module rename breaks
 *   placeholder continuity for it, visibly and for a reason no Java symbol has.
 * - **A top-level callable is owned by its file's facade**, so an `expect`/`actual` pair split
 *   across two differently-named files reaches two keys. Visible as two placeholders, never a leak.
 * - **An `actual` carrying `@JvmName` or `internal` splits from its `expect`**, which keys natively
 *   and therefore unmangled. `getExpectsForActual` is rejected as the fix: it is experimental API,
 *   documented to return multiple symbols under ambiguity, and bought for a case that
 *   Kotlin-Multiplatform-with-Java-interop already makes narrow. A recorded gap is more honest than
 *   silent machinery.
 *
 * **Injectivity holds in both directions, and they fail differently.** *Two symbols → one key* is
 * impossible by the compiler: two callables in one class sharing a JVM name and erased signature are
 * a platform declaration clash, which Kotlin refuses to compile — and where a key collapses
 * deliberately, as overloads sharing `method:class:p.A#g` do, that is the existing *signature is
 * evidence, not key* rule, which `CallableId` encodes identically. *One symbol → two keys* is
 * impossible by the partition: a callable with no Java-visible spelling is one no Java file can
 * name, so no Java token can ever key to it.
 *
 * **This class lives below `com.snippetveil.plugin` on purpose.** It names `org.jetbrains.kotlin.*`,
 * so it may only be loaded where the Kotlin plugin is; the package boundary is the mechanism, and
 * `ShippedCodeArchitectureTest` holds it over bytecode.
 */
internal object KotlinSymbolKeys {

    /**
     * [declaration]'s key, and whether it may be written down.
     *
     * The order of the branches is the rule, not a convenience. An enum entry is a `KtClass` in the
     * grammar and a field on the JVM, and Java keys it as the field — so it is answered before the
     * classifier branch that would otherwise key it as a class it never is. A constructor is
     * answered before the callable branch for the same kind of reason: the Java walk resolves a
     * constructor's identifier to its class, so `Ledger()` and `Ledger` reach one key there, and
     * keying a `KtConstructor` to its light constructor would put a second key on a symbol Java
     * already spells one way.
     */
    fun ledgerKeyOf(declaration: KtNamedDeclaration): LedgerKey = when (declaration) {
        is KtEnumEntry -> enumEntryKeyOf(declaration)
        is KtConstructor<*> -> classifierKeyOf(declaration.getContainingClassOrObject())
        is KtClassLikeDeclaration -> classifierKeyOf(declaration)

        // A type parameter is a `PsiTypeParameter` on the Java side, which is a `PsiClass` with no
        // qualified name — so it is an anchored classifier there, and it is one here.
        is KtTypeParameter -> anchoredClassifierKeyOf(declaration)

        is KtCallableDeclaration -> callableKeyOf(declaration)

        // A destructuring entry, and anything else named that is not a declaration either language
        // can refer to from elsewhere. Keyed on where it is written, and never written down.
        else -> LedgerKey(SymbolKeys.localKeyOf(declaration), keyIsQualified = false)
    }

    /**
     * **Whether a `CallableId` names a position stable enough to persist** — which takes *both*
     * halves, and that is the whole point of the function.
     *
     * The sources disagree about what a local callable's id even is. `CallableId`'s own
     * documentation gives `<local>/loc` as one — non-null, and not stable — while a survey of the API
     * recorded `callableId` as `null` for locals. Requiring only non-null would persist a key that
     * re-points at a different symbol on the next edit, which is precisely the failure structural
     * path keys were rejected over; requiring only `!isLocal` would dereference `null`.
     *
     * **The classifier test is deliberately not this one.** A classifier is local iff its `ClassId`
     * is `null`; `ClassId.isLocal` carries `@ClassIdBasedLocality` and is documented as discouraged
     * under K2 for unclear semantics, so it is never read — see the rule in
     * `KotlinSymbolKeyingTest` that holds that over shipped bytecode.
     */
    fun namesAStablePosition(id: CallableId?): Boolean = id != null && !id.isLocal

    /**
     * A classifier's key, taken **natively** from its `ClassId` — no light class asked, and no
     * normalisation step, because `asFqNameString()` already *is* the Java qualified name.
     *
     * A `null` `ClassId` is the locality test, and it is the only one: a local class has no stable
     * name in either language, so it is keyed on where it is written, exactly as a Java local class
     * with no `getQualifiedName()` is. The two spellings coincide there too — a local class's light
     * class carries its declaration's own text offset — which is what keeps a local class and its
     * members, whose keys come through that light class, naming one owner.
     */
    private fun classifierKeyOf(declaration: KtClassLikeDeclaration): LedgerKey {
        val classId = declaration.getClassId() ?: return anchoredClassifierKeyOf(declaration)
        return LedgerKey(SymbolKeys.classifierKeyOf(classId.asFqNameString()), keyIsQualified = true)
    }

    private fun anchoredClassifierKeyOf(declaration: KtDeclaration): LedgerKey =
        LedgerKey(SymbolKeys.classifierKeyOf(SymbolKeys.anchorOf(declaration)), keyIsQualified = false)

    /**
     * An enum entry's key: the **field** its enum class exposes for it, which is how Java spells the
     * same constant and how [SymbolKeys.keyOf] keys a `PsiField`.
     *
     * Through [SymbolKeys.backingFieldOf], the same call a property goes through, so an entry the
     * light class does not expose falls back the way a property does rather than by a rule of its
     * own.
     */
    private fun enumEntryKeyOf(entry: KtEnumEntry): LedgerKey =
        fieldKeyOf(entry) ?: nativeCallableKeyOf(entry, SymbolKeys.FIELD)

    /**
     * A callable's key: **the light element's, wherever the platform makes one.**
     *
     * The order is the rule stated in [SymbolKeys.backingFieldOf] and in this ticket's §4: **the key
     * is the backing field where the light class exposes one, and the getter where it does not.**
     * That is where the Java path already lands — the walk reports `AccessorEvidence` against the
     * field's key, `:core` allocates the placeholder under it and derives the accessor's rendering
     * from it — so keying a Kotlin property to its getter instead would mint two entries for one
     * symbol and lean on accessor derivation to paper over the gap.
     *
     * A computed property has no backing field, so it falls to the getter; and because both
     * languages ask [SymbolKeys.backingFieldOf], an answer that changes moves them **together**.
     *
     * The native id is reached only where the platform makes no light element at all: an `expect`
     * declaration in a common module, a value class's own member functions, and a local — the first
     * two keyed by a stable name, the last not keyed at all.
     */
    private fun callableKeyOf(declaration: KtCallableDeclaration): LedgerKey {
        val isProperty = declaration.isProperty()
        if (isProperty) fieldKeyOf(declaration)?.let { return it }

        declaration.getRepresentativeLightMethod()?.let { return keyOfLightElement(it) }
        return nativeCallableKeyOf(declaration, if (isProperty) SymbolKeys.FIELD else SymbolKeys.METHOD)
    }

    /**
     * The rule's own sentence as a function: **the key this light element would receive from
     * [SymbolKeys.keyOf]**, with the persistability [SymbolKeys] gives the same element.
     *
     * One shape rather than the pair spelled out at each site, because the pair *is* the rule — a
     * site that took the key from the light element and the flag from somewhere else would be the
     * fork this whole file exists to avoid, and it would look like an ordinary two-argument call.
     */
    private fun keyOfLightElement(element: PsiElement): LedgerKey =
        LedgerKey(SymbolKeys.keyOf(element), SymbolKeys.keyIsQualified(element))

    /**
     * The key of the field [declaration] compiles to, or `null` where its light class exposes none —
     * which is both the computed-property case and every case where there is no light class to ask.
     */
    private fun fieldKeyOf(declaration: KtNamedDeclaration): LedgerKey? {
        val name = declaration.name ?: return null
        val owner = lightOwnerOf(declaration) ?: return null
        return SymbolKeys.backingFieldOf(owner, name)?.let(::keyOfLightElement)
    }

    /**
     * The key of a declaration the platform gives no light element, spelled in the Java grammar from
     * its `CallableId` — `method:class:p.Session#open` for a member of an `expect class`, which is
     * character-for-character the key its `actual`'s light method reaches.
     *
     * A top-level callable is owned by its file's **facade**, because that is the owner the light
     * element would have carried. `getFileClassInfoNoResolve` answers it from the file's name and
     * its `@JvmName` alone, which is exactly what the platform's own facade naming reads — and it
     * answers for a file the platform builds no facade for, which is the case this branch is in.
     */
    private fun nativeCallableKeyOf(declaration: KtNamedDeclaration, kind: String): LedgerKey {
        val id = callableIdOf(declaration)
        if (!namesAStablePosition(id)) {
            return LedgerKey(SymbolKeys.localKeyOf(declaration), keyIsQualified = false)
        }

        val owner = id!!.classId?.asFqNameString() ?: facadeNameOf(declaration.containingKtFile)
        return LedgerKey(
            SymbolKeys.memberKeyOf(kind, SymbolKeys.classifierKeyOf(owner), id.callableName.asString()),
            keyIsQualified = true,
        )
    }

    /**
     * [declaration]'s native id, or `null` where it has none — a local, or a member of a class that
     * is itself local.
     *
     * Built from PSI rather than from an analysis session: the two facts it needs are the owner's
     * `ClassId` and the callable's own name, both of which the tree carries, and a session opened to
     * re-derive them would be a second answer to a question already settled by [ownerOf].
     */
    private fun callableIdOf(declaration: KtNamedDeclaration): CallableId? {
        val name = declaration.nameAsName ?: return null
        ownerOf(declaration)?.let { owner -> return owner.getClassId()?.let { CallableId(it, name) } }
        if (declaration.parent !is KtFile) return null
        return CallableId(declaration.containingKtFile.packageFqName, name)
    }

    /**
     * The light class that would declare [declaration] as a Java member: its owner's, or its file's
     * facade when it is top-level.
     *
     * `null` for a declaration inside a function body, which has no Java-visible owner at all —
     * asking the facade for one would hand back a class that never declared it.
     */
    private fun lightOwnerOf(declaration: KtDeclaration): PsiClass? {
        ownerOf(declaration)?.let { return it.toLightClass() }
        return if (declaration.parent is KtFile) declaration.containingKtFile.findFacadeClass() else null
    }

    /**
     * The classifier [declaration] is a member of, read off the tree.
     *
     * Two shapes and no more, spelled out rather than taken from `containingClassOrObject`: an
     * ordinary member is a child of the class body, and a **primary-constructor `val`** is a child of
     * the constructor's parameter list — which `containingClassOrObject` answers `null` for, and
     * which is the shape `class Clock(val amount: Int)` puts every one of its properties in.
     * Everything else — a declaration inside a function body, a top-level one, a plain parameter —
     * has no classifier owner, and saying so here is what keeps a local out of the ledger.
     */
    private fun ownerOf(declaration: KtDeclaration): KtClassOrObject? = when (declaration.parent) {
        is KtClassBody -> declaration.parent.parent as? KtClassOrObject

        // **`val`, and not merely *in the constructor's parameter list*.** A plain
        // `class Clock(amount: Int)` parameter is a local: it declares no member, no Java file can
        // name it, and giving it the class as an owner would mint `method:class:p.Clock#amount` —
        // a *persistable* key, written to a durable file, that a real `fun amount()` on the same
        // class then collides with. That is the injectivity invariant broken in the one direction
        // the compiler cannot catch for us, so the test is the `val` keyword rather than the shape
        // of the tree around it.
        is KtParameterList -> (declaration as? KtParameter)
            ?.takeIf { it.hasValOrVar() }
            ?.let { (it.parent.parent as? KtPrimaryConstructor)?.getContainingClassOrObject() }

        else -> null
    }

    private fun facadeNameOf(file: KtFile): String =
        JvmFileClassUtil.getFileClassInfoNoResolve(file).facadeClassFqName.asString()

    /** Whether this declaration is one the JVM gives a backing field and accessors to. */
    private fun KtDeclaration.isProperty(): Boolean = this is KtProperty || (this is KtParameter && hasValOrVar())
}

/**
 * A declaration's key and whether it may be persisted, answered together.
 *
 * One value rather than two functions, because the two answers come out of one walk down the same
 * branches: which shape the declaration is, and whether the platform gave it a light element. The
 * Java side computes them apart deliberately — see [SymbolKeys.keyIsQualified] — and the reason does
 * not carry over: there the second answer is a `when` over PSI shapes, here it would be a second
 * traversal of the same three fallbacks, and two traversals are what drift.
 *
 * @param keyIsQualified spelled as the engine spells it, because it is the same fact:
 *   `SymbolEvidence.keyIsQualified` is what decides whether a key reaches a durable file.
 */
internal class LedgerKey(val key: String, val keyIsQualified: Boolean)
