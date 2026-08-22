package com.snippetveil.core

/**
 * The choices one invocation runs under.
 *
 * One rule governs everything that will be added here, and it is worth carrying next to the type
 * rather than in a document: **persistent settings may only ever increase anonymization. Any
 * reduction is per-invocation and visible in the preview.** A reduction that can be set once and
 * forgotten silently leaks on every paste after it.
 *
 * @param preservedUnknowns the symbol keys of unresolved names to emit verbatim, from
 *   [AnonymizationResult.unknowns]. **Per-invocation only, never persisted**, by the rule above —
 *   this is the one deliberate fail-open in the product, and it exists to buy off the case where a
 *   typo'd JDK call is hidden behind a placeholder and the snippet stops being answerable.
 *
 *   **A key naming anything but an unresolved symbol is ignored**, and that is a rule rather than
 *   defensiveness: the override was given to unresolved names specifically and it must not creep.
 *   One that reached resolved symbols would be the free-text preserve list this design already
 *   rejected, built out of keys instead of text, and it would put a reduction on the spine rule.
 *   Enforcing it here rather than in the dialog puts it where creep cannot happen by adding a
 *   checkbox.
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
 * @param internalLibraries which library packages are the company's own code arriving as a jar.
 *   **The one setting the product persists**, and see [InternalLibraries] for why it survives the
 *   rule above rather than being an exception to it.
 */
class AnonymizationSettings(
    val preservedUnknowns: Set<String> = emptySet(),
    val keepComments: Boolean = false,
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
 * @param placeholders symbol key -> placeholder, for symbols already named. **Qualified keys only**
 *   — see [LedgerDelta] for why, and for what happens to everything else.
 * @param nextNumber the next number the counter will hand out
 */
class LedgerSnapshot(
    val placeholders: Map<String, String>,
    val nextNumber: Int,
) {
    companion object {
        /** No symbol has been named yet, and numbering starts at 1. */
        val EMPTY: LedgerSnapshot = LedgerSnapshot(emptyMap(), nextNumber = 1)
    }
}

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
 * @param placeholders the qualified keys named during this invocation, and what they were named
 * @param nextNumber where the counter stands afterwards. Higher than
 *   `snapshot.nextNumber + placeholders.size` whenever a number was burnt — by an unpersisted
 *   symbol, by a redacted literal, or by a candidate that collided with a name surviving into the
 *   output.
 */
class LedgerDelta(
    val placeholders: Map<String, String>,
    val nextNumber: Int,
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
 */
operator fun LedgerSnapshot.plus(delta: LedgerDelta): LedgerSnapshot =
    LedgerSnapshot(placeholders + delta.placeholders, delta.nextNumber)

/**
 * What one invocation produced. Nothing here has been committed anywhere; the caller decides.
 *
 * @param text the anonymized snippet, ready for the clipboard
 * @param mapping placeholder -> the real name it stands for. **Injective**, which is the whole
 *   point: a reverse mapping is well-defined only if no two symbols render to one placeholder, and
 *   the AI's reply carries no scope context to disambiguate with if they did.
 * @param counts the distinct names in the snippet, partitioned by what became of them
 * @param comments what the strip removed, split by parse verdict
 * @param unknowns every name that failed to resolve, in document order of first occurrence
 * @param delta what to commit, if the caller gets as far as committing
 */
class AnonymizationResult(
    val text: String,
    val mapping: Map<String, String>,
    val counts: NameCounts,
    val comments: CommentCounts,
    val unknowns: List<UnknownName>,
    val delta: LedgerDelta,
)

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
 * user cannot act on. The preview dialog's per-item **Preserve** is built on exactly these three
 * fields: [name] to show, [placeholder] to show it against, and [key] to hand back through
 * [AnonymizationSettings.preservedUnknowns].
 *
 * @param key the symbol key, which is what a preserve override is expressed in
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
 * @param preserved distinct names that survive verbatim — the JDK and third-party libraries, and
 *   the project's own names that Java forbids from being anything else
 */
class NameCounts(val replaced: Int, val unknown: Int, val preserved: Int)
