package com.snippetveil.plugin

import com.intellij.notification.NotificationType
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

/**
 * The tracer bullet, end to end: a selection in an editor goes in and anonymized text comes off the
 * clipboard, through the real action, the real PSI and the real index.
 *
 * Every test that depends on resolution calls [assertTheHarnessResolves] first. That is not
 * ceremony — an unresolved reference is *preserved* by this ticket's rules, so a fixture that
 * silently stopped resolving would turn this entire file green while proving nothing.
 */
class CopyAnonymizedActionTest : JavaSnippetTestCase() {

    fun `test the fixture resolves the JDK and a real library`() {
        assertTheHarnessResolves()
    }

    fun `test a selection is anonymized onto the clipboard`() {
        assertTheHarnessResolves()
        myFixture.configureByText(
            "Ledger.java",
            """
            class Ledger {
                <selection>String describe(int amount) {
                    return String.valueOf(amount);
                }</selection>
            }
            """.trimIndent(),
        )

        invokeCopyAnonymized()

        // Byte for byte, including the leading indentation of the lines after the first: the
        // replacement is spliced into the selected string, so nothing but the identifiers moves.
        assertEquals(
            "String method1(int param2) {\n" +
                "        return String.valueOf(param2);\n" +
                "    }",
            clipboard(),
        )
    }

    /**
     * The spine rule, seen from the outside: **anonymize a symbol iff its declaring file is
     * project-owned.** The JDK and third-party libraries alike survive byte-identically — concealing
     * the tech stack is a declared non-goal, and library names are what make a snippet answerable.
     */
    fun `test project types methods fields locals and parameters are renamed and the JDK and libraries are not`() {
        assertTheHarnessResolves()
        myFixture.addFileToProject("com/acme/Money.java", "package com.acme; public class Money {}")
        myFixture.configureByText(
            "Ledger.java",
            """
            import com.acme.Money;
            import org.junit.Assert;

            class Ledger {
                private Money balance;

                <selection>void settle(Money payment) {
                    Money total = balance;
                    Assert.assertNotNull(total);
                    String.valueOf(payment);
                }</selection>
            }
            """.trimIndent(),
        )

        invokeCopyAnonymized()

        assertEquals(
            "void method1(Type2 param3) {\n" +
                "        Type2 local4 = field5;\n" +
                "        Assert.assertNotNull(local4);\n" +
                "        String.valueOf(param3);\n" +
                "    }",
            clipboard(),
        )
    }

    /**
     * Resolution runs over the whole containing file and follows references out of it, so a symbol
     * declared nowhere near the selection still renames — and renames to the same thing everywhere.
     */
    fun `test out-of-selection and cross-file references rename consistently`() {
        assertTheHarnessResolves()
        myFixture.addFileToProject(
            "com/acme/Wallet.java",
            "package com.acme; public class Wallet { public int balance; }",
        )
        myFixture.configureByText(
            "Ledger.java",
            """
            import com.acme.Wallet;

            class Ledger {
                private Wallet wallet;

                <selection>int total() {
                    return wallet.balance + wallet.balance;
                }</selection>
            }
            """.trimIndent(),
        )

        invokeCopyAnonymized()

        assertEquals(
            "int method1() {\n" +
                "        return field2.field3 + field2.field3;\n" +
                "    }",
            clipboard(),
        )
    }

    /** Overloads share a name in source, so they share a placeholder. */
    fun `test overloads collapse and a shadowed parameter and field stay distinct`() {
        assertTheHarnessResolves()
        myFixture.configureByText(
            "Ledger.java",
            """
            class Ledger {
                private int amount;

                <selection>void send(int amount) { this.amount = amount; }
                void send(int amount, int retries) { send(amount); }</selection>
            }
            """.trimIndent(),
        )

        invokeCopyAnonymized()

        assertEquals(
            "void method1(int param2) { this.field3 = param2; }\n" +
                "    void method1(int param4, int param5) { method1(param4); }",
            clipboard(),
        )
    }

