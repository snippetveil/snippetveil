package com.snippetveil.core

/**
 * **Puts the project's own names back into an AI's reply.**
 *
 * The other direction of [anonymize], and pure in the same way: no clock, no I/O, no state outside
 * its arguments. The reply arrives on the clipboard, the real names go back on it, and everything
 * that was not ours is left byte-for-byte alone.
 *
 * ### The reversal contract
 *
 * > Match **only the exact placeholder strings SnippetVeil minted**, on word boundaries,
 * > case-sensitively. Everything else in the reply is left verbatim.
 *
 * **This is not the pattern-matching the product refuses elsewhere.** *Do not guess at domain names
 * by shape* forbids inspecting text to decide what a name means. Here the namespace is one this
 * engine created and wrote down, so an exact hit against the recorded table is **total, not
 * heuristic** — the opposite situation, and the reason the match may be exact rather than clever.
 *
 * It is why the restore pass is driven by *the table* rather than by [MINTED]: every word of the
 * reply is looked up, and a word that is in the table is by definition a string we minted. The shape
 * recogniser is used for one thing only, which is deciding what a word that did **not** decode was —
 * see [Unrestored].
 *
 * ### Under-recovery is the designed behaviour
 *
 * A word the tables do not know stays exactly as the model wrote it. If the AI names a variable of
 * its own `local1`, it stays `local1`: an obvious, inspectable gap. Fuzzy matching — case-insensitive,
 * or absorbing a compound like `Type1Test` — would recover more of the reply while **deciding without
 * evidence** that a token the model invented refers to a mapped symbol, and would manufacture text
 * the AI never meant. A gap a reader can see beats a name a reader has no way to doubt.
 *
 * ### Lookup order: the sidecar, then the mapping
 *
 * The sidecar holds recent invocations *whole* — locals, parameters, and the text of redacted
 * literals, none of which is in the durable mapping — so it is asked first because it knows more,
 * not because the two could disagree. They cannot: a placeholder stands for one symbol in the
 * project's whole history, which is what burning numbers buys.
 *
 * @param text the reply, exactly as it came off the clipboard
 * @param sidecar the recent invocations, already trimmed to their horizon
 * @param mapping the durable mapping, whose counter is also what tells the two kinds of
 *   under-recovery apart
 */
fun deanonymize(text: String, sidecar: Sidecar, mapping: LedgerSnapshot): Reversal {
    val restored = LinkedHashSet<String>()
    val unrestored = LinkedHashMap<String, Unrestored>()
    val rebuilt = StringBuilder(text.length)

    // One left-to-right pass, so a restored name is never itself re-read. A literal's text is the
    // case that makes this matter rather than a precaution: it is arbitrary content the user once
    // wrote, and a second pass over it would happily decode a word that only ever lived inside a
    // string.
    var copied = 0
    for (word in WORD.findAll(text)) {
        val token = word.value
        val original = sidecar.originalOf(token) ?: mapping.originalOf(token)

        if (original == null) {
            if (MINTED.matches(token)) {
                unrestored.getOrPut(token) { Unrestored(token, reasonFor(token, mapping.nextNumber)) }
            }
            continue
        }

        rebuilt.append(text, copied, word.range.first)
        rebuilt.append(original)
        copied = word.range.last + 1
        restored += token
    }
    rebuilt.append(text, copied, text.length)

    return Reversal(rebuilt.toString(), restored.toList(), unrestored.values.toList())
}

/**
 * What a reversal did to a reply.
 *
 * Counted in **distinct placeholders** rather than in occurrences, in both directions, because that
 * is what a reader is holding: *"12 restored"* is twelve names they now have, where a count of
 * occurrences would report the same name twelve times and read as twelve findings.
 *
 * @param text the reply with every placeholder the tables knew replaced in place
 * @param restored the placeholders that decoded, in the order they are first written
 * @param unrestored the placeholder-shaped words that did not, in the order they are first written
 */
class Reversal(
    val text: String,
    val restored: List<String>,
    val unrestored: List<Unrestored>,
) {

    /**
     * Whether this reply had anything of ours in it at all — restored or not.
     *
     * The two are one question, and that is the point: a reply full of placeholders that all fell
     * past the horizon is **not** *"nothing found"*. Reporting it as such would tell the user their
     * clipboard is clean when it is full of words they cannot read.
     */
    val found: Boolean = restored.isNotEmpty() || unrestored.isNotEmpty()
}

/**
 * One placeholder-shaped word that did not decode, and which of the two things that means.
 *
 * A value, and compared as one — like [MintedName], and for the reason a test wants: a list of these
 * asserted against a list of expected ones has to compare what they *say*, or a green assertion over
 * an empty list is the only one that ever meant anything.
 *
 * @param placeholder the word as the reply writes it
 * @param reason which side of the counter it fell on. See [UnrestoredReason].
 */
data class Unrestored(val placeholder: String, val reason: UnrestoredReason)

