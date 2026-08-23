package com.snippetveil.plugin

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.snippetveil.core.AnonymizationSettings
import com.snippetveil.core.LedgerSnapshot
import com.snippetveil.core.SnippetPlan
import com.snippetveil.core.SymbolEvidence
import com.snippetveil.core.anonymize

/**
 * **The keying assertion family: every symbol kind, how it is keyed, and whether that key can be
 * persisted.**
 *
 * A throwaway spike walked PSI directly and stumbled into three keying bugs on the way through. Two
 * of them were *"what is the key for this symbol"* and one was *"what is the key's owner, and does
 * that owner permit renaming"* — so three tombstones would be the wrong unit. The honest reading of
 * a walk that found three is that there are more, and what finds those is a family that asks the
 * same two questions of **every** kind rather than three tests remembering three afternoons.
 *
 * The two questions:
 *
 *  - **What is the key?** Two occurrences of one symbol must reach one key however far apart they
 *    are, and two distinct symbols must never reach the same one — that second half is the
 *    injectivity invariant the whole product rests on, and it is what an anonymous class breaks when
 *    its members are keyed by a qualified name it does not have.
 *  - **Does the key survive an edit?** A key built out of a file offset is stable for exactly as
 *    long as one invocation, and a key built out of a fully qualified name is stable for as long as
 *    the name is. That distinction is what decides which symbols a persisted mapping may hold, so it
 *    is asserted here rather than assumed there.
 */
class SymbolKeyingTest : JavaSnippetTestCase() {

    /**
     * Every kind of symbol this walk can produce, and the key shape it is expected to reach.
     *
     * `<anchor>` stands for *this file, at this offset* — the fallback for everything Java gives no
     * qualified name to. Reading the table down the `persistable` column is reading the cut a
     * persisted mapping has to make.
     *
     * **One kind is not in the table and cannot be**: a Lombok light member has no declaration in
     * source, so no fixture file can name one. Its key is asserted in `LombokLightAccessorTest`
     * against a synthesized one, which is the only place it can be.
     */
    fun `test every symbol kind is keyed the way its persistability requires`() {
        assertTheHarnessResolves()
        val plan = planFor(FIXTURE_PATH, FIXTURE)

        for (kind in KINDS) {
            assertEquals(
                "${kind.description}: keyed wrongly",
                kind.key,
                normalize(plan.keyOf(kind.token, kind.ordinal)),
            )
        }
    }

    /**
     * **A key survives an unrelated edit iff it is derived from a fully qualified name**, which is
     * the fact a persisted mapping is built on: FQN-keyed symbols are exactly the ones a
     * conversation refers back to across snippets, and an anchored key silently re-points at a
     * different symbol the moment a line is inserted above it.
     *
     * Asserted by making the edit rather than by reading the key's shape, because the shape is the
     * mechanism and surviving the edit is the property.
     */
    fun `test only FQN-derived keys survive an edit elsewhere in the file`() {
        assertTheHarnessResolves()
        val before = planFor(FIXTURE_PATH, FIXTURE)

        // A blank line at the top of the file: it renames nothing and moves everything.
        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.insertString(0, "\n")
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
        val after = JavaPlanBuilder.build(SnippetRequest(project, myFixture.file, emptyList()))

        for (kind in KINDS) {
            val survived = before.keyOf(kind.token, kind.ordinal) == after.keyOf(kind.token, kind.ordinal)
            assertEquals(
                "${kind.description}: expected to survive an unrelated edit=${kind.survivesAnEdit}",
                kind.survivesAnEdit,
                survived,
            )
        }
    }

