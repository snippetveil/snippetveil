package com.snippetveil.sweep

import com.intellij.psi.PsiFile
import com.snippetveil.plugin.KotlinUnregisteredTestCase

/**
 * **A Kotlin file the IDE cannot anonymise refuses the whole sweep**, rather than being skipped.
 *
 * Built on the fixture where SnippetVeil's Kotlin support is not registered — the one fact a K1 session
 * and a switched-off Kotlin plugin have in common, and what every default run on the floor platform
 * meets. Sweeping the Java half there would write a report whose Kotlin half nobody anonymised, and
 * silence about that half is the one answer the coverage habit rules out.
 *
 * Runs in every cell, the floor included, because the floor is where it bites.
 */
class SweepRefusalTest : KotlinUnregisteredTestCase() {

    fun `test a Kotlin file the IDE cannot anonymise refuses the whole sweep`() {
        val files = listOf(invoice(), payment()).map { it.virtualFile }

        val refused = runCatching { refuseKotlinThatCannotBeSwept(project, files) }.exceptionOrNull()

        assertTrue("A Kotlin file nothing can anonymise here was not refused: $refused", refused is IllegalStateException)
        val message = refused?.message.orEmpty()
        assertTrue("The refusal does not count the Kotlin it could not sweep: $message", "1 Kotlin file(s)" in message)
        assertTrue("The refusal does not say how to run where Kotlin can be swept: $message", "-PplatformProfile=k2" in message)
    }

    /** A target with no Kotlin in it has nothing to refuse, so the floor still sweeps a Java codebase. */
    fun `test a Java-only target is not refused where Kotlin support is missing`() {
        refuseKotlinThatCannotBeSwept(project, listOf(invoice().virtualFile))
    }

    private fun invoice(): PsiFile =
        myFixture.addFileToProject("com/acme/ledger/Invoice.java", "package com.acme.ledger;\n\npublic class Invoice {}\n")

    private fun payment(): PsiFile =
        myFixture.addFileToProject("com/acme/ledger/Payment.kt", "package com.acme.ledger\n\nclass Payment\n")
}
