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
one before it cannot see:

| Check | Where | Covers |
|---|---|---|
| `ShippedCodeArchitectureTest` | `plugin/src/test/kotlin/com/snippetveil/trust/`, runs in `check` | compiled classes from this repository's sources: no networking package, no reflective escape hatch, and no `com.intellij.*` under `com.snippetveil.core` |
| `scanDistributionForNetworkReferences` | `plugin/build.gradle.kts`, finalizes `buildPlugin`, and runs in `check` | every class in the built distribution's `lib/`, **including bundled or generated code** — read straight out of the zip that gets uploaded |
| `assertNothingThirdPartyIsShipped` | `plugin/build.gradle.kts`, runs in `check` | the shipped runtime classpath: nothing but `:core` may be in it |

**All three attach to Gradle, none to a CI workflow.** That is deliberate: a check that exists only
in YAML cannot be run by the person reading the claim. `./gradlew check` goes red on a `java.net`
import on any machine, with no CI account and no permission from anyone.

The distribution scan is wired three ways rather than one, because `finalizedBy` orders a task
without requiring it: `buildPlugin` finalizes the scan, `check` depends on it so it runs before a
push, and `publishPlugin` depends on it so an upload cannot start while it is still pending.

Two habits keep them honest, and both are worth preserving if you change them:

- **Each check asserts its own coverage.** A rule that matches nothing passes; so the architecture
  test asserts that the shipped classes were imported and that test classes were not, and the
  distribution scan fails if `lib/` yielded nothing to scan, or contains a file it cannot read, or
  hides a jar inside a jar.
- **The distribution scan proves it can fail before it reports that nothing failed**, by scanning
  `java.net.URL` and `java.lang.Object` out of the running JDK and requiring one hit and no hits.
  A red path that is never exercised decays into a check that always passes.

There is no suppression mechanism and no exception list, in any of the three. That is the feature.

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
judgement. The architecture rules do not read test code either, for the same reason: a test may
legitimately call `Class.forName` — `CoreIsIdeFreeTest` proves the module boundary that way — and
carving out an exception for it would start the exception list this project does not want.

## Issues

Work is tracked in this repository's [issues](https://github.com/snippetveil/snippetveil/issues).
