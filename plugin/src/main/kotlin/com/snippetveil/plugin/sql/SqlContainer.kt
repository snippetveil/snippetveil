package com.snippetveil.plugin.sql

import com.intellij.database.model.ObjectKind
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtilCore
import com.intellij.sql.psi.SqlCommonTokens
import com.intellij.sql.psi.SqlFile
import com.intellij.sql.psi.SqlFunctionCallExpression
import com.intellij.sql.psi.SqlIdentifier
import com.intellij.sql.psi.SqlIdentifierKeywordTokenType
import com.intellij.sql.psi.SqlKeywordTokenType
import com.intellij.sql.psi.SqlParameter
import com.intellij.sql.psi.SqlReferenceElementType
import com.intellij.sql.psi.SqlReferenceExpression
import com.intellij.sql.psi.SqlTokens
import com.snippetveil.core.SourceLanguage
import com.snippetveil.core.SqlKeys
import com.snippetveil.core.SymbolEvidence
import com.snippetveil.core.SymbolOrigin
import com.snippetveil.core.SymbolRole
import com.snippetveil.plugin.InjectedContainer
import com.snippetveil.plugin.InjectedFragment
import com.snippetveil.plugin.InjectedName
import com.snippetveil.plugin.InjectedReading

/**
 * **An injected SQL fragment, read token by token into `table`, `col` and `schema` names** — or, when
 * any token is one this reading cannot vouch for, nothing at all, and the fragment is one redacted
 * literal exactly as it was before this container existed.
 *
 * `// language=SQL "SELECT state FROM billing.customers"` comes out as
 * `"SELECT col1 FROM schema2.table3"`.
 *
 * **The trigger is position, not binding.** A fragment is read when the platform injected SQL into
 * the host literal and the fragment parses. Nothing has to resolve, and nothing is asked to: a table
 * name in raw SQL usually resolves to nothing at all, and that is expected rather than a failure. What
 * decides a token is where the grammar put it.
 *
 * **The composite decides the token.** A token's kind is read off the reference element type's
 * target kind, on the **composite** — the reference expression the token names — while the preserve
 * predicate reads the **leaf** token. Read as though they were flat, the two disagree about exactly
 * the case that matters: a column named `state` has a composite that says *column reference* and a
 * leaf that is an identifier-keyword, so a leaf-first reading classifies it as a keyword and writes it
 * into the output verbatim. That is a leak, and it is the class this product cares most about. So **a
 * claimed position claims its token, whatever the token type says**, and the preserve predicate is a
 * residual: it decides only what no claimed position took. See [decide].
 *
 * **The preserve predicate is closed**, and deliberately not a table anyone can extend by taste:
 * keyword, operator and punctuation tokens; a function call carrying a builtin definition, which is a
 * dialect fact needing no model; and a non-string literal. **Everything else falls the fragment back**
 * — a reference the grammar could not decide, a user-defined routine name, a string, a comment, an
 * element type shipped in a database-plugin version nobody has met yet. Nobody adds an entry to rescue
 * a bug report: either the projection rule is extended, or the fragment falls back.
 *
 * **A fallback is silent**, and that is load-bearing rather than an omission. It produces one
 * redacted-literal occurrence per host literal, byte-identical to the plan built with no container,
 * and nothing user-visible fires: no balloon, no notice, no count. A message here would tell the user
 * which plugins their IDE has and which resolution scopes their project carries — an in-product
 * availability signal this product has declined to build. The accepted cost is that a query which
 * silently stops decomposing is indistinguishable from one that never did.
 *
 * **Loaded only where the database plugin is.** Every type this class reads is that plugin's, so it is
 * registered from `com.snippetveil-withDatabase.xml` and lives in the one package allowed to name
 * them; `ShippedCodeArchitectureTest` holds the rest of the plugin to that.
 */
internal class SqlContainer : InjectedContainer {

    override fun read(fragment: InjectedFragment): InjectedReading? =
        (decide(fragment) as? SqlDecision.Decomposed)?.reading

