package com.snippetveil.sweep

import com.snippetveil.sweep.Declaration.JavaMethod
import com.snippetveil.sweep.Declaration.JvmName
import com.snippetveil.sweep.Declaration.KotlinFacade
import com.snippetveil.sweep.Declaration.KotlinFunction
import com.snippetveil.sweep.Declaration.KotlinObject
import com.snippetveil.sweep.Declaration.KotlinProperty
import com.snippetveil.sweep.Declaration.Written
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **The closure, row by row** — every spelling by which a project-owned declaration can be written in
 * a `.java` or `.kt` source file, derived from declaration text and file names and nothing else.
 *
 * Each expected spelling is a literal taken from what the Kotlin compiler writes for that declaration,
 * never recomputed the way the closure computes it: a test that mangled `body` into `getBody` by the
 * same rule would agree with the closure whatever the compiler did. The platform's own answer for the
 * same shapes is asserted over real PSI in `SweepDeclarationWalkTest`, in the one cell a Kotlin fixture
 * may run in.
 */
class SourceSpellingsTest {

    @Test
    fun `a var is spelled by its getter, its setter and its backing field`() {
        val spellings = spellingsOf(KotlinProperty("body", mutable = true))

        assertIn(spellings, "body", "getBody", "setBody")
    }

    /** *Exactly* those spellings by which it can be written — and a `val` has no setter to write. */
    @Test
    fun `a val is spelled by its getter and has no setter`() {
        val spellings = spellingsOf(KotlinProperty("body", mutable = false))

        assertIn(spellings, "body", "getBody")
        assertOut(spellings, "setBody")
    }

    @Test
    fun `an is-prefixed property is its own getter, with no get prefix`() {
        val spellings = spellingsOf(KotlinProperty("isSettled", mutable = true))

        assertIn(spellings, "isSettled", "setSettled")
        assertOut(spellings, "getIsSettled", "setIsSettled")
    }

    /** `island` is not `is` + `Land`: the character after the prefix decides, as it does for the compiler. */
    @Test
    fun `a property that only starts with the letters is gets a get prefix`() {
        val spellings = spellingsOf(KotlinProperty("island", mutable = false))

        assertIn(spellings, "getIsland")
    }

    @Test
    fun `a Java getter is spelled from Kotlin as the property it stands for`() {
        assertIn(spellingsOf(JavaMethod("getBody")), "getBody", "body")
        assertIn(spellingsOf(JavaMethod("isSettled")), "isSettled")
        assertOut(spellingsOf(JavaMethod("isSettled")), "settled")
    }

    /** The compiler's smart decapitalisation: a leading acronym is lowered as a word. */
    @Test
    fun `a Java getter over an acronym is spelled the way Kotlin lowers it`() {
        assertIn(spellingsOf(JavaMethod("getURL")), "url")
        assertIn(spellingsOf(JavaMethod("getURLConnection")), "urlConnection")
        assertIn(spellingsOf(JavaMethod("getX")), "x")
    }

    @Test
    fun `a Java method that only starts with the letters get is not a getter`() {
        assertOut(spellingsOf(JavaMethod("getaway")), "away", "Away")
        assertOut(spellingsOf(JavaMethod("get")), "")
    }

    /** A facade is declared in no source text at all: its name comes from the file's. */
    @Test
    fun `top-level declarations are spelled through their file's facade`() {
        assertIn(spellingsOf(KotlinFacade("Ledger.kt", jvmName = null)), "LedgerKt")
        assertIn(spellingsOf(KotlinFacade("ledgerUtils.kt", jvmName = null)), "LedgerUtilsKt")
        assertIn(spellingsOf(KotlinFacade("ledger-utils.kt", jvmName = null)), "Ledger_utilsKt")
    }

    @Test
    fun `a file JvmName replaces the facade name rather than adding to it`() {
        val spellings = spellingsOf(KotlinFacade("Ledger.kt", jvmName = "Settlements"))

        assertIn(spellings, "Settlements")
        assertOut(spellings, "LedgerKt")
    }

    @Test
    fun `a JvmName is a spelling of the declaration it renames`() {
        assertIn(spellingsOf(JvmName("settleNow", of = "settle")), "settleNow")
    }

    @Test
    fun `a named companion is spelled by its name`() {
        assertIn(spellingsOf(Written("Registry")), "Registry")
    }

    /**
     * `Foo.INSTANCE` is in, and bare `INSTANCE` is not: the language fixes the word, and only the
     * declared object makes it a spelling of something the project owns.
     */
    @Test
    fun `an object is spelled through its instance, qualified by its name`() {
        val spellings = spellingsOf(KotlinObject("Settlement"))

        assertIn(spellings, "Settlement", "Settlement.INSTANCE")
        assertOut(spellings, "INSTANCE")
    }

