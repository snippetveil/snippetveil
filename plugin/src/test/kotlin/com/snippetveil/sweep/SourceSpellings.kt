package com.snippetveil.sweep

/**
 * **One declaration, as the declaration walk read it: text, and never a resolved symbol.**
 *
 * Every field is a string lifted out of a declaration or a file name, and that is the property the
 * whole leak check rests on — the closure over these runs on what was *written*, so it cannot come to
 * agree with the anonymiser's walk, which resolves references. A record that needed a type, a
 * reference or a light element to fill in would be the first step towards the check sharing the
 * anonymiser's blind spots.
 *
 * Each variant is one row of the closure's rule, and carries exactly the text that row mangles.
 */
internal sealed interface Declaration {

    /**
     * The name as the declaration writes it, or `null` for a declaration no source text names —
     * which is the whole reason a facade needs a closure rather than a declaration walk.
     */
    val written: String?

    /**
     * Spelled only as written: a class, a Java field, a parameter, a local, a label, a package
     * segment, a named companion.
     */
    data class Written(val name: String) : Declaration {
        override val written get() = name
    }

    /** A Java method, which Kotlin writes as a property when it is shaped like a getter. */
    data class JavaMethod(val name: String) : Declaration {
        override val written get() = name
    }

    /**
     * A Kotlin function, member or top-level.
     *
     * @param internalIn the IDE's name for the module holding it when it is `internal`, and `null`
     *   when it is not — the module name is mangled into what Java writes for it.
     */
    data class KotlinFunction(val name: String, val internalIn: String? = null) : Declaration {
        override val written get() = name
    }

    /**
     * A Kotlin property that has accessors: a member or top-level `val` or `var`, or a constructor
     * parameter carrying one. A local variable is [Written], because it has no accessors to spell.
     */
    data class KotlinProperty(val name: String, val mutable: Boolean, val internalIn: String? = null) : Declaration {
        override val written get() = name
    }

    /** An `object` declaration — not a companion, and not an object expression. */
    data class KotlinObject(val name: String) : Declaration {
        override val written get() = name
    }

    /**
     * A `.kt` file with a top-level function or property, and so a facade class.
     *
     * @param fileName the file's own name, extension included
     * @param jvmName what `@file:JvmName` says, when it says anything
     */
    data class KotlinFacade(val fileName: String, val jvmName: String?) : Declaration {
        override val written: String? get() = null
    }

    /** `@JvmName("settleNow")`, or its `get:` and `set:` forms, on the declaration named [of]. */
    data class JvmName(val name: String, val of: String) : Declaration {
        override val written: String? get() = null
    }
}

/**
 * **The leak universe for source under analysis, before the libraries are subtracted: a set of
 * spellings, not a set of declarations.**
 *
 * > The closure contains exactly those spellings by which a project-owned declaration can be written
 * > in a `.java` or `.kt` source file.
 *
 * Java let a use-site token equal its declaration's text so reliably that nobody wrote the
 * assumption down. Kotlin ends it: Java's `getBody()` is written `body` from Kotlin, Kotlin's
 * `val body` is written `getBody()` from Java, and a facade is declared in no source text at all. A
 * universe of declared text is blind to exactly those spellings, so this one closes over them.
 *
 * ### A rule, and string mangling
 *
 * Every spelling here is derived from declaration text and file names, and **nothing is resolved** —
 * no reference, no plan, no mapping, no result. That is what keeps the universe independent of the
 * anonymiser's own walk, which is the entire reason the leak check exists; an implementation that
 * finds itself resolving something to decide a spelling has stopped building this. The private
 * constructor keeps [of] the only way in, and its parameter says what the universe comes from.
 *
 * **In**, by the rule: a property's getter and setter (a `val` has no setter) and its backing field;
 * the `is` form of both, where the compiler keeps the prefix; a Java getter's Kotlin property; a
 * facade, and the `@file:JvmName` that replaces it; a `@JvmName`; an object's `INSTANCE`, qualified
 * by the object; an `internal` callable with its module mangled in.
 *
 * **Out**, by the same rule rather than by omission: a value class's `f-impl`, which is not a Java
 * identifier and cannot be written; and the bytecode-only manglings `f$default`, `access$…` and
 * `DefaultImpls`. A JVM signature is not a source spelling. Names the *language* fixes — `it`,
 * `Companion`, `component1` — are declared in no source text and nothing here derives them.
 *
 * ### The noise is the accepted direction
 *
 * Some spellings derived here will never appear in any output, and some will collide with library
 * members. The library set only ever subtracts, so what that costs is false positives — and a
 * false-positive rate that proves intolerable is answered by the annotation each derived spelling
 * carries ([derivationOf]), never by a narrower closure.
 */
internal class SourceSpellings private constructor(private val derivations: Map<String, String?>) {

    /** Every spelling in the closure. */
    val names: Set<String> get() = derivations.keys

    /** How many spellings were written in the source, as the declarations spell themselves. */
    val written: Int get() = derivations.values.count { it == null }

    /** How many spellings only the closure put here. */
    val derived: Int get() = derivations.size - written

    /**
     * What [spelling] was derived from, as a human reads it in a triage row — or `null` where it is
     * written in the source, which wins wherever the closure derives the same spelling too.
     */
    fun derivationOf(spelling: String): String? = derivations[spelling]

