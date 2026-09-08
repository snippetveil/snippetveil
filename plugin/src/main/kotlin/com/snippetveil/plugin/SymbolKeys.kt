package com.snippetveil.plugin

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiPackage
import com.intellij.psi.PsiRecordComponent
import com.intellij.psi.util.PsiUtilCore

/**
 * **How a declared symbol is spelled in the ledger, for every language this plugin reads.**
 *
 * There is one key rule and not one per language. A Kotlin declaration takes **the key its light
 * element would receive from [keyOf]** — the same object asked the same question, rather than a
 * second implementation computing the same answer — and where no light element exists, the key is
 * derived from the native id and spelled in **this file's** grammar. That is what makes the
 * agreement between the two languages structural: nobody has to maintain it.
 *
 * **The Java path did not change when this file appeared.** It was lifted out of `JavaPlanBuilder`
 * unaltered, because the whole value of one key rule is that a ledger already on a developer's disk
 * gains Kotlin with no migration — no key it holds is spelled differently afterwards.
 *
 * **It stays here, in the main descriptor's package, and it is pure Java PSI.** The Kotlin
 * dependency is optional, so a class naming `org.jetbrains.kotlin.*` fails to link on an IDE with
 * Kotlin disabled, and `JavaPlanBuilder` — which has to keep working there — calls into this file.
 * So the Kotlin half calls *in*, carrying a light element or a name; nothing Kotlin-shaped reaches
 * the other way. `ShippedCodeArchitectureTest` holds that rule over bytecode.
 */
internal object SymbolKeys {

    /** The `kind` of a member key that is a method — see [memberKeyOf]. */
    const val METHOD = "method"

    /** The `kind` of a member key that is a field — see [memberKeyOf]. */
    const val FIELD = "field"

    /**
     * The identity of a declared symbol, as a string the engine can compare and the ledger persists.
     *
     * A method's key deliberately omits its signature, which is how overloads collapse to one
     * placeholder: they share a name in source, so they share a placeholder. The signature is still
     * reported as evidence, so a rule that needs to tell them apart can, without this changing.
     *
     * Anything with no qualified name — an anonymous class, a local, a parameter, a label — is keyed
     * on where it is written. That key is stable for exactly as long as the file is not edited,
     * which is exactly as long as one invocation lasts.
     *
     * **Anonymous and local class members inherit that fallback through their owner**, and that is
     * the point of routing every member key through [memberKeyOf]. `PsiClass.getQualifiedName()` is
     * `null` inside one, so an owner keyed by name alone collapsed the `state` fields of two
     * different anonymous classes onto one placeholder — two unrelated symbols rendered as one name,
     * which is precisely what the injectivity invariant forbids.
     *
     * **A Lombok light member is keyed by `(owner FQN, kind, name)` and nothing else**, which is
     * what these three branches already say: a light member has no `TextRange` to anchor on, so a
     * key that reached for one would have nothing to read. **A Kotlin light member arrives here the
     * same way and for the same reason** — it is a `PsiMethod` or a `PsiField` whose containing
     * class is a light class, and this function neither knows nor needs to know which language wrote
     * it.
     */
    fun keyOf(symbol: PsiElement): String = when (symbol) {
        // A package is keyed by the whole qualified name and not by the segment that ends it, which
        // is what makes `com.acme.billing` and `org.acme.billing` two symbols and what makes two
        // types in one package share a placeholder for it.
        is PsiPackage -> "package:" + symbol.qualifiedName

        is PsiClass -> classifierKeyOf(symbol.qualifiedName ?: anchorOf(symbol))
        is PsiMethod -> memberKeyOf(METHOD, symbol.containingClass, symbol.name)
        is PsiField -> memberKeyOf(FIELD, symbol.containingClass, symbol.name)

        // Keyed as the field it compiles to, which is rule 5 stated as identity: one declared symbol
        // wearing three PSI faces reaches one key from whichever face the walk arrives at.
        is PsiRecordComponent -> memberKeyOf(FIELD, symbol.containingClass, symbol.name)

        else -> localKeyOf(symbol)
    }

