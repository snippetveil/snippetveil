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
 * ### A row says what it is, not only what it does
 *
 * A row carries three facts beyond its predicate and its reader, and each of them exists because a
 * statement about the **vocabulary** cannot be made from the predicates alone.
 *
 *  - [engine], so that *per engine* is a question this list can answer. Nothing in the reading path
 *    reads it: the rule that no step guesses an engine before choosing a format is unchanged, and
 *    [PLAN_FORMATS] is still one flat list for exactly that reason.
 *  - [state], so that a row's **zero captures** can be told from another's. See [PlanFormatState].
 *  - [reach], so that *what a user gets without asking* is a property of the row rather than a
 *    sentence in somebody's prose. See [PlanFormatReach].
 *
 * @param name what the format is called, for a test to name it in a failure
 * @param engine whose output this is. A fact about the row, never an input to the reading.
 * @param state whether this row admits the form, refuses it, or refuses it for want of a capture
 * @param reach how far a user has to go to get this form out of that engine
 * @param recognises whether a text is written in this format. Anchored, and cheap: it decides which
 *   reader runs and never what is in the text.
 * @param read what the format's reader makes of the text — the occurrences, or a throw carrying the
 *   verdict. See [PlanRefusal].
 */
internal class PlanFormat(
    val name: String,
    val engine: PlanEngine,
    val state: PlanFormatState,
    val reach: PlanFormatReach,
    val recognises: (String) -> Boolean,
    val read: (String) -> List<PlanOccurrence>,
)

/**
 * **Whose output a format row is about** — a fact carried by the row, and read by nothing in the
 * reading path.
 *
 * It is here so that the statements this product makes *per engine* — the default-form share, the
 * coverage floor — are read off the vocabulary rather than written out beside it, where a row added
 * later would not reach them. **MariaDB is an engine of its own** and not a MySQL variant: every
 * sentence this product says about it differs from every sentence it says about MySQL, which is the
 * whole reason [MARIADB_FORMATS] exists.
 *
 * @param printed how the engine is named to a person reading a report
 */
internal enum class PlanEngine(val printed: String) {
    POSTGRESQL("PostgreSQL"),
    MYSQL("MySQL"),
    MARIADB("MariaDB"),
    SQLSERVER("SQL Server"),
    ORACLE("Oracle"),
}

/**
 * **What a vocabulary row says about the form it names** — and the reason the third state exists at
 * all.
 *
 * A row with **zero captures behind it** means two opposite things, and nothing but the row can say
 * which. *Zero captures because the form is refused* is the shipped, honest behaviour and holds
 * nothing up. *Zero captures because nobody looked* is a form admitted on no evidence, and that
 * holds the release. So the state is on the row, and the instrument's denominator clause reads it
 * rather than guessing from the reader's shape.
 *
 * See `PlanSweepReport` for the clause, and `PlanVocabularyTest` for the assertion that every row
 * carries a state consistent with what its reader actually does.
 */
internal enum class PlanFormatState {

    /** **The form is read.** A row in this state with zero captures behind it holds the release. */
    ADMITTED,

    /**
     * **The form is recognised and refused on an argument about the form itself** — a name written
     * with no delimiter, a statement echoed into the plan's own rowset, a writer that does not
     * escape what it prints. A capture would not change the answer, so zero of them is consistent.
     */
    REFUSED,

    /**
     * **The form is recognised and refused for want of a capture** — the treatment may even be
     * decided, and what is missing is the evidence that the text is shaped the way it is believed to
     * be.
     *
     * Its own state rather than [REFUSED], because the two are **different findings** in front of
     * the same zero: one is a decision and the other is an outstanding piece of work. A row here is
     * reported as outstanding and holds nothing up, because the shipped behaviour — refusing — is
     * the fail-closed one either way.
     *
     * **No row carries this state today, and that is a fact rather than an oversight.** Every form
     * recognised here is refused on an argument about the form, and the shapes refused for want of a
     * capture — SQL Server's results-to-text mode, MySQL's vertical `\G` form, Oracle's peeked-bind
     * section, `psql`'s unaligned frame — have no predicate at all and fall to the general refusal.
     * The day one of them gains a recogniser ahead of a capture, this is the state it gains it in.
     */
    REFUSED_PENDING_CAPTURE,
}

