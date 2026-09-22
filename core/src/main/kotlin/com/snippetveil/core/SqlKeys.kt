package com.snippetveil.core

/**
 * **The two shapes a SQL name's key can take** — both ephemeral, and neither ever written down.
 *
 *  - [declared] — `(fragment, kind, name)`, for a name a fragment itself declares: a CTE, an alias.
 *    It belongs to that fragment and to nothing else, so two fragments each declaring `recent` are
 *    two symbols.
 *  - [named] — `(kind, spelling)`, invocation-wide, for a table, a column or a schema. Nothing
 *    resolves a table name to a declaration, so the only identity it has is how it is spelled, and
 *    it is spelled **exactly as written**: delimiters and case included, with no folding. A
 *    delimited identifier is case-sensitive in most dialects where a bare one is folded, so
 *    `"Customers"` and `customers` name different tables, and keying them together would make one
 *    placeholder stand for two things.
 *
 * **Not a new tier, and not a new rule.** Neither key is qualified, so a builder reports it with
 * [SymbolEvidence.keyIsQualified] left `false`, and the rule that keeps a local out of the ledger
 * keeps these out too, for the same reason: there is no declaration for the name to belong to. It is
 * the existing allocated-but-not-persisted path taking a second population — each name draws a
 * number from the shared counter, and the number is burnt when the invocation ends.
 *
 * Spelled here rather than by each builder so that the two shapes are one grammar with one owner.
 * Both start with a prefix no JVM key starts with, so no SQL key can ever pick up a placeholder the
 * ledger holds for a Java symbol.
 */
object SqlKeys {

    /**
     * The key of [name], of kind [kind], **as declared by [fragment]** — whatever identifies one
     * fragment within one invocation.
     *
     * The fragment is length-prefixed so the key is injective in its three parts however a fragment
     * happens to be identified: `("a:b", "c")` and `("a", "b:c")` are two keys, not one.
     */
    fun declared(fragment: String, kind: SymbolRole, name: String): String =
        "sql-declared:${sqlKind(kind)}:${fragment.length}:$fragment:$name"

    /** The key of a SQL name of kind [kind], written [spelling] — delimiters and case included. */
    fun named(kind: SymbolRole, spelling: String): String = "sql:${sqlKind(kind)}:$spelling"

    private fun sqlKind(kind: SymbolRole): String {
        require(kind in SQL_KINDS) { "$kind is not a SQL kind; a SQL name is one of $SQL_KINDS" }
        return kind.name
    }

    /** The kinds a SQL name can be. */
    private val SQL_KINDS = setOf(SymbolRole.TABLE, SymbolRole.COLUMN, SymbolRole.SCHEMA)
}
