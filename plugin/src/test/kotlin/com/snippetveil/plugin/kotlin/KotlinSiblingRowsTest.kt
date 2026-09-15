package com.snippetveil.plugin.kotlin

import com.snippetveil.core.MintedName
import com.snippetveil.sweep.SourceDeclarations
import com.snippetveil.sweep.SourceSpellings

/**
 * **A Kotlin declaration is a second source of accessor spellings, and each one is a row** — over real
 * PSI, in the one cell a Kotlin fixture may run in.
 *
 * > The closure contains exactly those spellings by which a project-owned declaration can be written
 * > in a `.java` or `.kt` source file.
 *
 * Kotlin does not create the defect `SiblingSpellingRowsTest` pins in pure Java; it makes it common,
 * because every `val` is a getter nobody wrote. What is only assertable here is which spellings the
 * Kotlin walk reads off a light class — and that they are the ones the leak check's own closure
 * derives from declaration text, by an implementation that shares nothing with this one.
 *
 * **The rows here are accessors.** A facade, a `@JvmName`, an `internal` mangling, a named companion
 * and an object's instance are asserted as what they already are — spellings the declaration's own row
 * is keyed by — and a facade gains no row of its own, because nothing it could render from would
 * leave the counter where it stood.
 */
internal class KotlinSiblingRowsTest : KotlinSnippetTestCase() {

    fun `test a var contributes its backing field, its getter and its setter`() {
        assertTheSessionIsK2()

        val rows = rowsFor(
            """
            package com.acme.ledger

            class Payment {
                <selection>var body: String = ""</selection>
            }
            """.trimIndent(),
        )

        assertEquals(MintedName("field1", "body"), rows["field:class:com.acme.ledger.Payment#body"])
        assertEquals(MintedName("getField1", "getBody"), rows["method:class:com.acme.ledger.Payment#getBody"])
        assertEquals(MintedName("setField1", "setBody"), rows["method:class:com.acme.ledger.Payment#setBody"])
    }

    /** A `val` has no setter, and that is asserted as absent rather than assumed. */
    fun `test a val contributes its getter and no setter`() {
        assertTheSessionIsK2()

        val rows = rowsFor(
            """
            package com.acme.ledger

            class Payment(<selection>val merchantRef: String</selection>)
            """.trimIndent(),
        )

        assertEquals(MintedName("getField1", "getMerchantRef"), rows["method:class:com.acme.ledger.Payment#getMerchantRef"])
        assertTrue("a val was given a setter row: ${rows.keys}", rows.keys.none { it.endsWith("#setMerchantRef") })
    }

    /** `val isSettled` is its own getter: the row is `isSettled`, under `is`, and `getIsSettled` is minted nowhere. */
    fun `test an is-prefixed val contributes isX and not getIsX`() {
        assertTheSessionIsK2()

        val rows = rowsFor(
            """
            package com.acme.ledger

            class Payment {
                <selection>val isSettled: Boolean = false</selection>
            }
            """.trimIndent(),
        )

        assertEquals(MintedName("isField1", "isSettled"), rows["method:class:com.acme.ledger.Payment#isSettled"])
        assertTrue("a getter nobody can write was minted: $rows", rows.keys.none { "getIsSettled" in it })
        assertTrue("a getter nobody can write was minted: $rows", rows.values.none { it.original == "getIsSettled" })
    }

    /**
     * **A Java `getBody()` and `isSettled()` contribute the spellings Kotlin writes them in** — `body`,
     * which is the field's own row, and `isSettled`, which is the getter's.
     */
    fun `test a Java getter contributes the Kotlin property spelling`() {
        assertTheSessionIsK2()
        myFixture.addFileToProject(
            "com/acme/ledger/Invoice.java",
            """
            package com.acme.ledger;

            public class Invoice {
                private String body;
                private boolean settled;
                public String getBody() { return body; }
                public boolean isSettled() { return settled; }
            }
            """.trimIndent(),
        )

        val result = kotlinResultFor(
            "com/acme/ledger/Reader.kt",
            """
            package com.acme.ledger

            fun read(invoice: Invoice) {
                <selection>println(invoice.body)
                println(invoice.isSettled)</selection>
            }
            """.trimIndent(),
        )
        val rows = result.delta.placeholders

        assertEquals("println(param1.field2)\n    println(param1.isField3)", result.text)
        assertEquals(MintedName("field2", "body"), rows["field:class:com.acme.ledger.Invoice#body"])
        assertEquals(MintedName("isField3", "isSettled"), rows["method:class:com.acme.ledger.Invoice#isSettled"])
    }

