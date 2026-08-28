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

*Exactly one setting* is a claim about settings rather than about `@State`. Four components ship that
the platform can write to disk, and only this one holds a choice: the other three — the mapping, the
sidecar and the first-run marker — record what happened, and nothing any of them can hold changes
what the anonymizer does. `Settings > Tools > SnippetVeil` is the page, and one tick box with a
prefix list beneath it is the whole of the product's persistent configuration.

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
  among the four state holders it covers.

`PlaceholderSidecarTest` asserts the tier rather than describing it — it resolves `$CACHE_FILE$`
through the project's own storage manager and checks where that lands — and the window itself is a
value type in `:core`, held over generated sequences of invocations by `SidecarHorizonTest`.

### The settings page, and the one destructive button in the product

`Settings > Tools > SnippetVeil` is **project-level**, because the one setting it edits is inherently
per-repository: a different employer means a different group id, and a global page would offer to
write one project's vocabulary into the next one's analysis.

It shows **the mapping's storage path in full**, and that is the cheapest possible support for
auditability there is: the four properties argued above are claims about *where the file is*, and a
suspicious person can check all four by reading one line instead of taking our word for it. The whole
no-network claim rests on evidence a sceptic can check, and this is the same bargain one directory
down. `PlaceholderLedgerTest` asserts that the line names the file the platform actually writes.

**The orphan count is deliberately not shown.** Orphans — entries for symbols that no longer exist —
are non-actionable *by construction*, since pruning is forbidden and they are retained on purpose.
The only action the number could motivate is `Reset Mappings…`, and **a number whose sole affordance
is the button you should not press is worse than no number.** A test reads the built page's own text
and fails if the word appears on it.

`Reset Mappings…` is **a button on the settings page and not an action**, so it is nowhere near
`Copy Anonymized` and — the accepted cost, stated rather than discovered — not reachable from Find
Action. It is the single place the append-only rule ends: it clears this project's mapping and the
sidecar, and **leaves the org-prefix configuration intact**, which is a structural fact rather than a
special case because the three are separate components. **The counter is not rewound**, and that is
not a leftover: rewinding would hand an old placeholder to a new symbol, so a reply pasted from last
week's conversation would decode to a *plausible wrong name* — the failure class this product refuses
— where a cleared row decodes to nothing, which is a visible gap. It is one integer and it names
nobody. Its confirmation states the consequence —
*existing anonymized snippets become undecodable* — rather than asking whether the user is sure,
because the effect is invisible until a reply fails to decode days later. `MappingResetTest` holds
each half, including the text the platform is actually shown.

**No default keyboard shortcut ships**, and that is a decision: every combination worth having is
taken, differently, across the Default, macOS, Eclipse and VS Code keymaps, so a binding clean on one
machine ships a conflict badge on another. `Configure shortcut…` on the page opens Keymap with
`Copy Anonymized` selected, which turns the absence into one click. `ActionRegistrationTest` asserts
that nothing ships a shortcut.

### The one balloon nobody asked for

The surface is deliberately invisible — no toolbar button, no tool window, no default shortcut, no
menu entry outside a Java editor — and together those make **install-and-never-notice** the realistic
failure mode. One dismissible notification, on the first project opened after installing, shown once
ever, is the whole mitigation; `FirstRunNotice` is the application-level record that keeps it to once
and the fourth state holder the roaming rule covers.

**A post-install web page is refused, and that is a release-configuration constraint as well as a
code one: the Marketplace listing must not be configured with one.** A user who installs a plugin
whose proposition is *makes no network connections* and immediately watches their IDE fetch
`snippetveil.com` has been handed a counterexample on day one — and that the request comes from the
platform rather than from our code does not help, because the claim is built on evidence a sceptic
can check.

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
- **Nothing here can tell whether a snippet is still *answerable*.** Every check in this repository
  is about concealment. Not one of them catches a change that preserves every invariant and still
  makes snippets worse to reason about — a placeholder scheme that reads badly, a literal rule that
  destroys the one string that located the bug, a comment strip that took the ground truth with it.

  **There is no CI-able assertion for it, and that is a property of the question rather than a gap
  in the effort.** Quality here is non-binary, non-deterministic, and measured by asking a model —
  which is a network call, in the repository of a plugin whose central claim is that it makes none.
  Every way of closing it is worse than leaving it open: a mocked model measures the mock, a
  golden-answer corpus pins one model's phrasing, and a live call puts a network dependency in the
  one build that must not have one.

  So it is an **occasional human-run exercise, not a suite** — re-run the naming experiment when the
  rules change shape, and read the answers. It is recorded here rather than papered over, because
  the failure mode is a green build over a product that has quietly stopped being useful, and a
  reader of this file is entitled to know which of the two claims it checks.
