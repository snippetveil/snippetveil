package com.snippetveil.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * A plan over [text] whose occurrences are located by searching [text] for each symbol's name, so a
 * test states the code and the evidence and never an offset. Each symbol is keyed by its own name,
 * which is what a test wants by default: same name, same symbol.
 */
internal fun planOf(text: String, vararg symbols: SymbolEvidence): SnippetPlan {
    val occurrences = mutableListOf<Occurrence>()
    for (symbol in symbols) {
        var from = 0
        while (true) {
            val at = text.indexOf(symbol.declaredName, from).takeIf { it >= 0 } ?: break
            occurrences += SymbolOccurrence(at, at + symbol.declaredName.length, symbol.declaredName, symbol)
            from = at + symbol.declaredName.length
        }
    }
    return SnippetPlan(text, occurrences.sortedBy { it.start })
}

/**
 * A plan over [text] in which two *different* symbols are spelled alike — an override chain, an
 * overload pair, a record's three faces — so each occurrence has to be placed rather than searched
 * for. Placed by counting occurrences of the name the way a reader counts them, so these tests
 * still state no offset.
 */
internal fun planPlacing(text: String, vararg placements: Placement, rootPackage: String? = null): SnippetPlan {
    val used = mutableMapOf<String, MutableList<Int>>()
    val occurrences = placements.map { placement ->
        val name = placement.symbol.declaredName
        val offsets = used.getOrPut(name) {
            generateSequence(text.indexOf(name)) { text.indexOf(name, it + name.length) }
                .takeWhile { it >= 0 }
                .toMutableList()
        }
        val at = offsets.getOrNull(placement.ordinal)
            ?: error("`$name` occurs ${offsets.size} times in the snippet; there is no #${placement.ordinal}")
        SymbolOccurrence(at, at + name.length, name, placement.symbol)
    }
    return SnippetPlan(text, occurrences.sortedBy { it.start }, rootPackage)
}

/** One symbol, and which occurrence of its own name in the snippet this is — counted from zero. */
internal class Placement(val ordinal: Int, val symbol: SymbolEvidence)

/** Places [symbol] at the [ordinal]-th occurrence of its name. See [planPlacing]. */
internal fun at(ordinal: Int, symbol: SymbolEvidence) = Placement(ordinal, symbol)

internal fun symbol(
    name: String,
    role: SymbolRole,
    origin: SymbolOrigin,
    key: String = name,
    qualifiedName: String? = null,
    signature: String? = null,
    overrideRoots: List<OverrideRoot> = emptyList(),
    accessor: AccessorEvidence? = null,
) = SymbolEvidence(
    key = key,
    role = role,
    origin = origin,
    declaredName = name,
    qualifiedName = qualifiedName,
    signature = signature,
    overrideRoots = overrideRoots,
    accessor = accessor,
)

/**
 * One segment of a package name, named by the package it *ends* — `pkg("com.acme")` is the segment
 * written `acme`, in a snippet that says `com.acme`.
 *
 * Naming the whole package rather than the segment is what a test wants, because every rule about
 * packages is a rule about the qualified name: the top-level segment is the one with no dot before
 * it, and two types share a package placeholder by sharing this key.
 */
internal fun pkg(qualifiedName: String, origin: SymbolOrigin) = symbol(
    name = qualifiedName.substringAfterLast('.'),
    role = SymbolRole.PACKAGE,
    origin = origin,
    key = "package:" + qualifiedName,
    qualifiedName = qualifiedName,
)

internal fun symbolAt(
    start: Int,
    name: String,
    role: SymbolRole,
    origin: SymbolOrigin,
    key: String = name,
    signature: String? = null,
) = SymbolOccurrence(
    start,
    start + name.length,
    name,
    SymbolEvidence(key = key, role = role, origin = origin, declaredName = name, signature = signature),
)

/**
 * Every distinct placeholder the output uses for a symbol whose real name is [name].
 *
 * This is the primitive the rule assertions below are built on, and it is why they are structural
 * rather than golden. Each of these rules is a statement about entries *agreeing* — so the question
 * a test asks is "how many placeholders does this one name render as", which is a question a golden
 * file answers only by accident.
 */
internal fun AnonymizationResult.placeholdersFor(name: String): Set<String> =
    mapping.filterValues { it == name }.keys

/**
 * Asserts that every symbol named [name] rendered as one and the same placeholder.
 *
 * @param rule names the rule in the failure message, which is the point: when one of the five dies,
 *   a golden's diff shows a name change and leaves the reader to reason backwards to which.
 */
internal fun AnonymizationResult.assertShared(rule: String, name: String) {
    val placeholders = placeholdersFor(name)
    assertEquals(
        1,
        placeholders.size,
        "$rule: `$name` must render as exactly one placeholder, and rendered as $placeholders\n$text",
    )
}

/** Asserts that [name] rendered as [expected] everywhere it occurs. See [assertShared]. */
internal fun AnonymizationResult.assertRendersAs(rule: String, name: String, expected: String) {
    assertEquals(
        setOf(expected),
        placeholdersFor(name),
        "$rule: `$name` must render as `$expected`\n$text",
    )
}

/** Asserts that [name] survived into the output under its own name. See [assertShared]. */
internal fun AnonymizationResult.assertKeptItsName(rule: String, name: String) {
    assertEquals(
        emptySet<String>(),
        placeholdersFor(name),
        "$rule: `$name` may not be renamed, and was renamed\n$text",
    )
    assertTrue(
        Regex("""\b${Regex.escape(name)}\b""").containsMatchIn(text),
        "$rule: `$name` may not be renamed, and is not in the output at all\n$text",
    )
}

/** Asserts that the symbols named [name] were *not* collapsed onto one placeholder. */
internal fun AnonymizationResult.assertDistinct(rule: String, name: String, count: Int) {
    val placeholders = placeholdersFor(name)
    assertEquals(
        count,
        placeholders.size,
        "$rule: the $count symbols named `$name` must render as $count placeholders, and rendered as $placeholders\n$text",
    )
}
