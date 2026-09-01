package com.snippetveil.core

/**
 * The choices one invocation runs under.
 *
 * One rule governs everything that will be added here, and it is worth carrying next to the type
 * rather than in a document: **persistent settings may only ever increase anonymization. Any
 * reduction is per-invocation and visible in the preview.** A reduction that can be set once and
 * forgotten silently leaks on every paste after it.
 *
 * @param preservedSymbols the symbol keys this invocation emits verbatim, read off
 *   [MappedName.key]. **Per-invocation only, never persisted**, by the rule above — this is the one
 *   deliberate fail-open in the product, and it exists because a handful of names sometimes carry
 *   the context that makes a snippet answerable at all: a typo'd JDK call hidden behind a
 *   placeholder, or a variable called `filter` that the question is entirely about.
 *
 *   **A key may name any symbol this invocation would otherwise replace** — a resolved local,
 *   parameter, field, method or type as readily as an unresolved name. The friction that used to be
 *   *the engine refuses* is an explicit unlock in the preview instead: locked again on every open,
 *   warned before it opens, and remembered nowhere. That puts the friction at the moment of
 *   reduction, where it is read, rather than on every invocation — and it leaves the governing rule
 *   above exactly as it was, because a reduction is still per-invocation and still visible in the
 *   preview. (It reached unresolved names alone until the maintainer extended it on 2026-08-31,
 *   which is worth one sentence because comments elsewhere may still be catching up.)
 *
 *   **A key naming anything this invocation was not going to replace is a no-op**, which is what
 *   keeps the extension off the spine rule: the JDK and third-party libraries are preserved before
 *   this set is consulted, a name Java forbids from being renamed was never going to move, and a
 *   key naming nothing in the snippet names nothing. **A literal has no key and stays
 *   non-preservable** — an explicit non-goal, literal text being the most directly sensitive
 *   content the product handles.
 *
 *   **A key is the key the placeholder was handed out against** — see [MappedName.key] — so one
 *   tick on the single row two symbols share preserves the whole override chain rather than half of
 *   it, which would rename a declaration and keep its call site.
 *
 *   **A preserved type keeps its simple name and nothing more**: its package still follows the
 *   package rules, so `com.acme.billing.PaymentFilter` in a renamed package renders
 *   `pkg1.PaymentFilter`. Preservation reaches the symbol's own name, and the package-renaming
 *   spine rule is not a thing it touches.
 * @param keepComments whether comments and javadoc survive into the output. **Off by default, and
 *   per-invocation only** — a later ticket puts it in the preview as a tick that resets to *strip*
 *   on every invocation, and it is **never persisted**, by the rule above.
 *
 *   Comments are the largest single domain leak in the product. Renaming symbols does essentially
 *   nothing to prose: an experiment measured **28/29/29 distinct domain words surviving** across
 *   three naming schemes against **36** in the original — enough to reconstruct the business domain
 *   — and `// reconcile against the merchant ledger` sitting two lines above `field1` is incoherent
 *   on its face. Under an adversary model whose primary threat is retention, a reduction that can be
 *   ticked once and forgotten is the severe failure mode: **one tick set a year ago silently leaks
 *   the domain on every paste since.**
 *
 *   **One flag, not two.** The leak is prose, and prose is in comments and javadoc alike; two knobs
 *   would be a settings surface with no use case behind it.
 *
 *   Rejected on the way here, so it need not be re-litigated: a persistent toggle plus a README
 *   non-goal (*"we told you"* is a weak answer for a tool whose moat is trust); a persistent toggle
 *   plus a preview banner (a warning shown on every invocation stops being read within a week); and
 *   removing the control altogether, which hands the developer who genuinely always wants comments a
 *   forced loss with no escape hatch.
 * @param renamedStems symbol key -> the stem this invocation names that symbol with, read off
 *   [MappedName.key] exactly like [preservedSymbols]. **This map is per-invocation only and is never
 *   persisted** — no store holds *which key was renamed to what*, so a rename cannot come back as a
 *   setting nobody re-confirmed. The stem itself is a different question; see below.
 *
 *   **Renaming is stem-only, and the number always stays**: `Type1` may become `FilterType1`, and
 *   it may never become `Filter`. A stem is a hint the user invents to give the AI context — *the
 *   filter this question is about* — and the mandatory number is what keeps the output announcing
 *   itself as anonymized while it carries the hint. See [stemRejection] for what a stem may be, and
 *   note that **an invalid stem is ignored here** rather than trusted from the dialog: the rule is
 *   the engine's, so a caller that never validated anything cannot break the invariant.
 *
 *   **A rename is not a reduction.** The symbol is still replaced, so this does not strain the rule
 *   above — but the stem is text the user typed and it goes to the AI, which makes it a **chosen
 *   disclosure** of whatever meaning was typed into it. `THREAT-MODEL.md` names it as one.
 *
 *   **Stability comes from the existing ledger row and from nothing else.** A renamed placeholder
 *   for a qualified key is written into [LedgerDelta.placeholders] like any other, which is what
 *   makes `FilterType1` come back on next week's paste. An unqualified key — a local, a parameter, a
 *   type parameter, a label — is never ledgered, so its rename lasts this invocation and the next
 *   snippet re-mints it under the default stem. That is an accepted cost rather than an oversight:
 *   the `filter` local still carries its context in the snippet where the question is.
 *
 *   **The stem is stored, and the pairing is not.** [LedgerDelta.mintedStems] records the *words*
 *   this invocation minted under — a set, filed under no key — because a reversal has no way to tell
 *   `theFilter7` from ordinary prose unless it was told the word, and `De-anonymize Clipboard and
 *   Paste` refuses to write a reply it could not restore in full. That is read by the reversal and
 *   never by this engine: nothing here consults it, so a recorded stem renames nothing on a later
 *   invocation, which is what keeps the map above a per-invocation input.
 *
 *   **Three keys are ignored**, and each for its own reason — see [Renaming], which is how core
 *   tells the dialog which rows to offer. A key already in the [LedgerSnapshot] keeps the
 *   placeholder it was given, because the ledger is append-only and rewriting an entry would make an
 *   old reply decode wrongly. An unresolved name keeps the `Unknown` namespace, which is
 *   load-bearing: `Unknown1` tells the model the IDE could not resolve this. And a literal has no
 *   key to be named by at all.
 * @param internalLibraries which library packages are the company's own code arriving as a jar.
 *   **The one setting the product persists**, and see [InternalLibraries] for why it survives the
 *   rule above rather than being an exception to it.
 */
