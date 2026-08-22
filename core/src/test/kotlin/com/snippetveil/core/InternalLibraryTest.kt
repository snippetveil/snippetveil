package com.snippetveil.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **A company's own internal libraries are the project's code, however they arrive.**
 *
 * No IntelliJ API separates a third-party library from a company-internal artifact — both are a
 * `LibraryOrderEntry`, and only the library's name, its root URLs and its Maven coordinates exist to
 * look at. So this is a **policy** rather than a lookup, and the policy is a package prefix: the
 * root package of the file under analysis, plus a project-level list that adds the prefixes the
 * heuristic misses and removes the ones it wrongly claims.
 *
 * Every test here is the whole rule end to end, over a plan literal with **no IDE anywhere near
 * it** — which is the point of the seam rather than a convenience. The plan says `LIBRARY` and says
 * which package the symbol lives in; both are facts a builder observed. The prefix match is applied
 * here and nowhere else, so the product's one persistent knob is testable in milliseconds.
 */
class InternalLibraryTest {

    /**
     * The heuristic, and it is on by default: a library symbol under the analysed file's own root
     * package is the company's own code arriving as a jar, and it is anonymized like any other
     * project-owned name.
     *
     * The plan literal is the other half of this assertion. It reports `LIBRARY` for both symbols
     * even though their package obviously matches the file's — because the builder must not
     * pre-judge — and the engine is what turns that evidence into a rename.
     */
    @Test
    fun `a library symbol under the file's root package is anonymized`() {
        val plan = planOf(
            "Money total = invoice.amountDue();",
            symbol("Money", SymbolRole.TYPE, SymbolOrigin.LIBRARY, packageName = "com.acme.commons"),
            symbol("total", SymbolRole.LOCAL, SymbolOrigin.IN_CONTENT),
            symbol("invoice", SymbolRole.LOCAL, SymbolOrigin.IN_CONTENT),
            symbol("amountDue", SymbolRole.METHOD, SymbolOrigin.LIBRARY, packageName = "com.acme.commons"),
            rootPackage = "com.acme",
        )

        assertTrue(
            plan.occurrences.filterIsInstance<SymbolOccurrence>()
                .filter { it.text == "Money" || it.text == "amountDue" }
                .all { it.symbol.origin == SymbolOrigin.LIBRARY },
            "the plan must report LIBRARY unmodified; the prefix match belongs to the engine",
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("Type1 local2 = local3.method4();", result.text)
    }

    /**
     * A genuine third-party symbol is preserved, which is the half of the rule that keeps the
     * snippet answerable: concealing the tech stack is a declared non-goal, and `Assert.notNull` is
     * the sentence a reader needs in order to say what the code does.
     */
    @Test
    fun `a third-party symbol outside the prefix is preserved`() {
        val plan = planOf(
            "Money total = Assert.notNull(value);",
            symbol("Money", SymbolRole.TYPE, SymbolOrigin.LIBRARY, packageName = "com.acme.commons"),
            symbol("total", SymbolRole.LOCAL, SymbolOrigin.IN_CONTENT),
            symbol("Assert", SymbolRole.TYPE, SymbolOrigin.LIBRARY, packageName = "org.springframework.util"),
            symbol("notNull", SymbolRole.METHOD, SymbolOrigin.LIBRARY, packageName = "org.springframework.util"),
            symbol("value", SymbolRole.LOCAL, SymbolOrigin.IN_CONTENT),
            rootPackage = "com.acme",
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("Type1 local2 = Assert.notNull(local3);", result.text)
    }

    /**
     * **The added half of the list**, which exists because the heuristic misses a whole shape: an
     * employer whose shared artifacts are published under a different group id from the repository
     * they are consumed in. Nothing about the file under analysis can see that, so a human says so
     * once, per project.
     */
    @Test
    fun `an added prefix pulls a matching library symbol into the anonymized set`() {
        val plan = planOf(
            "Money total = value;",
            symbol("Money", SymbolRole.TYPE, SymbolOrigin.LIBRARY, packageName = "io.acmecorp.commons"),
            symbol("total", SymbolRole.LOCAL, SymbolOrigin.IN_CONTENT),
            symbol("value", SymbolRole.LOCAL, SymbolOrigin.IN_CONTENT),
            rootPackage = "com.acme",
        )

        val settings = AnonymizationSettings(
            internalLibraries = InternalLibraries(internalPrefixes = setOf("io.acmecorp")),
        )

        assertEquals("Money local1 = local2;", anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY).text)
        assertEquals("Type1 local2 = local3;", anonymize(plan, settings, LedgerSnapshot.EMPTY).text)
    }

    /**
     * **The removed half**, for a false hit: a genuinely public library that happens to sit under
     * the company's own group id — an open-source project the employer publishes, which is exactly
     * the artifact whose name a reader needs to see.
     *
     * A removal claws back only what the rule above it claimed. It cannot make the tool anonymize
     * less than it did before this rule existed, which is why the list survives *persistent settings
     * may only ever increase anonymization* while being persisted.
     */
    @Test
    fun `a removed prefix pushes a matching symbol back out`() {
        val plan = planOf(
            "Money total = value;",
            symbol("Money", SymbolRole.TYPE, SymbolOrigin.LIBRARY, packageName = "com.acme.commons"),
            symbol("total", SymbolRole.LOCAL, SymbolOrigin.IN_CONTENT),
            symbol("value", SymbolRole.LOCAL, SymbolOrigin.IN_CONTENT),
            rootPackage = "com.acme",
        )

        val settings = AnonymizationSettings(
            internalLibraries = InternalLibraries(thirdPartyPrefixes = setOf("com.acme")),
        )

        assertEquals("Type1 local2 = local3;", anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY).text)
        assertEquals("Money local1 = local2;", anonymize(plan, settings, LedgerSnapshot.EMPTY).text)
    }