- **The corpus sweep is blind to an exact collision, and to nothing else.** The one instrument that
  can see a *missing* plan item subtracts every name the JDK or a library also declares, so a project
  class called `Builder` leaking verbatim looks exactly like the `Builder` the anonymiser preserves
  on purpose. That is the **only** thing it cannot see, and it is deliberately the only thing: see
  below for why nothing else is subtracted, and for the one false positive every sweep will contain.

## The corpus sweep

**Real code in, findings out, and the code never moves.**

```
./gradlew corpusSweep -PsweepProject=/path/to/a/real/checkout
```

It opens that project, anonymises every Java file in its source content **whole-file**, and writes a
triage list of names the project owns that survived into the output. With no `-PsweepProject` it is
**skipped, not failed**, so a contributor with no codebase to point it at is never blocked.

**The committed fixture corpus is 100% synthetic, and stays that way.** The obvious clever move —
run the anonymiser over a real codebase and commit the anonymised output as the public corpus — is
the worst idea available, and it is rejected in writing so that it need not be re-litigated. It is
exactly circular: **if the anonymiser leaks, the leak is in a public repository, permanently, in git
history.** The product's own failure mode, aimed at the repo.

So the rule is: **a bug this instrument finds earns a *synthetic* fixture reproducing its shape,
never the real code that revealed it.** Real code goes in; a conclusion comes out — a new rule, or a
new fixture — and the code itself never enters this repository in any form, anonymised or otherwise.

### Why real code, when the fixtures are synthetic

Synthetic fixtures contain only the PSI shapes somebody already thought of, so they can confirm the
rules that exist and nothing else. **The entire value of real code is the shapes we did not think
of.** This is the only layer in the project where a *missing* plan item is visible at all.

### The oracle is derived from the input, never from the mapping

There are two ways to assert that nothing project-owned got out. Walk the **mapping** and check that
each entry's source name is absent from the output; or build the project-owned name set **from the
input** and check that none of it survives. They look equivalent. **They are not.**

The worst bug the spike produced was a reference that passed through verbatim — and *that name was
never in the mapping*. That is what the bug **was**. A mapping-derived check is green on it forever,
because it can only ask about entries that exist: it can prove that what the anonymiser did was
done, and it can never prove that it did everything.

So `LeakOracle` is built from two sets the anonymiser's own walk had no part in — every identifier
declared anywhere in the target's own sources, less the names the JDK and the libraries declare — and
its constructor is private so that it cannot be built from anything else. A future maintainer
reaching for `AnonymizationResult.mapping` has to change a signature that says what the universe is
derived from.

### One subtraction, and why there is not a second

The library subtraction is the only one, and adding another is a **product decision** rather than a
maintainer's call about report length. **The instrument biases toward false positives rather than
silently suppressing possible leaks**, and every subtraction is a class of leak the sweep can never
see again, bought with a class of noise a human reads past once.

There was briefly a second — the top-level package segment of each declared package. It is written up
here because the argument for it is the argument that will be made for the next one, and it did not
survive:

- **The known false positive it would have removed.** `com` out of `com.acme.billing` is passed
  through by a positional rule in the engine, so it reaches the output of every file and this oracle
  flags it in every file. That is a real cost, and it is the whole cost.
- **What it would have bought in exchange.** A project class, method or field named exactly `com`,
  `org` or `io` — or any project name equal to a top-level segment declared anywhere in the codebase
  — leaking verbatim, invisibly, forever.

So it is reported. `LeakOracleTest` pins the behaviour so that a second subtraction cannot be added
quietly, the report names the row and tells the reader to adjudicate it once and read past it, and
the number this instrument is blind to stays exactly the size the ticket said it should be. If the
noise ever justifies the trade, that needs its own ticket and its own argument.

