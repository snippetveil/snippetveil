package com.snippetveil.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * **The first field this product removes** — the drop rule, asserted over the bind list that made it
 * necessary.
 *
 * > **A number that varies with a value SnippetVeil masks is dropped. A number describing the slot
 * > the value sits in is preserved.**
 *
 * Four claims, and each is a different half of the rule:
 *
 *  - the lengths are **absent** from the output, not renamed and not blanked;
 *  - two binds of equal length **share no token**, which is why the rule drops rather than masks;
 *  - the drop **advances no counter, writes no ledger row and fires no notice**, which is what makes
 *    it a disposition rather than a replacement;
 *  - what describes the **slot** — the type, its code, its character set — comes out whole, because
 *    that is what explains an implicit conversion and a declared width explains no plan.
 */
class OracleBindTest {

    /**
     * **The length and maximum-length attributes are absent from the output.**
     *
     * Absent rather than emptied: a `len=""` still says there was a length, and the field's whole
     * extent — its name, its `=`, its quotes and the space in front of them — is what goes. The
     * surrounding attributes are asserted to come back spaced as the writer spaced them, which is the
     * other half of *the output is a report with an attribute missing* rather than a report with a
     * hole in it.
     */
    @Test
    fun `the length and maximum-length attributes are absent from the output`() {
        val text = anonymizedText(ORACLE_MONITOR_XML)

        assertFalse("len=" in text, "a length attribute survived the drop:\n$text")
        assertFalse("maxlen" in text, "a maximum length survived the drop:\n$text")
        assertTrue(
            Regex("""<bind name="str\d+" pos="1" dty="1" dtystr="VARCHAR2" csid="873">""").containsMatchIn(text),
            "the attributes either side of the drop did not close up as the writer spaced them:\n$text",
        )
    }

