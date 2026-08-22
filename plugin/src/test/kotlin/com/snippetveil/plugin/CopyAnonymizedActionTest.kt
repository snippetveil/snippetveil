package com.snippetveil.plugin

import com.intellij.notification.NotificationType
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.snippetveil.core.LedgerDelta
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

/**
 * The tracer bullet, end to end: a selection in an editor goes in and anonymized text comes off the
 * clipboard, through the real action, the real PSI and the real index.
 *
 * Every test that depends on resolution calls [assertTheHarnessResolves] first. That is not
 * ceremony — a fixture that silently stopped resolving would classify the JDK, every library and
 * all of the user's own code alike as `Unknown`, and every assertion here that only checks a name
 * was concealed would stay green while proving nothing.
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
     * A qualified name the project does not own survives segment by segment, and a selection that
     * excludes the file's import block simply has nothing to rewrite — which is the common case, and
     * the one a user selecting a method body gets.
     *
     * `java.util.List` is preserved because the JDK owns every part of it, not because of where the
     * dots fall: a package's origin is asked of the directories behind it exactly as a type's is
     * asked of its file.
     */
    fun `test a qualified JDK name is preserved segment by segment`() {
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
     * **The golden for an import block rewritten in place** — and one of the few things here a
     * structural assertion cannot express, because what is being asserted is that the lines are
     * still *there*, still in order, and still spelled like imports.
     *
     * **A missing import is indistinguishable from a bug and the reviewer will report it.** That is
     * the manufactured-false-positive failure this rule exists to prevent, and dropping lines a user
     * selected is separately the kind of surprise that costs trust. So imports of anonymized project
     * types are rewritten, never deleted, and third-party imports come out byte-identical.
     *
     * Read the package placeholders across the three qualified names: `com` is passed through,
     * `com.acme` is `pkg1` in both of the project's own, and `com.acme.web` is `pkg3` where
     * `com.acme.billing` is `pkg2`. Same-package and different-package survive the rename, which is
     * the whole reason segments are renamed one at a time.
     */
    fun `test an import block is rewritten in place and third-party imports are byte-identical`() {
        assertTheHarnessResolves()
        addClassInPackage("com.acme.billing", "Refund")
        addClassInPackage("com.acme.web", "Controller")
        myFixture.configureByText(
            "Ledger.java",
            """
            <selection>package com.acme.billing;

            import com.acme.web.Controller;
            import org.junit.Assert;

            class Ledger {
                Controller controller;

                void audit() {
                    Assert.assertNotNull(controller);
                }
            }</selection>
            """.trimIndent(),
        )

        invokeCopyAnonymized()

        assertEquals(
            "package com.pkg1.pkg2;\n" +
                "\n" +
                "import com.pkg1.pkg3.Type4;\n" +
                "import org.junit.Assert;\n" +
                "\n" +
                "class Type5 {\n" +
                "    Type4 field6;\n" +
                "\n" +
                "    void method7() {\n" +
                "        Assert.assertNotNull(field6);\n" +
                "    }\n" +
                "}",
            clipboard(),
        )
    }

    /**
     * A static import needs no rule of its own either: the segments resolve as packages, the type as
     * a type and the member as a member, so the line is rewritten in place like any other import.
     *
     * It gets an assertion anyway because the resolution path is the one that could quietly differ —
     * a static import's reference is its own PSI shape, and a member reported `UNRESOLVED` here
     * would put `Unknown` in an import line and read as broken code rather than as anonymized code.
     */
    fun `test a static import is rewritten in place`() {
        assertTheHarnessResolves()
        myFixture.addFileToProject(
            "com/acme/billing/Limits.java",
            "package com.acme.billing; public class Limits { public static final int MAX = 5; }",
        )
        myFixture.configureByText(
            "Ledger.java",
            """
            <selection>import static com.acme.billing.Limits.MAX;
            import static org.junit.Assert.assertTrue;

            class Ledger {
                int ceiling = MAX;
            }</selection>
            """.trimIndent(),
        )

        invokeCopyAnonymized()

        assertEquals(
            "import static com.pkg1.pkg2.Type3.field4;\n" +
                "import static org.junit.Assert.assertTrue;\n" +
                "\n" +
                "class Type5 {\n" +
                "    int field6 = field4;\n" +
                "}",
            clipboard(),
        )
    }

    /**
     * A nested type renames per segment as a package does, and for the same reason: `Payment.Status`
     * is two symbols, and collapsing them would hide that the enum belongs to the class.
     */
    fun `test a qualified nested type renames with its nesting preserved`() {
        assertTheHarnessResolves()
        myFixture.addFileToProject(
            "com/acme/billing/Payment.java",
            "package com.acme.billing; public class Payment { public enum Status { ACTIVE } }",
        )
        myFixture.configureByText(
            "Ledger.java",
            """
            class Ledger {
                <selection>com.acme.billing.Payment.Status state;</selection>
            }
            """.trimIndent(),
        )

        invokeCopyAnonymized()

        assertEquals("com.pkg1.pkg2.Type3.Type4 field5;", clipboard())
    }

    /**
     * A sealed hierarchy needs no rule of its own, and this is the assertion that says so.
     *
     * The targets of a `permits` clause are ordinary type references and rename like any other, and
     * per-segment package renaming is what keeps the rest coherent: a sealed type and its permitted
     * subclasses have to sit in one package, and after the rename they still visibly do.
     */
    fun `test a sealed hierarchy renames coherently with its permits clause`() {
        assertTheHarnessResolves()
        addClassInPackage("com.acme.billing", "Refund")
        myFixture.configureByText(
            "Payment.java",
            """
            <selection>package com.acme.billing;

            sealed interface Payment permits Card, Wire {}

            final class Card implements Payment {}

            final class Wire implements Payment {}</selection>
            """.trimIndent(),
        )

        invokeCopyAnonymized()

        assertEquals(
            "package com.pkg1.pkg2;\n" +
                "\n" +
                "sealed interface Type3 permits Type4, Type5 {}\n" +
                "\n" +
                "final class Type4 implements Type3 {}\n" +
                "\n" +
                "final class Type5 implements Type3 {}",
            clipboard(),
        )
    }

    /**
     * **An annotation attribute name inherits the ownership of the annotation type that declares
     * it**, and a library annotation is preserved whole — type, attribute names and the enum
     * constant it is given. Annotations are the densest metadata in a snippet and frequently the
     * reason it is answerable at all.
     *
     * `AuditLogged` is meta-annotated by a third-party annotation and is anonymized regardless.
     * **There is deliberately no carve-out for project annotations that behave like framework
     * markers**: such a rule fires on precisely the annotations most likely to be domain-named —
     * `@MerchantFacing`, `@PciScope` — and breaks the one-sentence spine rule that makes the model
     * explainable.
     *
     * What this asserts is the *names*: `action` and `scope` are the project's vocabulary and they
     * go. The attribute *values* go too — a literal carrying no references is replaced whole — and
     * the numbering shows the one counter every namespace shares: `attr2`, `str3`, `attr4`, `str5`
     * in the order they are written.
     */
    fun `test a library annotation is preserved whole and a project annotation loses its attribute names`() {
        assertTheHarnessResolves()
        myFixture.addFileToProject("com/acme/audit/AuditLogged.java", PROJECT_ANNOTATION)
        myFixture.configureByText(
            "Ledger.java",
            """
            import com.acme.audit.AuditLogged;
            import org.junit.FixMethodOrder;
            import org.junit.runners.MethodSorters;

            <selection>@FixMethodOrder(value = MethodSorters.NAME_ASCENDING)
            @AuditLogged(action = "x", scope = "y")
            class Ledger {
            }</selection>
            """.trimIndent(),
        )

        invokeCopyAnonymized()

        assertEquals(
            "@FixMethodOrder(value = MethodSorters.NAME_ASCENDING)\n" +
                "@Anno1(attr2 = \"str3\", attr4 = \"str5\")\n" +
                "class Type6 {\n" +
                "}",
            clipboard(),
        )
    }

    /**
     * **A string literal is anonymized by default, into its own `str` namespace.** A literal is not
     * passive text; it is a domain carrier, like a comment — an experiment counted 38 identifiable
     * literals in the original snippets, and blanket replacement took that to 0 while answering 5 of
     * 6 questions at parity.
     *
     * `String.format("%s-%d", a, b)` becoming `String.format("str1", a, b)` is the accepted cost:
     * it reads as an arity mismatch, which is refusal-class rather than a plausible wrong answer,
     * and the artifact is unmistakable.
     */
    fun `test a string literal is replaced whole`() {
        assertTheHarnessResolves()
        myFixture.configureByText(
            "Ledger.java",
            """
            class Ledger {
                void log(String message) {}

                <selection>void settle() {
                    log("merchant settlement failed");
                }</selection>
            }
            """.trimIndent(),
        )

        invokeCopyAnonymized()

        assertEquals(
            "void method1() {\n" +
                "        method2(\"str3\");\n" +
                "    }",
            clipboard(),
        )
    }

    /**
     * **A text block stays a text block.** Only the content between the delimiters is rewritten, so
     * the form survives — `"""…"""` collapsed to `"str1"` is malformed-looking in a way that reads
     * as a bug in this tool rather than as anonymization.
     *
     * The output is a text block whose closing delimiter now sits on the content's own line, which
     * is deliberate: the incidental indentation is then zero and the value is exactly `str2`.
     */
    fun `test a text block stays a text block`() {
        assertTheHarnessResolves()
        myFixture.configureByText(
            "Ledger.java",
            """
            class Ledger {
                <selection>String query() {
                    return $FENCE
                        SELECT * FROM merchants
                        $FENCE;
                }</selection>
            }
            """.trimIndent(),
        )

        invokeCopyAnonymized()

        assertEquals(
            "String method1() {\n" +
                "        return $FENCE\n" +
                "str2$FENCE;\n" +
                "    }",
            clipboard(),
        )
    }

    /**
     * **An escaped literal stays escaped, and the output still parses.**
     *
     * Only the content between the delimiters is replaced, so there is no path by which a stray
     * backslash or an unbalanced quote reaches the clipboard — but *the output still parses* is the
     * invariant that claim is about, so it is asserted as such, over a text block and an escaped
     * string together.
     */
    fun `test an escaped literal and a text block are replaced and the output still parses`() {
        assertTheHarnessResolves()
        myFixture.configureByText(
            "Ledger.java",
            """
            class Ledger {
                <selection>String describe() {
                    String quoted = "he said \"settle\" \n at 09:00";
                    return quoted + $FENCE
                        SELECT * FROM merchants
                        $FENCE;
                }</selection>
            }
            """.trimIndent(),
        )

        invokeCopyAnonymized()

        assertEquals(
            "String method1() {\n" +
                "        String local2 = \"str3\";\n" +
                "        return local2 + $FENCE\n" +
                "str4$FENCE;\n" +
                "    }",
            clipboard(),
        )
        assertParses("copied", clipboard())

        // And the check proves it can fail before it reports that nothing failed: a text block
        // collapsed to a quoted string is the malformed shape the rule exists to avoid, and it is
        // what a rewrite that replaced the literal *including* its delimiters would produce.
        assertDoesNotParse("collapsed", clipboard().replace(FENCE, "\""))
    }

    /**
     * **A literal carrying resolved references renames in lockstep with the symbols they name.**
     * `JavaClassReferenceSet` yields one reference per dotted segment, so the gaps are the dots —
     * non-alphanumeric, and coverage holds. Nothing here reads the literal's text to decide what any
     * segment becomes: `com` is passed through because it is a top-level package segment, and
     * `Payment` renames because its own symbol does.
     *
     * The reference set the platform hands over is wider than the four segments — the reflection
     * contributor puts an unresolved reference over the whole name as well — and that is why this is
     * an end-to-end test rather than only a plan literal: an engine rule that treated an unresolved
     * reference as a gap would fail here and nowhere in `:core`.
     */
    fun `test a class name in a literal renames segment by segment`() {
        assertTheHarnessResolves()
        addClassInPackage("com.acme.billing", "Payment")
        myFixture.configureByText(
            "Ledger.java",
            """
            class Ledger {
                <selection>Class<?> load() throws Exception {
                    return Class.forName("com.acme.billing.Payment");
                }</selection>
            }
            """.trimIndent(),
        )

        invokeCopyAnonymized()

        assertEquals(
            "Class<?> method1() throws Exception {\n" +
                "        return Class.forName(\"com.pkg2.pkg3.Type4\");\n" +
                "    }",
            clipboard(),
        )
    }

    /**
     * **A type parameter is anonymized as its own kind.** `<T>` carries no domain and
     * `<REQ extends Money>` does, and no rule keeps the first and replaces the second: preserving by
     * name length is inspecting the text. `T1` says *a type parameter* as plainly as `Type2` says
     * *a type*, which is the whole of what the prefixes are for.
     */
    fun `test a type parameter renames into its own namespace`() {
        assertTheHarnessResolves()
        myFixture.addFileToProject("com/acme/Money.java", "package com.acme; public class Money {}")
        myFixture.configureByText(
            "Ledger.java",
            """
            import com.acme.Money;

            class Ledger {
                <selection>static <REQ extends Money> REQ settle(REQ request) {
                    return request;
                }</selection>
            }
            """.trimIndent(),
        )

        invokeCopyAnonymized()

        assertEquals(
            "static <T1 extends Type2> T1 method3(T1 param4) {\n" +
                "        return param4;\n" +
                "    }",
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
     * **The uniqueness invariant is about the output, and a stripped comment is not in it.**
     *
     * A placeholder colliding with a name that survives would make the mapping many-to-one at the
     * exact moment a human reads the AI's reply back onto real code, so such a number is burnt. A
     * word in a comment that was removed is not such a name: it stands for nothing, because it is
     * not there.
     */
    fun `test a placeholder-shaped word in a stripped comment does not burn its number`() {
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

        // The reservation is about the output, not about the snippet: the comment holding `local1`
        // is not on the clipboard, so `local1` there stands for nothing and `amount` takes number 1.
        // The rule that burns a number against a name that *does* survive is asserted in `:core`
        // over a plan literal, where every rule of that shape is asserted.
        assertEquals("        int local1 = 0;", clipboard())
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
        // Inside a method body, which is the only place this is legal Java. It used to sit straight
        // in the class body, where `valueOf` does not resolve — so the assertion that a JDK call is
        // *preserved* was passing on a name that was never classified as one.
        myFixture.configureByText(
            "Ledger.java",
            "class Ledger { void audit() { <selection>String.valueOf(1);</selection> } }",
        )

        invokeCopyAnonymized()

        assertEquals("String.valueOf(1);", clipboard())
        assertEquals("0 names replaced · 0 unknown · 2 preserved · 0 comments stripped", notifications.single().content)
    }

    /**
     * **Comments and javadoc are stripped, on the fast path, with nothing to set and nothing to
     * forget.**
     *
     * This is the largest single domain leak the product has. Renaming symbols does essentially
     * nothing to prose — an experiment measured 28/29/29 distinct domain words surviving across three
     * naming schemes against 36 in the original, enough to reconstruct the business domain — and
     * `// reconcile against the merchant ledger` sitting two lines above `field1` is incoherent on
     * its face.
     *
     * The line the comment was on goes with it. A blank indented line where a comment used to be
     * reads as a bug in this tool rather than as anonymization, and the output is *read*.
     */
    fun `test comments and javadoc are stripped and their lines go with them`() {
        assertTheHarnessResolves()
        myFixture.configureByText(
            "Ledger.java",
            """
            class Ledger {
                <selection>/** Reconciles a batch against the merchant ledger. */
                void settle(int amount) {
                    // this.customer.setOrder(order);
                    audit(amount); // the ledger is authoritative
                }</selection>

                void audit(int amount) {}
            }
            """.trimIndent(),
        )

        invokeCopyAnonymized()

        // The first line keeps the indentation the selection started inside, because the javadoc's
        // own line went and nothing else moved.
        assertEquals(
            "    void method1(int param2) {\n" +
                "        method3(param2);\n" +
                "    }",
            clipboard(),
        )
    }

    /**
     * **A comment can sit between any two tokens, and the output still parses without it.**
     *
     * That invariant is one of the two classes of bug this project has actually been bitten by, so
     * the awkward shapes are asserted rather than assumed: a block comment mid-expression, a javadoc
     * in front of a member, and a trailing comment after the last statement of a block.
     */
    fun `test the output still parses with every comment taken out of it`() {
        assertTheHarnessResolves()
        myFixture.configureByText(
            "Ledger.java",
            """
            class Ledger {
                <selection>/** The running total. */
                int total = /* pennies, never pounds */ 0;

                int audit(int amount) {
                    return total + amount; // the ledger is authoritative
                }</selection>
            }
            """.trimIndent(),
        )

        invokeCopyAnonymized()

        assertEquals(
            "    int field1 = 0;\n" +
                "\n" +
                "    int method2(int param3) {\n" +
                "        return field1 + param3;\n" +
                "    }",
            clipboard(),
        )
        assertParses("stripped", clipboard())
    }

    /**
     * **The strip is reported, because a stripped comment is invisible in the output.** The text
     * that comes back is clean, compiles and reads as ordinary code, and the AI answers accurately
     * about a snippet the defect has been lifted out of — that is the quietest failure in the design,
     * and the response to it is disclosure at the point of use.
     *
     * On the balloon rather than only in the preview: `Copy Anonymized` has no preview, so a
     * disclosure the dialog carried alone would never fire for the people who never open it.
     */
    fun `test the balloon reports the number of comments stripped`() {
        assertTheHarnessResolves()
        myFixture.configureByText(
            "Ledger.java",
            """
            class Ledger {
                <selection>void audit(int amount) {
                    // TODO: fix this
                    // this.customer.setOrder(order);
                    String.valueOf(amount);
                }</selection>
            }
            """.trimIndent(),
        )

        invokeCopyAnonymized()

        assertEquals("2 names replaced · 0 unknown · 2 preserved · 2 comments stripped", notifications.single().content)
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
        assertEquals("2 names replaced · 0 unknown · 2 preserved · 0 comments stripped", balloon.content)
        assertEquals(NotificationType.INFORMATION, balloon.type)
    }

    /**
     * **Red code is the common case, not an edge case** — the snippet a developer is debugging is
     * exactly the one most likely to contain names the IDE cannot resolve. Every one of them fails
     * closed into its own namespace rather than passing through verbatim, which is what this used to
     * do: `MerchantAccount`, `settlementStage` and `merchantReference` are precisely the domain
     * vocabulary the tool exists to remove.
     */
    fun `test names in red code fail closed into the Unknown namespace`() {
        assertTheHarnessResolves()
        myFixture.configureByText(
            "Ledger.java",
            """
            class Ledger {
                <selection>void audit(MerchantAccount account) {
                    settlementStage = account.merchantReference;
                }</selection>
            }
            """.trimIndent(),
        )

        invokeCopyAnonymized()

        // `Unknown`, never `Type` — a uniform type placeholder would invite the model to reason
        // confidently about a phantom symbol, where `Unknown2` says the IDE could not resolve it.
        assertEquals(
            "void method1(Unknown2 param3) {\n" +
                "        Unknown4 = param3.Unknown5;\n" +
                "    }",
            clipboard(),
        )

        // **Information level with the count at its highest, which is where the level is a
        // decision.** Under fail-closed every one of these three *was* anonymized, so the count is a
        // quality risk and never a privacy one — a warning here would train the user to read our
        // alarm as "this might have leaked", which is exactly backwards.
        val balloon = notifications.single()
        assertEquals("2 names replaced · 3 unknown · 0 preserved · 0 comments stripped", balloon.content)
        assertEquals(NotificationType.INFORMATION, balloon.type)
    }

    /**
     * A missing import, which is the shape red code most often takes — and the case that shows why
     * unresolved names skew heavily project-owned. `Money` is the user's own class either way; the
     * only difference between `Type` and `Unknown` here is whether the file imported it.
     */
    fun `test a missing import fails closed rather than passing the name through`() {
        assertTheHarnessResolves()
        myFixture.addFileToProject("com/acme/Money.java", "package com.acme; public class Money {}")
        myFixture.configureByText(
            "Ledger.java",
            "class Ledger { <selection>Money settle(Money owed) { return owed; }</selection> }",
        )

        invokeCopyAnonymized()

        // One name, one placeholder, both occurrences — unresolved names are keyed on what they are
        // written as, which is all there is to key them on.
        assertEquals("Unknown1 method2(Unknown1 param3) { return param3; }", clipboard())
        assertEquals("2 names replaced · 1 unknown · 0 preserved · 0 comments stripped", notifications.single().content)
    }

    /**
     * **A reference resolution reaches only by breaking a rule of the language has not resolved.**
     * `String.length()` names a real JDK method and a plain `resolve()` hands it straight back; the
     * compiler rejects the call, because an instance method cannot be reached through a class name.
     * Fail-closed asks whether the resolution was *valid*, so this reads as red code and is reported
     * as such rather than being vouched for as a JDK name.
     *
     * This is the exact case the per-item `Preserve` override exists to buy off — a typo'd JDK call
     * hidden behind a placeholder — which is why the answer here can afford to be the strict one.
     */
    fun `test a reference the language rejects is treated as unresolved`() {
        assertTheHarnessResolves()
        myFixture.configureByText(
            "Ledger.java",
            "class Ledger { <selection>static int broken() { return String.length(); }</selection> }",
        )

        invokeCopyAnonymized()

        assertEquals("static int method1() { return String.Unknown2(); }", clipboard())
    }

    /**
     * A label is reached through the statement's own reference rather than through the identifier's
     * parent, so the walk has to ask for it by name. Until this ticket that omission was invisible —
     * the label passed through verbatim, which was a leak. Failing closed made it visible instead, as
     * `break Unknown3` against a `local1:` declaration.
     *
     * Reporting it as unresolved would be the builder saying *the IDE could not resolve this* about a
     * reference it never asked the IDE about — and now that the `Unknown` count is a product surface,
     * that is a false reading of the one quality signal the tool has.
     */
    fun `test a label reference renders as the label's own placeholder`() {
        assertTheHarnessResolves()
        myFixture.configureByText(
            "Ledger.java",
            """
            class Ledger {
                void audit() {
                    <selection>settlement:
                    for (int row = 0; row < 2; row++) { break settlement; }</selection>
                }
            }
            """.trimIndent(),
        )

        invokeCopyAnonymized()

        assertEquals(
            "label1:\n" +
                "        for (int local2 = 0; local2 < 2; local2++) { break label1; }",
            clipboard(),
        )
    }

    /**
     * **Red code spreads, and fail-closed spreads with it.** An argument the IDE cannot resolve
     * leaves the surrounding call with no overload to pick, so `valueOf` — a name nobody needs
     * hidden — resolves to nothing and fails closed alongside it.
     *
     * This is the case the per-item `Preserve` override was created for and the reason it is a
     * stated residual gap rather than a problem assumed away: a JDK call hidden behind a placeholder
     * can cost a snippet its answerability, and one click in the preview buys it back for that
     * invocation only.
     */
    fun `test a JDK call is failed closed when its argument is red code`() {
        assertTheHarnessResolves()
        myFixture.configureByText(
            "Ledger.java",
            "class Ledger { void audit() { <selection>String.valueOf(undefinedVar);</selection> } }",
        )

        invokeCopyAnonymized()

        // `String` still resolves and is still preserved: only the call the unresolved argument
        // took down with it goes.
        assertEquals("String.Unknown1(Unknown2);", clipboard())
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

    /**
     * **The whole reason the mapping is persisted, end to end: the second paste agrees with the
     * first.**
     *
     * The fixture is the conversation this design exists for. `settle` is named in the first paste
     * and named again in the second, and in the second it is *not* the first name met — `refund`
     * is. Under fresh-per-invocation numbering `refund` would take `method1`, the number `settle`
     * already went out under, and the model would be reading two different methods under one name.
     *
     * So this asserts both halves at once: `settle` keeps `method1` wherever it appears, and
     * **stability outranks allocation ordering** — first-occurrence order now decides only which of
     * the newcomers takes the next number.
     */
    fun `test a symbol named in an earlier invocation keeps its placeholder in a later one`() {
        assertTheHarnessResolves()

        myFixture.configureByText(LEDGER_PATH, ledgerSelecting(SETTLE))
        invokeCopyAnonymized()
        assertEquals("void method1() {}", clipboard())

        myFixture.configureByText(LEDGER_PATH, ledgerSelecting(REFUND))
        invokeCopyAnonymized()

        assertEquals("void method2() { method1(); }", clipboard())
    }

    /**
     * **And it survives the IDE being shut down in between**, which is the case that rules out
     * keeping the mapping in memory: a restart would silently strip the ability to decode yesterday's
     * reply, and the user would find out by reading a decode that quietly says the wrong thing.
     *
     * The restart is the state written out and read back the way `@State` writes and reads it. That
     * is the honest form of the test available in-process, and it is the half that can actually
     * break: a field the serializer drops leaves every in-memory assertion green.
     */
    fun `test a placeholder survives the mapping being written out and read back`() {
        assertTheHarnessResolves()

        myFixture.configureByText(LEDGER_PATH, ledgerSelecting(SETTLE))
        invokeCopyAnonymized()
        assertEquals("void method1() {}", clipboard())

        PlaceholderLedger.getInstance().let { it.loadState(asWrittenAndReadBack(it.state)) }

        myFixture.configureByText(LEDGER_PATH, ledgerSelecting(REFUND))
        invokeCopyAnonymized()

        assertEquals("void method2() { method1(); }", clipboard())
    }

    /**
     * **The clipboard write is the single moment this invocation has happened at all**, so an
     * invocation that never got there leaves the mapping byte-identical — no entry, and no number
     * burnt.
     *
     * That matters beyond tidiness: numbers burnt by a failure are numbers no reply will ever
     * mention, and a mapping that drifted forwards on every failed attempt would make the
     * placeholders in a real paste depend on how many times the analysis had fallen over.
     *
     * **The stated limit.** What this reaches is an analysis that throws. The other half of the rule
     * — that the commit happens *after* the clipboard write rather than before it, so a clipboard
     * write that throws also commits nothing — is not reachable from here, because nothing can make
     * the platform's clipboard write fail on demand. It is stated in `CopyAnonymizedAction.deliver`
     * and held by the ordering of two lines, and that is worth saying out loud rather than leaving a
     * reader to assume this test covers it.
     */
    fun `test a failed invocation commits nothing to the mapping`() {
        assertTheHarnessResolves()

        myFixture.configureByText(LEDGER_PATH, ledgerSelecting(SETTLE))
        invokeCopyAnonymized()

        val committed = PlaceholderLedger.getInstance().snapshotOf(project)

        myFixture.configureByText(LEDGER_PATH, ledgerSelecting(REFUND))
        invokeCopyAnonymized(CopyAnonymizedAction { error("the analysis fell over") })

        val after = PlaceholderLedger.getInstance().snapshotOf(project)
        assertEquals("a failed invocation named a symbol", committed.placeholders, after.placeholders)
        assertEquals("a failed invocation burnt a number", committed.nextNumber, after.nextNumber)
    }

    /**
     * **What the sidecar is for, end to end.** The reply that comes back talks about the local and
     * the literal — *"the `local2` you build from `str3`"* — and neither has a qualified key, so
     * neither is in the persistent mapping. The invocation's whole table is what makes them
     * decodable, and it is recorded at the same moment the mapping is committed.
     */
    fun `test the locals and literals of an invocation are decodable from the sidecar`() {
        assertTheHarnessResolves()
        myFixture.configureByText(
            LEDGER_PATH,
            """
            class Ledger {
                <selection>void settle() {
                    String reason = "merchant settlement failed";
                }</selection>
            }
            """.trimIndent(),
        )

        invokeCopyAnonymized()

        assertEquals(
            "void method1() {\n" +
                "        String local2 = \"str3\";\n" +
                "    }",
            clipboard(),
        )
        val sidecar = PlaceholderSidecar.getInstance(project)
        assertEquals("settle", sidecar.originalOf("method1"))
        assertEquals("reason", sidecar.originalOf("local2"))
        assertEquals("merchant settlement failed", sidecar.originalOf("str3"))
    }

    /**
     * **The clipboard write is the single moment this invocation happened at all**, so an invocation
     * that never got there is not in the sidecar either — the same rule the mapping is held to, for
     * the same reason: nothing was sent, so there is nothing about it to decode.
     */
    fun `test a failed invocation records nothing in the sidecar`() {
        assertTheHarnessResolves()
        myFixture.configureByText(LEDGER_PATH, ledgerSelecting(SETTLE))

        invokeCopyAnonymized(CopyAnonymizedAction { error("the analysis fell over") })

        assertNull(
            "a failed invocation left a table behind",
            PlaceholderSidecar.getInstance(project).originalOf("method1"),
        )
    }

    /**
     * **Two invocations at once must not be handed the same number.**
     *
     * `queue()` serializes nothing, so two analyses over a large file run side by side, read the
     * same ledger and are each handed the same next number — one placeholder standing for two
     * different symbols, which is the one thing the design forbids. The clipboard write is on the
     * EDT, so that is where the ledger is read a second time and the analysis re-run if it moved.
     *
     * Made deterministic rather than raced: the plan build is precisely the window between this
     * invocation reading the ledger and committing to it, so a plan builder that commits during it
     * *is* another invocation getting there first — and it is a window a real slow analysis has.
     */
    fun `test an invocation whose ledger moved underneath it is re-run against the ledger that moved`() {
        assertTheHarnessResolves()
        myFixture.configureByText(LEDGER_PATH, ledgerSelecting(SETTLE))

        invokeCopyAnonymized(
            CopyAnonymizedAction { request ->
                PlaceholderLedger.getInstance().commit(
                    request.project,
                    LedgerDelta(mapOf(INTERLOPER to "method1"), nextNumber = 2),
                )
                JavaPlanBuilder.build(request)
            },
        )

        // `method1` is the number the stale analysis was handed. It went to the interloper, so the
        // re-run hands `settle` the next one — rather than both of them `method1`.
        assertEquals("void method2() {}", clipboard())
        assertEquals(
            mapOf(INTERLOPER to "method1", "method:class:Ledger#settle" to "method2"),
            PlaceholderLedger.getInstance().snapshotOf(project).placeholders,
        )
    }

    /**
     * Asserts that [snippet] is still Java the parser accepts, wrapped in the class body it came out
     * of. A rewrite that broke a literal's delimiters would leave an error element here.
     */
    private fun assertParses(name: String, snippet: String) {
        val error = parseErrorIn(name, snippet)
        assertNull("The anonymized snippet does not parse: ${error?.errorDescription}\n$snippet", error)
    }

    /** The other half of [assertParses]: a check that cannot fail is a check that passes. */
    private fun assertDoesNotParse(name: String, snippet: String) {
        assertNotNull("The parse check accepted malformed Java, so it is checking nothing:\n$snippet", parseErrorIn(name, snippet))
    }

    private fun parseErrorIn(name: String, snippet: String): PsiErrorElement? {
        val parsed = myFixture.addFileToProject("parsed/$name/Parsed.java", "class Parsed {\n$snippet\n}")
        return PsiTreeUtil.findChildOfType(parsed, PsiErrorElement::class.java)
    }

    /** What the undo stack currently offers for the fixture's editor. */
    private fun undoState(): String {
        val fileEditor = TextEditorProvider.getInstance().getTextEditor(myFixture.editor) as TextEditor
        val undo = UndoManager.getInstance(project)
        return "${undo.isUndoAvailable(fileEditor)}:${undo.getUndoActionNameAndDescription(fileEditor)}"
    }

}

private const val PREVIOUS_CLIPBOARD = "the raw snippet the user copied a minute ago"

private const val LEDGER_PATH = "Ledger.java"

/** A symbol another invocation named while this one was still analysing. */
private const val INTERLOPER = "method:class:com.other.Interloper#run"

private const val SETTLE = "void settle() {}"

private const val REFUND = "void refund() { settle(); }"

/**
 * The two-method fixture with [selected] selected, so that two invocations run over one file and one
 * symbol is named by both. Written as one source with a moving selection rather than as two files,
 * because the point is that `settle` is *the same symbol* in both pastes.
 */
private fun ledgerSelecting(selected: String): String {
    val body = listOf(SETTLE, REFUND).joinToString("\n    ") { if (it == selected) "<selection>$it</selection>" else it }
    return "class Ledger {\n    $body\n}"
}

internal fun clipboard(): String =
    CopyPasteManager.getInstance().contents?.getTransferData(DataFlavor.stringFlavor) as? String ?: ""

internal fun setClipboard(text: String) {
    CopyPasteManager.getInstance().setContents(StringSelection(text))
}
