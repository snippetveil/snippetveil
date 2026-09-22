package com.snippetveil.sweep

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.snippetveil.core.SymbolRole
import com.snippetveil.plugin.IDENTIFIERS_AS_TABLES
import com.snippetveil.plugin.InjectedFragment
import com.snippetveil.plugin.JavaSnippetTestCase
import com.snippetveil.plugin.unionProjection

/**
 * **The query sweep's loop, watched finding each of the three things a run stands on** — a
 * decomposition, a fallback and a host-range violation — and watched failing on the one shape an
 * instrument cannot be allowed to pass: a corpus it read nothing in.
 *
 * It runs in `check`, in every cell, because the harness is not the containers: what is under test
 * here is what the pass counts and what the counts then fail on. The fragments are injected with
 * IntelliLang's `// language=JAVA` comment, as the range-identity fixtures are, and read by a stand-in
 * reader — so a cell with no database plugin and no query contributors exercises all of it. The
 * containers' own readings are asserted where they belong, on the query fixtures.
 *
 * **The violation is provoked by the thing that provokes it in real code**: the single-range host
 * projection, which answers a name across two shreds with the union of them.
 */
class QueryPassTest : JavaSnippetTestCase() {

    /**
     * **A run that read no query fragment is a failed run rather than a clean one** — proved by
     * pointing the harness at a corpus with none, which is the only way to prove it. Everything the
     * run then asserts holds vacuously over it, which is the fail-green this assertion exists for.
     */
    fun `test a corpus with no injected fragment fails the run rather than passing it clean`() {
        val file = myFixture.configureByText("NoQueries.java", NO_QUERIES)

        val swept = pass().over("corpus", listOf(file.virtualFile), pathOf = { it.name })

        assertEquals("a fragment was found in a file that has none", 0, swept.fragments)
        assertEquals("a host with an injected file was found where there is none", 0, swept.hostsWithInjection)
        assertNoHostRangeViolation(listOf(swept))
        val refused = runCatching { assertTheRunHolds(listOf(swept)) }.exceptionOrNull()
        assertTrue("a run that read nothing was not failed: $refused", refused is IllegalStateException)
        assertTrue(
            "the refusal does not say a zero is a failed run: ${refused?.message}",
            "failed run" in refused?.message.orEmpty(),
        )
    }

    /** A fragment that projects is counted as decomposed, with the names it claimed and how they were spelled. */
    fun `test a decomposed fragment is counted with its claimed names`() {
        val swept = pass().over("corpus", listOf(queries().virtualFile), pathOf = { it.name })

        assertEquals("the injected fragments were not all found", 4, swept.fragments)
        assertEquals("a fragment the reader reads was left out of the denominator", 4, swept.queryFragments)
        assertEquals("the clean and the escaped fragment did not decompose", 2, swept.decomposed)
        assertEquals("the names claimed were not counted by role", mapOf(SymbolRole.TABLE to 2), swept.claimed)
        assertTrue("no host was seen to yield an injected file", swept.hostsWithInjection > 0)
        // The six literals, and not the four `// language=` comments beside them: a Java comment is an
        // injection host too, and the seam this measures reaches literals only.
        assertEquals("the hosts counted are not the file's literals", 6, swept.hosts)
    }

    /**
     * **Two spellings of one table, measured rather than judged.** The product keys a SQL name by its
     * spelling exactly as written, so `Customers` and `customers` are two placeholders; whether real
     * code ever writes one table both ways is the fourth thing this instrument reports.
     */
    fun `test two spellings of one table are grouped, and one spelling stays one group`() {
        val swept = pass().over("corpus", listOf(spellings().virtualFile), pathOf = { it.name })

        assertEquals(
            "the two spellings of one table were not grouped together",
            mapOf("customers" to setOf("Customers", "customers"), "orders" to setOf("orders")),
            swept.tableSpellings,
        )
        assertEquals("a table written two ways was not reported", 1, QueryRates(listOf(swept)).tablesWrittenTwoWays.size)
    }

    /** A fragment the reader will not read is counted as a fallback, under the reason it gave. */
    fun `test a fragment the reader falls back on is counted under its reason`() {
        val reader = FragmentReader { FragmentVerdict.FellBack(QueryFallback.BIND_PARAMETER, "SQL_QUESTION_MARK") }

        val swept = pass(reader).over("corpus", listOf(queries().virtualFile), pathOf = { it.name })

        assertEquals("the fallbacks were not counted", 4, swept.fallbacks.size)
        assertEquals("the reason was not kept", QueryFallback.BIND_PARAMETER, swept.fallbacks.first().reason)
        assertEquals("what it fell back on was not kept", "SQL_QUESTION_MARK", swept.fallbacks.first().at)
        assertEquals("the bind-parameter rate is not over the fragments read", 4, QueryRates(listOf(swept)).bindParameters)
    }

