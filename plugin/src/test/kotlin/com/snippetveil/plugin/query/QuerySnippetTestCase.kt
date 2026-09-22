package com.snippetveil.plugin.query

import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.LightProjectDescriptor
import com.snippetveil.core.AnonymizationResult
import com.snippetveil.core.AnonymizationSettings
import com.snippetveil.core.LedgerSnapshot
import com.snippetveil.core.SnippetPlan
import com.snippetveil.core.anonymize
import com.snippetveil.plugin.InjectedFragment
import com.snippetveil.plugin.JavaSnippetTestCase
import com.snippetveil.plugin.RealClasspath
import com.snippetveil.plugin.assertTheQueryContributorPinHolds
import com.snippetveil.plugin.attachJar
import com.snippetveil.plugin.pinnedQueryContributors
import java.io.File

/**
 * The Java fixture's classpath with the **persistence libraries** on it, and the assertions a query
 * fixture makes about its harness before anything is measured through it.
 *
 * **Every trap here fails in the green direction.** A query the IDE never injected into comes out as
 * one redacted literal, which is exactly what a query that *fell back* comes out as — so a fixture
 * whose contributor is missing, or whose annotation is not on the classpath, passes every *falls back*
 * assertion there is while measuring nothing. Hence the two preconditions:
 *
 *  - [assertTheQueryHarnessHolds] — the harness resolves, and the query contributors this cell pins
 *    are the ones present and are not none.
 *  - [assertInjected] — the literal a fixture is about really was injected, in the language it says.
 *
 * These run only where the query contributors are pinned; the build excludes this package elsewhere.
 */
internal abstract class QuerySnippetTestCase : JavaSnippetTestCase() {

    override fun getProjectDescriptor(): LightProjectDescriptor = PERSISTENCE_CLASSPATH

    protected fun assertTheQueryHarnessHolds() {
        assertTheHarnessResolves()
        assertTheQueryContributorPinHolds()
        assertFalse(
            "This package runs only where query contributors are pinned, and this cell pins none.",
            pinnedQueryContributors().isEmpty(),
        )
    }

    /**
     * The literal written [literal] in [file] was injected into, as exactly one fragment in the language
     * [languageId] — so that what a fixture says about a fragment is about a fragment that exists.
     */
    protected fun assertInjected(file: PsiFile, literal: String, languageId: String) {
        val host = PsiTreeUtil.findChildrenOfType(file, PsiLanguageInjectionHost::class.java).firstOrNull { it.text == literal }
        assertNotNull("no literal written $literal in the fixture", host)
        val fragments = InjectedFragment.injectedInto(host!!)
        assertEquals(
            "the IDE injected ${fragments.map { it.file.language.id }} into $literal, where the fixture needs one $languageId fragment",
            listOf(languageId),
            fragments.map { it.file.language.id },
        )
    }

    /** What the engine makes of [plan], with an empty ledger and [settings]. */
    protected fun resultOf(plan: SnippetPlan, settings: AnonymizationSettings = AnonymizationSettings.DEFAULTS): AnonymizationResult =
        anonymize(plan, settings, LedgerSnapshot.EMPTY)

    /** Adds [text] to the project at [path], as project content the snippet can name. */
    protected fun addProjectFile(path: String, text: String): PsiFile = myFixture.addFileToProject(path, text)
}

/**
 * The same fixture with **Hibernate** on the classpath as well — which is what makes the platform inject
 * HQL into Hibernate's own `@NamedQuery`, and what makes it read a JPA `@NamedQuery` as HQL too. So it is
 * a classpath of its own rather than part of the one above: the JPQL fixtures need a module where JPQL
 * stays JPQL.
 */
internal abstract class HibernateSnippetTestCase : QuerySnippetTestCase() {
    override fun getProjectDescriptor(): LightProjectDescriptor = HIBERNATE_CLASSPATH
}

/**
 * The persistence API and Spring Data, attached as the real jars the build resolved. A distinct class,
 * because the light fixture tells two descriptors apart by their class — see [RealClasspath].
 */
private open class PersistenceClasspath : RealClasspath() {

    override fun attachLibraries(model: ModifiableRootModel) {
        super.attachLibraries(model)
        queryFixtureJars().filter { includes(it) }.forEach { attachJar(model, it.nameWithoutExtension, it.path) }
    }

    open fun includes(jar: File): Boolean = !jar.name.startsWith(HIBERNATE_JAR)
}

private class HibernateClasspath : PersistenceClasspath() {
    override fun includes(jar: File): Boolean = true
}

/**
 * The jars the build resolved for the query fixtures, or a failure that says the wiring is missing
 * rather than letting the fixture come up without them — which would inject nothing, and pass.
 */
private fun queryFixtureJars(): List<File> {
    val named = System.getProperty(QUERY_LIBRARIES_PROPERTY)
        ?: error(
            "-D$QUERY_LIBRARIES_PROPERTY is not set, so no persistence library can be attached and no query " +
                "would ever be injected. The `test` task in plugin/build.gradle.kts sets it from " +
                "`queryFixtureLibraries`.",
        )
    return named.split(File.pathSeparator).map(::File).onEach { check(it.isFile) { "$QUERY_LIBRARIES_PROPERTY names $it, which is not a file." } }
}

private const val QUERY_LIBRARIES_PROPERTY = "snippetveil.query.libraryJars"

private const val HIBERNATE_JAR = "hibernate-core"

private val PERSISTENCE_CLASSPATH: LightProjectDescriptor = PersistenceClasspath()

private val HIBERNATE_CLASSPATH: LightProjectDescriptor = HibernateClasspath()
