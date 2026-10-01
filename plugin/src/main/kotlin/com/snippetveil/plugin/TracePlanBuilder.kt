package com.snippetveil.plugin

import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
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
 * ### Longest prefix first, and the remainder replaced whole
 *
 * Synthetic frames are where a real trace spends most of its names — lambdas, coroutine state
 * machines, proxies, inner classes — and the owning class of one usually resolves perfectly well.
 * So **at each name position, the longest prefix of the JVM binary name the index accepts is
 * resolved, and what is left over is one `Unknown`**:
 *
 * ```
 * com.acme.BillingService$charge$1.invokeSuspend(BillingService.java:42)
 *     ->  com.pkg1.Type2$Unknown3.invokeSuspend(Type2.java:42)
 * com.acme.BillingService.charge$suspendImpl(BillingService.java:42)
 *     ->  com.pkg1.Type2.method4$Unknown5(Type2.java:42)
 * ```
 *
 * **Text only enumerates the candidates, and the index decides.** A candidate is the name cut before
 * one of its `$`s, tried longest first; each is resolved exactly as a whole name is, spelling check
 * included. Longest-first is monotone: a wrong split resolves a *shorter* prefix and drops *more*
 * into `Unknown`, so no split can print more than the right one. That is what makes this allowed
 * where de-mangling is not — stripping `$$…` or `lambda$…` to recover an owner decides a symbol from
 * the shape of its text and fails toward printing more, and nothing here does it. A name with no
 * accepted prefix is still **one whole `Unknown`**, package included; the cuts are at `$` only, so a
 * class that does not resolve does not have its package resolved instead.
 *
 * **The remainder is replaced wholesale and never read.** `$charge$1` carries `charge`, a declared
 * method's name: printing it leaks, and renaming the `charge` in it would be text surgery deciding
 * that a `$`-segment is a method. It is reported whole as [SymbolEvidence.remainder], so it renders
 * one `Unknown`, counts as one, is never offered for preserve, and its text lives in the mapping
 * a reply decodes against and nowhere a person reads.
 *
 * A frame whose class only partly resolved has **no method to look up** — the class it would be
 * declared in is the compiler's — so its method is one whole `Unknown`, unless the language fixed its
 * spelling and `parseTrace` reported no method at all, as it does for `invokeSuspend`. Its **file
 * name** is still the resolved prefix's file's, which is the file the compiler generated it from.
 *
 * ### The file name
 *
 * It renders the placeholder of **the symbol the file's name is fixed to**, which in Java is the
 * public top-level class and in Kotlin is the **file facade** (`BillingKt`), with the **real
 * extension of the resolved file** read off its `VirtualFile` — never a constant. Which symbol that
 * is, is the language's rule: see [LanguageSupport.traceFileOf]. `com.acme.Foo$Bar.baz(Foo.java:12)`
 * is named after the *outer* class, so rendering the frame class's own placeholder there would print
 * a false file name; a Kotlin file's name is tied to no class at all, so a frame of `class Payment`
 * in `Billing.kt` renders `BillingKt`'s. Where there is no such symbol — a package-private class, a
 * `.kt` with no top-level callable and so no facade, a class that did not resolve — the file name is
 * a bare `Unknown`, with no extension at all.
 *
 * ### Texts are literals
 *
 * The thread name and every exception message are reported as string literals and redacted to `str`
 * like any other, library exceptions' included: ownership belongs to symbols, and nothing here owns
 * the text.
 *
 * ### The org root is the trace's own
 *
 * A snippet's internal-org root is its file's root package, and a trace has no file. So the plan says
 * so, and **the engine takes the roots of the types this resolution found in the project**:
 * `com.acme.billing.BillingService` in project content makes `com.acme.platform.HttpClient`, a
 * library class, internal-org. A `_CREATION` block's frames are frames and count like any other. A
 * trace where nothing resolves as the project's derives no root, and only the editable prefix list
 * applies to it — see [SnippetPlan.rootFromOwnedTypes] for why that is intended.
 *
 * ### A foreign trace is mostly `Unknown`, and is not refused
 *
 * A trace from a colleague's project or an old build resolves little or nothing here, and comes back
 * as `Unknown`s. That is the rule applied, and it says something true: *this trace is not about code
 * you have open*. Nothing is refused for it, and no `java.`/`javax.` list pulls unresolved names out
 * of anonymization on the strength of their text.
 *
 * **Only a resolved name can be written down.** A resolved frame's class has a qualified key, so a
 * confirmed trace seeds the ledger with it exactly as a snippet would; an unresolved frame's
 * class-shaped string is text, keyed as such, and nothing is written for it.
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
            frame.method?.let { occurrences += resolver.method(type.declaring, it) }
            frame.file?.let { occurrences += resolver.file(type.prefix, it) }
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

        // No file, so no file root: the org roots are those of the frames the index found in the
        // project. See `SnippetPlan.rootFromOwnedTypes`.
        return SnippetPlan(trace.text, occurrences.sortedBy { it.start }, rootFromOwnedTypes = true)
    }

    /**
     * One class name as the trace wrote it, and what it resolved to.
     *
     * @param declaring the class the **whole** name resolved to, which is where the frame's method is
     *   looked up — `null` where only a prefix resolved, or nothing did
     * @param prefix the class the **longest accepted prefix** resolved to, which is whose file the
     *   frame's file name is — the whole name's class where the whole name resolved, and `null` where
     *   nothing did
     */
    private class ResolvedClass(val declaring: PsiClass?, val prefix: PsiClass?, val occurrences: List<Occurrence>)

    private class Resolver(private val project: Project) {

        private val facade = JavaPsiFacade.getInstance(project)
        private val scope = GlobalSearchScope.allScope(project)

        /** Each distinct remainder of this trace, numbered as first met — its key, which its text is not. */
        private val remainders = HashMap<String, Int>()

        /**
         * The class [name] names, reported segment by segment — each package segment, the outer
         * class and every class nested in it — then, where only a prefix of it resolved, **the rest
         * as one remainder**, and one whole `Unknown` where no prefix did.
         *
         * The whole name is the longest candidate, so it is tried first; then the name cut before
         * each of its `$`s, longest first. See the class comment for why that order is the whole
         * argument.
         */
        fun classNamed(name: TraceName): ResolvedClass {
            resolvedExactly(name.text, name.start)?.let { (found, occurrences) -> return ResolvedClass(found, found, occurrences) }
            for (cut in prefixCuts(name.text)) {
                val (found, occurrences) = resolvedExactly(name.text.substring(0, cut), name.start) ?: continue
                return ResolvedClass(null, found, occurrences + remainderOf(name, cut))
            }
            return ResolvedClass(null, null, listOf(unresolved(name)))
        }

        /**
         * The class [binaryName] names, and its segments as occurrences starting at [start] — or
         * `null` where it does not resolve.
         *
         * **The resolution is checked against the spelling.** A binary name is looked up with its
         * `$` read as a `.`, and `com.acme.Foo$Bar` could then find a class `Bar` in a package
         * `com.acme.Foo`; a class is accepted only where its own chain spells the name back exactly,
         * and anything else fails closed.
         */
        private fun resolvedExactly(binaryName: String, start: Int): Pair<PsiClass, List<Occurrence>>? {
            val found = find(binaryName) ?: return null
            val chain = generateSequence(found) { it.containingClass }.toList().asReversed()
            val outer = chain.first()
            val packageName = outer.qualifiedName?.substringBeforeLast('.', "").orEmpty()
            val spelled = listOfNotNull(packageName.takeIf { it.isNotEmpty() }, chain.joinToString("$") { it.name.orEmpty() })
                .joinToString(".")
            if (spelled != binaryName) return null

            val occurrences = mutableListOf<Occurrence>()
            var at = start
            if (packageName.isNotEmpty()) {
                val segments = packageName.split('.')
                for ((index, segment) in segments.withIndex()) {
                    val qualified = segments.take(index + 1).joinToString(".")
                    val psiPackage = facade.findPackage(qualified) ?: return null
                    occurrences += symbol(at, segment, SymbolFacts.evidenceOf(project, psiPackage, segment))
                    at += segment.length + 1
                }
            }
            for (type in chain) {
                val simple = type.name.orEmpty()
                occurrences += symbol(at, simple, SymbolFacts.evidenceOf(project, type, simple))
                at += simple.length + 1
            }
            return found to occurrences
        }

        /**
         * The method [name] names in [owner] — or, where only a prefix of it names one, that method
         * followed by the rest as one remainder — or a whole `Unknown` where nothing resolved.
         *
         * `charge$suspendImpl` is the case: the compiler's name for a suspend function's body, spelled
         * after the declared function, so it renders that function's placeholder and an `Unknown`. The
         * candidates are cut at `$` and tried longest first, exactly as a class name's are.
         *
         * **By name, and the first overload found is as good as any**: a method's key omits its
         * signature, so every overload shares one placeholder — see [SymbolKeys.keyOf]. Declared in
         * the frame's own class only, because a frame names the class whose code was running.
         */
        fun method(owner: PsiClass?, name: TraceName): List<Occurrence> {
            if (owner != null) {
                methodNamed(owner, name.text, name.start)?.let { return listOf(it) }
                for (cut in prefixCuts(name.text)) {
                    val prefix = methodNamed(owner, name.text.substring(0, cut), name.start) ?: continue
                    return listOf(prefix, remainderOf(name, cut))
                }
            }
            return listOf(unresolved(name))
        }

        private fun methodNamed(owner: PsiClass, written: String, start: Int): Occurrence? {
            val method = owner.findMethodsByName(written, false).firstOrNull() ?: return null
            return symbol(start, written, SymbolFacts.evidenceOf(project, method, written))
        }

        /**
         * The file name, rendered as **the placeholder of the class the file's name is fixed to** with
         * the resolved file's real extension — or a bare `Unknown` where no class fixes it. Which class
         * that is, is the language's rule, and [traceFileOf] asks the language.
         *
         * **The printed name has to be that file's name**, extension aside: a trace that says
         * `Other.java` where the class resolved into `Foo.java` is naming a file this index does not
         * have, and rendering `Foo`'s placeholder there would print a file name the trace never did.
         * The extension is left out of the comparison because a compiled library class resolves to
         * its `.class`, and the trace prints the source it was compiled from.
         */
        fun file(owner: PsiClass?, name: TraceName): Occurrence {
            val outer = owner?.let { generateSequence(it) { nested -> nested.containingClass }.last() }
            val traceFile = outer?.let(::traceFileOf)
            val fixedTo = traceFile?.fixedTo?.takeIf { traceFile.isNamedBy(name.text) }
                ?: return symbol(name.start, name.text, SymbolFacts.unresolvedEvidence(name.text))
            return SymbolOccurrence(
                start = name.start,
                end = name.end,
                text = name.text,
                symbol = SymbolFacts.evidenceOf(project, fixedTo, fixedTo.name.orEmpty()),
                language = LANGUAGE,
                suffix = traceFile.suffix,
            )
        }

        /**
         * The class a binary name names: as written first, which is how a class whose own name holds
         * a `$` is found, and then with each `$` read as the `.` the platform qualifies nesting with.
         */
        private fun find(binaryName: String): PsiClass? =
            facade.findClass(binaryName, scope) ?: facade.findClass(binaryName.replace('$', '.'), scope)

        /** [name], whole, as one `Unknown`. */
        private fun unresolved(name: TraceName): Occurrence = symbol(name.start, name.text, SymbolFacts.unresolvedEvidence(name.text))

        /**
         * What follows the `$` at [cut] in [name], **whole**, as one remainder — see
         * [SymbolEvidence.remainder]. The `$` itself stays where it is, as the separator the output
         * reads `Type1$Unknown2` by.
         */
        private fun remainderOf(name: TraceName, cut: Int): Occurrence {
            val remainder = name.text.substring(cut + 1)
            val ordinal = remainders.getOrPut(remainder) { remainders.size + 1 }
            return symbol(name.start + cut + 1, remainder, SymbolFacts.remainderEvidence(remainder, ordinal))
        }

        private fun symbol(start: Int, text: String, evidence: SymbolEvidence) =
            SymbolOccurrence(start, start + text.length, text, evidence, LANGUAGE)
    }

    /**
     * **Where [written] may be cut into a prefix and a remainder**: before each `$`, longest prefix
     * first. The text only enumerates the candidates — whether a prefix is one is the index's
     * question, asked by the caller. A cut that would leave the prefix or the remainder empty, or the
     * prefix ending in a `$` of its own, is no candidate: `Foo$$Proxy` is tried as `Foo` and nothing
     * else.
     */
    private fun prefixCuts(written: String): List<Int> =
        written.indices.reversed().filter { cut ->
            written[cut] == '$' && cut > 0 && cut < written.lastIndex && written[cut - 1] != '$' && written[cut - 1] != '.'
        }

    /**
     * **A frame names a Java symbol whichever language declared it**: the trace is the JVM's
     * rendering, and what is spliced is a placeholder over a whole name, never a Kotlin spelling of
     * one.
     */
    private val LANGUAGE = SourceLanguage.JAVA
}