    /**
     * **Members of two different anonymous classes get distinct placeholders.** This is the spike's
     * third regression, re-expressed: `PsiClass.getQualifiedName()` is `null` inside an anonymous
     * class, so an owner key that read the name alone was `null` for both — and the `state` fields
     * of two unrelated anonymous classes collapsed onto one placeholder. Two distinct symbols
     * rendering as one name is exactly what the reverse mapping cannot survive.
     *
     * Its control is the table above rather than a test of its own, and that is the pairing a
     * re-expressed regression needs: a rewrite has never failed in its life, so green here is
     * otherwise ambiguous between *the bug is fixed* and *the fixture never had two anonymous
     * classes in it*. The `an anonymous class's field` row asserts the key is anchored — which is
     * only true of a class with no qualified name — and the two `assertEquals` below assert each
     * class's own field reaches one key, so the fixture is pinned as reproducing the condition.
     *
     * It was also demonstrated red the blunt way before being accepted, by making `ownerKeyOf` fall
     * back to a constant when the owner has no qualified name
     * (`owner?.qualifiedName?.let { "class:" + it } ?: "<none>"`), which collapses both classes'
     * `state` onto one key and fails all three tests here.
     */
    fun `test members of two different anonymous classes are keyed apart`() {
        assertTheHarnessResolves()
        val plan = planFor(FIXTURE_PATH, FIXTURE)

        val (firstDeclaration, firstUse, secondDeclaration, secondUse, localClass) =
            (0..4).map { plan.keyOf("state", it) }

        assertEquals("one anonymous class's field is one symbol", firstDeclaration, firstUse)
        assertEquals("one anonymous class's field is one symbol", secondDeclaration, secondUse)
        assertTrue(
            "two anonymous classes' `state` fields must not share a key, and both are $firstDeclaration",
            firstDeclaration != secondDeclaration,
        )
        assertTrue(
            "a local class's `state` field must not share a key either, and both are $localClass",
            localClass != firstDeclaration && localClass != secondDeclaration,
        )

        // Distinct keys are the mechanism; distinct placeholders are the property, and the reverse
        // mapping is well-defined only because they are.
        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)
        assertEquals(
            "the three `state` fields must render as three placeholders",
            3,
            result.mapping.filterValues { it == "state" }.size,
        )
    }

    /**
     * **The table's `persistable` column, asserted against the evidence the walk actually reports.**
     *
     * Until this existed the column was a claim the file made about itself: the row said *this key
     * cannot be persisted* and the only thing holding it to that was the edit test above, which
     * measures a *consequence* of the key's shape rather than what the walk tells the engine. The
     * engine does not look at key shapes — it reads [SymbolEvidence.keyIsQualified] — so a walk that
     * reported the flag wrongly would write anchored keys into a durable file with every test here
     * still green.
     */
    fun `test the walk reports whether each key is derived from a qualified name`() {
        assertTheHarnessResolves()
        val plan = planFor(FIXTURE_PATH, FIXTURE)

        for (kind in KINDS) {
            assertEquals(
                "${kind.description}: the walk reported the wrong answer for `keyIsQualified`",
                kind.persistable,
                plan.evidenceOf(kind.token, kind.ordinal).keyIsQualified,
            )
        }
    }

    /**
     * **The whole fixture through the engine: everything the delta holds is qualified, and every
     * qualified kind is in it.**
     *
     * Both halves are needed and neither is the other. *Nothing anchored got in* is the rule that
     * keeps a durable file from filling with keys that re-point at a different symbol after an edit;
     * *everything qualified did* is what stops the rule being satisfied by writing nothing down at
     * all, which is the shape a fail-closed bug takes here.
     */
    fun `test the delta holds every qualified key and nothing else`() {
        assertTheHarnessResolves()
        val plan = planFor(FIXTURE_PATH, FIXTURE)

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals(
            "a key the walk anchored to a file offset reached the durable mapping",
            emptyList<String>(),
            result.delta.placeholders.keys.filter { "@" in it || it.startsWith("unresolved:") },
        )
        assertEquals(
            "a symbol whose key is qualified was left out of the durable mapping",
            emptyList<String>(),
            KINDS.filter { it.persistable }
                .map { it.key }
                .distinct()
                .filterNot { it in result.delta.placeholders },
        )
    }

    /**
     * **Every qualified key contains the name it is a key for**, which is the fact the whole *"the
     * mapping stores nothing new at rest"* argument stands on.
     *
     * `PlaceholderLedger` persists the symbol's real name beside its placeholder, so that a reversal
     * can read a stated fact instead of parsing a key whose spelling belongs to this module. The
     * justification for that being *free* — CONTRIBUTING says it "puts nothing new at rest" — is that
     * a qualified key is derived from the fully-qualified name and therefore already spells the name
     * out: `field:class:com.acme.Payment#merchantRef`.
     *
     * **That is a claim about this file's `keyOf`, made in a document and in `:core`, and until here
     * nothing checked it.** A key format that stopped carrying the simple name — hashed, numbered,
     * abbreviated — would leave both statements quietly false, and no test in either module would
     * notice, because neither module can see both ends. It is the same drift the accessor-prefix
     * check in `JavaPlanBuilderTest` exists for, on the other fact that crosses this seam.
     *
     * Asserted over the qualified keys alone, because they are exactly the ones written down. An
     * anchored key is `local:file@17` and names nothing, which is why its name is not persisted
     * either.
     */
    fun `test every qualified key spells out the name it is a key for`() {
        assertTheHarnessResolves()
        val plan = planFor(FIXTURE_PATH, FIXTURE)

        val qualified = plan.symbols().map { it.symbol }.filter { it.keyIsQualified }.distinctBy { it.key }

        assertNotEmpty(qualified)
        assertEquals(
            "a qualified key no longer spells out its own name, so the mapping's stored name is not free after all",
            emptyList<String>(),
            qualified.filterNot { it.declaredName in it.key }.map { "${it.declaredName} not in ${it.key}" },
        )
    }

    /** The [ordinal]-th occurrence of [token] in the plan, and the key the walk gave it. */
    private fun SnippetPlan.keyOf(token: String, ordinal: Int): String =
        evidenceOf(token, ordinal).key

    /** The [ordinal]-th occurrence of [token] in the plan, and everything the walk observed about it. */
    private fun SnippetPlan.evidenceOf(token: String, ordinal: Int): SymbolEvidence {
        val occurrences = symbols().filter { it.text == token }
        assertTrue(
            "`$token` occurs ${occurrences.size} times in the fixture; there is no #$ordinal",
            ordinal < occurrences.size,
        )
        return occurrences[ordinal].symbol
    }

    /** The file-and-offset anchor, reduced to the word `<anchor>` so a table can state a key. */
    private fun normalize(key: String): String =
        key.replace(Regex(Regex.escape(myFixture.file.virtualFile.url) + """@\d+"""), "<anchor>")
}

