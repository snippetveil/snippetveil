package com.snippetveil.plugin.kotlin

import com.intellij.psi.PsiFile
import com.snippetveil.core.SnippetPlan
import com.snippetveil.core.SymbolEvidence
import com.snippetveil.plugin.SymbolKeys
import com.snippetveil.plugin.symbols
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtPrimaryConstructor
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.KtTypeParameter
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass

/**
 * **One key rule, over two languages: every Kotlin declaration kind, how it is keyed, and whether
 * that key can be persisted.**
 *
 * The family `SymbolKeyingTest` opens, asked of the other language. The two questions are the same
 * two — *what is the key?* and *does the key survive an edit?* — and the answer to the first is not
 * allowed to be *a Kotlin one*:
 *
 * > The key of a Kotlin declaration is the key its light element would receive from
 * > [SymbolKeys.keyOf]. Where no light element exists, it comes from the native `ClassId` /
 * > `CallableId`, spelled in [SymbolKeys]' grammar.
 *
 * So the assertions here are mostly **equalities between two spellings of one symbol**, not
 * expectations about a string. The table is the readable half; what holds the rule are the tests
 * under it that key one declaration twice — natively and through its light element, or from Kotlin
 * and from a Java file naming the same thing — and require the two to agree.
 *
 * **Nothing here walks a `.kt` file.** `com.snippetveil-withKotlin.xml` still registers nothing, so
 * every `.kt` file keeps taking the stated refusal `KotlinUnavailableTest` asserts; the keys are
 * exercised against declarations the fixture resolves directly.
 */
internal class KotlinSymbolKeyingTest : KotlinSnippetTestCase() {

    /**
     * Every kind of Kotlin declaration this fixture holds, and the key shape it is expected to
     * reach. `<anchor>` stands for *this file, at this offset* — the fallback for everything with no
     * stable name — and reading the table down the `persistable` column is reading the cut a
     * persisted mapping makes.
     *
     * Two kinds are deliberately absent because their key is not a constant: an `internal` member
     * and a value-class member carry the Gradle module name in the mangling the light element gives
     * them, so they are asserted against that light element in
     * `manglings reach the ledger as the light element spells them`.
     */
    fun `test every kotlin declaration kind is keyed the way its persistability requires`() {
        assertTheSessionIsK2()
        val file = fixture()

        for (kind in KINDS) {
            assertEquals("${kind.description}: keyed wrongly", kind.key, normalize(file, file.keyOf(kind)))
        }
    }

    /**
     * **The persistability column, asserted against what the key rule actually reports** rather than
     * inferred from the shape of the string.
     *
     * The engine reads [SymbolEvidence.keyIsQualified] and never looks at a key's spelling, so a rule
     * that reported the flag wrongly would write an anchored key into a durable file with the table
     * above still green.
     */
    fun `test the key rule reports whether each key names a stable position`() {
        assertTheSessionIsK2()
        val file = fixture()

        for (kind in KINDS) {
            assertEquals(
                "${kind.description}: the key rule reported the wrong answer for `keyIsQualified`",
                kind.persistable,
                KotlinSymbolKeys.of(file.declarationOf(kind)).keyIsQualified,
            )
        }
    }

    /**
     * **A classifier's native key is character-for-character the key its light class receives.**
     *
     * `ClassId.asFqNameString()` is the Java qualified name, so classes, objects, companions and
     * nested types key natively with no normalisation step — and the two sources coincide *by
     * construction* rather than by agreement. That is the claim, and this is it asserted rather than
     * reasoned about: the same declaration is keyed both ways and the strings are required to match.
     *
     * The set is asserted non-empty and asserted to contain a nested type and a companion, because a
     * loop over classifiers that had stopped finding any would pass while checking nothing.
     */
    fun `test a classifier's native key is the key its own light class receives`() {
        assertTheSessionIsK2()
        val file = fixture()

        val compared = file.collectDescendantsOfType<KtClassOrObject>()
            .mapNotNull { declaration -> declaration.toLightClass()?.takeIf { it.qualifiedName != null }?.let { declaration to it } }

        assertNotEmpty(compared)
        assertTrue(
            "the comparison never reached a nested type or a companion, which are the rows most " +
                "likely to diverge: " + compared.map { it.second.qualifiedName },
            compared.any { it.second.qualifiedName == "com.acme.keys.Clock.Nested" } &&
                compared.any { it.second.qualifiedName == "com.acme.keys.Clock.Companion" },
        )
        for ((declaration, light) in compared) {
            assertEquals(
                "the native key and the light class's key parted company for ${light.qualifiedName}",
                SymbolKeys.keyOf(light),
                KotlinSymbolKeys.of(declaration).key,
            )
        }
    }