### An instrument, not a test

It **cannot** be green/red, and pretending otherwise is how it would die. The oracle is deliberately
blunt and false-positive-prone — it throws on genuine collisions with preserved library members — so
as a test it would be permanently red or permanently suppressed. That bluntness is affordable only
because a human adjudicates every row: **a false positive costs a minute, and a false negative is the
product's core promise failing silently.**

Its rules prove they can fail before it reports anything, like every other check here, and the pure
half of them is unit-tested in `check` by `LeakOracleTest` and `SweepReportTest` — a rule nobody
could test without a proprietary checkout would be a rule nobody tests.

### The report contains the leak by construction

It is a list of real identifiers from a real proprietary codebase — **the single most sensitive file
this project can produce** — and the natural instinct on reading one is to paste it into an issue to
ask about it.

**It is written outside the repository tree entirely**, defaulting to `~/snippetveil-sweep` and
refused outright if `-PsweepReportDir` points inside this repository or inside the swept project.
`.gitignore` stops `git add`; it does not stop a paste or a screenshot, and **a file being *inside*
the repository is exactly what makes those feel safe.** The console prints counts and a path, never
a name, for the same reason.

### It is never run in CI, and that is asserted rather than assumed

Two layers, because they cover different routes:

- **`assertTheSweepIsNeverRunInCi`**, in the root `build.gradle.kts` and wired into `check`, reads
  every `./gradlew` line in `.github/workflows/` and fails if one names the sweep task or either of
  its properties. This is readable only because of the thin-CI-over-thick-Gradle rule: *what CI runs*
  is a list, so it is a list that can be tested. Like the other workflow check it proves it can fail
  over fixtures first, and fails outright if it read no `./gradlew` line at all.
- **The task refuses.** `corpusSweep` fails if `CI`, `GITHUB_ACTIONS` or `BUILD_NUMBER` is set —
  which covers the routes the first layer cannot see: a `dependsOn` somebody adds to `check`, or a
  shell script on a runner that is not GitHub's.

The task name is spelled once, as an `extra` in the root build that `plugin/build.gradle.kts` reads,
so that a rename cannot leave the first layer guarding a task nobody registers. A deliberate absence:
there is no assertion over the Gradle task graph, because the configuration cache means a
`whenReady` listener does not run on a cache hit — a graph check written here would be one that
quietly stops checking on the second run.

## Continuous integration

**Thin CI over thick Gradle.** Every check CI runs is a Gradle task, and the workflows run Gradle
without knowing what is in it. That is the same rule the trust checks follow, for the same reason:
a check that exists only in YAML cannot be run by the person reading the claim.

| Workflow | Fires on | Runs |
|---|---|---|
| `build.yml` | push to `main`, and every pull request | `buildPlugin`, `check` at two platform versions, `verifyPlugin`, then a draft GitHub Release |
| `release.yml` | a GitHub Release being published or pre-released | `check`, `verifyPlugin`, `signPlugin`, `publishPlugin` — in that order — then a pull request patching `CHANGELOG.md` |

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
  *audit me* has no implicit `write-all` anywhere. Both files are `contents: read`; the two jobs that
  need more — drafting a release, and patching the changelog back — ask for it on themselves.
- **Every action is pinned to a commit SHA, not a tag.** A tag is mutable and can be repointed by
  whoever controls the action's repository. Dependabot keeps the SHAs current, which is the other
  half of the trade, and the cost is a pull request that runs the same merge gate as any other.
- **CodeQL runs through GitHub's default setup**, not a hand-written workflow — so it adds no
  supply-chain surface of its own.
- **A job that opens a pull request needs two settings on top of its own permission, and neither is
  in this repository.** `release.yml`'s changelog job declares `pull-requests: write` and still fails
  with *"GitHub Actions is not permitted to create or approve pull requests"* unless
  `can_approve_pull_request_reviews` is true on the repository — which the organization has to allow
  first, or the repository-level write is refused with a 409. Both are on now. Before they were, the
  job pushed its branch and its signed commit and went red on `gh pr create`, which is how v1.1.0
  shipped: uploaded cleanly, red run, changelog pull request opened by hand.

  **The switch is labelled "create *and* approve", and the approve half is the one worth thinking
  about** — a workflow that can approve a pull request can satisfy a required-review count and merge
  its own work unreviewed. There is none to satisfy here: the `main` ruleset requires a pull request
  with `required_approving_review_count: 0`, so the dangerous half has no gate to bypass. **Raising
  that count above 0 makes this setting load-bearing and is the moment to revisit it.**
  `default_workflow_permissions` stays `read` at both levels, so what the switch lifts is a
  prohibition rather than a token scope: a job still has to ask for `pull-requests: write`, and the
  changelog job is the only one in either file that does.

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