/**
 * **How far a user has to go to get this form out of its engine** — the property behind the
 * default-form share and behind the coverage floor.
 *
 * It is on the row because both of those are statements about **what the public listing may say**.
 * *Paste your `EXPLAIN` output* is true only where the [DEFAULT] form is admitted, and the floor —
 * *if no engine's default-or-one-flag-away form is admitted, the release waits* — is a question
 * about this field and [PlanFormat.state] together, asked over the whole list.
 */
internal enum class PlanFormatReach {

    /**
     * **What the engine prints without being asked for anything.**
     *
     * An engine may have **no row in this state**, and SQL Server does not: it prints no plan at all
     * until a session option is set. That absence is itself a copy fact, and it is reported as one
     * rather than smoothed over by calling some option's output the default.
     */
    DEFAULT,

    /** **One option away** — `FORMAT JSON`, `FORMAT=JSON`, `SET SHOWPLAN_XML ON`. */
    ONE_FLAG,

    /**
     * **Further than one option** — a report procedure called with arguments, a separate licensed
     * pack. A form here is one a user reaches deliberately, and it can never carry the floor.
     */
    FURTHER,
}

/**
 * **PostgreSQL's four output formats**, in the order a reader meets them and in no other.
 *
 * **The text format is this engine's default and it is admitted**, which is the one row carrying the
 * coverage floor: without it the feature would fire only for users who already know which option to
 * pass. See [PlanFormatReach.DEFAULT].
 */
internal val POSTGRES_FORMATS: List<PlanFormat> = listOf(
    reads("text", PlanEngine.POSTGRESQL, PlanFormatReach.DEFAULT, ::opensATextPlan, ::textOccurrencesIn),
    reads("json", PlanEngine.POSTGRESQL, PlanFormatReach.ONE_FLAG, { it.trimStart().startsWith("[") }) {
        structuredOccurrencesIn(jsonQueriesIn(it), POSTGRES_QUERY_FIELDS, POSTGRES, AS_WRITTEN, JSON_QUOTE)
    },
    reads("yaml", PlanEngine.POSTGRESQL, PlanFormatReach.ONE_FLAG, ::opensAYamlPlan) {
        structuredOccurrencesIn(yamlQueriesIn(it), POSTGRES_QUERY_FIELDS, POSTGRES, AS_WRITTEN, JSON_QUOTE)
    },
    reads("xml", PlanEngine.POSTGRESQL, PlanFormatReach.ONE_FLAG, ::opensAPostgresXmlPlan) {
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
 * A format that is read — the admitted row, with its reader.
 *
 * A builder rather than the constructor at each site, so that **the state is not a parameter anybody
 * spells**: an admitted row is admitted by being written with a reader, and a refused one by being
 * written with [refuses]. A row cannot claim one and do the other by a typo.
 */
internal fun reads(
    name: String,
    engine: PlanEngine,
    reach: PlanFormatReach,
    recognises: (String) -> Boolean,
    read: (String) -> List<PlanOccurrence>,
) = PlanFormat(name, engine, PlanFormatState.ADMITTED, reach, recognises, read)

/**
 * A format that is recognised and never read — the shape is known, and knowing it is what lets the
 * refusal name it.
 *
 * The refusal is thrown rather than returned so that a recognised-and-refused form travels the same
 * path a refusal decided halfway down a document does. There is one way out of a reader, and it is
 * [PlanRefusal].
 *
 * @param state [PlanFormatState.REFUSED] unless the refusal is waiting on a capture rather than on
 *   an argument — see [PlanFormatState.REFUSED_PENDING_CAPTURE], which no row carries today
 */
internal fun refuses(
    name: String,
    engine: PlanEngine,
    reach: PlanFormatReach,
    recognises: (String) -> Boolean,
    form: PlanRefusedForm,
    state: PlanFormatState = PlanFormatState.REFUSED,
) = PlanFormat(name, engine, state, reach, recognises) { throw PlanRefusal(PlanReading.Refused(form)) }

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
    POSTGRES_FORMATS + MYSQL_FORMATS + MARIADB_FORMATS + SQLSERVER_FORMATS + ORACLE_FORMATS

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