class AnonymizationSettings(
    val preservedSymbols: Set<String> = emptySet(),
    val keepComments: Boolean = false,
    val renamedStems: Map<String, String> = emptyMap(),
    val internalLibraries: InternalLibraries = InternalLibraries(),
) {
    companion object {
        /** Everything anonymized that can be: no reduction of any kind. */
        val DEFAULTS: AnonymizationSettings = AnonymizationSettings()
    }
}

/**
 * **Which library packages hold the company's own code.**
 *
 * A symbol from a shared internal artifact is the project's domain vocabulary, and it must not leak
 * merely because it arrived as a jar. Nothing in the IDE can tell such an artifact from a
 * third-party one — both are a `LibraryOrderEntry`, and only the library's name, its root URLs and
 * its Maven coordinates exist to look at — so this is a **policy**, not a lookup, and the policy is
 * a package prefix.
 *
 * **This is the only persistent setting v1 ships, and it survives the governing rule** — *persistent
 * settings may only ever increase anonymization* — precisely because it only ever pulls **more**
 * symbols into the anonymized set. Before this rule every library symbol was preserved; nothing
 * written here can take the output back past that, and [thirdPartyPrefixes] can only give back what
 * the two rules above it claimed. The baseline the rule is measured against is *no internal-library
 * rule at all*, and against that baseline this one only ever adds.
 *
 * Fail closed is the direction to err in here, and cheaply so: over-anonymizing a genuinely public
 * library only dulls a signal the product already disclaims — concealing the tech stack is a
 * declared non-goal, not a promise — while under-anonymizing puts the employer's domain vocabulary
 * on the clipboard, which is the whole failure.
 *
 * @param autoDetectRootPackage whether the root package of the file under analysis claims library
 *   symbols beneath it — `com.acme` out of `com.acme.web.PaymentController`. **On by default**: the
 *   reverse-domain convention makes it right far more often than not, and the cost of it being
 *   wrong is the cheap direction above.
 * @param internalPrefixes package prefixes the heuristic misses, added by a human. It exists for a
 *   shape no file under analysis can see: an employer whose shared artifacts are published under a
 *   different group id from the repository consuming them.
 * @param thirdPartyPrefixes package prefixes wrongly claimed, removed by a human — the
 *   open-source project an employer publishes under its own group id, whose name a reader needs.
 *
 *   **The longer prefix decides**, so `com.acme` and `com.acme.oss` can be stated together and mean
 *   what they read as. On an exact tie the removal wins, because a human who typed a prefix out is
 *   answering the heuristic that guessed it.
 *
 *   A blank entry matches nothing, on either side. It is what an empty row in the settings list
 *   produces, and *"the list had an empty line in it"* must not be a way to switch the rule off — or,
 *   through [internalPrefixes], a way to anonymize every library in the world.
 */