## Releasing

### The version is a claim, not a build counter

**Semver, single source of truth in `gradle.properties`, hand-bumped in the pull request that earns
it.** Nothing derives it from a tag, a run number or a date. A version says what a user can expect
of an upgrade, and that is a judgement somebody makes rather than a number something increments.

`build.yml` asks Gradle for it rather than grepping the properties file, and drafts `v$VERSION`
from every green `main`. Once a version is published the tag exists, so the draft step warns and
exits instead of failing — being red until somebody bumps the version is not a useful way to say
*bump the version*.

### `CHANGELOG.md`

Maintained under `## Unreleased` as the work lands, by the person landing it. Three things read it,
and that is the reason it is a real file rather than a habit:

- **The descriptor's `<change-notes>`**, rendered to HTML by `org.jetbrains.changelog`, which means
  the text ships inside the distribution and is what a user sees in the IDE's plugin panel.
- **The draft release's notes**, from `getChangelog --unreleased` — not a generated list of commit
  subjects. The draft therefore shows exactly the text that would be uploaded.
- **`assertNoRoadmapIsPublished`**, which reads it as one more published surface, under the same
  rules as the README.

**Placeholder change notes are a documented Marketplace rejection reason**, so this is a
requirement and not hygiene. It is also why `groups` is empty: an auto-generated `### Fixed` under a
release that fixed nothing is a placeholder a tool wrote, and a plain list of what actually changed
is both what a reader wants and what an approver is checking for.

After an upload, `release.yml` runs `patchChangelog` — which closes `## Unreleased` into
`## [<version>] - <date>` and opens a fresh one — and puts the result up as a **pull request**, not
a push. A changelog committed by a job nobody reviewed is a changelog on its way to becoming a
generated file.

### One Marketplace channel, and no `beta`

Two facts gut the case for a second channel at this scale: a non-default channel is **a separate
repository URL the user has to add to their IDE by hand**, and **every upload is manually reviewed
anyway** — beta included — so the channel shortens no latency and reaches nobody who has not already
been told where to look.

**Pre-releases are GitHub Release assets instead.** `build.yml` already uploads the distribution
zip; a tester installs it with *Install Plugin from Disk*.

The version-suffix → channel derivation stays wired into `publishPlugin` anyway, because the
alternative to deriving the channel is remembering to set it, and the release that needs one is the
release where somebody is already doing something unusual. `1.1.0-beta.1` would route to `beta` on
the day it is wanted; `1.0.0` routes to `default`, and so does every version this project has built.

### Signing, and where the key lives

The plugin is signed with a self-signed RSA-4096 key and its certificate chain; the Marketplace
counter-signs on the way through. **Signing is not mandatory and is treated as required anyway** —
an unsigned or revoked-certificate plugin shows the user an install warning, which is a poor first
impression for a plugin whose entire pitch is trust.

`signPlugin` runs automatically before `publishPlugin` when the key is present, and is skipped when
it is absent — which is what lets a fork run everything up to the upload.

**And a skip is silent, so it is asserted rather than trusted.** `PublishPluginTask`'s convention
reads `signPlugin.didWork` and falls back to `buildPlugin`'s archive when it is false:

```kotlin
signed -> signPluginTaskProvider.flatMap { it.signedArchiveFile }
else -> buildPluginTaskProvider.flatMap { it.archiveFile }
```

The fallback is what makes a keyless fork work, and the cost is that **the difference between a
signed release and an unsigned one is one empty environment variable.** `SignPluginTask`'s `onlyIf`
treats an empty string as an absent key, so a secret that exists and holds nothing skips signing and
leaves a green build behind it. That is not hypothetical: it is how v1.0.0's first release run
behaved, and only the Marketplace's *upload once manually* rule stopped an unsigned upload.

