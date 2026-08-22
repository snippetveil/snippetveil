package com.snippetveil.trust

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **Keeping comments is a reduction, so it is never persisted.**
 *
 * One rule governs every setting this product will ever have: *persistent settings may only ever
 * increase anonymization; any reduction is per-invocation and visible in the preview.* Under an
 * adversary model whose primary threat is retention — the snippet outliving the conversation in a
 * corpus, a log, a breach or a subpoena — the forgotten-reduction failure mode is the severe one:
 * **one tick set a year ago silently leaks the domain on every paste since.**
 *
 * That rule is a claim about code that does not exist yet, which is exactly why it is a check rather
 * than a paragraph. Today this repository ships no persistent state at all, so the interesting half
 * of this file is [the rule proves it can fail][`the rule tells a persisted reduction from a
 * persisted increase`] — pointed at fixtures written to be flagged and not flagged, the same habit
 * every other check in this repository follows.
 *
 * It reads bytecode off the same [SHIPPED_CLASSES] the architecture rules do, so *what ships* has
 * one definition here rather than two that can drift.
 */
class CommentRetentionIsNeverPersistedTest {

    /**
     * A rule that matches nothing passes, so what it matched is asserted first — and this one has
     * something to find today: the flag exists, on exactly one class, and that class is the
     * per-invocation settings object.
     *
     * **This is the load-bearing assertion while there is no persistence to check.** It fails the
     * moment `keepComments` is declared anywhere else — on a service, on a state bean, on a
     * component — whether or not that class has yet been wired up to be persisted.
     */
    @Test
    fun `keeping comments is declared on the per-invocation settings and nowhere else`() {
        val declaring = SHIPPED_CLASSES.filter { it.declaresCommentRetention() }.map { it.name }

        assertEquals(
            listOf("com.snippetveil.core.AnonymizationSettings"),
            declaring,
            "keeping comments is a per-invocation reduction and belongs to one class only",
        )
    }

    /**
     * The per-invocation settings object is not itself something the platform can persist. It has no
     * persistence machinery on it at all — which is what makes *"it is never persisted"* a property
     * of the type rather than a discipline about how the type is used.
     */
    @Test
    fun `the per-invocation settings are not a state holder`() {
        val settings = SHIPPED_CLASSES.single { it.name == "com.snippetveil.core.AnonymizationSettings" }

        assertTrue(!settings.isStateHolder()) {
            "AnonymizationSettings carries persistence machinery, so a reduction could be written to disk"
        }
    }

    /** The rule itself, over everything that ships. Vacuous today, and the assertion above says so. */
    @Test
    fun `nothing shipped persists a reduction`() {
        assertEquals(
            emptyList<String>(),
            persistedReductionsIn(SHIPPED_CLASSES),
            "a persistent setting may only ever increase anonymization",
        )
    }

    /**
     * The rule, pointed at code written to violate it — and at code written to look like a violation
     * and not be one.
     *
     * The second half is not decoration. Banning persistence outright would be the easy rule and the
     * wrong one: the spine rule permits a persistent setting and forbids only a persistent
     * *reduction*, so a check that failed on [PersistsAnIncrease] would be the kind of noise that
     * teaches people to suppress a check.
     */
    @Test
    fun `the rule tells a persisted reduction from a persisted increase`() {
        val reduction = ClassFileImporter().importClasses(
            PersistsCommentRetention::class.java,
            PersistsCommentRetention.Settings::class.java,
        )
        val increase = ClassFileImporter().importClasses(
            PersistsAnIncrease::class.java,
            PersistsAnIncrease.Settings::class.java,
        )

        assertEquals(
            listOf(PersistsCommentRetention.Settings::class.java.name),
            persistedReductionsIn(reduction),
            "the rule did not flag a state class carrying comment retention",
        )
        assertEquals(
            emptyList<String>(),
            persistedReductionsIn(increase),
            "the rule flagged a persistent setting that only increases anonymization",
        )
    }

    /**
     * Every class in [classes] that a state holder would write to disk and that carries comment
     * retention.
     *
     * **The state class, not only the component**, because that is the shape the mistake takes:
     * nobody puts the flag on the `PersistentStateComponent` itself, they put it on the plain bean
     * the platform serializes. So the surface examined is a state holder together with everything it
     * directly depends on — which is where `getState()`'s return type is, whatever it is called.
     *
     * The stated limit: a state class outside `com.snippetveil` is not read, because it is not read
     * *anywhere* — `assertNothingThirdPartyIsShipped` means there is no such class to find.
     */
    private fun persistedReductionsIn(classes: JavaClasses): List<String> = classes
        .filter { it.isStateHolder() }
        .flatMap { holder -> listOf(holder) + holder.directDependenciesFromSelf.map { it.targetClass } }
        .filter { it.packageName.startsWith("com.snippetveil") }
        .filter { it.declaresCommentRetention() }
        .map { it.name }
        .distinct()

    /**
     * Whether the platform can write this class to disk: a `PersistentStateComponent`, or anything
     * carrying `@State`. Either is enough on its own — the annotation names the file, the interface
     * produces the content, and a class with one and not the other is a class mid-way through being
     * given both.
     */
    private fun JavaClass.isStateHolder(): Boolean =
        allRawInterfaces.any { it.name == PERSISTENT_STATE_COMPONENT } ||
            annotations.any { it.rawType.name == STATE_ANNOTATION }

    /**
     * Whether this class declares comment retention, under any spelling a Kotlin `val`, a Java field
     * or an accessor pair produces for that one name.
     *
     * Matched on the name rather than on the type, because a `Boolean` says nothing: what makes this
     * one a reduction is what it means, and the name is where the meaning is written.
     *
     * **A rename of the flag does not slip past this, and the reason is worth stating**, because it
     * is not obvious: matching one spelling would be a weak rule on its own, but the assertion above
     * requires the name to be *found*, on exactly one class. Rename it to `retainComments` and that
     * assertion goes red with the whole list in the message. The rule fails closed, and the person
     * doing the renaming is the one who reads why.
     */
    private fun JavaClass.declaresCommentRetention(): Boolean =
        fields.any { it.name.contains(KEEP_COMMENTS, ignoreCase = true) } ||
            methods.any { it.name.contains(KEEP_COMMENTS, ignoreCase = true) }
}

private const val KEEP_COMMENTS = "keepComments"

private const val PERSISTENT_STATE_COMPONENT = "com.intellij.openapi.components.PersistentStateComponent"

private const val STATE_ANNOTATION = "com.intellij.openapi.components.State"
