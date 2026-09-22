package com.snippetveil.plugin

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.util.PsiTreeUtil
import com.snippetveil.core.CommentOccurrence
import com.snippetveil.core.LiteralOccurrence
import com.snippetveil.core.SnippetPlan
import com.snippetveil.core.SourceLanguage
import com.snippetveil.core.SqlKeys
import com.snippetveil.core.SymbolEvidence
import com.snippetveil.core.SymbolOccurrence
import com.snippetveil.core.SymbolOrigin
import com.snippetveil.core.SymbolRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

/*
 * **Injected fragments as real code lays them out.**
 *
 * The fixtures inject with IntelliLang's `// language=JAVA` comment, in Java and in Kotlin, because
 * the two shapes the range identity has to catch were measured in real code and are only worth
 * asserting against the shred layout a real injector produces: a Java concatenation operand that is
 * not a constant becomes `missingValue` in the injected document, a Kotlin `$tableVar` becomes
 * `tableVar`, and a Kotlin `${tableVar}` becomes `missingValue` again. A test injector would have lain
 * those out however its author imagined them.
 *
 * Java is the injected language only because it is a language every cell of the matrix has and whose
 * identifiers are one PSI type. What is under test is the mapping, and the mapping reads no grammar.
 */

/**
 * **A stand-in container**: every identifier in an injected Java fragment, read as a table name.
 *
 * Test data rather than a container — it decides nothing a real one would, and the evidence it
 * reports is whatever makes a placeholder visible in the output. `IN_CONTENT` is chosen for that
 * reason only; which origin a SQL name reports is the SQL container's decision to make, and this
 * ticket does not make it.
 */
internal val IDENTIFIERS_AS_TABLES = InjectedContainer { fragment ->
    InjectedReading(PsiTreeUtil.findChildrenOfType(fragment.file, PsiIdentifier::class.java).map { nameOf(it.textRange, it.text) })
}

/** One table name over [range] of an injected document, spelled [written] — the whole token its own name. */
internal fun nameOf(range: TextRange, written: String, name: TextRange = range): InjectedName = InjectedName(
    token = range,
    name = name,
    symbol = SymbolEvidence(
        key = SqlKeys.named(SymbolRole.TABLE, written),
        role = SymbolRole.TABLE,
        origin = SymbolOrigin.IN_CONTENT,
        declaredName = written,
    ),
    language = SourceLanguage.SQL,
)

/**
 * The one fragment injected into the literal written [literal] in [file] — asserted to exist, because
 * a fixture whose injection silently did not happen would pass every *falls back* assertion there is.
 */
internal fun injectedFragmentIn(file: PsiFile, literal: String): InjectedFragment {
    val host = PsiTreeUtil.findChildrenOfType(file, PsiLanguageInjectionHost::class.java).firstOrNull { it.text == literal }
    assertNotNull("no literal written $literal in the fixture", host)
    val fragments = InjectedFragment.injectedInto(host!!)
    assertEquals("the injector laid ${fragments.size} fragments over $literal, and the fixture expects one", 1, fragments.size)
    return fragments.single()
}

/** The range, in the injected document, of the identifier written [text] there. */
internal fun InjectedFragment.identifier(text: String): TextRange {
    val identifier = PsiTreeUtil.findChildrenOfType(file, PsiIdentifier::class.java).firstOrNull { it.text == text }
    assertNotNull("the injected document ${file.text} holds no identifier $text", identifier)
    return identifier!!.textRange
}

/**
 * **The single-range host projection** — the API the shipped path must never call, called here so the
 * assertion can be shown refusing what it answers.
 */
internal fun InjectedFragment.unionProjection(): (TextRange) -> TextRange? =
    { range -> InjectedLanguageManager.getInstance(file.project).injectedToHost(file, range) }

/**
 * Every field of every occurrence in [plan], as one string — so that *byte-identical plan* is one
 * assertion rather than a sample of fields.
 */
internal fun describe(plan: SnippetPlan): String = buildString {
    append(plan.text).append('\n').append(plan.rootPackage).append('/').append(plan.selectionExpanded).append('\n')
    for (occurrence in plan.occurrences) {
        append(occurrence.javaClass.simpleName).append(' ').append(occurrence.start).append(':').append(occurrence.end)
            .append(' ').append(occurrence.language).append(' ')
        when (occurrence) {
            is SymbolOccurrence -> append(occurrence.text).append(' ').append(occurrence.nameStart).append(':')
                .append(occurrence.nameEnd).append(' ').append(evidence(occurrence.symbol))
            is LiteralOccurrence -> append(occurrence.kind).append(' ').append(occurrence.contentStart).append(':')
                .append(occurrence.contentEnd).append(' ')
                .append(occurrence.references.map { "${it.start}:${it.end} ${evidence(it.symbol)}" })
            is CommentOccurrence -> append(occurrence.verdict)
        }
        append('\n')
    }
}

private fun evidence(symbol: SymbolEvidence): String = listOf(
    symbol.key, symbol.role, symbol.origin, symbol.declaredName, symbol.qualifiedName, symbol.packageName,
    symbol.signature, symbol.keyIsQualified, symbol.overrideRoots.map { "${it.key}/${it.origin}/${it.packageName}/${it.keyIsQualified}" },
    symbol.accessor?.let { "${it.fieldKey}/${it.fieldName}/${it.prefix}/${it.fieldKeyIsQualified}" },
    symbol.siblingAccessors.map(::evidence),
).joinToString(" ")

/**
 * Runs [projection] and asserts it failed **on the range identity** — not merely that it threw, since
 * a mapping that crashed on its way to a wrong range would otherwise pass for one the identity caught.
 */
internal fun assertFailsTheRun(projection: () -> Unit) {
    try {
        projection()
    } catch (mismatch: IllegalStateException) {
        assertTrue("the run failed, but not on the range identity: ${mismatch.message}", mismatch.message!!.contains("maps back to"))
        return
    }
    fail("a host range that does not map back to its name passed the range identity")
}
