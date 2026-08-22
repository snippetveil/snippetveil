package com.snippetveil.trust

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaAccess
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.domain.properties.HasName
import com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Assertions.assertThrows
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
     * A subprocess is a network call the rule above cannot see:
     * `Runtime.getRuntime().exec("curl …")` reaches the network without a single `java.net`
     * reference appearing in our bytecode. The types are banned outright — the plugin reads PSI and
     * rewrites text, so there is no shipped code with a reason to hold a [Process].
     */
    @Test
    fun `nothing shipped can start a process`() {
        NOTHING_STARTS_A_PROCESS.check(SHIPPED_CLASSES)
    }

    /**
     * `Runtime.exec` is banned as a **call**, not as a type reference, because `java.lang.Runtime`
     * itself has to stay legal: `Runtime.getRuntime().availableProcessors()` is an ordinary thing to
     * want, and a rule that banned the class outright would be the kind of noise that teaches people
     * to suppress a check. Every overload is covered, since the target's name is matched and its
     * descriptor is not.
     */
    @Test
    fun `nothing shipped calls Runtime exec`() {
        NOTHING_CALLS_RUNTIME_EXEC.check(SHIPPED_CLASSES)
    }

    /**
     * Reflection would make the rules above meaningless: `Class.forName("java.net.Socket")` leaves
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

    /**
     * The two process rules, pointed at code written to violate them — the same habit as the
     * distribution scan proving it can fail before it reports that nothing failed.
     *
     * It earns its place here rather than in a one-off manual check because [CALL_RUNTIME_EXEC] has
     * a way to be wrong that the other rules do not. Nothing in this repository's shipped sources
     * touches `java.lang.Runtime` at all, so a version of it that banned the whole class — or one
     * that matched nothing — would leave every rule above green and every real subprocess unseen.
     *
     * These are the only two rules in this file hoisted into named [ArchRule]s rather than built
     * inline, and that is the point: this test and the two above check the *same* objects. Rebuilt
     * copies would prove that a copy can fail, which is one edit away from proving nothing.
     */
    @Test
    fun `the process rules tell a subprocess from an ordinary Runtime call`() {
        val subprocess = classesOf(StartsASubprocess::class.java)
        val ordinaryRuntimeUse = classesOf(UsesRuntimeLegitimately::class.java)

        assertTrue("viaProcessBuilder" in NOTHING_STARTS_A_PROCESS.violationsIn(subprocess)) {
            "The process-type rule did not flag a ProcessBuilder: " +
                NOTHING_STARTS_A_PROCESS.violationsIn(subprocess)
        }
        assertTrue("viaRuntimeExec" in NOTHING_CALLS_RUNTIME_EXEC.violationsIn(subprocess)) {
            "The Runtime.exec rule did not flag an exec call: " +
                NOTHING_CALLS_RUNTIME_EXEC.violationsIn(subprocess)
        }

        // Neither rule may fire on Runtime.getRuntime().availableProcessors(); `check` throws if it
        // does, and the assertion message it throws with is the report.
        NOTHING_STARTS_A_PROCESS.check(ordinaryRuntimeUse)
        NOTHING_CALLS_RUNTIME_EXEC.check(ordinaryRuntimeUse)
    }

    private fun classesOf(vararg types: Class<*>): JavaClasses = ClassFileImporter().importClasses(*types)

    /** The violations [this] reports against [classes]. Fails the test if it reports none. */
    private fun ArchRule.violationsIn(classes: JavaClasses): String =
        assertThrows(AssertionError::class.java) { check(classes) }.message.orEmpty()
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

/**
 * Process execution, as the JVM spells it. `java.lang.Runtime` is deliberately absent: it is banned
 * by [CALL_RUNTIME_EXEC], one method at a time, so that the rest of the class stays usable.
 *
 * The nested-class tail covers `ProcessBuilder$Redirect` and `ProcessHandle$Info`, which are how a
 * caller spells the interesting arguments to the types above.
 *
 * `plugin/build.gradle.kts` states the same pattern for the distribution scan, character for
 * character, so that the two can be diffed by eye; the two are deliberately separate implementations
 * of one policy over different inputs. Typed by name rather than by class because that is what it
 * matches — `dependOnClassesThat` takes either.
 */
private val PROCESS_EXECUTION_CLASSES: DescribedPredicate<HasName> =
    nameMatching("java\\.lang\\.(ProcessBuilder|ProcessHandle|Process)(\\$[\\w$]+)?")
        .`as`("are process-execution classes")

/**
 * Every `Runtime.exec` overload, matched by target name so that no descriptor has to be enumerated,
 * and only that method, so `java.lang.Runtime` stays legal for everything else.
 */
private val CALL_RUNTIME_EXEC =
    object : ArchCondition<JavaClass>("call Runtime.exec") {
        override fun check(item: JavaClass, events: ConditionEvents) {
            item.accessesFromSelf
                .filter { it.targetOwner.name == "java.lang.Runtime" && it.target.name == "exec" }
                .forEach { events.add(SimpleConditionEvent.satisfied(it, it.description)) }
        }
    }

private val NOTHING_STARTS_A_PROCESS: ArchRule =
    noClasses().should().dependOnClassesThat(PROCESS_EXECUTION_CLASSES)
        .because("a subprocess routes around the no-network rule without tripping it")

private val NOTHING_CALLS_RUNTIME_EXEC: ArchRule =
    noClasses().should(CALL_RUNTIME_EXEC)
        .because("Runtime.exec starts a subprocess while leaving java.lang.Runtime looking innocent")

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
internal val NON_TEST_CODE = ImportOption { location ->
    !location.matches(Pattern.compile(".*/(test-classes|classes/[^/]+/test|instrumented/instrumentTestCode)/.*"))
}

/**
 * The main output of both subprojects, taken off this test's own runtime classpath: `:plugin`'s
 * because these tests live in it, `:core`'s because `:plugin` depends on it. That is the same set
 * of classes the distribution's `lib/` is built from.
 *
 * Internal rather than private so that [CommentRetentionIsNeverPersistedTest] reads the same set
 * these rules do. A second importer would be a second definition of *what ships*, and the two would
 * drift on the day one of them is updated.
 */
internal val SHIPPED_CLASSES: JavaClasses = ClassFileImporter()
    .withImportOption(NON_TEST_CODE)
    .importPackages("com.snippetveil")
