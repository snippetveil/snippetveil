package com.snippetveil.plugin

import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiModifier
import com.intellij.psi.search.GlobalSearchScope
import com.snippetveil.core.LiteralKind
import com.snippetveil.core.LiteralOccurrence
import com.snippetveil.core.Occurrence
import com.snippetveil.core.SnippetPlan
import com.snippetveil.core.SourceLanguage
import com.snippetveil.core.StackTrace
import com.snippetveil.core.SymbolEvidence
import com.snippetveil.core.SymbolOccurrence
import com.snippetveil.core.TraceName

/**
 * **Describes a stack trace truthfully, and decides nothing about it** — the half of the trace
 * invocation that needs an index.
 *
 * `parseTrace` has already said where every name is; this resolves each class name it returned and
 * reports what the platform says about the symbol, in exactly the words [SymbolFacts] reports it for
 * a snippet. **That is the whole of the correlation the action exists for**: a frame's class and
 * method reach the engine carrying the evidence a snippet of the same class carries, so they take the
 * placeholder the ledger already holds for it — or mint the one a later snippet will find.
 *
 * ### Resolve-and-mint, not ledger lookup
 *
 * Each class name goes to [JavaPsiFacade.findClass] over [GlobalSearchScope.allScope] and is
 * classified by the ownership rule the Java path uses, which lives in [SymbolFacts] and in the
 * engine, not here. A lookup in the ledger alone was disqualified: it cannot tell
 * `com.acme.internal.Foo` from `java.util.HashMap`, since neither may ever have been named before.
 *
 * **A class or a method that does not resolve is one whole `Unknown`** — the whole qualified name,
 * package included, as a single placeholder. Resolving the package of a class that did not resolve is
 * partial resolution, and not this builder's to do.
 *
 * ### The file name
 *
 * It renders the placeholder of **the symbol the file's name is fixed to**, which in Java is the
 * public top-level class, with the **real extension of the resolved file** read off its
 * `VirtualFile` — never a constant. `com.acme.Foo$Bar.baz(Foo.java:12)` is named after the *outer*
 * class, so rendering the frame class's own placeholder there would print a false file name. Where
 * there is no such symbol — a package-private class, a class that did not resolve — the file name is
 * a bare `Unknown`, with no extension at all.
 *
 * ### Texts are literals
 *
 * The thread name and every exception message are reported as string literals and redacted to `str`
 * like any other, library exceptions' included: ownership belongs to symbols, and nothing here owns
 * the text.
 */
internal object TracePlanBuilder {

    /** The plan for [trace], built inside a read action in smart mode. */
    fun build(project: Project, trace: StackTrace): SnippetPlan {
        val resolver = Resolver(project)
        val occurrences = mutableListOf<Occurrence>()

        for (exception in trace.exceptions) occurrences += resolver.classNamed(exception).occurrences
        for (frame in trace.frames) {
            val type = resolver.classNamed(frame.type)
            occurrences += type.occurrences
            frame.method?.let { occurrences += resolver.method(type.resolved, it) }
            frame.file?.let { occurrences += resolver.file(type.resolved, it) }
        }
        for (text in trace.texts) {
            occurrences += LiteralOccurrence(
                start = text.start,
                end = text.end,
                kind = LiteralKind.STRING,
                contentStart = text.start,
                contentEnd = text.end,
                language = LANGUAGE,
            )
        }

        // No root package: there is no analysed file here to have one. See `SnippetPlan.rootPackage`.
        return SnippetPlan(trace.text, occurrences.sortedBy { it.start })
    }

    /** One class name as the trace wrote it, and what it resolved to — or `null` where it did not. */
    private class ResolvedClass(val resolved: PsiClass?, val occurrences: List<Occurrence>)

    private class Resolver(private val project: Project) {

        private val facade = JavaPsiFacade.getInstance(project)
        private val scope = GlobalSearchScope.allScope(project)

