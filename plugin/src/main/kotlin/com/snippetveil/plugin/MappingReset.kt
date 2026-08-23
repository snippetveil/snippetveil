package com.snippetveil.plugin

import com.intellij.openapi.ui.Messages
import com.intellij.openapi.project.Project

/**
 * **The single destructive operation in the product**, and the confirmation that has to precede it.
 *
 * Everything else SnippetVeil writes is append-only: a placeholder is never rewritten, a number is
 * never recycled, and nothing is pruned. This is the one place a user can end that, and the reason
 * it exists at all is that the alternative — a mapping that can only ever grow — leaves a person who
 * wants their machine to stop holding their employer's vocabulary with no answer but deleting a file
 * they had to find first.
 *
 * ### A button on the settings page, and deliberately not an action
 *
 * *Explicit* is better served by a button standing next to the numbers a person needs in order to
 * decide — how many entries there are, and where the file is — than by a menu item sitting one row
 * from `Copy Anonymized`. The accepted cost is stated rather than discovered: **it is not reachable
 * from Find Action**, so a user who knows the feature by name has to open Settings to reach it.
 * `ActionRegistrationTest` pins the four ids this plugin registers, so an id acquired here goes red.
 *
 * ### The confirmation states the consequence rather than asking for confidence
 *
 * *"Are you sure?"* asks for a feeling about an operation whose effect is invisible: nothing on
 * screen changes, no file the user is looking at moves, and the damage shows up days later in a
 * reply that will not decode. So [MESSAGE] says what stops working, in the tense it will be
 * discovered in.
 */
internal object MappingReset {

    /** The dialog's title, and the button that carries it out — named for what it does, not `OK`. */
    const val TITLE: String = "Reset Mappings"

    /**
     * **What the user is agreeing to, said in the direction they will meet it.**
     *
     * Three facts, and each is here because leaving it out would leave a false impression rather
     * than merely a shorter message:
     *
     *  - **Existing anonymized snippets become undecodable.** This is the consequence, and it is the
     *    first line because it is the whole decision.
     *  - **Numbering starts again.** It is the mechanism behind the first line and it is the part a
     *    reader can otherwise talk themselves out of — *surely `Type1` still means what it meant* —
     *    and after a reset it does not.
     *  - **The prefixes stay.** The one thing that is *not* destroyed, said out loud, because a user
     *    who has curated a prefix list would otherwise have to guess, and guessing wrong in the
     *    cautious direction means never pressing the button they wanted.
     */
    const val MESSAGE: String =
        "Existing anonymized snippets become undecodable. A reply that comes back holding " +
            "Type1 can no longer be turned back into the name it stood for, here or anywhere else.\n\n" +
            "This project's placeholders start again from 1, so the same placeholder will later stand " +
            "for a different name.\n\n" +
            "The internal-library prefixes on this page are kept."

    /**
     * Asks, and resets if the answer is yes.
     *
     * The two halves are separate methods and this one is what the button calls: [reset] is the
     * operation and is worth testing without a dialog in front of it, and a user pressing Cancel has
     * to leave *nothing* changed, which is easiest to be sure of when the only writer runs after the
     * only question.
     */
    fun confirmAndReset(project: Project) {
        if (confirmed(project)) reset(project)
    }

    /**
     * **Clears the mapping and the sidecar, and nothing else.**
     *
     * Two stores, one operation — see [PlaceholderSidecar.clear] for why leaving the window behind
     * would reset the half nobody worries about. What it does *not* touch is
     * [InternalLibrarySettings]: the prefix list is configuration a person curated, it is not part of
     * any reversal, and destroying it here would be an unrelated loss buried inside a confirmed one.
     * That the three are separate components is what makes this a fact about the code rather than a
     * special case in it.
     */
    fun reset(project: Project) {
        PlaceholderLedger.getInstance().clear(project)
        PlaceholderSidecar.getInstance(project).clear()
    }

    private fun confirmed(project: Project): Boolean =
        Messages.showYesNoDialog(project, MESSAGE, TITLE, TITLE, CANCEL, Messages.getWarningIcon()) == Messages.YES

    private const val CANCEL = "Cancel"
}
