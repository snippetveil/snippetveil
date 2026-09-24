package com.snippetveil.trust

import com.intellij.ide.BrowserUtil
import com.intellij.internal.statistic.eventLog.EventLogGroup
import com.intellij.openapi.diagnostic.ErrorReportSubmitter
import com.intellij.util.io.DigestUtil
import com.intellij.util.io.HttpRequests
import com.intellij.util.net.HttpConfigurable

/**
 * The red path of the networking rule's platform half, baked in rather than observed once.
 *
 * Every route here reaches the network inside the platform, so none of them names a `java.net` type
 * and the JDK half of the rule would pass all of them. [DownloadsThroughThePlatform] must be flagged
 * once per route and [UsesThePlatformLegitimately] must not be flagged at all, and `the networking
 * rule flags the platform's HTTP stack and nothing else` asserts both.
 *
 * Test scope, so [SHIPPED_CLASSES] excludes them and the shipped-code rules never see them; the
 * methods are never called, so nothing is ever sent. They are imported by name, one class at a time,
 * which is the only way a rule gets pointed at code that is meant to violate it.
 */
internal class DownloadsThroughThePlatform {

    fun viaHttpRequests(): String = HttpRequests.request("https://example.invalid").readString()

    fun viaProxySettings(): HttpConfigurable = HttpConfigurable.getInstance()

    fun viaErrorReports(): Class<*> = ErrorReportSubmitter::class.java

    fun viaUsageStatistics(): EventLogGroup = EventLogGroup("snippetveil.fixture", 1)
}

/**
 * A package neighbour of `HttpRequests` that only hashes bytes, and the one platform route to a URL
 * that shipped code does use: handing it to the desktop, which opens nothing in this process.
 */
internal class UsesThePlatformLegitimately {

    fun digest(): ByteArray = DigestUtil.sha256().digest(byteArrayOf())

    fun reportAnIssue() = BrowserUtil.browse("https://example.invalid")
}
