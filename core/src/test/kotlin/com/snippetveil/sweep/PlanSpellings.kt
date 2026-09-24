package com.snippetveil.sweep

import com.snippetveil.core.MYSQL
import com.snippetveil.core.ORACLE
import com.snippetveil.core.POSTGRES
import com.snippetveil.core.PlanEngine
import com.snippetveil.core.PlanVocabulary
import com.snippetveil.core.SQLSERVER

/**
 * **The rider one engine's plans are read under**, or `null` for an engine that has none.
 *
 * MariaDB has none, and that absence is the engine's whole entry in this product: nothing of its
 * output is read, so no argument was ever written for preserving a bare token in it. A leak oracle
 * over a MariaDB capture is therefore not merely unbuilt — it would have nothing to subtract and
 * nothing to annotate, and the capture never reaches an output for it to read.
 */
internal fun riderOf(engine: PlanEngine): PlanVocabulary? = when (engine) {
    PlanEngine.POSTGRESQL -> POSTGRES
    PlanEngine.MYSQL -> MYSQL
    PlanEngine.SQLSERVER -> SQLSERVER
    PlanEngine.ORACLE -> ORACLE
    PlanEngine.MARIADB -> null
}

/**
 * **Every identifier-shaped spelling a plan text writes** — the universe both the leak oracle and the
 * fixture-provenance trap are built over, and the one place that reading is spelled.
 *
 * > **The input's own identifier-shaped tokens, split on engine-neutral punctuation runs, with
 * > delimited spellings taken whole, minus the riders' keyword tokens and printer phrases.**
 *
 * ### Why the input and not the mapping
 *
 * The same argument the source half's `LeakOracle` is built on, and it is the whole reason this is
 * derived from the text rather than from what the parser reported: a universe built from the
 * anonymizer's own walk can only ask about names the walk *found*, and the failure worth catching is
 * the name it never looked at. Nothing here consults an occurrence, a disposition or a ledger.
 *
 * ### Engine-neutral, deliberately
 *
 * The split is on punctuation runs and nothing else — no dialect, no format, no document model. Five
 * engines print plans in four document shapes here, and a splitter that knew which one it was reading
 * would be a second parser to keep agreeing with the first. What it costs is precision, which is the
 * currency this instrument is happy to spend: see [PlanLeakOracle].
 *
 * ### Delimited spellings are kept whole *as well as* split
 *
 * A name the engine delimited may carry punctuation — `[Vis]]its]`, `"a.b"` — and a universe that
 * only split it would hold two halves of a name and never the name. So a delimited content
 * contributes **its identifier runs, and the whole spelling too where the whole spelling could be an
 * identifier.**
 *
 * *Could be an identifier* is decided by the characters, and narrowly: identifier characters, the
 * `.` that qualifies one, and the delimiters an engine doubles inside one. Everything else — a
 * space, a `(`, a `/`, an `@` — says this delimited thing is not one name, and splitting it reaches
 * every name in it anyway.
 *
 * That narrowness is what keeps this honest over documents whose **attribute values** are quoted. An
 * XML plan writes the user's whole statement, a namespace URI and a cost inside `"…"`, and a
 * universe that took each of those whole would hold members that are a sentence, a URL and a number.
 *
 * ### A spelling never starts inside a number
 *
 * A run beginning with a digit is consumed and dropped, along with the `.`-joined tail a number
 * carries — so `0x9A1B2C3D`, `16.0.4135.4` and `25kB` contribute nothing. Without that, the `x` of a
 * hex digest opens a "spelling" in every capture that prints a query hash. It is the same reading
 * the product's own lexer gives a token that opens on a digit: shape, never domain.
 *
 * ### The subtraction, and the one row it must never reach
 *
 * A **bare** spelling the rider's [PlanVocabulary.words], constants or phrase words hold is dropped:
 * those are spellings the engine's quoting rule says cannot be a user's identifier printed bare.
 * A **delimited** spelling is never dropped, whatever it is spelled — a delimited token is always a
 * name, so a table called `Sort` is a finding and not a keyword.
 *
 * > **[PlanVocabulary.builtins] is never subtracted.**
 *
 * That list is the stated leak surface: a user function shadowing a builtin is preserved by design,
 * so an oracle that subtracted the list would be green on exactly the defect the list is known to be
 * able to have. A builtin spelling stays in the universe and, where it survives, is reported as a
 * **rule-stated preserve annotated with the row that saved it**. See [PlanLeakOracle.survivorsIn].
 *
 * @param spelling the identifier, exactly as the text writes it — without the delimiters, and with a
 *   doubled closing delimiter recovered to the single character it stands for
 * @param delimited whether the engine wrote it inside delimiters, which is what makes it a name
 *   whatever it is spelled
 */
internal class PlanSpelling(val spelling: String, val delimited: Boolean)

/**
 * Every spelling [text] writes, in the order it writes them and once each — the first reading of a
 * spelling is the one kept, so a name delimited in one place and bare in another is a delimited one.
 *
 * Deduplicated here rather than by every caller, because both callers want the set and neither wants
 * the count: how many times a plan printed a relation says nothing about whether it leaked.
 */
