package com.snippetveil.trust

/**
 * The red path of the process-execution rules, baked in rather than observed once.
 *
 * `Runtime.exec` is banned by a *call*-based rule while `java.lang.Runtime` itself stays legal, so
 * that rule has a way to be wrong that a type-reference rule does not: it can ban the whole class by
 * accident, and nothing in this repository's own sources would notice, because nothing here calls
 * `Runtime` at all. These two classes give it something to be right about. [StartsASubprocess] must
 * be flagged and [UsesRuntimeLegitimately] must not, and `the process rules tell a subprocess from
 * an ordinary Runtime call` asserts both.
 *
 * Test scope, so [SHIPPED_CLASSES] excludes them and the shipped-code rules never see them; the
 * methods are never called, so no process is ever started. They are imported by name, one class at
 * a time, which is the only way a rule gets pointed at code that is meant to violate it.
 */
internal class StartsASubprocess {

    fun viaRuntimeExec(): Process = Runtime.getRuntime().exec("true")

    fun viaProcessBuilder(): Process = ProcessBuilder("true").start()
}

/**
 * `Runtime.getRuntime().availableProcessors()` is an ordinary thing to want. A rule that banned it
 * would be the kind of noise that teaches people to suppress a check, so it is asserted legal.
 */
internal class UsesRuntimeLegitimately {

    fun availableProcessors(): Int = Runtime.getRuntime().availableProcessors()
}
