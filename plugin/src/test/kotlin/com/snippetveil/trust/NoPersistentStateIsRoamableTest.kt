package com.snippetveil.trust

import com.tngtech.archunit.core.domain.JavaAnnotation
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaEnumConstant
import com.tngtech.archunit.core.importer.ClassFileImporter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * **Nothing SnippetVeil persists ever leaves the machine it was written on.**
 *
 * Settings sync copies a roamable `@State` to JetBrains' servers, and the one setting this product
 * persists — the internal-library prefix list — **literally is the employer's group id**, which is
 * the single most identifying string the plugin handles. A privacy tool that uploaded that would be
 * doing the thing it exists to prevent, by default, through a mechanism nobody chose.
 *
 * So the rule is absolute rather than per-file: *no `@State` in SnippetVeil is roamable.* An
 * absolute rule is worth having because it is checkable in one pass and readable in one grep, where
 * *this one is fine to sync* is a judgement that has to be made again correctly every time.
 *
 * Written as a check rather than a paragraph, over the same [SHIPPED_CLASSES] every other trust rule
 * reads, and following the same two habits: it asserts its own coverage, and it proves it can fail
 * before it reports that nothing failed.
 */
class NoPersistentStateIsRoamableTest {

    /**
     * A rule over an empty list passes, so what it matched is asserted first — and this one has
     * something to find: the plugin ships three state holders today, and the rule is absolute over
     * all of them. Two of the three are the ones a settings sync would hurt most — the mapping,
     * which is the employer's whole vocabulary, and the sidecar, which is that plus the text of
     * every literal it has replaced.
     */
    @Test
    fun `the rule below is applied to state that actually ships`() {
        assertEquals(
            listOf(
                "com.snippetveil.plugin.InternalLibrarySettings",
                "com.snippetveil.plugin.PlaceholderLedger",
                "com.snippetveil.plugin.PlaceholderSidecar",
            ),
            SHIPPED_STATE_HOLDERS.map { it.name }.sorted(),
            "the shipped state holders are not what this rule was written against",
        )
    }

    /** The rule itself, over everything that ships and can be written to disk. */
    @Test
    fun `nothing shipped roams its state`() {
        assertEquals(
            emptyList<String>(),
            roamableIn(SHIPPED_STATE_HOLDERS),
            "no @State in SnippetVeil is roamable: settings sync would carry it off the machine",
        )
    }

    /**
     * The rule, pointed at a class written to violate it — and at one written to look like a
     * violation and not be one.
     *
     * [RoamsItsState] is the shape the mistake actually takes: a `@Storage` that simply says nothing
     * about roaming, because [com.intellij.openapi.components.RoamingType] defaults to roamable and
     * nobody writes the default out in full. A rule that only matched an explicit `DEFAULT` would
     * match nobody, and this is what says so.
     */
    @Test
    fun `the rule tells a roaming storage from a disabled one`() {
        val roaming = ClassFileImporter().importClasses(RoamsItsState::class.java)
        val local = ClassFileImporter().importClasses(KeepsItsStateLocal::class.java)

        assertEquals(
            listOf(RoamsItsState::class.java.name),
            roamableIn(roaming.toList()),
            "the rule did not flag a @Storage that leaves roaming at its default",
        )
        assertEquals(
            emptyList<String>(),
            roamableIn(local.toList()),
            "the rule flagged state that is already pinned to the machine",
        )
    }

    /**
     * Every class in [holders] whose state can leave the machine.
     *
     * **Fails closed on anything it cannot read as disabled**, which covers three shapes at once: a
     * `@Storage` that leaves roaming at its default, one that names any other
     * [com.intellij.openapi.components.RoamingType], and a state holder declaring no storage this
     * rule can see at all. The last is not a real shape today — the platform needs a `@State` to
     * persist anything — and it is treated as a violation rather than a skip, because *the rule
     * could not tell* must never read the same as *the rule was satisfied*.
     */
    private fun roamableIn(holders: List<JavaClass>): List<String> =
        holders.filter { holder ->
            val declared = roamingTypesOf(holder)
            declared.isEmpty() || declared.any { it != DISABLED }
        }.map { it.name }.distinct()

    /**
     * The roaming type of each `@Storage` on [holder], read out of the bytecode — and
     * [UNDECLARED] for one that does not say, which is what an author who never thought about
     * roaming leaves behind.
     */
    private fun roamingTypesOf(holder: JavaClass): List<String> {
        val state = holder.tryGetAnnotationOfType(STATE_ANNOTATION).orElse(null) ?: return emptyList()
        val storages = state.get("storages").orElse(null) as? Array<*> ?: return emptyList()

        return storages.filterIsInstance<JavaAnnotation<*>>()
            .filter { it.rawType.name == STORAGE_ANNOTATION }
            .map { storage -> (storage.get("roamingType").orElse(null) as? JavaEnumConstant)?.name() ?: UNDECLARED }
    }
}

/** The rule's own reading of a `@Storage` that declares no roaming type. */
private const val UNDECLARED = "<undeclared>"

private const val DISABLED = "DISABLED"