    /**
     * **A shape the rules do not recognise must still be concealed.**
     *
     * A record component is a named, project-owned symbol whose PSI type is none of the six the role
     * table lists — and a symbol that falls off that table is dropped from the plan, which means its
     * name is copied verbatim. `merchantRef` is exactly the domain vocabulary the tool exists to
     * remove, so this is a leak and not a gap: the invariant is that **there is no path where a
     * project-owned name reaches the output un-anonymized**, and "the walk did not recognise it" is
     * not an exception to it.
     */
    fun `test a record component is concealed rather than dropped`() {
        assertTheHarnessResolves()
        myFixture.configureByText(
            "Payment.java",
            "<selection>record Payment(String merchantRef) {}</selection>",
        )

        invokeCopyAnonymized()

        val copied = clipboard()
        assertFalse("A project-owned name reached the clipboard verbatim: $copied", copied.contains("merchantRef"))
        assertFalse("A project-owned name reached the clipboard verbatim: $copied", copied.contains("Payment"))
    }

    /**
     * A constructor's identifier **is** its class's name, so it renders as the class's placeholder.
     * Emitting it from the method namespace would produce `public method1(String param1)` inside
     * `class Type1` — a method with no return type, which does not read as anonymized, it reads as
     * broken. The wider set of symbols Java forces to share a name is its own ticket; this one is
     * grammar rather than policy, and leaving it out would ship a visibly broken tracer bullet.
     */
    fun `test a constructor renders as its class`() {
        assertTheHarnessResolves()
        myFixture.configureByText(
            "Ledger.java",
            """
            class Ledger {
                <selection>Ledger() {}
                Ledger open() { return new Ledger(); }</selection>
            }
            """.trimIndent(),
        )

        invokeCopyAnonymized()

        assertEquals(
            "Type1() {}\n" +
                "    Type1 method2() { return new Type1(); }",
            clipboard(),
        )
    }

    /**
     * Package names are left alone, deliberately and temporarily. They rename per segment, so that
     * same-package and different-package stay distinguishable, and that rule is its own ticket —
     * until it lands, folding `java` in `java.util.List` into the type namespace would be strictly
     * worse than leaving it be.
     */
    fun `test package names are not touched yet`() {
        assertTheHarnessResolves()
        myFixture.configureByText(
            "Ledger.java",
            """
            package com.acme.billing;

            <selection>class Ledger {
                java.util.List<String> rows;
            }</selection>
            """.trimIndent(),
        )

        invokeCopyAnonymized()

        assertEquals(
            "class Type1 {\n" +
                "    java.util.List<String> field2;\n" +
                "}",
            clipboard(),
        )
    }

    /**
     * **Selection snapping closes the last fail-open leak.** A selection cutting mid-identifier
     * would otherwise emit raw domain text — here, `chantReference` out of `merchantReference`. The
     * range is snapped outward to whole-token boundaries before anything else runs, so the whole
     * identifier is governed by a rule and the fragment that leaked no longer exists.
     */
    fun `test a selection starting and ending mid-identifier is snapped outward`() {
        assertTheHarnessResolves()
        myFixture.configureByText(
            "Ledger.java",
            """
            class Ledger {
                void audit() {
                    int mer<selection>chantReference = settlementSta</selection>ge;
                }
                int settlementStage;
            }
            """.trimIndent(),
        )

        invokeCopyAnonymized()

        val copied = clipboard()
        assertFalse("A partial real name reached the clipboard: $copied", copied.contains("chantReference"))
        assertFalse("A partial real name reached the clipboard: $copied", copied.contains("settlementSta"))
        assertEquals("local1 = field2", copied)
    }

    /** No selection means the whole file, which is also the case that rules out doing this on the EDT. */
    fun `test no selection anonymizes the whole file`() {
        assertTheHarnessResolves()
        myFixture.configureByText(
            "Ledger.java",
            """
            class Ledger {
                int amount;<caret>
            }
            """.trimIndent(),
        )

        invokeCopyAnonymized()

        assertEquals(
            "class Type1 {\n" +
                "    int field2;\n" +
                "}",
            clipboard(),
        )
    }

    /**
     * A multi-caret copy concatenates its fragments, and the same symbol has to render identically
     * in all of them — otherwise the clipboard contradicts itself in a single paste.
     */
    fun `test multiple carets share one symbol table`() {
        assertTheHarnessResolves()
        myFixture.configureByText(
            "Ledger.java",
            """
            class Ledger {
                int amount;
                <selection>int first() { return amount; }<caret></selection>
                int ignored() { return 0; }
                <selection>int second() { return amount; }<caret></selection>
            }
            """.trimIndent(),
        )

        invokeCopyAnonymized()

        assertEquals(
            "int method1() { return field2; }\n" +
                "int method3() { return field2; }",
            clipboard(),
        )
    }

