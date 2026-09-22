package com.snippetveil.plugin

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals

/**
 * **The SQL container is registered exactly where the database plugin is loaded** — in every cell,
 * the IC ones included.
 *
 * It is registered from `com.snippetveil-withDatabase.xml`, which the platform reads only where the
 * database plugin is running, so the registration and the plugin have to come and go together: a
 * container present without the plugin would be a class that cannot link, and one absent beside it
 * would leave every SQL string one redacted literal on an IDE that could have read it. Asked by class
 * name, so that this test itself links nowhere the plugin is absent.
 */
class SqlContainerRegistrationTest : BasePlatformTestCase() {

    fun `test the SQL container is registered exactly where the database plugin is loaded`() {
        val loaded = PluginManagerCore.getPlugin(PluginId.getId(DATABASE_PLUGIN_ID))?.isEnabled == true
        assertEquals(
            "whether the database plugin is loaded disagrees with this cell's pin",
            DATABASE_PLUGIN_ID in pinnedQueryContributors(),
            loaded,
        )
        assertEquals(
            if (loaded) listOf(SQL_CONTAINER) else emptyList(),
            RegisteredContainers.INJECTED_CONTAINERS.extensionList.map { it.javaClass.name },
        )
    }
}

private const val SQL_CONTAINER = "com.snippetveil.plugin.sql.SqlContainer"