class InternalLibraries(
    val autoDetectRootPackage: Boolean = true,
    val internalPrefixes: Set<String> = emptySet(),
    val thirdPartyPrefixes: Set<String> = emptySet(),
)

/**
 * The placeholders already handed out, as they stood when this invocation started.
 *
 * **Placeholders are stable: `CustomerService` is `Type1` today, tomorrow, and after an IDE
 * restart.** The deciding argument is fidelity rather than privacy. The common workflow is a
 * conversation — paste a method, the AI asks to see the collaborator, paste that too — and
 * fresh-per-invocation numbering makes *the second paste contradict the first*: `CustomerService`
 * arrives as `Type3` while `Type1` has already been handed to `OrderRepo`, and the model then
 * reasons confidently about types that do not exist.
 *
 * Core takes the ledger as a **snapshot in** and returns a [LedgerDelta] **out**, rather than
 * holding an injected store it mutates. The reason is a preview that gets cancelled: under a
 * mutating store, allocation happens while the preview renders, so a cancelled preview would burn
 * numbers — and a burnt number is not recoverable, because the invariant that makes reverse mapping
 * work is that no two symbols ever render to the same placeholder. A snapshot in and a delta out
 * makes "cancelled" mean *nothing happened*, with no compensating logic to get wrong.
 *
 * @param placeholders symbol key -> what that symbol was named. **Qualified keys only** — see
 *   [LedgerDelta] for why, and for what happens to everything else.
 * @param nextNumber the next number the counter will hand out
 * @param mintedStems every stem this project has minted a placeholder under that is not one of the
 *   engine's own namespaces — see [LedgerDelta.mintedStems]. Defaulted, because a project that has
 *   never renamed anything has none and a file written before this existed says so correctly.
 */
class LedgerSnapshot(
    val placeholders: Map<String, MintedName>,
    val nextNumber: Int,
    val mintedStems: Set<String> = emptySet(),
) {

    /**
     * **The same rows read from the other end**, which is the end a reply is read from: a person
     * pastes back `Type1` and wants `Payment`, having no idea what key it was filed under.
     *
     * Built here rather than at each call site because [deanonymize] asks it once per word of an
     * AI's reply, and a scan over the values per word would be quadratic in a file that grows for
     * the life of a project. **[Sidecar.originalOf] deliberately does not do the same, and the
     * asymmetry is the difference between the two stores rather than an oversight**: the window is
     * bounded at ~50 invocations and the mapping is unbounded by design, so a scan is a constant
     * there and a growing cost here. Adding an index to the sidecar would buy nothing and put a
     * second copy of the most sensitive table in the product in memory.
     *
     * It is well-defined because placeholders are injective across the
     * project's whole history — see [LedgerDelta] — so a value collapsing two rows into one is a
     * thing the counter makes impossible rather than a case to handle.
     *
     * **A row with no name is not a row that decodes to nothing; it is a row that knows nothing**, and
     * the two are only the same thing if the blank is dropped here. A store that has one — a file
     * written before the mapping kept names — would otherwise answer `""`, and a reversal taking an
     * answer at face value would **delete the placeholder from the reply**. That is the one shape of
     * wrong this whole design refuses: silent, invisible in the output, and worse than the gap it
     * replaced. No real symbol has an empty name, so nothing legitimate is dropped with it.
     */
    private val originals: Map<String, String> by lazy {
        placeholders.values.filter { it.original.isNotEmpty() }.associate { it.placeholder to it.original }
    }

    /**
     * The name [placeholder] was minted for, or `null` when this project never minted it — which a
     * reversal renders by leaving the word alone.
     */
    fun originalOf(placeholder: String): String? = originals[placeholder]

    companion object {
        /** No symbol has been named yet, and numbering starts at 1. */
        val EMPTY: LedgerSnapshot = LedgerSnapshot(emptyMap(), nextNumber = 1)
    }
}

