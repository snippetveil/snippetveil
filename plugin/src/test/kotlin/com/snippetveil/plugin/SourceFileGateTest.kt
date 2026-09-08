package com.snippetveil.plugin

import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertThrows

/**
 * **The source-file gate's tri-state, asserted as a table.**
 *
 * Two of the three outcomes are easy to get wrong in the same direction, which is why they are
 * tested together rather than one per method: a bug that turned [GateVerdict.Refuse] into
 * [GateVerdict.Absent] would make a `.kt` file *silently* unsupported on an IDE where Kotlin is
 * merely switched off — the false reassurance the third outcome exists to prevent — and every
 * assertion written only about `.java` would still pass.
 *
 * **What this cannot assert, and what stands in for it.** The fixture runs with the Kotlin plugin
 * present and enabled, so the cause a refusal carries here is always
 * [Unavailable.PATH_NOT_ACTIVATED]. The other configuration — an IDE with the Kotlin plugin genuinely
 * switched off — is an environment rather than an input and cannot be built from inside a running
 * fixture; what covers it instead is the arch rule in `ShippedCodeArchitectureTest`, which asserts
 * over bytecode that nothing the main descriptor reaches could fail to link there.
 */
class SourceFileGateTest : BasePlatformTestCase() {

    /** The whole accepted set, and it is exactly two extensions. */
    fun `test a java file is offered`() {
        assertEquals(GateVerdict.Offer, gate(fileNamed("Payment.java", "class Payment {}")))
    }

    /**
     * **A kt file is never silently absent**, whatever the outcome — which is the assertion that
     * separates *unsupported* from *unavailable*.
     *
     * It is written as *not [GateVerdict.Absent]* rather than as an equality, deliberately: which of
     * the two remaining outcomes it takes depends on whether a Kotlin `LanguageSupport` is registered
     * in this fixture, and that is a fact about the build rather than about the gate. The rule under
     * test holds either way and is the one a regression would break.
     */
    fun `test a kt file is either offered or refused, and never silently absent`() {
        val verdict = gate(fileNamed("Payment.kt", "class Payment"))
        assertTrue(
            "A .kt file fell into silent absence, which is the false reassurance the third outcome exists to prevent.",
            verdict == GateVerdict.Offer || verdict is GateVerdict.Refuse,
        )
    }

    /**
     * **`.kts` is refused silently, and that is the third finding rather than an oversight.**
     *
     * A script's secrets sit in strings rather than in project symbols; its top-level declarations
     * carry script-derived qualified names that would become permanent ledger keys derived from a
     * file name; and under the distinguishing test a `.kts` would not be anonymized on a
     * correctly-configured IDE either, so a stated refusal here would rebuild the dead menu item the
     * silent outcome exists to avoid.
     */
    fun `test a kts script is silently absent rather than refused`() {
        assertEquals(GateVerdict.Absent, gate(fileNamed("build.gradle.kts", "plugins {}")))
    }

    /** Anything outside the set at all — the case the gate was originally written for. */
    fun `test an unsupported file is silently absent`() {
        assertEquals(GateVerdict.Absent, gate(fileNamed("application.properties", "db.password=hunter2")))
        assertEquals(GateVerdict.Absent, gate(fileNamed("README.md", "# hello")))
    }

    /**
     * **Decompiled editors get nothing, and this is a withdrawal rather than a gap.**
     *
     * A decompiled class is backed by a `.class` virtual file, which is not in the accepted set — so
     * no menu item appears, decided with no `isCompiled` check and no Kotlin type reference. The
     * previous PSI-typed gate *did* offer the action in a decompiled Java editor, because a
     * decompiled Java file is a `PsiJavaFile`. Asserted here deliberately, so that the change is a
     * decision on the record rather than something a later reader discovers as a regression.
     *
     * The extension is what the gate reads, so a `.class` file is the whole of what it sees; opening
     * a real decompiled editor would exercise the platform's decompiler rather than this predicate.
     * **Library sources attached are the opposite case and stay offered** — navigation lands in a
     * real `.java` inside a jar, which the row above covers.
     */
    fun `test a decompiled class file is silently absent`() {
        assertEquals(GateVerdict.Absent, gate(fileNamed("Payment.class", "")))
    }

    /** No file at all — a Tools-menu invocation with nothing open. */
    fun `test no file is silently absent`() {
        assertEquals(GateVerdict.Absent, gate(null))
    }

    /**
     * **The extension is compared as text, case-insensitively.**
     *
     * `Payment.JAVA` is a Java file on a case-insensitive filesystem, and a gate that compared it
     * case-sensitively would fall into silent absence on a file the IDE itself treats as Java —
     * the same failure as the `.kt` one above, arriving through a filesystem instead of a plugin.
     */
    fun `test the extension test is case-insensitive`() {
        assertEquals(GateVerdict.Offer, gate(fileNamed("Payment.JAVA", "class Payment {}")))
    }

