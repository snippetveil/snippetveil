package com.snippetveil.plugin.kotlin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtilCore
import com.snippetveil.core.SnippetPlan
import com.snippetveil.core.SourceLanguage
import com.snippetveil.core.SymbolOccurrence
import com.snippetveil.core.SymbolOrigin
import com.snippetveil.core.SymbolRole
import com.snippetveil.plugin.SnippetRequest
import com.snippetveil.plugin.selectedRangesOf
import com.snippetveil.plugin.symbols
import org.jetbrains.kotlin.asJava.KotlinAsJavaSupport
import org.jetbrains.kotlin.asJava.LightClassUtil
import org.jetbrains.kotlin.asJava.elements.KtLightElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import java.util.concurrent.Callable

/**
 * **Does the plan describe reality** — the seam from the Kotlin walk's side.
 *
 * Every assertion here is about occurrences: their ranges, their keys, their ownership and their
 * language tag. Not one of them is about what a placeholder comes out as, and that is the point
 * rather than an omission — a **range** assertion is what catches a walk driven off light classes,
 * and an output assertion is not. What placeholders spell in Kotlin is decided elsewhere and is not
 * settled by anything here.
 *
 * The builder is constructed directly, because nothing registers it: the optional descriptor stays
 * empty, so every `.kt` file still takes the gate's stated refusal.
 */
internal class KotlinPlanBuilderTest : KotlinSnippetTestCase() {

    /**
     * **Every occurrence's range is a range in the file** — asserted against the plan's own text,
     * which is what every offset in it indexes into.
     *
     * This is the assertion the whole builder exists for. `JavaPlanBuilder` walks `PsiIdentifier`
     * leaves; Kotlin has none, and the tempting shortcut — walk the light classes the Java↔Kotlin
     * bridge already provides — reports ranges that are **not the ranges in the file**, because a
     * light identifier aliases the Kotlin source token. The next test pins that aliasing directly.
     */
    fun `test every occurrence spells the text at its own range`() {
        assertTheHarnessResolves()
        val plan = kotlinPlanFor(LEDGER_PATH, LEDGER)

        assertTrue("the walk reported no occurrences at all", plan.symbols().isNotEmpty())
        for (occurrence in plan.symbols()) {
            assertEquals(
                "an occurrence's range is not the range of the text it claims",
                occurrence.text,
                plan.text.substring(occurrence.start, occurrence.end),
            )
        }
    }

    /**
     * **A property's occurrence is the property's own token**, and the light accessor that would have
     * been walked instead reports that very same range.
     *
     * Both halves are the test. The second is the platform fact that makes a light-driven walk
     * unusable — `getMerchantRef`'s name identifier *is* `merchantRef`'s — so a walk that took its
     * ranges from the bridge would splice a nine-character accessor placeholder over an
     * eleven-character property token. The first says this walk does not.
     */
    fun `test a property reports its own range and not its light accessor's`() {
        assertTheHarnessResolves()
        val file = ledgerInTheEditor() as KtFile
        val property = PsiTreeUtil.findChildrenOfType(file, KtParameter::class.java).single { it.name == "merchantRef" && it.hasValOrVar() }
        val accessor = LightClassUtil.getLightClassPropertyMethods(property).getter

        assertNotNull("the fixture has no light accessor, so the aliasing this guards cannot be shown", accessor)
        assertEquals("the light accessor is not the one this test is about", "getMerchantRef", accessor!!.name)
        assertEquals(
            "the light accessor no longer aliases the property's token, so this test is pinning nothing",
            property.nameIdentifier!!.textRange,
            accessor.nameIdentifier!!.textRange,
        )

        val plan = KotlinPlanBuilder.build(SnippetRequest(project, file, emptyList()))
        val occurrence = plan.symbols().single { it.text == "merchantRef" && it.symbol.role == SymbolRole.FIELD }

        // The whole file, so a plan offset *is* a file offset — which is what lets this compare the
        // occurrence against the source tree rather than only against the plan's own text. Against
        // the plan alone the two sides come off one leaf and agree with themselves.
        assertEquals(property.nameIdentifier!!.textRange.startOffset, occurrence.start)
        assertEquals(property.nameIdentifier!!.textRange.endOffset, occurrence.end)
        assertEquals("merchantRef", plan.text.substring(occurrence.start, occurrence.end))
        assertEquals("merchantRef", occurrence.symbol.declaredName)
    }