    /**
     * **The uniqueness invariant extends to preserved names.** `local1` here is a JDK-declared local
     * type name in scope; a placeholder colliding with it would make the mapping many-to-one at the
     * exact moment a human reads the AI's reply back onto real code.
     */
    fun `test a placeholder never collides with an identifier that survives into the output`() {
        assertTheHarnessResolves()
        myFixture.configureByText(
            "Ledger.java",
            """
            class Ledger {
                void audit() {
                    <selection>// local1 was the old name for this
                    int amount = 0;</selection>
                }
            }
            """.trimIndent(),
        )

        invokeCopyAnonymized()

        // `amount` is the first symbol allocated, so it asks for number 1 — and `local1` is already
        // in the output. The number is burnt rather than reused under another prefix.
        assertEquals(
            "// local1 was the old name for this\n" +
                "        int local2 = 0;",
            clipboard(),
        )
    }

    /**
     * **No PSI is mutated, so there is no write action, no command and no undo entry.** Replacements
     * are spliced right-to-left into the selected *string*, which is also why formatting comes out
     * byte-perfect: splicing into the original text cannot damage layout, and no formatter runs.
     */
    fun `test nothing is written to the document and no undo entry is created`() {
        assertTheHarnessResolves()
        myFixture.configureByText(
            "Ledger.java",
            "class Ledger {   int   amount ;\n\n\n    <selection>void audit(){}</selection> }",
        )
        val document = myFixture.editor.document
        val before = document.text
        val stamp = document.modificationStamp
        val undoState = undoState()

        invokeCopyAnonymized()

        assertEquals("The document was modified; something took a write action.", before, document.text)
        assertEquals("The document's modification stamp moved.", stamp, document.modificationStamp)

        // Compared against where the stack stood beforehand rather than against "empty": the
        // fixture's own setup writes files, and what is under test is that *this* action adds
        // nothing.
        assertEquals(
            "An undo entry was created; a copy must leave the undo stack alone.",
            undoState,
            undoState(),
        )
        assertEquals("void method1(){}", clipboard())
    }

    /** Odd spacing is not tidied on the way out. Nothing here has an opinion about layout. */
    fun `test formatting is byte-perfect`() {
        assertTheHarnessResolves()
        val ugly = "void   audit  (  int   amount )   {\n\t\treturn;\n  }"
        myFixture.configureByText("Ledger.java", "class Ledger {\n<selection>$ugly</selection>\n}")

        invokeCopyAnonymized()

        assertEquals(
            ugly.replace("audit", "method1").replace("amount", "param2"),
            clipboard(),
        )
    }

    /**
     * **Nothing to anonymize is a copy, not a refusal.** Refusing would be a behavioural fork the
     * user has to learn, and zero is a truthful and useful reading: it is the moment someone
     * discovers the snippet they were worried about contains nothing of theirs.
     */
    fun `test a snippet with nothing of the project's in it is copied anyway`() {
        assertTheHarnessResolves()
        myFixture.configureByText(
            "Ledger.java",
            "class Ledger { <selection>String.valueOf(1);</selection> }",
        )

        invokeCopyAnonymized()

        assertEquals("String.valueOf(1);", clipboard())
        assertEquals("0 names replaced · 2 preserved", notifications.single().content)
    }

    /** The balloon states mechanism and makes no claim about what it means. */
    fun `test the balloon reports names replaced and names preserved`() {
        assertTheHarnessResolves()
        myFixture.configureByText(
            "Ledger.java",
            "class Ledger { <selection>void audit(int amount) { String.valueOf(amount); }</selection> }",
        )

        invokeCopyAnonymized()

        val balloon = notifications.single()
        assertEquals("Anonymized snippet copied", balloon.title)
        assertEquals("2 names replaced · 2 preserved", balloon.content)
        assertEquals(NotificationType.INFORMATION, balloon.type)
    }

