# Threat model

SnippetVeil's listing says what it replaces and what it leaves alone. That is what fits on the tin.
This document is the rest: who the tool is for, who it is not for, what defeats it, and which of its
claims are checked by a machine rather than asserted by its author.

It is a document in a repository rather than listing copy, so it is free to be specific — the
examples below are what the tin cannot carry.

## The one claim

**Every name your project owns is replaced with a placeholder before the snippet leaves the
editor.** Structure, control flow and the libraries you call go as-is.

That is a claim about *names*. It is not a claim about secrecy, about compliance, or about what
somebody clever can work out from the shape of what is left.

## The adversary, ranked

### Primary: retention, not a person

**The threat SnippetVeil is built against is that the snippet outlives the conversation.**

You paste a class into a chat window to ask why a null gets through. The answer takes four minutes.
The paste does not stop existing when you close the tab: it sits in a conversation history, in a
provider's logs, in a training corpus if the terms allow it, in a vendor's breach two years from
now, in the discovery set of a lawsuit nobody has filed yet. None of that involves a person reading
your code and deciding to do something with it. It is a copy of your employer's vocabulary coming to
rest somewhere neither of you chose, for longer than either of you thought about.

Against that adversary the mechanism is exactly right, and it is why the mechanism is name
replacement rather than anything cleverer. `CustomerCreditRiskAssessment` in a corpus is a fact
about a business. `Type7` in a corpus is a fact about nothing.

### Secondary: the person reading the paste

Someone reads the snippet in the chat and works out where it came from.

This is a real reader with a real motive, and SnippetVeil helps against them by much less. It
removes the vocabulary, which is usually the whole tell. It does not remove the shape, and shape can
be a tell on its own: an unusual state machine, a distinctive workaround, a comment style, the exact
sequence of six library calls that only one codebase makes.

**Output that is obviously anonymized is explicitly not a failure.** `Type1.method2(field3)` reads
as anonymized code, and it is meant to. Making the output look like ordinary code — plausible fake
names, a realistic-looking domain — would be a different product with a worse property: a reader who
cannot tell whether they are looking at real names has no way to know what they are holding, and
neither do you. The placeholders are numbered so that they announce themselves.

### Explicitly refused: compliance and auditability

**SnippetVeil is not a compliance tool, and the refusal is a design decision rather than a missing
feature.**

There is no audit ledger. There is no policy enforcement — nothing here can be configured by an
administrator to prevent an employee pasting something. There is no attestation surface: no report
you can hand to a reviewer saying *this snippet was cleared*, because the tool is not in a position
to clear anything.

**The stored mapping is a reversal key, never an audit trail.** It exists so that an AI's reply can
be turned back into your real names. It is not a log of what you sent, it is not evidence of
anything, and it is not designed to be read by anyone but the plugin — a use that treated it as a
record of activity would be relying on a file that *Reset Mappings…* is designed to destroy.

If your organisation needs to demonstrate control over what leaves developer machines, it needs a
control, and this is not one.

## Out of the threat model: a compromised local machine

**If an attacker is running code on your machine, SnippetVeil offers you nothing, and it does not
pretend otherwise.**

This is the assumption the plaintext mapping rests on. The mapping file holds every placeholder
beside the real name it stands for; it is not encrypted, and it sits in the IDE's configuration
directory in the clear. An attacker who can read it can read the `.java` files it was derived from,
which are also sitting in plaintext on the same disk, in greater quantity and with the comments
still attached.

What encryption would buy against that adversary is nothing. What it would cost is auditability, on
a product whose entire pitch is *audit me* — a state file you can open in a text editor is worth more
here than one you have to take on trust.

The risks that are *not* out of scope are accidental commit and cloud sync, and both are location
problems rather than encryption problems: the mapping is kept outside the project tree so it cannot
be committed, and every piece of state SnippetVeil holds is `RoamingType.DISABLED` so that Settings
Sync does not copy it to a server. [PRIVACY.md](PRIVACY.md) lists the three files and where each one
lives.