/**
 * One symbol kind, the identifier that names it in the fixture, and the two answers.
 *
 * @param persistable whether the key is derived from a fully qualified name, and therefore whether
 *   it may be written to a file and read back next week meaning the same symbol.
 * @param survivesAnEdit whether the key is the same string after an unrelated edit, which for almost
 *   every kind is the same question — and for exactly one is not. **An `unresolved:` key survives any
 *   edit and still must never be persisted**: it names a *spelling* rather than a symbol, so two
 *   distinct symbols spelled alike share it, and a durable table keyed on it would hand back the
 *   wrong name. Defaults to [persistable], so a row states this only where the two diverge.
 */
private class Kind(
    val description: String,
    val token: String,
    val ordinal: Int = 0,
    val key: String,
    val persistable: Boolean,
    survivesAnEdit: Boolean? = null,
) {
    /** Whether the key is the same string after an unrelated edit. See [Kind]'s own KDoc. */
    val survivesAnEdit: Boolean = survivesAnEdit ?: persistable
}

private val KINDS = listOf(
    Kind("a class", "Ledger", key = "class:com.acme.Ledger", persistable = true),
    Kind("a constructor", "Ledger", ordinal = 1, key = "class:com.acme.Ledger", persistable = true),
    Kind("a type parameter", "T", key = "class:<anchor>", persistable = false),
    Kind("a field", "total", key = "field:class:com.acme.Ledger#total", persistable = true),
    Kind("a method", "audit", key = "method:class:com.acme.Ledger#audit", persistable = true),
    Kind("a parameter", "amount", key = "local:<anchor>", persistable = false),
    Kind("a local variable", "running", key = "local:<anchor>", persistable = false),
    Kind("a label", "outer", key = "local:<anchor>", persistable = false),
    Kind("a nested enum", "Status", key = "class:com.acme.Ledger.Status", persistable = true),
    Kind("an enum constant", "ACTIVE", key = "field:class:com.acme.Ledger.Status#ACTIVE", persistable = true),
    Kind("an anonymous class's field", "state", key = "field:class:<anchor>#state", persistable = false),
    Kind("an anonymous class's method", "run", key = "method:class:<anchor>#run", persistable = false),
    Kind("a local class", "Helper", key = "class:<anchor>", persistable = false),
    Kind("a local class's field", "state", ordinal = 4, key = "field:class:<anchor>#state", persistable = false),
    Kind("a record component", "merchantRef", key = "field:class:com.acme.Payment#merchantRef", persistable = true),

    // Rule 5's fourth face. A compact constructor's parameters are implicit and the JLS names them
    // after the components, so this row is the component's row again — and that it is *the same key*
    // rather than a key of its own is the whole of the fix: keyed apart, the constructor's body named
    // a `param` the record declares nowhere.
    Kind(
        "a compact constructor's implicit parameter",
        "merchantRef",
        ordinal = 1,
        key = "field:class:com.acme.Payment#merchantRef",
        persistable = true,
    ),
    Kind("a record accessor", "merchantRef", ordinal = 2, key = "field:class:com.acme.Payment#merchantRef", persistable = true),

    // The one row where the two columns part company, and the reason they are two columns.
    Kind("an unresolved name", "Missing", key = "unresolved:Missing", persistable = false, survivesAnEdit = true),
)

private const val FIXTURE_PATH = "com/acme/Ledger.java"

/**
 * One file naming every kind in [KINDS] once, so the family is read as a table rather than as a
 * fixture per row.
 */
private val FIXTURE = """
    package com.acme;

    class Ledger<T> {
        static int total;

        Ledger() {}

        <R> R audit(int amount) {
            int running = amount;
            outer: for (;;) { break outer; }

            Runnable first = new Runnable() { int state; public void run() { state++; } };
            Runnable second = new Runnable() { int state; public void run() { state++; } };
            class Helper { int state; }
            Missing absent = null;
            return null;
        }

        enum Status { ACTIVE }
    }

    record Payment(String merchantRef) {
        Payment {
            if (merchantRef == null) throw new IllegalArgumentException();
        }

        static String of(Payment p) { return p.merchantRef(); }
    }
""".trimIndent()
