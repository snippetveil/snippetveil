package com.snippetveil.plugin.kotlin

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.snippetveil.plugin.InjectedFragment
import com.snippetveil.plugin.identifier
import com.snippetveil.plugin.injectedFragmentIn
import com.snippetveil.plugin.nameOf
import com.snippetveil.plugin.project
import com.snippetveil.plugin.unionProjection

/**
 * **The same mapping over a Kotlin host**, where the two measured shapes arrive spelled differently
 * and the escaper refuses where Java's does not.
 *
 *  - **Kotlin's escaper refuses outright** on a range cutting into an escape, at either end. That is
 *    the unprojectable case arriving as a refusal rather than as a wrong number, and it is taken as
 *    one.
 *  - **A template substitutes an interpolation as placeholder text**, and not always the same text:
 *    `$tableVar` becomes the variable's own name, `${tableVar}` becomes `missingValue`. Either way it
 *    sits where a table name goes and has no host counterpart.
 *  - **A concatenation straddles** exactly as a Java one does, and its union range is caught the same
 *    way.
 *
 * The Java half of the claim — and the plan the builder produces when a fragment falls back — is
 * `InjectedRangeTest`. This half asserts the mapping and the identity over Kotlin's shred layout and
 * Kotlin's escaper, which are the parts of the claim that differ by host language.
 */
internal class KotlinInjectedRangeTest : KotlinSnippetTestCase() {

    /**
     * **The refusal is the escaper's own, and it is propagated.** `customers` maps whole — the
     * host span is the escaped spelling — while a range ending inside `o` is refused by Kotlin's
     * escaper itself and is therefore unprojectable, in both directions.
     */
    fun `test Kotlin's escaper refuses a range cutting into an escape, and that makes it unprojectable`() {
        val file = templates()
        val fragment = injectedFragmentIn(file, ESCAPED)
        val injected = fragment.identifier(ESCAPED_NAME)

        val host = fragment.hostRangeOf(injected)!!
        assertEquals("the host span is not the whole escaped spelling", ESCAPED_NAME, host.substring(file.text))
        fragment.assertMapsBack(injected, host)

        val cut = "cust\\u0".length
        val literal = fragment.hosts!!.single()
        val local = TextRange(host.startOffset, host.startOffset + cut).shiftLeft(literal.textRange.startOffset)
        assertFalse(
            "Kotlin's escaper no longer refuses a range ending inside an escape, so this test no longer shows the refusal being propagated",
            literal.createLiteralTextEscaper().decode(local, StringBuilder()),
        )

        val intoTheEscape = TextRange(injected.startOffset, injected.startOffset + cut)
        assertNull("a range the escaper refuses was projected", fragment.hostRangeOf(intoTheEscape))
        assertNull(
            "a fragment with a name the escaper refuses was decomposed",
            fragment.project(listOf(nameOf(injected, "customers", name = intoTheEscape))),
        )
        assertNull(
            "a host range the escaper refuses mapped back to something",
            fragment.injectedRangeOf(TextRange(host.startOffset, host.startOffset + cut)),
        )
    }

    /** `$tableVar` is substituted as `tableVar` — a real-looking name with nothing in the host behind it. */
    fun `test an interpolation substituted as its own name is unprojectable, and the identity catches a range made up for it`() {
        assertSubstitutedNameIsCaught(BARE_TEMPLATE, "tableVar")
    }

    /** `${tableVar}x` is substituted as `missingValue`, which runs into the host text after it. */
    fun `test an interpolation substituted as missingValue is unprojectable, and the identity catches a range made up for it`() {
        assertSubstitutedNameIsCaught(BRACED_TEMPLATE, "missingValuex")
    }

    fun `test a name straddling a Kotlin concatenation is unprojectable, and the identity catches its union`() {
        val file = templates()
        val fragment = injectedFragmentIn(file, STRADDLING)
        val injected = fragment.identifier("customers")

        assertNull("a name across two shreds was projected", fragment.hostRangeOf(injected))
        assertNull("a fragment with a straddling name was decomposed", fragment.project(listOf(nameOf(injected, "customers"))))

        val union = fragment.unionProjection()(injected)!!
        assertEquals("the fixture no longer shows the union swallowing host text", "cust\" + \"omers", union.substring(file.text))
        assertFailsTheRun { fragment.assertMapsBack(injected, union) }
    }

    private fun assertSubstitutedNameIsCaught(literal: String, substituted: String) {
        val fragment: InjectedFragment = injectedFragmentIn(templates(), literal)
        val injected = fragment.identifier(substituted)

        assertNull("a name with no host counterpart was projected", fragment.hostRangeOf(injected))
        assertNull("a fragment with a substituted name was decomposed", fragment.project(listOf(nameOf(injected, substituted))))

        val madeUp = fragment.unionProjection()(injected)!!
        assertFailsTheRun { fragment.assertMapsBack(injected, madeUp) }
        assertFailsTheRun { fragment.project(listOf(nameOf(injected, substituted)), fragment.unionProjection()) }
    }

    private fun templates(): PsiFile = myFixture.configureByText("Templates.kt", TEMPLATES)

    private fun assertFailsTheRun(projection: () -> Unit) {
        try {
            projection()
        } catch (mismatch: IllegalStateException) {
            assertTrue("the run failed, but not on the range identity: ${mismatch.message}", mismatch.message!!.contains("maps back to"))
            return
        }
        fail("a host range that does not map back to its name passed the range identity")
    }
}

/** A `$` for the fixtures, which a Kotlin string cannot otherwise write before a name. */
private const val D = "$"

/** `customers`, with the backslash written into the fixture rather than read by this file's compiler. */
private const val ESCAPED_NAME = "cust\\u006fmers"

private const val ESCAPED = "\"class $ESCAPED_NAME {}\""
private const val BARE_TEMPLATE = "\"class ${D}tableVar {}\""
private const val BRACED_TEMPLATE = "\"class ${D}{tableVar}x {}\""
private const val STRADDLING = "\"class cust\""

private const val TEMPLATES =
    "fun queries(tableVar: String) {\n" +
        "    // language=JAVA\n" +
        "    val escaped = $ESCAPED\n" +
        "    // language=JAVA\n" +
        "    val bare = $BARE_TEMPLATE\n" +
        "    // language=JAVA\n" +
        "    val braced = $BRACED_TEMPLATE\n" +
        "    // language=JAVA\n" +
        "    val straddling = $STRADDLING + \"omers {}\"\n" +
        "}\n"
