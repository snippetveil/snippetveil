package com.snippetveil.plugin

import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.impl.source.resolve.reference.PsiReferenceRegistrarImpl
import com.intellij.psi.impl.source.resolve.reference.ReferenceProvidersRegistry
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import com.snippetveil.core.LiteralOccurrence
import com.snippetveil.core.SnippetPlan
import com.snippetveil.core.SymbolOrigin

/**
 * **The same snippet anonymizes differently in Community and Ultimate, and both are correct.**
 *
 * Literal references are contributed per-framework by `PsiReferenceContributor`. Core Java
 * contributes class-name references — `Class.forName` is one — while the JPA ones (`mappedBy`,
 * `columnList`, `@Column(name = …)`) and the Spring ones (`@RequestMapping` paths, `@Value`) ship
 * only in Ultimate. A literal that renames column by column there is replaced whole here.
 *
 * **Accepted, because it runs in the safe direction.** Community anonymizes *more*, never less, so
 * no install leaks more than another, and the degraded case is a `"str1"` a reader can see rather
 * than a wrong answer they cannot. Rejected: normalizing up, by resolving the common JPA and Spring
 * attributes ourselves keyed by annotation FQN plus attribute name — legal, and unbounded, because
 * it puts this project in the business of tracking framework annotation schemas.
 *
 * **The harness trap this file exists for.** A test that asserts `"str1"` cannot tell the coverage
 * rule firing apart from the contributor being absent, and the fixture runs against whichever IDE
 * the build was pointed at. So every test here **pins the contributor set it ran under** — the
 * Community-shaped ones assert that the literal carried no resolved reference at all, and the
 * Ultimate-shaped ones register the references the framework contributor would have produced. It is
 * the same failure shape as the mock-JDK trap that [JavaSnippetTestCase] exists for, and it is
 * closed the same way.
 */
class LiteralEditionTest : JavaSnippetTestCase() {

    /**
     * The pin itself, stated once as an assertion about the fixture rather than as a comment: the
     * Java class-name contributor is here, and no JPA or Spring one is.
     */
    fun `test the fixture pins which reference contributors are present`() {
        assertTheHarnessResolves()
        addClassInPackage("com.acme.billing", "Payment")

        val classNames = planFor(
            "Loader.java",
            """
            class Loader {
                <selection>Object load() throws Exception { return Class.forName("com.acme.billing.Payment"); }</selection>
            }
            """.trimIndent(),
        )
        assertEquals(
            "Core Java's class-name contributor is not in this fixture; every literal test here would be measuring its absence.",
            listOf("com", "acme", "billing", "Payment"),
            classNames.resolvedCover(),
        )

        val columnList = planFor("Ledger.java", COLUMN_LIST_SNIPPET)
        assertEquals(
            "A JPA-shaped contributor is present in this fixture, so the Community-shaped tests below are not testing what they say.",
            emptyList<String>(),
            columnList.resolvedCover(),
        )
    }

    /**
     * **Community: a column list carries no references, so it is replaced whole.** This is the
     * degraded case, and it degrades into a refusal rather than into a wrong answer.
     */
    fun `test a column list is replaced whole under the Community contributor set`() {
        assertTheHarnessResolves()
        val plan = planFor("Ledger.java", COLUMN_LIST_SNIPPET)
        assertEquals(emptyList<String>(), plan.resolvedCover())

        invokeCopyAnonymized()

        assertEquals(
            "@Anno1(attr2 = \"str3\")\n" +
                "    private String field4;\n" +
                "    private String field5;",
            clipboard(),
        )
    }