        /**
         * The class [name] names, reported segment by segment — each package segment, the outer
         * class and every class nested in it — or one whole `Unknown` where it does not resolve.
         *
         * **The resolution is checked against the spelling.** A binary name is looked up with its
         * `$` read as a `.`, and `com.acme.Foo$Bar` could then find a class `Bar` in a package
         * `com.acme.Foo`; a class is accepted only where its own chain spells the name back exactly,
         * and anything else fails closed.
         */
        fun classNamed(name: TraceName): ResolvedClass {
            val found = find(name.text) ?: return unresolved(name)
            val chain = generateSequence(found) { it.containingClass }.toList().asReversed()
            val outer = chain.first()
            val packageName = outer.qualifiedName?.substringBeforeLast('.', "").orEmpty()
            val spelled = listOfNotNull(packageName.takeIf { it.isNotEmpty() }, chain.joinToString("$") { it.name.orEmpty() })
                .joinToString(".")
            if (spelled != name.text) return unresolved(name)

            val occurrences = mutableListOf<Occurrence>()
            var at = name.start
            if (packageName.isNotEmpty()) {
                val segments = packageName.split('.')
                for ((index, segment) in segments.withIndex()) {
                    val qualified = segments.take(index + 1).joinToString(".")
                    val psiPackage = facade.findPackage(qualified) ?: return unresolved(name)
                    occurrences += symbol(at, segment, SymbolFacts.evidenceOf(project, psiPackage, segment))
                    at += segment.length + 1
                }
            }
            for (type in chain) {
                val simple = type.name.orEmpty()
                occurrences += symbol(at, simple, SymbolFacts.evidenceOf(project, type, simple))
                at += simple.length + 1
            }
            return ResolvedClass(found, occurrences)
        }

        /**
         * The method [name] names in [owner], or a whole `Unknown` where either did not resolve.
         *
         * **By name, and the first overload found is as good as any**: a method's key omits its
         * signature, so every overload shares one placeholder — see [SymbolKeys.keyOf]. Declared in
         * the frame's own class only, because a frame names the class whose code was running.
         */
        fun method(owner: PsiClass?, name: TraceName): Occurrence {
            val method = owner?.findMethodsByName(name.text, false)?.firstOrNull()
                ?: return symbol(name.start, name.text, SymbolFacts.unresolvedEvidence(name.text))
            return symbol(name.start, name.text, SymbolFacts.evidenceOf(project, method, name.text))
        }

        /**
         * The file name, rendered as **the placeholder of the class the file's name is fixed to** with
         * the resolved file's real extension — or a bare `Unknown` where no class fixes it.
         *
         * The class's navigation element rather than the class, so that a library class with sources
         * attached names its `.java` rather than the `.class` it was compiled to.
         *
         * **The printed name has to be that file's name**, extension aside: a trace that says
         * `Other.java` where the class resolved into `Foo.java` is naming a file this index does not
         * have, and rendering `Foo`'s placeholder there would print a file name the trace never did.
         * The extension is left out of the comparison because a compiled library class resolves to
         * its `.class`, and the trace prints the source it was compiled from.
         */
        fun file(owner: PsiClass?, name: TraceName): Occurrence {
            val outer = owner?.let { generateSequence(it) { nested -> nested.containingClass }.last() }
            val source = (outer?.navigationElement as? PsiClass) ?: outer
            val file = source?.containingFile as? PsiJavaFile
            val virtualFile = file?.virtualFile
            val stem = virtualFile?.nameWithoutExtension
            val fixedTo = file?.classes?.firstOrNull { it.hasModifierProperty(PsiModifier.PUBLIC) && it.name == stem }
                ?.takeIf { name.text.substringBefore('.') == stem }
            if (fixedTo == null || virtualFile == null) {
                return symbol(name.start, name.text, SymbolFacts.unresolvedEvidence(name.text))
            }
            val extension = virtualFile.extension?.let { ".$it" }.orEmpty()
            return SymbolOccurrence(
                start = name.start,
                end = name.end,
                text = name.text,
                symbol = SymbolFacts.evidenceOf(project, fixedTo, fixedTo.name.orEmpty()),
                language = LANGUAGE,
                suffix = extension,
            )
        }

        /**
         * The class a binary name names: as written first, which is how a class whose own name holds
         * a `$` is found, and then with each `$` read as the `.` the platform qualifies nesting with.
         */
        private fun find(binaryName: String): PsiClass? =
            facade.findClass(binaryName, scope) ?: facade.findClass(binaryName.replace('$', '.'), scope)

        private fun unresolved(name: TraceName) =
            ResolvedClass(null, listOf(symbol(name.start, name.text, SymbolFacts.unresolvedEvidence(name.text))))

        private fun symbol(start: Int, text: String, evidence: SymbolEvidence) =
            SymbolOccurrence(start, start + text.length, text, evidence, LANGUAGE)
    }

    /**
     * **A frame names a Java symbol whichever language declared it**: the trace is the JVM's
     * rendering, and what is spliced is a placeholder over a whole name, never a Kotlin spelling of
     * one.
     */
    private val LANGUAGE = SourceLanguage.JAVA
}