    /**
     * **The facade is the owner in a top-level declaration's key, and the facade itself gets no row.**
     *
     * The second half is the gap #114 left open rather than a rule: a facade's row would need a
     * placeholder, a facade has none of its own to render from, and the ticket forbids a sibling
     * spelling a number. It is asserted so that closing the gap has to change this test on purpose.
     */
    fun `test a top-level declaration's row is keyed through its facade, and the facade has no row`() {
        assertTheSessionIsK2()

        val plain = rowsFor(
            """
            package com.acme.ledger

            <selection>fun settle(amount: Int): Int = amount</selection>
            """.trimIndent(),
            path = "com/acme/ledger/Ledger.kt",
        )
        val renamed = rowsFor(
            """
            @file:JvmName("Settlements")
            package com.acme.ledger

            <selection>fun batch(): Int = 1</selection>
            """.trimIndent(),
            path = "com/acme/ledger/Batches.kt",
        )

        assertTrue("the facade is not the owner in the key: ${plain.keys}", "method:class:com.acme.ledger.LedgerKt#settle" in plain)
        assertTrue("the @file:JvmName is not the owner in the key: ${renamed.keys}", "method:class:com.acme.ledger.Settlements#batch" in renamed)
        assertFalse("a facade gained a row of its own: ${plain.keys}", "class:com.acme.ledger.LedgerKt" in plain)
        assertFalse("a facade gained a row of its own: ${renamed.keys}", "class:com.acme.ledger.Settlements" in renamed)
    }

    /**
     * **`@JvmName`, `internal`, a named companion and an object are keyed by the spelling Java writes**,
     * so the declaration's own row already carries it — and no second row stands the same placeholder
     * for a second name, which is what a reversal reading one table in both languages cannot afford.
     */
    fun `test a JvmName, an internal mangling, a named companion and an object are their own rows' keys`() {
        assertTheSessionIsK2()

        val rows = rowsFor(
            """
            package com.acme.ledger

            <selection>class Payment {
                @JvmName("settleNow")
                fun settleLater() {}

                internal fun reconcile(): Int = 0

                companion object Registry {
                    fun lookup(): Int = 0
                }
            }

            object Settlement {
                fun run() {}
            }</selection>
            """.trimIndent(),
        )

        assertEquals("settleLater", rows["method:class:com.acme.ledger.Payment#settleNow"]?.original)
        assertTrue("the internal member is not keyed mangled: ${rows.keys}", rows.keys.any { it.startsWith("method:class:com.acme.ledger.Payment#reconcile$") })
        assertEquals("Registry", rows["class:com.acme.ledger.Payment.Registry"]?.original)
        assertEquals("Settlement", rows["class:com.acme.ledger.Settlement"]?.original)
        assertTrue("INSTANCE entered the mapping: $rows", rows.keys.none { "INSTANCE" in it } && rows.values.none { it.original == "INSTANCE" })

        val placeholders = rows.values.groupBy { it.placeholder }
        assertTrue("one placeholder stands for two names: $placeholders", placeholders.values.all { named -> named.map { it.original }.distinct().size == 1 })
    }

