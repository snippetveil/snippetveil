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

### Known limits

This is deliberate, and it is stated here rather than left for a reader to discover, because a trust
artifact that overstates its own reach is worse than one that does less and says so.

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

## Issues

Work is tracked in this repository's [issues](https://github.com/snippetveil/snippetveil/issues).
