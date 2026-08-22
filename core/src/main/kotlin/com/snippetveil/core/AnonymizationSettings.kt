package com.snippetveil.core

/**
 * The choices one invocation runs under.
 *
 * One rule governs everything that will be added here, and it is worth carrying next to the type
 * rather than in a document: **persistent settings may only ever increase anonymization. Any
 * reduction is per-invocation and visible in the preview.** A reduction that can be set once and
 * forgotten silently leaks on every paste after it.
 *
 * @param preservedUnknowns the symbol keys of unresolved names to emit verbatim, from
 *   [AnonymizationResult.unknowns]. **Per-invocation only, never persisted**, by the rule above —
 *   this is the one deliberate fail-open in the product, and it exists to buy off the case where a
 *   typo'd JDK call is hidden behind a placeholder and the snippet stops being answerable.
 *
 *   **A key naming anything but an unresolved symbol is ignored**, and that is a rule rather than
 *   defensiveness: the override was given to unresolved names specifically and it must not creep.
 *   One that reached resolved symbols would be the free-text preserve list this design already
 *   rejected, built out of keys instead of text, and it would put a reduction on the spine rule.
 *   Enforcing it here rather than in the dialog puts it where creep cannot happen by adding a
 *   checkbox.
 * @param keepComments whether comments and javadoc survive into the output. **Off by default, and
 *   per-invocation only** — a later ticket puts it in the preview as a tick that resets to *strip*
 *   on every invocation, and it is **never persisted**, by the rule above.
 *
 *   Comments are the largest single domain leak in the product. Renaming symbols does essentially
 *   nothing to prose: an experiment measured **28/29/29 distinct domain words surviving** across
 *   three naming schemes against **36** in the original — enough to reconstruct the business domain
 *   — and `// reconcile against the merchant ledger` sitting two lines above `field1` is incoherent
 *   on its face. Under an adversary model whose primary threat is retention, a reduction that can be
 *   ticked once and forgotten is the severe failure mode: **one tick set a year ago silently leaks
 *   the domain on every paste since.**
 *
 *   **One flag, not two.** The leak is prose, and prose is in comments and javadoc alike; two knobs
 *   would be a settings surface with no use case behind it.
 *
 *   Rejected on the way here, so it need not be re-litigated: a persistent toggle plus a README
 *   non-goal (*"we told you"* is a weak answer for a tool whose moat is trust); a persistent toggle
 *   plus a preview banner (a warning shown on every invocation stops being read within a week); and
 *   removing the control altogether, which hands the developer who genuinely always wants comments a
 *   forced loss with no escape hatch.
 */
class AnonymizationSettings(
    val preservedUnknowns: Set<String> = emptySet(),
    val keepComments: Boolean = false,
) {
    companion object {
        /** Everything anonymized that can be: no reduction of any kind. */
        val DEFAULTS: AnonymizationSettings = AnonymizationSettings()
    }
}

/**
 * The placeholders already handed out, as they stood when this invocation started.
 *
 * Core takes the ledger as a **snapshot in** and returns a [LedgerDelta] **out**, rather than
 * holding an injected store it mutates. The reason is a preview that gets cancelled: under a
 * mutating store, allocation happens while the preview renders, so a cancelled preview would burn
 * numbers — and a burnt number is not recoverable, because the invariant that makes reverse mapping
 * work is that no two symbols ever render to the same placeholder. A snapshot in and a delta out
 * makes "cancelled" mean *nothing happened*, with no compensating logic to get wrong.
 *
 * @param placeholders symbol key -> placeholder, for symbols already named
 * @param nextNumber the next number the counter will hand out
 */
class LedgerSnapshot(
    val placeholders: Map<String, String>,
    val nextNumber: Int,
) {
    companion object {
        /** No symbol has been named yet, and numbering starts at 1. */
        val EMPTY: LedgerSnapshot = LedgerSnapshot(emptyMap(), nextNumber = 1)
    }
}

/**
 * What one invocation would add to the ledger — returned, never applied. The caller commits it at
 * the single point where the invocation succeeds, or drops it and nothing happened.
 *
 * @param placeholders the symbol keys named during this invocation, and what they were named
 * @param nextNumber where the counter stands afterwards. Higher than
 *   `snapshot.nextNumber + placeholders.size` when a number was skipped to avoid colliding with a
 *   name that survives into the output.
 */
class LedgerDelta(
    val placeholders: Map<String, String>,
    val nextNumber: Int,
) {
    val isEmpty: Boolean get() = placeholders.isEmpty()
}

