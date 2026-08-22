package com.snippetveil.plugin

import com.snippetveil.core.CommentOccurrence
import com.snippetveil.core.LiteralOccurrence
import com.snippetveil.core.SymbolOccurrence
import com.snippetveil.core.SymbolOrigin
import com.snippetveil.core.SymbolRole

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

    /**
     * **A package is reported one segment at a time, each carrying the package it ends.**
     *
     * The qualified name is the fact the engine's rules are stated over: the top-level segment is
     * the one with no dot before it, and two types share a package placeholder by sharing this
     * string. Reporting the segment alone would leave `acme` in `com.acme` indistinguishable from
     * `acme` in `org.acme`, and reporting the whole name at one occurrence would be reporting a
     * symbol the snippet does not contain.
     *
     * **A package's origin cannot be read off a `VirtualFile`, because a `PsiPackage` has none.**
     * It is a directory question instead, and getting it wrong in the fail-closed direction would
     * rename `util` in `java.util.List` — which is why the JDK half of this assertion is here
     * rather than left to the engine's own tests.
     */
    fun `test package segments are reported per segment with the package each one ends`() {
        assertTheHarnessResolves()
        addClassInPackage("com.acme.billing", "Refund")
        val plan = planFor(
            "Ledger.java",
            """
            <selection>package com.acme.billing;

            class Ledger {
                java.util.List<String> rows;
            }</selection>
            """.trimIndent(),
        )

        val packages = plan.symbols().filter { it.symbol.role == SymbolRole.PACKAGE }
        assertEquals(
            listOf(
                "com" to "com",
                "acme" to "com.acme",
                "billing" to "com.acme.billing",
                "java" to "java",
                "util" to "java.util",
            ),
            packages.map { it.text to it.symbol.qualifiedName },
        )
        assertEquals(
            listOf(
                SymbolOrigin.IN_CONTENT,
                SymbolOrigin.IN_CONTENT,
                SymbolOrigin.IN_CONTENT,
                SymbolOrigin.JDK,
                SymbolOrigin.JDK,
            ),
            packages.map { it.symbol.origin },
        )
    }

    /**
     * **An annotation attribute name is a PSI shape a plain `PsiIdentifier` walk misses entirely.**
     *
     * `action` in `@AuditLogged(action = …)` hangs off a `PsiNameValuePair`, which is not a
     * `PsiNameIdentifierOwner` and whose identifier is not part of any reference element — so the
     * walk that finds every other name in this file walks straight past it, and the name is copied
     * through verbatim. Resolving it is a required path rather than an optimisation, and this test
     * is the one that goes red if it is ever dropped: without `PsiNameValuePair.reference` there is
     * no `ATTRIBUTE`-role occurrence in the plan at all, and this list comes back empty.
     *
     * What the reference resolves to is the annotation type's own member, which is what carries the
     * ownership: `action` is declared by a project annotation and `timeout` by JUnit's, and each
     * inherits the origin of the type that declares it.
     */
    fun `test an annotation attribute name resolves through the name-value pair's reference`() {
        assertTheHarnessResolves()
        myFixture.addFileToProject("com/acme/audit/AuditLogged.java", PROJECT_ANNOTATION)
        val plan = planFor(
            "Ledger.java",
            """
            import com.acme.audit.AuditLogged;
            import org.junit.Test;

            class Ledger {
                <selection>@AuditLogged(action = "x")
                @Test(timeout = 100)
                void audit() {}</selection>
            }
            """.trimIndent(),
        )

        val attributes = plan.symbols().filter { it.symbol.role == SymbolRole.ATTRIBUTE }
        assertEquals(
            listOf("action" to SymbolOrigin.IN_CONTENT, "timeout" to SymbolOrigin.LIBRARY),
            attributes.map { it.text to it.symbol.origin },
        )
        assertEquals(
            listOf("method:class:com.acme.audit.AuditLogged#action", "method:class:org.junit.Test#timeout"),
            attributes.map { it.symbol.key },
        )
    }

}
