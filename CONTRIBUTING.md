# Contributing to SnippetVeil

## Building

```
./gradlew build      # compile and run every check
./gradlew runIde     # a sandbox IDE with the plugin installed
./gradlew buildPlugin # the installable distribution, in plugin/build/distributions/

./gradlew check -PplatformProfile=latest   # the same checks against the newest stable IDE
```

JDK 17. Everything else the build needs, it downloads.

`platformProfile` picks which IntelliJ Platform the build compiles and tests against. It defaults to
`floor` — the version the plugin descriptor names in `sinceBuild` — so a plain `./gradlew build`
builds and tests exactly what ships. gradle.properties carries both profiles and the reasoning.

## The trust checks

SnippetVeil's central claim is that **it makes no network calls**. A claim like that is worth
nothing asserted, so it is checked — by three machine checks, in layers, each covering what the
one before it cannot see.

The checks ban more than `java.net`, because a rule that bans only the obvious spelling of a thing
bans nothing. **A subprocess is a network call the networking rules cannot see** —
`Runtime.getRuntime().exec("curl …")` reaches the network without a single `java.net` reference
appearing in our bytecode — and so is a reflective lookup. Both escape hatches are banned for that
reason, alongside the thing they route around.

| Check | Where | Covers |
|---|---|---|
| `ShippedCodeArchitectureTest` | `plugin/src/test/kotlin/com/snippetveil/trust/`, runs in `check` | compiled classes from this repository's sources: no networking package, no process execution, no reflective escape hatch, and no `com.intellij.*` under `com.snippetveil.core` |
| `scanDistributionForBannedReferences` | `plugin/build.gradle.kts`, finalizes `buildPlugin`, and runs in `check` | every class in the built distribution's `lib/`, **including bundled or generated code** — read straight out of the zip that gets uploaded |
| `assertNothingThirdPartyIsShipped` | `plugin/build.gradle.kts`, runs in `check` | the shipped runtime classpath: nothing but `:core` may be in it |

**All three attach to Gradle, none to a CI workflow.** That is deliberate: a check that exists only
in YAML cannot be run by the person reading the claim. `./gradlew check` goes red on a `java.net`
import on any machine, with no CI account and no permission from anyone.

The distribution scan is wired three ways rather than one, because `finalizedBy` orders a task
without requiring it: `buildPlugin` finalizes the scan, `check` depends on it so it runs before a
push, and `publishPlugin` depends on it so an upload cannot start while it is still pending.

**`java.lang.Runtime` is legal; `Runtime.exec` is not.** The process ban names
`ProcessBuilder`, `Process` and `ProcessHandle` as types, but `Runtime` is banned one method at a
time, because `Runtime.getRuntime().availableProcessors()` is an ordinary thing to want and a rule
that banned the class outright would be the kind of noise that teaches people to suppress a check.
Both layers pay for that distinction: the architecture test matches the *call* rather than the type,
and the distribution scan reads `Methodref` constants and resolves the owner/name pair, alongside
the flat walk over the pool's text rather than instead of it — text is what covers the descriptors
and signatures no `Methodref` reaches.

**That structured walk buys no coverage, and the reason it is there anyway is worth stating plainly,
because the obvious reason is wrong.** Every `Runtime.exec` overload returns a `Process`, so the
descriptor `(Ljava/lang/String;)Ljava/lang/Process;` sits in the constant pool as text — and the flat
walk, which bans `java.lang.Process` as a type, already matches it. A class whose only process use is
a `Runtime.exec` call with the result discarded still fails the scan with the `Methodref` walk turned
off. Banning the types closes the `Runtime.exec` gap on its own.

Two things that are *not* coverage justify keeping it:

- **It names the violation.** Without it the report says `references java.lang.Process`, which is
  true and unhelpful; with it, `references java.lang.Runtime.exec`. These reports are read by people
  deciding whether to believe the claim, and "a subprocess was started" is the fact they want.