    @Test
    fun `an internal function is spelled with its module's name mangled in`() {
        assertIn(spellingsOf(KotlinFunction("settle", internalIn = "billing")), "settle", "settle\$billing")
    }

    /**
     * The module name is not declaration text, so the closure is handed the IDE's name for the module
     * and reads it both ways: as written, and as the Gradle source-set module it names —
     * `acme.billing.main` compiles under `billing`, and `acme.billing.test` under `billing_test`.
     */
    @Test
    fun `an internal function in a Gradle source-set module is spelled with the Gradle module name`() {
        assertIn(
            spellingsOf(KotlinFunction("settle", internalIn = "acme.billing.main")),
            "settle\$billing",
            "settle\$acme_billing_main",
        )
        assertIn(spellingsOf(KotlinFunction("settle", internalIn = "acme.billing.test")), "settle\$billing_test")
    }

    @Test
    fun `an internal property is spelled through mangled accessors`() {
        assertIn(
            spellingsOf(KotlinProperty("body", mutable = true, internalIn = "billing")),
            "body",
            "getBody\$billing",
            "setBody\$billing",
        )
    }

    @Test
    fun `a function that is not internal is not mangled`() {
        assertOut(spellingsOf(KotlinFunction("settle", internalIn = null)), "settle\$billing")
    }

    /**
     * **Out by the rule, not by omission.** A value class's `f-impl` cannot be written in source, and
     * `f$default`, `access$f` and `DefaultImpls` are the compiler's and never a source spelling. A JVM
     * signature is not a source spelling.
     */
    @Test
    fun `value-class and bytecode-only manglings are out`() {
        val spellings = spellingsOf(
            KotlinFunction("settle", internalIn = "billing"),
            KotlinProperty("body", mutable = true, internalIn = "billing"),
            KotlinObject("Settlement"),
        )

        assertOut(spellings, "settle-impl", "settle\$default", "access\$settle", "DefaultImpls", "getBody\$default")
    }

    /**
     * `it`, `Companion` and `component1` are fixed by the language and declared in no source text —
     * so the closure derives them from nothing, however Kotlin-shaped its input.
     */
    @Test
    fun `names the language fixes never enter the closure`() {
        val spellings = spellingsOf(
            Written("Payment"),
            KotlinProperty("merchantRef", mutable = false),
            KotlinObject("Settlement"),
            KotlinFunction("settle"),
            KotlinFacade("Ledger.kt", jvmName = null),
        )

        assertOut(spellings, "it", "Companion", "component1", "copy", "INSTANCE")
    }

    /** `p.copy(merchantRef = x)`: the argument label is the declared property, and it is in. */
    @Test
    fun `a generated name fixed to a declared symbol is spelled by that symbol`() {
        assertIn(spellingsOf(KotlinProperty("merchantRef", mutable = false)), "merchantRef")
    }

    /**
     * **The annotation a human triages by**: a spelling the closure derived says what it was derived
     * from, and a spelling written in the source says nothing — even where the closure derives it too.
     */
    @Test
    fun `a derived spelling says what it was derived from, and a written one does not`() {
        val spellings = spellingsOf(KotlinProperty("body", mutable = false), Written("getBody"), Written("Payment"))
        val derived = spellingsOf(KotlinProperty("body", mutable = false))

        assertNull(spellings.derivationOf("getBody")) { "getBody is written in the source, so it is in by declaration." }
        assertNull(spellings.derivationOf("Payment"))
        assertNull(derived.derivationOf("body"))
        assertNotNull(derived.derivationOf("getBody"))
        assertTrue("body" in derived.derivationOf("getBody").orEmpty()) {
            "The derivation does not name the declaration it came from: ${derived.derivationOf("getBody")}"
        }
    }

    @Test
    fun `the closure counts what was written apart from what it derived`() {
        val spellings = spellingsOf(KotlinProperty("body", mutable = true), Written("Payment"), KotlinFacade("Ledger.kt", null))

        assertEquals(2, spellings.written) { "body and Payment are written." }
        assertEquals(3, spellings.derived) { "getBody, setBody and LedgerKt are derived." }
    }

    private fun spellingsOf(vararg declarations: Declaration) = SourceSpellings.of(declarations.toList())

    private fun assertIn(spellings: SourceSpellings, vararg expected: String) = expected.forEach { name ->
        assertTrue(name in spellings.names) { "`$name` is not in the closure: ${spellings.names.sorted()}" }
    }

    private fun assertOut(spellings: SourceSpellings, vararg refused: String) = refused.forEach { name ->
        assertTrue(name !in spellings.names) { "`$name` is in the closure and must not be: ${spellings.names.sorted()}" }
    }
}