    /**
     * **A property keys to its backing field, and a computed property to its getter** — and both
     * sides reach that decision through the *same call*, [SymbolKeys.backingFieldOf], rather than
     * through two answers that happen to agree.
     *
     * This asserts the call. `the backing field is found in exactly one place in shipped code`
     * asserts that it is the only one, over bytecode, which is the half a test written like this
     * cannot cover.
     */
    fun `test a property keys to the field its light class exposes and a computed one to its getter`() {
        assertTheSessionIsK2()
        val file = fixture()
        val clock = file.declarationOf(KtClass::class, "Clock").toLightClass()!!

        val backed = SymbolKeys.backingFieldOf(clock, "backed")
        assertNotNull("the light class exposes no backing field for `backed`, so nothing below holds", backed)
        assertEquals(
            "a property with a backing field must key to that field, which is where the Java path lands",
            SymbolKeys.keyOf(backed!!),
            KotlinSymbolKeys.of(file.declarationOf(KtProperty::class, "backed")).key,
        )

        assertNull(
            "the light class exposes a backing field for a computed property, so the fallback is untested",
            SymbolKeys.backingFieldOf(clock, "computed"),
        )
        assertEquals(
            "a computed property has no backing field, so its key is its getter's",
            "method:class:com.acme.keys.Clock#getComputed",
            KotlinSymbolKeys.of(file.declarationOf(KtProperty::class, "computed")).key,
        )

        // The open question about private backing fields, measured rather than left open: this
        // platform does return them, so a private property keys to its field and not to a getter it
        // does not have. The rule is safe either way — if the answer changed, the *Java* side's
        // accessor lookup would change with it, because it is this same call.
        assertNotNull(
            "a private property's backing field is no longer exposed; both languages now fall back " +
                "together, which is still one key, but the table's `hidden` row moves",
            SymbolKeys.backingFieldOf(clock, "hidden"),
        )
    }

    /**
     * **The whole point, end to end: a Java file and a Kotlin file naming one symbol reach one key.**
     *
     * Every other test here compares two spellings this module computes. This one compares what
     * [KotlinSymbolKeys] says with what the **shipped Java walk** says, over a Java file that names
     * the Kotlin declarations through the light classes the platform builds for them — which is the
     * only way a Java file ever sees Kotlin, and the whole reason one shared ledger is possible.
     *
     * The accessor row is the one that matters most. `getBacked()` is not keyed as a method: the Java
     * walk reports it as an accessor of the field `backed`, `:core` allocates the placeholder under
     * `accessor.fieldKey` and derives the accessor's rendering from it. Keying the Kotlin property to
     * its getter instead would mint a second entry for one symbol, and this is what would catch it.
     */
    fun `test a java file and a kotlin file naming one symbol reach one key`() {
        assertTheSessionIsK2()
        assertTheHarnessResolves()
        val file = fixture()
        val java = planFor("UsesClock.java", JAVA_PROBE)

        assertEquals(
            "the class",
            KotlinSymbolKeys.of(file.declarationOf(KtClass::class, "Clock")).key,
            java.evidenceOf("Clock").key,
        )
        assertEquals(
            "a member function",
            KotlinSymbolKeys.of(file.declarationOf(KtNamedFunction::class, "now")).key,
            java.evidenceOf("now").key,
        )
        assertEquals(
            "an enum constant",
            KotlinSymbolKeys.of(file.declarationOf(KtEnumEntry::class, "RED")).key,
            java.evidenceOf("RED").key,
        )
        assertEquals(
            "a computed property, which both languages key to its getter",
            KotlinSymbolKeys.of(file.declarationOf(KtProperty::class, "computed")).key,
            java.evidenceOf("getComputed").key,
        )

        val accessor = java.evidenceOf("getBacked").accessor
        assertNotNull("the Java walk reported no accessor evidence for `getBacked`", accessor)
        assertEquals(
            "a Kotlin property and the Java view of its generated accessor must name one entry",
            KotlinSymbolKeys.of(file.declarationOf(KtProperty::class, "backed")).key,
            accessor!!.fieldKey,
        )
    }