    /**
     * **The more specific prefix wins**, which is what makes the two halves of the list a
     * classification rather than a fight. `com.acme` is ours and `com.acme.oss` is the thing we
     * open-sourced; saying both is the only way to say that, and the longer one is the one that
     * knows more.
     *
     * On an exact tie the removal wins, because a human who typed the prefix out is answering the
     * heuristic that guessed it.
     */
    @Test
    fun `the longest matching prefix decides, and a tie goes to the removal`() {
        val plan = planOf(
            "Money total = Widget.of(value);",
            symbol("Money", SymbolRole.TYPE, SymbolOrigin.LIBRARY, packageName = "com.acme.billing"),
            symbol("total", SymbolRole.LOCAL, SymbolOrigin.IN_CONTENT),
            symbol("Widget", SymbolRole.TYPE, SymbolOrigin.LIBRARY, packageName = "com.acme.oss.widgets"),
            symbol("value", SymbolRole.LOCAL, SymbolOrigin.IN_CONTENT),
            rootPackage = "com.acme",
        )

        val moreSpecificRemoval = AnonymizationSettings(
            internalLibraries = InternalLibraries(thirdPartyPrefixes = setOf("com.acme.oss")),
        )
        val moreSpecificAddition = AnonymizationSettings(
            internalLibraries = InternalLibraries(
                internalPrefixes = setOf("com.acme.billing"),
                thirdPartyPrefixes = setOf("com.acme"),
            ),
        )

        assertEquals(
            "Type1 local2 = Widget.of(local3);",
            anonymize(plan, moreSpecificRemoval, LedgerSnapshot.EMPTY).text,
        )
        assertEquals(
            "Type1 local2 = Widget.of(local3);",
            anonymize(plan, moreSpecificAddition, LedgerSnapshot.EMPTY).text,
        )
    }

