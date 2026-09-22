package com.snippetveil.plugin.query

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.util.PsiTreeUtil
import com.snippetveil.core.AnonymizationResult
import com.snippetveil.core.AnonymizationSettings
import com.snippetveil.core.CommentOccurrence
import com.snippetveil.core.InternalLibraries
import com.snippetveil.core.LiteralKind
import com.snippetveil.core.LiteralOccurrence
import com.snippetveil.core.MappedKind
import com.snippetveil.core.SnippetPlan
import com.snippetveil.core.SymbolOccurrence
import com.snippetveil.core.SymbolOrigin
import com.snippetveil.core.SymbolRole
import com.snippetveil.plugin.InjectedFragment
import com.snippetveil.plugin.JavaPlanBuilder
import com.snippetveil.plugin.PlaceholderLedger
import com.snippetveil.plugin.SnippetRequest
import com.snippetveil.plugin.describe
import com.snippetveil.plugin.selectedRangesOf
import com.snippetveil.plugin.symbols

/**
 * **A JPQL query in an annotation decomposes onto the project's own placeholders** — when every name
 * in it binds, and is one redacted literal when any does not.
 *
 * The claims, each asserted against a query the IDE really injected ([assertInjected]) and resolved
 * with the contributors this cell pins:
 *
 *  - **The trigger is binding.** Every identifier position resolves, or the whole literal is one
 *    redacted literal — never partly decomposed.
 *  - **The names share the ledger.** An entity name is the entity class and a persistent property is
 *    its field, reached through one navigation hop; each takes the placeholder the Java symbol has.
 *  - **One hop, not a chain.** A hop landing anywhere but a project declaration is a position that has
 *    not resolved.
 *  - **What the query itself declares is fragment-local**: a bind parameter is a `local`, never a
 *    `param`, and an alias is keyed by its fragment and never written down.
 *  - **A string is its own redacted literal and a comment is a comment**, stripped and counted.
 *  - **No query name is ever an `Unknown`**, in any configuration.
 */
internal class QueryDecompositionTest : QuerySnippetTestCase() {

    fun `test a JPQL query whose positions all resolve decomposes onto the entity's placeholders`() {
        assertTheQueryHarnessHolds()
        val plan = planFor("Customer.java", entity(RESOLVING_QUERY))
        assertInjected(myFixture.file, "\"$RESOLVING_QUERY\"", JPQL)

        assertEquals(
            "@Entity\n" +
                "@NamedQuery(name = \"str1\", query = \"SELECT local2 FROM Type3 local2 WHERE local2.field4 = :local5\")\n" +
                "public class Type3 {\n" +
                "    @Id Long field6;\n" +
                "    String field4;\n" +
                "}",
            resultOf(plan).text,
        )
    }

    fun `test a JPQL query with a position that does not resolve is one redacted literal`() {
        assertTheQueryHarnessHolds()
        val query = "SELECT c FROM Customer c WHERE c.nothingCalledThis = :ref"
        val plan = planFor("Customer.java", entity(query))
        assertInjected(myFixture.file, "\"$query\"", JPQL)

        assertEquals("the query was not one literal", listOf("\"byRef\"", "\"$query\""), plan.literalTexts())
        assertTrue("no name of the query should reach the plan", plan.symbols().none { it.text == "c" || it.text == "ref" })
        assertTrue("the query was not redacted whole: ${resultOf(plan).text}", resultOf(plan).text.contains("query = \"str2\""))
    }

    fun `test an entity name in a query and the entity class share one placeholder`() {
        assertTheQueryHarnessHolds()
        val plan = planFor("Customer.java", entity(RESOLVING_QUERY))
        val result = resultOf(plan)

        val named = plan.symbols().filter { it.text == "Customer" }
        assertEquals("the class and the query should both name Customer", 2, named.size)
        assertEquals("the two are not one symbol", 1, named.map { it.symbol.key }.distinct().size)
        assertEquals("they did not render as one placeholder", 1, named.map { result.placeholderOf(it) }.distinct().size)
    }

