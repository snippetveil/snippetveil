# Contributing to SnippetVeil

## Building

```
./gradlew build      # compile and run every check
./gradlew runIde     # a sandbox IDE with the plugin installed
./gradlew buildPlugin # the installable distribution, in plugin/build/distributions/
```

JDK 17. Everything else the build needs, it downloads.

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
