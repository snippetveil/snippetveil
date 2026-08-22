package com.snippetveil.core

/**
 * A truthful description of one snippet — and the only thing that crosses the boundary between the
 * IDE and the anonymization engine.
 *
 * **Evidence crosses this seam, never judgments.** The plan says where a symbol's declaring file
 * lives, what the symbol is called and what shape it has; it never says whether the symbol should
 * be anonymized. That decision belongs to [anonymize], as a pure function over data an IDE never
 * touched — which is what makes every policy rule in this module testable in milliseconds against
 * a plan literal, with no fixture and no platform.
 *
 * The rule has a sharp edge worth stating: the plan builder **must not "helpfully" pre-judge.** A
 * [SymbolOrigin.LIBRARY] symbol is reported as `LIBRARY` even when its package obviously matches
 * something the engine would care about. The moment the builder starts deciding, the decision
 * leaves the module that can be tested cheaply and moves into one that needs an IDE booted.
 *
 * @param text the snapped selection, verbatim — every offset below indexes into this string
 * @param occurrences everything the engine may act on, in document order
 * @param rootPackage the analysed file's root package (`com.acme` from `com.acme.web.Controller`),
 *   or `null` for the default package. Nothing here reads it yet; it is the evidence the
 *   internal-library prefix rule needs, and it is a fact about the file rather than a judgment.
 */
class SnippetPlan(
    val text: String,
    val occurrences: List<Occurrence>,
    val rootPackage: String? = null,
)

/**
 * Something in [SnippetPlan.text] the engine may act on, as a half-open range `[start, end)`.
 *
 * Ranges never overlap and never split a token: the plan builder snaps the selection outward to
 * whole-token boundaries before it walks anything.
 */
sealed class Occurrence {
    abstract val start: Int
    abstract val end: Int
}

/**
 * An identifier, together with everything known about the symbol it names.
 *
 * @param text the identifier exactly as it is written at this position
 */
class SymbolOccurrence(
    override val start: Int,
    override val end: Int,
    val text: String,
    val symbol: SymbolEvidence,
) : Occurrence()

/**
 * A literal — string, character or numeric.
 *
 * Reported but not yet acted on: literal handling is its own ticket. It is in the plan from the
 * start because the plan is a description of the snippet rather than a work list, and a description
 * that omitted the literals would be a lie about what the snippet contains.
 */
class LiteralOccurrence(override val start: Int, override val end: Int) : Occurrence()

/** A comment or javadoc block, whole. Reported but not yet acted on, for the reason above. */
class CommentOccurrence(override val start: Int, override val end: Int) : Occurrence()

/**
 * What the plan builder observed about one declared symbol.
 *
 * @param key the identity of the **declared symbol**, not of this occurrence. Two occurrences of
 *   one symbol carry one key however far apart they are — across the selection's gaps, and across
 *   files — which is what makes a rename consistent. Two *distinct* symbols never share a key, so a
 *   parameter shadowing a field is two keys and therefore two placeholders.
 * @param role what the symbol is in Java's grammar; decides the placeholder's prefix
 * @param origin where the symbol's declaring file lives
 * @param declaredName the symbol's own name, which is what a reverse mapping has to hand back
 * @param qualifiedName the symbol's fully-qualified name, for a symbol that has one — `com.acme` for
 *   the package segment written `acme`, and the binary name for a top-level class. The rule that
 *   passes a package's top-level segment through reads it and nothing else reads it at all: a
 *   qualified name with no dot in it is a top-level package. `null` wherever there is no such name,
 *   which is every local, parameter, member and anonymous class.
 * @param signature a method's parameter types, or `null`. Evidence, deliberately **not** part of
 *   [key]: overloads share a name in source, so they share a placeholder, and the engine collapses
 *   them by ignoring this. Reporting it anyway is what lets a later rule change its mind without
 *   the builder changing at all.
 * @param overrideRoots the roots of a method's override chain — what `findDeepestSuperMethods()`
 *   returns — and empty for everything that is not a method or overrides nothing. Two rules read
 *   it, and they read different halves: whether the name may be renamed at all reads the roots'
 *   [OverrideRoot.origin], and which placeholder the chain shares reads their [OverrideRoot.key].
 * @param accessor the field this method reads or writes, when it is a JavaBeans accessor of one.
 *   `null` otherwise, which includes every fluent accessor: `merchantId()` is deliberately not
 *   covered, because nothing in Java forces a fluent accessor's name to agree with its field's.
 */
class SymbolEvidence(
    val key: String,
    val role: SymbolRole,
    val origin: SymbolOrigin,
    val declaredName: String,
    val qualifiedName: String? = null,
    val signature: String? = null,
    val overrideRoots: List<OverrideRoot> = emptyList(),
    val accessor: AccessorEvidence? = null,
)

