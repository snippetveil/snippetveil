package com.snippetveil.plugin

import com.snippetveil.core.AnonymizationResult
import com.snippetveil.core.AnonymizationSettings
import com.snippetveil.core.LedgerSnapshot
import com.snippetveil.core.MintedName
import com.snippetveil.core.anonymize

/**
 * **Every accessor a Java class declares for a field is a row, whether or not the snippet showed it** —
 * over real PSI, in pure Java, which is where the defect has been live since 1.1.0.
 *
 * > Record a row for every spelling by which PSI says the declaration can be written — snippet or no
 * > snippet.
 *
 * The rows are the engine's and are asserted against plan literals in `SiblingRowsTest`. What is only
 * assertable here is which siblings the walk reports: **the members the class actually declares**,
 * matched by the rule that already decides what an accessor of a field is — never a name put together
 * out of a prefix and the field's.
 */
class SiblingSpellingRowsTest : JavaSnippetTestCase() {

    fun `test a field anonymized alone writes rows for the getter and setter its class declares`() {
        assertTheHarnessResolves()

        val result = resultFor(
            """
            package com.acme;

            public class Payment {
                <selection>private String merchantRef;</selection>
                public String getMerchantRef() { return merchantRef; }
                public void setMerchantRef(String merchantRef) { this.merchantRef = merchantRef; }
            }
            """.trimIndent(),
        )

        assertEquals("private String field1;", result.text)
        assertEquals(
            mapOf(
                "field:class:com.acme.Payment#merchantRef" to MintedName("field1", "merchantRef"),
                "method:class:com.acme.Payment#getMerchantRef" to MintedName("getField1", "getMerchantRef"),
                "method:class:com.acme.Payment#setMerchantRef" to MintedName("setField1", "setMerchantRef"),
            ),
            result.delta.placeholders,
        )
    }

    /**
     * **A Java getter contributes the Kotlin property spelling** — which is the field's own row, since
     * `payment.merchantRef` from Kotlin renders the field's placeholder — and the field's other
     * accessor is a sibling of the getter as surely as of the field.
     */
    fun `test a getter anonymized alone writes rows for its field and the field's setter`() {
        assertTheHarnessResolves()
        myFixture.addFileToProject("com/acme/Payment.java", PAYMENT)

        val result = resultFor(
            """
            package com.acme;

            class Checkout {
                String describe(Payment payment) { <selection>return payment.getMerchantRef();</selection> }
            }
            """.trimIndent(),
            path = "com/acme/Checkout.java",
        )

        assertEquals("return param1.getField2();", result.text)
        assertEquals(MintedName("field2", "merchantRef"), result.delta.placeholders["field:class:com.acme.Payment#merchantRef"])
        assertEquals(MintedName("setField2", "setMerchantRef"), result.delta.placeholders["method:class:com.acme.Payment#setMerchantRef"])
    }

    /** `isSettled()` is written `isSettled` from Kotlin as well as from Java, and its row keeps the `is`. */
    fun `test a boolean getter's row keeps the is prefix it is declared with`() {
        assertTheHarnessResolves()

        val result = resultFor(
            """
            package com.acme;

            public class Payment {
                <selection>private boolean settled;</selection>
                public boolean isSettled() { return settled; }
            }
            """.trimIndent(),
        )

        assertEquals(MintedName("isField1", "isSettled"), result.delta.placeholders["method:class:com.acme.Payment#isSettled"])
        assertFalse(
            "a getter the class does not declare was minted: ${result.delta.placeholders}",
            "method:class:com.acme.Payment#getSettled" in result.delta.placeholders,
        )
    }

    /**
     * **A spelling PSI cannot report gets no row.** A class with no setter mints no setter row, and a
     * method merely named like a field — fluent, or taking an argument — is not an accessor of it.
     */
    fun `test only the accessors the class declares are rows`() {
        assertTheHarnessResolves()

        val result = resultFor(
            """
            package com.acme;

            public class Payment {
                <selection>private String merchantRef;</selection>
                public String getMerchantRef() { return merchantRef; }
                public String merchantRef() { return merchantRef; }
                public String getMerchantRef(int width) { return merchantRef; }
            }
            """.trimIndent(),
        )

        assertEquals(
            setOf("field:class:com.acme.Payment#merchantRef", "method:class:com.acme.Payment#getMerchantRef"),
            result.delta.placeholders.keys,
        )
    }

    private fun resultFor(text: String, path: String = "com/acme/Payment.java"): AnonymizationResult =
        anonymize(planFor(path, text), AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)
}

private val PAYMENT = """
    package com.acme;

    public class Payment {
        private String merchantRef;
        public String getMerchantRef() { return merchantRef; }
        public void setMerchantRef(String merchantRef) { this.merchantRef = merchantRef; }
    }
""".trimIndent()
