package com.snippetveil.core

/**
 * **The shapes a token in an execution plan keys under** — two for a name and one for a masked
 * value, all ephemeral, and none ever written down.
 *
 *  - [named] — `(kind, spelling)`, invocation-wide, for a relation, a column, a schema or an index.
 *    Nothing resolves one of these to a declaration, so the only identity it has is how it is
 *    spelled. **A qualified name is therefore several symbols and not one**: `billing.invoices` is a
 *    schema and a relation, each keyed on its own.
 *  - [declared] — for a name the plan itself introduces: a CTE, a relation alias, a subquery's name.
 *    It belongs to **the plan** rather than to the invocation's vocabulary, because two plans each
 *    calling something `v` are not talking about one thing.
 *
 * **The exact spelling keys, with no case folding and no suffix removal.** A delimited identifier is
 * case-sensitive in most dialects where a bare one is folded, so `"Customers"` and `customers` name
 * different relations and keying them together would make one placeholder stand for two things. And
 * an engine-invented spelling keys exactly as written: stripping `_1` from `visits_1` would be text
 * deciding identity by a rule that differs per engine and per version, against a user whose table is
 * genuinely called `visits_1`.
 *
 * **Every key here is ephemeral, and that is a property of the shape rather than a rule applied to
 * it.** Neither is qualified — there is no declaration for a plan name to belong to — so a plan key
 * takes the existing allocated-but-not-persisted path: each name draws a number from the shared
 * counter, and the number is burnt when the invocation ends. The same plan pasted twice therefore
 * gets two sets of placeholders, which is a fidelity limit and not a disclosure.
 *
 * Every prefix here is one no JVM key and no [SqlKeys] key starts with, so no plan token can pick up
 * a placeholder the ledger holds for something else.
 *
 * **The plan sits in the owner position of [declared] as a prefix rather than as an argument**,
 * because an invocation reads exactly one plan: the engine takes one text, and a name the plan
 * declared can only have been declared by that one. A second plan in one invocation would be the
 * moment to spell the owner out, and it would be a change to this file alone.
 */
object PlanKeys {

    /** The key of a plan name of kind [kind], written [spelling] — delimiters and case included. */
    fun named(kind: SymbolRole, spelling: String): String = "plan:${planKind(kind)}:$spelling"

    /** The key of [name], of kind [kind], **as declared by the plan itself** — an alias, a CTE. */
    fun declared(kind: SymbolRole, name: String): String = "plan-declared:${planKind(kind)}:$name"

    /**
     * **The key of a value the plan printed and this product masks** — a literal, an echoed query, a
     * query hash, a hostname, a field that could not be scanned soundly.
     *
     * Keyed on the spelling, so **equal spellings share one token** and within-plan correlation
     * survives the mask: a hash printed at two nodes still reads as one hash at two nodes.
     *
     * **It is a namespace of its own, and that is the mechanism rather than a convention.** A masked
     * value does not join name identity — a database user spelled like a schema is not that schema —
     * and with a prefix neither [named] nor [declared] can produce, the two cannot collide however
     * they are spelled. One namespace for every masked class rather than one per class, because the
     * classes differ in *why* a value is masked and not in *what the value is*: two slots printing
     * the same spelling are printing the same thing.
     */
    fun masked(spelling: String): String = "plan-mask:$spelling"

    private fun planKind(kind: SymbolRole): String {
        require(kind in PLAN_KINDS) { "$kind is not a plan kind; a plan name is one of $PLAN_KINDS" }
        return kind.name
    }

    /** The kinds a plan name can be — the three a query has, and the access path a plan adds. */
    private val PLAN_KINDS =
        setOf(SymbolRole.TABLE, SymbolRole.COLUMN, SymbolRole.SCHEMA, SymbolRole.INDEX)
}