    /**
     * **`@JvmName` and `internal` key on the light element's name unmodified, manglings included.**
     *
     * `demangleInternalName` exists, so demangling was available and is deliberately not used: the
     * shipped `keyOf` reads `PsiMethod.getName()`, and demangling here would be a divergence from a
     * key the Java builder already writes for the very same light method.
     *
     * Asserted against the light method's own name rather than against a literal, because the
     * mangling carries the **Gradle module name** — which is the cost this decision takes on, and it
     * is asserted too: a module rename breaks placeholder continuity for an `internal` member,
     * visibly, and for a reason no Java symbol has.
     */
    fun `test manglings reach the ledger as the light element spells them`() {
        assertTheSessionIsK2()
        val file = fixture()
        val clock = file.declarationOf(KtClass::class, "Clock").toLightClass()!!
        val owner = SymbolKeys.classifierKeyOf("com.acme.keys.Clock")

        val internal = clock.methods.single { it.name.startsWith("secret") }
        assertTrue(
            "`internal fun secret` is not mangled in this fixture, so the decision below is untested",
            internal.name != "secret",
        )
        assertEquals(
            "an internal member keys on the light element's name unmodified",
            SymbolKeys.memberKeyOf(SymbolKeys.METHOD, owner, internal.name),
            KotlinSymbolKeys.of(file.declarationOf(KtNamedFunction::class, "secret")).key,
        )

        val renamed = KotlinSymbolKeys.of(file.declarationOf(KtNamedFunction::class, "jvmNamed")).key
        assertEquals(
            "a @JvmName member keys on the name the annotation gave its light method",
            SymbolKeys.memberKeyOf(SymbolKeys.METHOD, owner, "renamed"),
            renamed,
        )
        assertFalse(
            "the source name reached the key, so @JvmName was not read: $renamed",
            "jvmNamed" in renamed,
        )
    }

    /**
     * **An `expect` declaration has no light element, keys natively, and persists.**
     *
     * The shipped persistence test was `getQualifiedName() == null`, and as the *general* test it is
     * false: an `expect class` in a common module has no light element and no `PsiClass` to ask, and
     * it persists anyway. It satisfies the rule the null test was standing in for — **only keys
     * derived from a stable qualified name persist** — on exactly the same grounds a `PsiClass` does:
     * a `ClassId` names a position in the package/class nesting, not an in-memory identity and not a
     * file offset.
     *
     * The absence of the light element is asserted first, because it is the precondition the rest of
     * this rests on: were the platform to start building one, these keys would be coming down the
     * light-element path and the native path would be untested.
     */
    fun `test an expect declaration has no light element, keys natively and persists`() {
        assertTheSessionIsK2()
        val common = expectFixture()

        val session = common.declarationOf(KtClass::class, "Session")
        assertNull(
            "an `expect class` now has a light class, so the native path below is not the one under test",
            session.toLightClass(),
        )

        for ((description, declaration, key) in listOf(
            Triple("the expect class", session, "class:com.acme.pair.Session"),
            Triple("its member function", common.declarationOf(KtNamedFunction::class, "open"), "method:class:com.acme.pair.Session#open"),
            Triple("its property", common.declarationOf(KtProperty::class, "id"), "field:class:com.acme.pair.Session#id"),
            Triple("a top-level expect callable", common.declarationOf(KtNamedFunction::class, "connect"), "method:class:com.acme.pair.CommonKt#connect"),
        )) {
            val keyed = KotlinSymbolKeys.of(declaration)
            assertEquals("$description: keyed wrongly", key, keyed.key)
            assertTrue("$description: a stable qualified name must persist", keyed.keyIsQualified)
        }
    }

