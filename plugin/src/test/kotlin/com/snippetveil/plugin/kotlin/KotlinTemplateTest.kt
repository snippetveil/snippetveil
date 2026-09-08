package com.snippetveil.plugin.kotlin

import com.snippetveil.core.LedgerSnapshot
import com.snippetveil.core.plus
import com.snippetveil.core.RecordedInvocation
import com.snippetveil.core.Sidecar
import com.snippetveil.core.SnippetPlan
import com.snippetveil.core.Unrestored
import com.snippetveil.core.deanonymize
import com.snippetveil.plugin.FENCE
import java.time.Instant

/**
 * **An interpolated template is a tree, not a token — so it decomposes, and nothing passes
 * through.**
 *
 * Two halves, and the first is the one a reader skips.
 *
 *  - **The selection snaps to the whole template.** A selection cutting into one snaps outward to
 *    the template and never to one of its entries, because an entry-level snap would extend the
 *    rewritten range into a template while leaving the rest of that template inside the copied range
 *    and **outside every rule** — precisely the fail-open outward snapping exists to close.
 *  - **The container is then decomposed structurally.** Each literal-entry run is its own `str`
 *    occurrence and each interpolated expression is ordinary code under the ordinary rules, so the
 *    text the snap pulled in **meets a rule rather than passing through**.
 *
 * That pairing is the whole safety argument, and it is why a Java literal may not be treated the
 * same way: a Java literal is one token, so rewriting a sub-range of it means splicing into text —
 * which is why the coverage rule exists at all, and why *mixed-always* (rewrite the covered ranges,
 * pass the gaps through) was a live leak. A template is a PSI tree, so the decomposition here is
 * structural rather than textual, and every chunk is replaced.
 *
 * **On the numbering in the worked examples.** The ticket writes them with a counter per kind —
 * `"str1$local1 str2$local2"` — and every placeholder in this engine is drawn from **one**
 * counter shared by every role, which the last test here asserts directly. So the examples are
 * rendered character for character in the spelling that one counter produces:
 * `"str1$local2 str3$local4"`. The shape is the ticket's; the numbers are the invariant's.
 *
 * **Nothing here is reachable from a user's IDE.** The optional descriptor declares no
 * `languageSupport`, so every `.kt` file still takes the gate's stated refusal, and these fixtures
 * construct the walk directly.
 */
internal class KotlinTemplateTest : KotlinSnippetTestCase() {

    /**
     * **A selection cutting into a template snaps to the whole template**, and the preview is told
     * that it did.
     *
     * Both halves are the test. The snap is the safety rule; the flag is what the preview discloses,
     * conditionally and only when snapping fired — a notice that showed on every invocation is one
     * nobody reads on the invocation where it matters. And the text the snap pulled in is asserted
     * to have **met a rule**: not one word of the template survives into the output, which is the
     * claim that separates outward snapping from a fail-open.
     */
    fun `test a selection cutting into a template snaps to the whole template`() {
        assertTheHarnessResolves()
        val plan = kotlinPlanFor(
            TEMPLATES_PATH,
            """
            package com.acme.ledger

            fun show(id: String): String {
                val refundId = id
                return "Refund ${DOLLAR}refundId reje<selection>cted</selection>"
            }
            """.trimIndent(),
        )

        assertEquals(
            "the selection snapped to an entry rather than to the template",
            """"Refund ${DOLLAR}refundId rejected"""",
            plan.text,
        )
        assertEquals("snapping fired and the plan does not say so", true, plan.selectionExpanded)
        assertEquals(
            "text the snap pulled in was copied through instead of meeting a rule",
            """"str1${DOLLAR}local2 str3"""",
            kotlinResultFor(plan).text,
        )
    }

    /**
     * **The first worked example**, and the case the whole decomposition exists for: two chunks and
     * two interpolations, each replaced, none passed through.
     *
     * The space in front of `str3` is the one character of the chunk the splice may not eat. `$name`
     * ends at the first character that could not continue the name, so a placeholder written flush
     * against it would fuse into `$local2str3` — Kotlin for one name that stands for
     * nothing, and two placeholders neither a reader nor a reversal could take apart. The boundary
     * the source already had is kept, which is also why the round trip below closes exactly.
     */
    fun `test a template decomposes into a placeholder per chunk and ordinary code per interpolation`() {
        assertRenders(
            """"Refund ${DOLLAR}refundId rejected by ${DOLLAR}merchant"""",
            """"str1${DOLLAR}local2 str3${DOLLAR}local4"""",
        )
    }

