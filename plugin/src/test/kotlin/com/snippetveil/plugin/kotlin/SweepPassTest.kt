package com.snippetveil.plugin.kotlin

import com.intellij.psi.PsiFile
import com.snippetveil.core.AnonymizationSettings
import com.snippetveil.core.SnippetPlan
import com.snippetveil.core.SymbolOccurrence
import com.snippetveil.plugin.PlanBuilder
import com.snippetveil.sweep.LeakOracle
import com.snippetveil.sweep.SourceDeclarations
import com.snippetveil.sweep.SourceSpellings
import com.snippetveil.sweep.Swept
import com.snippetveil.sweep.SweepPass
import com.snippetveil.sweep.refuseKotlinThatCannotBeSwept
import com.snippetveil.sweep.sweepRoute
import org.jetbrains.kotlin.psi.KtFile

/**
 * **The corpus sweep's pass, over a mixed Java and Kotlin fixture** — the loop the sweep runs over a
 * real checkout, run here over a synthetic one so that what it does to a Kotlin file can be asserted.
 *
 * In the Kotlin fixture package for the reason [SweepDeclarationWalkTest] is: a Kotlin file is swept
 * through the registered support, which exists only where the Kotlin plugin runs in K2, and the floor
 * cell's Kotlin plugin runs in K1.
 */
internal class SweepPassTest : KotlinSnippetTestCase() {

    /**
     * **#105's headline shape, produced end to end.** A Java `getBody()` is written `javaObj.body` in
     * Kotlin, and a Kotlin output that still holds `body` is a finding — which it can only be once a
     * Kotlin file is anonymised at all and its output handed to the oracle.
     *
     * The leak is manufactured by a route that drops the rename, because the sweep's own route makes none:
     * a sweep that could not see this one would be blind to it on the day the walk regressed.
     */
    fun `test a Kotlin output still holding a Java getter's property spelling is a finding`() {
        assertTheSessionIsK2()
        val files = listOf(invoice(), reader())

        val swept = passOver(files, route = { file -> leavingBodyInKotlin(sweepRoute(file), file) })

        assertEquals(
            "The Kotlin output kept `javaObj.body`, and the sweep did not report it.",
            listOf("body"),
            swept.findings.filter { it.path == "Reader.kt" }.flatMap { it.survivors }.map { it.name }.filter { it == "body" },
        )
    }

    /**
     * **A `.kt` file in the target is anonymised, whole-file, through the route that ships** — counted
     * as Kotlin, swept without a throw, and its output read by the same oracle as the Java file's.
     *
     * The count and the empty failure list are what keep the last assertion from holding vacuously: a
     * Kotlin file the pass skipped, or threw on, would have no survivors either.
     */
    fun `test a Kotlin file is anonymised whole-file through the registered route`() {
        assertTheSessionIsK2()

        val swept = passOver(listOf(invoice(), reader()))

        assertEquals("Files that threw: ${swept.failures.map { "${it.path}: ${it.summary}" }}", 0, swept.failures.size)
        assertEquals(1, swept.counts.java)
        assertEquals(1, swept.counts.kotlin)
        assertTrue("The Kotlin file's own function was not renamed: ${originalsIn(swept)}", "read" in originalsIn(swept))
        assertFalse(
            "`body` survived the shipped route in the Kotlin output.",
            swept.findings.any { file -> file.path == "Reader.kt" && file.survivors.any { it.name == "body" } },
        )
    }

    /**
     * **One ledger across the run, and across both languages.** The Java class the Kotlin file names
     * keeps the placeholder the Java file minted for it — which it does only if the Kotlin file was
     * anonymised *against* the ledger the Java file left, rather than against a fresh one whose delta
     * was merged in afterwards. And what the Java file alone minted is still held at the end.
     */
    fun `test one ledger carries across the Java and Kotlin files of one run`() {
        assertTheSessionIsK2()
        val invoice = invoice()
        val reader = reader()

        val mintedByJava = placeholderOf("Invoice", passOver(listOf(invoice)))
        val swept = passOver(listOf(invoice, reader))

        assertEquals(
            "The Kotlin file re-minted the Java class it names instead of reading it from the one ledger.",
            mintedByJava,
            placeholderOf("Invoice", swept),
        )
        assertTrue("The Java file's names were not carried to the end of the run: ${originalsIn(swept)}", "serial" in originalsIn(swept))
        assertTrue("The Kotlin file minted nothing: ${originalsIn(swept)}", "read" in originalsIn(swept))
    }

    private fun placeholderOf(original: String, swept: Swept): String? =
        swept.ledger.placeholders.values.single { it.original == original }.placeholder

    /** **A throw on a Kotlin file is a failure, recorded the way one on a Java file is**, and the run goes on. */
    fun `test a throw on a Kotlin file is recorded and the files after it are still swept`() {
        assertTheSessionIsK2()

        val swept = passOver(
            listOf(reader(), invoice()),
            route = { file -> if (file is KtFile) PlanBuilder { error("a shape nobody thought of") } else sweepRoute(file) },
        )

        assertEquals(listOf("Reader.kt"), swept.failures.map { it.path })
        assertTrue(swept.failures.single().summary, "a shape nobody thought of" in swept.failures.single().summary)
        assertTrue("The Java file after the throw was not swept: ${originalsIn(swept)}", "serial" in originalsIn(swept))
    }

    /** Where the Kotlin support is registered, a mixed target is swept rather than refused. */
    fun `test a Kotlin file the registered support can anonymise is not refused`() {
        assertTheSessionIsK2()

        refuseKotlinThatCannotBeSwept(project, listOf(invoice(), reader()).map { it.virtualFile })
    }

    private fun originalsIn(swept: Swept): List<String> = swept.ledger.placeholders.values.map { it.original }

    private fun passOver(files: List<PsiFile>, route: (PsiFile) -> PlanBuilder = ::sweepRoute): Swept {
        val oracle = LeakOracle.over(
            SourceSpellings.of(SourceDeclarations.of(project, files.map { it.virtualFile }).declarations),
            declaredByLibraries = emptySet(),
        )
        return SweepPass(project, oracle, AnonymizationSettings.DEFAULTS, route).over(files.map { it.virtualFile }, pathOf = { it.name })
    }

    /** A Java class whose getter Kotlin writes as a property. */
    private fun invoice(): PsiFile = myFixture.addFileToProject(
        "com/acme/ledger/Invoice.java",
        """
        package com.acme.ledger;

        public class Invoice {
            private String serial;
            public String getBody() { return serial; }
        }
        """.trimIndent(),
    )

    /** The Kotlin file that writes it: `javaObj.body`, for the Java `getBody()` above. */
    private fun reader(): PsiFile = myFixture.addFileToProject(
        "com/acme/ledger/Reader.kt",
        """
        package com.acme.ledger

        fun read(javaObj: Invoice): String = javaObj.body
        """.trimIndent(),
    )

    /**
     * [builder], less the rename of every `body` token in a Kotlin file — the missing plan item the sweep
     * exists to see, put there on purpose.
     */
    private fun leavingBodyInKotlin(builder: PlanBuilder, file: PsiFile): PlanBuilder =
        if (file !is KtFile) builder
        else PlanBuilder { request ->
            val plan = builder.build(request)
            SnippetPlan(
                plan.text,
                plan.occurrences.filterNot { it is SymbolOccurrence && it.text == "body" },
                plan.rootPackage,
                plan.selectionExpanded,
            )
        }
}