    companion object {

        /**
         * **The closure, and the only way to build one**: [declarations], each spelled as it is
         * written and by every sibling spelling its row derives.
         */
        fun of(declarations: Collection<Declaration>): SourceSpellings {
            val derivations = LinkedHashMap<String, String?>()
            declarations.forEach { declaration -> declaration.written?.let { derivations[it] = null } }
            declarations.forEach { declaration ->
                siblingsOf(declaration).forEach { (spelling, derivation) ->
                    if (spelling !in derivations) derivations[spelling] = derivation
                }
            }
            return SourceSpellings(derivations)
        }

        /** The spellings one row derives, each with the sentence a triage row shows for it. */
        private fun siblingsOf(declaration: Declaration): List<Pair<String, String>> = when (declaration) {
            is Declaration.Written -> emptyList()
            is Declaration.JavaMethod -> kotlinPropertyFor(declaration.name)
                ?.let { listOf(it to "the Kotlin property for Java getter ${declaration.name}") }
                .orEmpty()
            is Declaration.KotlinFunction -> manglings(declaration.name, declaration.internalIn)
                .map { it to "internal function ${declaration.name}, with its module mangled in" }
            is Declaration.KotlinProperty -> accessorsOf(declaration).flatMap { (accessor, role) ->
                listOf(accessor to "the $role of Kotlin property ${declaration.name}") +
                    manglings(accessor, declaration.internalIn)
                        .map { it to "the $role of internal Kotlin property ${declaration.name}, with its module mangled in" }
            }
            is Declaration.KotlinObject ->
                listOf("${declaration.name}.$INSTANCE" to "the instance of Kotlin object ${declaration.name}")
            is Declaration.KotlinFacade -> listOf(
                (declaration.jvmName ?: facadeNameOf(declaration.fileName)) to "the facade of ${declaration.fileName}",
            )
            is Declaration.JvmName -> listOf(declaration.name to "the @JvmName of ${declaration.of}")
        }

        /**
         * The getter, and the setter where there is one — as `JvmAbi` spells them. An `is`-prefixed
         * name is its own getter and loses the prefix in its setter; any other name is capitalised
         * behind `get` and `set`.
         */
        private fun accessorsOf(property: Declaration.KotlinProperty): List<Pair<String, String>> {
            val name = property.name
            val getter = if (startsWithIsPrefix(name)) name else "get" + capitalizedAsciiOnly(name)
            val setter = "set" + capitalizedAsciiOnly(if (startsWithIsPrefix(name)) name.substring(IS.length) else name)
            return listOfNotNull(getter to "getter", (setter to "setter").takeIf { property.mutable })
        }

        /**
         * The Kotlin property a Java method is written as, or `null` where it is not shaped like a
         * getter. `getBody` is `body`, `getURL` is `url` — a leading acronym is lowered as one word —
         * and `isSettled` keeps its name.
         *
         * Parameter lists and return types are not read, so a `getBody(int)` is taken for a getter.
         * That over-includes, which is the direction of failure this closure is allowed.
         */
        private fun kotlinPropertyFor(method: String): String? = when {
            isAccessorShaped(method, IS) -> method
            isAccessorShaped(method, GET) -> decapitalizedSmart(method.substring(GET.length))
            else -> null
        }

        /**
         * [name] with each module name it may compile under mangled in, or nothing when it is not
         * `internal`. The IDE's name for the module is read two ways, because the one the compiler
         * was given is not declaration text and cannot be read here: as it is, and — for a Gradle
         * source-set module, `acme.billing.main` — as the Gradle module, `billing` for `main` and
         * `billing_test` for anything else. A guess that names no module costs nothing: a spelling
         * nobody compiled is a spelling that never appears.
         */
        private fun manglings(name: String, internalIn: String?): List<String> {
            if (internalIn == null) return emptyList()
            val segments = internalIn.split('.')
            val gradle = if (segments.size < 2) {
                null
            } else {
                val (project, sourceSet) = segments[segments.size - 2] to segments.last()
                if (sourceSet == "main") project else "${project}_$sourceSet"
            }
            return listOfNotNull(internalIn, gradle).map { "$name$${sanitizedAsJavaIdentifier(it)}" }.distinct()
        }

        /** `Ledger.kt` is `LedgerKt`, as `PackagePartClassUtils` spells a file's facade. */
        private fun facadeNameOf(fileName: String): String {
            val stem = fileName.substringBeforeLast('.')
            return if (stem.isEmpty()) "_$FACADE_SUFFIX" else capitalizedAsciiOnly(sanitizedAsJavaIdentifier(stem)) + FACADE_SUFFIX
        }

        /** `JvmAbi.startsWithIsPrefix`: `is`, then something that is not a lower-case ASCII letter. */
        private fun startsWithIsPrefix(name: String): Boolean = isAccessorShaped(name, IS)

        private fun isAccessorShaped(name: String, prefix: String): Boolean =
            name.startsWith(prefix) && name.length > prefix.length && name[prefix.length] !in 'a'..'z'

        private fun capitalizedAsciiOnly(name: String): String =
            if (name.firstOrNull() in 'a'..'z') name[0].uppercaseChar() + name.substring(1) else name

        /** The compiler's `decapitalizeSmartForCompiler`, ASCII only. */
        private fun decapitalizedSmart(name: String): String {
            fun upperAt(index: Int) = name[index] in 'A'..'Z'
            if (name.isEmpty() || !upperAt(0)) return name
            if (name.length == 1 || !upperAt(1)) return name[0].lowercaseChar() + name.substring(1)
            val secondWord = name.indices.firstOrNull { !upperAt(it) } ?: return name.lowercase()
            return name.substring(0, secondWord - 1).lowercase() + name.substring(secondWord - 1)
        }

        /** `NameUtils.sanitizeAsJavaIdentifier`: anything not a letter or a digit becomes `_`. */
        private fun sanitizedAsJavaIdentifier(name: String): String = name.replace(Regex("""[^\p{L}\p{Nd}]"""), "_")

        private const val GET = "get"
        private const val IS = "is"
        private const val FACADE_SUFFIX = "Kt"
        private const val INSTANCE = "INSTANCE"
    }
}
