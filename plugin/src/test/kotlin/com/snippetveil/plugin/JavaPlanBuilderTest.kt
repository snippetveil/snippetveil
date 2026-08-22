package com.snippetveil.plugin

import com.snippetveil.core.CommentOccurrence
import com.snippetveil.core.CommentVerdict
import com.snippetveil.core.SnippetPlan
import com.snippetveil.core.LiteralKind
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
     * reports `LIBRARY` anyway, and reports the root package and the symbol's own package alongside
     * it, so that the decision stays in the module a test can reach without booting an IDE. A builder
     * that started matching prefixes here would move the product's one persistent knob into the layer
     * that needs a fixture to test.
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

        val library = plan.symbols().single { it.text == "Test" }.symbol
        assertEquals(SymbolOrigin.LIBRARY, library.origin)
        assertEquals("org.junit", library.packageName)
    }

    /**
     * **A member reports the package of the type that declares it**, which is the evidence the
     * prefix rule has to read: `assertNotNull` is not a name Java qualifies, and a rule that could
     * only reach types would rename an internal library's classes while leaving its method and field
     * names — the domain vocabulary — on the clipboard.
     *
     * The package segment reports itself, for the same reason: every rule that reads this field is
     * asking which package a name is part of, and a package is part of itself.
     */
    fun `test a member and a package segment report the package they belong to`() {
        assertTheHarnessResolves()
        addClassInPackage("com.acme.billing", "Payment")
        val plan = planFor(
            "Ledger.java",
            """
            class Ledger {
                <selection>void check(String value) {
                    org.junit.Assert.assertNotNull(value);
                    com.acme.billing.Payment payment = null;
                }</selection>
            }
            """.trimIndent(),
        )

        val packageOf = plan.symbols().associate { it.text to it.symbol.packageName }
        assertEquals("org.junit", packageOf["assertNotNull"])
        assertEquals("org.junit", packageOf["Assert"])
        assertEquals("com.acme.billing", packageOf["billing"])
        assertEquals("com.acme.billing", packageOf["Payment"])
    }

    /**
     * The plan the production walk builds for [source] with **no editor open on it** — nothing is
     * selected, so the plan covers the whole file.
     *
     * [planFor] configures an editor, and opening one over a file whose literal is delimited by
     * unicode escapes trips an assertion inside the platform's own string lexer. That is a fact
     * about the platform rather than about this plugin, and the plan builder never needed an editor.
     */
    private fun planWithoutAnEditor(source: String): SnippetPlan {
        val file = myFixture.addFileToProject("probe/Probe.java", source)
        return JavaPlanBuilder.build(SnippetRequest(project, file, emptyList()))
    }

    /**
     * A comment is described whole and with the verdict a parser reached about its body; a literal is
     * described down to where its own text starts and ends inside its delimiters. Neither carries a
     * judgment about what becomes of it — see [CommentEvidenceTest] for the verdict either side of
     * the line.
     *
     * **The delimiters are read here rather than in the engine**, which is what lets the engine
     * preserve a literal's syntactic form without knowing how one is spelled: it rewrites the
     * content and nothing else.
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
        assertEquals(CommentVerdict.PROSE, comment.verdict)
        assertEquals("\"settlement\"", plan.text.substring(literal.start, literal.end))
        assertEquals(LiteralKind.STRING, literal.kind)
        assertEquals("settlement", plan.text.substring(literal.contentStart, literal.contentEnd))
    }

    /**
     * Every kind of literal Java has, reported as what it is. Only the two string-shaped kinds carry
     * text a project can put a domain word in, and telling the rest apart is what lets the engine
     * preserve them by rule rather than by omission.
     */
    fun `test every literal reports its kind and its own text`() {
        assertTheHarnessResolves()
        val plan = planFor(
            "Ledger.java",
            """
            class Ledger {
                <selection>Object[] values = {
                    "settlement", $FENCE
                        SELECT * FROM merchants
                        $FENCE, 'x', 30_000, true, null,
                };</selection>
            }
            """.trimIndent(),
        )

        val literals = plan.occurrences.filterIsInstance<LiteralOccurrence>()
        assertEquals(
            listOf(
                LiteralKind.STRING,
                LiteralKind.TEXT_BLOCK,
                LiteralKind.CHARACTER,
                LiteralKind.NUMBER,
                LiteralKind.BOOLEAN,
                LiteralKind.NULL,
            ),
            literals.map { it.kind },
        )

        // A text block's content starts after the line terminator the opening delimiter has to be
        // followed by, so what is replaced leaves a text block that is still one.
        val textBlock = literals.single { it.kind == LiteralKind.TEXT_BLOCK }
        assertEquals(
            "            SELECT * FROM merchants\n            ",
            plan.text.substring(textBlock.contentStart, textBlock.contentEnd),
        )

        // A number has no delimiters, so it is all content — which is the truth about it, and
        // reaches no rule that acts.
        val number = literals.single { it.kind == LiteralKind.NUMBER }
        assertEquals("30_000", plan.text.substring(number.contentStart, number.contentEnd))
    }

    /**
     * **A literal in red code frequently has no closing anything**, and the closing delimiter is
     * required to be there rather than assumed: its content is everything after the opening quote,
     * so the whole of it is replaced. A rule that assumed a closing quote would leave the last
     * character of a domain word on the clipboard.
     */
    fun `test an unterminated literal reports its content to the end of the token`() {
        assertTheHarnessResolves()
        val plan = planFor(
            "Ledger.java",
            """
            class Ledger {
                <selection>String label = "merchantReference
                    ;</selection>
            }
            """.trimIndent(),
        )

        val literal = plan.occurrences.filterIsInstance<LiteralOccurrence>().single()
        assertEquals(LiteralKind.STRING, literal.kind)
        assertEquals("merchantReference", plan.text.substring(literal.contentStart, literal.contentEnd))
    }

    /**
     * **A literal whose delimiters are written as unicode escapes is still a string literal**, and
     * classifying it by its opening character would call it a number — the one kind that is emitted
     * verbatim. Java translates unicode escapes before it tokenizes anything, so this is a string as
     * far as the language and the platform are concerned, and `merchantReference` would have gone to
     * the clipboard in plain text.
     *
     * The whole literal is its own content here, because the delimiters are not written the way the
     * language usually writes them: what comes out does not compile and leaks nothing, which is
     * refusal-class and therefore accepted.
     */
    fun `test a literal whose delimiters are unicode escapes is still a string`() {
        assertTheHarnessResolves()
        val plan = planWithoutAnEditor("class Probe { String label = \\u0022merchantReference\\u0022; }")
        val literal = plan.occurrences.filterIsInstance<LiteralOccurrence>().single()

        assertEquals(LiteralKind.STRING, literal.kind)
        assertEquals(
            "\\u0022merchantReference\\u0022",
            plan.text.substring(literal.contentStart, literal.contentEnd),
        )
    }

    /**
     * The same reading, for the shape that has no closing delimiter at all rather than an unusual
     * one: red code is normal, and its literals carry domain words like any others.
     */
    fun `test an unterminated literal is classified by its type rather than its text`() {
        assertTheHarnessResolves()
        val plan = planWithoutAnEditor("class Probe { String label = \"merchantReference\n; }")

        assertEquals(
            LiteralKind.STRING,
            plan.occurrences.filterIsInstance<LiteralOccurrence>().single().kind,
        )
    }

    /**
     * **A literal's references are reported as evidence, resolved and unresolved alike.**
     *
     * The platform hands over more than the four dotted segments here: the reflection contributor
     * puts a reference over the whole class name as well, and it resolves to nothing. Dropping it
     * would be the builder judging, and the engine's rule — a reference that resolved to nothing
     * covers nothing, and what decides the literal is whether the *text* around what did resolve
     * bears a word — belongs where it can be tested against a plan literal.
     */
    fun `test a literal's references are reported with what they resolve to`() {
        assertTheHarnessResolves()
        addClassInPackage("com.acme.billing", "Payment")
        val plan = planFor(
            "Loader.java",
            """
            class Loader {
                <selection>Object load() throws Exception { return Class.forName("com.acme.billing.Payment"); }</selection>
            }
            """.trimIndent(),
        )

        val references = plan.occurrences.filterIsInstance<LiteralOccurrence>().single().references
        assertEquals(
            listOf(
                "com" to SymbolOrigin.IN_CONTENT,
                "acme" to SymbolOrigin.IN_CONTENT,
                "billing" to SymbolOrigin.IN_CONTENT,
                "Payment" to SymbolOrigin.IN_CONTENT,
            ),
            references
                .filter { it.symbol.origin != SymbolOrigin.UNRESOLVED }
                .map { plan.text.substring(it.start, it.end) to it.symbol.origin },
        )
        assertEquals(SymbolRole.PACKAGE, references.first { it.symbol.origin == SymbolOrigin.IN_CONTENT }.symbol.role)
        assertTrue(
            "Nothing unresolved came back, so this test is no longer about what it says it is.",
            references.any { it.symbol.origin == SymbolOrigin.UNRESOLVED },
        )
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
