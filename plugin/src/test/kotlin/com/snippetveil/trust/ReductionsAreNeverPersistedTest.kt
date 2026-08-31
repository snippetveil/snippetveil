package com.snippetveil.trust

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **A reduction is never persisted, whichever reduction it is.**
 *
 * One rule governs every setting this product will ever have: *persistent settings may only ever
 * increase anonymization; any reduction is per-invocation and visible in the preview.* Under an
 * adversary model whose primary threat is retention — the snippet outliving the conversation in a
 * corpus, a log, a breach or a subpoena — the forgotten-reduction failure mode is the severe one:
 * **one tick set a year ago silently leaks the domain on every paste since.**
 *
 * There are two reductions to be right about, and this file names both. Keeping comments is one. The
 * per-item preserve is the other, and it earned a check of its own when it stopped being *unresolved
 * names only* and grew an unlock: an unlock that survived an opening would be exactly the
 * set-once-and-forgotten reduction the rule exists to prevent, and it would be invisible in a way
 * the tick under it is not.
 *
 * The rule has something of its own to be right about: SnippetVeil ships exactly one persistent
 * setting — the internal-library prefix list — and it is an *increase*, which is the case that makes
 * the distinction load-bearing rather than theoretical. The other half of this file is
 * [the rule proves it can fail][`the rule tells a persisted reduction from a persisted increase`],
 * pointed at fixtures written to be flagged and not flagged, the same habit every other check in
 * this repository follows.
 *
 * It reads bytecode off the same [SHIPPED_CLASSES] the architecture rules do, so *what ships* has
 * one definition here rather than two that can drift.
 */
class ReductionsAreNeverPersistedTest {

