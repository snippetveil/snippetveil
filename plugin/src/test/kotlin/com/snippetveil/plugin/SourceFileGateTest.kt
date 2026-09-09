package com.snippetveil.plugin

import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.kotlin.idea.base.plugin.KotlinPluginMode
import org.jetbrains.kotlin.idea.base.plugin.KotlinPluginModeProvider
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
 * **What this cannot assert, and what stands in for it.** An IDE with the Kotlin plugin genuinely
 * switched off is an environment rather than an input, and cannot be built from inside a running
 * fixture. Two things cover it instead: the arch rule in `ShippedCodeArchitectureTest`, which asserts
 * over bytecode that nothing the main descriptor reaches could fail to link there, and the
 * Kotlin-disabled boot in `com.snippetveil.boot`, which is a release gate rather than a merge one and
 * boots an IDE that really does not have the plugin.
 */
class SourceFileGateTest : BasePlatformTestCase() {

    /**
     * **Every row of the table in one assertion**, because the failure this guards is a row going
     * missing rather than a row coming back wrong.
     *
     * A method per outcome passes as happily over five rows as over six, and the row a change is
     * most likely to drop is the one it was not thinking about. Asserted as a list against a list so
     * that the report names the file whose verdict moved, rather than the first `assertEquals` to
     * disagree.
     */
    fun `test the gate table, every row`() {
        val table = listOf(
            Row("Payment.java", "class Payment {}", GateVerdict.Offer),
            Row("Payment.kt", "class Payment", kotlinVerdictInThisCell()),
            Row("build.gradle.kts", "plugins {}", GateVerdict.Absent),
            Row("application.properties", "db.password=hunter2", GateVerdict.Absent),
            Row("README.md", "# hello", GateVerdict.Absent),
            Row("Payment.class", "", GateVerdict.Absent),
        )

        assertEquals(
            table.map { it.name to it.verdict },
            table.map { it.name to gate(fileNamed(it.name, it.text)) },
        )
    }

    /**
     * **A kt file is never silently absent**, whatever the outcome — which is the assertion that
     * separates *unsupported* from *unavailable*.
     *
     * It is written as *not [GateVerdict.Absent]* rather than as an equality deliberately, and it is
     * kept beside the table above rather than folded into it: which of the two remaining outcomes a
     * `.kt` file takes is a fact about the configuration, and this is the part of the rule that holds
     * in **every** configuration there is. A change that got the row wrong would fail the table; a
     * change that got the whole idea wrong fails here.
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
     *
     * In the table above as a row, and here as a sentence: the row keeps it from disappearing, and
     * this keeps the reason from having to be inferred from a list.
     */
    fun `test a kts script is silently absent rather than refused`() {
        assertEquals(GateVerdict.Absent, gate(fileNamed("build.gradle.kts", "plugins {}")))
    }

    /** No file at all — a Tools-menu invocation with nothing open. */
    fun `test no file is silently absent`() {
        assertEquals(GateVerdict.Absent, gate(null))
    }

    /**
     * **Decompiled editors get nothing, and this is a withdrawal rather than a gap.**
     *
     * A decompiled class is backed by a `.class` virtual file, which is not in the accepted set — so
     * no menu item appears, decided with no `isCompiled` check and no Kotlin type reference. The
     * previous PSI-typed gate *did* offer the action in a decompiled Java editor, because a
     * decompiled Java file is a `PsiJavaFile`. Kept as its own test beside the row in the table, so
     * that the change stays a decision on the record rather than something a later reader discovers
     * as a regression.
     *
     * The extension is what the gate reads, so a `.class` file is the whole of what it sees; opening
     * a real decompiled editor would exercise the platform's decompiler rather than this predicate.
     * **Library sources attached are the opposite case and stay offered** — navigation lands in a
     * real `.java` or `.kt` inside a jar, which the first row of the table covers.
     */
    fun `test a decompiled class file is silently absent`() {
        assertEquals(GateVerdict.Absent, gate(fileNamed("Payment.class", "")))
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
     * Asserted here of Java support, which is registered in every cell; Kotlin's own half of this is
     * asserted where its classes are safe to run, in `KotlinSupportTest`.
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
        assertTrue(
            "No support is registered for java; the main descriptor's registration is missing.",
            LANGUAGE_SUPPORT.extensionList.any { it.extension == JAVA_EXTENSION },
        )
    }

    private fun fileNamed(name: String, text: String): PsiFile = myFixture.configureByText(name, text)
}

/**
 * One row of the table: the file, what is in it, and what the gate must say about it.
 *
 * A top-level class rather than a nested one, and the reason is the runner rather than taste: the
 * fixtures here are JUnit 3 `TestCase`s, and every class *inside* one of them is offered to the test
 * engine as a test class of its own. A data class holding three fields is not one.
 */
private data class Row(val name: String, val text: String, val verdict: GateVerdict)

/**
 * **What a `.kt` file gets in *this* cell, read off the Kotlin plugin's own mode.**
 *
 * The row is genuinely two answers, and both of them are the product working. `plugin.xml`
 * declares `supportsK1="false" supportsK2="true"`, so in a **K1** session the platform skips
 * `com.snippetveil-withKotlin.xml` outright — nothing registers a support for `kt` and the gate
 * states its refusal, which is exactly what a user on such an IDE sees. In a **K2** session the
 * descriptor loads, the registration is there, and the file is offered.
 *
 * That is why this is not the conditional assertion this codebase otherwise refuses. The
 * expectation is taken from the platform — a fact about the session, decided before any of this
 * plugin's code ran — rather than from the gate, and **both branches are exercised**: the floor
 * cell is a K1 session and the `k2` and `latest` cells are K2 ones, so neither arm is a branch
 * nobody takes. Asking the gate what it thinks and agreeing with it is the version that would
 * assert nothing.
 *
 * It also puts one otherwise-unasserted claim under test: that the declaration in `plugin.xml`
 * has the effect it is written for. If the platform ever stopped skipping the optional descriptor
 * in K1, the floor cell goes red here rather than a user in K1 mode meeting the Analysis API
 * through a path this plugin says it does not support.
 */
private fun kotlinVerdictInThisCell(): GateVerdict = when (KotlinPluginModeProvider.currentPluginMode) {
    KotlinPluginMode.K2 -> GateVerdict.Offer
    KotlinPluginMode.K1 -> GateVerdict.Refuse(Unavailable.PATH_NOT_ACTIVATED)
}

/**
 * The accepted extension the table asserts a registration for, spelled as the descriptor spells it.
 * Named here rather than read from the gate's own constants: a test that took its expectations from
 * the code under test would agree with it about a typo.
 */
private const val JAVA_EXTENSION = "java"