    /**
     * A prefix matches whole package segments and never a spelling: `com.acme` says nothing about
     * `com.acmecorp`, which is a different company as surely as `org.junit` is.
     */
    @Test
    fun `a prefix matches package segments rather than characters`() {
        val plan = planOf(
            "Widget value;",
            symbol("Widget", SymbolRole.TYPE, SymbolOrigin.LIBRARY, packageName = "com.acmecorp.widgets"),
            symbol("value", SymbolRole.LOCAL, SymbolOrigin.IN_CONTENT),
            rootPackage = "com.acme",
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("Widget local1;", result.text)
    }

    /** Auto-detect is on by default, which is the setting's whole shape stated as an assertion. */
    @Test
    fun `auto-detect defaults to on`() {
        assertTrue(InternalLibraries().autoDetectRootPackage)
        assertTrue(AnonymizationSettings.DEFAULTS.internalLibraries.autoDetectRootPackage)
    }

    /**
     * Turned off, the heuristic stops guessing and the list is the whole rule. The added prefixes
     * still apply — switching the guess off is not switching the feature off.
     */
    @Test
    fun `auto-detect off leaves the root package to the list`() {
        val plan = planOf(
            "Money total;",
            symbol("Money", SymbolRole.TYPE, SymbolOrigin.LIBRARY, packageName = "com.acme.commons"),
            symbol("total", SymbolRole.LOCAL, SymbolOrigin.IN_CONTENT),
            rootPackage = "com.acme",
        )

        val off = AnonymizationSettings(
            internalLibraries = InternalLibraries(autoDetectRootPackage = false),
        )
        val offButListed = AnonymizationSettings(
            internalLibraries = InternalLibraries(
                autoDetectRootPackage = false,
                internalPrefixes = setOf("com.acme.commons"),
            ),
        )

        assertEquals("Money local1;", anonymize(plan, off, LedgerSnapshot.EMPTY).text)
        assertEquals("Type1 local2;", anonymize(plan, offButListed, LedgerSnapshot.EMPTY).text)
    }

    /**
     * A file in the default package reports no root package, so there is nothing for the heuristic
     * to derive and it claims nothing. Fail-open here rather than closed, and deliberately: the
     * alternative is a rule that reads *no root package* as *every prefix matches*.
     */
    @Test
    fun `a file with no root package claims nothing`() {
        val plan = planOf(
            "Money total;",
            symbol("Money", SymbolRole.TYPE, SymbolOrigin.LIBRARY, packageName = "com.acme.commons"),
            symbol("total", SymbolRole.LOCAL, SymbolOrigin.IN_CONTENT),
        )

        assertEquals("Money local1;", anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY).text)
    }

    /**
     * **The rule reaches library symbols and nothing else.** The JDK is preserved by the spine rule
     * and stays preserved however the prefixes are written, because a company does not ship
     * `java.util` however its artifacts are named — and a knob that could rename `List` would make
     * every snippet unreadable while concealing nothing.
     */
    @Test
    fun `the prefix rule never reaches the JDK`() {
        val plan = planOf(
            "List values;",
            symbol("List", SymbolRole.TYPE, SymbolOrigin.JDK, packageName = "java.util"),
            symbol("values", SymbolRole.LOCAL, SymbolOrigin.IN_CONTENT),
            rootPackage = "java.util",
        )

        val settings = AnonymizationSettings(
            internalLibraries = InternalLibraries(internalPrefixes = setOf("java")),
        )

        assertEquals("List local1;", anonymize(plan, settings, LedgerSnapshot.EMPTY).text)
    }