    companion object {

        /**
         * **What this container makes of [fragment]**: the names it read, or the first token it could
         * not vouch for and why — the one place the reason a fragment fell back is kept, for a test or
         * an instrument to read. It never reaches the user.
         *
         * Each token is asked, in this order and no other:
         *
         *  1. **Nothing to carry** — whitespace, or an element with no text.
         *  2. **A bind parameter**, in either spelling — `?` or `:name`. Unprojectable, and asked
         *     before anything else so that its punctuation is not kept as punctuation.
         *  3. **A claimed position** — the name of a reference whose composite projects onto `table`,
         *     `col` or `schema`. Claimed **whatever the leaf's token type says**: this is the precedence
         *     the whole container exists to state.
         *  4. **Any other reference's name** falls the fragment back, unless it names a function call
         *     carrying a builtin definition: it is a name, so its token type is not asked.
         *  5. **The residual preserve predicate** — see [isPreserved].
         *  6. Anything else, and the fragment falls back.
         */
        fun decide(fragment: InjectedFragment): SqlDecision {
            val file = fragment.file as? SqlFile ?: return SqlDecision.FellBack(SqlFallback.NOT_SQL, fragment.file)
            if (PsiTreeUtil.hasErrorElements(file)) return SqlDecision.FellBack(SqlFallback.DOES_NOT_PARSE, file)

            val names = mutableListOf<InjectedName>()
            var leaf: PsiElement? = PsiTreeUtil.firstChild(file)
            while (leaf != null) {
                val at = leaf
                leaf = PsiTreeUtil.nextLeaf(at)

                if (at is PsiWhiteSpace || at.textLength == 0) continue
                if (isInBindParameter(at, file)) return SqlDecision.FellBack(SqlFallback.BIND_PARAMETER, at)

                val position = referenceNamedBy(at)
                if (position != null) {
                    val role = roleOf(position)
                    if (role != null) {
                        names += nameOf(at, at.parent as SqlIdentifier, role)
                        continue
                    }
                    // The name of a reference the projection does not know is never a keyword or an
                    // operator, whatever its token type says; the one thing it may be kept as is the
                    // name of a builtin function call.
                    if (isBuiltinFunctionName(at)) continue
                    return SqlDecision.FellBack(SqlFallback.UNPROJECTABLE_REFERENCE, at)
                }

                if (isPreserved(at)) continue
                return SqlDecision.FellBack(fallbackFor(at), at)
            }
            return SqlDecision.Decomposed(InjectedReading(names))
        }

        /**
         * The reference [leaf] is **the name of**, or `null` when it names none: the leaf is the whole
         * of an identifier, and that identifier is the reference's own — not a qualifier's, which is
         * a reference of its own, and not a declaration's, such as an alias.
         *
         * A whole identifier, one leaf, because *a claimed position claims its token*: an identifier
         * the platform built out of more than one leaf is not a shape this container has met, and a
         * token it half-claimed would leave the other half to no rule.
         */
        private fun referenceNamedBy(leaf: PsiElement): SqlReferenceExpression? {
            val identifier = leaf.parent as? SqlIdentifier ?: return null
            if (identifier.firstChild !== leaf || identifier.lastChild !== leaf) return null
            val reference = identifier.parent as? SqlReferenceExpression ?: return null
            return reference.takeIf { it.identifier === identifier }
        }

        /**
         * **The projection rule**: which of `table`, `col` and `schema` the name of [reference] is, read
         * on the composite — or `null` when the composite does not say, which leaves the token to the
         * residual predicate.
         *
         * **The composite** is the outermost reference [reference] is a qualifier of — `billing.customers`
         * for `billing` — or [reference] itself when it qualifies nothing. Its name takes the target
         * kind of the composite's own element type.
         *
         * **A qualifier takes the part of the composite's name it is**, and the composite is what says
         * which part that is: `billing.customers` reports `billing` as its schema part and `customers` as
         * its table part, so a qualified name yields a `schema` and a `table` rather than one name. The
         * composite has to account for every segment it is written with, in order and spelled as each
         * segment names itself; a composite that reports fewer parts than it has segments —
         * `crm.dbo.accounts`, whose first segment is a database, or `customers.state`, a column whose
         * qualifier the grammar leaves undecided — has not said what a segment is, and the segment is
         * not claimed.
         */
        private fun roleOf(reference: SqlReferenceExpression): SymbolRole? {
            var composite = reference
            while (true) {
                val outer = composite.parent as? SqlReferenceExpression ?: break
                if (outer.qualifierExpression !== composite) break
                composite = outer
            }
            val compositeRole = ROLES[composite.referenceElementType.targetKind] ?: return null
            if (composite === reference) return compositeRole

            val written = qualifierChainOf(composite)
            val parts = PART_ORDER.mapNotNull { kind ->
                composite.getReferencePart(kind).takeIf { it.isNotEmpty() }?.let { kind to it }
            }
            if (parts.size != written.size || ROLES[parts.last().first] != compositeRole) return null
            for ((segment, part) in written.zip(parts)) {
                if (segment.identifier?.name != part.second) return null
            }
            return ROLES[parts[written.indexOf(reference)].first]
        }

        /** [composite] and every qualifier under it, as written: the outermost qualifier first. */
        private fun qualifierChainOf(composite: SqlReferenceExpression): List<SqlReferenceExpression> {
            val chain = mutableListOf(composite)
            var at: SqlReferenceExpression = composite
            while (true) {
                val qualifier = at.qualifierExpression as? SqlReferenceExpression ?: break
                chain += qualifier
                at = qualifier
            }
            return chain.asReversed()
        }

        /**
         * One claimed name: the whole token, and the part of it a placeholder is written into — the
         * inside of the delimiters of a delimited identifier, as the identifier itself reports it.
         *
         * **Keyed by how it is spelled, and never persisted.** Nothing resolves a table name to a
         * declaration, so its spelling — delimiters and case included — is its only identity; see
         * [SqlKeys.named]. The key is unqualified, so the rule that keeps a local out of the ledger
         * keeps it out too.
         *
         * **[SymbolOrigin.IN_CONTENT], whatever file the host literal is in.** A SQL name stands in for
         * part of a literal, and a literal is redacted wherever it is written — so a name read out of
         * one is never less concealed than the fallback it replaces. Any other origin would be worse
         * than wrong: a library origin would put a table name on the clipboard that the redacted
         * literal hid, and an unresolved one would render it in the `Unknown` namespace, which says the
         * IDE could not tell what the name is when the grammar just did.
         */
        private fun nameOf(leaf: PsiElement, identifier: SqlIdentifier, role: SymbolRole): InjectedName {
            val token = leaf.textRange
            val name = identifier.nameRange.shiftRight(identifier.textRange.startOffset)
                .takeIf { token.contains(it) && !it.isEmpty }
                ?: token
            val spelling = leaf.text
            return InjectedName(
                token = token,
                name = name,
                symbol = SymbolEvidence(
                    key = SqlKeys.named(role, spelling),
                    role = role,
                    origin = SymbolOrigin.IN_CONTENT,
                    declaredName = name.shiftLeft(token.startOffset).substring(spelling),
                ),
                language = SourceLanguage.SQL,
            )
        }

        /**
         * **The residual preserve predicate** — what may be kept as written, decided by the leaf's
         * element type and never by its characters. Asked only of a token no claimed position took.
         *
         *  - A **keyword** token. Not an identifier-keyword: that is a different token type, and it
         *    exists precisely for a keyword used as an identifier, so it cannot be kept — it may be a
         *    name.
         *  - An **operator** or **punctuation** token: one of the token types the database plugin
         *    declares as its common operators and punctuation. See [OPERATORS_AND_PUNCTUATION].
         *  - The name of a **function call carrying a builtin definition** — `count`, `upper` — which
         *    is a fact of the dialect and needs no data source. A call with no builtin definition is a
         *    user-defined routine, and its name is the user's.
         *  - A **non-string literal**: a number.
         */
        private fun isPreserved(leaf: PsiElement): Boolean {
            val type = PsiUtilCore.getElementType(leaf)
            return type is SqlKeywordTokenType || type in OPERATORS_AND_PUNCTUATION || type in NUMBER_TOKENS
        }

        private fun isBuiltinFunctionName(leaf: PsiElement): Boolean {
            val reference = referenceNamedBy(leaf) ?: return false
            if (reference.qualifierExpression != null) return false
            val call = reference.parent as? SqlFunctionCallExpression ?: return false
            return call.nameElement === reference && call.functionDefinition != null
        }

        /**
         * Whether [leaf] is part of a **bind parameter**, in either spelling: a positional `?` — or
         * `?1`, `$1` — which the platform builds as a parameter, and a named `:name`, which it builds as
         * a reference to a variable. Neither is a name the fragment's text can say anything about.
         */
        private fun isInBindParameter(leaf: PsiElement, file: SqlFile): Boolean {
            var at: PsiElement? = leaf
            while (at != null && at !== file) {
                if (at is SqlParameter) return true
                val type = PsiUtilCore.getElementType(at)
                if (type is SqlReferenceElementType && type.targetKind == ObjectKind.VARIABLE) return true
                at = at.parent
            }
            return false
        }

        /** Why [leaf], which names no reference and which nothing kept, falls its fragment back. */
        private fun fallbackFor(leaf: PsiElement): SqlFallback =
            if (PsiUtilCore.getElementType(leaf) is SqlIdentifierKeywordTokenType) {
                SqlFallback.IDENTIFIER_KEYWORD_OUTSIDE_A_CLAIM
            } else {
                SqlFallback.UNCLAIMED_TOKEN
            }

        /**
         * **The three kinds a composite can project onto**, by the target kind its element type
         * reports. Every other target kind — a routine, an index, a sequence, *any* — is a reference
         * this rule does not project, and the token under it is left to the residual predicate.
         */
        private val ROLES: Map<ObjectKind, SymbolRole> = mapOf(
            ObjectKind.SCHEMA to SymbolRole.SCHEMA,
            ObjectKind.TABLE to SymbolRole.TABLE,
            ObjectKind.COLUMN to SymbolRole.COLUMN,
        )

        /** The parts of a qualified name, in the order a name is written: the schema before its table. */
        private val PART_ORDER: List<ObjectKind> = listOf(ObjectKind.SCHEMA, ObjectKind.TABLE, ObjectKind.COLUMN)

        private val NUMBER_TOKENS: Set<IElementType> = setOf(SqlTokens.SQL_INTEGER_TOKEN, SqlTokens.SQL_FLOAT_TOKEN)

        /**
         * **The operator and punctuation token types** — every token type the database plugin declares
         * in `SqlCommonTokens`, its own statement of which tokens are its common operators and
         * punctuation, as of the oldest plugin this is compiled against.
         *
         * Named one by one because a set read out of the interface at runtime would take in whatever a
         * later plugin adds to it, and that is the direction this predicate may not move in by itself.
         * A token type added since is not here, and a fragment holding one falls back — the
         * fail-closed direction, and the one a newer plugin is allowed to push this in.
         */
        private val OPERATORS_AND_PUNCTUATION: Set<IElementType> = setOf(
            SqlCommonTokens.CROACH_OP_TYPE_CAST,
            SqlCommonTokens.ORA_OP_JOIN,
            SqlCommonTokens.ORA_OP_NAMED_PARAM_BINDING,
            SqlCommonTokens.ORA_POWER,
            SqlCommonTokens.PG_COPY_TERMINATOR,
            SqlCommonTokens.PG_OP_ABS,
            SqlCommonTokens.PG_OP_BITWISE_XOR,
            SqlCommonTokens.PG_OP_CUSTOM,
            SqlCommonTokens.PG_OP_EXP,
            SqlCommonTokens.PG_OP_PRIME,
            SqlCommonTokens.PG_OP_TYPE_CAST,
            SqlCommonTokens.PG_PSQL_BACKSLASH,
            SqlCommonTokens.SQL_ASTERISK,
            SqlCommonTokens.SQL_AT_SIGN,
            SqlCommonTokens.SQL_COLON,
            SqlCommonTokens.SQL_COMMA,
            SqlCommonTokens.SQL_DOLLAR,
            SqlCommonTokens.SQL_DOUBLE_COLON,
            SqlCommonTokens.SQL_DOUBLE_PERIOD,
            SqlCommonTokens.SQL_ELLIPSES,
            SqlCommonTokens.SQL_EOL,
            SqlCommonTokens.SQL_HASHMARK,
            SqlCommonTokens.SQL_LEFT_ANGLES,
            SqlCommonTokens.SQL_LEFT_BRACE,
            SqlCommonTokens.SQL_LEFT_BRACKET,
            SqlCommonTokens.SQL_LEFT_PAREN,
            SqlCommonTokens.SQL_OP_ASSIGN,
            SqlCommonTokens.SQL_OP_BITWISE_AND,
            SqlCommonTokens.SQL_OP_BITWISE_AND_EQ,
            SqlCommonTokens.SQL_OP_BITWISE_NOT,
            SqlCommonTokens.SQL_OP_BITWISE_OR,
            SqlCommonTokens.SQL_OP_BITWISE_OR_EQ,
            SqlCommonTokens.SQL_OP_BITWISE_XOR,
            SqlCommonTokens.SQL_OP_BITWISE_XOR_EQ,
            SqlCommonTokens.SQL_OP_CONCAT,
            SqlCommonTokens.SQL_OP_DIV,
            SqlCommonTokens.SQL_OP_DIV_EQ,
            SqlCommonTokens.SQL_OP_DIV_INT,
            SqlCommonTokens.SQL_OP_EQ,
            SqlCommonTokens.SQL_OP_EQEQ,
            SqlCommonTokens.SQL_OP_EQ_MUL,
            SqlCommonTokens.SQL_OP_GE,
            SqlCommonTokens.SQL_OP_GE_WS,
            SqlCommonTokens.SQL_OP_GT,
            SqlCommonTokens.SQL_OP_INVERT,
            SqlCommonTokens.SQL_OP_JSON_EXTRACT,
            SqlCommonTokens.SQL_OP_JSON_EXTRACT_UNQUOTE,
            SqlCommonTokens.SQL_OP_LE,
            SqlCommonTokens.SQL_OP_LEFT_SHIFT,
            SqlCommonTokens.SQL_OP_LE_WS,
            SqlCommonTokens.SQL_OP_LOGICAL_AND,
            SqlCommonTokens.SQL_OP_LOGICAL_OR,
            SqlCommonTokens.SQL_OP_LT,
            SqlCommonTokens.SQL_OP_MINUS,
            SqlCommonTokens.SQL_OP_MINUS_EQ,
            SqlCommonTokens.SQL_OP_MODULO,
            SqlCommonTokens.SQL_OP_MODULO_EQ,
            SqlCommonTokens.SQL_OP_MUL,
            SqlCommonTokens.SQL_OP_MUL_EQ,
            SqlCommonTokens.SQL_OP_NEQ,
            SqlCommonTokens.SQL_OP_NEQ2,
            SqlCommonTokens.SQL_OP_NEQ2_WS,
            SqlCommonTokens.SQL_OP_NEQ3,
            SqlCommonTokens.SQL_OP_NEQ3_WS,
            SqlCommonTokens.SQL_OP_NEQ4,
            SqlCommonTokens.SQL_OP_NEQ4_WS,
            SqlCommonTokens.SQL_OP_NEQ_WS,
            SqlCommonTokens.SQL_OP_NOT2,
            SqlCommonTokens.SQL_OP_NOT_GT,
            SqlCommonTokens.SQL_OP_NOT_LT,
            SqlCommonTokens.SQL_OP_NULLSAFE_EQ,
            SqlCommonTokens.SQL_OP_PLUS,
            SqlCommonTokens.SQL_OP_PLUS_EQ,
            SqlCommonTokens.SQL_OP_RANGE,
            SqlCommonTokens.SQL_OP_RIGHT_SHIFT,
            SqlCommonTokens.SQL_PERCENT_SIGN,
            SqlCommonTokens.SQL_PERIOD,
            SqlCommonTokens.SQL_QUESTION_MARK,
            SqlCommonTokens.SQL_RIGHT_ANGLES,
            SqlCommonTokens.SQL_RIGHT_BRACE,
            SqlCommonTokens.SQL_RIGHT_BRACKET,
            SqlCommonTokens.SQL_RIGHT_PAREN,
            SqlCommonTokens.SQL_SEMICOLON,
            SqlCommonTokens.SQL_UNDERSCORE,
            SqlCommonTokens.VERT_FAIL_CAST,
        )
    }
}

