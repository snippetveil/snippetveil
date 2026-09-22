package com.snippetveil.plugin.query

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtilCore
import com.intellij.sql.psi.SqlFile
import com.intellij.sql.psi.SqlIdentifierKeywordTokenType
import com.snippetveil.core.MappedKind
import com.snippetveil.core.SnippetPlan
import com.snippetveil.core.SourceLanguage
import com.snippetveil.core.SqlKeys
import com.snippetveil.core.SymbolOrigin
import com.snippetveil.core.SymbolRole
import com.snippetveil.plugin.DATABASE_PLUGIN_ID
import com.snippetveil.plugin.FirstRunNotice
import com.snippetveil.plugin.InjectedFragment
import com.snippetveil.plugin.JavaPlanBuilder
import com.snippetveil.plugin.PlaceholderLedger
import com.snippetveil.plugin.PlaceholderSidecar
import com.snippetveil.plugin.SnippetRequest
import com.snippetveil.plugin.clipboard
import com.snippetveil.plugin.describe
import com.snippetveil.plugin.selectedRangesOf
import com.snippetveil.plugin.sql.SqlContainer
import com.snippetveil.plugin.sql.SqlDecision
import com.snippetveil.plugin.sql.SqlFallback
import com.snippetveil.plugin.symbols

/**
 * **An injected SQL fragment decomposes, and the composite decides the token** — or the fragment is
 * one redacted literal, silently, exactly as it was before.
 *
 * Every fixture injects with `// language=SQL`, the injection a user writes by hand and the one every
 * IDE with the database plugin honours, and asserts the injection happened before it measures anything
 * ([assertInjectedSql]): a literal nothing injected into comes out as one redacted literal, which is
 * also what a fragment that *fell back* comes out as, so every fallback assertion here would pass over a
 * fixture that measured nothing.
 *
 * The claims:
 *
 *  - **The trigger is position.** A fragment that parses decomposes into `table`, `col` and `schema`,
 *    though no name in it resolves to anything.
 *  - **The composite decides the token.** A column named `state` is anonymized, though its leaf is an
 *    identifier-keyword that a leaf-first reading keeps.
 *  - **The preserve predicate is closed.** Keywords, operators, punctuation, a builtin function call
 *    and a number are kept; everything else falls the fragment back — an unrecognised reference, an
 *    identifier-keyword nothing claimed, a bind parameter in either spelling.
 *  - **A fallback is byte-identical and silent.** The plan is the one built with no container, the
 *    rest of the snippet is unaffected, and no balloon, notice or count moves.
 */
internal class SqlDecompositionTest : QuerySnippetTestCase() {

    fun `test the harness has the database plugin, an injected SQL file, and both outcomes in one invocation`() {
        assertTheQueryHarnessHolds()
        assertTrue(
            "the database plugin is not loaded, so nothing would inject SQL and every fixture here would measure its absence",
            PluginManagerCore.getPlugin(PluginId.getId(DATABASE_PLUGIN_ID))?.isEnabled == true,
        )

        val decomposing = "SELECT state FROM customers"
        val fallingBack = "SELECT id FROM customers WHERE id = ?"
        val plan = planFor("Repository.java", twoQueries(decomposing, fallingBack))
        assertInjectedSql(myFixture.file, decomposing)
        assertInjectedSql(myFixture.file, fallingBack)

        assertTrue(
            "the invocation claimed no SQL name: ${describe(plan)}",
            plan.symbols().any { it.language == SourceLanguage.SQL },
        )
        assertTrue(
            "no fragment fell back in the invocation: ${describe(plan)}",
            plan.literalTexts().contains("\"$fallingBack\""),
        )
    }

    fun `test an injected SQL fragment that parses decomposes into table, col and schema placeholders`() {
        assertTheQueryHarnessHolds()
        val query = "SELECT state, id FROM billing.customers WHERE id = 42"
        val plan = planFor("Repository.java", oneQuery(query))
        assertInjectedSql(myFixture.file, query)
        val result = resultOf(plan)

        assertEquals("\"SELECT col1, col2 FROM schema3.table4 WHERE col2 = 42\"", result.text)
        assertEquals(
            "the kinds in the mapping",
            listOf("state" to MappedKind.COLUMN, "id" to MappedKind.COLUMN, "billing" to MappedKind.SCHEMA, "customers" to MappedKind.TABLE),
            result.names.map { it.original to it.kind },
        )
        for (name in plan.symbols()) {
            assertEquals("${name.text} is not an SQL name", SourceLanguage.SQL, name.language)
            assertEquals("${name.text} is not the project's", SymbolOrigin.IN_CONTENT, name.symbol.origin)
            assertEquals("${name.text} is keyed by how it is spelled", SqlKeys.named(name.symbol.role, name.text), name.symbol.key)
            assertFalse("${name.text} would be persisted", name.symbol.keyIsQualified)
        }
    }