    /**
     * **Ultimate: the same snippet renames column by column, in lockstep with the fields.** The gap
     * is `", "`, which bears no word, so coverage holds — and the placeholders in the literal are
     * the same ones the declarations get, because they are the same symbols.
     *
     * That agreement is the whole point of driving the rewrite from references. A pattern-rewritten
     * foreign-key literal going out of sync with its symbol-renamed field is what manufactured a
     * *confident, false* finding in the experiment: the reviewer reported an inconsistency that does
     * not exist upstream.
     */
    fun `test a column list renames field by field under an Ultimate-shaped contributor set`() {
        assertTheHarnessResolves()
        contributeReferences { literal -> columnsOf(literal) }

        val plan = planFor("Ledger.java", COLUMN_LIST_SNIPPET)
        assertEquals(listOf("merchantId", "createdAt"), plan.resolvedCover())

        invokeCopyAnonymized()

        assertEquals(
            "@Anno1(attr2 = \"field3, field4\")\n" +
                "    private String field3;\n" +
                "    private String field4;",
            clipboard(),
        )
    }

    /**
     * **A partly covered literal is replaced whole, in either edition.** In Ultimate a
     * `@RequestMapping` path contributes a reference for its `{id}` variable and nothing for the
     * path around it — and *merchants* is a domain word sitting in that gap.
     *
     * This is the row that ruled out **mixed-always**: rewriting the covered ranges and passing the
     * gaps through fixes the easy cases and leaks this one.
     */
    fun `test a path template is replaced whole even where its variable is covered`() {
        assertTheHarnessResolves()
        contributeReferences { literal -> pathVariablesOf(literal) }

        val plan = planFor(
            "Ledger.java",
            """
            class Ledger {
                <selection>@SuppressWarnings("/api/merchants/{id}")
                String find(String id) { return id; }</selection>
            }
            """.trimIndent(),
        )
        assertEquals(
            "The path-variable contributor produced nothing, so the whole-literal result below proves nothing.",
            listOf("id"),
            plan.resolvedCover(),
        )

        invokeCopyAnonymized()

        assertTrue(
            "The path template was not replaced whole: ${clipboard()}",
            clipboard().startsWith("@SuppressWarnings(\"str1\")"),
        )
    }

    /**
     * **The case that rules out a preserve list keyed by resolved callee.** A JPQL query is an
     * argument to a library method, and a callee-keyed list waves it straight through with a project
     * type and its fields in plain text. Even in Ultimate — where the query is an injected language
     * and the type name really is covered — `SELECT p FROM ` is a word-bearing gap, so the literal
     * goes whole.
     */
    fun `test a query naming a project type is replaced whole even where the type is covered`() {
        assertTheHarnessResolves()
        addClassInPackage("com.acme.billing", "Payment")
        contributeReferences { literal -> typeNamesOf(literal, "com.acme.billing.Payment") }

        val plan = planFor(
            "Ledger.java",
            """
            class Ledger {
                <selection>String query() {
                    return "SELECT p FROM com.acme.billing.Payment p";
                }</selection>
            }
            """.trimIndent(),
        )
        assertEquals(
            "The injected-query contributor produced nothing, so the whole-literal result below proves nothing.",
            listOf("com.acme.billing.Payment"),
            plan.resolvedCover(),
        )

        invokeCopyAnonymized()

        assertEquals(
            "String method1() {\n" +
                "        return \"str2\";\n" +
                "    }",
            clipboard(),
        )
    }

    /**
     * Registers a stand-in for the framework contributors that ship only in Ultimate, for this test
     * alone — the platform's own registrar, with the fixture's disposable, so nothing leaks into the
     * next test.
     *
     * **[claim] reads the literal's text, and that is the point rather than a shortcut.** A
     * reference contributor is exactly the thing that is allowed to: it looks at `"merchantId,
     * createdAt"`, decides that two columns are named there, and resolves each against PSI. What is
     * under test is that this plugin's rewrite is driven only by the references it is handed —
     * whoever produced them, and however they were found.
     */
    private fun contributeReferences(claim: (PsiLiteralExpression) -> List<Pair<TextRange, PsiElement?>>) {
        val provider = object : PsiReferenceProvider() {
            override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
                val literal = element as? PsiLiteralExpression ?: return PsiReference.EMPTY_ARRAY
                return claim(literal)
                    .map { (range, target) ->
                        object : PsiReferenceBase<PsiLiteralExpression>(literal, range, true) {
                            override fun resolve(): PsiElement? = target
                        }
                    }
                    .toTypedArray()
            }
        }