    /**
     * **An `expect`/`actual` pair reaches one key, and the split that is left is recorded here rather
     * than left to be discovered.**
     *
     * The pair is one declaration whose two halves the *language* forces to agree, so unifying is
     * right, and it is mostly free: the `expect` keys natively, the `actual` keys through its light
     * element, and the strings coincide.
     *
     * **The residual, asserted as a residual.** A top-level callable is owned by its file's facade,
     * so an `expect` and an `actual` in differently-named files reach two keys. That is visible as
     * two placeholders and is never a leak, and `getExpectsForActual` is rejected as the fix — it is
     * experimental API, documented to return multiple symbols under ambiguity, and bought for a case
     * that Kotlin-Multiplatform-with-Java-interop already makes narrow. A recorded gap is more honest
     * than silent machinery, and a recorded gap nobody asserted is a gap that grows.
     */
    fun `test an expect and its actual reach one key, and the facade split is the recorded residual`() {
        assertTheSessionIsK2()
        val common = expectFixture()
        val jvm = actualFixture()

        for ((description, expect, actual) in listOf(
            Triple("the classifier", common.declarationOf(KtClass::class, "Session"), jvm.declarationOf(KtClass::class, "Session")),
            Triple("a member function", common.declarationOf(KtNamedFunction::class, "open"), jvm.declarationOf(KtNamedFunction::class, "open")),
            Triple("a property with a backing field", common.declarationOf(KtProperty::class, "id"), jvm.declarationOf(KtProperty::class, "id")),
        )) {
            assertEquals(
                "$description: an expect and its actual are one declaration and must reach one key",
                KotlinSymbolKeys.of(expect).key,
                KotlinSymbolKeys.of(actual).key,
            )
        }

        assertEquals(
            "the recorded residual moved: a top-level actual is keyed by its own file's facade",
            "method:class:com.acme.pair.JvmKt#connect",
            KotlinSymbolKeys.of(jvm.declarationOf(KtNamedFunction::class, "connect")).key,
        )
        assertTrue(
            "the top-level facade split is no longer a split, so the residual above is stale and " +
                "should be deleted rather than left as a warning about nothing",
            KotlinSymbolKeys.of(common.declarationOf(KtNamedFunction::class, "connect")).key !=
                KotlinSymbolKeys.of(jvm.declarationOf(KtNamedFunction::class, "connect")).key,
        )
    }

    /**
     * **Two declarations never share a key.** The injectivity invariant the whole product rests on,
     * asked of the fixture rather than argued from the compiler.
     *
     * The one deliberate collapse is a class and its own primary constructor, which key alike because
     * that is what the Java walk already does with `new Ledger()` — one symbol, one placeholder.
     * Every other pair must be distinct, and the three `state`-shaped locals are the case that would
     * break it: keyed on a name alone they would collapse onto one placeholder, which is exactly what
     * the reverse mapping cannot survive.
     */
    fun `test two declarations never share a key`() {
        assertTheSessionIsK2()
        val file = fixture()

        val keyed = file.collectDescendantsOfType<KtNamedDeclaration>()
            .filterNot { it is KtPrimaryConstructor }
            .associateWith { KotlinSymbolKeys.of(it).key }

        assertNotEmpty(keyed.keys.toList())
        val collisions = keyed.entries.groupBy { it.value }
            .filterValues { it.size > 1 }
            .map { (key, entries) -> key + " <- " + entries.map { it.key.name } }
        assertEquals("two declarations reached one key", emptyList<String>(), collisions)
    }

    /** The [Kind]'s declaration in this file, found by its PSI shape and its name. */
    private fun KtFile.keyOf(kind: Kind): String = KotlinSymbolKeys.of(declarationOf(kind)).key

    private fun KtFile.declarationOf(kind: Kind): KtNamedDeclaration = declarationOf(kind.type, kind.name)

    /**
     * The one declaration of this shape and name in this file. `single` rather than `first`, so that
     * a fixture edit which duplicates a name fails here instead of silently addressing the wrong one.
     */
    private fun <T : KtNamedDeclaration> KtFile.declarationOf(type: KClass<T>, name: String): T =
        collectDescendantsOfType<KtNamedDeclaration>()
            .filterIsInstance(type.java)
            .single { it.name == name }

    /** The [ordinal]-th occurrence of [token] in a Java plan, and everything the walk observed. */
    private fun SnippetPlan.evidenceOf(token: String, ordinal: Int = 0): SymbolEvidence {
        val occurrences = symbols().filter { it.text == token }
        assertTrue(
            "`$token` occurs ${occurrences.size} times in the Java probe; there is no #$ordinal",
            ordinal < occurrences.size,
        )
        return occurrences[ordinal].symbol
    }

    /** The file-and-offset anchor, reduced to the word `<anchor>` so a table can state a key. */
    private fun normalize(file: PsiFile, key: String): String =
        key.replace(Regex(Regex.escape(file.virtualFile.url) + """@\d+"""), "<anchor>")

    private fun fixture(): KtFile = myFixture.addFileToProject(FIXTURE_PATH, FIXTURE) as KtFile

    private fun expectFixture(): KtFile = myFixture.addFileToProject("com/acme/pair/Common.kt", COMMON) as KtFile

