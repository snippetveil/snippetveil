package com.snippetveil.core

/**
 * **The two disclosure notices, and the rule that closes the list at two.**
 *
 * Signal-loss is a third failure mode, and it is the one with no artifact at all. A leak leaves
 * evidence in the output and a broken snippet leaves a compiler error; a *lifted-out defect* leaves
 * nothing. The text is clean, it compiles, it reads as ordinary code, and the AI answers accurately
 * about a snippet the bug is no longer in — so the user gets a confident, well-formed, **wrong**
 * answer with nothing in front of them hinting at it. That is the quietest failure in the design.
 *
 * **The response is disclosure, not preservation.** The tool says what it removed at the point of
 * use, and the human — who can still see the original in the editor behind it — judges whether it
 * mattered. Preservation was rejected because every mechanism for it reopens a fail-open path, and
 * refusal because it would refuse exactly the snippets users most want help with.
 *
 * ### The trigger test
 *
 * > **A fidelity notice exists iff the loss is invisible in the anonymized output.**
 *
 * That is what closes the list rather than letting it grow with every future transform. Literals,
 * `Unknown` symbols and preserved symbols are all *loudly* visible — `str1` and `Unknown2` are in
 * the text and every one of them is a row in the preview — so none of them gets a notice however
 * large the loss is. **Only two losses are invisible, so exactly two ship.**
 *
 * A third would have to clear the same bar, and the two that were argued for and lost are worth
 * recording. Injecting a machine comment into the clipboard so the AI sees a destroyed coincidence
 * costs no privacy, but *"these two share a name"* is a loud prompt: the model will very likely
 * report shadowing as *the* finding regardless of the real defect, which is a confident false
 * positive manufactured on purpose — and it pushes the tool past a descriptive-substitution claim
 * into annotating code the user did not write. Near-miss (edit-distance) detection for typo'd
 * duplicates is inference, it fires on legitimately similar names, and **noisy notices train the
 * user to ignore all of them**; typo'd-duplicate bugs stay a stated residual gap instead.
 *
 * ### Where they are said
 *
 * Both ride the **copy balloon** as well as the preview. `Copy Anonymized` is the fast path and has
 * no dialog, so a preview-only notice would never fire for the users who never open the preview —
 * and the disclosure rule would collapse for exactly the people it was written for. That is why the
 * sentence is built here, in the module both surfaces already read, rather than twice.
 *
 * ### The rule they follow, which is the opposite of the counts'
 *
 * **Nothing is said when nothing was lost.** The counts beside these are shown every time including
 * the zeroes, because a number that appeared only when it fired would make its absence unreadable.
 * A *notice* is a sentence rather than a number: one that fired on every invocation is one nobody
 * reads on the invocation where it matters.
 *
 * @return zero, one or two sentences, flattened names first
 */
fun AnonymizationResult.fidelityNotices(): List<String> = listOfNotNull(
    flattened.takeIf { it.isNotEmpty() }?.let(::flattenedNamesNotice),
    comments.takeIf { it.stripped > 0 }?.let(::strippedCommentsNotice),
)

/**
 * **One source name that two or more of a snippet's renamed symbols shared, and the placeholders it
 * flattened into.**
 *
 * Injectivity is load-bearing — a reverse mapping is well-defined only if no two symbols render to
 * one placeholder — so **distinct symbols get distinct placeholders and name coincidence is
 * destroyed by construction.** No preservation rule saves it without unpicking the never-recycle
 * invariant, which is the trade this whole design is built on. So the coincidence is disclosed
 * instead of kept.
 *
 * @param placeholders what those symbols render as, in first-occurrence order — and **the only
 *   thing carried here, because the shared name itself may not leave the machine.** The notice can
 *   say everything it needs to in placeholders: *"param1 and field1 were the same name"* is the
 *   whole fact, and it costs no privacy to say out loud on a balloon.
 */
class FlattenedName(val placeholders: List<String>)