/**
 * **One row of the mapping: a placeholder, and the name it was minted for.**
 *
 * The pair rather than the placeholder alone, because the mapping is *read in both directions* and
 * only one of them was ever written down. Forward — key to placeholder — is what makes `Payment`
 * come out as `Type1` again next week. Backward — placeholder to name — is what [deanonymize]
 * needs, and a row holding only the placeholder cannot answer it: the reversal would have to read
 * the name back out of the key, which is a string the plan builder owns the spelling of and `:core`
 * is not allowed to know the shape of.
 *
 * **It puts nothing new at rest.** A qualified key already contains the name it is a key for —
 * `field:class:com.acme.Payment#merchantRef` — so this row states plainly what the file next to it
 * already said obliquely. What it buys is that the statement is *made* rather than reverse-engineered.
 *
 * A value, and compared as one: two rows are the same row when they say the same thing, which is
 * what lets a test assert a committed mapping by writing down what it should be.
 */
data class MintedName(val placeholder: String, val original: String)

/**
 * What one invocation would add to the ledger — returned, never applied. The caller commits it at
 * the single point where the invocation succeeds, or drops it and nothing happened.
 *
 * ### Two tiers of key, and only one of them is written down
 *
 * A stored table needs a **serializable** key, and half the symbols in a snippet do not have one:
 * anonymous and local classes, their members, locals, parameters, labels and type parameters are all
 * identified by *where they are written*, which is stable for exactly as long as the file is not
 * edited. So [placeholders] holds **only the symbols whose key is derived from a fully-qualified
 * name** — see [SymbolEvidence.keyIsQualified] — and that cut is principled rather than a
 * concession: a qualified symbol is exactly the kind an AI conversation refers back to across
 * snippets (*"the `Type1` you showed me"*), while a local's number surviving until next week buys
 * nothing. A string literal has no key at all, so `str` placeholders are never written down either
 * — which is right on its own merits, literal text being the most directly sensitive content the
 * product handles.
 *
 * ### Unpersisted symbols burn a number rather than reusing one
 *
 * This is the load-bearing detail. Every symbol draws from **one counter shared across all kinds**,
 * whether or not it is written down, and [nextNumber] moves past the ones that are not. Anonymous
 * members occupy the same `field`/`method` namespaces as persisted symbols: without burning,
 * `field44` could be an anonymous member today and a genuine persisted field next month, and **an
 * old reply mentioning `field44` would decode to the wrong name**. Burning costs one integer and
 * makes that impossible.
 *
 * The invariant it buys: **no two distinct symbols in the project's whole history ever render to
 * the same placeholder.** Within-output injectivity now holds across time, which is what makes
 * reverse mapping well-defined at all. The accepted cost is that numbers climb monotonically, so a
 * mature project emits `Type247` rather than `Type3` — placeholder form is near-irrelevant to answer
 * quality, so this is cosmetic.
 *
 * **There is deliberately no `isEmpty`.** A delta with no [placeholders] in it is still a delta that
 * has to be committed, because [nextNumber] may have moved; a caller skipping the commit on an empty
 * map would hand the burnt numbers back out to different symbols later.
 *
 * ### The stems, which are a set of words rather than a third tier of key
 *
 * [mintedStems] is the one thing here that is **not** filed under a key, and that is what it is for.
 * A stem the user typed in the preview is arbitrary text, so [deanonymize]'s shape recogniser cannot
 * know a word like `theFilter7` is a placeholder unless this project wrote the word down — and the
 * placeholder that most needs recognising is a **local's**, whose key is exactly the kind that is
 * never ledgered. So the *word* is recorded where the key could not be, which is all recognition
 * needs and strictly less than a row: a stem names no symbol, has no number attached, and says
 * nothing about what it stood for.
 *
 * **Only words the engine actually minted a placeholder under.** An invalid stem falls back to the
 * default namespace and a stem spelling one of the engine's own namespaces is refused outright, so a
 * word the user typed and the engine declined is not here. A **derived accessor's** stem is —
 * `merchantField1` under `get` renders `getMerchantField1`, which is a word of its own and in no set
 * built from what the user typed.
 *
 * **Minted is not the same as *in the output*, and the gap is deliberate.** A JavaBeans accessor
 * allocates its backing field's placeholder whether or not the field is anywhere in the snippet —
 * with Lombok it usually is not — so a stem can be recorded for a placeholder no character of this
 * invocation's text contains. That is the right way round: the ledger row for that field is written
 * too, an AI's reply can quote either name, and recognising a word this project minted is the whole
 * job.
 *
 * @param placeholders the qualified keys named during this invocation, and what they were named —
 *   see [MintedName] for why a row is a pair rather than a placeholder.
 * @param nextNumber where the counter stands afterwards. Higher than
 *   `snapshot.nextNumber + placeholders.size` whenever a number was burnt — by an unpersisted
 *   symbol, by a redacted literal, or by a candidate that collided with a name surviving into the
 *   output.
 * @param mintedStems the custom stems this invocation minted under, qualified and unqualified keys
 *   alike. Empty on every invocation that renamed nothing, which is almost all of them.
 */