    /**
     * **A selection's occurrences are offsets into the snippet, not into the file** — the translation
     * every offset in a plan depends on, and the one thing a whole-file fixture can never exercise,
     * because there the two are equal.
     *
     * Snapping is asserted in both directions for the reason the Java walk asserts it in both: a
     * notice that fires on every invocation is one nobody reads on the invocation where it matters.
     */
    fun `test a selection reports offsets into the snippet and says whether it was widened`() {
        assertTheHarnessResolves()
        val plan = kotlinPlanFor(
            LEDGER_PATH,
            """
            package com.acme.ledger

            class Ledger {
                fun settle(amount: Int): Int {
                    val owed = amount
                    return ow<selection>ed</selection>
                }
            }
            """.trimIndent(),
        )

        assertEquals("snapping was not reported although the selection cut a token", true, plan.selectionExpanded)
        assertEquals("owed", plan.text)

        val occurrence = plan.symbols().single()
        assertEquals(0, occurrence.start)
        assertEquals("owed", plan.text.substring(occurrence.start, occurrence.end))
        assertEquals(SymbolRole.LOCAL, occurrence.symbol.role)
    }

    /**
     * **Ownership is resolved, never assumed** — the obligation Kotlin adds and Java never had.
     *
     * `charge(merchantRef = x)` names a **parameter of the callee**, which may be in a library, the
     * JDK or another module. A builder that treated a named argument as project-owned by construction
     * — which is exactly what `JavaPlanBuilder` may do, because Java has no syntax that reaches a
     * callee's parameter names — would rename library parameter names and break every call it
     * touches. That is a plausible artifact, which is the class this product refuses outright.
     *
     * Both directions in one fixture, because either alone passes under a builder that got the rule
     * backwards.
     */
    fun `test a named argument's ownership is resolved rather than assumed`() {
        assertTheHarnessResolves()
        assertTheKotlinStandardLibraryIsALibrary()
        val plan = kotlinPlanFor(LEDGER_PATH, LEDGER)

        val library = plan.symbols().single { it.text == "separator" }.symbol
        assertEquals(
            "a named argument on a standard-library call was not classified as the library's: " + library.key,
            SymbolOrigin.LIBRARY,
            library.origin,
        )
        assertEquals(SymbolRole.PARAMETER, library.role)

        // Every `amount` in the fixture — the parameter, the named argument naming it and the body's
        // reference to it — is one symbol, so asserting all of them is the claim without a positional
        // trick to pick the argument out.
        val ours = plan.symbols().filter { it.text == "amount" }
        assertEquals("the declaration, the named argument and the reference should all be reported", 3, ours.size)
        for (occurrence in ours) {
            assertEquals(
                "a named argument on a call declared in this file is not this project's",
                SymbolOrigin.IN_CONTENT,
                occurrence.symbol.origin,
            )
        }
        assertEquals(
            "the named argument and the parameter it names are one symbol and have to share a key",
            1,
            ours.map { it.symbol.key }.toSet().size,
        )
    }

