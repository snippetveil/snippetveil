package com.snippetveil.plugin.kotlin

import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.testFramework.LightProjectDescriptor
import com.snippetveil.plugin.FixtureOrigin
import com.snippetveil.plugin.JavaSnippetTestCase
import com.snippetveil.plugin.SnippetRequest
import com.snippetveil.plugin.RealClasspath
import com.snippetveil.plugin.attachJar
import com.snippetveil.plugin.complaintAboutFixtureOrigin
import com.snippetveil.plugin.originInTheFixture
import com.snippetveil.plugin.selectedRangesOf
import com.snippetveil.core.AnonymizationResult
import com.snippetveil.core.AnonymizationSettings
import com.snippetveil.core.LedgerSnapshot
import com.snippetveil.core.SnippetPlan
import com.snippetveil.core.anonymize
import org.jetbrains.kotlin.asJava.KotlinAsJavaSupport
import org.jetbrains.kotlin.asJava.elements.KtLightElement
import org.jetbrains.kotlin.idea.base.plugin.KotlinPluginMode
import org.jetbrains.kotlin.idea.base.plugin.KotlinPluginModeProvider
import org.jetbrains.kotlin.psi.KtFile
import java.io.File

/**
 * The Java fixture's classpath with the **Kotlin standard library** on it, and four assertions the
 * harness makes about itself before anything is measured through it.
 *
 * **Every Kotlin trap here fails in the green direction, and the sharpest one fails green by
 * renaming _more_.** A fixture with no standard library attached resolves every Kotlin symbol to
 * `null`; unresolved fails closed into the `Unknown` namespace, so *everything* is renamed — and the
 * leak oracle, whose whole question is *did anything project-owned survive into the output*, comes
 * back maximally green because the harness is broken. The output of a broken run is cleaner than the
 * output of a correct one, which is why no ordinary assertion catches it.
 *
 * So the preconditions are written the way `JavaSnippetTestCase.assertTheHarnessResolves` is
 * written — as assertions the harness makes about itself, each with a message that says what is
 * wrong with the *fixture* rather than with the code under test:
 *
 *  - [assertTheKotlinStandardLibraryIsALibrary] — a `kotlin.*` member resolves, to `LIBRARY`.
 *  - [assertTheSessionIsK2] — the analysis session is K2, the only mode this plugin declares.
 *  - [assertJavaReachesKotlinThroughALightClass] — the Java↔Kotlin bridge is present.
 *  - [assertTheFacadeBehaviourIsPinned] — the platform's facade behaviour is the one recorded here.
 *
 * Each of the four is a [String]-returning complaint underneath, and every one of them is
 * demonstrated red: over a deliberately broken fixture in [KotlinHarnessMissingHalvesTest] where a
 * fixture can produce the failure, and over the complaint itself in [KotlinHarnessComplaintTest]
 * where only the platform can. An assertion nobody has seen fail is a comment.
 *
 * **Nothing here is reachable from a user's IDE.** `com.snippetveil-withKotlin.xml` still registers
 * no `languageSupport`, so every `.kt` file keeps taking the stated refusal that
 * `KotlinUnavailableTest` asserts, and [kotlinPlanFor] constructs the walk directly. These are the
 * assertions that have to be in place *before* the first Kotlin fixture whose result anyone
 * believes.
 */
internal abstract class KotlinSnippetTestCase : JavaSnippetTestCase() {

    override fun getProjectDescriptor(): LightProjectDescriptor = KOTLIN_CLASSPATH

    /**
     * The plan the Kotlin walk builds for [text], whose `<selection>` markers say what is selected —
     * **the production walk, constructed directly.**
     *
     * Directly, because there is nothing to reach it through: `com.snippetveil-withKotlin.xml`
     * registers no `languageSupport`, so no dispatch finds this builder and every `.kt` file still
     * takes the gate's stated refusal. That is the shape of this change rather than a gap in it.
     */
    protected fun kotlinPlanFor(path: String, text: String): SnippetPlan {
        val file = myFixture.configureByText(path.substringAfterLast('/'), text)
        return KotlinPlanBuilder.build(SnippetRequest(project, file, selectedRangesOf(myFixture.editor)))
    }

