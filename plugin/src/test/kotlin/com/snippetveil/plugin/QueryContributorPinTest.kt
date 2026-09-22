package com.snippetveil.plugin

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals

/**
 * **The query contributors a cell carries are the ones it pins** — in every cell, the IC ones
 * included.
 *
 * A query decomposes only where the IDE injects its language and resolves its names, and the plugins
 * that do that are Ultimate's. So the same fixture decomposes in one cell and is one redacted literal
 * in another, and an assertion about either cannot tell the rule working apart from the contributor
 * being absent. The build pins the set per cell — `queryContributors` in `plugin/build.gradle.kts` —
 * and this is where the pin is held to what the running IDE actually loaded, in both directions: a
 * pinned contributor that is missing, and a present one nobody pinned.
 */
class QueryContributorPinTest : BasePlatformTestCase() {

    fun `test the query contributors present are exactly the ones this cell pins`() {
        assertTheQueryContributorPinHolds()
    }
}

/** Fails unless the contributors loaded in this IDE are exactly the ones the cell pins. */
internal fun assertTheQueryContributorPinHolds() {
    val pinned = pinnedQueryContributors()
    assertEquals(
        "This cell pins the query contributors $pinned, and the IDE running it loaded a different set: " +
            "every query fixture would be measuring the difference.",
        pinned,
        presentQueryContributors(),
    )
}

/**
 * The query contributors this cell pins, as the build handed them over — a failure that says the wiring
 * is missing, rather than an empty set that would look like an IC cell.
 */
internal fun pinnedQueryContributors(): Set<String> {
    val named = System.getProperty(QUERY_CONTRIBUTORS_PROPERTY)
        ?: error(
            "-D$QUERY_CONTRIBUTORS_PROPERTY is not set, so nothing says which query contributors this cell " +
                "carries. The `test` task in plugin/build.gradle.kts sets it from `queryContributors`.",
        )
    return named.split(',').filter { it.isNotBlank() }.toSet()
}

/** Which of the query contributors any cell could carry this IDE actually loaded. */
private fun presentQueryContributors(): Set<String> =
    KNOWN_QUERY_CONTRIBUTORS.filter { PluginManagerCore.getPlugin(PluginId.getId(it))?.isEnabled == true }.toSet()

/**
 * Every contributor the pin can name: JPA, which injects JPQL and HQL, Spring Data, and the database
 * plugin, which injects SQL.
 */
private val KNOWN_QUERY_CONTRIBUTORS = listOf("com.intellij.javaee.jpa", "com.intellij.spring.data", DATABASE_PLUGIN_ID)

/** The database plugin — Database Tools and SQL — which injects SQL and parses it. */
internal const val DATABASE_PLUGIN_ID = "com.intellij.database"

private const val QUERY_CONTRIBUTORS_PROPERTY = "snippetveil.query.contributors"