## The no-network claim, and the limit of what checks it

SnippetVeil makes no network calls. Two machine checks stand behind that, and this section states
plainly what they do and do not establish.

- **`ShippedCodeArchitectureTest`** reads the classes compiled from this repository's sources and
  fails on a networking package, a process-execution type, or `Runtime.exec`.
- **`scanDistributionForBannedReferences`** reads every class in the built distribution — including
  anything bundled or generated, whoever compiled it — straight out of the zip that gets uploaded.

Both run in `./gradlew check`, on any machine, with no CI account and no permission from anyone.
CONTRIBUTING.md carries the details of each.

**What they establish is that the code says what it appears to say. Neither defeats a determined
author.** A reflective call whose target is assembled at run time —

```java
var c = Class.forName(new String(new byte[] {106, 97, 118, 97, 46, 110, 101, 116, 46, 85, 82, 76}));
```

— is a `java.net.URL` that appears in no constant pool as a class reference and in no import list.
Both checks read text and structure; neither of them runs the code. A person who wanted to put a
network call in this plugin and had commit access could do it, and these checks would stay green.

So the checks are not the guarantee, and it would be dishonest to present them as one. **The real
guarantee is that this repository has been public since its first commit**, with every change on
the record, and the two checks are continuously-verified evidence sitting on top of that — they
raise the cost of the obvious mistake, they catch the accidental dependency, and they mean the
claim is re-tested on every pull request rather than asserted once in a README. What holds the
claim up is that you can read the history, and that a hidden call would have to survive being
publicly visible from the day it was written.

## What survives, and what that costs

### Preserved by design

Frameworks, libraries, the identity provider, the persistence layer, control flow, and your
algorithm. These are what make a snippet answerable, and removing them would leave a question nobody
can help with.

The consequence is worth stating directly: **if the thing you are protecting is the algorithm
itself, SnippetVeil does nothing for you.** A distinctive pricing rule survives anonymization
intact, with only its vocabulary changed.

### The stack trace

**A stack trace pasted alongside a snippet is not anonymized at all.**

This is called out on its own rather than folded into general advice, because it is the single
likeliest way a careful user leaks. The action reads the Java code you selected in the editor. A
stack trace is not Java code and is usually not in the editor — it is in the run console, or in a log
file, or in a ticket — and it is dense with exactly what the snippet no longer contains:

```
at com.acme.billing.CustomerCreditRiskAssessment.evaluate(CustomerCreditRiskAssessment.java:214)
at com.acme.billing.internal.MerchantLedgerRepository.load(MerchantLedgerRepository.java:88)
```

Package names, class names, method names, file names. Pasting the anonymized snippet and the raw
trace together undoes the anonymization for the reader and for the corpus in one step.

### Residual gaps

Every one of these is a real way a secret reaches the clipboard. They are here rather than in a
release note because a trust document that lists only its strengths is not a trust document.

- **Numbers and characters are preserved.** `LiteralKind.NUMBER` and `LiteralKind.CHARACTER` are
  emitted verbatim, deliberately: a retry count of `3` and a port of `443` are shape rather than
  domain, and destroying them makes snippets unanswerable for no gain. The cost is that **a secret
  encoded as numbers survives** — an account number, a key material array, an identifier that
  happens to be an integer.
- **Comments leak wholesale when you keep them.** They are stripped by default because prose is
  where a snippet's domain vocabulary actually lives. Keeping them for one snippet, from the
  preview, hands over their text unmodified — renaming symbols does nothing to a sentence.
- **The per-item preserve override can hand a secret back.** The preview lets you release a name and
  send it exactly as written. Unresolved names can be released directly; every other name in the
  table becomes releasable once you unlock the column, which asks you to confirm first and says what
  a preserved name is. That is the one reduction the design allows, and it exists because a name is
  sometimes the one thing the question is about — a variable called `filter`, or a call the IDE could
  not resolve. It is a direct route from *SnippetVeil concealed this* to *I chose to send it*.

  What holds it in place is structural rather than a warning: **the set is per-invocation and is
  stored nowhere.** The unlock is locked again on every open, there is no *don't warn me again*, and
  every box is unticked on every open — so the failure mode this document worries about most, a
  reduction set once and forgotten, cannot be reached from here. The warning is the friction, and it
  sits at the moment of reduction rather than on every invocation, which is where a warning stops
  being read. Literals are not preservable at all.