    /**
     * **The hop is real, and it is one.** The platform does not answer a persistent property with the
     * field: it answers with a target of its own, which navigates to the field — so the fixture asserts
     * that shape before asserting the two share a placeholder, or the test would pass on a contributor
     * that resolved straight to the field and never exercised the hop at all.
     */
    fun `test a persistent property and its field share one placeholder, through one navigation hop`() {
        assertTheQueryHarnessHolds()
        val plan = planFor("Customer.java", entity(RESOLVING_QUERY))

        val target = referenceTargetIn(RESOLVING_QUERY, "merchantRef")
        assertFalse("the property resolved straight to the field, so no hop was exercised", target is PsiField)
        assertTrue("the property's target does not navigate to the field", target.navigationElement is PsiField)

        val named = plan.symbols().filter { it.text == "merchantRef" }
        assertEquals("the field and the query should both name merchantRef", 2, named.size)
        assertEquals("the property and its field are not one symbol", 1, named.map { it.symbol.key }.distinct().size)
        assertEquals(SymbolRole.FIELD, named.first().symbol.role)
    }

    /**
     * **A hop landing on a library declaration is a position that has not resolved.** `id` is declared
     * by Spring Data's `AbstractPersistable`, a class in a jar: the property's target navigates there,
     * and the fragment falls back.
     */
    fun `test a navigation hop landing on a non-project declaration falls the fragment back`() {
        assertTheQueryHarnessHolds()
        val query = "SELECT c FROM Customer c WHERE c.id = :id"
        val plan = planFor(
            "Customer.java",
            """
            package com.acme.billing;

            import jakarta.persistence.*;
            import org.springframework.data.jpa.domain.AbstractPersistable;

            <selection>@Entity
            @NamedQuery(name = "byId", query = "$query")
            public class Customer extends AbstractPersistable<Long> {
                String merchantRef;
            }</selection>
            """.trimIndent(),
        )
        assertInjected(myFixture.file, "\"$query\"", JPQL)

        val landed = referenceTargetIn(query, "id").navigationElement
        assertTrue("the hop did not land on a field, so this is not the case under test", landed is PsiField)
        assertEquals("the hop did not land in a library", "AbstractPersistable", (landed as PsiField).containingClass?.name)

        assertEquals("the query was not one literal", listOf("\"byId\"", "\"$query\""), plan.literalTexts())
    }

    /**
     * **An entity named by `@Entity(name = …)` is reached through the name's literal**, which is not a
     * declaration at all — so that hop lands on nothing the rule may take, and the fragment falls back.
     */
    fun `test a navigation hop landing on something that is not a declaration falls the fragment back`() {
        assertTheQueryHarnessHolds()
        val query = "SELECT c FROM Client c WHERE c.merchantRef = :ref"
        val plan = planFor(
            "Customer.java",
            """
            package com.acme.billing;

            import jakarta.persistence.*;

            <selection>@Entity(name = "Client")
            @NamedQuery(name = "byRef", query = "$query")
            public class Customer {
                @Id Long id;
                String merchantRef;
            }</selection>
            """.trimIndent(),
        )
        assertInjected(myFixture.file, "\"$query\"", JPQL)
        assertNotNull("the entity name did not resolve at all, so this is not the case under test", referenceTargetIn(query, "Client"))

        assertEquals("the query was not one literal", listOf("\"Client\"", "\"byRef\"", "\"$query\""), plan.literalTexts())
    }