class LedgerDelta(
    val placeholders: Map<String, MintedName>,
    val nextNumber: Int,
    val mintedStems: Set<String> = emptySet(),
)

/**
 * The ledger as it stands once [delta] has been committed — **the one definition of what committing
 * means**, so that a store and a test cannot drift into two readings of it.
 *
 * Append-only, and that is the whole of the rule: an entry already here is never rewritten and never
 * removed. Rename a class and its qualified key stops matching, so the renamed symbol is a *new* key
 * with the next number while the old entry stays. That is correct rather than sloppy, because the
 * ledger is **a record of what was actually sent, not an index of the current codebase**: an old
 * reply saying *"fix `Type1.method2()`"* decodes to the name that was in the snippet when it was
 * sent. Following the rename and dropping the stale key would decode it to a name that did not exist
 * at the time — which reads correct and is wrong.
 *
 * [LedgerDelta.nextNumber] replaces rather than adds, because it is where the counter *stands* and
 * not how far it moved. Taking it wholesale is what carries the burnt numbers across.
 *
 * **[LedgerDelta.mintedStems] unions**, like the rows and unlike the counter: a stem is a word this
 * project has minted under at some point in its history, and a later invocation that renamed nothing
 * has not stopped that from being true. A stem that stopped accumulating would leave last month's
 * `theFilter7` unrecognisable the moment the next paste went out under the default namespaces.
 */
operator fun LedgerSnapshot.plus(delta: LedgerDelta): LedgerSnapshot =
    LedgerSnapshot(placeholders + delta.placeholders, delta.nextNumber, mintedStems + delta.mintedStems)

/**
 * **Whether this snapshot is still the ledger it was taken from** — asked by whoever is about to
 * commit against it, so that an invocation which analysed a ledger that has since moved is re-run
 * rather than committed over the top of the one that moved it. Two invocations that both analysed
 * against `nextNumber = 7` both hand out `Type7`, to two different symbols.
 *
 * **The counter alone answers it, and that is a property of [plus] rather than a shortcut**: a
 * commit that adds an entry allocated a number to put in it, so the ledger cannot gain an entry
 * without the counter having moved. It cannot go backwards either, since [LedgerDelta.nextNumber] is
 * where an allocator that only ever counted up finished. A stem is recorded at the moment a
 * placeholder is minted under it, so [LedgerDelta.mintedStems] cannot grow without the counter
 * having moved either.
 */