- **A renamed placeholder discloses whatever you typed into it.** The preview lets you give a
  placeholder a stem of your own — `Type1` becomes `FilterType1` — so the model has the word your
  question is about. The real name never leaves your machine, and the symbol is still replaced, so
  this is not the preserve override wearing a different hat. What it does send is **the alias you
  invented**, and an alias chosen to be useful to the model is usually chosen out of the same
  vocabulary as the name it stands for. That is a chosen disclosure: small, deliberate, and yours.

  **The number is what stops it becoming something else.** A stem may not end in a digit, may not
  spell one of SnippetVeil's own namespaces, and the number is never removable, so a renamed
  placeholder still reads as a placeholder and never claims to be an `Unknown` or a literal — the
  announce-itself property this document refuses to trade away survives the feature. Free renaming
  was considered and rejected for exactly that reason: `Filter` would be indistinguishable from a
  name, which is a different product with a worse property. Renaming is per-invocation input, and
  what makes a rename come back next week is the mapping row that was already being written.

  **The word itself is recorded, and that is the one thing the feature adds to what is at rest.**
  The mapping keeps a set of the stems this project has minted under — words, filed under no key and
  standing for no symbol — because `De-anonymize Clipboard and Paste` refuses to write a reply it
  could not restore in full, and it cannot tell `theFilter7` from ordinary prose unless it was told
  the word. It is strictly less than the rows beside it, which hold real names in plaintext against
  their placeholders, and it is a word you chose and already sent to the model. `Reset Mappings…`
  clears it with the rest, and the cost of that is stated where the button is.
- **A selection inside a non-project file is preserved.** Anonymization replaces names your project
  owns. Select code in a decompiled library, a dependency's sources, or a file outside the analysed
  project, and there is nothing project-owned in it to replace — the output is the input.
- **Stable pseudonyms make snippets linkable.** `CustomerService` is `Type1` today, tomorrow and
  after a restart, which is what stops a second snippet contradicting the first. It also means the
  provider receiving your pastes can tell that a snippet from March and a snippet from September are
  about the same class, and can accumulate structure across a conversation history you never thought
  of as one document. Stability is a usability property with a privacy price, and it is priced in
  this direction on purpose.
- **The output is edition-dependent.** Anonymization is fail-closed: a name the IDE cannot resolve is
  replaced rather than emitted. So the *more* the IDE can resolve, the more names it can positively
  identify as belonging to a library and preserve. IDEA Community resolves less than Ultimate does,
  which means **Community anonymizes more, never less** — the same file can come out with more
  placeholders on one machine than another. The direction is safe; the inconsistency is real, and it
  means a snippet checked on one machine is not evidence about another.

### And what SnippetVeil never sees

**The prompt you type around the snippet.** SnippetVeil anonymizes the code. The sentence above it —
*"why does the merchant ledger reconciliation drop the last payment for Acme Bank"* — is yours, and
it is where the domain vocabulary tends to reappear.

## "Anonymize", precisely

SnippetVeil uses "anonymize" in its ordinary sense. The mapping is reversible by design, which makes
this pseudonymization in the regulatory sense. SnippetVeil makes no data-protection compliance
claim.

## Reporting a problem

A snippet that came out with a project-owned name still in it is a bug, and the most valuable kind.
Open an issue at `https://github.com/snippetveil/snippetveil/issues`.

**Do not paste the leaked code.** Describe the shape — what kind of symbol it was, where it was
declared, what the IDE knew about it — and, if you can, reproduce it with invented names. Every
fixture in this repository is synthetic for exactly this reason: a leak reported in a public issue
tracker is a leak in a public repository, permanently.
