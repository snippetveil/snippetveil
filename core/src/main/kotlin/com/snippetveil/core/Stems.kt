package com.snippetveil.core

/**
 * **What a custom placeholder stem has to be — and the three things it may never be.**
 *
 * A rename in the preview replaces the *namespace* half of a placeholder and never the number:
 * `Type1` may become `FilterType1`, and it may never become `Filter`. The number is what makes the
 * output announce itself as anonymized, which `THREAT-MODEL.md` refuses to trade away — *"the
 * placeholders are numbered so that they announce themselves"* — and it is also what carries
 * injectivity, being drawn from a counter that is unique across the project's whole history.
 *
 * **The rule lives in `:core`, and the dialog mirrors it rather than owning it.** The engine falls
 * back to the default namespace for a stem it rejects, so the invariant does not depend on a text
 * field having been validated — the same reasoning that put the preserve rule in the engine rather
 * than in the preview. The dialog reads [StemRejection.message] so that what a user is told and
 * what the engine does are one statement rather than two that can drift.
 *
 * **Surrounding whitespace is not part of a stem**, and it is dropped here rather than at the
 * editor so that both sides judge the same string: a caller that never trimmed must not get a
 * different answer from one that did.
 *
 * `null` for a stem the engine will use **and for the empty one**: empty is not an error, it is the
 * way back to the default stem for the symbol's namespace.
 */
fun stemRejection(stem: String): StemRejection? {
    val trimmed = stem.trim()
    val codePoints = trimmed.codePoints().toArray()
    return when {
        codePoints.isEmpty() -> null
        !Character.isJavaIdentifierStart(codePoints.first()) -> StemRejection.NOT_AN_IDENTIFIER
        codePoints.any { !Character.isJavaIdentifierPart(it) } -> StemRejection.NOT_AN_IDENTIFIER
        Character.isDigit(codePoints.last()) -> StemRejection.ENDS_WITH_A_DIGIT
        trimmed in NAMESPACES -> StemRejection.RESERVED_NAMESPACE
        else -> null
    }
}

/**
 * **The stem to name a symbol with, or `null` when there is none this engine will use** — one
 * question with one answer, where *is it acceptable* and *is there one at all* are two.
 *
 * Both call sites want the second question: [stemRejection] answers `null` for the empty stem
 * because empty is the way back to the default, so an allocator asking only *was it rejected* would
 * mint the placeholder `1`. Spelled here so that no caller has to remember the pair.
 */
internal fun usableStem(stem: String): String? = stem.trim().takeIf { it.isNotEmpty() && stemRejection(it) == null }

/**
 * **The stem half of a placeholder** — `FilterType` out of `FilterType1`, `field` out of `field3`.
 *
 * Read from this side of the module boundary rather than from a dialog splitting the string itself,
 * for the reason a reversal reads a stated fact rather than parsing a key: *stem then number* is a
 * format this file owns, and a second reading of it elsewhere is one that goes stale the day the
 * format moves. It is unambiguous because a stem may not end in a digit — see
 * [StemRejection.ENDS_WITH_A_DIGIT] — so the digits at the end are the number and nothing else.
 */
fun stemOf(placeholder: String): String = placeholder.dropLastWhile(Char::isDigit)

/** **The number half of a placeholder**, as it is written. See [stemOf]. */
fun numberOf(placeholder: String): String = placeholder.takeLastWhile(Char::isDigit)

/**
 * Why a stem was refused, in the user's words.
 *
 * The message is carried here rather than written where it is shown, for the reason
 * [UnrestoredReason] carries its own: the engine decides, the dialog reports, and a sentence spelled
 * out on the reporting side is one that goes stale the day the rule moves.
 *
 * @param message what the editor says when it rejects what was typed
 */
enum class StemRejection(val message: String) {

    /**
     * **Not a Java identifier**, as the language defines one rather than as ASCII would — a stem is
     * pasted straight into source, so anything the compiler would not accept makes the snippet stop
     * compiling, which is a fidelity loss the reader has no way to attribute.
     */
    NOT_AN_IDENTIFIER("A name has to be a Java identifier: letters, digits, _ or $, not starting with a digit."),

    /**
     * **Ends with a digit**, which is the one rule that is about injectivity rather than about
     * Java. Stem `Filter2` with number 1 and stem `Filter` with number 21 both render `Filter21` —
     * two symbols, one word, exactly what reverse mapping forbids. Banning the trailing digit makes
     * *stem then number* unambiguous by construction rather than by a check somewhere downstream.
     */
    ENDS_WITH_A_DIGIT("A name cannot end with a digit: the number that follows it would run into it."),

    /**
     * **A namespace this engine mints from.** Injectivity is untouched — the counter is shared, so
     * `Unknown7` is still exactly one symbol — but the *signal* is not: a placeholder is read as a
     * claim about what it stands for, and `Unknown1` is the load-bearing one, telling the model the
     * IDE could not resolve a name. A stem spelling it would let a resolved symbol claim otherwise,
     * which is the misinformation the `Unknown` namespace exists to prevent, and `str` would let a
     * symbol pass for a redacted literal.
     */
    RESERVED_NAMESPACE("That is one of SnippetVeil's own placeholder names, so it would say something untrue."),
}

/**
 * **Every namespace this engine mints from**, derived from the roles rather than written out, so
 * that a namespace added later cannot become a stem by having been forgotten here. It is the same
 * derivation the reversal's shape recogniser uses, and for the same reason.
 *
 * A derived accessor's prefixed form — `getField` — is in it too: a field stemmed `getField` would
 * read as an accessor of a field nobody can find.
 */
private val NAMESPACES: Set<String> =
    (SymbolRole.entries.map { it.placeholderPrefix } +
        UNKNOWN_PREFIX +
        LITERAL_PREFIX +
        AccessorEvidence.PREFIXES.flatMap { prefix ->
            SymbolRole.entries.map { derivedAccessorPlaceholder(prefix, it.placeholderPrefix) }
        }).toSet()