    /**
     * **Whether [keyOf] derived this symbol's key from a fully-qualified name**, which is the fact the
     * engine reads when it decides what may be written down. See [SymbolEvidence.keyIsQualified].
     *
     * It answers the branches of [keyOf] one for one, and that is the point of writing it as a `when`
     * over the same shapes rather than as a test on the resulting string: the two are one decision
     * made twice, and a rule that parsed the key back out would go quietly wrong the day a key format
     * changed. A member inherits the answer from its owner, exactly as its key inherits the owner's,
     * so an anonymous class's field is positional however ordinary the field itself looks.
     *
     * Everything not named here — a local, a parameter, a label, a type parameter — is keyed on where
     * it is written, and so is a class with no qualified name and every member of one. A name that
     * did not resolve never reaches this at all: it is keyed on its own text, and the walk returns
     * before it gets here.
     */
    fun keyIsQualified(symbol: PsiElement): Boolean = when (symbol) {
        is PsiPackage -> true
        is PsiClass -> symbol.qualifiedName != null
        is PsiMethod -> symbol.containingClass?.let(::keyIsQualified) == true
        is PsiField -> symbol.containingClass?.let(::keyIsQualified) == true
        is PsiRecordComponent -> symbol.containingClass?.let(::keyIsQualified) == true
        else -> false
    }

    /**
     * **The field a property of [owner] called [name] compiles to, or `null` where there is none** —
     * the one light-class call either language makes on its way to a property's key.
     *
     * It is one function rather than two agreeing ones because *which* field a light class exposes
     * is a platform fact this project does not get to decide. The Java walk asks it of a JavaBeans
     * accessor, to key `getBar()` to the `bar` it reads; the Kotlin walk asks it of `val bar`, to key
     * the property to the same field. Ask it twice and an answer that changes — a private backing
     * field the light class stops exposing, say — moves one language and not the other, and the two
     * mint different entries for one symbol. Ask it once and the answer can change freely: **both
     * sides fall back to the getter together**, which is a key they still agree on.
     *
     * `false` for the hierarchy flag, and that is the rule rather than an optimisation: a
     * superclass's field is a symbol of that superclass, and a property deriving from a name its own
     * class does not declare would tie two placeholders together on a resemblance.
     */
    fun backingFieldOf(owner: PsiClass, name: String): PsiField? = owner.findFieldByName(name, false)

    /**
     * A classifier's key: a class, an interface, an enum, an object or a typealias, named by
     * [qualifiedName].
     *
     * Spelled here rather than at each caller because the Kotlin half keys classifiers from a native
     * `ClassId` and has to reach **this** spelling to reach the Java one — `ClassId.asFqNameString()`
     * is character-for-character the Java qualified name, so the two coincide by construction the
     * moment they go through one grammar.
     */
    fun classifierKeyOf(qualifiedName: String): String = "class:$qualifiedName"

    /**
     * A member's key: what kind of member it is, whose it is, and what it is called.
     *
     * The owner is given as a **key** rather than as a name, which is the whole of what makes an
     * anonymous class's members work: the owner has no qualified name, so it falls through to an
     * anchor, and the member's key inherits that.
     */
    fun memberKeyOf(kind: String, ownerKey: String, name: String): String = "$kind:$ownerKey#$name"

    /**
     * The key of a symbol identified by **where it is written** — a local, a parameter, a label, a
     * type parameter, and on the Kotlin side a declaration inside a function body.
     *
     * Stable for exactly as long as the file is not edited, which is exactly as long as one
     * invocation lasts. [keyIsQualified] answers `false` for everything that reaches here, so none of
     * it is ever written down.
     */
    fun localKeyOf(symbol: PsiElement): String = "local:" + anchorOf(symbol)

    /**
     * **This file, at this offset** — the identity of a symbol the language gives no stable name to.
     *
     * Exposed rather than private because the Kotlin half needs the same fallback for the same
     * reason: a local class has no `ClassId` and no light class, so there is nothing to key it by but
     * its position, and a second spelling of *position* would be a second answer to what a
     * non-persistable key looks like.
     */
    fun anchorOf(symbol: PsiElement): String =
        (PsiUtilCore.getVirtualFile(symbol)?.url ?: "<light>") + "@" + symbol.textOffset

    /**
     * A member's key, taken from the owner PSI the Java walk has in hand. A `null` owner is the light
     * member with no class at all, and it gets a name rather than an exception.
     */
    private fun memberKeyOf(kind: String, owner: PsiClass?, name: String): String =
        memberKeyOf(kind, owner?.let(::keyOf) ?: "<none>", name)
}
