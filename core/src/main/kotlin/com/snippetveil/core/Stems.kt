package com.snippetveil.core

/**
 * **What a custom placeholder stem has to be — and the two things it may never be.**
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
 * `null` for a stem the engine will use **and for the empty one**: empty is not an error, it is the
 * way back to the default stem for the symbol's namespace.
 */
fun stemRejection(stem: String): StemRejection? = when {
    stem.isEmpty() -> null
    !Character.isJavaIdentifierStart(stem.first()) -> StemRejection.NOT_AN_IDENTIFIER
    !stem.all { Character.isJavaIdentifierPart(it) } -> StemRejection.NOT_AN_IDENTIFIER
    stem.last().isDigit() -> StemRejection.ENDS_WITH_A_DIGIT
    else -> null
}

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
}