    /**
     * **The precedence the ticket exists to state.** `state` is an identifier-keyword here — the
     * harness asserts it, and asserts the language calls the word a keyword — so any reading that asks
     * the leaf before the composite keeps it, or falls the fragment back for it, and either way `state`
     * is no longer `col1` and this fails.
     */
    fun `test a column named state is anonymized though its leaf is an identifier-keyword`() {
        assertTheQueryHarnessHolds()
        val query = "SELECT state FROM customers WHERE state = 1"
        val plan = planFor("Repository.java", oneQuery(query))
        val fragment = assertInjectedSql(myFixture.file, query)

        val leaf = leafIn(fragment.file, "state")
        assertTrue(
            "the fixture only means something if `state` is an identifier-keyword here: ${PsiUtilCore.getElementType(leaf)}",
            PsiUtilCore.getElementType(leaf) is SqlIdentifierKeywordTokenType,
        )
        assertEquals(
            "the fixture only means something if the token type carries a keyword a leaf-first reading would keep",
            "STATE",
            (PsiUtilCore.getElementType(leaf) as SqlIdentifierKeywordTokenType).keyword.toString(),
        )

        val state = plan.symbols().filter { it.text == "state" }
        assertEquals("both `state`s were not claimed", 2, state.size)
        assertTrue("`state` was not claimed as a column", state.all { it.symbol.role == SymbolRole.COLUMN })
        assertEquals("\"SELECT col1 FROM table2 WHERE col1 = 1\"", resultOf(plan).text)
    }

    fun `test keywords, operators, punctuation, a builtin function call and a number are preserved`() {
        assertTheQueryHarnessHolds()
        val query = "SELECT count(*), upper(name) FROM customers WHERE balance >= 10.5 AND active = TRUE AND closed IS NULL ORDER BY 1"
        val plan = planFor("Repository.java", oneQuery(query))
        assertInjectedSql(myFixture.file, query)

        assertEquals(
            "\"SELECT count(*), upper(col1) FROM table2 WHERE col3 >= 10.5 AND col4 = TRUE AND col5 IS NULL ORDER BY 1\"",
            resultOf(plan).text,
        )
    }

    fun `test a reference of a kind the projection does not know falls the fragment back rather than guessing`() {
        assertTheQueryHarnessHolds()
        assertFallsBackSilently("DROP INDEX idx_customers_state", SqlFallback.UNPROJECTABLE_REFERENCE, at = "idx_customers_state")
        assertFallsBackSilently("SELECT score(id) FROM customers", SqlFallback.UNPROJECTABLE_REFERENCE, at = "score")
        assertFallsBackSilently("SELECT id FROM crm.dbo.accounts", SqlFallback.UNPROJECTABLE_REFERENCE, at = "crm")
    }

    fun `test an identifier-keyword outside a claimed position falls the fragment back`() {
        assertTheQueryHarnessHolds()
        val query = "SELECT id FROM customers c"
        assertFallsBackSilently(query, SqlFallback.IDENTIFIER_KEYWORD_OUTSIDE_A_CLAIM, at = "c")

        val alias = injectedFragmentOf(query).file.let { it.findElementAt(it.textLength - 1)!! }
        assertTrue(
            "the fixture only means something if the alias is an identifier-keyword: ${PsiUtilCore.getElementType(alias)}",
            PsiUtilCore.getElementType(alias) is SqlIdentifierKeywordTokenType,
        )
    }

    fun `test a bind parameter falls the fragment back, in either spelling`() {
        assertTheQueryHarnessHolds()
        assertFallsBackSilently("SELECT id FROM customers WHERE id = ?", SqlFallback.BIND_PARAMETER, at = "?")
        assertFallsBackSilently("SELECT id FROM customers WHERE id = :ref", SqlFallback.BIND_PARAMETER, at = ":")
    }

    fun `test the rest of the fallback closure falls back too, a string and a comment among it`() {
        assertTheQueryHarnessHolds()
        assertFallsBackSilently("SELECT id FROM customers WHERE ref = 'acme'", SqlFallback.UNCLAIMED_TOKEN, at = "'acme'")
        assertFallsBackSilently("SELECT id /* merchant */ FROM customers", SqlFallback.UNCLAIMED_TOKEN, at = "/* merchant */")
        assertFallsBackSilently("SELECT id AS merchant FROM customers", SqlFallback.UNCLAIMED_TOKEN, at = "merchant")
        assertFallsBackSilently("SELECT id FROM customers WHERE", SqlFallback.DOES_NOT_PARSE, at = null)
    }

