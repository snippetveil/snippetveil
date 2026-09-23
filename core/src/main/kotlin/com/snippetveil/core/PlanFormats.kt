package com.snippetveil.core

/**
 * **One output format of one engine** — a predicate that says whether a text is written in it, and a
 * reader that says what is in it.
 *
 * ### Three structured vocabularies, not one
 *
 * JSON, YAML and XML carry **the same tree**, and they are still **three separate vocabularies with
 * three separate recognition predicates**. A YAML document is not a JSON document: the tree they
 * agree on is what [PlanDocument] absorbs, and agreement about the tree is not a reason to blur the
 * question of which spelling arrived.
 *
 * ### The predicates must not both accept
 *
 * > **If two vocabularies match, the input refuses rather than picking one.**
 *
 * Picking would mean this product deciding, on a text it does not understand, which of two readings
 * of it to trust — and then reading the whole plan under that choice. There is no fail-closed version
 * of that choice, so the choice is not made. See [readingOf].
 *
 * The predicates here are exclusive by construction, each anchored on a character the others cannot
 * begin with, and a test asserts it pairwise over a corpus. The rule in [readingOf] is what holds
 * when a later format makes that no longer true by inspection.
 *
 * @param name what the format is called, for a test to name it in a failure
 * @param recognises whether a text is written in this format. Anchored, and cheap: it decides which
 *   reader runs and never what is in the text.
 * @param read what the format's reader makes of the text — the occurrences, or a throw carrying the
 *   verdict. See [PlanRefusal].
 */
internal class PlanFormat(
    val name: String,
    val recognises: (String) -> Boolean,
    val read: (String) -> List<PlanOccurrence>,
)

/** **PostgreSQL's four output formats**, in the order a reader meets them and in no other. */
internal val POSTGRES_FORMATS: List<PlanFormat> = listOf(
    PlanFormat("text", ::opensATextPlan, ::textOccurrencesIn),
    PlanFormat("json", { it.trimStart().startsWith("[") }) {
        structuredOccurrencesIn(jsonQueriesIn(it), AS_WRITTEN, JSON_QUOTE)
    },
    PlanFormat("yaml", ::opensAYamlPlan) {
        structuredOccurrencesIn(yamlQueriesIn(it), AS_WRITTEN, JSON_QUOTE)
    },
    PlanFormat("xml", { it.trimStart().startsWith("<") }) {
        structuredOccurrencesIn(xmlQueriesIn(it), { label -> POSTGRES_XML_LABELS[label] ?: label }, XML_QUOTE)
    },
)

/** The naming of a format that writes the inventory's labels as they are — which is JSON's and YAML's. */
private val AS_WRITTEN: (String) -> String = { it }

/**
 * **What [parsePlan] reads** — the frame off, the format decided, the plan read, and the offsets put
 * back where the input had them.
 *
 * @param formats the vocabularies to try, which is [POSTGRES_FORMATS] everywhere but in the test that
 *   asserts what happens when two of them accept. That case cannot be built out of the real four,
 *   because they are exclusive — and a rule that can only be asserted by inspection is one that stops
 *   being asserted the day it stops being true.
 */
internal fun readingOf(text: String, formats: List<PlanFormat> = POSTGRES_FORMATS): PlanReading {
    val framing = framingOf(text)
    val matched = formats.filter { it.recognises(framing.inner) }

    // Zero and two are one answer, and it is the same answer: *this is not a plan this engine reads*.
    // Two is the interesting half — the input matched more than one vocabulary, and **refusing is
    // what not picking one looks like**.
    if (matched.size != 1) return PlanReading.Unreadable

    return try {
        val occurrences = matched.single().read(framing.inner).map { framing.translate(it) }
        PlanReading.Read(SnippetPlan(text, occurrences.sortedBy { it.start }))
    } catch (refusal: PlanRefusal) {
        refusal.reading
    }
}

/**
 * **A verdict thrown from inside a reader** — the one place this product uses an exception for
 * something that is not a failure.
 *
 * A refusal can be decided at any depth of a document, and the alternative is a result type threaded
 * through every walk in [PlanStructureReader] and every line rule in the text reader. That threading
 * is exactly where a later change forgets to propagate one — and a refusal that is silently dropped
 * is a plan this product reads while believing it refused. It is caught in [readingOf] and nowhere
 * else.
 *
 * **It carries a [PlanReading] and never a message**, so the rule that no refusal can quote the input
 * holds here for the reason it holds everywhere: there is nowhere to put the text.
 */
internal class PlanRefusal(val reading: PlanReading) : RuntimeException(null, null, false, false)

/**
 * **The reading of a structured document** — the two passes every format runs, and the refusal a
 * document that did not parse produces.
 *
 * Two passes over one set of declarations, for the reason the text format runs two: a plan prints the
 * use of a name it declared as readily above the declaration as below it, and a reader that keyed
 * each name where it met it would hand out two placeholders for one thing. The first pass's
 * occurrences are thrown away.
 */
private fun structuredOccurrencesIn(
    queries: List<PlanMapping>?,
    naming: (String) -> String,
    quote: String,
): List<PlanOccurrence> {
    if (queries == null) throw PlanRefusal(PlanReading.Unreadable)

    val declared = mutableSetOf<String>()
    val declarations = PlanStructureReader(PlanSymbols(declared, POSTGRES), naming, quote)
    queries.forEach(declarations::readQuery)

    val symbols = PlanSymbols(declared, POSTGRES)
    val reader = PlanStructureReader(symbols, naming, quote)
    queries.forEach(reader::readQuery)
    return symbols.occurrences
}

/**
 * **Whether this is a YAML plan** — a block sequence whose first item opens a mapping, which is the
 * one shape PostgreSQL's YAML printer emits.
 *
 * It cannot accept a JSON plan, and the reason is structural rather than incidental: a JSON document
 * begins with `[` and this requires `- ` at the start of the first line with content.
 */
private fun opensAYamlPlan(text: String): Boolean {
    val first = text.lineSequence().firstOrNull { it.isNotBlank() } ?: return false
    return first.startsWith("- ") && first.contains(':')
}

/** How JSON and YAML write a double quote inside a scalar. See [PlanStructureReader]. */
private const val JSON_QUOTE = "\\\""

/** How XML writes one, which is as itself: element content escapes `&`, `<` and `>` and nothing else. */
private const val XML_QUOTE = "\""