/** What [SqlContainer.decide] made of one fragment. */
internal sealed class SqlDecision {

    /** Every token vouched for: the names read, and nothing else in the fragment carries a word. */
    class Decomposed(val reading: InjectedReading) : SqlDecision()

    /**
     * The fragment falls back, because of [at] — the first token nothing claimed or kept, or the
     * fragment itself when it was never read.
     */
    class FellBack(val reason: SqlFallback, val at: PsiElement) : SqlDecision() {

        /** [at]'s element type, as the database plugin names it — what a triage list would group by. */
        val elementType: String get() = PsiUtilCore.getElementType(at).toString()
    }
}

/**
 * **Why a fragment fell back** — never shown to the user, and never counted where the user can see
 * it. See [SqlContainer] for why the silence is the design.
 */
internal enum class SqlFallback {
    /** The platform injected something other than SQL. */
    NOT_SQL,

    /** The fragment has a parse error, so no position in it is one the grammar decided. */
    DOES_NOT_PARSE,

    /** A bind parameter, `?` or `:name`: unprojectable in either spelling. */
    BIND_PARAMETER,

    /**
     * The name of a reference whose composite projects onto none of `table`, `col` and `schema` — a
     * routine with no builtin definition, an index, a reference the grammar could not decide, an
     * element type nobody has met yet.
     */
    UNPROJECTABLE_REFERENCE,

    /**
     * An identifier-keyword no claimed position took. The token type is the evidence: it is not the
     * keyword token type, and it exists for a keyword used as an identifier — so it may be a name and
     * cannot be kept, and nothing claimed it, so it cannot be projected.
     */
    IDENTIFIER_KEYWORD_OUTSIDE_A_CLAIM,

    /** Any other token nothing claimed or kept: a string, a comment, an alias, an unrecognised type. */
    UNCLAIMED_TOKEN,
}