`assertThePluginWasSigned` closes it, reading the bytes rather than the task state. The signed
archive must exist, must carry the `PK Sig Block 42` block the Marketplace ZIP Signer writes ahead
of the central directory, and must hold **the same entries at the same sizes** as the archive this
build produced — the third rule being what stops a leftover `-signed.zip` from an earlier version
passing the first two.

**It is the one trust check here that cannot run from a clone**, and that is a property of the thing
being checked rather than a choice: a signature needs a key, and the key is reachable only from the
environment-gated release job. So it hangs off `publishPlugin` and never off `check` — a `check`
variant would be vacuous on every machine that has no key, which is every machine but one. Its rules
are still exercised on every run, against the unsigned archive, which is the fixture that is always
present.

**The key of record lives in a password manager and in no repository, with the `.pem` and the chain
backed up.** A changed or revoked certificate makes every user see an install warning, so key loss
is *recoverable but visible*: the backup is an obligation of the release process rather than a
personal habit.

### The four secrets, and why they are not repository secrets

`CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD` and `PUBLISH_TOKEN` **are the plugin's
identity**: whoever holds them can sign and publish as SnippetVeil. They live in a GitHub
**Environment** named `marketplace`, which the release job declares and which carries a **required
reviewer**.

**The vector this closes is not the obvious one.** Fork pull requests never receive secrets, so that
one was closed already. The live one is that **a compromised third-party action in `build.yml` runs
with access to every *repository* secret and to none of the *environment* ones** — and `build.yml`
is the file that runs on every pull request, against code nobody has merged. Scoping removes the
crown jewels from that pipeline's blast radius entirely. The required reviewer then adds a second
deliberate human act before anything is signed, matching the posture the Marketplace's own manual
review already imposes on the other end.

Required reviewers **are** available for public repositories on a GitHub Free plan; that was
confirmed against this repository rather than taken from documentation. `can_admins_bypass` is off,
so the approval is not something an admin can skip. `prevent_self_review` is on the other side: with
one maintainer it would mean nobody could ever approve.

Half of that arrangement is a settings page, which is exactly the half that is true the day it is
set up and quietly false a year later. The half that lives in the repository is checked:
`assertOnlyTheGatedJobCanReachTheSigningKey`, in the root `build.gradle.kts` and wired into `check`,
enforces three rules.

1. **Only `release.yml` may name any of the four.** Every other workflow is checked for the names
   and must not have them.
2. **A job that names one must declare `environment: marketplace`.**
3. **No workflow may reach a secret by anything but its name.** `toJSON(secrets)` and `secrets[…]`
   hand out the whole context, and the first two rules are a name-based scan — sound only while
   names are the only way through. Without the third, they are a search that can be stepped around
   in one line.

Like the other trust checks it proves it can fail over fixtures before reporting that nothing
failed, and it asserts its own coverage: `release.yml` must yield all four, so a run that read three
fails rather than passing quietly. What it cannot check is the GitHub side — that the environment
exists, holds these four and no others, and carries the reviewer. None of that is in a clone.

### The release checklist

1. Version hand-bumped in `gradle.properties`; `CHANGELOG.md` has real content, no placeholder text,
   **no roadmap**.
2. The bytecode scan is green — automatic, it finalizes `buildPlugin`.
3. `verifyPlugin` is green: **no `COMPATIBILITY_PROBLEMS`, no `INTERNAL_API_USAGES`**. Both are
   explicit Marketplace approval criteria and both are in the task's default failure levels.
4. `assertNothingThirdPartyIsShipped` is green.
4b. `assertThePluginWasSigned` is green — it gates `publishPlugin` and needs no remembering.
5. Screenshots re-shot from `demo/` if the dialog changed.
6. `demo/` is excluded from the distribution — `assertTheDemoIsNotShipped`.
7. **The Marketplace listing has no post-install page.** A plugin selling *no network connections*
   does not open one on day one.
8. The licence field declares **Apache-2.0 with a resolving source URL**. An OSS licence with no
   source link is a documented rejection reason.

Steps 1-4 and 6 are `./gradlew check verifyPlugin` on any machine. 5, 7 and 8 are judgement and a
Marketplace form.