    /**
     * **Refusal is fragment-scoped.** Two queries in one snippet: the one that falls back is exactly the
     * literal occurrence the walk made of it with no container at all, and every occurrence outside the
     * one that decomposed — the Java around both included — is exactly what that walk made of it too.
     */
    fun `test a fallen-back fragment is byte-identical and the rest of the snippet is unaffected`() {
        assertTheQueryHarnessHolds()
        val decomposing = "SELECT state FROM customers"
        val fallingBack = "SELECT id FROM customers WHERE id = ?"
        myFixture.configureByText("Repository.java", twoQueries(decomposing, fallingBack))
        assertInjectedSql(myFixture.file, decomposing)
        assertInjectedSql(myFixture.file, fallingBack)
        val request = SnippetRequest(project, myFixture.file, selectedRangesOf(myFixture.editor))

        val with = JavaPlanBuilder.build(request)
        val without = JavaPlanBuilder.build(request, container = null)
        val decomposed = with.text.indexOf("\"$decomposing\"").let { it until it + decomposing.length + 2 }

        assertEquals(describe(outside(without, decomposed)), describe(outside(with, decomposed)))
        assertTrue("the other query did not decompose", with.symbols().any { it.language == SourceLanguage.SQL })
    }

    /**
     * Asserts [query], injected, falls back for [reason] at the token written [at] — `null` for a
     * fragment that was never read token by token — and that the fallback is **byte-identical and
     * silent**: the plan is the one built with no container, and copying it puts the same text on the
     * clipboard and raises the same balloons, no more, as copying the same literal with nothing
     * injected into it.
     */
    private fun assertFallsBackSilently(query: String, reason: SqlFallback, at: String?) {
        val decision = SqlContainer.decide(injectedFragmentOf(query))
        assertTrue("$query did not fall back", decision is SqlDecision.FellBack)
        decision as SqlDecision.FellBack
        assertEquals("$query fell back at ${decision.at.text} (${decision.elementType})", reason, decision.reason)
        if (at != null) assertEquals("$query fell back at another token", at, decision.at.text)

        val request = SnippetRequest(project, myFixture.file, selectedRangesOf(myFixture.editor))
        assertEquals("$query was not byte-identical", describe(JavaPlanBuilder.build(request, container = null)), describe(JavaPlanBuilder.build(request)))

        assertEquals("$query said something the uninjected literal does not", copied(query, injected = false), copied(query, injected = true))
    }

    /**
     * What copying [query]'s literal puts on the clipboard, and every balloon it raises — from a fresh
     * ledger each time, so that two copies are compared rather than a copy and its successor.
     */
    private fun copied(query: String, injected: Boolean): Pair<String, List<String>> {
        PlaceholderLedger.getInstance().loadState(PlaceholderLedger.State())
        PlaceholderSidecar.getInstance(project).loadState(PlaceholderSidecar.State())
        FirstRunNotice.getInstance().loadState(FirstRunNotice.State())
        myFixture.configureByText("Repository.java", oneQuery(query, injected))
        if (injected) assertInjectedSql(myFixture.file, query)
        invokeCopyAnonymized()
        return clipboard() to notifications.map { "${it.type} ${it.title} ${it.content}" }
    }

    /** [query] injected as the one selected literal, and the fragment the platform injected. */
    private fun injectedFragmentOf(query: String): InjectedFragment {
        myFixture.configureByText("Repository.java", oneQuery(query))
        return assertInjectedSql(myFixture.file, query)
    }

    /**
     * The one fragment injected into the literal holding [query] in [file], asserted to be **SQL** — so
     * that a fixture's claim is about a fragment that exists, in the language it says.
     */
    private fun assertInjectedSql(file: PsiFile, query: String): InjectedFragment {
        val host = PsiTreeUtil.findChildrenOfType(file, PsiLanguageInjectionHost::class.java).firstOrNull { it.text == "\"$query\"" }
        assertNotNull("no literal holds $query", host)
        val fragments = InjectedFragment.injectedInto(host!!)
        assertEquals("the IDE injected ${fragments.size} fragments into $query", 1, fragments.size)
        assertTrue("the IDE injected ${fragments.single().file.language.id} into $query, not SQL", fragments.single().file is SqlFile)
        return fragments.single()
    }

    /** The leaf of [file] where [text] first occurs. */
    private fun leafIn(file: PsiFile, text: String): PsiElement {
        val leaf = file.findElementAt(file.text.indexOf(text))
        assertNotNull("${file.text} holds no $text", leaf)
        return leaf!!
    }

    /** [plan] with every occurrence starting inside [range] left out. */
    private fun outside(plan: SnippetPlan, range: IntRange): SnippetPlan =
        SnippetPlan(plan.text, plan.occurrences.filter { it.start !in range }, plan.rootPackage, plan.selectionExpanded)
}

/** One `// language=SQL` literal holding [query], and only that literal selected. */
private fun oneQuery(query: String, injected: Boolean = true): String = """
    class Repository {
        void run() {
            ${if (injected) "// language=SQL" else "// a query"}
            String query = <selection>"$query"</selection>;
        }
    }
""".trimIndent()

/** Two `// language=SQL` literals, and the Java around both selected. */
private fun twoQueries(first: String, second: String): String = """
    class Repository {
        <selection>void run() {
            // language=SQL
            String first = "$first";
            // language=SQL
            String second = "$second";
        }</selection>
    }
""".trimIndent()
