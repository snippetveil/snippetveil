package com.snippetveil.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * **A SQL name in the occurrence plan, and the placeholder it renders as** — stated against plan
 * literals, with no IDE and no injected-language work anywhere in it.
 *
 * Nothing here builds a SQL plan from real code. These tests state the evidence a SQL walk would
 * report and assert what the engine makes of it, which is the whole of what `:core` is responsible
 * for: a third language value, three placeholder kinds, two ephemeral key shapes, and a rendering
 * that writes into the identifier's name range so the delimiters survive.
 */
class SqlNameTest {

    /**
     * **The three kinds, and their lowercase prefixes.** A rowset takes `table`, a value in a rowset
     * takes `col`, a namespace qualifier takes `schema` — all drawn from the one shared counter.
     */
    @Test
    fun `a table, a column and a schema take their own lowercase kinds`() {
        val plan = sqlPlan(
            "SELECT total FROM billing.invoices",
            sql("total", column("total")),
            sql("billing", schema("billing")),
            sql("invoices", table("invoices")),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("SELECT col1 FROM schema2.table3", result.text)
        assertEquals(listOf(MappedKind.COLUMN, MappedKind.SCHEMA, MappedKind.TABLE), result.names.map { it.kind })
        assertEquals("table", SymbolRole.TABLE.placeholderPrefix)
        assertEquals("col", SymbolRole.COLUMN.placeholderPrefix)
        assertEquals("schema", SymbolRole.SCHEMA.placeholderPrefix)
    }

    /**
     * **`table3` is the third *symbol* in the output, never the third table.** The number comes from
     * the one counter every kind shares, so a two-table join is not `table1, table2` — and a reader
     * looking for `table1` here will not find it, because the first thing named was a column.
     */
    @Test
    fun `the number on a table placeholder counts symbols, not tables`() {
        val plan = sqlPlan(
            "SELECT total FROM orders JOIN customers ON orders.customer_id = customers.id",
            sql("total", column("total")),
            sql("orders", table("orders")),
            sql("customers", table("customers")),
            sql("orders", table("orders"), ordinal = 1),
            sql("customer_id", column("customer_id")),
            sql("customers", table("customers"), ordinal = 1),
            // The first `id` in the text is the tail of `customer_id`, which is not a token.
            sql("id", column("id"), ordinal = 1),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("SELECT col1 FROM table2 JOIN table3 ON table2.col4 = table3.col5", result.text)
        assertFalse("table1" in result.text, "a table was numbered by counting tables:\n${result.text}")
    }

    /**
     * **Both ephemeral key shapes allocate, and neither reaches the persisted ledger.** A SQL key is
     * unqualified, so the rule that keeps a local out keeps it out too — there is no declaration for
     * the name to belong to. The numbers are still drawn and still burnt, which is what the counter
     * advancing says.
     */
    @Test
    fun `neither SQL key shape is ever written down`() {
        val plan = sqlPlan(
            "WITH recent AS (SELECT id FROM orders) SELECT id FROM recent",
            sql("recent", declaredTable("q1", "recent")),
            sql("id", column("id")),
            sql("orders", table("orders")),
            sql("id", column("id"), ordinal = 1),
            sql("recent", declaredTable("q1", "recent"), ordinal = 1),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("WITH table1 AS (SELECT col2 FROM table3) SELECT col2 FROM table1", result.text)
        assertEquals(emptyMap<String, MintedName>(), result.delta.placeholders, "a SQL key was persisted")
        assertEquals(4, result.delta.nextNumber, "the SQL names drew no numbers")

        // Nothing written down, so the same fragment pasted again is named afresh — after the counter.
        val again = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY + result.delta)
        assertEquals("WITH table4 AS (SELECT col5 FROM table6) SELECT col5 FROM table4", again.text)
    }

    /**
     * **A name a fragment declares is that fragment's own.** Two fragments each declaring `recent`
     * are two symbols, and a declared `recent` is not the invocation-wide table spelled the same way.
     */
    @Test
    fun `a declared name keys by its fragment and never meets an invocation-wide one`() {
        val first = SqlKeys.declared("q1", SymbolRole.TABLE, "recent")

        assertNotEquals(first, SqlKeys.declared("q2", SymbolRole.TABLE, "recent"))
        assertNotEquals(first, SqlKeys.declared("q1", SymbolRole.COLUMN, "recent"))
        assertNotEquals(first, SqlKeys.named(SymbolRole.TABLE, "recent"))
        // Injective in its parts, however a fragment's identity happens to be spelled.
        assertNotEquals(SqlKeys.declared("a:b", SymbolRole.TABLE, "c"), SqlKeys.declared("a", SymbolRole.TABLE, "b:c"))

        val plan = sqlPlan(
            "FROM recent; FROM recent; FROM recent",
            sql("recent", declaredTable("q1", "recent")),
            sql("recent", declaredTable("q2", "recent"), ordinal = 1),
            sql("recent", table("recent"), ordinal = 2),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("FROM table1; FROM table2; FROM table3", result.text)
    }

    /**
     * **The placeholder goes into the name range, so the delimiters survive** — for each of the
     * three ways a dialect delimits an identifier. Normalising to a bare placeholder would rewrite
     * what the query means, because a delimited identifier is case-sensitive where a bare one folds.
     */
    @Test
    fun `a delimited identifier renders with its delimiters intact`() {
        for (written in listOf("\"customers\"", "`customers`", "[customers]")) {
            val plan = sqlPlan("SELECT * FROM $written", sql(written, table(written, name = "customers")))

            val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

            val (open, close) = written.first() to written.last()
            assertEquals("SELECT * FROM ${open}table1$close", result.text)
            assertEquals(mapOf("table1" to "customers"), result.mapping)
        }
    }

    /**
     * **`"Customers"` and `customers` name different tables**, and so do `"customers"` and
     * `customers` in any dialect that folds bare names to upper case. The spelling keys as written,
     * delimiters and case included, so no two of these share a placeholder.
     */
    @Test
    fun `a delimited and a bare spelling of the same word do not share a placeholder`() {
        val plan = sqlPlan(
            "SELECT * FROM \"Customers\" JOIN customers JOIN \"customers\"",
            sql("\"Customers\"", table("\"Customers\"", name = "Customers")),
            sql("customers", table("customers")),
            sql("\"customers\"", table("\"customers\"", name = "customers")),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("SELECT * FROM \"table1\" JOIN table2 JOIN \"table3\"", result.text)
        assertEquals(3, result.names.map { it.key }.distinct().size)
    }

    /**
     * **A stem carrying `$` is refused by the engine**, driven here with no dialog in front of it.
     * `$` is legal in a Java identifier and illegal in an unquoted SQL one, so `my$table1` would not
     * be a legal placeholder in the output it is spliced into. The engine falls back to the default
     * namespace, and records nothing as minted.
     */
    @Test
    fun `a stem carrying a dollar is refused and falls back to the default namespace`() {
        assertEquals(StemRejection.CARRIES_A_DOLLAR, stemRejection("my\$table"))
        assertEquals(StemRejection.CARRIES_A_DOLLAR, stemRejection("\$table"))

        val plan = sqlPlan("SELECT * FROM customers", sql("customers", table("customers")))
        val key = SqlKeys.named(SymbolRole.TABLE, "customers")

        val result = anonymize(plan, AnonymizationSettings(renamedStems = mapOf(key to "my\$table")), LedgerSnapshot.EMPTY)

        assertEquals("SELECT * FROM table1", result.text)
        assertEquals(emptySet<String>(), result.delta.mintedStems)

        // The control: a stem without the `$` does reach a SQL key, so the fallback above is the
        // refusal and not a rename that never applied to SQL names at all.
        val renamed = anonymize(plan, AnonymizationSettings(renamedStems = mapOf(key to "myTable")), LedgerSnapshot.EMPTY)
        assertEquals("SELECT * FROM myTable1", renamed.text)
    }

    /**
     * **The kind column says `table`, `col` and `schema`** — the words the placeholders are written
     * in, so a row reads the same in the `Kind` column as in the `Placeholder` column beside it.
     * `column` would be a fourth spelling of a thing the product already calls `col`.
     */
    @Test
    fun `a query row's kind reads table, col and schema`() {
        val result = anonymize(
            threeKinds(),
            AnonymizationSettings.DEFAULTS,
            LedgerSnapshot.EMPTY,
        )

        assertEquals(listOf("col", "schema", "table"), result.names.map { it.kind.label })
    }

    /**
     * **A query row is minted by this invocation against a key of its own, so it takes the ordinary
     * editable state** — and nothing new: its key is unqualified, so the rename lasts the invocation
     * and the next snippet re-mints `table` under the default stem, which is the trade a renamed
     * local already makes. The word is recorded all the same, which is what the return leg reads.
     */
    @Test
    fun `a renamed query row lasts the invocation and the next snippet re-mints the default stem`() {
        val plan = ordersPlan()
        val key = ORDERS

        val first = anonymize(plan, AnonymizationSettings(renamedStems = mapOf(key to "OrdersTable")), LedgerSnapshot.EMPTY)
        val second = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY + first.delta)

        assertEquals(Renaming.OFFERED, anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY).names.single().renaming)
        assertEquals("SELECT * FROM OrdersTable1", first.text)
        assertEquals(setOf("OrdersTable"), first.delta.mintedStems)
        assertEquals(emptyMap<String, MintedName>(), first.delta.placeholders, "a query row's key was ledgered")
        assertEquals("SELECT * FROM table2", second.text)
        assertEquals(Renaming.OFFERED, second.names.single().renaming)
    }

    /**
     * **The return leg admits `table` by its namespace alone.** Nothing in either table holds it —
     * a SQL key is never ledgered and the sidecar here has forgotten the snippet — so the only thing
     * that can tell the reply's `table4` from a word of the model's own is the shape recogniser, and
     * it does so because the namespace exists. Above the counter it is foreign; below, evicted.
     */
    @Test
    fun `the shape recogniser admits a table placeholder`() = assertAdmitted(SymbolRole.TABLE)

    /** See the `table` case above. */
    @Test
    fun `the shape recogniser admits a col placeholder`() = assertAdmitted(SymbolRole.COLUMN)

    /** See the `table` case above. */
    @Test
    fun `the shape recogniser admits a schema placeholder`() = assertAdmitted(SymbolRole.SCHEMA)

    /**
     * **A renamed query row round-trips through the minted-stems set, and through nothing else.**
     * The key was never ledgered and the sidecar has forgotten the snippet, so `OrdersTable1` is in
     * neither table — and it is still recognised as this project's, because the word it was minted
     * under was written down. Without that set it would be a word nobody recognises, and `Paste`
     * would write it into source.
     *
     * The control is the set itself: the same reply against a mapping that never recorded the stem
     * reports nothing at all.
     */
    @Test
    fun `a renamed query row is recognised from a fresh invocation with the sidecar empty`() {
        val plan = ordersPlan()
        val key = ORDERS
        val renamed = anonymize(plan, AnonymizationSettings(renamedStems = mapOf(key to "OrdersTable")), LedgerSnapshot.EMPTY)
        val committed = LedgerSnapshot.EMPTY + renamed.delta

        // A fresh invocation of the same snippet, committed after it: the stem survives it.
        val fresh = anonymize(plan, AnonymizationSettings.DEFAULTS, committed)
        val later = committed + fresh.delta

        val reply = "Add an index on OrdersTable1 before the join."
        val back = deanonymize(reply, Sidecar.EMPTY, later)

        assertEquals("SELECT * FROM OrdersTable1", renamed.text)
        assertEquals(reply, back.text)
        assertEquals(listOf(Unrestored("OrdersTable1", UnrestoredReason.EVICTED)), back.unrestored)

        val unrecorded = LedgerSnapshot(later.placeholders, later.nextNumber, mintedStems = emptySet())
        assertEquals(emptyList<Unrestored>(), deanonymize(reply, Sidecar.EMPTY, unrecorded).unrestored)
    }

    /**
     * **A query placeholder has no sibling spellings, so none is written.** Sibling rows exist
     * because a field's accessors are derived from its placeholder; nothing derives an accessor from
     * a table name, so the closure obligation does not grow. Asserted on both tables a reversal
     * reads: no ledger row at all, and a sidecar table holding the three placeholders and nothing
     * spelled from them.
     */
    @Test
    fun `a query placeholder writes no sibling-spelling rows`() {
        val result = anonymize(
            threeKinds(),
            AnonymizationSettings.DEFAULTS,
            LedgerSnapshot.EMPTY,
        )

        assertEquals(emptyMap<String, MintedName>(), result.delta.placeholders)
        assertEquals(mapOf("col1" to "total", "schema2" to "billing", "table3" to "invoices"), result.mapping)
    }

    /**
     * A reply naming a [role] placeholder that neither table holds: below the counter it is evicted,
     * at or above it foreign — reported either way, which is what *admitted* means.
     */
    private fun assertAdmitted(role: SymbolRole) {
        val prefix = role.placeholderPrefix
        val mapping = LedgerSnapshot(emptyMap(), nextNumber = 10)

        // `rows4` ends in digits and is in no namespace — the control that this is about [role]'s
        // namespace and not about any word with a number on it.
        val back = deanonymize("Join on ${prefix}4, not ${prefix}12 or rows4.", Sidecar.EMPTY, mapping)

        assertEquals(
            listOf(Unrestored("${prefix}4", UnrestoredReason.EVICTED), Unrestored("${prefix}12", UnrestoredReason.FOREIGN)),
            back.unrestored,
        )
    }

    /** A column, a schema and a table, in that order. */
    private fun threeKinds() = sqlPlan(
        "SELECT total FROM billing.invoices",
        sql("total", column("total")),
        sql("billing", schema("billing")),
        sql("invoices", table("invoices")),
    )

    /** One table, `orders`, keyed [ORDERS]. */
    private fun ordersPlan() = sqlPlan("SELECT * FROM orders", sql("orders", table("orders")))

    /** One SQL token: the text written at it, which occurrence of that text it is, and its symbol. */
    private class SqlToken(val written: String, val ordinal: Int, val symbol: SymbolEvidence)

    private fun sql(written: String, symbol: SymbolEvidence, ordinal: Int = 0) = SqlToken(written, ordinal, symbol)

    /**
     * A plan over [text] whose SQL tokens are placed by the text written at them. A token wider than
     * its symbol's name is a **delimited** identifier, and its name range is the name inside the
     * delimiters — which is the one fact these tests need a plan literal to say that the Java helpers
     * do not.
     */
    private fun sqlPlan(text: String, vararg tokens: SqlToken): SnippetPlan {
        val occurrences = tokens.map { token ->
            val at = generateSequence(text.indexOf(token.written)) {
                text.indexOf(token.written, it + token.written.length)
            }.takeWhile { it >= 0 }.elementAt(token.ordinal)
            val inset = (token.written.length - token.symbol.declaredName.length) / 2
            SymbolOccurrence(
                start = at,
                end = at + token.written.length,
                text = token.written,
                symbol = token.symbol,
                language = SourceLanguage.SQL,
                nameStart = at + inset,
                nameEnd = at + token.written.length - inset,
            )
        }
        return SnippetPlan(text, occurrences.sortedBy { it.start })
    }

    /**
     * A SQL name keyed invocation-wide by `(kind, spelling)`. [spelling] is the token as written,
     * delimiters included; [name] is what stands inside them.
     */
    private fun named(role: SymbolRole, spelling: String, name: String) =
        symbol(name, role, SymbolOrigin.IN_CONTENT, key = SqlKeys.named(role, spelling))

    private fun table(spelling: String, name: String = spelling) = named(SymbolRole.TABLE, spelling, name)

    private fun column(spelling: String) = named(SymbolRole.COLUMN, spelling, spelling)

    private fun schema(spelling: String) = named(SymbolRole.SCHEMA, spelling, spelling)

    /** A table a fragment itself declares — a CTE — keyed by `(fragment, kind, name)`. */
    private fun declaredTable(fragment: String, name: String) =
        symbol(name, SymbolRole.TABLE, SymbolOrigin.IN_CONTENT, key = SqlKeys.declared(fragment, SymbolRole.TABLE, name))
}

/** The key of the table spelled `orders`. */
private val ORDERS = SqlKeys.named(SymbolRole.TABLE, "orders")
