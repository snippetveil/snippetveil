# How SnippetVeil works

The mechanism, for anyone who wants to know what happens to a snippet before answering that
question for somebody else. [THREAT-MODEL.md](../THREAT-MODEL.md) covers what defeats it;
this covers what it does.

## The whole model in one paragraph

Every identifier in the selection is classified as **yours**, **not yours**, or **unresolvable**.
Yours are replaced with a numbered placeholder drawn from one project-wide counter that never reuses
a number. The mapping is remembered, so a reply that mentions a placeholder can be turned back into
the name it stood for — including a reply about a snippet you sent last week.

## The three classifications

The balloon and the preview both report the same counts:

```
14 names replaced · 3 unknown · 22 preserved
```

| | What it means |
|---|---|
| **replaced** | A symbol your project owns. It becomes a placeholder. |
| **preserved** | Left **exactly as written**, because it is not yours — the JDK, your libraries, your frameworks. |
| **unknown** | The reference did not resolve. It **fails closed**: treated as yours, never passed through. |

**"Preserved" means *survived unchanged*, not *protected*.** It is the count of things that went to
the other end verbatim. That is deliberate and it is most of the product's usefulness: an assistant
cannot help with code whose every type is anonymous, so the shape of the code, its control flow, and
every library call go as-is.

An unresolved reference is the interesting case. SnippetVeil does not guess from a name's shape what
it means, so a reference it cannot resolve is one it knows nothing about — and the one safe reading
of *unknown* is *possibly yours*. It gets an `Unknown3` placeholder rather than a pass.

## Placeholders, and why they are stable

A placeholder is its kind plus a number: `Type1`, `method2`, `field3`, `param4`, `local5`,
`package6`, `str7`, `Unknown8`.

`CustomerService` is `Type1` today, tomorrow, and after the IDE has been restarted twice. The
argument for that is fidelity rather than privacy: the normal use is a conversation, and numbering
that restarted every invocation would make the second paste contradict the first.

### The counter, which is what makes everything else work

**One counter, shared across every kind of symbol, and a number is burnt when it is handed out even
if the symbol is never written down.** See `LedgerDelta` in
`core/src/main/kotlin/com/snippetveil/core/AnonymizationSettings.kt`.

The property this buys:

> no two distinct symbols in the project's whole history ever render to the same placeholder

Without burning, `field44` could be an anonymous class member today and a real field next month, and
an old reply mentioning `field44` would decode to the wrong name. Burning costs one integer and
makes that impossible.

The visible cost is that numbers only ever climb, so a mature project emits `Type247` rather than
`Type3`. Placeholder form does not affect answer quality, so this is cosmetic.

## Reversing a reply

**De-anonymize Clipboard** matches only the exact placeholder strings SnippetVeil minted, on word
boundaries, case-sensitively. Everything else in the reply is left byte-for-byte alone.

This is not the shape-guessing the product refuses elsewhere. The namespace is one this engine
created and wrote down, so an exact hit against the recorded table is total rather than heuristic.

**Under-recovery is the designed behaviour.** A word the tables do not know stays exactly as the
model wrote it. If an assistant invents its own variable called `local1`, it stays `local1` — a
visible, inspectable gap. Fuzzy matching would recover more of the reply while deciding without
evidence that a token refers to a mapped symbol, and would manufacture text the model never meant.
**A gap a reader can see beats a name a reader has no way to doubt.**

### Pasting it back, and the one place under-recovery is refused instead

**De-anonymize Clipboard and Paste** runs the same reversal and inserts the result at the caret,
replacing the selection, as a single undo step.

It differs from the reversal above in exactly one behaviour, and the difference follows from the
destination. **A reply it could not restore in full is not pasted at all** — nothing is written, and
the balloon says how many placeholders did not come back, with the same `Show details` breakdown.

A visible gap on the clipboard is inspectable, because you read what you paste. A visible gap
*already in a file* is a placeholder that compiles as an identifier and reads as a name somebody
chose, and the case that hides best is the one that mostly worked: twelve of fifteen restored, three
left over, everything around them correct. Refusing is cheap to recover from — the clipboard still
holds the reply, so De-anonymize Clipboard and a paste are still there for anyone who has read the
details and wants the text anyway. Reading a placeholder as a real name has no undo.

**The clipboard is never rewritten by this action.** The insert already delivered the text, and
overwriting the clipboard would destroy the anonymized reply you may still want to quote into the
chat.

## Two stores, and why several snippets decode without you doing anything

This is the part most often misunderstood, because **De-anonymize Clipboard does not use Export
Mapping.** They solve different problems.

A reversal looks up two stores, in order:

| | What it holds | Bound |
|---|---|---|
| **The sidecar** | Recent invocations **whole** — including locals, parameters, type parameters, anonymous-class members, and the text of redacted string literals | **50 invocations or 30 days**, whichever bites first |
| **The durable mapping** | **Only fully-qualified symbols** — types, packages, fields, methods | None. Append-only, kept indefinitely |

So pasting a reply that refers to five different snippets from a two-week conversation simply works.
Nothing to export, nothing to load, no step to remember.

**The two stores cannot disagree.** The sidecar is asked first because it knows more, not because it
wins a conflict — the burning counter means a placeholder stands for one symbol in the project's
whole history, so both stores can only ever say the same thing about the same word.

