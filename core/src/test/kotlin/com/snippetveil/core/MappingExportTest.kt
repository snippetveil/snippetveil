package com.snippetveil.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * **The file `Export Mapping…` writes** — one invocation's table, as CSV.
 *
 * The export exists because the sidecar is bounded: locals, parameters, type parameters,
 * anonymous-class members and **every string literal** live there and nowhere else, so once an
 * invocation falls past the horizon that half of its mapping is gone permanently. This file is the
 * only way to keep a specific conversation decodable past that point.
 *
 * What is under test here is the *format*, over values and without an IDE. Where the file may be
 * reached from — the preview and its read-only re-open, and nowhere else — is a fact about the
 * plugin's surfaces and is asserted there.
 */
class MappingExportTest {

    /**
     * **Placeholder first, because the lookup direction is always *reply → source*.** A user reading
     * this file is holding a word out of an AI's answer and wants the name it stood for; a file
     * ordered the other way would have them scanning the column they do not have.
     *
     * Rows are in first-occurrence order — the engine's own order, which is the order the snippet
     * introduces the names, so the file reads top to bottom against the snippet it reverses.
     */
    @Test
    fun `the export is placeholder, original and kind, in first-occurrence order`() {
        val result = anonymize(
            planOf(
                "class Ledger { void settle(int amount) {} }",
                symbol("Ledger", SymbolRole.TYPE, SymbolOrigin.IN_CONTENT),
                symbol("settle", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT),
                symbol("amount", SymbolRole.PARAMETER, SymbolOrigin.IN_CONTENT),
            ),
            AnonymizationSettings.DEFAULTS,
            LedgerSnapshot.EMPTY,
        )

        assertEquals(
            "Placeholder,Original,Kind\r\n" +
                "Type1,Ledger,Type\r\n" +
                "method2,settle,method\r\n" +
                "param3,amount,param\r\n",
            result.mappingCsv(),
        )
    }

    /**
     * **A literal's text is arbitrary content the user wrote, so the quoting is not decoration.** A
     * comma in a message is the ordinary case, and unquoted it splits one row into two — silently,
     * because the row still parses.
     *
     * Minimal quoting rather than quoting everything: this file is read by people as often as by a
     * parser, and a column of quotes around `Type1` costs legibility on every row to say something
     * about none of them.
     */
    @Test
    fun `a comma in a literal is quoted rather than allowed to split the row`() {
        val result = anonymize(
            planOf("""log.warn("settlement failed, unreconciled");""")
                .withLiteral(""""settlement failed, unreconciled""""),
            AnonymizationSettings.DEFAULTS,
            LedgerSnapshot.EMPTY,
        )

        assertEquals(
            "Placeholder,Original,Kind\r\n" +
                "str1,\"settlement failed, unreconciled\",literal\r\n",
            result.mappingCsv(),
        )
    }

    /**
     * **A text block carries raw quotes and raw newlines, and both come back out of the file
     * unchanged.**
     *
     * Asserted through a reader written the other way round rather than against a string this test
     * spelled out: *properly quoted* means a conforming parser recovers the value, and a writer
     * checked only against expectations its own author wrote can agree with itself while agreeing
     * with nothing else.
     */
    @Test
    fun `a text block's newlines and quotes survive the file`() {
        val query = "    SELECT * FROM \"merchants\", ledgers\n    "
        val result = anonymize(
            SnippetPlan("String query = \"\"\"\n$query\"\"\";", emptyList())
                .withLiteral("\"\"\"\n$query\"\"\"", LiteralKind.TEXT_BLOCK),
            AnonymizationSettings.DEFAULTS,
            LedgerSnapshot.EMPTY,
        )

        assertEquals(
            listOf(
                listOf("Placeholder", "Original", "Kind"),
                listOf("str1", query, "literal"),
            ),
            readCsv(result.mappingCsv()),
        )
    }

    /**
     * **A preserved unresolved name is not a row.** It stands for itself: it is written into the
     * snippet under its own name, so a row for it would be a name mapping to itself — a line that
     * decodes a reply that never needed decoding, and the only line in this file that would put a
     * real name in the placeholder column.
     */
    @Test
    fun `a name this invocation preserved is not exported`() {
        val plan = planOf(
            "class Ledger { void settle(MissingType m) {} }",
            symbol("Ledger", SymbolRole.TYPE, SymbolOrigin.IN_CONTENT),
            symbol("MissingType", SymbolRole.TYPE, SymbolOrigin.UNRESOLVED, key = "unresolved:MissingType"),
        )

        val result = anonymize(
            plan,
            AnonymizationSettings(preservedUnknowns = setOf("unresolved:MissingType")),
            LedgerSnapshot.EMPTY,
        )

        assertEquals("MissingType", result.names.single { it.placeholder == null }.original)
        assertEquals("Placeholder,Original,Kind\r\nType1,Ledger,Type\r\n", result.mappingCsv())
    }
}

/**
 * A CSV reader, to RFC 4180 — the independent half of the quoting assertions above.
 *
 * Deliberately naive about everything the writer is not asked to produce: it reads what a
 * conforming reader reads and nothing more, so a file this recovers is one a spreadsheet recovers.
 */
private fun readCsv(csv: String): List<List<String>> {
    val records = mutableListOf<List<String>>()
    val fields = mutableListOf<String>()
    val field = StringBuilder()
    var quoted = false
    var at = 0

    while (at < csv.length) {
        val char = csv[at]
        when {
            quoted && char == '"' && csv.getOrNull(at + 1) == '"' -> { field.append('"'); at++ }
            char == '"' -> quoted = !quoted
            quoted -> field.append(char)
            char == ',' -> { fields += field.toString(); field.clear() }
            char == '\r' && csv.getOrNull(at + 1) == '\n' -> {
                fields += field.toString()
                field.clear()
                records += fields.toList()
                fields.clear()
                at++
            }
            else -> field.append(char)
        }
        at++
    }

    check(field.isEmpty() && fields.isEmpty()) { "the file ended mid-record: `$csv`" }
    return records
}