    /**
     * **A fragment in a language no container reads is not a fallback — it is not a query**, and it
     * stays out of the denominator every rate is over. It is still counted as an injected fragment,
     * because *what the IDE injected* is the coverage the denominator is read against.
     */
    fun `test a fragment no reader reads is outside the denominator rather than a fallback`() {
        val swept = pass(FragmentReader { FragmentVerdict.NotAQuery })
            .over("corpus", listOf(queries().virtualFile), pathOf = { it.name })

        assertEquals("the injected fragments were not counted", 4, swept.fragments)
        assertEquals("a fragment nothing reads was counted as a query", 0, swept.queryFragments)
        assertTrue("a fragment nothing reads was counted as a fallback", swept.fallbacks.isEmpty())
    }

    /**
     * **A part of a reading that lies nowhere in the host falls the fragment back**, and the pass
     * counts that as its own reason rather than as a decomposition of the half that did project. The
     * straddling and the substituted fragments are the two shapes measured in real code.
     */
    fun `test an unprojectable position is counted as one, and never as a decomposition`() {
        val swept = pass().over("corpus", listOf(queries().virtualFile), pathOf = { it.name })

        assertEquals(
            "the two unprojectable fragments were not counted as such",
            2,
            swept.fallbacks.count { it.reason == QueryFallback.UNPROJECTABLE_POSITION },
        )
        assertEquals(
            "the unprojectable-position rate is not over the fragments read",
            2,
            QueryRates(listOf(swept)).unprojectablePositions,
        )
    }

    /**
     * **A host range that does not map back to its own name is a host-range violation, and the run
     * fails on it** — provoked through the union projection, the one API that produces the shape.
     *
     * It is counted rather than thrown out of the pass, because an instrument that died on the first
     * one would report nothing about the corpus it died in — and then it is asserted, exactly.
     */
    fun `test a host range that does not map back fails the run`() {
        val swept = pass(hostRangeOf = { it.unionProjection() })
            .over("corpus", listOf(queries().virtualFile), pathOf = { it.name })

        assertEquals("the union projection produced no violation", 2, swept.violations.size)
        assertTrue(
            "the violation is not the range identity's: ${swept.violations.first().message}",
            "maps back to" in swept.violations.first().message,
        )
        assertTrue("the violation was counted as a file that threw instead", swept.failures.isEmpty())

        val refused = runCatching { assertNoHostRangeViolation(listOf(swept)) }.exceptionOrNull()
        assertTrue("a host-range violation did not fail the run: $refused", refused is IllegalStateException)
    }

    /** A file the pass throws on is a row in the report rather than the end of the run. */
    fun `test a file that throws is reported and the pass carries on`() {
        val thrown = FragmentReader { error("the reader threw on this fragment") }

        val swept = pass(thrown).over("corpus", listOf(queries().virtualFile), pathOf = { it.name })

        assertEquals("the throw was not reported", 1, swept.failures.size)
        assertTrue(
            "the failure does not say what threw: ${swept.failures.first().summary}",
            "the reader threw" in swept.failures.first().summary,
        )
    }

    private fun pass(
        reader: FragmentReader = EVERY_IDENTIFIER_IS_A_TABLE,
        hostRangeOf: (InjectedFragment) -> (TextRange) -> TextRange? = { fragment -> fragment::hostRangeOf },
    ) = QueryPass(project, reader, hostRangeOf)

    private fun queries(): PsiFile = myFixture.configureByText("Queries.java", QUERIES)

    private fun spellings(): PsiFile = myFixture.configureByText("Spellings.java", SPELLINGS)
}

/**
 * **The stand-in reader**: every fragment is a query, and every identifier in it is a table name.
 *
 * It decides nothing a real container would — what a container makes of a token is asserted on the
 * query fixtures, in the cell that has the plugins. What it buys here is a harness that can be watched
 * counting in every cell, including the ones where nothing injects a query at all.
 */
private val EVERY_IDENTIFIER_IS_A_TABLE = FragmentReader { fragment ->
    IDENTIFIERS_AS_TABLES.read(fragment)?.let { FragmentVerdict.Decomposed(it) }
        ?: FragmentVerdict.FellBack(QueryFallback.NOTHING_WAS_READ)
}

/** A file with no injection in it at all: the corpus a run that measured nothing is proved over. */
private val NO_QUERIES = """
    class NoQueries {
        String greeting = "hello";
        String path = "/tmp/ledger";
    }
""".trimIndent()

/**
 * The four shapes, as the range-identity fixtures lay them out: one clean, one escaped, one name
 * straddling a concatenation, and one sitting in the placeholder text a non-constant operand was
 * substituted by. The last two are the two shapes measured in real code.
 */
private val QUERIES = """
    class Queries {
        String tableVar;

        // language=JAVA
        String clean = "class customers {}";

        // language=JAVA
        String escaped = "class cust\157mers {}";

        // language=JAVA
        String straddling = "class cust" + "omers {}";

        // language=JAVA
        String substituted = "class " + tableVar + " {}";
    }
""".trimIndent()

/** One table written two ways, and one written one way — the fourth measurement's fixture. */
private val SPELLINGS = """
    class Spellings {
        // language=JAVA
        String upper = "class Customers {}";

        // language=JAVA
        String lower = "class customers {}";

        // language=JAVA
        String other = "class orders {}";
    }
""".trimIndent()
