package com.snippetveil.core

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **A kept comment is counted, and the unknowns it brings are split out rather than inflating the
 * number.**
 *
 * Both numbers are read off the tag each occurrence carries — [CodeContainer] — and off nothing
 * else, so every plan here states the tag and every assertion is about what the engine counts from
 * it. Whether a body parses is the builder's question, on the other side of the seam.
 */
class KeptCommentCountTest {

    /** **The count**: one comment whose body parsed was kept and renamed, so it says one. */
    @Test
    fun `a kept commented-out statement is counted`() {
        val plan = planOf(
            """
            void post() {
                // settle();
                audit();
            }
            """.trimIndent(),
            symbol("post", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT),
            symbol("settle", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT),
            symbol("audit", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT),
        ).keeping("// settle();")

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals(1, result.comments.anonymized)
        assertEquals(0, result.comments.stripped, "a kept comment is never counted as stripped")
    }

    /**
     * **An unresolved name that occurs only inside a kept comment is split out of `unknown`.** The
     * total is unmoved — the name did not resolve, and the number is not wrong — and the part of it
     * that came from comments is said beside it.
     */
    @Test
    fun `an unknown only in a kept comment is counted in the total and in the comment part`() {
        val plan = planOf(
            """
            void post() {
                // legacyAudit();
                missingHelper();
            }
            """.trimIndent(),
            symbol("post", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT),
            symbol("legacyAudit", SymbolRole.METHOD, SymbolOrigin.UNRESOLVED, key = "unresolved:legacyAudit"),
            symbol("missingHelper", SymbolRole.METHOD, SymbolOrigin.UNRESOLVED, key = "unresolved:missingHelper"),
        ).keeping("// legacyAudit();")

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals(2, result.counts.unknown)
        assertEquals(1, result.counts.unknownFromComments)
    }

    /**
     * **Absent, not zero, starts here**: a snippet with no kept comment has nothing to count, and a
     * stripped comment is not a kept one. The rendering drops a zero; the engine has to report one.
     */
    @Test
    fun `a snippet with no kept comment counts none and splits nothing`() {
        val plan = planOf(
            """
            void post() {
                // reconcile against the merchant ledger
                missingHelper();
            }
            """.trimIndent(),
            symbol("post", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT),
            symbol("missingHelper", SymbolRole.METHOD, SymbolOrigin.UNRESOLVED, key = "unresolved:missingHelper"),
        ).withComment("// reconcile against the merchant ledger", CommentVerdict.PROSE)

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals(0, result.comments.anonymized)
        assertEquals(1, result.counts.unknown)
        assertEquals(0, result.counts.unknownFromComments)
    }

    /**
     * **A name unresolved in live code and in a kept comment counts once, in the live part.** The
     * comment part is the names that occur *only* in kept comments, which is what keeps it a part.
     */
    @Test
    fun `an unknown in both live code and a kept comment counts once and not from comments`() {
        val plan = planOf(
            """
            void post() {
                // missingHelper();
                missingHelper();
            }
            """.trimIndent(),
            symbol("post", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT),
            symbol("missingHelper", SymbolRole.METHOD, SymbolOrigin.UNRESOLVED, key = "unresolved:missingHelper"),
        ).keeping("// missingHelper();")

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals(1, result.counts.unknown)
        assertEquals(0, result.counts.unknownFromComments)
        assertEquals(1, result.comments.anonymized)
    }

