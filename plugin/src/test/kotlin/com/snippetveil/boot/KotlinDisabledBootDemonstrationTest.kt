package com.snippetveil.boot

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * **The Kotlin-disabled boot's own precondition, shown red in the cell where Kotlin is running.**
 *
 * [KotlinDisabledBootTest] asserts that the IDE it booted does not have the Kotlin plugin, before it
 * asserts anything else — because with the plugin enabled its Java assertions pass for ordinary
 * reasons and its refusal assertions do not fire at all, so a cell whose disabling had quietly come
 * apart would be green and would mean nothing. An assertion nobody has seen fail is a comment, and
 * this is the one place that failure can be produced on demand: **every ordinary cell is an IDE with
 * the Kotlin plugin loaded.**
 *
 * Two directions, because a precondition that flags the healthy case too is one somebody eventually
 * suppresses. And the second test is the demonstration proper: it takes the complaint to the real
 * plugin set of the cell it is running in, which is exactly the state the boot must refuse.
 *
 * This class runs in the merge gate, and the boot it demonstrates does not — the boot needs its own
 * IDE. That asymmetry is the point rather than an oversight: what a pull request can afford to check
 * is that the check is still capable of failing.
 */
class KotlinDisabledBootDemonstrationTest : BasePlatformTestCase() {

    /** Both directions of the complaint, over plugin sets written out rather than observed. */
    fun `test the complaint names a loaded Kotlin plugin and passes an IDE without one`() {
        val complaint = complaintAboutKotlinBeingLoaded(listOf("com.intellij", KOTLIN_PLUGIN_ID)).orEmpty()

        assertTrue(
            "The complaint does not name the plugin whose presence it is about: $complaint",
            KOTLIN_PLUGIN_ID in complaint,
        )
        assertTrue(
            "The complaint does not say that the disabling came apart, which is the thing a reader of " +
                "a red boot needs to know: $complaint",
            "disablePlugin" in complaint,
        )
        assertNull(
            "The precondition flagged an IDE that does not have the Kotlin plugin, which is the only " +
                "configuration the boot is allowed to run in.",
            complaintAboutKotlinBeingLoaded(listOf("com.intellij", "com.intellij.java")),
        )
    }

    /**
     * **The boot's precondition, against this cell's real plugin set** — and it must complain.
     *
     * The merge gate runs against an IDE with the Kotlin plugin, so this is the state
     * [KotlinDisabledBootTest] exists to refuse. If it ever stops complaining here, either the
     * ordinary cells have lost the Kotlin plugin — which would take the `k2` and `latest` Kotlin
     * fixtures with them — or the precondition has stopped reading the plugin set at all.
     */
    fun `test the boot's precondition fails in a cell where the Kotlin plugin is loaded`() {
        assertNotNull(
            "The Kotlin plugin is not loaded in this cell, so the Kotlin-disabled boot's precondition " +
                "would pass here — and passing everywhere is what a precondition may not do.",
            complaintAboutKotlinBeingLoaded(loadedPluginIds()),
        )
    }
}
