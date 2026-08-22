package com.snippetveil.core

/**
 * The choices one invocation runs under.
 *
 * Empty today, and that is the honest state of it: every control the product will grow — the
 * per-invocation preserve set, the keep-comments tick, the internal-library prefix list — arrives
 * with the rule that reads it. It exists now because it is part of the seam [anonymize] is
 * specified with, and adding a parameter to that signature later would touch every caller.
 *
 * One rule governs everything that will be added here, and it is worth carrying next to the type
 * rather than in a document: **persistent settings may only ever increase anonymization. Any
 * reduction is per-invocation and visible in the preview.** A reduction that can be set once and
 * forgotten silently leaks on every paste after it.
 */
class AnonymizationSettings {
    companion object {
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
 * @param counts what the balloon reports
 * @param delta what to commit, if the caller gets as far as committing
 */
class AnonymizationResult(
    val text: String,
    val mapping: Map<String, String>,
    val counts: NameCounts,
    val delta: LedgerDelta,
)

/**
 * The balloon's two numbers, counted in **distinct names** rather than occurrences — "14 names
 * replaced" is a claim about names.
 *
 * Both are mechanism, and stay that way: no "safe to paste", no "sanitized", no adjective. A
 * category claim is one the tool is not in a position to make, and it is the claim a user would act
 * on.
 */
class NameCounts(val replaced: Int, val preserved: Int)