    /**
     * **A member is where the domain vocabulary actually is**, so the rule has to reach one. A
     * method or a field carries no qualified name of its own — `getMerchantId` is not a name Java
     * qualifies — which is why the evidence the match reads is the package the symbol *belongs to*
     * rather than a qualified name that exists only for types.
     *
     * Half a rule here would be worse than none: `Payment` renamed to `Type1` while
     * `type1.merchantRef()` keeps its name is not anonymized output, it is output that leaks the
     * domain and looks like it did not.
     */
    @Test
    fun `a member of an internal library type is anonymized`() {
        val plan = planOf(
            "total = ledger.merchantRef;",
            symbol("total", SymbolRole.LOCAL, SymbolOrigin.IN_CONTENT),
            symbol("ledger", SymbolRole.LOCAL, SymbolOrigin.IN_CONTENT),
            symbol("merchantRef", SymbolRole.FIELD, SymbolOrigin.LIBRARY, packageName = "com.acme.commons"),
            rootPackage = "com.acme",
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("local1 = local2.field3;", result.text)
    }

    /**
     * **A name constraint follows ownership, so it follows this rule too.** An internal-library
     * method that implements a third-party interface may not be renamed for exactly the reason a
     * project one may not: the output is *read*, and a rename that stops an override overriding is a
     * contradiction a reviewer reports as a bug that does not exist upstream.
     *
     * This is the case that would otherwise be missed silently. Before this rule the method was
     * preserved by the spine rule and no constraint had to fire; now that it is anonymized by
     * default, the constraint is the only thing keeping the output coherent.
     */
    @Test
    fun `an internal library method overriding a third-party one keeps its name`() {
        val plan = planOf(
            "public void run() { ledger.post(); }",
            symbol(
                "run",
                SymbolRole.METHOD,
                SymbolOrigin.LIBRARY,
                packageName = "com.acme.commons",
                overrideRoots = listOf(
                    OverrideRoot("method:java.lang.Runnable#run", SymbolOrigin.JDK, packageName = "java.lang"),
                ),
            ),
            symbol("ledger", SymbolRole.LOCAL, SymbolOrigin.IN_CONTENT),
            symbol("post", SymbolRole.METHOD, SymbolOrigin.LIBRARY, packageName = "com.acme.commons"),
            rootPackage = "com.acme",
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("public void run() { local1.method2(); }", result.text)
    }

    /**
     * A chain that stays inside the company renames, and renames as one name. `LIBRARY` origin on
     * the root is not on its own a reason to keep a name — whose library it is, is the question, and
     * this rule is the answer to it.
     */
    @Test
    fun `an override chain inside the internal library renames as one name`() {
        val plan = planOf(
            "public void post() { delegate.post(); }",
            symbol(
                "post",
                SymbolRole.METHOD,
                SymbolOrigin.LIBRARY,
                key = "method:com.acme.commons.Journal#post",
                packageName = "com.acme.commons",
                overrideRoots = listOf(
                    OverrideRoot("method:com.acme.commons.Postable#post", SymbolOrigin.LIBRARY, packageName = "com.acme.commons"),
                ),
            ),
            symbol("delegate", SymbolRole.LOCAL, SymbolOrigin.IN_CONTENT),
            rootPackage = "com.acme",
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("public void method1() { local2.method1(); }", result.text)
    }

    /**
     * The package segments of an internal library rename like any other project-owned package, one
     * segment at a time — so `com.acme.commons` comes out as `com.pkg1.pkg2` and same-package versus
     * different-package survives the rename, exactly as it does for the project's own code.
     */
    @Test
    fun `the package segments of an internal library rename per segment`() {
        val plan = planOf(
            "import com.acme.billing.Money;",
            pkg("com", SymbolOrigin.LIBRARY),
            pkg("com.acme", SymbolOrigin.LIBRARY),
            pkg("com.acme.billing", SymbolOrigin.LIBRARY),
            symbol("Money", SymbolRole.TYPE, SymbolOrigin.LIBRARY, packageName = "com.acme.billing"),
            rootPackage = "com.acme",
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("import com.pkg1.pkg2.Type3;", result.text)
    }

    /**
     * **A library symbol in the default package lies under no prefix**, so it is preserved — a
     * correct negative rather than a fail-open on missing evidence. `null` here is not *the builder
     * could not tell*; it is the fact that there is no package, and no prefix can claim or disown
     * one that does not exist.
     */
    @Test
    fun `a library symbol in the default package is preserved`() {
        val plan = planOf(
            "Money total;",
            symbol("Money", SymbolRole.TYPE, SymbolOrigin.LIBRARY),
            symbol("total", SymbolRole.LOCAL, SymbolOrigin.IN_CONTENT),
            rootPackage = "com.acme",
        )

        assertEquals("Money local1;", anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY).text)
    }

    /**
     * A blank prefix is ignored rather than matching everything. It is what an empty row in the
     * settings list produces, and *"the list had an empty line in it"* must not be a way to
     * anonymize the JDK — or, through the removal half, a way to switch the whole rule off.
     */
    @Test
    fun `a blank prefix matches nothing`() {
        val plan = planOf(
            "Money total = Assert.notNull(value);",
            symbol("Money", SymbolRole.TYPE, SymbolOrigin.LIBRARY, packageName = "com.acme.commons"),
            symbol("total", SymbolRole.LOCAL, SymbolOrigin.IN_CONTENT),
            symbol("Assert", SymbolRole.TYPE, SymbolOrigin.LIBRARY, packageName = "org.springframework.util"),
            symbol("notNull", SymbolRole.METHOD, SymbolOrigin.LIBRARY, packageName = "org.springframework.util"),
            symbol("value", SymbolRole.LOCAL, SymbolOrigin.IN_CONTENT),
            rootPackage = "com.acme",
        )

        val settings = AnonymizationSettings(
            internalLibraries = InternalLibraries(
                internalPrefixes = setOf("", "   "),
                thirdPartyPrefixes = setOf(""),
            ),
        )

        assertEquals("Type1 local2 = Assert.notNull(local3);", anonymize(plan, settings, LedgerSnapshot.EMPTY).text)
    }

    /**
     * **A project method overriding an internal-library one renames**, and the two render as one
     * name — which is the same rule the project's own chains follow, arriving here because the jar
     * is now the project's too.
     *
     * Before this rule the project method was preserved, because its root was not `IN_CONTENT` and
     * that was the whole test. It is a behaviour change rather than a side effect, and it is the
     * coherent one: the root renames, so a call site keeping the old name is the contradiction the
     * name constraint exists to prevent.
     */
    @Test
    fun `a project method overriding an internal library method renames as one name`() {
        val plan = planOf(
            "public void post() { delegate.post(); }",
            symbol(
                "post",
                SymbolRole.METHOD,
                SymbolOrigin.IN_CONTENT,
                key = "method:com.acme.web.Journal#post",
                packageName = "com.acme.web",
                overrideRoots = listOf(
                    OverrideRoot("method:com.acme.commons.Postable#post", SymbolOrigin.LIBRARY, packageName = "com.acme.commons"),
                ),
            ),
            symbol("delegate", SymbolRole.LOCAL, SymbolOrigin.IN_CONTENT),
            rootPackage = "com.acme",
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("public void method1() { local2.method1(); }", result.text)
    }

    /**
     * And the same method against a *genuine* third-party root keeps its name, which is what says
     * the test above is about ownership rather than about the constraint having been dropped.
     */
    @Test
    fun `a project method overriding a third-party one still keeps its name`() {
        val plan = planOf(
            "public void post() { delegate.post(); }",
            symbol(
                "post",
                SymbolRole.METHOD,
                SymbolOrigin.IN_CONTENT,
                key = "method:com.acme.web.Journal#post",
                packageName = "com.acme.web",
                overrideRoots = listOf(
                    OverrideRoot("method:org.springframework.Postable#post", SymbolOrigin.LIBRARY, packageName = "org.springframework"),
                ),
            ),
            symbol("delegate", SymbolRole.LOCAL, SymbolOrigin.IN_CONTENT),
            rootPackage = "com.acme",
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("public void post() { local1.post(); }", result.text)
    }

    /**
     * The counts are a claim about the clipboard, so an internal-library name that became a
     * placeholder counts as replaced and a genuine third-party one counts as preserved — the same
     * partition every other rule is counted by.
     */
    @Test
    fun `an internal library name counts as replaced and a third-party one as preserved`() {
        val plan = planOf(
            "Money total = Assert.notNull(value);",
            symbol("Money", SymbolRole.TYPE, SymbolOrigin.LIBRARY, packageName = "com.acme.commons"),
            symbol("total", SymbolRole.LOCAL, SymbolOrigin.IN_CONTENT),
            symbol("Assert", SymbolRole.TYPE, SymbolOrigin.LIBRARY, packageName = "org.springframework.util"),
            symbol("notNull", SymbolRole.METHOD, SymbolOrigin.LIBRARY, packageName = "org.springframework.util"),
            symbol("value", SymbolRole.LOCAL, SymbolOrigin.IN_CONTENT),
            rootPackage = "com.acme",
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals(3, result.counts.replaced)
        assertEquals(2, result.counts.preserved)
        assertEquals(mapOf("Type1" to "Money", "local2" to "total", "local3" to "value"), result.mapping)
    }
}