    private fun actualFixture(): KtFile = myFixture.addFileToProject("com/acme/pair/Jvm.kt", JVM) as KtFile
}

/**
 * **The locality half of the persistence rule, exercised where only a hand-built id can reach it.**
 *
 * A plain test rather than a fixture-based one: nothing here needs an IDE, and the failure it pins
 * is one no fixture on this platform produces — the PSI-derived id of a local is `null`, so the
 * `!isLocal` half would never fire and would sit there looking like a line nobody needed.
 *
 * It is needed because **the sources disagree about what a local's id is**. `CallableId`'s own
 * documentation gives `<local>/loc` — non-null, and not stable — while a survey of the API recorded
 * `callableId` as `null`. Persisting a local on a non-null-but-local id writes a key that re-points
 * on the next edit, which is precisely the failure structural path keys were rejected over.
 */
internal class StablePositionTest {

    @Test
    fun `a local callable id does not name a stable position, and neither does a missing one`() {
        assertFalse(
            KotlinSymbolKeys.namesAStablePosition(
                CallableId(FqName("<local>"), FqName("loc"), Name.identifier("settle"))
            ),
            "a `<local>` id is non-null and still names nothing that survives an edit",
        )
        assertFalse(
            KotlinSymbolKeys.namesAStablePosition(null),
            "a missing id names no position at all",
        )
        org.junit.jupiter.api.Assertions.assertTrue(
            KotlinSymbolKeys.namesAStablePosition(
                CallableId(FqName("com.acme"), FqName("Clock"), Name.identifier("now"))
            ),
            "an ordinary member's id was rejected, which would keep every Kotlin callable out of the ledger",
        )
    }
}

/**
 * One declaration kind, the PSI shape and name that address it in the fixture, and the two answers.
 *
 * @param persistable whether the key names a stable position — a `ClassId`, a `CallableId` or the
 *   qualified name of a light element's owner — and therefore whether it may be written to a file and
 *   read back next week meaning the same symbol.
 */
private class Kind(
    val description: String,
    val type: KClass<out KtNamedDeclaration>,
    val name: String,
    val key: String,
    val persistable: Boolean,
)

private val KINDS = listOf(
    Kind("a class", KtClass::class, "Clock", "class:com.acme.keys.Clock", persistable = true),

    // A constructor is keyed as its class, which is what the Java walk already does with
    // `new Ledger()`: one symbol, one placeholder, whichever face the walk arrives at.
    Kind("a primary constructor", KtPrimaryConstructor::class, "Clock", "class:com.acme.keys.Clock", persistable = true),

    Kind("a primary-constructor val", KtParameter::class, "amount", "field:class:com.acme.keys.Clock#amount", persistable = true),
    Kind("a property with a backing field", KtProperty::class, "backed", "field:class:com.acme.keys.Clock#backed", persistable = true),
    Kind("a computed property", KtProperty::class, "computed", "method:class:com.acme.keys.Clock#getComputed", persistable = true),
    Kind("a private property", KtProperty::class, "hidden", "field:class:com.acme.keys.Clock#hidden", persistable = true),
    Kind("a member function", KtNamedFunction::class, "now", "method:class:com.acme.keys.Clock#now", persistable = true),
    Kind("a type parameter", KtTypeParameter::class, "T", "class:<anchor>", persistable = false),
    Kind("a companion object", KtObjectDeclaration::class, "Companion", "class:com.acme.keys.Clock.Companion", persistable = true),

    // The companion's backing field is static on the *outer* class, so the companion's own light
    // class exposes none — and the Java view of `Clock.Companion.getShared()` finds none either.
    // Both languages fall back to the getter together, which is the property the one shared call
    // buys: the answer may change, and it cannot change for only one of them.
    Kind("a companion's property", KtProperty::class, "shared", "method:class:com.acme.keys.Clock.Companion#getShared", persistable = true),

    Kind("a nested class", KtClass::class, "Nested", "class:com.acme.keys.Clock.Nested", persistable = true),
    Kind("a nested class's property", KtProperty::class, "n", "field:class:com.acme.keys.Clock.Nested#n", persistable = true),
    Kind("an object", KtObjectDeclaration::class, "Solo", "class:com.acme.keys.Solo", persistable = true),
    Kind("an object's property", KtProperty::class, "only", "field:class:com.acme.keys.Solo#only", persistable = true),
    Kind("a typealias", KtTypeAlias::class, "Alias", "class:com.acme.keys.Alias", persistable = true),
    Kind("an enum class", KtClass::class, "Colour", "class:com.acme.keys.Colour", persistable = true),
    Kind("an enum entry", KtEnumEntry::class, "RED", "field:class:com.acme.keys.Colour#RED", persistable = true),
    Kind("a value class", KtClass::class, "Money", "class:com.acme.keys.Money", persistable = true),
    Kind("a value class's property", KtParameter::class, "cents", "field:class:com.acme.keys.Money#cents", persistable = true),

    // A value class's own member functions get no light method at all on this platform — they are
    // mangled beyond what Java can name — so this row comes down the native path, unmangled. The
    // partition is what keeps that safe: no Java file can name it, so no Java token can key to it.
    Kind("a value class's member function", KtNamedFunction::class, "plus", "method:class:com.acme.keys.Money#plus", persistable = true),

    Kind("a top-level function", KtNamedFunction::class, "topLevel", "method:class:com.acme.keys.KeysKt#topLevel", persistable = true),
    Kind("a top-level property", KtProperty::class, "topLevelProperty", "field:class:com.acme.keys.KeysKt#topLevelProperty", persistable = true),

    // Everything below has no stable name in either language, and nothing below is written down.
    Kind("a local class", KtClass::class, "Local", "class:<anchor>", persistable = false),
    Kind("a local class's property", KtProperty::class, "state", "field:class:<anchor>#state", persistable = false),
    Kind("a local function", KtNamedFunction::class, "localFun", "local:<anchor>", persistable = false),
    Kind("a local value", KtProperty::class, "localValue", "local:<anchor>", persistable = false),
    Kind("a plain parameter", KtParameter::class, "other", "local:<anchor>", persistable = false),
)