/**
 * What one invocation produced. Nothing here has been committed anywhere; the caller decides.
 *
 * @param text the anonymized snippet, ready for the clipboard
 * @param mapping placeholder -> the real name it stands for. **Injective**, which is the whole
 *   point: a reverse mapping is well-defined only if no two symbols render to one placeholder, and
 *   the AI's reply carries no scope context to disambiguate with if they did.
 * @param counts the distinct names in the snippet, partitioned by what became of them
 * @param comments what the strip removed, split by parse verdict
 * @param unknowns every name that failed to resolve, in document order of first occurrence
 * @param delta what to commit, if the caller gets as far as committing
 */
class AnonymizationResult(
    val text: String,
    val mapping: Map<String, String>,
    val counts: NameCounts,
    val comments: CommentCounts,
    val unknowns: List<UnknownName>,
    val delta: LedgerDelta,
)

/**
 * **What the strip removed, split by [CommentVerdict].**
 *
 * Reported so that removal is never silent. A comment stripped is a loss the anonymized output gives
 * no sign of: the text that comes back is clean, compiles, and reads as ordinary code, which is the
 * quietest failure the design has. The answer is disclosure at the point of use — the tool says what
 * it removed, and the human, who can still see the original, judges whether it mattered.
 *
 * Split rather than totalled, because the split is the part a user can act on: *`2 comments
 * stripped`* is a number, *`2 comments stripped, 1 of them commented-out code`* is a reason to open
 * the preview and tick the box.
 *
 * Both are zero when this invocation kept its comments. This counts what was **removed**, not what
 * was there — a count of comments in the snippet would be a fact about the input, and the thing a
 * user needs to know is what is missing from the output.
 *
 * @param prose comments whose body does not parse as code
 * @param code comments whose body does
 */
class CommentCounts(val prose: Int, val code: Int) {

    /** How many comments the strip removed, which is what the balloon says. */
    val stripped: Int get() = prose + code
}

/**
 * One name the IDE could not resolve, and what this invocation did with it.
 *
 * Reported as a list rather than only as a count because the count alone would name a surface the
 * user cannot act on. The preview dialog's per-item **Preserve** is built on exactly these three
 * fields: [name] to show, [placeholder] to show it against, and [key] to hand back through
 * [AnonymizationSettings.preservedUnknowns].
 *
 * @param key the symbol key, which is what a preserve override is expressed in
 * @param name the name as it is written in the snippet
 * @param placeholder what it renders as, or `null` when this invocation preserved it and its real
 *   name was emitted. Null rather than the name itself: a preserved item has no placeholder, and
 *   saying it stands for itself would put a row in the mapping table that maps nothing.
 */
class UnknownName(
    val key: String,
    val name: String,
    val placeholder: String?,
)

/**
 * The balloon's three numbers, counted in **distinct names** rather than occurrences — "14 names
 * replaced" is a claim about names.
 *
 * They **partition** the snippet's named symbols: every distinct name is counted once, so the three
 * add up to what is in the snippet and nothing is counted twice.
 *
 * They are counted by **outcome** — replaced, or surviving verbatim — rather than by the origin the
 * plan reported, and that is a correction the name-constrained rules forced rather than a
 * preference. A project method that keeps its real name because it implements `Runnable` is
 * project-owned *evidence* and a preserved *name*, and [replaced] is a claim about what is on the
 * clipboard. [unknown] is the exception and stays evidence-shaped, because it reports what the IDE
 * could not resolve rather than what became of it.
 *
 * All three are mechanism, and stay that way: no "safe to paste", no "sanitized", no adjective. A
 * category claim is one the tool is not in a position to make, and it is the claim a user would act
 * on.
 *
 * @param replaced distinct names that became placeholders
 * @param unknown distinct names the IDE could not resolve. **An information-level number, never a
 *   warning.** Under fail-closed an `Unknown` *was* anonymized, so it is a quality risk and never a
 *   privacy one — styling it as an alarm would train the user to read our alarm as "this might have
 *   leaked", which is precisely the inversion to avoid. It counts what did not resolve, so it is
 *   unmoved by a per-invocation preserve: the override changes what was emitted, not what the IDE
 *   knew.
 * @param preserved distinct names that survive verbatim — the JDK and third-party libraries, and
 *   the project's own names that Java forbids from being anything else
 */
class NameCounts(val replaced: Int, val unknown: Int, val preserved: Int)