internal fun spellingsIn(text: String): List<PlanSpelling> {
    val found = LinkedHashMap<String, PlanSpelling>()

    fun add(spelling: String, delimited: Boolean) {
        if (spelling.isEmpty()) return
        val existing = found[spelling]
        if (existing == null || (delimited && !existing.delimited)) {
            found[spelling] = PlanSpelling(spelling, delimited)
        }
    }

    /** The identifier runs inside one stretch of text, each a spelling of its own. */
    fun addRuns(inner: String, delimited: Boolean) {
        var at = 0
        while (at < inner.length) {
            if (inner[at].isDigit()) {
                at = endOfNumber(inner, at)
                continue
            }
            if (!opensARun(inner[at])) {
                at++
                continue
            }
            var end = at
            while (end < inner.length && continuesARun(inner[end])) end++
            add(inner.substring(at, end), delimited)
            at = end
        }
    }

    var at = 0
    while (at < text.length) {
        val character = text[at]
        val closing = CLOSING[character]
        if (closing != null) {
            val content = StringBuilder()
            var next = at + 1
            var closed = false
            while (next < text.length) {
                if (text[next] == closing) {
                    // A doubled closing delimiter is the character itself, which is how every engine
                    // here escapes one inside a name it delimited.
                    if (next + 1 < text.length && text[next + 1] == closing) {
                        content.append(closing)
                        next += 2
                        continue
                    }
                    closed = true
                    break
                }
                content.append(text[next])
                next++
            }

            // An opener that never closes is not a delimited spelling. Its content is read as bare
            // text rather than skipped: **the universe never shrinks because a document was odd.**
            val inner = content.toString()
            if (closed) {
                addRuns(inner, delimited = true)
                if (couldBeOneIdentifier(inner)) add(inner, delimited = true)
                at = next + 1
            } else {
                addRuns(inner, delimited = false)
                at = text.length
            }
            continue
        }

        if (character.isDigit()) {
            at = endOfNumber(text, at)
            continue
        }

        if (opensARun(character)) {
            var end = at
            while (end < text.length && continuesARun(text[end])) end++
            add(text.substring(at, end), delimited = false)
            at = end
            continue
        }
        at++
    }

    return found.values.toList()
}

/**
 * The universe [text] yields under [vocabulary] — every spelling above, with the rider's bare-word
 * rows taken out of the bare half and nothing taken out of the delimited half.
 *
 * See [PlanSpelling] for the whole of the argument, including why [PlanVocabulary.builtins] is not a
 * parameter of this function and cannot be made one.
 */
internal fun universeOf(text: String, vocabulary: PlanVocabulary): List<PlanSpelling> {
    val printer = printerWordsOf(vocabulary)
    return spellingsIn(text).filter { it.delimited || it.spelling !in printer }
}

/**
 * **The rider's keyword tokens and printer phrases, as single words** — and `builtins` is not among
 * them, which is the one thing this function exists to make visible in one place.
 *
 * A phrase contributes each of its words: `double precision` reaches a scanner as two tokens, and a
 * universe that subtracted only the whole phrase would hold `precision` as a candidate name in every
 * plan that casts anything.
 */
internal fun printerWordsOf(vocabulary: PlanVocabulary): Set<String> =
    vocabulary.words + vocabulary.constants + vocabulary.phraseWords

/** Whether a spelling can start here — a letter or an underscore, as every engine's identifiers do. */
private fun opensARun(character: Char): Boolean = character.isLetter() || character == '_'

/**
 * Where the number starting at [at] ends — its digits, its letters, and the `.`-joined tail a
 * version or a decimal carries. Everything inside it is dropped. See [spellingsIn].
 */
private fun endOfNumber(text: String, at: Int): Int {
    var end = at
    while (end < text.length) {
        val character = text[end]
        when {
            continuesARun(character) -> end++
            character == '.' && end + 1 < text.length && text[end + 1].isDigit() -> end++
            else -> return end
        }
    }
    return end
}

/**
 * Whether a delimited content could be **one identifier**, and is therefore worth holding whole as
 * well as split.
 *
 * Identifier characters, the `.` that qualifies one, and the delimiters an engine doubles inside
 * one. A space, a bracket, a slash or an `@` says otherwise. See [PlanSpelling].
 */
private fun couldBeOneIdentifier(inner: String): Boolean =
    inner.isNotEmpty() &&
        opensARun(inner[0]) &&
        !inner.all(::continuesARun) &&
        inner.all { continuesARun(it) || it == '.' || it in CLOSING.values }

/**
 * Whether a spelling continues here — letters, digits, `_`, and the `$` Oracle writes a query block's
 * name with.
 */
private fun continuesARun(character: Char): Boolean =
    character.isLetterOrDigit() || character == '_' || character == '$'

/**
 * **The delimiters every engine here writes a name inside**, each mapped to what closes it.
 *
 * One table for all of them rather than one per engine, because the reading is engine-neutral by
 * construction: a `[` in a PostgreSQL plan is an array subscript and its content is read as bare text
 * either way, which costs precision and never a member.
 */
private val CLOSING: Map<Char, Char> = mapOf('"' to '"', '`' to '`', '[' to ']')