- **It decouples two rules that only look like one.** Otherwise every bit of `Runtime.exec` coverage
  rides on `java.lang.Process` staying banned *as a type* — a coupling nothing states and a later
  narrowing would silently break.

The two layers now ban process execution identically over different inputs, which is also easier to
reason about than an asymmetry. (They are not identical in general: reflective escape hatches are
still the architecture test's alone.)

Two habits keep them honest, and both are worth preserving if you change them:

- **Each check asserts its own coverage.** A rule that matches nothing passes; so the architecture
  test asserts that the shipped classes were imported and that test classes were not, and the
  distribution scan fails if `lib/` yielded nothing to scan, or contains a file it cannot read, or
  hides a jar inside a jar.
- **Both layers prove they can fail before they report that nothing failed.** A red path that is
  never exercised decays into a check that always passes. The distribution scan scans `java.net.URL`,
  `java.lang.ProcessBuilder`, `java.lang.Object`, `java.lang.Runtime` and
  `java.util.concurrent.ForkJoinPool` out of the running JDK and requires a hit on the first two, a
  `Runtime.exec` hit on the fourth, and nothing on the rest. `ForkJoinPool` is there because it is
  the JDK's own `Runtime.getRuntime().availableProcessors()`, in a class that names `java.lang.Runtime`
  as a type — the exact thing neither layer may fail on. The architecture test does the same against
  `ProcessAccessFixtures`, two test-scope classes written to be flagged and not flagged.

There is no suppression mechanism and no exception list, in any of the three. That is the feature.

### One more claim, checked the same way

**Persistent settings may only ever increase anonymization; any reduction is per-invocation and
visible in the preview.** That rule exists because the primary threat is retention — a snippet
outliving the conversation in a corpus, a log, a breach or a subpoena — and a reduction that can be
ticked once and forgotten is the severe failure mode: one tick set a year ago silently leaks the
domain on every paste since.

`CommentRetentionIsNeverPersistedTest`, in the same package and reading the same shipped classes,
holds the first instance of it: keeping comments is a reduction, so nothing that ships may persist
it. It follows both habits above. It asserts its own coverage — the flag has to be found, on exactly
one class, and that class is the per-invocation settings object — and it proves it can fail, over
fixtures that persist a reduction and an *increase*, because a check that banned persistence outright
would be the kind of noise that teaches people to suppress a check.

The rule is not vacuous: **SnippetVeil persists exactly one setting**, the internal-library prefix
list, and it is an increase. Before it existed every library symbol was preserved, and nothing that
can be written into the list takes the output back past that baseline — the removals only give back
what the additions and the root-package heuristic claimed.

### And one absolute claim: **no `@State` in SnippetVeil is roamable**

Settings sync copies a roamable `@State` to JetBrains' servers, and the one setting this product
persists **literally is the employer's group id** — the single most identifying string the plugin
handles. So every `@Storage` declares `RoamingType.DISABLED`, and that is a rule about all of them
rather than a judgement made per file: an absolute rule is checkable in one pass and readable in one
grep, where *this one is fine to sync* has to be got right again every time.

`NoPersistentStateIsRoamableTest` checks it, over the same shipped classes, following the same two
habits. It asserts its own coverage — the shipped state holders are named, so a rule matching nothing
goes red rather than green — and it proves it can fail, against a fixture whose `@Storage` simply
says nothing about roaming, which is the shape the mistake actually takes: nobody writes
`RoamingType.DEFAULT` out in full. The rule fails closed on anything it cannot read as `DISABLED`.

### Where the placeholder mapping lives

Beside the one *setting* above, SnippetVeil persists one other thing, and it is the larger one:
**the placeholder mapping**, which is what makes `CustomerService` come out as `Type1` today,
tomorrow and after a restart. It is not a setting — nothing in it is a choice a user makes — and it
is the densest collection of employer vocabulary the product holds, so where the file sits is a
decision worth reading rather than a default.

Four properties, and **no project-level `@Storage` satisfies them all**:

1. **Outside the project tree, never `.idea/`** — `.idea/` is routinely committed, and a mapping
   there publishes the domain vocabulary into git history and every fork.
2. **Never roaming** — settings sync would copy it to JetBrains' servers.
3. **Not the cache directory** — *Invalidate Caches* is routine IDE hygiene aimed at unrelated
   indexing problems, and letting it silently destroy every outstanding reverse mapping is a trap.
4. **Project-scoped** — one project's vocabulary must never be handed to another project's paste.

Every project-level storage resolves inside `.idea/` — `$PROJECT_FILE$`, `$PROJECT_CONFIG_DIR$`,
`$WORKSPACE_FILE$` and `$PRODUCT_WORKSPACE_FILE$` all do — with one exception, `$CACHE_FILE$`, which
resolves to `<system>/projects/<hash>/cache-state.xml`: outside the tree, and the platform's own
cache slot. So (1) and (3) cannot both be had from a project-level component.

**The resolution: the component is application-level and the data is project-partitioned.** The file
lands in the IDE config directory, which is neither the project tree nor the cache, and every entry
is filed under the project it belongs to. What that gives up is the *component* being project-level.
What the alternative gives up is a reverse mapping that a routine Invalidate Caches destroys — and
that is the failure a user actually experiences. The reason project-level was wanted in the first
place, *application-level settings roam*, is closed directly and checkably by `RoamingType.DISABLED`,
which is an absolute rule here and has a test of its own. Roaming off also keeps the file out of
**Export Settings**, which collects roamable components only.

It is **plaintext**, deliberately. The file holds names already sitting in plaintext `.java` files on
the same disk, and a compromised local machine is out of the threat model; the genuine risks are
accidental commit and cloud sync, and both are *location* problems closed above. Encryption would
cost auditability, and greppable state is worth more to a product whose pitch is *audit me*.

**A row is a placeholder *and the name it stands for*, and that is a property of the file rather
than a detail of a bean.** The mapping is read in both directions: forward — key to placeholder — is
what makes `CustomerService` come out as `Type1` again next week, and backward — placeholder to name
— is what `De-anonymize Clipboard` needs, holding a word out of an AI's reply and no idea what key it
was filed under. Storing the name **puts nothing new at rest**: a qualified key already contains it,
`field:class:com.acme.Payment#merchantRef`, so the row states plainly what the key beside it already
said obliquely. What it buys is that the reversal reads a stated fact instead of parsing a key format
that `:plugin` owns the spelling of and `:core` is not allowed to know — the same rule that keeps
`com.intellij.*` out of `:core`, applied to a string. A row written before the field existed loads
with an empty name and still holds its placeholder and its number: under-recovery, never a wrong
name, and pinned by a test.

`PlaceholderLedgerTest` asserts each of these rather than describing them: the roaming type, the
component level, and the resolved path being neither under the project nor under the system
directory.

**Two costs, written down rather than left to be discovered.** Entries are filed under the project's
location hash, which is derived from its path — so moving or renaming the project directory reads as
a new project and the mapping starts from empty. A file in `.idea/` would have travelled with the
tree. Nothing decodes to the wrong name when that happens; what is lost is stability across the move.
And a mapping that survives an IDE restart also means a placeholder issued weeks ago is emitted even
when today's snippet preserves a library name spelled the same way, so that word can appear twice
standing for two things. Both are argued where they live, in `PlaceholderLedger` and in
`Anonymize.placeholderFor`, and both are pinned by a test.

### And where the sidecar lives

There is a second store beside the mapping, and it is deliberately a *second* one: **the sidecar**,
which holds the complete symbol table of the last ~50 invocations — locals, parameters, type
parameters, anonymous-class members and the text of every string literal that was replaced. Only
FQN-keyed symbols persist in the mapping, and an AI's reply talks about the rest constantly — *"the
`local43` here is null before you validate"* — so without a sidecar the round trip recovers types
and fields and drops exactly the detail a debugging conversation runs on.

It gets the treatment the mapping refuses, and each half of that follows from one fact:

- **It is in the cache tier.** `$CACHE_FILE$`, the platform's own per-project cache slot, declared
  deletable at any time. Losing it costs **under-recovery** — a placeholder past the horizon stays
  verbatim in the reply, a visible gap and **never a wrong name**, because no number is ever reused.
  *The sidecar is cache; the mapping is data*, and that asymmetry is what lets Invalidate Caches
  wipe one and not the other. It is also what lets this component be project-level where the
  mapping could not be: `$CACHE_FILE$` is the one project-level storage outside the project tree.
- **It is a separate component**, not a second field on the mapping's `@State`. The two differ on
  nature and decisively on **reset semantics**: *Reset Mappings* has to clear the mapping without
  destroying the org-prefix configuration, and a merged component makes that a hand-written special
  case instead of a structural fact.
- **It is bounded**: ~50 invocations, FIFO by invocation, with a 30-day age cap — applied on every
  read as well as on every write, because a count bound is no bound at all for someone who pastes
  twice a month, and this file is the one durable place a **literal's text** comes to rest.
- **It allocates nothing.** Numbers come from the mapping's single shared counter, so nothing here
  can collide with anything there — by construction, not by care.
- **`RoamingType.DISABLED`**, like everything else that ships, and the absolute rule's test names it
  among the three state holders it covers.

`PlaceholderSidecarTest` asserts the tier rather than describing it — it resolves `$CACHE_FILE$`
through the project's own storage manager and checks where that lands — and the window itself is a
value type in `:core`, held over generated sequences of invocations by `SidecarHorizonTest`.

### The one file that leaves both stores

`Export Mapping…` writes a placeholder table to a file the user picks, which is a mapping going
somewhere neither of the locations above argued for — very likely `~/Downloads`, outside the
storage location chosen so carefully to keep the mapping out of git and out of cloud sync. So what
it may write is bounded to the thing that made it necessary.

**It exports the current invocation and nothing else, and there is no surface where it could mean
anything else.** It is a button on the preview dialog and on the read-only re-open behind the copy
balloon, and there is deliberately **no Tools-menu entry**: a menu item has no invocation in front
of it, so the only thing it could mean is *dump the whole stored mapping* — the complete plaintext
domain glossary of the codebase, in one file, in `~/Downloads`. That is refused twice over. It is an
audit-trail feature in a different hat, and auditability was explicitly refused as an objective; and
the stored mapping is a **reversal key**, which is worth exporting for exactly one snippet.

**Why an export exists at all**, given that the mapping already survives restarts: the sidecar is
bounded, and locals, parameters, type parameters, anonymous-class members and **every string
literal** live there and nowhere else — a literal has no qualified key, so it is never written into
the durable mapping. Once an invocation falls past the horizon, that half of its table is gone
permanently and no later reversal recovers it. The file is the only way to keep a specific
conversation decodable past that point.

**And the related refusal, recorded so that it is not re-proposed.** *"Copy Anonymized + Mapping"*
does not exist and must not be built: it puts the deanonymization key on the same clipboard as the
anonymized code, and the overwhelmingly likely next keystroke is a paste into the AI chat — which
hands over everything the plugin just concealed and makes the product theatre. Also rejected: the
mapping as an appended comment block, and the mapping as a second clipboard-history entry, because
paste history is obscure and one wrong pick is still a full leak.

Both halves are checked rather than described. `ActionRegistrationTest` pins the four action ids
this plugin registers, so an export that acquired a menu entry goes red rather than shipping; and
`ExportMappingTest` asserts that an export leaves the clipboard byte-identical, and that the file it
wrote reverses the snippet the way `De-anonymize Clipboard` does.

### Known limits

This is deliberate, and it is stated here rather than left for a reader to discover, because a trust
artifact that overstates its own reach is worse than one that does less and says so.

- **Round-trip is not evidence of concealment.** `deanonymize(anonymize(x)) == x` is asserted in
  `RoundTripTest`, and it is a test of the *reversal contract* and of nothing else. It is a
  self-consistency check between two of our own functions, so **it passes when both are wrong in
  mirror-image ways**: a symbol the anonymiser misses sits in the output verbatim, and the reversal
  leaves it there too, because it is not a minted token. Green tick, leaked snippet. The test file
  says so at the top and demonstrates it in a case of its own, so that nobody reading it comes away
  with the stronger claim. What holds concealment is the fail-closed rules and their tests.

  The identity also **does not hold in the default configuration**, and that is asserted rather than
  footnoted: comments are stripped by default, information is destroyed, and no reversal restores
  destroyed information. So it holds with comment retention on, within one invocation — and a
  separate test pins the default path as lossy, so that nobody later "fixes" the round trip by making
  comment-stripping reversible.
- **Test code is not read.** The architecture rules cover the main output of `:core` and `:plugin` —
  what actually ships — so a `java.net` import in a test fails nothing. The alternative was worse: a
  test may legitimately call `Class.forName`, and `CoreIsIdeFreeTest` proves the module boundary
  precisely that way, so covering test code would mean carving out an exception for it on day one.
  An exception list is where a violation eventually hides.

## Continuous integration

**Thin CI over thick Gradle.** Every check CI runs is a Gradle task, and the workflows run Gradle
without knowing what is in it. That is the same rule the trust checks follow, for the same reason:
a check that exists only in YAML cannot be run by the person reading the claim.

| Workflow | Fires on | Runs |
|---|---|---|
| `build.yml` | push to `main`, and every pull request | `buildPlugin`, `check` at two platform versions, `verifyPlugin`, then a draft GitHub Release |
| `release.yml` | a GitHub Release being published or pre-released | `check`, `verifyPlugin`, `publishPlugin` — in that order |

The draft release is the manual-acceptance gate: green `main` cuts a draft, and a human publishing
it is what fires `release.yml`.

**`release.yml` re-runs the checks rather than trusting the tag.** The template this is adapted from
runs a bare `publishPlugin` there, on the reasoning that the tag came off a green `main`. A tag can
be pushed at anything, and *"we published a build that never passed CI"* is a sentence a plugin whose
pitch is *audit me* cannot survive. The cost is a few minutes on a handful of releases a year.

They are three separate steps rather than one `./gradlew check verifyPlugin publishPlugin`, and that
is not stylistic: Gradle orders a task graph by dependency, not by the order tasks were named on the
command line, and `publishPlugin` depends on neither of the two before it. A step boundary is a
barrier no scheduler gets to reorder.

### The merge gate

**Tiers 1-3 are merge gates on every pull request, not a nightly.** All of them run inside `check`,
so a contributor runs the whole gate locally with `./gradlew check`.

Not a nightly heavyweight tier, because on a solo project a nightly failure is a failure nobody is
watching — no rota, no triage, no page. You find it on Thursday having built three commits on
Tuesday's break. And the heavyweight tier is not a redundancy tier: it holds the only check for a
missing plan item and the "output still parses" invariant, which are the two classes this project
has actually been bitten by. A merge gate that skips exactly the layer catching the
historically-real bugs inverts the point.

**The test job runs at two platform versions: the `sinceBuild` floor and latest stable.**
`untilBuild` is unset, so the descriptor claims *241 and everything after it*; `verifyPlugin` checks
API compatibility, **not behaviour**, and PSI resolution behaviour across four years of platform
releases is exactly what drifts quietly. If the matrix ever proves painful, the honest fix is to
**raise the floor**, not to stop testing it.

The two legs do not name the same product: IntelliJ IDEA Community stopped being published after
2025.2, so the floor is `IC` and latest stable is the unified `IU`. Both are pinned in
gradle.properties rather than looked up, so that a run is reproducible and a bump is a reviewable
one-line diff — `platformLatestVersion` going stale is a maintenance chore nothing automates.

### Hardening

- **Both workflows declare `permissions:` explicitly and minimally.** A repository whose pitch is
  *audit me* has no implicit `write-all` anywhere. Both files are `contents: read`; the one job that
  needs more — drafting a release — asks for `contents: write` on itself.
- **Every action is pinned to a commit SHA, not a tag.** A tag is mutable and can be repointed by
  whoever controls the action's repository. Dependabot keeps the SHAs current, which is the other
  half of the trade, and the cost is a pull request that runs the same merge gate as any other.
- **CodeQL runs through GitHub's default setup**, not a hand-written workflow — so it adds no
  supply-chain surface of its own.

The first two are not left to habit. `assertWorkflowsAreHardened`, in the root `build.gradle.kts`
and wired into `check`, fails the build if a workflow uses an action it has not pinned to a SHA,
pins one without a `# vX.Y.Z` comment naming what the SHA is, or does not declare its own top-level
`permissions:`. It is a Gradle task for the same reason the trust checks are, and like them it
proves it can fail — over fixtures — before it reports that nothing failed, and it asserts its own
coverage: a `uses:` written in a shape the rules cannot read is a violation rather than a skip, and
a run that read no action at all fails outright.

The version comment is a rule and not a nicety, because a bare 40-character SHA is unauditable by
eye, and these files are read by people deciding whether to trust the build. What the check does
*not* look at is what the permissions actually **are**: "minimal" is a judgement about what a job
does, and a rule that guessed at it would be the kind of noise that teaches people to suppress a
check.

## Inbound dependency policy

Today the shipped distribution contains no third-party code at all: `:core` has zero runtime
dependencies, and `:plugin` depends only on `:core` and on the IntelliJ Platform, which is provided
by the IDE rather than bundled. `assertNothingThirdPartyIsShipped` fails the build if that stops
being true.

**Asserting emptiness is strictly stronger than classifying licences, and it is why there is no
licence-report tool here.** A distribution containing no third-party code has no outbound licence
surface at all — the policy below is satisfied unconditionally, transitives included, with nothing
to classify. It also cannot exhibit the failure that makes licence tooling untrustworthy: POM
licence metadata is routinely absent or wrong, so a report-based check eventually needs a
hand-maintained exception list, **and an exception list is exactly where a GPL transitive hides.**

When a real dependency is eventually proposed, this is the policy it has to clear:

- **Allowed:** MIT, BSD-2-Clause, BSD-3-Clause, Apache-2.0, ISC, CC0, Unlicense
- **Allowed as unmodified dependencies:** MPL-2.0, EPL-2.0
- **Banned:** GPL, AGPL, **LGPL**

LGPL is banned deliberately. Its dynamic-linking carve-out is too murky to rely on given the
fat-jar packaging an IntelliJ plugin uses.

A licence-report tool, if one ever arrives, must:

- **fail closed on unknown.** This project fails closed at every other layer; a licence check that
  failed open would be the single exception.
- **be scoped to the shipped runtime classpath only.** Test dependencies are not distributed.
  Scanning them manufactures exceptions, and exceptions train people to ignore the check.

Test-scope dependencies are not covered by any of this, and are added on ordinary engineering
judgement — for the same reason the architecture rules do not read test code. Nothing test-scope is
distributed, so none of it carries an outbound licence obligation.

There are three of them today — JUnit, ArchUnit and **jqwik**, the property-based testing library the
mapping's history invariants are written against. jqwik's licence was **checked against the list
above rather than assumed**: it is EPL-2.0, which that list allows as an unmodified dependency, and
it is used unmodified. It was preferred over the Apache-2.0 alternative on weight rather than on
licence — it is a JUnit Platform engine with three small transitive dependencies, where the
alternative brings a competing test framework and a coroutines runtime into a module that had two
dependencies.

## Issues

Work is tracked in this repository's [issues](https://github.com/snippetveil/snippetveil/issues).
