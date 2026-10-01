package com.snippetveil.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * **A stack trace has no file under analysis, so its org root comes from its own project-owned
 * types.**
 *
 * A snippet's internal-org root is its file's root package. A trace has no file. Without this rule,
 * `com.acme.platform.HttpClient`, a library class, would be printed as it is, and the org's
 * namespace would leak in the one artifact that lists it. So the root of every type the plan reports
 * as project content is a root, and a library type under one is the org's own code arriving as a
 * jar.
 *
 * Every test here is over a plan literal. The builder reports origins and packages, and the engine
 * derives the roots. The derivation reads classified symbols, never the text.
 */
class TraceOrgRootTest {

    @Test
    fun `a library class under a project-owned frame's root is anonymized`() {
        val plan = planOf(
            "BillingService then HttpClient.send",
            BILLING_SERVICE,
            HTTP_CLIENT,
            SEND,
            rootFromOwnedTypes = true,
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("Type1 then Type2.method3", result.text)
    }

    /**
     * **This varies per invocation, by design.** A trace from somebody else's project resolves
     * nothing as project-owned, so it derives no root and `com.acme.platform` stays a library. The
     * same class in a local trace is anonymized (see above). It looks like a bug. It is not: the
     * alternative derives the prefix project-wide, which is a heuristic and comes out empty in a
     * monorepo rooted at both `com.acme` and `io.tools`.
     */
    @Test
    fun `a foreign trace with nothing project-owned preserves the org's library, and that is intended`() {
        val plan = FOREIGN_TRACE

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("Unknown1 then HttpClient.send", result.text)
    }

    @Test
    fun `with nothing project-owned, only the editable prefix list applies`() {
        val plan = FOREIGN_TRACE
        val listed = AnonymizationSettings(internalLibraries = InternalLibraries(internalPrefixes = setOf("com.acme.platform")))

        val result = anonymize(plan, listed, LedgerSnapshot.EMPTY)

        assertEquals("Unknown1 then Type2.method3", result.text)
    }

    /** The derived roots are auto-detect, so the setting that switches auto-detect off switches them off. */
    @Test
    fun `auto-detect off leaves a trace's roots to the list`() {
        val plan = planOf("BillingService then HttpClient.send", BILLING_SERVICE, HTTP_CLIENT, SEND, rootFromOwnedTypes = true)
        val off = AnonymizationSettings(internalLibraries = InternalLibraries(autoDetectRootPackage = false))

        val result = anonymize(plan, off, LedgerSnapshot.EMPTY)

        assertEquals("Type1 then HttpClient.send", result.text)
    }

    /**
     * **Every project-owned root counts**, so a trace through both halves of a monorepo claims both
     * orgs' libraries.
     */
    @Test
    fun `a trace through two project roots claims the libraries under each`() {
        val plan = planOf(
            "BillingService then HttpClient.send then Indexer then Shard",
            BILLING_SERVICE,
            HTTP_CLIENT,
            SEND,
            symbol("Indexer", SymbolRole.TYPE, SymbolOrigin.IN_CONTENT, packageName = "io.tools.search"),
            symbol("Shard", SymbolRole.TYPE, SymbolOrigin.LIBRARY, packageName = "io.tools.storage"),
            rootFromOwnedTypes = true,
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("Type1 then Type2.method3 then Type4 then Type5", result.text)
    }

    /**
     * **Only a type counts.** A package segment is project content whenever any directory behind it
     * is, so `com` is a project package in nearly every project. Read as a root, it would claim every
     * `com.*` library in the world.
     */
    @Test
    fun `a project-owned package segment derives no root`() {
        val plan = planOf(
            "com then Gson.toJson",
            pkg("com", SymbolOrigin.IN_CONTENT),
            symbol("Gson", SymbolRole.TYPE, SymbolOrigin.LIBRARY, packageName = "com.google.gson"),
            symbol("toJson", SymbolRole.METHOD, SymbolOrigin.LIBRARY, packageName = "com.google.gson"),
            rootFromOwnedTypes = true,
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("com then Gson.toJson", result.text)
    }

    /**
     * **A snippet is unchanged.** Its root is its file's, and a project type from another org in it
     * claims nothing. Without this, deriving roots from types would widen every snippet as well.
     */
    @Test
    fun `a snippet's root stays its file's and its types derive none`() {
        val plan = planOf(
            "Indexer then HttpClient.send",
            symbol("Indexer", SymbolRole.TYPE, SymbolOrigin.IN_CONTENT, packageName = "io.tools.search"),
            HTTP_CLIENT,
            SEND,
            rootPackage = "org.example",
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("Type1 then HttpClient.send", result.text)
    }

    /** The root is the first two segments, the same cut a snippet's file root takes. */
    @Test
    fun `the root of a type is its package's first two segments`() {
        assertEquals("com.acme", rootPackageOf("com.acme.billing.internal"))
        assertEquals("com.acme", rootPackageOf("com.acme"))
        assertEquals("acme", rootPackageOf("acme"))
    }
}

private val BILLING_SERVICE = symbol("BillingService", SymbolRole.TYPE, SymbolOrigin.IN_CONTENT, packageName = "com.acme.billing")
private val HTTP_CLIENT = symbol("HttpClient", SymbolRole.TYPE, SymbolOrigin.LIBRARY, packageName = "com.acme.platform")
private val SEND = symbol("send", SymbolRole.METHOD, SymbolOrigin.LIBRARY, packageName = "com.acme.platform")

/** The same frames with `BillingService` unresolved: a trace from a project this IDE does not have. */
private val FOREIGN_TRACE = planOf(
    "BillingService then HttpClient.send",
    symbol("BillingService", SymbolRole.TYPE, SymbolOrigin.UNRESOLVED),
    HTTP_CLIENT,
    SEND,
    rootFromOwnedTypes = true,
)
