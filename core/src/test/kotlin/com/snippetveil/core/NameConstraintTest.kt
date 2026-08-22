package com.snippetveil.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * **Which project-owned symbols keep their real names, because Java or the platform will not let
 * them be anything else.**
 *
 * The spine rule says *we own it, so it goes*; these are the cases where ownership is not the whole
 * story. The output is read rather than compiled, so what a wrong answer costs is not a broken
 * build — it is an output that **contradicts itself**, and a reviewer who reports a non-override as
 * a bug that does not exist upstream.
 *
 * **There is deliberately no user-editable preserve list of names anywhere in the product.** Every
 * rule here is derived from evidence the IDE already holds: a knob would leak by construction, and
 * the settings file holding it would become a plaintext domain glossary committed to the repo.
 */
class NameConstraintTest {

    /**
     * **A project method whose override chain reaches a non-project root keeps its name — at the
     * declaration *and* at the call site.**
     *
     * This is the spike's first regression, re-expressed. It surfaced as an anonymous `Runnable`
     * whose `run()` was renamed at the declaration and preserved at the call site, because the call
     * site resolves to `java.lang.Runnable.run` and the spine rule preserved it there: **the
     * override silently stopped overriding.** The class is large in real code — `@Override` of
     * framework types, interface implementations, `equals`/`hashCode`/`toString`, JUnit and Spring
     * lifecycle methods.
     *
     * **Demonstrated red** by making [isNameConstrained] return `false`: the declaration renames to
     * `method2` while the call site stays `run`, which is the original bug exactly.
     */
    @Test
    fun `a project method overriding a JDK member keeps its name at both sites`() {
        val text = """
            Runnable task = new Runnable() { public void run() { audit(); } };
            task.run();
        """.trimIndent()

        val jdkRun = symbol("run", SymbolRole.METHOD, SymbolOrigin.JDK, key = "method:class:java.lang.Runnable#run")
        val plan = planPlacing(
            text,
            at(0, symbol("Runnable", SymbolRole.TYPE, SymbolOrigin.JDK, key = "class:java.lang.Runnable")),
            at(0, symbol("task", SymbolRole.LOCAL, SymbolOrigin.IN_CONTENT, key = "local:task")),
            at(1, symbol("Runnable", SymbolRole.TYPE, SymbolOrigin.JDK, key = "class:java.lang.Runnable")),
            at(0, symbol(
                "run",
                SymbolRole.METHOD,
                SymbolOrigin.IN_CONTENT,
                key = "method:class:file@31#run",
                overrideRoots = listOf(OverrideRoot(jdkRun.key, SymbolOrigin.JDK)),
            )),
            at(0, symbol("audit", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT, key = "method:class:com.acme.Job#audit")),
            at(1, symbol("task", SymbolRole.LOCAL, SymbolOrigin.IN_CONTENT, key = "local:task")),
            at(1, jdkRun),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        result.assertKeptItsName(OVERRIDES, "run")
        assertEquals(
            "Runnable local1 = new Runnable() { public void run() { method2(); } };\nlocal1.run();",
            result.text,
        )
    }

    /** A library root constrains exactly as a JDK one does: neither name is ours to change. */
    @Test
    fun `a project method implementing a library interface keeps its name`() {
        val text = "class Job implements Callable { public String call() { return ref; } }"
        val plan = planPlacing(
            text,
            at(0, symbol("Job", SymbolRole.TYPE, SymbolOrigin.IN_CONTENT, key = "class:com.acme.Job")),
            at(0, symbol("Callable", SymbolRole.TYPE, SymbolOrigin.LIBRARY, key = "class:lib.Callable")),
            at(0, symbol(
                "call",
                SymbolRole.METHOD,
                SymbolOrigin.IN_CONTENT,
                key = "method:class:com.acme.Job#call",
                overrideRoots = listOf(OverrideRoot("method:class:lib.Callable#call", SymbolOrigin.LIBRARY)),
            )),
            at(0, symbol("ref", SymbolRole.FIELD, SymbolOrigin.IN_CONTENT, key = "field:class:com.acme.Job#ref")),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        result.assertKeptItsName(OVERRIDES, "call")
        assertEquals("class Type1 implements Callable { public String call() { return field2; } }", result.text)
    }

    /**
     * **A chain that stays inside the project is renamed**, which is the other half of the rule and
     * the reason it is stated as *reaches a non-project root* rather than *overrides anything*. A
     * rule that preserved every override would preserve most of a real codebase's method names.
     */
    @Test
    fun `a project-to-project override chain is renamed like anything else`() {
        val text = "class Job implements Task { public void audit() {} }"
        val plan = planPlacing(
            text,
            at(0, symbol("Job", SymbolRole.TYPE, SymbolOrigin.IN_CONTENT, key = "class:com.acme.Job")),
            at(0, symbol("Task", SymbolRole.TYPE, SymbolOrigin.IN_CONTENT, key = "class:com.acme.Task")),
            at(0, symbol(
                "audit",
                SymbolRole.METHOD,
                SymbolOrigin.IN_CONTENT,
                key = "method:class:com.acme.Job#audit",
                overrideRoots = listOf(OverrideRoot("method:class:com.acme.Task#audit", SymbolOrigin.IN_CONTENT)),
            )),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("class Type1 implements Type2 { public void method3() {} }", result.text)
    }

    /**
     * **The five names no override chain reaches**, because the platform looks them up by spelling
     * rather than through a type. `main` is the JVM entry point; the other four are
     * `java.io.Serializable`'s hooks, found reflectively on a class that declares them privately.
     *
     * All non-domain words, so preserving them leaks nothing — which is what makes a fixed list
     * acceptable here when a user-editable one is not.
     */
    @ParameterizedTest
    @ValueSource(strings = ["main", "readObject", "writeObject", "readResolve", "writeReplace"])
    fun `a platform-constrained method name is preserved`(name: String) {
        val text = "class Job { private void $name(Object stream) { this.ref = stream; } }"
        val plan = planPlacing(
            text,
            at(0, symbol("Job", SymbolRole.TYPE, SymbolOrigin.IN_CONTENT, key = "class:com.acme.Job")),
            at(0, symbol(name, SymbolRole.METHOD, SymbolOrigin.IN_CONTENT, key = "method:class:com.acme.Job#$name")),
            at(0, symbol("ref", SymbolRole.FIELD, SymbolOrigin.IN_CONTENT, key = "field:class:com.acme.Job#ref")),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        result.assertKeptItsName(PLATFORM_NAMES, name)
        assertEquals("class Type1 { private void $name(Object stream) { this.field2 = stream; } }", result.text)
    }

    /**
     * The list is about *methods*, and the rule says so rather than matching the spelling wherever
     * it turns up. A field called `main` is an ordinary project-owned field with an ordinary
     * placeholder, and the serialization machinery has never heard of it.
     */
    @Test
    fun `a field sharing a platform-constrained name is renamed`() {
        val plan = planOf(
            "int main = 0;",
            symbol("main", SymbolRole.FIELD, SymbolOrigin.IN_CONTENT, key = "field:class:com.acme.Job#main"),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("int field1 = 0;", result.text)
    }

    /**
     * **A constraint may not reach a name that did not resolve.** The rules here are reasons to keep
     * a name the project *owns*; an unresolved name is one nobody can vouch for, and it stays failed
     * closed. Otherwise the one fail-open in the product would have grown a second entrance — a name
     * released on the strength of its spelling, which is the free-text preserve list this design
     * rejected.
     */
    @Test
    fun `an unresolved name spelled like a constrained one still fails closed`() {
        val plan = planOf(
            "main(args);",
            symbol("main", SymbolRole.TYPE, SymbolOrigin.UNRESOLVED, key = "unresolved:main"),
            symbol("args", SymbolRole.TYPE, SymbolOrigin.UNRESOLVED, key = "unresolved:args"),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("Unknown1(Unknown2);", result.text)
    }

    /**
     * **The balloon may not call a preserved name replaced.** The three counts partition the
     * snippet's distinct names, and they are counted by outcome for exactly this case: a project
     * method that keeps its real name is project-owned *evidence* and a preserved *name*, and
     * `replaced` is a claim about what is on the clipboard.
     */
    @Test
    fun `a name-constrained method counts as preserved rather than replaced`() {
        val text = "class Job implements Runnable { public void run() { audit(); } }"
        val plan = planPlacing(
            text,
            at(0, symbol("Job", SymbolRole.TYPE, SymbolOrigin.IN_CONTENT, key = "class:com.acme.Job")),
            at(0, symbol("Runnable", SymbolRole.TYPE, SymbolOrigin.JDK, key = "class:java.lang.Runnable")),
            at(0, symbol(
                "run",
                SymbolRole.METHOD,
                SymbolOrigin.IN_CONTENT,
                key = "method:class:com.acme.Job#run",
                overrideRoots = listOf(OverrideRoot("method:class:java.lang.Runnable#run", SymbolOrigin.JDK)),
            )),
            at(0, symbol("audit", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT, key = "method:class:com.acme.Job#audit")),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        // Job and audit are replaced; Runnable and the constrained run() survive verbatim.
        assertEquals(2, result.counts.replaced)
        assertEquals(2, result.counts.preserved)
        assertEquals(0, result.counts.unknown)
    }

    /**
     * A preserved name still holds its ground in the placeholder namespace. Nothing can be named
     * `run` here anyway, but the reason is worth pinning: preserved names are read out of the
     * surviving *text*, so a rule that stopped renaming a symbol without leaving its name in the
     * reserved set would be one collision away from an ambiguous mapping.
     */
    @Test
    fun `a preserved name is still reserved against collision`() {
        val text = "class Job { void method1() {} void audit() {} }"
        val plan = planPlacing(
            text,
            at(0, symbol("Job", SymbolRole.TYPE, SymbolOrigin.IN_CONTENT, key = "class:com.acme.Job")),
            at(0, symbol(
                "method1",
                SymbolRole.METHOD,
                SymbolOrigin.IN_CONTENT,
                key = "method:class:com.acme.Job#method1",
                overrideRoots = listOf(OverrideRoot("method:class:lib.Base#method1", SymbolOrigin.LIBRARY)),
            )),
            at(0, symbol("audit", SymbolRole.METHOD, SymbolOrigin.IN_CONTENT, key = "method:class:com.acme.Job#audit")),
        )

        val result = anonymize(plan, AnonymizationSettings.DEFAULTS, LedgerSnapshot.EMPTY)

        assertEquals("class Type1 { void method1() {} void method2() {} }", result.text)
    }
}

private const val OVERRIDES = "Name constraint 1 (an override chain reaching a non-project root)"
private const val PLATFORM_NAMES = "Name constraint 2 (a platform-constrained name)"
