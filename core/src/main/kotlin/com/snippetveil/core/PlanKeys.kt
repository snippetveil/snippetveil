package com.snippetveil.core

/**
 * **The two shapes a name in an execution plan keys under** — both ephemeral, and neither ever
 * written down.
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
 * Both prefixes are ones no JVM key and no [SqlKeys] key starts with, so no plan name can pick up a
 * placeholder the ledger holds for something else.
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

    private fun planKind(kind: SymbolRole): String {
        require(kind in PLAN_KINDS) { "$kind is not a plan kind; a plan name is one of $PLAN_KINDS" }
        return kind.name
    }

    /** The kinds a plan name can be — the three a query has, and the access path a plan adds. */
    private val PLAN_KINDS =
        setOf(SymbolRole.TABLE, SymbolRole.COLUMN, SymbolRole.SCHEMA, SymbolRole.INDEX)
}