private const val FIXTURE_PATH = "com/acme/keys/Keys.kt"

/** One file naming every kind in [KINDS] once, so the family is read as a table rather than as a fixture per row. */
private val FIXTURE = """
    package com.acme.keys

    class Clock(val amount: Int) {
        val backed: Int = 1
        val computed: Int get() = 2
        private val hidden: Int = 3

        fun now(): Int = 4
        internal fun secret(): Int = 5
        @JvmName("renamed") fun jvmNamed(): Int = 6
        fun <T> generic(value: T): T = value

        fun body() {
            class Local { val state: Int = 7 }
            fun localFun(): Int = 8
            val localValue = Local().state + localFun()
            check(localValue > 0)
        }

        companion object {
            val shared: Int = 9
        }

        class Nested {
            val n: Int = 10
        }
    }

    object Solo {
        val only: Int = 11
    }

    typealias Alias = Clock

    enum class Colour { RED }

    @JvmInline
    value class Money(val cents: Int) {
        fun plus(other: Money): Money = Money(cents + other.cents)
    }

    fun topLevel(): Int = 12

    val topLevelProperty: Int = 13
""".trimIndent()

/**
 * A Java file naming Kotlin declarations the only way a Java file ever can — through the light
 * classes the platform builds for them.
 *
 * Fully qualified, for the reason `JavaSnippetTestCase.assertTheHarnessResolves` writes its probe
 * that way: the file this is configured into does not sit in the package it names.
 */
private val JAVA_PROBE = """
    class UsesClock {
        int read(com.acme.keys.Clock clock) {
            com.acme.keys.Clock.Nested nested = new com.acme.keys.Clock.Nested();
            com.acme.keys.Colour colour = com.acme.keys.Colour.RED;
            return clock.now() + clock.getBacked() + clock.getComputed() + nested.getN() + colour.ordinal();
        }
    }
""".trimIndent()

/** The common half of an `expect`/`actual` pair: no light elements anywhere in it. */
private val COMMON = """
    package com.acme.pair

    expect class Session {
        fun open(): Int
        val id: Int
    }

    expect fun connect(): Int
""".trimIndent()

/**
 * The JVM half, written as the ordinary declarations an `actual` compiles to.
 *
 * A redeclaration of the names above, deliberately: this fixture is one module, so the two halves of
 * a multiplatform pair cannot be separated into two source sets — and the platform still builds the
 * light classes for this half, which is the only thing the comparison needs. What is being asserted
 * is that two *keys* agree, and each is computed from its own declaration.
 */
private val JVM = """
    package com.acme.pair

    class Session {
        fun open(): Int = 1
        val id: Int = 2
    }

    fun connect(): Int = 3
""".trimIndent()