    /**
     * **A callee-named label reference names the callee**, and a declared label stays the project's.
     *
     * `return@forEach` resolves to the lambda, and the lambda is in the analysed file — so a walk that
     * stopped there would classify the token as project-owned and rename it, while `forEach` itself
     * is preserved beside it as the library function it is. The label's spelling is not the author's
     * to choose: Kotlin names a lambda's implicit label after the function it is passed to, so the
     * token renames in lockstep with that function or not at all.
     *
     * A **declared** label is the other half and is unmoved: `outer@` is declared in the file it is
     * jumped from, so it is the project's by construction, and the jump to it reaches the same key.
     */
    fun `test a callee-named label names the callee and a declared label stays the project's`() {
        assertTheHarnessResolves()
        assertTheKotlinStandardLibraryIsALibrary()
        val plan = kotlinPlanFor(LEDGER_PATH, LEDGER)

        val forEaches = plan.symbols().filter { it.text == "forEach" }
        assertEquals("the call and the label it is named after should both be reported", 2, forEaches.size)
        val label = forEaches.single { plan.text[it.start - 1] == '@' }.symbol
        assertEquals(
            "a label named after a library call was not resolved to it: " + label.key,
            SymbolOrigin.LIBRARY,
            label.origin,
        )
        assertEquals(
            "the label does not share the callee's key, so the two cannot rename in lockstep",
            forEaches.single { plan.text[it.start - 1] != '@' }.symbol.key,
            label.key,
        )

        val declared = plan.symbols().filter { it.text == "outer" }
        assertEquals("both the declaration and the jump should be reported", 2, declared.size)
        for (occurrence in declared) {
            assertEquals(SymbolOrigin.IN_CONTENT, occurrence.symbol.origin)
            assertEquals(SymbolRole.LABEL, occurrence.symbol.role)
        }
        assertEquals(
            "the declared label and the jump to it are one symbol and have to share a key",
            declared.first().symbol.key,
            declared.last().symbol.key,
        )
    }

    /**
     * **A file facade's ownership comes from the files behind it, not from a virtual file.**
     *
     * A facade is synthesized rather than declared: it stands for a `.kt` file rather than for any
     * declaration in one, so its `kotlinOrigin` is `null` and the only thing that can answer *whose
     * code is this* is [KtLightClassForFacade.files][org.jetbrains.kotlin.asJava.classes.KtLightClassForFacade.files]. This is a
     * required resolution path rather than an optimisation, and it is asserted directly because no
     * Kotlin fixture can steer the walk into it — Kotlin has no syntax that names its own facade.
     *
     * **What this test cannot do, stated rather than implied.** The obligation was written as *the
     * test fails if ownership is read from `getVirtualFile()`*, and on this platform it cannot: a
     * facade's containing file is a `FakeFileForLightClass` whose virtual file is the `.kt` file, so
     * `PsiUtilCore.getVirtualFile` answers the same thing `files.first()` does and no assertion can
     * separate the two. The premise the obligation rests on — that a light element returns `null`
     * there — did not reproduce, so what is written down instead is the pair of facts that *are*
     * observable: the facade reports no `kotlinOrigin`, which is what makes `files.first()` the only
     * thing left to read it from, and the two paths agree today. If either stops holding, this goes
     * red naming the platform, in the habit of [assertTheFacadeBehaviourIsPinned] — rather than some
     * later rendering test going red and being triaged as a regression here.
     */
    fun `test a file facade is the project's through the files behind it`() {
        assertTheHarnessResolves()
        val file = ledgerInTheEditor() as KtFile
        val facade = KotlinAsJavaSupport.getInstance(project).getLightFacade(file)

        assertNotNull("the fixture produced no file facade, so nothing here holds", facade)
        assertNull(
            "a facade now reports a Kotlin origin of its own, so `files.first()` is no longer the " +
                "only thing that can answer whose code it is; re-read this deliberately",
            (facade as KtLightElement<*, *>).kotlinOrigin,
        )
        assertEquals(listOf(file), facade.files)

        assertEquals(SymbolOrigin.IN_CONTENT, ownershipOf(project, facade))
        assertEquals(
            "a facade's virtual file no longer agrees with the files behind it; re-pin this deliberately",
            file.virtualFile,
            PsiUtilCore.getVirtualFile(facade),
        )
    }

    /**
     * **A package symbol's occurrence carries its qualified name**, asserted on the evidence rather
     * than inferred from rendered output.
     *
     * [com.snippetveil.core.SymbolEvidence.qualifiedName] is what the top-level-segment rule reads,
     * and nothing else reads it at all — so a builder that left it null on a package symbol renames
     * the root segment, silently and everywhere. Measured on the spike: `org` survived 8,043 times in
     * Java output and not once in Kotlin until this field was populated. Load-bearing, and invisible
     * in every rule that consumes it, which is why it is asserted here directly.
     */
    fun `test a package symbol carries its qualified name`() {
        assertTheHarnessResolves()
        addClassInPackage("com.acme.ledger", "Anchor")
        val plan = kotlinPlanFor(LEDGER_PATH, LEDGER)

        val segments = plan.symbols().filter { it.symbol.role == SymbolRole.PACKAGE }
        assertTrue("the package directive reported no package segments at all", segments.isNotEmpty())

        assertEquals("com", segments.first().text)
        assertEquals("com", segments.first().symbol.qualifiedName)
        assertEquals("com.acme", segments.single { it.text == "acme" }.symbol.qualifiedName)
        assertEquals("com.acme.ledger", segments.single { it.text == "ledger" }.symbol.qualifiedName)
    }

