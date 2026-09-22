package com.snippetveil.plugin

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.snippetveil.core.AnonymizationSettings
import com.snippetveil.core.LedgerSnapshot
import com.snippetveil.core.LiteralOccurrence
import com.snippetveil.core.SnippetPlan
import com.snippetveil.core.SourceLanguage
import com.snippetveil.core.anonymize

/**
 * **An injected identifier maps back to exactly its own range in the host file** — and a fragment
 * where that cannot be guaranteed falls back instead of guessing.
 *
 * Three claims, and the third is the one that makes the other two worth having:
 *
 *  - **The mapping is per shred, through the literal's own escaper.** A name inside one shred lands on
 *    exactly its host span, escapes and all; a name that is not wholly inside one shred's host text —
 *    straddling a concatenation, or sitting in the placeholder text a non-constant operand was
 *    replaced by — is unprojectable, and so is one the escaper refuses.
 *  - **Unprojectable falls the fragment back, and falling back builds nothing.** The plan is
 *    byte-identical to the one built with no container at all, which is the plan built today.
 *  - **The range identity is asserted over every injected occurrence**, and it catches the two
 *    measured shapes when they are produced by the one thing that produces them: the single-range host
 *    projection, which answers a straddling name with the union of its shreds.
 *
 * The union projection is called here, and only here. It is the API the shipped path must not call —
 * `ShippedCodeArchitectureTest` holds that over bytecode — and a test is where it can be shown being
 * refused.
 */
class InjectedRangeTest : JavaSnippetTestCase() {

    fun `test a name maps to exactly its own range in the host file`() {
        val file = queries()
        val fragment = injectedFragmentIn(file, CLEAN)
        val injected = fragment.identifier("customers")

        val host = fragment.hostRangeOf(injected)

        assertEquals("the host range is not the name's span", TextRange(file.text.indexOf("customers"), file.text.indexOf("customers") + 9), host)
        assertEquals("the host range does not map back to the name", injected, fragment.injectedRangeOf(host!!))
    }

    /**
     * **An escaped name maps to its whole escaped span, and the range identity holds over it — where a
     * text comparison would fire.** `cust\157mers` means `customers`, so the host span is three
     * characters wider than the name's spelling, and a check comparing the two texts would call a
     * correct splice a corruption. Ranges round-trip; text does not.
     */
    fun `test the identity is a range identity, because a text comparison fires on a correct splice`() {
        val file = queries()
        val fragment = injectedFragmentIn(file, ESCAPED)
        val injected = fragment.identifier("cust\\157mers")

        val host = fragment.hostRangeOf(injected)!!
        fragment.assertMapsBack(injected, host)

        val spelled = InjectedLanguageManager.getInstance(project).getUnescapedText(identifierAt(fragment, injected))
        assertEquals("the fixture's name is not the escaped one it is about", "customers", spelled)
        assertEquals("the host span is not the whole escaped spelling", "cust\\157mers", host.substring(file.text))
        assertFalse(
            "a text comparison between the host span and the name's spelling would have passed, so this " +
                "fixture does not show why the identity is stated over ranges",
            host.substring(file.text) == spelled,
        )
    }

    /**
     * **The escaper is consulted, and its refusal is unprojectable.** A range ending inside `\157`
     * is not a range of the text the fragment means, and the fragment falls back rather than cutting
     * an escape in half.
     */
    fun `test a range the escaper refuses is unprojectable, and its fragment falls back`() {
        val file = queries()
        val fragment = injectedFragmentIn(file, ESCAPED)
        val injected = fragment.identifier("cust\\157mers")
        val intoTheEscape = TextRange(injected.startOffset, injected.startOffset + "cust\\1".length)

        assertNull("a range ending inside an escape was projected", fragment.hostRangeOf(intoTheEscape))
        assertNull(
            "a fragment with a name the escaper refuses was decomposed",
            fragment.project(listOf(nameOf(injected, "customers", name = intoTheEscape))),
        )

        val host = fragment.hostRangeOf(injected)!!
        assertNull(
            "a host range starting inside an escape mapped back to something",
            fragment.injectedRangeOf(TextRange(host.startOffset + "cust\\1".length, host.endOffset)),
        )
    }

    fun `test a name straddling a concatenation boundary is unprojectable, and its fragment falls back`() {
        val fragment = injectedFragmentIn(queries(), STRADDLING)
        val injected = fragment.identifier("customers")

        assertNull("a name across two shreds was projected", fragment.hostRangeOf(injected))
        assertNull("a fragment with a straddling name was decomposed", fragment.project(listOf(nameOf(injected, "customers"))))
    }