### Why the split exists

A local variable's identity is *where it is written*, which stays true only for as long as the file
is not edited. A fully-qualified name is serializable. So only qualified symbols persist.

That cut is principled rather than a concession: a qualified symbol is exactly the kind a
conversation refers back to across snippets — *"the `Type1` you showed me"* — while a local's number
surviving until next week buys nothing. A string literal has no qualified key at all, so `str`
placeholders are never written to the durable mapping either, which is right on its own merits:
literal text is the most directly sensitive content the product handles.

### The durable mapping is a record, not an index

Rename a class and its qualified key stops matching, so the renamed symbol becomes a *new* key with
the next number while the old entry stays. That is correct rather than untidy: the mapping records
**what was actually sent**, so an old reply saying *"fix `Type1.method2()`"* decodes to the name that
was in the snippet at the time. Following the rename would decode it to a name that did not exist
then — which reads correct and is wrong.

## So what is Export Mapping for?

One invocation, deliberately. A dump of the whole stored mapping would be the complete plaintext
domain glossary of the codebase sitting in a downloads folder, outside the location chosen to keep
it out of version control and out of cloud sync.

It exists for exactly one problem: **the sidecar is bounded.** Locals, parameters and every string
literal live there and nowhere else. Once an invocation falls past 50 pastes or 30 days, that half
of its table is gone permanently and no later reversal recovers it.

**Export Mapping is an archive for one specific conversation you expect to return to months from
now. Day-to-day decoding across many snippets needs nothing.**

The file is CSV, `Placeholder, Original, Kind`, placeholder first because the lookup direction is
always *reply → source*, and in first-occurrence order so it reads top to bottom against the text it
reverses.

## The preview, and the Preserve column

**Anonymize with Preview…** is the only place you can anonymize *less*, and there are exactly two
reductions:

- **Keep comments and javadoc**, for this snippet only.
- **Preserve**, on an individual row.

**By default the Preserve column only has checkboxes on `Unknown` rows.** That is the override for a
name SnippetVeil could not resolve and you know to be harmless — a library it failed to see, say.
Every other row renders nothing at all in that column, rather than a disabled checkbox, which would
read as an offer you had done something wrong to lose.

**An empty Preserve column means every reference resolved.** That is the good case, not a broken
dialog.

**`Unlock Preserve for resolved names…`, under the table, extends it to every name.** Sometimes the
name is the question — a variable called `filter`, a method whose name is the whole reason the
snippet is confusing — and hiding it makes the snippet unanswerable. Clicking the link asks you to
confirm, and says what it is asking:

> Preserved names are sent exactly as written in your code. SnippetVeil will not conceal a name you
> tick. Only preserve names you would be comfortable typing into the chat yourself.

Confirm and every row with a symbol behind it gains a checkbox, all of them unticked. Literals never
gain one: a literal has no symbol to key an override to, and literal text is the most directly
sensitive content the product handles.

**The unlock is locked again the next time you open the preview**, every box is unticked again, and
there is no *don't warn me again*. A sticky unlock would be exactly the reduction you set once and
never think about again — which is the failure this whole design is built to prevent. The friction
sits at the moment you reduce, where it gets read, rather than as a banner on every invocation.

A preserved type keeps its own name and nothing more: `com.acme.billing.PaymentFilter` still comes
out as `com.pkg1.pkg2.PaymentFilter`, because preserving a name is not a decision about its package.

Neither reduction is persisted. Both live for one invocation, so nothing you tick here can quietly
apply to a snippet next week.

## Where things are stored

- **The durable mapping** — the IDE config directory, partitioned by project. Not `.idea/` (which
  gets committed), not the cache directory (which *Invalidate Caches* destroys), and **never
  roaming**, so Settings Sync does not copy it anywhere.
- **The sidecar** — the same posture, bounded as above.
- Both are plaintext, deliberately. They hold names already sitting in plaintext `.java` files on the
  same disk, and a compromised local machine is out of scope. The real risks are accidental commit
  and cloud sync, and both are *location* problems. Readable state is worth more to a product whose
  pitch is *audit me* than a lock on a door standing one directory from an open one.

## What it does not do

Worth knowing before relying on it:

- **Stack traces are not anonymized at all** — the richest source of real package and class names any
  snippet can carry.
- **String literal text is destroyed, not mapped** — a literal becomes `"str1"` unless it carries
  resolvable references, in which case it renames in lockstep with them.
- **Structure and algorithm survive intact.** If your proprietary value *is* the algorithm,
  SnippetVeil does nothing for you.
- **The prompt you type around the snippet** is yours to write. SnippetVeil anonymizes the code, not
  the sentence above it.
- **Java files only** for the two anonymizing actions. The two reversals work anywhere —
  De-anonymize Clipboard needs only a project, De-anonymize Clipboard and Paste a writable editor.
- **It is not a secret scanner.** It replaces names your project owns; it does not look for
  credentials.

## No network

SnippetVeil makes no network calls and starts no subprocesses. That is checked rather than asserted,
in three layers, and all of them run from a clone with `./gradlew check` — no CI account and no
permission from anyone. See [CONTRIBUTING.md](../CONTRIBUTING.md) for what each layer covers and
where each one is blind.
