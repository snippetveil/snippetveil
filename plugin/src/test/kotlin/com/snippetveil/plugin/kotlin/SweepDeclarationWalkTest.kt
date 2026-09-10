package com.snippetveil.plugin.kotlin

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiFile
import com.snippetveil.sweep.SourceDeclarations
import com.snippetveil.sweep.SourceSpellings
import org.jetbrains.kotlin.asJava.KotlinAsJavaSupport
import org.jetbrains.kotlin.psi.KtFile

/**
 * **The corpus sweep's declaration walk, over real PSI in both languages** — and held against the
 * names the platform itself gives the same Kotlin declarations.
 *
 * It lives in the Kotlin fixture package rather than beside the sweep for the reason every Kotlin
 * fixture does: the floor cell's Kotlin plugin runs in K1, a mode this plugin declares unsupported, and
 * the package is what the floor excludes. The walk itself resolves nothing and would parse there; the
 * light classes this test reads to check it would not be honest there.
 *
 * **The light classes are this test's oracle, and never the walk's.** The walk derives every spelling
 * from declaration text and file names, so that the leak check stays independent of the anonymiser,
 * which keys Kotlin through exactly these light elements. Reading them here is how the string mangling
 * is shown to agree with the compiler, without the product's walk ever depending on them.
 */
internal class SweepDeclarationWalkTest : KotlinSnippetTestCase() {

    fun `test a name declared in a Kotlin file enters the universe`() {
        assertTheSessionIsK2()
        val kotlin = myFixture.addFileToProject(
            "com/acme/ledger/Payment.kt",
            """
            package com.acme.ledger

            class Payment(val merchantRef: String) {
                fun settle(amount: Int): Int = amount
            }
            """.trimIndent(),
        )

        assertIn(spellingsOf(kotlin), "Payment", "merchantRef", "getMerchantRef", "settle", "amount", "acme", "ledger")
    }

    /** The shape the ticket was filed over: both halves read, and the Kotlin half adding to the Java one. */
    fun `test a mixed-language corpus has a universe larger than its Java half`() {
        assertTheSessionIsK2()
        val java = myFixture.addFileToProject(
            "com/acme/ledger/Invoice.java",
            """
            package com.acme.ledger;

            public class Invoice {
                private String body;
                public String getBody() { return body; }
            }
            """.trimIndent(),
        )
        val kotlin = myFixture.addFileToProject(
            "com/acme/ledger/Payment.kt",
            """
            package com.acme.ledger

            class Payment(val merchantRef: String)
            """.trimIndent(),
        )

        val javaHalf = spellingsOf(java)
        val whole = spellingsOf(java, kotlin)

        assertTrue("The Java half is not contained in the whole: ${javaHalf.names - whole.names}", whole.names.containsAll(javaHalf.names))
        assertTrue(
            "A mixed corpus reported ${whole.names.size} spelling(s) against ${javaHalf.names.size} for its Java half alone.",
            whole.names.size > javaHalf.names.size,
        )
    }

    /** Java declares `getBody()`, and Kotlin writes `javaObj.body` — a name no source text declares. */
    fun `test a Java getter is spelled as the Kotlin property it stands for`() {
        val java = myFixture.addFileToProject(
            "com/acme/ledger/Invoice.java",
            """
            package com.acme.ledger;

            public class Invoice {
                public String getBody() { return ""; }
                public boolean isSettled() { return false; }
            }
            """.trimIndent(),
        )

        assertIn(spellingsOf(java), "getBody", "body", "isSettled")
    }