    /** **The second worked example** — a template that is one interpolation carries no chunk at all. */
    fun `test a template that is one interpolation is one placeholder`() {
        assertRenders(""""${DOLLAR}refundId"""", """"${DOLLAR}local1"""")
    }

    /**
     * **The third worked example** — an interpolated expression is **ordinary code under the
     * ordinary rules**, so a call inside one renames exactly as the same call outside one does.
     */
    fun `test an interpolated call is ordinary code`() {
        assertRenders(
            """"a ${DOLLAR}{ledger.label()}"""",
            """"str1${DOLLAR}{local2.method3()}"""",
        )
    }

    /**
     * **The fourth worked example** — a template without interpolation is an ordinary literal, and
     * it is not re-decided here: one run, one chunk, and the literal rule that already existed.
     */
    fun `test a template without interpolation is an ordinary literal`() {
        assertRenders(""""no refs here"""", """"str1"""")
    }

    /**
     * **The three invariants the container may be decomposed under**, asserted on the plan rather
     * than inferred from the output.
     *
     * The plan stays a **flat list of non-overlapping ranges** — a container is recorded by naming
     * its parts, never by nesting the plan — and **no occurrence spans the whole template**, which is
     * the difference between decomposing a container and rewriting it as one span of text.
     *
     * The third is the safety claim itself: **every character no occurrence covers is a character
     * the language wrote there** — a quote, an interpolation marker, the dot in a qualified call, or
     * the boundary an interpolated name ended at. Not one of them is a letter or a digit, so nothing
     * word-bearing passes through. The one shape with a stated exception is an escape sequence kept
     * as that boundary; it is pinned in its own test, with its own cost written down.
     */
    fun `test the entries partition the template and no occurrence spans it`() {
        assertTheHarnessResolves()
        val plan = templatePlanFor(""""Refund ${DOLLAR}refundId for ${DOLLAR}{ledger.label()}"""")

        val inside = plan.occurrences.sortedBy { it.start }
        assertTrue("the walk reported nothing, so this asserts nothing", inside.size >= 4)

        for (occurrence in inside) {
            assertFalse(
                "an occurrence spans the whole template, which is rewriting it as one span of text",
                occurrence.start == 0 && occurrence.end == plan.text.length,
            )
        }
        for ((earlier, later) in inside.zipWithNext()) {
            assertTrue(
                "two occurrences overlap, so the plan is no longer a flat list of ranges",
                earlier.end <= later.start,
            )
        }

        val covered = BooleanArray(plan.text.length)
        for (occurrence in inside) for (at in occurrence.start until occurrence.end) covered[at] = true
        val leaked = plan.text.indices.filter { !covered[it] && plan.text[it].isLetterOrDigit() }
        assertEquals(
            "a word-bearing character of the template is covered by no occurrence, so it passes through",
            "",
            leaked.map { plan.text[it] }.joinToString(""),
        )
    }

    /**
     * **The mixed-always regression, as its own case.**
     *
     * Transposing the coverage rule verbatim would send any template with a word-bearing chunk to
     * `"str1"` — so an idiomatic Kotlin log or error template would lose **every** reference in it,
     * while the Java sibling `"Refund {} rejected by {}", a, b` keeps them, and the two languages
     * would disagree about the same message for no reason a reader could see.
     *
     * So the assertion is both halves: the template did **not** collapse, and the reference inside it
     * renders the very placeholder its declaration does — one symbol, one placeholder, wherever it is
     * written.
     */
    fun `test a word-bearing chunk beside an interpolation does not collapse the template`() {
        assertTheHarnessResolves()
        val output = kotlinOutputFor(
            TEMPLATES_PATH,
            """
            package com.acme.ledger

            fun show(id: String): String {
                val refundId = id
                return "Refund ${DOLLAR}refundId"
            }
            """.trimIndent(),
        )

        assertEquals(
            "the template collapsed to one placeholder, which is the coverage rule transposed",
            """
            package com.pkg1.pkg2

            fun method3(param4: String): String {
                val local5 = param4
                return "str6${DOLLAR}local5"
            }
            """.trimIndent(),
            output,
        )
    }

    /**
     * **The quoting survives the decomposition, on both paths.**
     *
     * A raw template that decomposes stays raw; a raw template that collapses to a single
     * placeholder stays raw too — `"""…"""` written back as `"str1"` is malformed-looking, which is
     * a *plausible* artifact rather than an obvious one and therefore the class this product
     * refuses. An escaped literal stays escaped for the same reason, and by the same mechanism: no
     * delimiter is inside any chunk, so nothing downstream ever writes one.
     */
    fun `test a raw template stays raw and an escaped literal stays escaped`() {
        assertTheHarnessResolves()
        val output = kotlinOutputFor(
            TEMPLATES_PATH,
            """
            package com.acme.ledger

            fun show(id: String): String {
                val refundId = id
                val decomposed = ${FENCE}Refund ${DOLLAR}refundId rejected${FENCE}
                val collapsed = ${FENCE}no refs here${FENCE}
                val escaped = "tab\there ${DOLLAR}refundId"
                return decomposed + collapsed + escaped
            }
            """.trimIndent(),
        )

        assertEquals(
            "a literal's syntactic form did not survive the replacement",
            """
            package com.pkg1.pkg2

            fun method3(param4: String): String {
                val local5 = param4
                val local6 = ${FENCE}str7${DOLLAR}local5 str8${FENCE}
                val local9 = ${FENCE}str10${FENCE}
                val local11 = "str12${DOLLAR}local5"
                return local6 + local9 + local11
            }
            """.trimIndent(),
            output,
        )
    }

    /**
     * **An escape kept as the boundary, and the one character of a redaction that survives it.**
     *
     * A chunk that follows `$name` keeps the character the name ended at, because a
     * placeholder written flush against the name would fuse with it. Half an escape is not a
     * boundary — `\` alone in front of `str2` is `\s`, which Kotlin does not have — so the escape is
     * kept whole. The disclosed cost is one escaped character surviving a redaction, bounded at one
     * and carrying nothing in every escape the language spells except a unicode one; the alternative
     * is emitting source that does not lex.
     */
    fun `test an escape that opens a chunk is kept whole as the boundary`() {
        assertRenders(""""${DOLLAR}refundId\trejected"""", """"${DOLLAR}local1\tstr2"""")
    }

    /**
     * **A chunk that is only the boundary is not numbered**, because there is nothing left of it to
     * replace.
     *
     * `"$a $b"` is two interpolations with a single space between them: the space is
     * the character the first name ended at, so the chunk's content is empty and the engine preserves
     * an empty literal rather than burning a number on one. Nothing is lost — the character it is
     * made of is the one the language guarantees carries nothing.
     */
    fun `test a chunk that is only the boundary is not numbered`() {
        assertRenders(""""${DOLLAR}refundId ${DOLLAR}merchant"""", """"${DOLLAR}local1 ${DOLLAR}local2"""")
    }

    /**
     * **Templates nest, and the container a selection cut is the outermost one.**
     *
     * `"outer ${"inner $x".length}"` is a template inside an interpolation inside a template, and
     * snapping to the *innermost* one would leave the outer template cut open — the fail-open this
     * rule exists to close, reached through the shape that looks handled.
     *
     * The decomposition then goes the other way and stays flat: the inner template is decomposed on
     * its own, its chunks sit inside the outer template's interpolation, and no occurrence overlaps
     * another. A container is recorded by naming its parts, never by nesting the plan.
     */
    fun `test a selection inside a nested template snaps to the outermost one`() {
        assertTheHarnessResolves()
        val plan = kotlinPlanFor(
            TEMPLATES_PATH,
            """
            package com.acme.ledger

            fun show(id: String): String {
                val refundId = id
                return "outer ${DOLLAR}{"in<selection>ner</selection> ${DOLLAR}refundId".length}"
            }
            """.trimIndent(),
        )

        assertEquals(
            "the selection snapped to the inner template and left the outer one cut open",
            """"outer ${DOLLAR}{"inner ${DOLLAR}refundId".length}"""",
            plan.text,
        )
        assertEquals(
            "a nested template did not decompose on its own",
            """"str1${DOLLAR}{"str2${DOLLAR}local3".length}"""",
            kotlinResultFor(plan).text,
        )
    }

    /**
     * **Placeholders inside a template draw from the same counter as everything else in the
     * invocation** — asserted by the numbering across a snippet that mixes both.
     *
     * One counter shared by every role is what makes reverse mapping well-defined: a number that
     * meant two things at two moments is exactly what injectivity forbids. `str6` sitting between
     * `local5` and nothing else is the whole claim — the literal namespace is a spelling, not a
     * counter of its own.
     */
    fun `test placeholders inside a template draw from the same counter`() {
        assertTheHarnessResolves()
        val output = kotlinOutputFor(
            TEMPLATES_PATH,
            """
            package com.acme.ledger

            fun show(id: String): String {
                val refundId = id
                return "refund " + refundId + " done"
            }
            """.trimIndent(),
        )

        assertEquals(
            "a literal placeholder was numbered from a counter of its own",
            """
            package com.pkg1.pkg2

            fun method3(param4: String): String {
                val local5 = param4
                return "str6" + local5 + "str7"
            }
            """.trimIndent(),
            output,
        )
    }

    /**
     * **A reply restores every placeholder a template wrote against `$`, `{` or `"`** — the
     * three boundaries the word-boundary contract had never had to meet before this language
     * arrived.
     *
     * The reversal is exact, and that is the strongest thing said here: the boundary each chunk kept
     * is the boundary the source had, so what goes back is the file, character for character. A rule
     * that inserted a separator instead of keeping one would come back a space wider than the string
     * the user wrote — a silent change to their own data, inside a literal.
     *
     * Round-trip is not evidence of concealment and is not cited as any; what it asserts here is the
     * reversal contract over an output only this language can produce.
     */
    fun `test a reply restores the placeholders a template wrote against a dollar a brace and a quote`() {
        assertTheHarnessResolves()
        val source = """
            package com.acme.ledger

            fun show(id: String, ledger: Ledger): String {
                val refundId = id
                return "Refund ${DOLLAR}refundId for ${DOLLAR}{ledger.label()}" + ${FENCE}raw ${DOLLAR}refundId${FENCE}
            }
            """.trimIndent()
        writeTheLedger()

        val result = kotlinResultFor(TEMPLATES_PATH, source)
        val back = deanonymize(
            result.text,
            Sidecar.EMPTY.recording(RecordedInvocation(Instant.now(), result.mapping)),
            LedgerSnapshot.EMPTY + result.delta,
        )

        // What went out, stated in full rather than left to `it changed`: a reversal is only as
        // strong as the output it reverses, and an engine that left both templates alone would put
        // the file back perfectly while having concealed nothing at all.
        assertEquals(
            "the templates did not decompose, so the reversal below reverses nothing",
            """
            package com.pkg1.pkg2

            fun method3(param4: String, param5: Type6): String {
                val local7 = param4
                return "str8${DOLLAR}local7 str9${DOLLAR}{param5.method10()}" + ${FENCE}str11${DOLLAR}local7${FENCE}
            }
            """.trimIndent(),
            result.text,
        )
        assertEquals("the reversal did not put the file back", source, back.text)
        assertEquals("the reversal left a placeholder behind", emptyList<Unrestored>(), back.unrestored)
    }

    /**
     * Asserts that [template], selected on its own inside a function, renders as [expected].
     *
     * The selection is the template and nothing else, so the plan's text *is* the template and the
     * numbering starts at the template's first part — which is what lets a worked example be
     * asserted character for character rather than through the names around it.
     */
    private fun assertRenders(template: String, expected: String) {
        assertTheHarnessResolves()
        assertEquals("the template did not render as the worked example", expected, templateOutputFor(template))
    }

    /** What the engine makes of [template], selected on its own. See [assertRenders]. */
    private fun templateOutputFor(template: String): String = kotlinResultFor(templatePlanFor(template)).text

    /**
     * The plan for [template], selected on its own inside a function that declares everything the
     * worked examples name.
     *
     * The declarations sit outside the selection deliberately: they resolve, so each name is
     * classified on real evidence, and none of them is in the snippet, so none of them takes a
     * number before the template does.
     */
    private fun templatePlanFor(template: String): SnippetPlan {
        writeTheLedger()
        return kotlinPlanFor(
            TEMPLATES_PATH,
            """
            package com.acme.ledger

            fun show(id: String, aLedger: Ledger): String {
                val refundId = id
                val merchant = id
                val ledger = aLedger
                return <selection>$template</selection>
            }
            """.trimIndent(),
        )
    }

    /** The project-owned class the interpolated call in the worked examples reaches. */
    private fun writeTheLedger() {
        myFixture.addFileToProject(
            "com/acme/ledger/Ledger.kt",
            """
            package com.acme.ledger

            class Ledger {
                fun label(): String = "L"
            }
            """.trimIndent(),
        )
    }
}

/**
 * **An interpolation marker, spelled once**: a raw Kotlin string cannot hold one, and every fixture
 * here is a raw Kotlin string containing Kotlin.
 */
private const val DOLLAR = "\$"

private const val TEMPLATES_PATH = "com/acme/ledger/Templates.kt"