    /**
     * **The Java-file gate: anything it cannot anonymize, it does not offer.**
     *
     * This closes a fail-open no semantic rule owns — a user who has internalised *"SnippetVeil
     * protects me"* will eventually select `application.properties` and reach for it. There is no
     * menu item, so there is nothing to reach for, and the clipboard is never touched.
     */
    fun `test the action is absent outside Java files and present inside them`() {
        setClipboard(PREVIOUS_CLIPBOARD)
        myFixture.configureByText("application.properties", "<selection>merchant.api.key=secret</selection>")

        val outsideJava = myFixture.testAction(CopyAnonymizedAction())

        assertFalse("Copy Anonymized was offered on a .properties file.", outsideJava.isVisible)
        assertFalse(outsideJava.isEnabled)
        assertEquals("The clipboard was touched on a file the action does not handle.", PREVIOUS_CLIPBOARD, clipboard())

        myFixture.configureByText("Ledger.java", "class Ledger {<caret>}")
        assertTrue("Copy Anonymized was not offered on a .java file.", invokeCopyAnonymized().isEnabledAndVisible)
    }

    /**
     * Resolution is index-dependent and `PsiAugmentProvider`s may be skipped in dumb mode, so during
     * indexing the answer would be quietly wrong rather than unavailable. Not implementing
     * `DumbAware` hands that to the platform, which greys the item out with its own tooltip.
     */
    fun `test the action is not DumbAware and is greyed out during indexing`() {
        val action = CopyAnonymizedAction()

        // Asserted on the runtime type rather than with `is`, which the compiler rejects as always
        // false — that rejection is the strongest possible form of this check, and this line is what
        // would go red if someone made the class implement the interface.
        assertFalse(
            "The action is DumbAware; it would run against a half-built index.",
            DumbAware::class.java.isAssignableFrom(action.javaClass),
        )

        // And on the predicate the platform itself reads before greying an item out. Asserting the
        // greying through whichever ActionUtil helper currently performs it is what this test used to
        // do, and the two-platform matrix caught it: `performDumbAwareUpdate` disables the
        // presentation at 2024.1 and does not at 2026.2, because the greying moved. `isDumbAware` is
        // the question both versions ask, and it is the half this plugin actually owns.
        assertFalse(
            "The platform considers the action usable during indexing.",
            DumbService.isDumbAware(action),
        )
    }

    /**
     * **The highest-severity finding on the whole design, as a test.**
     *
     * When the analysis throws, the clipboard is not empty — it holds whatever was there before, and
     * the user's next keystroke is a paste into an AI chat they believe contains anonymized code.
     * Silent failure does not degrade to nothing; it degrades to pasting the previous thing.
     *
     * So two things are asserted, and the second is the load-bearing one: the clipboard is
     * byte-identical, and the message says so in words.
     */
    fun `test a throw in the analysis leaves the clipboard untouched and says so`() {
        assertTheHarnessResolves()
        myFixture.configureByText("Ledger.java", "class Ledger { <selection>int amount;</selection> }")
        setClipboard(PREVIOUS_CLIPBOARD)

        myFixture.testAction(CopyAnonymizedAction { error("the analysis fell over") })
        awaitBackgroundWork()

        assertEquals("The clipboard was changed by a failed invocation.", PREVIOUS_CLIPBOARD, clipboard())
        val balloon = notifications.single()
        assertEquals(NotificationType.ERROR, balloon.type)
        assertTrue(
            "The error does not state the clipboard fact: ${balloon.content}",
            balloon.content.contains("your clipboard was not changed"),
        )
    }

    /** What the undo stack currently offers for the fixture's editor. */
    private fun undoState(): String {
        val fileEditor = TextEditorProvider.getInstance().getTextEditor(myFixture.editor) as TextEditor
        val undo = UndoManager.getInstance(project)
        return "${undo.isUndoAvailable(fileEditor)}:${undo.getUndoActionNameAndDescription(fileEditor)}"
    }

}

private const val PREVIOUS_CLIPBOARD = "the raw snippet the user copied a minute ago"

internal fun clipboard(): String =
    CopyPasteManager.getInstance().contents?.getTransferData(DataFlavor.stringFlavor) as? String ?: ""

internal fun setClipboard(text: String) {
    CopyPasteManager.getInstance().setContents(StringSelection(text))
}
