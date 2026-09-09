package com.snippetveil.plugin.kotlin

import com.snippetveil.plugin.DispatchingPlanBuilder
import com.snippetveil.plugin.GateVerdict
import com.snippetveil.plugin.KOTLIN_EXTENSION
import com.snippetveil.plugin.LANGUAGE_SUPPORT
import com.snippetveil.plugin.SnippetRequest
import com.snippetveil.plugin.clipboard
import com.snippetveil.plugin.gate
import com.snippetveil.plugin.selectedRangesOf
import com.snippetveil.plugin.setClipboard

/**
 * **The registration that opens the gate, from the outside: a `.kt` file goes in and anonymized text
 * comes off the clipboard.**
 *
 * Everything the Kotlin half needed was in place before this — the walk, the keys, the rendering —
 * and none of it was reachable from a user's IDE, because `com.snippetveil-withKotlin.xml` registered
 * nothing and every `.kt` file took the gate's stated refusal. One line in that descriptor is what
 * changes here, and these are the assertions that it changed anything: the support is registered, the
 * gate offers, dispatch finds the Kotlin builder without being told where it is, and the clipboard
 * holds a snippet whose project-owned names are gone.
 *
 * **In the Kotlin sub-package because of where it can honestly run.** The floor cell's Kotlin plugin
 * defaults to K1, where the platform skips the optional descriptor on the strength of this plugin's
 * own `supportsK1="false"` — so on the floor there is no registration to assert and a `.kt` file is
 * refused, which is the row `SourceFileGateTest` asserts there and `KotlinUnavailableTest` asserts in
 * every cell. This runs in the `k2` and `latest` cells, which are the configurations where a user's
 * Kotlin path actually loads.
 */
internal class KotlinSupportTest : KotlinSnippetTestCase() {

    /**
     * **The registration itself**, read the way the gate reads it: off the bean's attribute, without
     * instantiating anything.
     *
     * A descriptor that stopped declaring this would take the whole Kotlin half out of every user's
     * IDE while every fixture that constructs [KotlinPlanBuilder] directly stayed green — which is
     * precisely how this plugin spent several changes with a working Kotlin walk nobody could reach.
     */
    fun `test a support is registered for kt`() {
        assertEquals(
            listOf(KotlinSupport::class.java.name),
            LANGUAGE_SUPPORT.extensionList
                .filter { it.extension.equals(KOTLIN_EXTENSION, ignoreCase = true) }
                .map { it.implementationClass },
        )
    }

    /** The gate's first outcome, on the file that used to take its third. */
    fun `test a kt file is offered`() {
        assertEquals(GateVerdict.Offer, gate(myFixture.configureByText("Payment.kt", "class Payment")))
    }

    /**
     * **Layer two is a real predicate, not a rubber stamp on the gate's verdict**, and the two files
     * it has to turn away are the two the gate would never have sent it.
     *
     * A `.kts` script is a `KtFile` and `build.gradle.kts` is the file a user is most likely to try
     * this on; the gate refuses it silently, and a support that claimed one would reopen that decision
     * from the layer meant to agree with it. A `.java` file is the other direction: two supports that
     * both claimed it would make *which builder ran* depend on registration order.
     */
    fun `test kotlin support reaches its own verdict rather than inheriting the gate's`() {
        val kotlin = KotlinSupport()

        assertTrue(
            "Kotlin support does not claim an ordinary Kotlin file.",
            kotlin.claims(myFixture.configureByText("Payment.kt", "class Payment")),
        )
        assertFalse(
            "Kotlin support claimed a script, which the gate refuses silently.",
            kotlin.claims(myFixture.configureByText("build.gradle.kts", "plugins {}")),
        )
        assertFalse(
            "Kotlin support claimed a Java file.",
            kotlin.claims(myFixture.configureByText("Payment.java", "class Payment {}")),
        )
    }

    /**
     * **Dispatch finds the Kotlin builder through the registration**, rather than through a fixture
     * that already knows where it is.
     *
     * Every other Kotlin fixture in this package constructs [KotlinPlanBuilder] directly, because
     * until this change there was nothing to reach it through. This is the one that goes the way a
     * user's invocation goes: the registered class is instantiated by the platform, claims the file
     * on its own PSI, and hands back the walk.
     */
    fun `test dispatch reaches the Kotlin walk through the registration`() {
        assertTheHarnessResolves()
        val file = myFixture.configureByText(
            "Ledger.kt",
            """
            class Ledger(val merchantRef: String) {
                <selection>fun show(): String = merchantRef</selection>
            }
            """.trimIndent(),
        )

        val plan = DispatchingPlanBuilder.build(SnippetRequest(project, file, selectedRangesOf(myFixture.editor)))

        assertEquals("fun show(): String = merchantRef", plan.text)
        assertTrue(
            "The plan named no symbol, so the walk that produced it was not the Kotlin one.",
            plan.occurrences.isNotEmpty(),
        )
    }

    /**
     * **The whole invocation, which is the thing a user can see for the first time here.**
     *
     * Through the real action rather than through the builder: the gate decides, the platform
     * instantiates the registered support, the Kotlin walk runs, the engine renames and the clipboard
     * is written. A test that stopped at the plan would have passed on every build since the walk
     * landed, including the ones where nothing was registered at all.
     *
     * The expected text is spelled out rather than described, for the reason the Java tracer bullet
     * spells its own: what is being asserted is that project-owned names left and everything else
     * stayed byte-identical, and a test that only checked *the clipboard changed* would pass on a copy
     * that had anonymized nothing.
     */
    fun `test Copy Anonymized writes an anonymized Kotlin selection to the clipboard`() {
        assertTheHarnessResolves()
        setClipboard(PREVIOUS_CLIPBOARD)
        myFixture.configureByText(
            "Ledger.kt",
            """
            package com.acme.ledger

            class Ledger(val merchantRef: String) {
                <selection>fun show(): String = merchantRef</selection>
            }
            """.trimIndent(),
        )

        invokeCopyAnonymized()

        assertEquals("fun method1(): String = field2", clipboard())
    }

    private companion object {

        /** Something recognisable to leave on the clipboard, so that *rewritten* is readable. */
        const val PREVIOUS_CLIPBOARD = "the text the user copied before reaching for SnippetVeil"
    }
}