    /**
     * **Out by the rule, not by omission**: a value class's `-impl`, `$default`, `access$…` and
     * `DefaultImpls` are the compiler's; and `it`, `Companion`, `component1` and `copy` are the
     * language's. None is written in source, so none is a row — read over the declarations that produce
     * every one of them.
     */
    fun `test the compiler's manglings and the names the language fixes never enter the mapping`() {
        assertTheSessionIsK2()

        val rows = rowsFor(
            """
            package com.acme.ledger

            @JvmInline
            value class Amount(val cents: Long) {
                fun doubled(): Amount = Amount(cents * 2)
            }

            interface Settles {
                fun settle(amount: Int = 0): Int = amount
            }

            internal fun reconcile(times: Int = 1): Int = times

            class Vault {
                private var secret: Int = 1

                inner class Door {
                    fun open(): Int = secret++
                }
            }

            data class Entry(val amount: Int) {
                companion object {
                    fun zero(): Entry = Entry(0)
                }
            }

            fun total(entries: List<Entry>): Int = entries.sumOf { it.component1() }

            fun reset(entry: Entry): Entry = entry.copy(amount = 0)
            """.trimIndent(),
        )

        assertTrue("the fixture wrote nothing down, so nothing below was checked", rows.isNotEmpty())
        val spellings = rows.values.map { it.original } + rows.keys.map { key -> key.substringAfterLast('#').substringAfterLast('.') }
        listOf("-impl", "\$default", "access\$", "DefaultImpls").forEach { mangled ->
            assertTrue("`$mangled` is the compiler's and entered the mapping: $rows", spellings.none { mangled in it })
        }
        listOf("it", "Companion", "component1", "copy").forEach { fixed ->
            assertTrue("`$fixed` is fixed by the language and entered the mapping: $rows", fixed !in spellings)
        }
    }

    /**
     * **The rule's two implementations agree on a fixture — and share nothing else.**
     *
     * The leak check's closure derives spellings from declaration text and file names; this walk reads
     * what PSI reports. Each is asserted against the other in both directions: every name the mapping
     * holds is a spelling the closure derived or read, and every accessor the closure derived is a
     * name the mapping holds. The fixture is whole-file, so nothing about a selection can put a
     * spelling on one side only.
     *
     * **One difference is the closure's by design, and it is asserted as exactly that.** An `internal`
     * property's accessors are written with the module mangled in, and the closure — which cannot read
     * the module the compiler was given — derives the unmangled `getCursor` beside the mangled one. It
     * is allowed to over-include, since a spelling nobody compiled costs a false positive and never a
     * leak; the mangled spelling, which is the one Java writes, is on both sides.
     */
    fun `test the sibling rows agree with the leak check's closure on a fixture`() {
        assertTheSessionIsK2()

        val plan = kotlinPlanFor(
            "com/acme/ledger/Payment.kt",
            """
            package com.acme.ledger

            class Payment(val merchantRef: String, var body: String) {
                var isSettled: Boolean = false
                internal var cursor: Int = 0
            }
            """.trimIndent(),
        )
        val originals = kotlinResultFor(plan).delta.placeholders.values.map { it.original }.toSet()
        val closure = SourceSpellings.of(SourceDeclarations.of(project, listOf(myFixture.file.virtualFile)).declarations)
        val derivedAccessors = closure.names.filter { name ->
            closure.derivationOf(name).orEmpty().let { "getter" in it || "setter" in it }
        }

        assertTrue("the closure derived no accessor, so the agreement below is vacuous", derivedAccessors.isNotEmpty())
        assertTrue("the mapping holds no accessor, so the agreement below is vacuous", "getBody" in originals)
        assertTrue("the mapping holds no mangled accessor, so the mangling is unchecked: $originals", originals.any { it.startsWith("getCursor$") })
        assertEquals("names the mapping holds that the closure never derived", emptySet<String>(), originals - closure.names)
        assertEquals(
            "accessors the closure derived that the mapping does not hold, beyond the unmangled internal ones it over-includes",
            setOf("getCursor", "setCursor"),
            derivedAccessors.toSet() - originals,
        )
    }

    private fun rowsFor(text: String, path: String = "com/acme/ledger/Payment.kt"): Map<String, MintedName> =
        kotlinResultFor(path, text).delta.placeholders
}