/**
 * **Every source name two or more renamed symbols shared — by an exact group-by, never by
 * resemblance.**
 *
 * Exactness is the rule rather than an implementation detail: it is what keeps this out of the
 * inference business, and it is why the notice can be trusted enough to be read. The group-by
 * covers shadowing, field hiding and same-name-different-scope without knowing what any of those
 * are — each is two symbols and one name, which is all this asks.
 *
 * Read off the rows rather than off the symbols, which settles two edges by construction:
 *
 *  - **A row is a placeholder**, so two symbols Java forces to share one — an override and its root
 *    — are one row and no coincidence. They agree in the output, so nothing was destroyed.
 *  - **A row with no placeholder is a name emitted verbatim**, and it is skipped. That is not a
 *    scope decision but the same no-real-names rule read from the other end: a preserved symbol is
 *    written out under its own name, so a notice pairing it with a placeholder would print that
 *    name. Every name this can reach is a name it may print, and it may print none.
 *
 * A [MappedKind.LITERAL] row is not a symbol and is excluded outright. Two occurrences of one string
 * are two rows standing for one value — the mapping working, rather than a coincidence being
 * destroyed — and string-literal text is a stated, accepted loss with no notice and no affordance:
 * the preview shows every one of them, so the loss is not invisible and the trigger test never
 * fires.
 */
internal fun flattenedNamesIn(names: Collection<MappedName>): List<FlattenedName> = names
    .filter { it.placeholder != null && it.kind != MappedKind.LITERAL }
    .groupBy { it.original }
    .values
    .filter { it.size > 1 }
    .map { group -> FlattenedName(group.mapNotNull { it.placeholder }) }

/** **Notice 1** — *`param1 and field1 were the same name`*, one clause per destroyed coincidence. */
private fun flattenedNamesNotice(flattened: List<FlattenedName>): String =
    flattened.joinToString("; ") { "${listing(it.placeholders)} were the same name" }

/**
 * **Notice 2** — *`2 comments stripped, 1 of them commented-out code`*: the count, and the half of
 * it a user can act on.
 *
 * The count already existed and the split is what makes it actionable: *`2 comments stripped`* is a
 * number, and the keep-comments tick is already sitting in the preview. Disclosure plus that tick
 * closes the loop with no new leak surface and no reopened decision.
 *
 * It is here rather than on [CommentCounts], which is the type it reads and nothing else: the two
 * notices are **one closed list**, and half of that list living on its own count type is half of it
 * a reader looking for *the notices* would not find. What [CommentCounts] owns is the split; what
 * this file owns is that the split is disclosed and how the two disclosures agree with each other.
 *
 * **The split is stated whenever the notice is, zero included** — *`2 comments stripped, 0 of them
 * commented-out code`* — and that is the point rather than an oversight. A strip that took no code
 * is the case where the user does **not** need to open the preview, and a clause that vanished on
 * zero would leave them to work that out from its absence. Dropping it also lands the commonest
 * strip there is, all-prose, back on the bare *`2 comments stripped`* the split exists to replace.
 *
 * That is the opposite rule from the notice around it, and the two are not in tension: **whether
 * there is a notice** is conditional, because a snippet that lost nothing has nothing to say; what
 * a notice that exists **says** is not, because a number missing from a sentence is unreadable in
 * exactly the way a missing count is.
 *
 * One sentence shape rather than a branch per case. *`1 comment stripped, 1 of them commented-out
 * code`* is a shade stiff read aloud, and it is the trade taken knowingly: prose that varied with
 * the numbers would be copy this ticket did not buy, and a disclosure a user has to re-read to see
 * whether it is the same sentence as last time is worse than a stiff one they can scan.
 */
private fun strippedCommentsNotice(comments: CommentCounts): String =
    "${comments.stripped} ${if (comments.stripped == 1) "comment" else "comments"} stripped" +
        ", ${comments.code} of them commented-out code"

/**
 * `a and b`, `a, b and c` — an English list, because a notice is read as a sentence.
 *
 * **Never called with fewer than two**, and there is no arm for it: a group of one is not a
 * coincidence and [flattenedNamesIn] does not emit one. An arm handling it would be dead code
 * standing where a reader would take it for a case that happens.
 */
private fun listing(placeholders: List<String>): String =
    placeholders.dropLast(1).joinToString(", ") + " and " + placeholders.last()