**The first submission cannot go through `publishPlugin`.** A new plugin has to be uploaded once by
hand, through the Marketplace form, because that upload is what creates the listing and sets the
licence and repository fields. `publishPlugin` only ever *updates* an existing page — pointed at a
plugin that does not exist yet it fails with *"Cannot find plugin"*, which is what v1.0.0 did. Every
release after the first goes through CI.

**Budget about a week for a submission.** Every new plugin *and every update* is reviewed by a
person. There is no published SLA — escalate after two to four working days — no documented
auto-approval, and approval can be withdrawn.

## The publication checks

Everything the Marketplace listing needs, other than the plugin itself, is in this repository — and
five more Gradle tasks decide whether it still says what it is supposed to.

**The Marketplace `<description>` and the README opening are the same strings**, not two texts
saying the same thing. Two differently-worded statements of one claim invite *which one is true*,
and on a plugin whose entire moat is trust that question costs more than the tailoring it buys.

So README.md is the one copy. The block between its two `<!-- listing copy -->` markers is rendered
to HTML by a small fail-closed renderer in `plugin/build.gradle.kts` and patched into the descriptor
— which carries **no `<description>` element of its own**, so the second copy cannot be written.

The consequence is the point: **the Approval Guidelines now govern the README too.** No third-party
brand references, no marketing adjectives, no unverifiable claims, English first, HTTPS links only.
The strictest surface wins automatically, by construction rather than by discipline.

| Check | Where | Covers |
|---|---|---|
| `assertTheListingCopyIsTheReadme` | `plugin/build.gradle.kts` | The description in the built distribution, word for word against README.md — plus the heading order, the 40-character floor, HTTPS-only links, the phrase ban and third-party brand names |
| `assertNoBannedPhraseAppearsOnAnySurface` | root `build.gradle.kts` | Every Markdown file in the repository, every string literal in `:core` and `:plugin` main sources, and the descriptor's own menu strings |
| `assertNoRoadmapIsPublished` | `plugin/build.gradle.kts` | README.md, CHANGELOG.md the day there is one, and the change notes in the built distribution |
| `assertTheDemoIsNotShipped` | `plugin/build.gradle.kts` | `settings.gradle.kts`, and every path in the zip at both levels |
| `assertBothPluginIconsShip` | `plugin/build.gradle.kts` | Both icons in the distribution: 40 × 40, no text, and a dark variant that is not a copy of the light one |

All five run in `check`, and the listing one also gates `publishPlugin` directly — `publishPlugin`
reaches `buildPlugin` without passing through `check`, so an upload could otherwise start on
unverified copy.

**Three of them read the built distribution rather than the checked-in files**, and the reason is
the descriptor: it is patched at build time, so the description is set in Gradle and appears nowhere
in `plugin.xml`. A rule that read the source descriptor would be blind to anything else written the
same way — change notes first among them. The phrase list itself is an `extra` on the root project,
read by both builds, for the reason the corpus sweep's task name is one: two lists would drift, and
they would drift towards the strictest surface being checked against the laxest rule.

### The banned phrases

**The list is spelled once, in `bannedPhrases` in the root `build.gradle.kts`, and deliberately not
repeated here.** A documentation file is one of the surfaces the check reads, so a ban list quoted
into it fails the build on its own contents — which is not an awkwardness to work around but the
rule working: there is no exception list, and a file exempted "because it is only explaining the
rule" is exactly where a violation eventually hides.

What is on it: eight phrases, and the inflections of the two that are verbs. Every one of them is a
claim about an adversary's capability, and none of them is ours to make. **Copy here states the
mechanism and never the category** — *"14 names replaced"* is a count of an operation; a verdict on
whether the result is now fit to send is a verdict about an attacker we have never met.

One entry is a past participle whose plain noun form is *not* banned, and the asymmetry is
deliberate: THREAT-MODEL.md turns on a sentence saying what actually holds the no-network claim up,
and that sentence needs the noun. Banning it too would be the kind of noise that teaches people to
suppress a check.