    /**
     * **Two binds of equal byte length share no token** — the reason the rule drops rather than
     * masks, asserted directly.
     *
     * Equal spellings share a placeholder, by design and everywhere: it is what keeps within-plan
     * correlation alive through a mask. Applied to a length it is a defeat of the redaction rule —
     * two binds of equal length would come back carrying **one** placeholder, and the receiver would
     * read length equality off the very mechanism meant to close it. A drop never reaches the
     * allocator, so there is nothing for the two to share.
     *
     * The fixture's first two binds are both seven bytes long, so a mask would have made this fail.
     */
    @Test
    fun `two binds of equal byte length share no token`() {
        val result = anonymize(planIn(ORACLE_MONITOR_XML), AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals(
            emptySet<String>(),
            result.placeholdersFor("7"),
            "a byte length reached the output under a placeholder:\n${result.text}",
        )
        assertTrue(
            result.names.none { it.original == "7" || it.original == "32" || it.original == "22" },
            "a dropped length was recorded as a name: ${result.names.map { it.original }}",
        )

        // And the binds are still as distinguishable as they were: three binds, three placeholders,
        // which is what the drop is not allowed to cost.
        assertEquals(
            3,
            Regex("""<bind name="(str\d+)"""").findAll(result.text).map { it.groupValues[1] }.toSet().size,
            "the binds stopped being distinguishable:\n${result.text}",
        )
    }

    /**
     * **A drop advances no counter and writes no ledger row.**
     *
     * Asserted as the comparison it is: the same report with the two fields, and with them never
     * written at all, produce **the same counter, the same rows and the same counts**. A dropped token
     * stands for nothing in the output, so a number handed out for one would be a number burnt to
     * protect a reader from a collision they cannot have.
     */
    @Test
    fun `a drop advances no counter and writes no ledger row`() {
        val dropped = anonymize(planIn(ORACLE_MONITOR_XML), AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)
        val absent = anonymize(
            planIn(ORACLE_MONITOR_XML_WITHOUT_LENGTHS),
            AnonymizationSettings.DEFAULTS,
            LedgerSnapshot.EMPTY,
        )

        assertEquals(absent.delta.nextNumber, dropped.delta.nextNumber, "the drop drew a number from the counter")
        assertEquals(
            absent.delta.placeholders.keys,
            dropped.delta.placeholders.keys,
            "the drop wrote a ledger row",
        )
        assertEquals(absent.mapping, dropped.mapping, "the drop put a row in the invocation's table")
    }

    /**
     * **No notice and no count fires for a dropped field.**
     *
     * A drop is neither a name that was replaced nor a word that survived, so it is in neither count —
     * counting it either way would be a false statement about what is on the clipboard. And nothing is
     * said about it: **no notice and no count**, exactly as a dropped prefix elsewhere has never been
     * counted. The cost is stated rather than hidden — a reader diffing against a real report sees a
     * missing attribute — and it is accepted, because the output is self-announcing and a byte count
     * is not what anyone reads a plan for.
     */
    @Test
    fun `no notice and no count fires for a dropped field`() {
        val dropped = anonymize(planIn(ORACLE_MONITOR_XML), AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)
        val absent = anonymize(
            planIn(ORACLE_MONITOR_XML_WITHOUT_LENGTHS),
            AnonymizationSettings.DEFAULTS,
            LedgerSnapshot.EMPTY,
        )

        assertEquals(emptyList<String>(), dropped.fidelityNotices(), "the drop announced itself")
        assertEquals(absent.counts.replaced, dropped.counts.replaced, "the drop was counted as replaced")
        assertEquals(absent.counts.preserved, dropped.counts.preserved, "the drop was counted as preserved")
        assertEquals(absent.counts.unknown, dropped.counts.unknown, "the drop was counted as unknown")
        assertEquals(
            emptyList<String>(),
            dropped.names.filter { it.original.toIntOrNull() != null }.map { it.original },
            "a dropped number reached the preview:\n${dropped.text}",
        )
    }

    /**
     * **The type name survives and its declared size does not** — the decomposition, and the rows
     * beside it that say what a plan reader actually needs.
     *
     * The type is what explains an implicit conversion, which is a top-tier reason to read a plan at
     * all. The declared width explains no plan and varies with the slot the value sits in, so it goes
     * under the same rule the lengths go under. The type **code** and the character-set id are
     * preserved whole, as the engine facts they are.
     */
    @Test
    fun `the type name keeps its name, loses its size, and its code and character set survive`() {
        val text = anonymizedText(ORACLE_MONITOR_XML)

        assertTrue("""dtystr="VARCHAR2"""" in text, "the type name did not survive its size being dropped:\n$text")
        assertFalse("VARCHAR2(32)" in text, "a declared width survived the drop:\n$text")
        assertTrue("""dtystr="DATE"""" in text, "a type with no size was rewritten:\n$text")
        assertTrue("""dty="1"""" in text, "the type code was not preserved:\n$text")
        assertTrue("""csid="873"""" in text, "the character-set id was not preserved:\n$text")

        assertTrue("VARCHAR2" in preservedIn(ORACLE_MONITOR_XML), "the type name was not reported as preserved")
    }

    /**
     * **A bind is typed by its type attribute, not by lexing its value.**
     *
     * A numeric bind is a number where it lexes as one; a string bind and a **date** bind are each one
     * redacted literal, whatever they happen to look like. Typing by the value would read `10-SEP-25`
     * as something and a date that arrived as digits as a number, which is the engine's own metadata
     * being second-guessed by a scanner.
     */
    @Test
    fun `a bind is typed by its type attribute, and a date bind is one redacted literal`() {
        val text = anonymizedText(ORACLE_MONITOR_XML)

        assertTrue(""">25</bind>""" in text, "a numeric bind's value was not preserved as the number it is:\n$text")
        assertFalse("10-SEP-25" in text, "a date bind's value was left on the clipboard:\n$text")
        assertFalse("Portland" in text, "a string bind's value was left on the clipboard:\n$text")
        assertEquals(
            2,
            Regex(""">str\d+</bind>""").findAll(text).count(),
            "the string and date binds did not each become one redacted literal:\n$text",
        )
    }

    /**
     * **A type outside the numeric codes is one redacted literal**, which is the fail-closed half of
     * the same rule: a code no row holds is a type this product has not seen, and a value of an
     * unseen type is not a number.
     */
    @Test
    fun `a bind whose type code no row holds is one redacted literal`() {
        val text = anonymizedText(ORACLE_MONITOR_XML_RAW_BIND)

        assertFalse("0FA1" in text, "a raw bind's value was left on the clipboard:\n$text")
        assertTrue("""dtystr="RAW"""" in text, "the unseen type's name did not survive:\n$text")
    }

    /** **Bind names are masked**, because a bind's name is routinely the column it filters. */
    @Test
    fun `bind names are masked`() {
        val text = anonymizedText(ORACLE_MONITOR_XML)

        for (name in listOf(":city", ":opened", ":limit")) {
            assertFalse(name in text, "the bind name `$name` was left on the clipboard:\n$text")
        }
        assertEquals(
            3,
            Regex("""<bind name="str\d+"""").findAll(text).count(),
            "the bind names did not come back as redacted literals:\n$text",
        )
    }

    /**
     * **A bound value carrying the sequence that would close its section early round-trips.**
     *
     * The engine splits such a value across two character-data sections rather than emitting it
     * unescaped, and the reader takes the two as **one value with the split inside it**. So the mask
     * replaces the whole value and leaves one well-formed section behind, and the reversal hands the
     * value back exactly as it arrived — the split included, because the split is how the document
     * spelled it.
     */
    @Test
    fun `a bound value carrying a character-data terminator round-trips`() {
        val result = anonymize(
            planIn(ORACLE_MONITOR_XML_SPLIT_VALUE),
            AnonymizationSettings(keepComments = true),
            LedgerSnapshot.EMPTY,
        )
        val back = deanonymize(
            result.text,
            Sidecar.EMPTY.recording(RecordedInvocation(Instant.now(), result.mapping)),
            LedgerSnapshot.EMPTY + result.delta,
        )

        assertNotEquals(
            ORACLE_MONITOR_XML_SPLIT_VALUE,
            result.text,
            "the report came back unchanged, so this asserts nothing",
        )
        assertFalse("a]]" in result.text, "half the split value was left on the clipboard:\n${result.text}")
        assertTrue(
            Regex("""<!\[CDATA\[str\d+]]>""").containsMatchIn(result.text),
            "the split value did not come back as one well-formed section:\n${result.text}",
        )
        assertTrue(
            """<![CDATA[a]]]]><![CDATA[>b]]>""" in back.text,
            "the split value did not come back from the reversal:\n${back.text}",
        )
        assertEquals(emptyList<Unrestored>(), back.unrestored, "the reversal left a placeholder behind")
    }
}