    /**
     * **Both numbers come from the tag, and from nothing else.** The same text and the same evidence,
     * with the tag dropped, is a snippet with no kept comment in it: nothing about the text of a line
     * starting `//` is read by either count.
     */
    @Test
    fun `dropping the container tag drops both numbers`() {
        val untagged = planOf(
            """
            void post() {
                // legacyAudit();
                audit();
            }
            """.trimIndent(),
            symbol("post", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT),
            symbol("legacyAudit", SymbolRole.METHOD, SymbolOrigin.UNRESOLVED, key = "unresolved:legacyAudit"),
            symbol("audit", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT),
        )
        val tagged = untagged.keeping("// legacyAudit();")

        val kept = anonymize(tagged, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)
        val dropped = anonymize(untagged, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals(1, kept.comments.anonymized)
        assertEquals(1, kept.counts.unknownFromComments)
        assertEquals(0, dropped.comments.anonymized, "a kept comment was counted with no tag to count it from")
        assertEquals(0, dropped.counts.unknownFromComments, "the split was made with no tag to make it from")
        assertEquals(kept.text, dropped.text, "the tag changed the output, and no rewriting rule reads it")
    }

    /**
     * **A kept line with nothing in it to anonymize is not counted — even when prose nested in it
     * was stripped.** The nested comment carries the kept line's tag, because it was read from inside
     * it; it went, so it is not something that was anonymized and kept.
     */
    @Test
    fun `a kept line whose only part is stripped prose is not counted`() {
        val plan = planOf(
            """
            void post() {
                // return; // todo
            }
            """.trimIndent(),
            symbol("post", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT),
        ).withComment("// todo", CommentVerdict.PROSE).keeping("// return; // todo")

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals(0, result.comments.anonymized)
        assertEquals(1, result.comments.stripped)
    }

    /** Two kept lines are two kept comments, and each is counted once however many names it holds. */
    @Test
    fun `each kept comment is counted once`() {
        val plan = planOf(
            """
            void post() {
                // settle(amount);
                // audit(amount);
            }
            """.trimIndent(),
            symbol("post", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT),
            symbol("settle", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT),
            symbol("audit", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT),
            symbol("amount", SymbolRole.LOCAL, SymbolOrigin.IN_CONTENT),
        ).keeping("// settle(amount);").keeping("// audit(amount);")

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals(2, result.comments.anonymized)
    }

    /**
     * **A count is not a notice.** The stripped-comments notice fires on stripped comments only,
     * never counts a kept one, and says nothing about kept comments — a kept comment is loudly
     * visible in the output, and folding it into the notice is the drift the two-notice limit exists
     * to prevent.
     */
    @Test
    fun `the stripped-comments notice counts stripped comments only and says nothing of kept ones`() {
        val both = planOf(
            """
            void post() {
                // settle();
                // TODO: fix this
            }
            """.trimIndent(),
            symbol("post", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT),
            symbol("settle", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT),
        ).keeping("// settle();").withComment("// TODO: fix this", CommentVerdict.PROSE)

        assertEquals(
            listOf("1 comment stripped"),
            anonymize(both, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY).fidelityNotices(),
        )

        val keptOnly = planOf(
            """
            void post() {
                // settle();
            }
            """.trimIndent(),
            symbol("post", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT),
            symbol("settle", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT),
        ).keeping("// settle();")

        assertEquals(
            emptyList<String>(),
            anonymize(keptOnly, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY).fidelityNotices(),
            "a kept comment raised a notice",
        )
    }

    /**
     * **The parenthetical never exceeds the total**, over generated snippets — and both numbers agree
     * with what the generator knows independently of the engine: which lines it wrote as kept
     * comments and which names it wrote as unresolved.
     */
    @Property(tries = 300)
    fun `the comment part of unknown is the names only kept comments hold, and never exceeds the total`(
        @ForAll("snippets") lines: List<Line>,
    ) {
        val text = lines.joinToString("\n") { line -> if (line.kept) "// ${NAMES[line.name]}();" else "${NAMES[line.name]}();" }
        val symbols = lines.map { it.name }.distinct().map { index ->
            val origin = if (index in UNRESOLVED) SymbolOrigin.UNRESOLVED else SymbolOrigin.IN_CONTENT
            symbol(NAMES[index], SymbolRole.METHOD, origin, key = "method:${NAMES[index]}")
        }
        val plan = lines.filter { it.kept }.fold(planOf(text, *symbols.toTypedArray())) { plan, line ->
            plan.keeping("// ${NAMES[line.name]}();")
        }

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        val unresolved = lines.map { it.name }.filter { it in UNRESOLVED }.toSet()
        val onlyInComments = unresolved.filter { name -> lines.filter { it.name == name }.all { it.kept } }
        assertTrue(
            result.counts.unknownFromComments <= result.counts.unknown,
            "${result.counts.unknownFromComments} from comments out of ${result.counts.unknown} unknown\n$text",
        )
        assertEquals(unresolved.size, result.counts.unknown, text)
        assertEquals(onlyInComments.size, result.counts.unknownFromComments, text)
        assertEquals(lines.count { it.kept }, result.comments.anonymized, text)
    }

    /**
     * Snippets of one call per line, each line live or a kept comment, over a universe of names small
     * enough that one name in both kinds of line is the common case rather than a lucky draw.
     */
    @Provide
    fun snippets(): Arbitrary<List<Line>> =
        Combinators.combine(Arbitraries.integers().between(0, NAMES.size - 1), Arbitraries.of(true, false))
            .`as` { name, kept -> Line(name, kept) }
            .list()
            .ofMinSize(1)
            .ofMaxSize(8)

    /** One generated line: which name it calls, and whether it is a kept comment. */
    class Line(val name: Int, val kept: Boolean) {
        override fun toString() = if (kept) "// ${NAMES[name]}();" else "${NAMES[name]}();"
    }

    private companion object {

        /** No name is a part of another, so a search for one never lands inside a second. */
        val NAMES = listOf("alpha", "bravo", "charlie", "delta", "echo", "foxtrot")

        /** The names the generated snippets hold unresolved; the rest are the project's own. */
        val UNRESOLVED = setOf(0, 1, 2)
    }
}