**Markdown is read whole; Kotlin is read for its string literals only.** The comments in this
codebase quote banned phrases in order to explain the ban, and a rule that read them would fail the
build over its own rationale. Telling the two apart needs a scanner rather than a pattern —
stripping line comments with a regular expression takes the `//` out of `https://` with it — so
there is a small state machine over Kotlin's comments, strings, raw strings and character literals,
exercised against a fixture carrying all four before it is pointed at anything real.

### No roadmap, anywhere

Not in the README, not in the listing, not in the change notes. **A live commit history is evidence
of maintenance; a roadmap is a promise about it** — and a solo hobby v1 that misses a published
roadmap item wounds precisely the thing this product sells. Status is not a roadmap and stays legal:
*"the plugin is published"* is a fact about today, and *"the plugin is not yet published"* was a fact
about the day before it shipped. Both are things that are true; neither is a promise about what
happens next.

### `demo/`, and the screenshots

[`demo/`](demo/README.md) is the invented, JDK-only sample the Marketplace screenshots are shot
from, committed so the shots are reproducible at any commit. **It is not a Gradle subproject** — the
build is fixed at `:core` + `:plugin`, and the release asserts the shipped classpath holds `:core`
and nothing else — so it is loose source, opened as its own IDEA project, and never on the
distribution's path. [`docs/screenshots/README.md`](docs/screenshots/README.md) lists the six shots
and the selection each is taken from.

## Signing off: the DCO, and deliberately no CLA

Every commit needs a `Signed-off-by` line. Git writes one for you:

```
git commit -s
```

That line is an assertion under the [Developer Certificate of Origin 1.1](https://developercertificate.org/),
reproduced in full at the bottom of this section. In one sentence: *you have the right to submit
this, under this project's licence.*

**There is no CLA, and that is a decision rather than an omission.**

A contributor licence agreement exists to solve a problem this project does not have. Apache-2.0 §5
already states that a contribution intentionally submitted for inclusion is licensed under these
same terms unless you say otherwise — **inbound is outbound, in the licence itself, with nothing to
sign.** And Apache-2.0 is permissive, so the relicensing freedom a CLA is usually collected for is
already granted to everyone, maintainer included: nothing here is waiting on a signature to be
reused in a proprietary product.

CLAs are for the *copyleft* open-core shape — a GPL project whose owner wants to ship a proprietary
edition needs rights the GPL does not give them, so they collect them one contributor at a time.
Ask for one here and you buy nothing that is not already granted, at the cost of a signing step on
every pull request. That step is measurably the biggest deterrent to small contributions, and small
contributions are the ones this repository most wants: a typo in a threat model, a fixture for a
shape the anonymiser gets wrong.

**What the DCO adds is the one thing §5 leaves out: an explicit right-to-submit assertion.** §5 says
what licence a contribution arrives under; it says nothing about whether the person submitting it
was entitled to. That gap used to be theoretical. It is not now — **AI-authored pull requests are
routine**, and "where did this code come from" is a question a public repository gets asked. The DCO
answers it at the point of the commit, for the price of one flag.

**Nothing enforces this mechanically**, and that is worth stating in a file full of checks that do.
There is no sign-off bot and no CI gate; an unsigned commit is caught in review or not at all. A
missing `Signed-off-by` is a thing to fix with `git commit --amend -s`, not a thing to argue about.

### Developer Certificate of Origin 1.1

```
Developer Certificate of Origin
Version 1.1

Copyright (C) 2004, 2006 The Linux Foundation and its contributors.

Everyone is permitted to copy and distribute verbatim copies of this
license document, but changing it is not allowed.


Developer's Certificate of Origin 1.1

By making a contribution to this project, I certify that:

(a) The contribution was created in whole or in part by me and I
    have the right to submit it under the open source license
    indicated in the file; or

(b) The contribution is based upon previous work that, to the best
    of my knowledge, is covered under an appropriate open source
    license and I have the right under that license to submit that
    work with modifications, whether created in whole or in part
    by me, under the same license (unless I am permitted to submit
    under a different license), as indicated in the file; or

(c) The contribution was provided directly to me by some other
    person who certified (a), (b) or (c) and I have not modified
    it.

(d) I understand and agree that this project and the contribution
    are public and that a record of the contribution (including all
    personal information I submit with it, including my sign-off) is
    maintained indefinitely and may be redistributed consistent with
    this project and the open source license(s) involved.
```

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