/**
 * **Why a placeholder did not decode — two facts, not one number.**
 *
 * The never-recycling per-project counter gives an honest test for this for free, and the two
 * answers prompt different actions. Collapsed into a single *"3 not restored"* the user goes hunting
 * for a mapping that either never existed or is provably gone.
 *
 * **A confound is accepted rather than hidden.** The mapping is per-project, so a placeholder minted
 * in project A and pasted back while project B is open can fall below B's counter and be reported as
 * [EVICTED] when it is really foreign. The message stays true in the part that matters — it still
 * cannot be restored here — and the alternative is dropping a distinction that is useful every day
 * for a case that is user error.
 *
 * @param message what the details list says, in the user's words
 */
enum class UnrestoredReason(val message: String) {

    /**
     * **Below the counter and in neither table: the name is gone.** This project minted the number,
     * so the word is ours; the sidecar has forgotten the invocation and the mapping never held it,
     * which is what happens to a local, a parameter or a literal past the horizon. Stop looking.
     */
    EVICTED("beyond the recent-history window"),

    /**
     * **At or above the counter: this project never minted it.** Either the model invented a word
     * that happens to be placeholder-shaped, or it came from another project's paste. Nothing here
     * ever stood for anything.
     */
    FOREIGN("not from this project"),
}

/**
 * Which side of the counter [placeholder] falls on.
 *
 * Numbers below [nextNumber] are exactly the ones this project has handed out, because the counter
 * only ever counts up and no number is ever reused — so *was it ours* is answerable without any
 * record of the symbol surviving, which is the whole point on a placeholder nothing remembers.
 *
 * A number too large to be an integer is [UnrestoredReason.FOREIGN] rather than an error: no counter
 * reaches it, so it is above every counter, which is what the bucket says.
 */
private fun reasonFor(placeholder: String, nextNumber: Int): UnrestoredReason {
    val number = placeholder.takeLastWhile(Char::isDigit).toLongOrNull()
    return if (number != null && number < nextNumber) UnrestoredReason.EVICTED else UnrestoredReason.FOREIGN
}

/**
 * **A word, as Java spells one** — the same definition [anonymize] reserves names against, which is
 * what makes *on word boundaries* mean the same thing in both directions.
 *
 * Deliberately wider than a Java identifier at the front: `9Type1` is one word here and matches
 * nothing, where a pattern that required an identifier start would have found `Type1` inside it and
 * restored a name in the middle of a token. Boundaries are what stop `Type1Test` and `xType1` from
 * being absorbed, so they are drawn to include everything an identifier could contain.
 */
private val WORD = Regex("""[\p{L}\p{N}_$]+""")

/**
 * **What a placeholder looks like** — every namespace this engine mints from, and nothing else.
 *
 * Read off [SymbolRole] rather than written out, so that a role added later cannot leave a namespace
 * this cannot recognise.
 *
 * **Alternation order carries no meaning here**, and that is worth saying because it looks as though
 * it should: `T` is a prefix of `Type`, so a pattern *searched* through text would have to put the
 * longer first. This one is only ever matched against a whole token, so `T` followed by `\d+` fails
 * on `Type1` and the engine backtracks to `Type` regardless of where either sits in the list.
 *
 * **It is not what restores anything** — the table is, and the table is exact. This decides only
 * what an *undecoded* word was, which is the difference between *"3 not restored"* and a silence
 * that reads as a clean clipboard.
 *
 * **The stated limit, because there is one.** A derived accessor placeholder is its prefix followed
 * by a capitalised field placeholder, and the prefixes are [AccessorEvidence.PREFIXES] — the three
 * JavaBeans spellings. That is every accessor the plan builder reports today. If one ever reported a
 * fourth, its placeholders would still *restore* — the table is exact and knows nothing about shape
 * — and would merely go uncounted when they fell past the horizon. Under-recovery, never a wrong
 * name, which is the direction everything here errs in.
 *
 * **Something now depends on that count being exhaustive, which it did not when this was written.**
 * `De-anonymize Clipboard and Paste` refuses to write anything it did not restore in full, and asks
 * [Reversal.unrestored] to decide — so a word that goes uncounted there is not a gap a reader can
 * see but a placeholder written silently into source. The tolerance argued above was priced against
 * a clipboard the user reads before it lands anywhere. Adding a namespace without adding it here is
 * therefore no longer a cosmetic omission, and the derivation from [SymbolRole] is what keeps it
 * from being possible by accident.
 */
private val MINTED = Regex(
    (SymbolRole.entries.map { it.placeholderPrefix } +
        UNKNOWN_PREFIX +
        LITERAL_PREFIX +
        AccessorEvidence.PREFIXES.map { derivedAccessorPlaceholder(it, SymbolRole.FIELD.placeholderPrefix) })
        .distinct()
        .joinToString("|", prefix = "(?:", postfix = """)\d+""") { Regex.escape(it) },
)