    /**
     * **The first measured shape**, produced by the API that produces it: the single-range projection
     * answers a name across two shreds with their union, which takes in the `" + "` between them — a
     * placeholder written there would eat the concatenation and leave valid Java behind.
     */
    fun `test the identity catches the union range of a straddling name`() {
        val file = queries()
        val fragment = injectedFragmentIn(file, STRADDLING)
        val injected = fragment.identifier("customers")
        val union = fragment.unionProjection()(injected)!!

        assertEquals("the fixture no longer shows the union swallowing host text", "cust\" + \"omers", union.substring(file.text))
        assertFailsTheRun { fragment.assertMapsBack(injected, union) }
        assertFailsTheRun { fragment.project(listOf(nameOf(injected, "customers")), fragment.unionProjection()) }
    }

    /**
     * **The second measured shape**: a non-constant operand is substituted into the fragment as the
     * placeholder text `missingValue`, exactly where a table name goes, and nothing in the host is that
     * name. It is unprojectable; and whatever host range a wrong mapping makes up for it maps back to
     * something else.
     */
    fun `test a name in substituted placeholder text is unprojectable, and the identity catches a range made up for it`() {
        val fragment = injectedFragmentIn(queries(), SUBSTITUTED)
        val injected = fragment.identifier(JAVA_PLACEHOLDER)

        assertNull("a name with no host counterpart was projected", fragment.hostRangeOf(injected))
        assertNull("a fragment with a substituted name was decomposed", fragment.project(listOf(nameOf(injected, JAVA_PLACEHOLDER))))

        val madeUp = fragment.unionProjection()(injected)!!
        assertFailsTheRun { fragment.assertMapsBack(injected, madeUp) }
        assertFailsTheRun { fragment.project(listOf(nameOf(injected, JAVA_PLACEHOLDER)), fragment.unionProjection()) }
    }

    /**
     * **A fallen-back fragment produces a plan byte-identical to today's.** The same selection built
     * with the stand-in container and with none — none being what ships — is one plan, field for
     * field, for every way a fragment can fall back.
     */
    fun `test a fragment that falls back produces a plan byte-identical to today's`() {
        for (literal in listOf(STRADDLING_SELECTED, SUBSTITUTED_SELECTED)) {
            val request = requestFor(literal)
            assertEquals(
                "a fragment that fell back changed the plan: $literal",
                describe(JavaPlanBuilder.build(request)),
                describe(JavaPlanBuilder.build(request, IDENTIFIERS_AS_TABLES)),
            )
        }
    }

    /**
     * **The control for the test above**, without which it would pass on a seam that never
     * decomposes anything: a fragment that projects does decompose, its names land on their host
     * spans in the plan, and the literals it was read from report no occurrence of their own.
     */
    fun `test a fragment that projects decomposes onto its host spans`() {
        for ((literal, written) in listOf(CLEAN_SELECTED to "customers", ESCAPED_SELECTED to "cust\\157mers")) {
            val plan = JavaPlanBuilder.build(requestFor(literal), IDENTIFIERS_AS_TABLES)

            val names = plan.symbols().filter { it.language == SourceLanguage.SQL }
            assertEquals("the fragment in $literal did not decompose", listOf(written), names.map { it.text })
            assertEquals("the name does not sit on its host span", written, plan.text.substring(names.single().start, names.single().end))
            assertTrue("the decomposed literal was also reported whole", plan.occurrences.none { it is LiteralOccurrence })
            assertEquals("the splice is not inside the literal", "\"class table1 {}\"", outputOf(plan))
        }
    }

    private fun queries(): PsiFile = myFixture.configureByText("Queries.java", QUERIES)

    private fun requestFor(selected: String): SnippetRequest {
        val file = myFixture.configureByText("Queries.java", selected)
        return SnippetRequest(project, file, selectedRangesOf(myFixture.editor))
    }

    private fun outputOf(plan: SnippetPlan): String =
        anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY).text

    private fun identifierAt(fragment: InjectedFragment, range: TextRange) =
        fragment.file.findElementAt(range.startOffset)!!

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

private const val CLEAN = "\"class customers {}\""
private const val ESCAPED = "\"class cust\\157mers {}\""
private const val STRADDLING = "\"class cust\""
private const val SUBSTITUTED = "\"class \""

/** What IntelliLang writes into a Java fragment where a non-constant operand stood. */
private const val JAVA_PLACEHOLDER = "missingValue"

private val QUERIES = """
    class Queries {
        String tableVar;

        // language=JAVA
        String clean = $CLEAN;

        // language=JAVA
        String escaped = $ESCAPED;

        // language=JAVA
        String straddling = $STRADDLING + "omers {}";

        // language=JAVA
        String substituted = $SUBSTITUTED + tableVar + " {}";
    }
""".trimIndent()

/** One fragment's initializer selected, in a class that declares what the fragment needs. */
private fun selecting(initializer: String): String = """
    class Queries {
        String tableVar;

        // language=JAVA
        String query = <selection>$initializer</selection>;
    }
""".trimIndent()

private val CLEAN_SELECTED = selecting(CLEAN)
private val ESCAPED_SELECTED = selecting(ESCAPED)
private val STRADDLING_SELECTED = selecting("$STRADDLING + \"omers {}\"")
private val SUBSTITUTED_SELECTED = selecting("$SUBSTITUTED + tableVar + \" {}\"")