/**
 * One root of a method's override chain: a method that overrides nothing itself.
 *
 * A root rather than the immediate super, because both rules that read it are statements about the
 * whole chain. Keying an override chain by the *declaring* class split an interface from its
 * implementation, so the `@Override` no longer implemented anything; and a chain reaching a
 * framework type is name-constrained however many project classes sit in between.
 *
 * @param key the root method's own [SymbolEvidence.key]. All roots of one chain share the method's
 *   name, so ordering these orders their owners.
 * @param origin where the root's declaring file lives. Anything but [SymbolOrigin.IN_CONTENT] means
 *   the name is not ours to change.
 */
class OverrideRoot(val key: String, val origin: SymbolOrigin)

/**
 * A method's backing field, and the prefix its own name puts in front of that field's.
 *
 * Both are observations about source rather than judgments: the builder saw a method named
 * `getMerchantId` next to a field named `merchantId`, and it reports the pair. What follows from
 * that — `merchantId → field1` implying `getMerchantId() → getField1()` — is the engine's rule.
 *
 * @param fieldKey the backing field's [SymbolEvidence.key]. The field need not appear in the
 *   snippet at all: with Lombok the accessor has no declaration in source either, and a key is
 *   still enough to name the same symbol.
 * @param prefix the accessor prefix as it is written — `get`, `is` or `set`.
 */
class AccessorEvidence(val fieldKey: String, val prefix: String)

/**
 * Where the file declaring a symbol lives.
 *
 * The spine rule reads exactly one of these — [IN_CONTENT] — and preserves everything else. The
 * other three are still reported separately rather than collapsed into "not ours", because the
 * distinctions are facts, and later rules are built on them.
 */
enum class SymbolOrigin {
    /** The declaring file is project content: the symbol is the user's own code. */
    IN_CONTENT,

    /** A third-party library. Preserved: library names are what make a snippet answerable at all. */
    LIBRARY,

    /** The JDK. Preserved for the same reason, and told apart from [LIBRARY] because it is a fact. */
    JDK,

    /**
     * The reference did not resolve. Normal rather than exceptional — red or incomplete code
     * resolves to nothing, and the snippet a developer is debugging is the likely one.
     *
     * **Fails closed**, into an `Unknown` namespace of its own rather than into the type namespace.
     * A name nobody can vouch for is treated as the user's own, and unresolved names skew heavily
     * project-owned in any case: a missing import of your own class, a class half-written.
     */
    UNRESOLVED,
}

/**
 * What a symbol is, which is what its placeholder says it is.
 *
 * The prefixes are role-preserving on **read-back ergonomics**, not answer quality: an experiment
 * found five of six questions at parity across opaque, role-preserving and structure-hinting
 * schemes *and the untouched original*, so no scheme wins on answers. What a human then has to do
 * is map the AI's reply back onto real code by hand, and `Repository2` is far cheaper to map than
 * `Class7`.
 *
 * Every constant here is a statement about Java's grammar, which is why there is no `UNKNOWN`
 * among them: a name that did not resolve has no grammatical role to report, and the engine reads
 * [SymbolOrigin.UNRESOLVED] for that namespace instead. Whatever role a plan reports alongside that
 * origin is a value the builder had to invent, and nothing reads it.
 */
enum class SymbolRole(val placeholderPrefix: String) {
    /** A class, interface, enum, record — or a type parameter, which is a type by any other name. */
    TYPE("Type"),
    METHOD("method"),
    FIELD("field"),
    PARAMETER("param"),

    /**
     * **One segment of a package name**, never a whole qualified name: `com.acme.billing` is three
     * symbols, and the two under the root render as `pkg1.pkg2`.
     *
     * Segment-at-a-time is the grammar and it is also the point. Same-package versus
     * different-package is what package-private access, a sealed type's `permits` clause and every
     * "why can't this see that" question ride on, and one placeholder standing for a whole qualified
     * name destroys it.
     */
    PACKAGE("pkg"),

    /**
     * **An annotation type's attribute** — `name` in `@Column(name = …)`.
     *
     * A member of an annotation type is a method in the bytecode and an attribute everywhere it is
     * written, and it is written that a reader has to map back. It is its own role rather than
     * [METHOD] for that reason alone: the two are the same symbol, and `@Type1(attr2 = …)` is what
     * the reader sees.
     */
    ATTRIBUTE("attr"),

    /** A local variable, or a label: named, block-scoped, and invisible outside its own method. */
    LOCAL("local"),
}