fun LedgerSnapshot.isStill(latest: LedgerSnapshot): Boolean = nextNumber == latest.nextNumber

/**
 * What one invocation produced. Nothing here has been committed anywhere; the caller decides.
 *
 * @param text the anonymized snippet, ready for the clipboard
 * @param names what this invocation did to every name it touched, **in first-occurrence order** —
 *   the preview's rows, and the source of [mapping]. See [MappedName].
 * @param counts the distinct names in the snippet, partitioned by what became of them
 * @param comments what the strip removed, split by parse verdict
 * @param unknowns every name that failed to resolve, in document order of first occurrence
 * @param flattened every source name two or more of this snippet's renamed symbols shared, in
 *   first-occurrence order. See [FlattenedName], and [fidelityNotices] for what is said about it.
 *
 *   Carried here rather than worked out by whoever renders the notice, for the reason [unknowns] is:
 *   **a loss is something this invocation produced**, and the result is where what it produced is
 *   said. It is derivable from [names] — so is [unknowns], and so is [mapping] — and a derivation
 *   each surface repeats is one each surface can eventually disagree about.
 * @param delta what to commit, if the caller gets as far as committing
 */
class AnonymizationResult(
    val text: String,
    val names: List<MappedName>,
    val counts: NameCounts,
    val comments: CommentCounts,
    val unknowns: List<UnknownName>,
    val flattened: List<FlattenedName>,
    val delta: LedgerDelta,
) {

    /**
     * placeholder -> what it stands for: a real name, or the text of a literal that was replaced
     * whole. **Injective**, which is the whole point: a reverse mapping is well-defined only if no
     * two symbols render to one placeholder, and the AI's reply carries no scope context to
     * disambiguate with if they did. Two occurrences of the same literal text are two rows standing
     * for one string, which is that same direction working rather than an exception to it — the
     * reply is read placeholder-first.
     *
     * **This is the invocation's complete table, ephemeral symbols and literals included**, and it
     * is deliberately one table rather than a table and a leftover. It is what [Sidecar] records,
     * and for a literal it is the only record there will ever be: a literal has no qualified key, so
     * it is never written into the persistent mapping. In first-occurrence order.
     *
     * Derived from [names] rather than carried beside them, so that the table a user reads and the
     * table a reply is decoded against cannot be two things. A name this invocation **preserved**
     * has no placeholder and is not here: it stands for itself, and a row mapping a name to itself
     * would decode a reply that never needed decoding.
     */
    val mapping: Map<String, String> =
        names.mapNotNull { name -> name.placeholder?.let { it to name.original } }.toMap()
}

/**
 * One row of the invocation's table: a name, what it became, and what kind of thing it was.
 *
 * **Every symbol that received a placeholder is here, `Unknown`s included — and preserved JDK and
 * third-party symbols are not.** That is the preview's rule rather than a convenience: their
 * preservation is deliberate and a declared non-goal, so each would be a row the user can do nothing
 * about, and `String`, `List` and `println` would drown the fourteen rows that matter. They are a
 * number instead, in [NameCounts.preserved].
 *
 * The one row with no placeholder is **a name this invocation preserved by hand**, which is the
 * single reduction the design authorises. It stays in the table precisely because it was preserved:
 * the tick that preserved it is on the row, and a row that vanished when ticked could not be
 * unticked.
 *
 * @param original the name as it is written in the snippet, or a replaced literal's text
 * @param placeholder what it renders as, or `null` when it was preserved and its own text was
 *   emitted
 * @param kind what it is, which is what makes the table readable at a glance and the export
 *   reversible by hand
 * @param key **the key the placeholder was handed out against**, or `null` for a literal — which has
 *   none, and needs none. It is what a per-invocation preserve is expressed in; see
 *   [AnonymizationSettings.preservedSymbols].
 *
 *   The key the placeholder was handed out against rather than the symbol's own, and the two part
 *   company exactly where two symbols share a row: an override and its root are one row because Java
 *   forbids their names from diverging, and a tick that reached only one of them would rename a
 *   declaration and keep its call site.
 * @param renaming whether the preview may rename this row's placeholder, and when it may not, why —
 *   see [Renaming]. Stated by the engine rather than worked out by the dialog, because every one of
 *   the four answers turns on something only the engine has: the ledger it ran against, the
 *   namespace it chose, and how the placeholder was allocated.
 */
