package com.snippetveil.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * **A suffix goes out with the placeholder and only with it** — the rendering a stack trace's file
 * name needs: `Ledger.java` is named after its public class, so it renders that class's placeholder
 * with the resolved file's real extension, and a preserved one stays exactly as it was written.
 */
class SymbolSuffixTest {

    @Test
    fun `a replaced symbol is written as its placeholder followed by its suffix`() {
        val text = "at Ledger.settle(Ledger.kt:3)"
        val result = anonymize(
            SnippetPlan(text, listOf(fileNamed(text, "Ledger.kt", LEDGER, suffix = ".java"))),
            AnonymizationSettings.DEFAULTS,
            LedgerSnapshot.EMPTY,
        )

        assertEquals("at Ledger.settle(Type1.java:3)", result.text)
    }

    @Test
    fun `a preserved symbol is left exactly as written, suffix and all`() {
        val text = "at Ledger.settle(Ledger.java:3)"
        val library = SymbolEvidence("class:org.lib.Ledger", SymbolRole.TYPE, SymbolOrigin.LIBRARY, "Ledger", packageName = "org.lib")
        val result = anonymize(
            SnippetPlan(text, listOf(fileNamed(text, "Ledger.java", library, suffix = ".class"))),
            AnonymizationSettings.DEFAULTS,
            LedgerSnapshot.EMPTY,
        )

        assertEquals(text, result.text)
    }

    @Test
    fun `the suffix shares the placeholder the symbol has everywhere else`() {
        val text = "at com.acme.Ledger.settle(Ledger.java:3)"
        val type = text.indexOf("Ledger")
        val result = anonymize(
            SnippetPlan(
                text,
                listOf(
                    SymbolOccurrence(type, type + 6, "Ledger", LEDGER, SourceLanguage.JAVA),
                    fileNamed(text, "Ledger.java", LEDGER, suffix = ".java"),
                ),
            ),
            AnonymizationSettings.DEFAULTS,
            LedgerSnapshot.EMPTY,
        )

        assertEquals("at com.acme.Type1.settle(Type1.java:3)", result.text)
    }

    private fun fileNamed(text: String, written: String, symbol: SymbolEvidence, suffix: String): SymbolOccurrence {
        val start = text.indexOf("($written") + 1
        return SymbolOccurrence(start, start + written.length, written, symbol, SourceLanguage.JAVA, suffix = suffix)
    }
}

private val LEDGER = SymbolEvidence(
    key = "class:com.acme.Ledger",
    role = SymbolRole.TYPE,
    origin = SymbolOrigin.IN_CONTENT,
    declaredName = "Ledger",
    qualifiedName = "com.acme.Ledger",
    packageName = "com.acme",
    keyIsQualified = true,
)