    /**
     * A rule that matches nothing passes, so what it matched is asserted first — and this one has
     * something to find today: each flag exists, on exactly one class, and that class is the
     * per-invocation settings object.
     *
     * It fails the moment either name is declared anywhere else — on a service, on a state bean, on
     * a component — whether or not that class has yet been wired up to be persisted, which is what
     * makes it a check on the *shape* of the flags rather than on today's wiring.
     */
    @Test
    fun `each reduction is declared on the per-invocation settings and nowhere else`() {
        assertEquals(
            NEVER_PERSISTED.associateWith { listOf("com.snippetveil.core.AnonymizationSettings") },
            NEVER_PERSISTED.associateWith { flag -> SHIPPED_CLASSES.filter { it.declares(flag) }.map { it.name } },
            "a per-invocation reduction belongs to one class only",
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

        assertFalse(settings.isStateHolder()) {
            "AnonymizationSettings carries persistence machinery, so a reduction could be written to disk"
        }
    }

    /**
     * **And neither is the preview**, which is where the unlock and every tick live.
     *
     * Asserted of the classes rather than of a flag name, because *the unlock* is a state a table
     * model holds for as long as its dialog is open and not a word a bean would carry. What the rule
     * has to rule out is either of them acquiring the machinery that would let the platform write it
     * down — and a preview that could be persisted is the shape *"don't warn me again"* would arrive
     * in.
     */
    @Test
    fun `the preview holds the unlock, and the preview is not a state holder`() {
        for (name in PREVIEW_CLASSES) {
            val preview = SHIPPED_CLASSES.single { it.name == name }

            assertFalse(preview.isStateHolder()) {
                "$name carries persistence machinery, so the Preserve unlock could survive an opening"
            }
        }
    }

    /**
     * The rule itself, over everything that ships — and over something, which is asserted first
     * because a rule that matches nothing passes.
     */
    @Test
    fun `nothing shipped persists a reduction`() {
        assertTrue(SHIPPED_STATE_HOLDERS.isNotEmpty()) {
            "Nothing shipped can be written to disk, so this rule holds vacuously."
        }
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
     *
     * Both reductions are pointed at it, because a rule that only ever saw one of them fail is a
     * rule that has only been proved for that one.
     */
    @Test
    fun `the rule tells a persisted reduction from a persisted increase`() {
        val comments = ClassFileImporter().importClasses(
            PersistsCommentRetention::class.java,
            PersistsCommentRetention.Settings::class.java,
        )
        val preserves = ClassFileImporter().importClasses(
            PersistsAPreserveList::class.java,
            PersistsAPreserveList.Settings::class.java,
        )
        val increase = ClassFileImporter().importClasses(
            PersistsAnIncrease::class.java,
            PersistsAnIncrease.Settings::class.java,
        )

        assertEquals(
            listOf(PersistsCommentRetention.Settings::class.java.name),
            persistedReductionsIn(comments),
            "the rule did not flag a state class carrying comment retention",
        )
        assertEquals(
            listOf(PersistsAPreserveList.Settings::class.java.name),
            persistedReductionsIn(preserves),
            "the rule did not flag a state class carrying a preserve list",
        )
        assertEquals(
            emptyList<String>(),
            persistedReductionsIn(increase),
            "the rule flagged a persistent setting that only increases anonymization",
        )
    }

    /**
     * Every class in [classes] that a state holder would write to disk and that carries a reduction.
     *
     * **The state class, not only the component**, because that is the shape the mistake takes:
     * nobody puts a reduction on the `PersistentStateComponent` itself, they put it on the plain bean
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
        .filter { holder -> NEVER_PERSISTED.any { holder.declares(it) } }
        .map { it.name }
        .distinct()
}

/**
 * Whether this class declares [reduction], under any spelling a Kotlin `val`, a Java field or an
 * accessor pair produces for that one name.
 *
 * Matched on the name rather than on the type, because a `Boolean` or a `Set` says nothing: what
 * makes one of these a reduction is what it means, and the name is where the meaning is written.
 *
 * **A rename of a flag does not slip past this, and the reason is worth stating**, because it is not
 * obvious: matching one spelling would be a weak rule on its own, but
 * [the assertion above][ReductionsAreNeverPersistedTest] requires each name to be *found*, on exactly
 * one class. Rename `keepComments` to `retainComments` and that assertion goes red with the whole
 * list in the message. The rule fails closed, and the person doing the renaming is the one who reads
 * why.
 */
private fun JavaClass.declares(reduction: String): Boolean =
    fields.any { it.name.contains(reduction, ignoreCase = true) } ||
        methods.any { it.name.contains(reduction, ignoreCase = true) }

/**
 * **The two reductions this product has**, named here so that adding a third is an edit to this line
 * rather than a check that quietly stops covering it.
 *
 * Keeping comments is the prose leak. The preserve set is the name leak, and since 2026-08-31 it
 * reaches every symbol in the snippet rather than the unresolved ones alone — which is what makes it
 * worth naming here beside the first.
 */
private val REDUCTIONS = listOf("keepComments", "preservedSymbols")

/**
 * **And the one per-invocation input that is not a reduction but may not be persisted either.**
 *
 * A rename does not anonymize less — the symbol is still replaced — so it is not on the list above,
 * and calling it a reduction would blunt a word this file needs to keep sharp. It is on this one
 * because the stem is text the user typed for one snippet: a **chosen disclosure**, and a chosen
 * disclosure set once and forgotten is the same failure the rule above exists to prevent, arriving
 * under a different name.
 *
 * The rule itself is proved against fixtures by
 * [`the rule tells a persisted reduction from a persisted increase`]; what this line adds is the
 * flag it is pointed at, and the assertion above is what stops that flag being renamed away
 * silently.
 */
private val NEVER_PERSISTED = REDUCTIONS + "renamedStems"


/**
 * The two shipped classes the `Preserve` unlock lives in while a preview is open — the table model
 * that holds the flag and decides which rows offer a box, and the dialog that sets it once the
 * warning has been answered.
 */
private val PREVIEW_CLASSES = listOf(
    "com.snippetveil.plugin.MappingTableModel",
    "com.snippetveil.plugin.PreviewDialog",
)