class MappedName(
    val original: String,
    val placeholder: String?,
    val kind: MappedKind,
    val key: String? = null,
    val renaming: Renaming = Renaming.NONE,
)

/**
 * **Whether this row's placeholder can be renamed, and when it cannot, what to say instead.**
 *
 * The engine answers it because the engine is where every answer lives, and because the rule it
 * enforces has to hold whatever a dialog offers: [AnonymizationSettings.renamedStems] ignores a
 * stem for anything that is not [OFFERED], so a dialog that offered a row it should not have would
 * change no character of the output. This exists so that the dialog need not guess — not so that it
 * can be trusted.
 *
 * **The default is [NONE]**, which is the direction to err in: a row nobody vouched for is a row
 * with no editor on it, and the cost of that being wrong is a rename a user could not make.
 *
 * @param message **why this row cannot be renamed**, in the user's words, or `null` where there is
 *   nothing to say. Carried here rather than written where it is shown, for the reason
 *   [StemRejection] carries its own: the reason is the engine's, and a sentence spelled out on the
 *   reporting side is one that goes stale the day the rule moves. [OFFERED] has none — the engine
 *   has no objection to state, and *how* to rename is the dialog's own affordance to describe.
 */
enum class Renaming(val message: String? = null) {

    /**
     * **Minted in this invocation, against this row's own key** — so the stem is this invocation's
     * to choose, and choosing it contradicts nothing that has already been sent.
     */
    OFFERED,

    /**
     * **Already in the ledger when this invocation started**, so the placeholder is a name a past
     * snippet used and past replies are written in. The ledger is append-only — *a record of what
     * was actually sent, not an index of the current codebase* — and rewriting an entry would make
     * an old reply decode wrongly or not at all.
     */
    ESTABLISHED("Named in an earlier snippet; renaming it would contradict that snippet and its replies."),

    /**
     * **Derived from a field's placeholder**, which is what a JavaBeans accessor's is: `field1`
     * under `get` renders `getField1`, so the two names agree the way the source's two names did.
     * There is nowhere for a stem typed here to land — rename the field and this follows it.
     *
     * The stated cost: a Lombok accessor whose field has no declaration in source has no row to
     * rename, so it keeps the default stem. Under-naming, never a wrong name.
     */
    DERIVED("This name follows its field's placeholder. Rename the field and it follows."),

    /**
     * **Nothing here to rename.** A preserved row has no placeholder at all; an `Unknown` row's
     * namespace is load-bearing and may not move — `Unknown1` tells the model the IDE could not
     * resolve this; a literal row has no key to be named by, and literal text is the most directly
     * sensitive content the product handles.
     */
    NONE,
}

/**
 * What a mapped name is, as the preview and the export say it.
 *
 * A separate enum from [SymbolRole] rather than a label on it, because the two answer different
 * questions: a role decides a placeholder's namespace, and a kind is what a human reads in a table.
 * They part company at exactly the two entries that have no role — a redacted [LITERAL] is not a
 * symbol at all, and [UNKNOWN] is what an unresolved symbol becomes **whatever its role**, because
 * the role of a name nothing resolved is a guess and the placeholder does not carry it either.
 *
 * @param label the word the table and the export use, capitalised the way Java writes the thing
 */
enum class MappedKind(val label: String) {
    TYPE("Type"),
    METHOD("method"),
    FIELD("field"),
    PARAMETER("param"),
    LOCAL("local"),
    PACKAGE("package"),
    TYPE_PARAMETER("type parameter"),
    LABEL("label"),
    ANNOTATION("annotation"),
    ATTRIBUTE("attribute"),
    LITERAL("literal"),
    UNKNOWN("Unknown"),
}