    /**
     * **Layer two is a real predicate, not a rubber stamp on the gate's verdict.**
     *
     * The two layers exist because they can be wrong in different ways: the gate reads a name and can
     * be lied to, and the builder reads the tree and cannot. A dispatch that claimed every file the
     * gate offered would collapse them into one, and the failure would be silent — the accepted cost
     * of an extension predicate is precisely that a file whose extension lies gets through layer one.
     *
     * Asserted in the direction that is constructible: Java support refuses files it does not own,
     * on PSI rather than on their names.
     */
    fun `test java support reaches its own verdict rather than inheriting the gate's`() {
        val java = JavaSupport()

        assertTrue("Java support does not claim an ordinary Java file.", java.claims(fileNamed("Payment.java", "class Payment {}")))
        assertFalse("Java support claimed a Kotlin file.", java.claims(fileNamed("Payment.kt", "class Payment")))
        assertFalse("Java support claimed a properties file.", java.claims(fileNamed("app.properties", "a=b")))
    }

    /**
     * **A file nothing claims throws, and that is the designed answer.**
     *
     * It means the gate believed an extension that lied. The fail-closed guarantee takes it from
     * there — the clipboard is left byte-identical and the user is told the operation failed, rather
     * than handed a file that was copied unchanged, which is the worst outcome available to a privacy
     * tool. Asserted because *throws* is easy to turn into *returns an empty plan* by accident, and
     * an empty plan copies the original text out verbatim.
     */
    fun `test the dispatching builder throws on a file no language claims`() {
        val unclaimed = fileNamed("notes.md", "# hello")
        val request = SnippetRequest(project, unclaimed, emptyList())

        assertThrows(IllegalStateException::class.java) { DispatchingPlanBuilder.build(request) }
    }

    /**
     * **Java support is registered from the main descriptor and can never be absent**, which is why
     * the gate's third outcome is unreachable for Java.
     */
    fun `test java support is always registered`() {
        val java = fileNamed("Payment.java", "class Payment {}")
        assertTrue(
            "No LanguageSupport claims an ordinary Java file; the main descriptor's registration is missing.",
            LANGUAGE_SUPPORT.extensionList.any { it.instance.claims(java) },
        )
    }

    /**
     * **The availability signal is asked per language, and asking it in aggregate is the way to get
     * this wrong without noticing.**
     *
     * Java's support is registered from the main descriptor on every IDE there is, so *is anything
     * registered?* comes back `yes` on a build with no Kotlin support at all — the gate would offer
     * every `.kt` file, dispatch would find nothing that claims it, and the user would get a thrown
     * error with a report link instead of the sentence describing their configuration. That is the
     * third outcome quietly deleted, and every assertion above still passes.
     *
     * The registration is read off [LanguageSupportBean.extension], which is a string in the
     * descriptor: reading it instantiates no implementation and therefore links no Kotlin class.
     *
     * **This test moves when a Kotlin support is registered.** Nothing registers `kt` today — the
     * optional descriptor is empty on purpose, and it stays empty even now that `KotlinPlanBuilder`
     * exists — so the refusal is what a `.kt` file gets, and that being asserted here is what makes
     * the arrival of a Kotlin support visible rather than silent.
     */
    fun `test the availability signal is asked per language rather than in aggregate`() {
        assertTrue(
            "No support is registered for java; the main descriptor's registration is missing.",
            LANGUAGE_SUPPORT.extensionList.any { it.extension == JAVA_EXTENSION },
        )
        assertFalse(
            "A support is registered for kt. The Kotlin half has landed, so this test and the " +
                "refusal assertions in KotlinUnavailableTest belong on a fixture that unregisters it.",
            LANGUAGE_SUPPORT.extensionList.any { it.extension == KOTLIN_EXTENSION },
        )

        assertEquals(
            "A .kt file was offered while nothing was registered to build a plan for it.",
            GateVerdict.Refuse(Unavailable.PATH_NOT_ACTIVATED),
            gate(fileNamed("Payment.kt", "class Payment")),
        )
    }

    private fun fileNamed(name: String, text: String): PsiFile = myFixture.configureByText(name, text)

    private companion object {

        /**
         * The two accepted extensions, spelled as the descriptor spells them. Named here rather than
         * read from the gate's own constants: a test that took its expectations from the code under
         * test would agree with it about a typo.
         */
        const val JAVA_EXTENSION = "java"
        const val KOTLIN_EXTENSION = "kt"
    }
}
