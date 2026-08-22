package com.snippetveil.plugin

import com.snippetveil.core.CommentOccurrence
import com.snippetveil.core.LiteralOccurrence
import com.snippetveil.core.SnippetPlan
import com.snippetveil.core.SymbolOccurrence
import com.snippetveil.core.SymbolOrigin

/**
 * The seam from the plugin's side: what the plan *says*, rather than what the engine does with it.
 *
 * These are the assertions that keep the boundary honest. Everything the engine decides is tested
 * against a plan literal in `:core` at millisecond speed; what cannot be tested there is whether the
 * plan told the truth about a real file, and that is what is here.
 */
class JavaPlanBuilderTest : JavaSnippetTestCase() {

    /**
     * **The plan builder must not "helpfully" pre-judge.**
     *
     * Here the analysed file and the library class share a root package, which is exactly the
     * evidence a later rule uses to pull internal-org libraries into the anonymized set. The builder
     * reports `LIBRARY` anyway, and reports the root package alongside it, so that the decision
     * stays in the module a test can reach without booting an IDE. A builder that started matching
     * prefixes here would move the product's one persistent knob into the layer that needs a fixture
     * to test.
     */
    fun `test a library symbol is reported as LIBRARY even when its package matches the file's own`() {
        assertTheHarnessResolves()
        val plan = planFor(
            "org/junit/probe/Probe.java",
            """
            package org.junit.probe;

            class Probe {
                <selection>org.junit.Test library;</selection>
            }
            """.trimIndent(),
        )

        assertEquals("org.junit", plan.rootPackage)
        assertEquals(
            SymbolOrigin.LIBRARY,
            plan.symbols().single { it.text == "Test" }.symbol.origin,
        )
    }

    /**
     * Literals and comments are described, not acted on. They are in the plan because it describes
     * the snippet rather than a work list — a description that left them out would be a lie about
     * what is in the text the engine is handed, and the tickets that redact literals and strip
     * comments read exactly these shapes.
     */
    fun `test literals and comments are reported as occurrences`() {
        assertTheHarnessResolves()
        val plan = planFor(
            "Ledger.java",
            """
            class Ledger {
                <selection>/** merchant ledger */
                String label = "settlement";</selection>
            }
            """.trimIndent(),
        )

        val comment = plan.occurrences.filterIsInstance<CommentOccurrence>().single()
        val literal = plan.occurrences.filterIsInstance<LiteralOccurrence>().single()
        assertEquals("/** merchant ledger */", plan.text.substring(comment.start, comment.end))
        assertEquals("\"settlement\"", plan.text.substring(literal.start, literal.end))
    }

    /**
     * Overloads are keyed identically and their signatures are reported anyway. That is the shape
     * the whole seam is built on: the evidence crosses, and the rule that reads it — or declines to,
     * as the collapse rule does here — lives on the other side.
     */
    fun `test overloads share a key and still report their signatures`() {
        assertTheHarnessResolves()
        val plan = planFor(
            "Ledger.java",
            """
            class Ledger {
                <selection>void send(String body) {}
                void send(String body, int retries) {}</selection>
            }
            """.trimIndent(),
        )

        val sends = plan.symbols().filter { it.text == "send" }
        assertEquals(2, sends.size)
        assertEquals(1, sends.map { it.symbol.key }.distinct().size)
        assertEquals(
            listOf("(java.lang.String)", "(java.lang.String,int)"),
            sends.map { it.symbol.signature },
        )
    }

    /**
     * A reference that resolves to nothing is reported as unresolved rather than dropped. Red code
     * is ordinary — it is the state the snippet a developer is debugging is usually in — and what to
     * do about it is the engine's call, not the builder's.
     *
     * The builder's half of it has to be exact, though, now that the engine fails these closed and
     * the count is on the balloon: **"the IDE could not resolve this" is a claim, and reporting it
     * about a reference the builder never asked the IDE about would be a false one.**
     */
    fun `test an unresolved reference is reported as UNRESOLVED`() {
        assertTheHarnessResolves()
        val plan = planFor(
            "Ledger.java",
            """
            class Ledger {
                void audit() { <selection>MissingType x = undefinedVar;</selection> }
            }
            """.trimIndent(),
        )

        assertEquals(
            listOf("MissingType" to SymbolOrigin.UNRESOLVED, "undefinedVar" to SymbolOrigin.UNRESOLVED),
            plan.symbols().filter { it.text != "x" }.map { it.text to it.symbol.origin },
        )
    }

    /**
     * **A resolution the language would reject is not a resolution.** `String.length()` names a real
     * JDK method and a plain `resolve()` hands it straight back, so the plan would report `JDK` and
     * the engine would preserve the name on the strength of it. An instance method cannot be reached
     * through a class name, so what the IDE can actually vouch for here is nothing, and that is what
     * the plan says.
     */
    fun `test a reference the language rejects is reported as UNRESOLVED`() {
        assertTheHarnessResolves()
        val plan = planFor(
            "Ledger.java",
            """
            class Ledger {
                <selection>static int broken() { return String.length(); }</selection>
            }
            """.trimIndent(),
        )

        // The qualifier resolves cleanly and is unaffected: only the member reached through it goes.
        assertEquals(
            listOf("String" to SymbolOrigin.JDK, "length" to SymbolOrigin.UNRESOLVED),
            plan.symbols().filter { it.text != "broken" }.map { it.text to it.symbol.origin },
        )
    }

    /** Locals, parameters and type parameters are project-owned by construction — no index asked. */
    fun `test locals parameters and type parameters are project-owned`() {
        assertTheHarnessResolves()
        val plan = planFor(
            "Ledger.java",
            """
            class Ledger {
                <selection><T> void audit(T seed) { T copy = seed; }</selection>
            }
            """.trimIndent(),
        )

        assertEquals(
            setOf(SymbolOrigin.IN_CONTENT),
            plan.symbols().map { it.symbol.origin }.toSet(),
        )
    }

    /** The plan's text is the snapped selection verbatim, which is what every offset indexes into. */
    private fun planFor(path: String, text: String): SnippetPlan {
        val file = myFixture.configureByText(path.substringAfterLast('/'), text)
        // The production walk, not a copy of it: a reimplementation here could pass while the
        // action's own reading of the selection drifted out from under it.
        return JavaPlanBuilder.build(SnippetRequest(project, file, selectedRangesOf(myFixture.editor)))
    }

}

private fun SnippetPlan.symbols(): List<SymbolOccurrence> = occurrences.filterIsInstance<SymbolOccurrence>()