/**
 * **What the strip removed, split by [CommentVerdict].**
 *
 * Reported so that removal is never silent. A comment stripped is a loss the anonymized output gives
 * no sign of: the text that comes back is clean, compiles, and reads as ordinary code, which is the
 * quietest failure the design has. The answer is disclosure at the point of use — the tool says what
 * it removed, and the human, who can still see the original, judges whether it mattered.
 *
 * Split rather than totalled, because the split is the part a user can act on: *`2 comments
 * stripped`* is a number, *`2 comments stripped, 1 of them commented-out code`* is a reason to open
 * the preview and tick the box.
 *
 * Both are zero when this invocation kept its comments. This counts what was **removed**, not what
 * was there — a count of comments in the snippet would be a fact about the input, and the thing a
 * user needs to know is what is missing from the output.
 *
 * @param prose comments whose body does not parse as code
 * @param code comments whose body does
 */
class CommentCounts(val prose: Int, val code: Int) {

    /** How many comments the strip removed, which is what the balloon says. */
    val stripped: Int get() = prose + code
}

/**
 * One name the IDE could not resolve, and what this invocation did with it.
 *
 * Reported as a list rather than only as a count because the count alone would name a surface the
 * user cannot act on. It is the evidence an unresolved name produced, and it stays that whatever
 * this invocation did with the name: [key], [name] and [placeholder] say what the IDE could not
 * resolve and what was emitted for it. The preview's **Preserve** reads rows off
 * [AnonymizationResult.names] rather than off this list, because that list is now the whole table.
 *
 * @param key the symbol key — the same one [MappedName.key] carries for this name, which is the key
 *   a preserve override is expressed in
 * @param name the name as it is written in the snippet
 * @param placeholder what it renders as, or `null` when this invocation preserved it and its real
 *   name was emitted. Null rather than the name itself: a preserved item has no placeholder, and
 *   saying it stands for itself would put a row in the mapping table that maps nothing.
 */
class UnknownName(
    val key: String,
    val name: String,
    val placeholder: String?,
)

/**
 * The balloon's three numbers, counted in **distinct names** rather than occurrences — "14 names
 * replaced" is a claim about names.
 *
 * They **partition** the snippet's named symbols: every distinct name is counted once, so the three
 * add up to what is in the snippet and nothing is counted twice.
 *
 * They are counted by **outcome** — replaced, or surviving verbatim — rather than by the origin the
 * plan reported, and that is a correction the name-constrained rules forced rather than a
 * preference. A project method that keeps its real name because it implements `Runnable` is
 * project-owned *evidence* and a preserved *name*, and [replaced] is a claim about what is on the
 * clipboard. [unknown] is the exception and stays evidence-shaped, because it reports what the IDE
 * could not resolve rather than what became of it.
 *
 * All three are mechanism, and stay that way: no "safe to paste", no "sanitized", no adjective. A
 * category claim is one the tool is not in a position to make, and it is the claim a user would act
 * on.
 *
 * @param replaced distinct names that became placeholders
 * @param unknown distinct names the IDE could not resolve. **An information-level number, never a
 *   warning.** Under fail-closed an `Unknown` *was* anonymized, so it is a quality risk and never a
 *   privacy one — styling it as an alarm would train the user to read our alarm as "this might have
 *   leaked", which is precisely the inversion to avoid. It counts what did not resolve, so it is
 *   unmoved by a per-invocation preserve: the override changes what was emitted, not what the IDE
 *   knew.
 * @param preserved distinct names that survive verbatim — the JDK and third-party libraries, the
 *   project's own names that Java forbids from being anything else, and **the names this invocation
 *   was told by hand to emit as written**. Outcome-based, so a ticked resolved name moves here out
 *   of [replaced]: this number is a claim about what is on the clipboard, and a name emitted as
 *   written was not replaced whatever the reason.
 */
class NameCounts(val replaced: Int, val unknown: Int, val preserved: Int)