    /**
     * **Every occurrence carries the language of its token.**
     *
     * Nothing in this change spells anything differently because of it. The field records what the
     * walk knows, and the walk is the only thing that knows it — which is why it lands with the walk,
     * and why what is asserted is that it is populated and correct rather than that anything consumes
     * it.
     */
    fun `test every occurrence carries Kotlin as its language`() {
        assertTheHarnessResolves()
        val plan = kotlinPlanFor(LEDGER_PATH, LEDGER)

        assertTrue("the walk reported no occurrences at all", plan.occurrences.isNotEmpty())
        assertEquals(
            "an occurrence of a Kotlin token is not tagged Kotlin",
            setOf(SourceLanguage.KOTLIN),
            plan.occurrences.map { it.language }.toSet(),
        )
    }

    /**
     * **The walk runs under a read action in smart mode, on a background thread** — the shape the
     * invocation already has, and the shape the Analysis API requires: it demands a read action,
     * forbids the EDT, and treats dumb mode as *restricted analysis* whose results are documented as
     * possibly incomplete.
     *
     * Asserted by running the production shape rather than by describing it, and asserted against the
     * plan built on the fixture's own thread — a walk that quietly degraded under a read action would
     * come back with a different plan rather than with an error.
     */
    fun `test the walk runs inside a non-blocking read action in smart mode`() {
        assertTheHarnessResolves()
        val file = ledgerInTheEditor()
        val request = SnippetRequest(project, file, selectedRangesOf(myFixture.editor))

        val onThisThread = KotlinPlanBuilder.build(request)
        val inAReadAction = ApplicationManager.getApplication().executeOnPooledThread(
            Callable {
                ReadAction.nonBlocking(Callable { KotlinPlanBuilder.build(request) })
                    .inSmartMode(project)
                    .expireWith(project)
                    .executeSynchronously()
            },
        ).get()

        assertEquals(describe(onThisThread), describe(inAReadAction))
    }

    /**
     * **Nothing the walk hands back reaches into an analysis session** — every field of every
     * occurrence is read here, after the read action the plan was built in has ended.
     *
     * A `KaSession` may not be leaked outside its analysis block and a symbol may not be touched
     * outside the session that produced it, so a plan carrying either would throw at this point
     * rather than answer. It cannot: [SnippetPlan] lives in `:core`, which has no IntelliJ dependency
     * at all — the structural half of this claim is `core does not reach for the IDE`, and this is the
     * half that runs.
     *
     * **Its limit, stated.** This walk opens no analysis block: resolution goes through the Kotlin
     * plugin's reference layer, which enters and leaves its own session inside each `resolve()`. So
     * what is asserted here is that nothing needing a session came back — which is the property that
     * matters, but is *not* a guard on a future walk that calls `analyze { }` itself. That walk needs
     * an assertion of its own.
     */
    fun `test the plan is readable after the read action that built it has ended`() {
        assertTheHarnessResolves()
        val file = ledgerInTheEditor()
        val request = SnippetRequest(project, file, selectedRangesOf(myFixture.editor))

        val inside = ApplicationManager.getApplication().executeOnPooledThread(
            Callable {
                val plan = ReadAction.nonBlocking(Callable { KotlinPlanBuilder.build(request) })
                    .inSmartMode(project)
                    .expireWith(project)
                    .executeSynchronously()
                plan to describe(plan)
            },
        ).get()

        assertEquals(inside.second, describe(inside.first))
    }