    /**
     * **A bind parameter is a `local`**, asserted on the kind the mapping table reports rather than only
     * on the text — `local` is what the placeholder says, and the row is what says it is not a `param`.
     * Both mentions of it are one name.
     */
    fun `test a bind parameter renders with the local kind`() {
        assertTheQueryHarnessHolds()
        val query = "SELECT c FROM Customer c WHERE c.merchantRef = :ref OR c.merchantRef = :ref"
        val plan = planFor("Customer.java", entity(query))
        assertInjected(myFixture.file, "\"$query\"", JPQL)
        val result = resultOf(plan)

        val parameter = plan.symbols().filter { it.text == "ref" }
        assertEquals("both mentions of :ref should be names", 2, parameter.size)
        assertEquals("the two mentions of :ref are not one name", 1, parameter.map { it.symbol.key }.distinct().size)
        assertEquals(SymbolRole.LOCAL, parameter.first().symbol.role)

        val row = result.names.single { it.original == "ref" }
        assertEquals("the bind parameter is not a local in the mapping", MappedKind.LOCAL, row.kind)
        assertTrue("the bind parameter's placeholder is not a local one: ${row.placeholder}", row.placeholder!!.startsWith("local"))
    }

    /**
     * **An alias belongs to its fragment.** The same `c` in two queries is two symbols with two keys,
     * each owned by its own fragment — and neither key is ever written down, asserted on the ledger a
     * real invocation leaves behind.
     */
    fun `test a fragment-local alias allocates against a fragment-owned key and is not persisted`() {
        assertTheQueryHarnessHolds()
        val other = "SELECT c FROM Customer c WHERE c.id = :id"
        val plan = planFor(
            "Customer.java",
            """
            package com.acme.billing;

            import jakarta.persistence.*;

            <selection>@Entity
            @NamedQuery(name = "byRef", query = "$RESOLVING_QUERY")
            @NamedQuery(name = "byId", query = "$other")
            public class Customer {
                @Id Long id;
                String merchantRef;
            }</selection>
            """.trimIndent(),
        )

        val aliases = plan.symbols().filter { it.text == "c" }
        assertEquals("both queries' aliases should be names", 6, aliases.size)
        val keys = aliases.map { it.symbol.key }.distinct()
        assertEquals("the two fragments' aliases are not two symbols", 2, keys.size)
        assertTrue("an alias's key is not fragment-owned: $keys", keys.all { it.startsWith("fragment-local:") })
        assertTrue("an alias's key is marked for the ledger", aliases.none { it.symbol.keyIsQualified })
        val result = resultOf(plan)
        assertEquals("the two aliases rendered as one placeholder", 2, aliases.map { result.placeholderOf(it) }.distinct().size)

        invokeCopyAnonymized()
        val persisted = PlaceholderLedger.getInstance().snapshotOf(project).placeholders.keys
        assertTrue("the entity class was not written down, so the ledger proves nothing", persisted.any { it.contains("Customer") })
        assertTrue("an alias key was written down: $persisted", persisted.none { it in keys })
        assertTrue("a fragment-local key was written down: $persisted", persisted.none { it.startsWith("fragment-local:") })
    }

    /**
     * **A string inside a query is its own redacted literal, and a comment inside one is a comment** —
     * stripped under the ordinary default, and counted where every stripped comment is.
     */
    fun `test a string in a query is its own redacted literal, and a comment in one is stripped and counted`() {
        assertTheQueryHarnessHolds()
        val query = "SELECT c FROM Customer c WHERE c.merchantRef = 'acme-holdings' /* merchants of record */"
        val plan = planFor("Customer.java", entity(query))
        assertInjected(myFixture.file, "\"$query\"", JPQL)

        val strings = plan.occurrences.filterIsInstance<LiteralOccurrence>().filter { plan.text.substring(it.start, it.end) == "'acme-holdings'" }
        assertEquals("the query's string is not a literal of its own", 1, strings.size)
        assertEquals(LiteralKind.STRING, strings.single().kind)
        assertEquals("acme-holdings", plan.text.substring(strings.single().contentStart, strings.single().contentEnd))
        assertEquals("the query's comment is not a comment", 1, plan.occurrences.filterIsInstance<CommentOccurrence>().size)

        val result = resultOf(plan)
        assertFalse("the string's words survived: ${result.text}", result.text.contains("acme"))
        assertFalse("the comment's words survived: ${result.text}", result.text.contains("merchants"))
        assertEquals("the stripped comment was not counted", 1, result.comments.stripped)
        assertTrue("the string did not become a redacted literal of its own: ${result.text}", Regex("""= 'str\d+'""").containsMatchIn(result.text))
    }

