package com.snippetveil.plugin

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.snippetveil.core.AnonymizationSettings
import com.snippetveil.core.LedgerSnapshot
import com.snippetveil.core.SnippetPlan
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
            val survived = before.keyOf(kind.token, kind.ordinal) == after.keyOf(kind.token, kind.ordinal + 0)
            assertEquals(
                "${kind.description}: expected persistable=${kind.persistable}",
                kind.persistable,
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
     * **Demonstrated red** by making `ownerKeyOf` fall back to a constant when the owner has no
     * qualified name (`owner?.qualifiedName?.let { "class:" + it } ?: "<none>"`), which collapses
     * both classes' `state` onto one key.
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

    /** The [ordinal]-th occurrence of [token] in the plan, and the key the walk gave it. */
    private fun SnippetPlan.keyOf(token: String, ordinal: Int): String {
        val occurrences = symbols().filter { it.text == token }
        assertTrue(
            "`$token` occurs ${occurrences.size} times in the fixture; there is no #$ordinal",
            ordinal < occurrences.size,
        )
        return occurrences[ordinal].symbol.key
    }

    /** The file-and-offset anchor, reduced to the word `<anchor>` so a table can state a key. */
    private fun normalize(key: String): String =
        key.replace(Regex(Regex.escape(myFixture.file.virtualFile.url) + """@\d+"""), "<anchor>")
}

/**
 * One symbol kind, the identifier that names it in the fixture, and the two answers.
 *
 * @param persistable whether the key is derived from a fully qualified name, and therefore whether
 *   it can be written to a file and read back next week meaning the same symbol.
 */
private class Kind(
    val description: String,
    val token: String,
    val ordinal: Int = 0,
    val key: String,
    val persistable: Boolean,
)

private val KINDS = listOf(
    Kind("a class", "Ledger", key = "class:com.acme.Ledger", persistable = true),
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

        <R> R audit(int amount) {
            int running = amount;
            outer: for (;;) { break outer; }

            Runnable first = new Runnable() { int state; public void run() { state++; } };
            Runnable second = new Runnable() { int state; public void run() { state++; } };
            class Helper { int state; }
            return null;
        }

        enum Status { ACTIVE }
    }
""".trimIndent()