    /**
     * What the engine makes of [kotlinPlanFor]'s plan — the whole invocation, from tree to clipboard
     * text, with no reduction of any kind and an empty ledger.
     *
     * Here rather than in one test class because *what a Kotlin token comes out as* is asked from
     * both sides of the same question: what is spliced, and what is silent. Two spellings of this
     * would be two ways for a fixture to differ from the one the other class measured.
     */
    protected fun kotlinOutputFor(path: String, text: String): String = kotlinResultFor(path, text).text

    /**
     * The whole of what the engine made of [kotlinPlanFor]'s plan, for the assertions that are about
     * something other than the characters — the mapping table a reader decodes a reply against, and
     * the counts a balloon reports.
     *
     * [kotlinOutputFor] is this function's `text`, rather than a second invocation beside it: a
     * fixture that measured the output through one call and the counts through another would be
     * measuring two runs, and *the count belongs to this output* is exactly what those assertions
     * claim.
     */
    protected fun kotlinResultFor(path: String, text: String): AnonymizationResult =
        anonymize(kotlinPlanFor(path, text), AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

    /**
     * **A `kotlin.*` member resolves, and resolves to _library_ origin** — not `null`, and not
     * project.
     *
     * Both halves are load-bearing. `null` is the fail-green case in this class's own header: a
     * fixture with no standard library renames everything and looks perfect doing it. **Project**
     * origin is the mirror failure — a standard library that arrived as project content would make
     * every `kotlin.*` name a candidate for renaming, and the spine rule (*anonymize a symbol iff its
     * declaring file is project-owned*) would be under test against a fixture that lies about which
     * files those are.
     *
     * `kotlin.random.Random` rather than `kotlin.String`: the built-in types are supplied by the
     * compiler itself and resolve with no library attached at all, so a probe on one of them would be
     * green on exactly the broken fixture this exists to catch.
     */
    protected fun assertTheKotlinStandardLibraryIsALibrary() {
        val probe = myFixture.addFileToProject(
            "com/acme/probe/StandardLibrary.kt",
            """
            package com.acme.probe

            class StandardLibrary {
                val random: kotlin.random.Random? = null
                val regex: kotlin.text.Regex? = null
            }
            """.trimIndent(),
        )

        for (name in STANDARD_LIBRARY_PROBES) {
            val resolved = probe.resolveQualifiedName(name)
            complaintAboutFixtureOrigin(name, FixtureOrigin.LIBRARY, originInTheFixture(project, resolved))
                ?.let { fail("$it The Kotlin standard library is not attached to this fixture as a library.") }
        }
    }

    /**
     * **The analysis session is K2, and a K1/FE10 session fails the run.**
     *
     * `plugin.xml` declares `supportsK1="false" supportsK2="true"`, so K1 is a configuration this
     * plugin says it does not support and where the platform skips its optional descriptor outright.
     * A fixture running in K1 mode measures that unsupported configuration — and does it silently,
     * because which Analysis API components the legacy FE10 engine leaves unimplemented is not
     * enumerated anywhere this project can read.
     *
     * This is also what makes the `k2` platform cell mean something. On the compatibility floor the
     * Kotlin plugin's default mode is K1, so a Kotlin fixture that ran there would either be
     * measuring K1 or skipping in silence, and the difference has to be visible: the build keeps the
     * floor cell Java-only, and this fails the run if that ever stops being true.
     */
    protected fun assertTheSessionIsK2() {
        complaintAboutTheSessionMode(KotlinPluginModeProvider.currentPluginMode)?.let { fail(it) }
    }

    /**
     * **A Java reference to a Kotlin declaration resolves, and reaches a light element.**
     *
     * The Java↔Kotlin bridge is what makes one shared ledger possible: a Java file's reference to a
     * Kotlin declaration resolves to a light class, and that is where the two languages meet without
     * any Kotlin code in this plugin at all. If the bridge is absent in this fixture, every later
     * assertion that rests on it holds **vacuously** — the same green failure as
     * [assertTheKotlinStandardLibraryIsALibrary], one layer out.
     *
     * A survey of the corpus found no case where this bridge is missing. This promotes that finding
     * from a number somebody measured once into a standing precondition, and it doubles as the
     * light-class self-test.
     */
    protected fun assertJavaReachesKotlinThroughALightClass() {
        writeTheKotlinDeclarationTheBridgeReachesFor()
        val probe = myFixture.addFileToProject(
            "com/acme/probe/UsesBridged.java",
            """
            package com.acme.probe;

            class UsesBridged {
                com.acme.probe.Bridged bridged;
            }
            """.trimIndent(),
        )

        val resolved = probe.resolveQualifiedName(BRIDGE_PROBE)
        complaintAboutTheBridge(resolved?.javaClass?.name, resolved is KtLightElement<*, *>)?.let { fail(it) }
    }

    /**
     * The Kotlin declaration [assertJavaReachesKotlinThroughALightClass] reaches for from Java.
     *
     * A step of its own so that a fixture can be built **without** it, which is how that assertion is
     * shown red: a fixture missing its Kotlin half is exactly the state in which every assertion
     * resting on the bridge would hold vacuously, and it is the state no fixture can wander into
     * unnoticed once this is asserted. See `KotlinHarnessMissingHalvesTest`.
     */
    protected open fun writeTheKotlinDeclarationTheBridgeReachesFor() {
        myFixture.addFileToProject(
            "com/acme/probe/Bridged.kt",
            """
            package com.acme.probe

            class Bridged(val amount: Int)
            """.trimIndent(),
        )
    }

    /**
     * **The platform's facade behaviour, pinned rather than tested conditionally.**
     *
     * Whether a `KtLightClassForFacade` exists for a `.kt` file with **no top-level callables** is
     * not documented. The tempting shape is a conditional assertion — *assert the facade placeholder
     * if a facade exists, otherwise assert the fallback* — and that test is green under both
     * behaviours and therefore asserts nothing.
     *
     * So the behaviour that is true is written down: **a `.kt` file with no top-level callables has
     * no facade light class, and one with a top-level callable has one.** Measured on
     * IntelliJ IDEA Community 2025.1.7.2 and IntelliJ IDEA 2026.2.1 in K2 mode, and on 2024.2.6 and
     * 2024.3.7.1 in K1 — the same answer in all four.
     *
     * The payoff is where the failure goes. If JetBrains changes it, **this precondition goes red
     * naming a platform change**, instead of some later rendering test going red and being triaged as
     * a regression in this plugin. Anything written against facade rendering can then be written
     * unconditionally, which is the only way it asserts anything.
     */
    protected fun assertTheFacadeBehaviourIsPinned() {
        val withoutCallables = myFixture.addFileToProject(
            "com/acme/probe/OnlyAClass.kt",
            """
            package com.acme.probe

            class OnlyAClass(val amount: Int)
            """.trimIndent(),
        )
        val withCallable = myFixture.addFileToProject(
            "com/acme/probe/HasATopLevelCallable.kt",
            """
            package com.acme.probe

            fun settle(amount: Int): Int = amount
            """.trimIndent(),
        )

        complaintAboutTheFacades(
            facadeWithoutCallables = facadeOf(withoutCallables),
            facadeWithCallable = facadeOf(withCallable),
        )?.let { fail(it) }
    }

    /**
     * The facade light class the platform reports for [file] — as the text that names it, or `null`
     * where the platform reports none, which is the whole of what the pin reads.
     */
    private fun facadeOf(file: PsiFile): String? =
        KotlinAsJavaSupport.getInstance(project).getLightFacade(file as KtFile)?.toString()

    /**
     * What the last segment of [name] resolves to in this file — `Random` out of
     * `kotlin.random.Random`, which is the reference the qualified name ends in.
     *
     * A probe file that does not contain [name] is a fixture error rather than a resolution failure,
     * and it says so: an offset computed from a name that is not there would point somewhere real and
     * report whatever it found.
     */
    private fun PsiFile.resolveQualifiedName(name: String): PsiElement? {
        val at = text.indexOf(name)
        check(at >= 0) { "The probe file ${this.name} does not name $name, so nothing about it holds." }
        return findReferenceAt(at + name.lastIndexOf('.') + 1)?.resolve()
    }

    private companion object {

        /** The Kotlin classes the standard-library probe names, in the order it writes them. */
        val STANDARD_LIBRARY_PROBES = listOf("kotlin.random.Random", "kotlin.text.Regex")
    }
}

/** The Kotlin declaration the bridge probe reaches for from Java. */
internal const val BRIDGE_PROBE = "com.acme.probe.Bridged"

/**
 * What is wrong with the fixture when the analysis session came back [mode], or `null` when nothing
 * is.
 *
 * Separated from the assertion because **only the platform can produce the failure**: a K1 session is
 * a property of which IDE the cell runs, and no fixture inside a K2 build can manufacture one. The
 * complaint is a value so that `KotlinHarnessDemonstrationTest` can hand it the mode this build must
 * never run in and watch it fire.
 */
internal fun complaintAboutTheSessionMode(mode: KotlinPluginMode): String? =
    if (mode == KotlinPluginMode.K2) {
        null
    } else {
        "The Kotlin analysis session is $mode. `plugin.xml` declares supportsK1=\"false\", so this " +
            "fixture is measuring a configuration this plugin says it does not support — and which " +
            "Analysis API components FE10 leaves unimplemented is enumerated nowhere. The `k2` " +
            "platform cell is the only one a Kotlin fixture may run in; see gradle.properties."
    }

/**
 * What is wrong with the fixture when a Java reference to a Kotlin declaration came back as
 * [resolvedType] — the name of what it resolved to, or `null` for nothing — and [isLightElement], or
 * `null` when nothing is.
 *
 * The two facts rather than the element itself: *is this a light element* is a question about a
 * Kotlin-plugin type that the caller has already had to answer, and a complaint that asked it again
 * would be a second answer to it.
 */
internal fun complaintAboutTheBridge(resolvedType: String?, isLightElement: Boolean): String? = when {
    resolvedType == null ->
        "A Java reference to the Kotlin declaration `$BRIDGE_PROBE` did not resolve. The Java↔Kotlin " +
            "bridge is absent from this fixture, so every assertion that rests on it holds vacuously " +
            "— including anything one shared ledger across the two languages means."
    !isLightElement ->
        "A Java reference to `$BRIDGE_PROBE` resolved to $resolvedType, which is not a light element. " +
            "The bridge between the two languages is what a Java file reaching a Kotlin declaration " +
            "goes through, and this fixture is not going through it."
    else -> null
}

/**
 * What is wrong with the platform when a `.kt` file with no top-level callables reports
 * [facadeWithoutCallables] and one with a top-level callable reports [facadeWithCallable], or `null`
 * when the pinned behaviour still holds.
 *
 * Both directions, because a pin that only checked the absent half would be satisfied by a platform
 * that had stopped making facades at all.
 */
internal fun complaintAboutTheFacades(facadeWithoutCallables: String?, facadeWithCallable: String?): String? = when {
    facadeWithoutCallables != null ->
        "A .kt file with no top-level callables now has the facade light class $facadeWithoutCallables. " +
            "That is a change in the platform, not in this plugin: the behaviour pinned here is that " +
            "no facade exists for such a file. Anything written against facade rendering was written " +
            "unconditionally on the strength of this pin, so re-pin it deliberately."
    facadeWithCallable == null ->
        "A .kt file with a top-level callable has no facade light class. That is a change in the " +
            "platform, not in this plugin, and it is the half of the pin that says the platform is " +
            "still making facades at all."
    else -> null
}

/**
 * The Java fixture's classpath — the running JDK and one real jar — with a real Kotlin standard
 * library added to it.
 *
 * **Handed in by the build rather than found here.** The IDE's own Kotlin runtime is not a library
 * any fixture module has, and a fixture that went hunting for one on its own test classpath would
 * find whichever copy the platform happened to load. `plugin/build.gradle.kts` resolves the jar and
 * names it in this property; a fixture that cannot see it says so rather than resolving every Kotlin
 * name to `null` and passing.
 */
private val KOTLIN_CLASSPATH: LightProjectDescriptor = KotlinClasspath()

/** A class of its own rather than an anonymous one: see [RealClasspath.attachLibraries]. */
private class KotlinClasspath : RealClasspath() {

    override fun attachLibraries(model: ModifiableRootModel) {
        super.attachLibraries(model)
        attachJar(model, "kotlin-stdlib", kotlinStandardLibraryJar())
    }
}

/**
 * The standard-library jar the build resolved, or a failure that says the wiring is missing rather
 * than letting the fixture come up without it.
 */
private fun kotlinStandardLibraryJar(): String {
    val named = System.getProperty(KOTLIN_STDLIB_PROPERTY)
        ?: error(
            "-D$KOTLIN_STDLIB_PROPERTY is not set, so no Kotlin standard library can be attached and " +
                "every kotlin.* name in every fixture would resolve to null. The `test` task in " +
                "plugin/build.gradle.kts sets it from the `kotlinFixtureStdlib` configuration."
        )
    check(File(named).isFile) { "$KOTLIN_STDLIB_PROPERTY names $named, which is not a file." }
    return named
}

private const val KOTLIN_STDLIB_PROPERTY = "snippetveil.kotlin.stdlibJar"
