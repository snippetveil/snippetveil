package com.snippetveil.plugin

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.JdkOrderEntry
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiUtilCore

/**
 * Where a fixture put something, as the harness assertions ask it — **and not [SymbolOrigin], which
 * is the product's answer to a different question.**
 *
 * The product classifies to decide what to rename, and it **fails closed**: a file in none of the
 * project's order entries is reported `LIBRARY` or `IN_CONTENT` because a plan has to say something,
 * and the safe something is the one that renames. A precondition may not do that. *The file came
 * back from somewhere this project's model does not describe* is exactly the shape of a broken
 * classpath — a symbol resolved out of the IDE's own runtime rather than the jar the fixture thinks
 * it attached — and folding it into `LIBRARY` would make the assertion pass on it.
 *
 * So this enum has one constant the product's has no word for, and that constant is the whole reason
 * it exists.
 */
internal enum class FixtureOrigin {
    /** The reference did not resolve at all. */
    UNRESOLVED,

    /** Project content: a file the fixture wrote. */
    PROJECT,

    /** The JDK attached by [RealClasspath]. */
    JDK,

    /** A jar attached to the module as a library — a real one, which is the point. */
    LIBRARY,

    /**
     * Resolved, but out of a file no order entry of this project owns.
     *
     * The one answer the product's enum cannot give, and the one a fixture assertion must never
     * treat as a library: it is what a symbol resolved from the running IDE rather than from an
     * attached jar looks like, and it is indistinguishable from a working classpath until something
     * asks.
     */
    OUTSIDE_THE_PROJECT_MODEL,
}

/**
 * The three names [JavaSnippetTestCase.assertTheHarnessResolves] probes, and what the fixture has to
 * classify each of them as.
 *
 * A list rather than three assertions in a row, because it is also what the red demonstration reads:
 * a probe added here is one `HarnessOriginTest` covers without being edited.
 */
internal val HARNESS_PROBES: List<Pair<String, FixtureOrigin>> = listOf(
    "com.acme.probe.Owned" to FixtureOrigin.PROJECT,
    "java.lang.String" to FixtureOrigin.JDK,
    "org.junit.Test" to FixtureOrigin.LIBRARY,
)

/**
 * Where the fixture put a resolved symbol, asked of the platform directly.
 *
 * **The product's own classifier is deliberately not used here.** A precondition exists to say *the
 * fixture is usable*, and one that ran the code under test would answer that question with the
 * answer it is there to protect — a broken classifier would go red naming the fixture, which is the
 * one thing these messages must never do wrongly.
 *
 * Only what a harness probe can produce: a resolved declaration in a file. The cases a plan builder
 * has to be cleverer about — a package, a light element, a local — are not what a classpath
 * assertion is asking.
 */
internal fun originInTheFixture(project: Project, symbol: PsiElement?): FixtureOrigin {
    val virtualFile = symbol?.let { PsiUtilCore.getVirtualFile(it) } ?: return FixtureOrigin.UNRESOLVED
    val index = ProjectFileIndex.getInstance(project)
    val entries = index.getOrderEntriesForFile(virtualFile)
    return when {
        index.isInContent(virtualFile) -> FixtureOrigin.PROJECT
        entries.any { it is JdkOrderEntry } -> FixtureOrigin.JDK
        entries.any { it is LibraryOrderEntry } -> FixtureOrigin.LIBRARY
        else -> FixtureOrigin.OUTSIDE_THE_PROJECT_MODEL
    }
}

/**
 * What is wrong with the *fixture* when [name] came back [observed] instead of [expected], or `null`
 * when nothing is.
 *
 * A returned complaint rather than an assertion, so that the demonstration that this can fail is an
 * ordinary test over ordinary values rather than a fixture nobody can build. Every sentence blames
 * the classpath, because that is what is broken when one of these fires: the code under test has not
 * been reached yet.
 */
internal fun complaintAboutFixtureOrigin(name: String, expected: FixtureOrigin, observed: FixtureOrigin): String? =
    when (observed) {
        expected -> null
        FixtureOrigin.UNRESOLVED ->
            "$name did not resolve; the fixture has no usable classpath, and unresolved fails closed — " +
                "so every name in every snippet here would be renamed and the suite would go green over it."
        FixtureOrigin.OUTSIDE_THE_PROJECT_MODEL ->
            "$name resolved out of a file no order entry of this project owns, where the fixture has to " +
                "make it $expected. Something answered that is not the classpath this fixture attached, " +
                "and an assertion about $expected symbols would be an assertion about that instead."
        else ->
            "$name resolved to $observed where the fixture has to make it $expected. The classpath has " +
                "drifted, and the $expected-versus-$observed discriminator is not being exercised at all."
    }
