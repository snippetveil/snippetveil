package com.snippetveil.trust

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaAccess
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.regex.Pattern

/**
 * The machine half of SnippetVeil's central claim: **the plugin makes no network calls.**
 *
 * These rules read compiled bytecode, not source, so they see what a grep over `src/` misses — a
 * reference introduced by a generated class, an inlined function, or a Kotlin compiler intrinsic.
 * They are ordinary JUnit tests wired into `check`, which is the point: a check that exists only in
 * CI cannot be run by the person reading the claim. `./gradlew check` goes red on a `java.net`
 * import, on any machine, with no CI account.
 *
 * **Scope: shipped code only** — the main output of `:core` and `:plugin`, which is exactly what
 * lands in the distribution's `lib/`. `:plugin`'s own test classes are excluded deliberately, and
 * `:core`'s never reach this classpath at all, because `implementation(project(":core"))` carries
 * main output only.
 *
 * Tests are not distributed, and scanning them would manufacture the project's first exception list:
 * `CoreIsIdeFreeTest` in `:core` proves the module boundary *by* calling `Class.forName`, which is
 * exactly the sort of legitimate use a test has and shipped code does not. An exception list is
 * where a violation eventually hides, so there is not going to be one. The cost is real and worth
 * naming: a `java.net` import in a test fails nothing here.
 *
 * Two further layers stand behind this one, because a source-level check cannot see code it was
 * never compiled alongside:
 *  - the ASM scan of the built distribution (`plugin/build.gradle.kts`), which walks every class in
 *    `lib/` including anything bundled or generated, and
 *  - the zero-shipped-dependency assertion, which keeps `lib/` down to code this repository wrote.
 */
class ShippedCodeArchitectureTest {

    /**
     * A rule that matches nothing passes vacuously, so the coverage of the import is itself
     * asserted. Without this, deleting a source set — or renaming a build output directory under
     * [NON_TEST_CODE] — would turn every rule below green rather than red.
     */
    @Test
    fun `the rules below are applied to the shipped classes`() {
        assertTrue(SHIPPED_CLASSES.iterator().hasNext()) {
            "No classes were imported. The rules below would all pass vacuously."
        }
        assertTrue(SHIPPED_CLASSES.any { it.name == "com.snippetveil.core.CoreModule" }) {
            "The :core output is missing from the import: " + SHIPPED_CLASSES.map { it.name }
        }
        assertTrue(SHIPPED_CLASSES.none { it.name == javaClass.name }) {
            "This test class was imported, so [NON_TEST_CODE] is no longer excluding test output."
        }
    }

    /**
     * The module boundary, restated as a test.
     *
     * Scoped by **package**, not by module, and that is the point: inside `:core` the boundary is
     * already absolute — no IntelliJ Platform dependency is declared, so `com.intellij.*` does not
     * compile there and this rule cannot fail. What the rule adds is coverage of the case the
     * compiler cannot refuse: a class put in `com.snippetveil.core` from `:plugin`, where the
     * platform *is* on the classpath. That is also how the rule is demonstrated red.
     */
    @Test
    fun `core does not reach for the IDE`() {
        noClasses().that().resideInAnyPackage("com.snippetveil.core..")
            .should().dependOnClassesThat().resideInAnyPackage("com.intellij..")
            .because("core is the anonymization engine: plain JUnit at millisecond speed, and reusable by a CLI")
            .check(SHIPPED_CLASSES)
    }

    /** The claim on the Marketplace listing, checked. */
    @Test
    fun `nothing shipped can open a socket`() {
        noClasses().should().dependOnClassesThat(NETWORKING_CLASSES)
            .because("SnippetVeil makes no network calls, and that is verifiable rather than asserted")
            .check(SHIPPED_CLASSES)
    }

    /**
     * Reflection would make the rule above meaningless: `Class.forName("java.net.Socket")` leaves
     * no `java.net` reference in the constant pool at all. Neither hatch has a legitimate use in
     * this codebase — the plugin reads PSI and rewrites text — so both are banned outright rather
     * than reviewed case by case.
     */
    @Test
    fun `nothing shipped reaches for a reflective escape hatch`() {
        noClasses().should(CALL_A_REFLECTIVE_ESCAPE_HATCH)
            .because("a reflective lookup would route around the no-network rule without tripping it")
            .check(SHIPPED_CLASSES)
    }
}

/**
 * Networking, as the JVM spells it.
 *
 * `java.nio.channels` is matched by class rather than by package because the package is mostly
 * file I/O: banning `FileChannel` would be noise, and noise is what teaches people to suppress a
 * check. The same list exists in `plugin/build.gradle.kts` for the distribution scan; the two are
 * deliberately separate implementations of one policy over different inputs, and the
 * `java.nio.channels` pattern is written identically in both so that they can be diffed by eye.
 */
private val NETWORKING_CLASSES: DescribedPredicate<JavaClass> =
    resideInAnyPackage(
        "java.net..",
        "javax.net..",
        "java.rmi..",
        "sun.net..",
        "jdk.internal.net..",
    ).or(nameMatching("java\\.nio\\.channels\\.[\\w.$]*(Socket|Datagram|Network)Channel[\\w$]*"))
        .`as`("are networking classes")

private val CALL_A_REFLECTIVE_ESCAPE_HATCH =
    object : ArchCondition<JavaClass>("call Class.forName or java.lang.invoke.MethodHandles") {
        override fun check(item: JavaClass, events: ConditionEvents) {
            item.accessesFromSelf
                .filter { it.isReflectiveEscapeHatch() }
                .forEach { events.add(SimpleConditionEvent.satisfied(it, it.description)) }
        }
    }

private fun JavaAccess<*>.isReflectiveEscapeHatch(): Boolean {
    val owner = targetOwner.name
    return (owner == "java.lang.Class" && target.name == "forName") ||
        owner.startsWith("java.lang.invoke.MethodHandles")
}

/**
 * Compiled test output, in the two layouts this build produces: Gradle's own
 * `build/classes/<language>/test`, and `build/instrumented/instrumentTestCode`, which is what the
 * IntelliJ Platform Gradle Plugin puts on the test runtime classpath in its place.
 *
 * Getting this wrong fails loudly rather than quietly: an exclusion that stops matching lets test
 * code into the rules and turns them red, and `the rules below are applied to the shipped classes`
 * checks that this class itself was excluded. The dangerous direction — main output starting to
 * match — is guarded there too, by naming a class that has to be present.
 */
private val NON_TEST_CODE = ImportOption { location ->
    !location.matches(Pattern.compile(".*/(test-classes|classes/[^/]+/test|instrumented/instrumentTestCode)/.*"))
}

/**
 * The main output of both subprojects, taken off this test's own runtime classpath: `:plugin`'s
 * because these tests live in it, `:core`'s because `:plugin` depends on it. That is the same set
 * of classes the distribution's `lib/` is built from.
 */
private val SHIPPED_CLASSES: JavaClasses = ClassFileImporter()
    .withImportOption(NON_TEST_CODE)
    .importPackages("com.snippetveil")
