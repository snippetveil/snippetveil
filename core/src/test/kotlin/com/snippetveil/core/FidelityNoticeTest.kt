package com.snippetveil.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **The two disclosure notices — and why the list is closed at exactly two.**
 *
 * Signal-loss is a third failure mode with no artifact at all. The output is clean, compiles and
 * reads as ordinary code; the AI answers accurately about a snippet the defect has been lifted out
 * of, and the user gets a confident, well-formed, **wrong** answer with nothing in the output
 * hinting at it. That is the quietest failure the design has.
 *
 * **The response is disclosure, not preservation.** The tool says what it removed at the point of
 * use, and the human — who can still see the original — judges whether it mattered. Preservation
 * was rejected because every mechanism for it reopens a fail-open path, and refusal because it
 * would refuse exactly the snippets users most want help with.
 *
 * The trigger test is one line: **a fidelity notice exists iff the loss is invisible in the
 * anonymized output.** Literals, `Unknown`s and preserved symbols are all loudly visible in the
 * preview and need none. Only two losses are invisible, so exactly two ship — and the list is
 * closed rather than growing with every future transform.
 */
class FidelityNoticeTest {

    /**
     * **Injectivity is load-bearing, so name coincidence is destroyed by construction.** Distinct
     * symbols must get distinct placeholders, and no preservation rule saves the coincidence
     * without unpicking the never-recycle invariant — so the coincidence is disclosed instead.
     *
     * A field and the fluent accessor beside it are one name in source and two afterwards, and the
     * output gives no sign that they ever agreed.
     */
    @Test
    fun `a name two symbols shared is disclosed by placeholder`() {
        val result = anonymize(fieldAndMethodAlike(), AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals(listOf("field2 and method3 were the same name"), result.fidelityNotices())
    }

    /**
     * **A param shadowing a field** — the spike's flagship case, and the one where the destroyed
     * coincidence changes the answer, because *"the parameter shadows the field"* is the whole
     * finding.
     */
    @Test
    fun `a param shadowing a field triggers the notice`() {
        val plan = planPlacing(
            """
            class Ledger {
                int balance;

                void settle(int balance) {
                    this.balance = balance;
                }
            }
            """.trimIndent(),
            at(0, symbol("Ledger", SymbolRole.TYPE, SymbolOrigin.IN_CONTENT)),
            at(0, symbol("balance", SymbolRole.FIELD, SymbolOrigin.IN_CONTENT, key = "field:Ledger.balance")),
            at(0, symbol("settle", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT)),
            at(1, symbol("balance", SymbolRole.PARAMETER, SymbolOrigin.IN_CONTENT, key = "param:balance")),
            at(2, symbol("balance", SymbolRole.FIELD, SymbolOrigin.IN_CONTENT, key = "field:Ledger.balance")),
            at(3, symbol("balance", SymbolRole.PARAMETER, SymbolOrigin.IN_CONTENT, key = "param:balance")),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals(listOf("field2 and param4 were the same name"), result.fidelityNotices())
    }

    /**
     * **Field hiding** — a subclass field spelled like its superclass's — is the same loss reached
     * by a different route, and the detection does not have to know that: two symbols, one name.
     */
    @Test
    fun `field hiding triggers the notice`() {
        val plan = planPlacing(
            """
            class Book {
                int balance;
            }

            class Ledger extends Book {
                int balance;
            }
            """.trimIndent(),
            at(0, symbol("Book", SymbolRole.TYPE, SymbolOrigin.IN_CONTENT)),
            at(0, symbol("balance", SymbolRole.FIELD, SymbolOrigin.IN_CONTENT, key = "field:Book.balance")),
            at(0, symbol("Ledger", SymbolRole.TYPE, SymbolOrigin.IN_CONTENT)),
            at(1, symbol("Book", SymbolRole.TYPE, SymbolOrigin.IN_CONTENT)),
            at(1, symbol("balance", SymbolRole.FIELD, SymbolOrigin.IN_CONTENT, key = "field:Ledger.balance")),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals(listOf("field2 and field4 were the same name"), result.fidelityNotices())
    }

    /**
     * **Same name, different scope** — two locals in sibling blocks. Nothing is shadowed and nothing
     * is hidden; the names simply agreed, and after the rename they do not.
     */
    @Test
    fun `two locals in sibling scopes trigger the notice`() {
        val plan = planPlacing(
            """
            void settle() {
                { int total = 1; }
                { int total = 2; }
            }
            """.trimIndent(),
            at(0, symbol("settle", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT)),
            at(0, symbol("total", SymbolRole.LOCAL, SymbolOrigin.IN_CONTENT, key = "local:total@1")),
            at(1, symbol("total", SymbolRole.LOCAL, SymbolOrigin.IN_CONTENT, key = "local:total@2")),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals(listOf("local2 and local3 were the same name"), result.fidelityNotices())
    }

    /**
     * **Detection is exact, and it is a group-by on the source name — never edit distance.**
     *
     * Near-miss detection for typo'd duplicates was rejected: it is inference, it fires on
     * legitimately similar names, and noisy notices train the user to ignore all of them.
     * Typo'd-duplicate bugs stay a stated residual gap, and this is the assertion that keeps the
     * rule from drifting into one — three names one edit apart, and nothing said about any of them.
     */
    @Test
    fun `names that merely resemble each other produce no notice`() {
        val plan = planOf(
            "void post(int settlement, int settelment, int settlment) {}",
            symbol("post", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT),
            symbol("settlement", SymbolRole.PARAMETER, SymbolOrigin.IN_CONTENT, key = "param:settlement"),
            symbol("settelment", SymbolRole.PARAMETER, SymbolOrigin.IN_CONTENT, key = "param:settelment"),
            symbol("settlment", SymbolRole.PARAMETER, SymbolOrigin.IN_CONTENT, key = "param:settlment"),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals(emptyList<String>(), result.fidelityNotices())
    }

    /**
     * **Real names never leave the machine.** The notice exists to say that a coincidence was
     * destroyed, and it can say so entirely in placeholders — which is why disclosure costs no
     * privacy here and why it may ride the balloon at all.
     */
    @Test
    fun `the notice names no real name`() {
        val plan = planPlacing(
            """
            class Ledger {
                int merchantReference;

                int merchantReference() {
                    return merchantReference;
                }
            }
            """.trimIndent(),
            at(0, symbol("Ledger", SymbolRole.TYPE, SymbolOrigin.IN_CONTENT)),
            at(0, symbol("merchantReference", SymbolRole.FIELD, SymbolOrigin.IN_CONTENT, key = "field:m")),
            at(1, symbol("merchantReference", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT, key = "method:m")),
            at(2, symbol("merchantReference", SymbolRole.FIELD, SymbolOrigin.IN_CONTENT, key = "field:m")),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        val notice = result.fidelityNotices().single()
        assertFalse("merchant" in notice.lowercase(), "the notice leaked the name it is about: $notice")
        assertEquals(listOf("field2 and method3 were the same name"), result.fidelityNotices())
    }

    /**
     * **Nothing is injected into the clipboard text.** Injecting a machine comment so the AI sees
     * the coincidence was rejected: it costs no privacy, but *"these two share a name"* is a loud
     * prompt and the model will very likely report shadowing as *the* finding regardless of the real
     * defect — a confident false positive, deliberately manufactured. It also pushes the tool past a
     * descriptive-substitution claim into annotating code the user did not write.
     */
    @Test
    fun `the disclosure changes no character of the output`() {
        val result = anonymize(fieldAndMethodAlike(), AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertTrue(
            result.fidelityNotices().isNotEmpty(),
            "the notice did not fire, so this asserts nothing about a disclosure",
        )
        assertEquals(
            """
            class Type1 {
                int field2;

                int method3() {
                    return field2;
                }
            }
            """.trimIndent(),
            result.text,
        )
    }

    /** Three symbols spelled alike are one destroyed coincidence, and the notice lists all three. */
    @Test
    fun `a name three symbols shared lists all three placeholders`() {
        val plan = planPlacing(
            """
            void settle(int total) {
                { int total = 1; }
                { int total = 2; }
            }
            """.trimIndent(),
            at(0, symbol("settle", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT)),
            at(0, symbol("total", SymbolRole.PARAMETER, SymbolOrigin.IN_CONTENT, key = "param:total")),
            at(1, symbol("total", SymbolRole.LOCAL, SymbolOrigin.IN_CONTENT, key = "local:total@1")),
            at(2, symbol("total", SymbolRole.LOCAL, SymbolOrigin.IN_CONTENT, key = "local:total@2")),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals(listOf("param2, local3 and local4 were the same name"), result.fidelityNotices())
    }

    /** Two coincidences are two clauses of the one notice — the list of notices stays closed at two. */
    @Test
    fun `two destroyed coincidences are one notice with two clauses`() {
        val plan = planPlacing(
            """
            void settle(int total, int owed) {
                { int total = 1; }
                { int owed = 2; }
            }
            """.trimIndent(),
            at(0, symbol("settle", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT)),
            at(0, symbol("total", SymbolRole.PARAMETER, SymbolOrigin.IN_CONTENT, key = "param:total")),
            at(0, symbol("owed", SymbolRole.PARAMETER, SymbolOrigin.IN_CONTENT, key = "param:owed")),
            at(1, symbol("total", SymbolRole.LOCAL, SymbolOrigin.IN_CONTENT, key = "local:total")),
            at(1, symbol("owed", SymbolRole.LOCAL, SymbolOrigin.IN_CONTENT, key = "local:owed")),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals(
            listOf("param2 and local4 were the same name; param3 and local5 were the same name"),
            result.fidelityNotices(),
        )
    }

    /**
     * **A preserved name is not grouped with the renamed one it matched**, and the reason is the
     * rule above rather than a scope decision: a preserved symbol is written out under its own name,
     * so a notice pairing it with a placeholder would say that name out loud. The group-by is over
     * what was renamed, and every name it can reach is a name it may print.
     */
    @Test
    fun `a preserved name is not grouped with the renamed one it matched`() {
        val plan = planPlacing(
            """
            class Task {
                boolean run;

                void run() {}
            }
            """.trimIndent(),
            at(0, symbol("Task", SymbolRole.TYPE, SymbolOrigin.IN_CONTENT)),
            at(0, symbol("run", SymbolRole.FIELD, SymbolOrigin.IN_CONTENT, key = "field:Task.run")),
            at(
                1,
                symbol(
                    "run",
                    SymbolRole.METHOD,
                    SymbolOrigin.IN_CONTENT,
                    key = "method:Task.run",
                    overrideRoots = listOf(OverrideRoot("method:java.lang.Runnable.run", SymbolOrigin.LIBRARY)),
                ),
            ),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertTrue("void run()" in result.text, "the method was renamed, so nothing here was preserved")
        assertEquals(emptyList<String>(), result.fidelityNotices())
    }

    /**
     * **A literal is not a symbol, and a repeated literal gets no notice.**
     *
     * String-literal text has no recourse and that is a stated, accepted loss — the preview already
     * shows every one of them, so the loss is not invisible and the trigger test does not fire. Two
     * occurrences of one string are two rows standing for one value, which is the mapping working
     * rather than a coincidence being destroyed.
     */
    @Test
    fun `two identical literals are not a destroyed name coincidence`() {
        val plan = planOf(
            """
            void settle() {
                audit("pending");
                audit("pending");
            }
            """.trimIndent(),
            symbol("settle", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT),
            symbol("audit", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT),
        )
            .withLiteral("\"pending\"")
            .withLiteral("\"pending\"")

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals(2, result.mapping.count { it.value == "pending" }, "the fixture redacted both literals")
        assertEquals(emptyList<String>(), result.fidelityNotices())
    }

    /**
     * The strip count already exists, split by parse verdict. This is the sentence that surfaces it:
     * *`2 comments stripped`* is not actionable, and the split is — the keep-comments tick is
     * already sitting in the preview, so disclosure plus that tick closes the loop with no new leak
     * surface and no reopened decision.
     */
    @Test
    fun `the comment notice splits the strip count by parse verdict`() {
        val result = anonymize(twoComments(), AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals(listOf("2 comments stripped, 1 of them commented-out code"), result.fidelityNotices())
    }

    /**
     * Prose alone is the commonest strip there is, and the split has nothing to add to it: the
     * clause appears only where there is commented-out code to report.
     */
    @Test
    fun `a strip of prose alone says only how many`() {
        val plan = planOf(
            """
            void settle() {
                // reconcile against the merchant ledger
                audit();
            }
            """.trimIndent(),
            symbol("settle", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT),
            symbol("audit", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT),
        ).withComment("// reconcile against the merchant ledger", CommentVerdict.PROSE)

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals(listOf("1 comment stripped"), result.fidelityNotices())
    }

    /**
     * The case the ticket is written for, and the one where the wording has to hold up: a single
     * stripped comment that *was* the commented-out assignment. The one question every variant of
     * the naming experiment answered at a full 9/9 was *"find the commented-out assignment"*, and a
     * reviewer called that line the most useful surviving clue. The default deletes it on every
     * paste; the default does not flip, and the loss is disclosed instead.
     */
    @Test
    fun `a single stripped comment that was code says so`() {
        val plan = planOf(
            """
            void settle() {
                // this.customer.setOrder(order);
                audit();
            }
            """.trimIndent(),
            symbol("settle", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT),
            symbol("audit", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT),
        ).withComment("// this.customer.setOrder(order);", CommentVerdict.CODE)

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals(listOf("1 comment stripped, and it was commented-out code"), result.fidelityNotices())
    }

    /** Every one of them, and none of them prose — a strip that is entirely commented-out code. */
    @Test
    fun `a strip that is all code says all of them`() {
        val plan = planOf(
            """
            void settle() {
                // this.customer.setOrder(order);
                // total = 1;
                audit();
            }
            """.trimIndent(),
            symbol("settle", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT),
            symbol("audit", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT),
        )
            .withComment("// this.customer.setOrder(order);", CommentVerdict.CODE)
            .withComment("// total = 1;", CommentVerdict.CODE)

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals(listOf("2 comments stripped, all of them commented-out code"), result.fidelityNotices())
    }

    /**
     * **Neither notice appears when its count is zero — a clean snippet says nothing.**
     *
     * That is the opposite rule from the counts beside them, and deliberately so: a count that
     * appeared only when it fired would make its absence unreadable, where a *notice* that fired on
     * every invocation is one nobody reads on the invocation where it matters.
     */
    @Test
    fun `a snippet with neither loss says nothing`() {
        val plan = planOf(
            "void settle(int amount) {}",
            symbol("settle", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT),
            symbol("amount", SymbolRole.PARAMETER, SymbolOrigin.IN_CONTENT, key = "param:amount"),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals(emptyList<String>(), result.fidelityNotices())
    }

    /**
     * The reduction closes its own notice: comments kept are comments not removed, and there is
     * nothing left to disclose. This counts what is **missing from the output**, never what was in
     * the input.
     */
    @Test
    fun `keeping comments leaves nothing to disclose`() {
        val result = anonymize(twoComments(), AnonymizationSettings(keepComments = true), LedgerSnapshot.EMPTY)

        assertEquals(emptyList<String>(), result.fidelityNotices())
    }

    /** Both losses at once, in the order the two notices are numbered. */
    @Test
    fun `both notices appear together, flattened names first`() {
        val plan = planPlacing(
            """
            class Ledger {
                int balance;

                int balance() {
                    // this.total = 1;
                    return balance;
                }
            }
            """.trimIndent(),
            at(0, symbol("Ledger", SymbolRole.TYPE, SymbolOrigin.IN_CONTENT)),
            at(0, symbol("balance", SymbolRole.FIELD, SymbolOrigin.IN_CONTENT, key = "field:Ledger.balance")),
            at(1, symbol("balance", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT, key = "method:Ledger.balance")),
            at(2, symbol("balance", SymbolRole.FIELD, SymbolOrigin.IN_CONTENT, key = "field:Ledger.balance")),
        ).withComment("// this.total = 1;", CommentVerdict.CODE)

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals(
            listOf(
                "field2 and method3 were the same name",
                "1 comment stripped, and it was commented-out code",
            ),
            result.fidelityNotices(),
        )
    }

    /** A field and the fluent accessor beside it: one name in source, two placeholders after it. */
    private fun fieldAndMethodAlike(): SnippetPlan = planPlacing(
        """
        class Ledger {
            int balance;

            int balance() {
                return balance;
            }
        }
        """.trimIndent(),
        at(0, symbol("Ledger", SymbolRole.TYPE, SymbolOrigin.IN_CONTENT)),
        at(0, symbol("balance", SymbolRole.FIELD, SymbolOrigin.IN_CONTENT, key = "field:Ledger.balance")),
        at(1, symbol("balance", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT, key = "method:Ledger.balance")),
        at(2, symbol("balance", SymbolRole.FIELD, SymbolOrigin.IN_CONTENT, key = "field:Ledger.balance")),
    )

    /** One prose comment and one commented-out line — the fixture two of these tests share. */
    private fun twoComments(): SnippetPlan = planOf(
        """
        void settle() {
            // reconcile against the merchant ledger
            // this.customer.setOrder(order);
            audit();
        }
        """.trimIndent(),
        symbol("settle", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT),
        symbol("audit", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT),
    )
        .withComment("// reconcile against the merchant ledger", CommentVerdict.PROSE)
        .withComment("// this.customer.setOrder(order);", CommentVerdict.CODE)
}