    /**
     * **No query name is ever an `Unknown`, in any configuration** — the queries that bind and the ones
     * that do not, under every setting that changes what reaches the output. A position either resolves
     * or its fragment falls back; there is no third outcome.
     */
    fun `test no query identifier becomes an unknown-namespace placeholder in any configuration`() {
        assertTheQueryHarnessHolds()
        val queries = listOf(
            RESOLVING_QUERY,
            "SELECT c FROM Customer c WHERE c.nothingCalledThis = :ref",
            "SELECT c FROM NothingCalledThis c",
            "SELECT c FROM Customer c WHERE c.merchantRef = 'acme' /* note */",
        )
        val settings = listOf(
            AnonymizationSettings.DEFAULTS,
            AnonymizationSettings(keepComments = true),
            AnonymizationSettings(internalLibraries = InternalLibraries(autoDetectRootPackage = false)),
            AnonymizationSettings(internalLibraries = InternalLibraries(internalPrefixes = setOf("com.acme", "jakarta"))),
        )

        for (query in queries) {
            val plan = planFor("Customer.java", entity(query))
            assertTrue(
                "a query name reached the plan unresolved: $query",
                plan.symbols().none { it.symbol.origin == SymbolOrigin.UNRESOLVED },
            )
            for (setting in settings) {
                val result = resultOf(plan, setting)
                assertTrue("an Unknown reached the output for $query: ${result.text}", !result.text.contains("Unknown"))
                assertEquals("an unknown was counted for $query", 0, result.counts.unknown)
            }
        }
    }

    /** A fragment that falls back produces exactly the plan the walk produced with no container at all. */
    fun `test a query that falls back produces the plan built without any container`() {
        assertTheQueryHarnessHolds()
        val query = "SELECT c FROM Customer c WHERE c.nothingCalledThis = :ref"
        myFixture.configureByText("Customer.java", entity(query))
        val request = SnippetRequest(project, myFixture.file, selectedRangesOf(myFixture.editor))

        assertEquals(describe(JavaPlanBuilder.build(request, container = null)), describe(JavaPlanBuilder.build(request)))
    }

    /** What the one reference sitting exactly over [name] in the injected [query] resolves to. */
    private fun referenceTargetIn(query: String, name: String): PsiElement {
        val host = PsiTreeUtil.findChildrenOfType(myFixture.file, PsiLanguageInjectionHost::class.java).first { it.text == "\"$query\"" }
        val fragment = InjectedFragment.injectedInto(host).single()
        val at = fragment.file.text.indexOf(name, fragment.file.text.indexOf(" WHERE ").coerceAtLeast(0)).takeIf { it >= 0 }
            ?: fragment.file.text.indexOf(name)
        val reference = fragment.file.findReferenceAt(at)
        assertNotNull("nothing in $query refers at $name", reference)
        val target = reference!!.resolve()
        assertNotNull("$name in $query resolves to nothing", target)
        return target!!
    }
}

/** The texts of the plan's literal occurrences, in document order. */
internal fun SnippetPlan.literalTexts(): List<String> =
    occurrences.filterIsInstance<LiteralOccurrence>().map { text.substring(it.start, it.end) }

/** The placeholder [occurrence]'s symbol became, as the mapping table reports it. */
internal fun AnonymizationResult.placeholderOf(occurrence: SymbolOccurrence): String? =
    names.first { it.key == occurrence.symbol.key }.placeholder

/** A project entity whose one named query is [query], the whole class selected. */
internal fun entity(query: String): String = """
    package com.acme.billing;

    import jakarta.persistence.*;

    <selection>@Entity
    @NamedQuery(name = "byRef", query = "$query")
    public class Customer {
        @Id Long id;
        String merchantRef;
    }</selection>
""".trimIndent()

internal const val RESOLVING_QUERY = "SELECT c FROM Customer c WHERE c.merchantRef = :ref"

internal const val JPQL = "JPAQL"