        val registrar = ReferenceProvidersRegistry.getInstance().getRegistrar(JavaLanguage.INSTANCE)
        (registrar as PsiReferenceRegistrarImpl).registerReferenceProvider(
            PlatformPatterns.psiElement(PsiLiteralExpression::class.java),
            provider,
            PsiReferenceRegistrar.DEFAULT_PRIORITY,
            testRootDisposable,
        )
    }

    /** A JPA `columnList`: one reference per comma-separated column, resolved to the class's field. */
    private fun columnsOf(literal: PsiLiteralExpression): List<Pair<TextRange, PsiElement?>> {
        val owner = PsiTreeUtil.getParentOfType(literal, PsiClass::class.java) ?: return emptyList()
        return segmentsOf(literal, ",").map { (range, name) -> range to owner.findFieldByName(name, false) }
    }

    /** A Spring path template: a reference for each `{variable}`, resolved to the method's parameter. */
    private fun pathVariablesOf(literal: PsiLiteralExpression): List<Pair<TextRange, PsiElement?>> {
        val owner = PsiTreeUtil.getParentOfType(literal, PsiMethod::class.java) ?: return emptyList()
        return Regex("""\{(\w+)}""").findAll(literal.text).map { match ->
            val name = match.groupValues[1]
            val range = TextRange(match.range.first + 1, match.range.first + 1 + name.length)
            range to owner.parameterList.parameters.firstOrNull { it.name == name }
        }.toList()
    }

    /** An injected query language: a reference over a fully-qualified type name it names. */
    private fun typeNamesOf(literal: PsiLiteralExpression, qualifiedName: String): List<Pair<TextRange, PsiElement?>> {
        val at = literal.text.indexOf(qualifiedName).takeIf { it >= 0 } ?: return emptyList()
        val type = javaFacade().findClass(qualifiedName, allScope()) ?: return emptyList()
        return listOf(TextRange(at, at + qualifiedName.length) to type)
    }

    private fun segmentsOf(literal: PsiLiteralExpression, separator: String): List<Pair<TextRange, String>> {
        val text = literal.text
        val segments = mutableListOf<Pair<TextRange, String>>()
        var at = 1
        while (at < text.length - 1) {
            val end = text.indexOf(separator, at).takeIf { it >= 0 } ?: (text.length - 1)
            val name = text.substring(at, end).trim()
            val start = at + text.substring(at, end).indexOf(name)
            segments += TextRange(start, start + name.length) to name
            at = end + separator.length
        }
        return segments
    }

    private fun javaFacade() = com.intellij.psi.JavaPsiFacade.getInstance(project)

    private fun allScope() = com.intellij.psi.search.GlobalSearchScope.allScope(project)

    /** The parts of the plan's one literal that a reference resolved over — the contributor set, seen. */
    private fun SnippetPlan.resolvedCover(): List<String> =
        occurrences.filterIsInstance<LiteralOccurrence>()
            .single { it.contentEnd > it.contentStart }
            .references
            .filter { it.symbol.origin != SymbolOrigin.UNRESOLVED }
            .map { text.substring(it.start, it.end) }
}

/**
 * A JPA-shaped column list on a project annotation. The annotation is the project's own because a
 * real `@Index` is not on this fixture's classpath, and what it is annotated with changes nothing:
 * the rule under test is about the literal.
 */
private val COLUMN_LIST_SNIPPET = """
    @interface Index { String columnList(); }

    class Ledger {
        <selection>@Index(columnList = "merchantId, createdAt")
        private String merchantId;
        private String createdAt;</selection>
    }
""".trimIndent()
