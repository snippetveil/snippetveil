package com.snippetveil.plugin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * **Which [Unavailable] each answer about the Kotlin plugin produces — the mapping, asserted apart
 * from the API that supplies the answers.**
 *
 * The gate asks the platform two things about the Kotlin plugin, *is it installed?* and *is it
 * disabled?*, and only one combination of the answers is reachable from a fixture: every cell this
 * suite runs in has the plugin installed and enabled. Kotlin switched off is reached by the
 * Kotlin-disabled boot alone, which is a release gate — so a mapping that went wrong there would pass
 * every pull request and be found a release later. Asserted here, over the answers rather than over
 * an IDE, it is found at merge speed.
 *
 * **The row that matters most is installed and disabled.** The platform's *is it installed?* counts a
 * disabled plugin as installed, so a probe that asked only that would send a user whose Kotlin
 * plugin is switched off to a Kotlin settings page that does not exist, instead of to Plugins.
 */
class UnavailableCauseTest {

    /** Every combination of the two answers, in one assertion, so a row that moves is named. */
    @Test
    fun `each answer about the Kotlin plugin maps to the cause the user has to fix`() {
        val table = listOf(
            Answers(installed = false, disabled = false) to Unavailable.PLUGIN_NOT_RUNNING,
            Answers(installed = false, disabled = true) to Unavailable.PLUGIN_NOT_RUNNING,
            Answers(installed = true, disabled = true) to Unavailable.PLUGIN_NOT_RUNNING,
            Answers(installed = true, disabled = false) to Unavailable.PATH_NOT_ACTIVATED,
        )

        assertEquals(
            table,
            table.map { (answers, _) -> answers to causeOfMissingKotlin(answers.installed, answers.disabled) },
        )
    }

    /** The two answers, named, so that a failing row reads as a sentence rather than as two booleans. */
    private data class Answers(val installed: Boolean, val disabled: Boolean)
}
