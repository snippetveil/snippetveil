package com.snippetveil.sweep

import com.intellij.psi.PsiReference
import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.domain.JavaMethodCall
import com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

/**
 * **The leak universe resolves nothing, and is built from nothing the anonymiser produced** — held
 * over bytecode rather than promised in a comment.
 *
 * The universe's independence from the plan builders is the entire reason the leak check exists: a
 * universe that resolved references, or consulted a plan, a mapping or a result, would come to share
 * the anonymiser's blind spots and report *clean* on exactly what the anonymiser missed. The closure
 * made that easier to break than it was, because deciding a spelling is the obvious place to reach
 * for a light class or an analysis session — which is what the anonymiser itself keys Kotlin through.
 *
 * So the classes that build the universe — the walk, the closure, the oracle and the records passed
 * between them — are imported by name and held to one rule, and the rule is shown red on fixtures
 * written to break it before it is trusted, the habit every check in this repository keeps.
 */
class LeakUniverseIndependenceTest {

    /** A rule that matches nothing passes, so what it was applied to is asserted first. */
    @Test
    fun `the rule is applied to the classes that build the universe`() {
        val names = CONSTRUCTION.map { it.name }

        listOf(LeakOracle::class, SourceSpellings::class, SourceDeclarations::class, Declaration.KotlinProperty::class)
            .forEach { type -> assertTrue(type.java.name in names) { "${type.java.name} was not imported: $names" } }
        assertTrue(names.any { it.startsWith(SourceDeclarations::class.java.name + "$") }) {
            "No visitor of the declaration walk was imported, so the walk's own traversal is unchecked: $names"
        }
        assertTrue(CorpusSweep::class.java.name !in names) {
            "The sweep itself was imported. It runs the anonymiser by design, so the rule would be red for a reason that is not a defect: $names"
        }
    }

    @Test
    fun `the universe is built without resolving anything or reading anything the anonymiser produced`() {
        THE_UNIVERSE_RESOLVES_NOTHING.check(CONSTRUCTION)
    }

    /**
     * **The rule, pointed at code written to violate it.** Each fixture is one way the universe could
     * stop being independent — resolving a reference to decide a spelling, and being built from a plan
     * or from a result — and each has to be flagged. The same [ArchRule] object the test above checks,
     * because a rebuilt copy would prove that a copy can fail.
     */
    @Test
    fun `the rule flags a construction that resolves, or that reads a plan or a result`() {
        val violations = assertThrows(AssertionError::class.java) {
            THE_UNIVERSE_RESOLVES_NOTHING.check(
                ClassFileImporter().importClasses(ResolvesToDecideASpelling::class.java, BuiltFromTheAnonymisersOutput::class.java)
            )
        }.message.orEmpty()

        listOf("resolve", "SnippetPlan", "AnonymizationResult").forEach { expected ->
            assertTrue(expected in violations) { "The rule did not flag `$expected`: $violations" }
        }
    }

    /**
     * **The constructors refuse.** The factories are the only ways in, and their parameters say what a
     * universe is derived from — declarations, and the spellings the libraries declare — which is what
     * the rule above holds them to.
     */
    @Test
    fun `neither the oracle nor the closure has a constructor anyone else can call`() {
        listOf(LeakOracle::class.java, SourceSpellings::class.java).forEach { type ->
            val open = type.declaredConstructors.filter { !it.isSynthetic && !Modifier.isPrivate(it.modifiers) }
            assertTrue(open.isEmpty()) { "${type.simpleName} can be constructed without its factory: $open" }
        }
    }
}

/**
 * The classes that build the universe, and nothing else from the sweep: the sweep runs the anonymiser
 * on purpose, and the report only formats what the oracle found.
 */
private val CONSTRUCTION: JavaClasses = ClassFileImporter()
    .importPackages(LeakOracle::class.java.packageName)
    .that(nameMatching("""com\.snippetveil\.sweep\.(LeakOracle|Survivor|SourceSpellings|SourceDeclarations|Declaration)(\$.*)?"""))

/**
 * No reference resolution and nothing of the anonymiser's.
 *
 * - `com.snippetveil.core` and `com.snippetveil.plugin` are where the plan, the mapping, the result
 *   and the plan builders live.
 * - Light classes, the Analysis API and the Kotlin reference machinery are how the anonymiser keys a
 *   Kotlin declaration, so a spelling decided through them would agree with the anonymiser by
 *   construction.
 * - A [PsiReference], and the calls that produce or resolve one, are named as well as the packages:
 *   a call's return type is not a dependency of the caller, so `element.reference?.resolve()` would
 *   otherwise leave no trace but the call itself.
 */
private val THE_UNIVERSE_RESOLVES_NOTHING: ArchRule = noClasses()
    .should().dependOnClassesThat().resideInAnyPackage(
        "com.snippetveil.core..",
        "com.snippetveil.plugin..",
        "org.jetbrains.kotlin.asJava..",
        "org.jetbrains.kotlin.analysis..",
        "org.jetbrains.kotlin.idea.references..",
        "org.jetbrains.kotlin.resolve..",
    )
    .orShould().dependOnClassesThat().areAssignableTo(PsiReference::class.java)
    .orShould().callMethodWhere(object : DescribedPredicate<JavaMethodCall>("a call that finds or resolves a reference") {
        override fun test(call: JavaMethodCall): Boolean =
            call.target.name in RESOLVING_CALLS &&
                listOf("com.intellij.", "org.jetbrains.kotlin.").any(call.target.owner.packageName::startsWith)
    })
    .because("the leak universe is derived from declaration text alone, so that it cannot share the anonymiser's blind spots")

private val RESOLVING_CALLS = setOf("resolve", "multiResolve", "getReference", "getReferences", "findReferenceAt")
