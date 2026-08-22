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
 * A literal, and everything the coverage rule needs in order to decide what becomes of it.
 *
 * **A literal is not passive text; it is a domain carrier, like a comment.** An experiment counted
 * 38 identifiable literals in the original snippets and blanket replacement took that to 0 while
 * answering 5 of 6 questions at parity — so preserving literals verbatim is not the conservative
 * default, it is a live leak the size of the comment leak.
 *
 * @param kind what the literal is in Java's grammar, which is what decides whether it is touched at
 *   all: a number is shape rather than domain, and `3`, `443` and `30_000` tell a reviewer about
 *   retry counts, ports and timeouts, which is frequently the bug
 * @param contentStart where the literal's own text starts — after the opening delimiter, and after
 *   the line terminator that opens a text block
 * @param contentEnd where it ends, before the closing delimiter. **Only the content is ever
 *   rewritten**, which is how the replacement preserves the literal's syntactic form for free: a
 *   text block stays a text block, and an escaped literal stays escaped, because no delimiter and
 *   no escape is ever written by this module. A literal with no delimiters — a number — reports its
 *   whole text as its content, which is the truth about a number and reaches no rule that acts.
 * @param references the PSI references the literal carries, in document order, each over the part
 *   of the literal it names. Reported per reference rather than per literal because
 *   `JavaClassReferenceSet` yields one reference per dotted segment, and the coverage rule is a
 *   statement about the gaps between them.
 */
class LiteralOccurrence(
    override val start: Int,
    override val end: Int,
    val kind: LiteralKind,
    val contentStart: Int,
    val contentEnd: Int,
    val references: List<LiteralReference> = emptyList(),
) : Occurrence()

/**
 * What a literal is, in Java's grammar — never a judgment about what it is *for*.
 *
 * Only [STRING] and [TEXT_BLOCK] carry text a project can put a domain word in, and they are the
 * only two anything acts on. The other four are enumerated rather than collapsed into "not a
 * string" because each is a fact, and a fact is what crosses this seam.
 */
enum class LiteralKind {
    /** `"merchantId"` — the shape the whole of this module's literal rule is about. */
    STRING,

    /**
     * `"""…"""`. Told apart from [STRING] because the two are delimited differently, and the
     * replacement lands inside the delimiters: `"""…"""` collapsed to `"str1"` is malformed-looking
     * in a way that reads as a bug in this tool rather than as anonymization.
     */
    TEXT_BLOCK,

    /** `'x'`. **Preserved**: a single character is shape, not domain. */
    CHARACTER,

    /** `3`, `443`, `30_000`. **Preserved**: see [CHARACTER], and see the retry count that is the bug. */
    NUMBER,

    /** `true`, `false`. Preserved, and there is nothing in one to conceal. */
    BOOLEAN,

    /** `null`. Preserved, for the reason [BOOLEAN] is. */
    NULL,
}

/**
 * One resolved reference inside a literal, as a half-open range `[start, end)` into
 * [SnippetPlan.text], together with what it resolves to.
 *
 * **This is the whole of what a literal's rewrite may be driven by.** Text-driven rewriting is
 * exactly the mechanism that manufactured a confident, false finding in the experiment: a
 * pattern-rewritten foreign-key literal went out of sync with the field its symbol renamed, and the
 * reviewer reported an inconsistency that does not exist upstream. A literal carrying references
 * renames **in lockstep with the symbols those references name**, and by nothing else.
 *
 * A reference that failed to resolve is reported like any other, carrying [SymbolOrigin.UNRESOLVED]
 * evidence — the builder does not get to drop it, because dropping it would be a judgment. What the
 * engine does with it is the engine's rule, and the rule is that it covers nothing.
 */
class LiteralReference(val start: Int, val end: Int, val symbol: SymbolEvidence)

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
 *   the package segment written `acme`, and `com.acme.Payment.Status` for a nested class. The rule
 *   that passes a package's top-level segment through reads it and nothing else reads it at all: a
 *   qualified name with no dot in it is a top-level package. `null` wherever there is no such name,
 *   which is every local, parameter, method, field, type parameter and anonymous class.
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
    /** A class, interface, enum or record. */
    TYPE("Type"),

    /**
     * **A type parameter** — `T`, `E`, `REQ`.
     *
     * Its own kind rather than a [TYPE], and anonymized like any other project-owned name. `<T>`
     * carries no domain and `<REQ extends MerchantRequest>` does, and there is no rule that keeps
     * the first and replaces the second: preserving by name length is inspecting the text, which is
     * the thing this product does nowhere. So both go, and `<T1 extends Type2>` is what a reader
     * gets — where `T1` says *a type parameter* as plainly as `Type2` says *a type*.
     */
    TYPE_PARAMETER("T"),
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
     * **An annotation type** — `@interface`, which is a type but not one a reader reads as one.
     *
     * `@Anno1(attr2 = …)` says *an annotation you cannot see the name of* in a way `@Type1(…)` does
     * not, and telling the two apart is free: an annotation type is a distinct declaration in Java's
     * grammar rather than a judgment about what a class is for.
     */
    ANNOTATION("Anno"),

    /**
     * **An annotation type's attribute** — `name` in `@Column(name = …)`.
     *
     * A member of an annotation type is a method in the bytecode and an attribute everywhere it is
     * written, and it is written that a reader has to map back. It is its own role rather than
     * [METHOD] for that reason alone: the two are the same symbol, and `@Type1(attr2 = …)` is what
     * the reader sees.
     */
    ATTRIBUTE("attr"),

    /** A local variable: named, block-scoped, and invisible outside its own method. */
    LOCAL("local"),

    /**
     * **A label** — `outer` in `break outer;`.
     *
     * Project-owned by construction: a label is declared in the method it is jumped from and
     * nowhere else, so there is no origin to ask about and no library that could own one. Its own
     * kind rather than a [LOCAL] because a label is not a variable and `break local7;` reads as one
     * — the prefix is what tells a reader which of the two a name was, and that is the only thing
     * the prefixes are for.
     */
    LABEL("label"),
}