    /**
     * **Every name the platform gives a Kotlin declaration in Java is a spelling the walk derived** —
     * accessors, the `is` forms, a facade and a `@file:JvmName` one, a `@JvmName`, a named companion,
     * an object's instance and an `internal` member's mangled name, all read off the light classes.
     *
     * One direction on purpose. The closure is allowed to over-include — a spelling nobody compiled
     * costs nothing — and what it may never do is miss a spelling the compiler writes.
     */
    fun `test every Java-side name of a Kotlin declaration is a spelling the walk derived`() {
        assertTheSessionIsK2()
        val ledger = myFixture.addFileToProject(
            "com/acme/ledger/Ledger.kt",
            """
            package com.acme.ledger

            val ledgerLimit: Int = 10

            fun settle(amount: Int): Int = amount

            class Payment(val merchantRef: String, var body: String) {
                var isSettled: Boolean = false
                internal var cursor: Int = 0

                internal fun reconcile(): Int = cursor

                @JvmName("settleNow")
                fun settleLater() {}

                companion object Registry {
                    fun lookup(): Payment? = null
                }
            }

            object Settlement {
                fun run() {}
            }
            """.trimIndent(),
        )
        val renamed = myFixture.addFileToProject(
            "com/acme/ledger/Batches.kt",
            """
            @file:JvmName("Settlements")
            package com.acme.ledger

            fun batch(): Int = 1
            """.trimIndent(),
        )

        val spellings = spellingsOf(ledger, renamed)
        val missed = javaSideNamesOf(ledger, renamed).filter { (owner, name) ->
            name !in spellings.names && "$owner.$name" !in spellings.names
        }

        assertTrue(
            "The platform writes these Java-side names for the fixture's Kotlin declarations, and the " +
                "walk derived none of them — a leak under any of them would be reported clean: $missed",
            missed.isEmpty(),
        )
        // The light classes are only an oracle if they are there: a fixture whose facade or mangled
        // member had gone missing would pass the check above vacuously.
        val names = javaSideNamesOf(ledger, renamed).map { it.second }
        assertTrue("The light classes did not name the facade, so the check proved nothing: $names", "LedgerKt" in names)
        assertTrue("The light classes did not name the file's @JvmName facade: $names", "Settlements" in names)
        assertTrue("The light classes did not name a mangled internal member: $names", names.any { it.startsWith("reconcile$") })
    }

    /**
     * `it`, `Companion` and `component1` are fixed by the language and declared in no source text, so
     * the walk reads nothing that would put them in — however many a file uses.
     */
    fun `test names the language fixes are not read as declarations`() {
        val kotlin = myFixture.addFileToProject(
            "com/acme/ledger/Entries.kt",
            """
            package com.acme.ledger

            data class Entry(val amount: Int) {
                companion object {
                    fun zero(): Entry = Entry(0)
                }
            }

            fun total(entries: List<Entry>): Int = entries.sumOf { it.component1() }

            fun reset(entry: Entry): Entry = entry.copy(amount = 0)
            """.trimIndent(),
        )

        val spellings = spellingsOf(kotlin)

        listOf("it", "Companion", "component1", "copy").forEach { fixed ->
            assertFalse("`$fixed` is fixed by the language and entered the universe: ${spellings.names.sorted()}", fixed in spellings.names)
        }
        assertIn(spellings, "Entry", "amount", "zero", "total", "entries", "reset", "entry")
    }

    /** A script is a `KtFile`, and the sweep reads source files: its names are not the project's API. */
    fun `test a Kotlin script is not read`() {
        val script = myFixture.addFileToProject("build.gradle.kts", "val scriptOnlyName = 1\n")

        assertFalse(
            "A .kts script was read as a source file.",
            "scriptOnlyName" in SourceDeclarations.of(project, listOf(script.virtualFile)).mapNotNull { it.written },
        )
    }

    private fun spellingsOf(vararg files: PsiFile): SourceSpellings =
        SourceSpellings.of(SourceDeclarations.of(project, files.map { it.virtualFile }))

    /**
     * Every name the platform's light classes give [files] — each class, and each field and method as
     * `(owner, name)` — which is what a Java file referring to those declarations would have to write.
     * Constructors are their class's name, and are read as members like any other.
     */
    private fun javaSideNamesOf(vararg files: PsiFile): List<Pair<String, String>> {
        val classes = files.flatMap { file ->
            val kt = file as KtFile
            kt.classes.toList() + listOfNotNull(KotlinAsJavaSupport.getInstance(project).getLightFacade(kt))
        }
        return classes.flatMap(::withNested).distinctBy { it.qualifiedName }.flatMap { owner ->
            val name = owner.name.orEmpty()
            listOf("" to name) + (owner.fields.map { it.name } + owner.methods.map { it.name }).map { name to it }
        }
    }

    private fun withNested(owner: PsiClass): List<PsiClass> = listOf(owner) + owner.innerClasses.flatMap(::withNested)

    private fun assertIn(spellings: SourceSpellings, vararg expected: String) = expected.forEach { name ->
        assertTrue("`$name` is not in the universe: ${spellings.names.sorted()}", name in spellings.names)
    }
}
