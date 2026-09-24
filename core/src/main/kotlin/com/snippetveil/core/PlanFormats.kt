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
        structuredOccurrencesIn(jsonQueriesIn(it), POSTGRES_QUERY_FIELDS, POSTGRES, AS_WRITTEN, JSON_QUOTE)
    },
    PlanFormat("yaml", ::opensAYamlPlan) {
        structuredOccurrencesIn(yamlQueriesIn(it), POSTGRES_QUERY_FIELDS, POSTGRES, AS_WRITTEN, JSON_QUOTE)
    },
    PlanFormat("xml", ::opensAPostgresXmlPlan) {
        structuredOccurrencesIn(
            xmlQueriesIn(it),
            POSTGRES_QUERY_FIELDS,
            POSTGRES,
            { label -> POSTGRES_XML_LABELS[label] ?: label },
            XML_QUOTE,
        )
    },
)

/**
 * A format that is recognised and never read — the shape is known, and knowing it is what lets the
 * refusal name it.
 *
 * The refusal is thrown rather than returned so that a recognised-and-refused form travels the same
 * path a refusal decided halfway down a document does. There is one way out of a reader, and it is
 * [PlanRefusal].
 */
internal fun refuses(name: String, recognises: (String) -> Boolean, form: PlanRefusedForm) =
    PlanFormat(name, recognises) { throw PlanRefusal(PlanReading.Refused(form)) }

/**
 * **Every format this product recognises, of every engine it recognises** — the list [readingOf]
 * decides over, and the list the exactly-one rule is applied to.
 *
 * One list rather than a list per engine, and that is the whole of how *ambiguity falls to the
 * general refusal* is enforced: a text that opens like MySQL's JSON v1 **and** like MariaDB's JSON
 * matches two entries here, and two is answered the same way zero is. Nothing picks the engine first
 * and then the format — there is no step at which an engine could be guessed.
 */
internal val PLAN_FORMATS: List<PlanFormat> =
    POSTGRES_FORMATS + MYSQL_FORMATS + MARIADB_FORMATS + SQLSERVER_FORMATS

/** The naming of a format that writes the inventory's labels as they are — which is JSON's and YAML's. */
internal val AS_WRITTEN: (String) -> String = { it }

/**
 * **What [parsePlan] reads** — the frame off, the format decided, the plan read, and the offsets put
 * back where the input had them.
 *
 * @param formats the vocabularies to try, which is [PLAN_FORMATS] everywhere but in the test that
 *   asserts what happens when two of them accept over a text the real list keeps exclusive — a rule
 *   that can only be asserted by inspection is one that stops being asserted the day it stops being
 *   true.
 */
internal fun readingOf(text: String, formats: List<PlanFormat> = PLAN_FORMATS): PlanReading {
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
 * The passes are [occurrencesOverTwoPasses]'s, which is where the argument for them lives.
 */
internal fun structuredOccurrencesIn(
    queries: List<PlanMapping>?,
    root: Map<String, PlanTreatment>,
    vocabulary: PlanVocabulary,
    naming: (String) -> String,
    quote: String,
): List<PlanOccurrence> {
    // **A document with no plan in it is not a plan.** `[]` parses as JSON and `<explain/>` parses
    // as XML, and neither is anything `EXPLAIN` prints — so they refuse here rather than being read
    // as a plan with nothing in it, which is a *success* the user would have to notice was empty.
    if (queries.isNullOrEmpty() || queries.any { it.entries.isEmpty() }) {
        throw PlanRefusal(PlanReading.Unreadable)
    }

    return occurrencesOverTwoPasses(vocabulary) { symbols ->
        val reader = PlanStructureReader(symbols, root, naming, quote)
        queries.forEach(reader::readQuery)
    }
}

/**
 * **Whether this is PostgreSQL's XML plan** — its root element, at the head.
 *
 * `<` alone used to be enough, and it stopped being enough the moment a **second** engine's XML
 * arrived: SQL Server's Showplan is an XML document too, and a predicate matching the punctuation
 * rather than the printer would accept both — which is the ambiguity [readingOf] answers by refusing.
 * So the anchor is the root element each writer writes, which is a literal one of them produces and
 * the other cannot.
 */
private fun opensAPostgresXmlPlan(text: String): Boolean = opensAnElement(text, EXPLAIN_ROOT)

/**
 * Whether the first thing written in [text] is an element named [root] — the root tag, the name
 * whole, and nothing else in front of it.
 *
 * The character after the name is checked so that a root is matched as a **name** rather than as a
 * prefix: `<explain>` and `<explain xmlns=…>` are this element and `<explainer>` is not.
 */
internal fun opensAnElement(text: String, root: String): Boolean {
    val head = text.trimStart()
    if (!head.startsWith("<$root")) return false
    val after = head.getOrNull(root.length + 1) ?: return false
    return after.isWhitespace() || after == '>' || after == '/'
}

/** The root element of PostgreSQL's XML plan. See [opensAPostgresXmlPlan]. */
private const val EXPLAIN_ROOT = "explain"

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
internal const val JSON_QUOTE = "\\\""

/** How XML writes one, which is as itself: element content escapes `&`, `<` and `>` and nothing else. */
internal const val XML_QUOTE = "\""