    /**
     * **Every key in the plan is the one key rule's**, for every declaration the fixture writes.
     *
     * The walk's job here is to *call* [KotlinSymbolKeys.ledgerKeyOf], not to reach the same answer
     * by its own route — one key rule for two languages is only one rule if nothing computes a second
     * opinion beside it. Whether that rule is *right* is `KotlinSymbolKeyingTest`'s question, and it
     * asks it against the shipped Java walk; this is the seam above it, and it goes red the moment a
     * key derivation reappears in this file.
     *
     * Total over the fixture rather than sampled: every named declaration in it is checked, so a
     * shape the walk started keying for itself cannot hide by not being one of three named here.
     * Both halves of the rule are compared, because [LedgerKey] answers them together — a site taking
     * the key from one place and the flag from another is exactly the fork the rule exists to prevent.
     */
    fun `test every key in the plan is the one key rule's`() {
        assertTheHarnessResolves()
        val file = ledgerInTheEditor() as KtFile

        // The whole file, so a plan offset is a file offset and a declaration's name identifier
        // locates its occurrence exactly.
        val plan = KotlinPlanBuilder.build(SnippetRequest(project, file, emptyList()))

        val declarations = PsiTreeUtil.findChildrenOfType(file, KtNamedDeclaration::class.java)
            .filter { it.nameIdentifier != null }
        assertTrue("the fixture declares nothing, so this asserts nothing", declarations.size >= 4)

        for (declaration in declarations) {
            val at = declaration.nameIdentifier!!.textRange.startOffset
            val occurrence = plan.symbols().single { it.start == at }
            val expected = KotlinSymbolKeys.ledgerKeyOf(declaration)

            assertEquals("the key of " + declaration.name, expected.key, occurrence.symbol.key)
            assertEquals(
                "whether the key of " + declaration.name + " may be written down",
                expected.keyIsQualified,
                occurrence.symbol.keyIsQualified,
            )
        }
    }

    /** [LEDGER], open in the editor — the file every test here that needs the tree rather than the plan reads. */
    private fun ledgerInTheEditor(): PsiFile =
        myFixture.configureByText(LEDGER_PATH.substringAfterLast('/'), LEDGER)

    /** Every field of every occurrence, as one string — so that reading them all is one assertion. */
    private fun describe(plan: SnippetPlan): String = buildString {
        append(plan.text).append('\n').append(plan.rootPackage).append('/').append(plan.selectionExpanded).append('\n')
        for (occurrence in plan.occurrences.filterIsInstance<SymbolOccurrence>()) {
            val symbol = occurrence.symbol
            append(occurrence.start).append(':').append(occurrence.end).append(' ')
                .append(occurrence.text).append(' ').append(occurrence.language).append(' ')
                .append(symbol.key).append(' ').append(symbol.role).append(' ').append(symbol.origin).append(' ')
                .append(symbol.declaredName).append(' ').append(symbol.qualifiedName).append(' ')
                .append(symbol.packageName).append(' ').append(symbol.signature).append(' ')
                .append(symbol.keyIsQualified).append(' ')
                .append(symbol.overrideRoots.map { it.key + "/" + it.origin + "/" + it.packageName + "/" + it.keyIsQualified })
                .append(' ')
                .append(symbol.accessor?.let { it.fieldKey + "/" + it.fieldName + "/" + it.prefix })
                .append('\n')
        }
    }
}

private const val LEDGER_PATH = "com/acme/ledger/Ledger.kt"

/**
 * One file carrying every shape this suite asserts on, because they are assertions about one walk
 * over one tree and splitting them into a fixture apiece would be six walks agreeing with themselves.
 *
 * `settle` is called with a named argument naming a parameter **declared here**; `joinToString` with
 * one naming a parameter in the **standard library**. `outer@` is a declared label and `return@forEach`
 * a callee-named one. `merchantRef` is a property whose light accessor aliases its token.
 */
private val LEDGER = """
    package com.acme.ledger

    class Ledger(val merchantRef: String) {

        fun settle(amount: Int): Int = amount

        fun walk(items: List<Int>): String {
            settle(amount = 1)
            outer@ for (item in items) {
                break@outer
            }
            items.forEach { return@forEach }
            return items.joinToString(separator = ",")
        }
    }

    fun ledgerOf(merchantRef: String): Ledger = Ledger(merchantRef)
""".trimIndent()
