# Security policy

## Reporting a vulnerability

**Email `security@snippetveil.com`. Please do not open a public issue.**

That is the one exception to this project's usual preference for the tracker. Everything else about
SnippetVeil is better discussed in public; a vulnerability is better discussed in public *after*
there is a version people can move to.

Useful in a report, none of it required:

- What an attacker gets, and what they need in order to get it.
- The plugin version and the IDE build — `Settings | Plugins | SnippetVeil` and `Help | About`.
- A way to reproduce it. **Synthetic code, please** — invented names in the shape of real ones. The
  whole point of this plugin is to keep real source off other people's machines, and a report is
  not an exception to that.

This is a solo project. Expect a human reply rather than a fast one, and expect it from one person
in one time zone. If a report goes unanswered for two weeks, send it again — the likeliest
explanation is that it was missed.

## What is in scope

The plugin, and the claims this repository makes about it. Two of those claims are the ones most
worth attacking:

- **A name that should have been replaced and was not.** Anonymization that misses a project-owned
  name is the failure this product exists to prevent. It is a bug in normal circumstances and a
  security issue when it is reachable deliberately.
- **The plugin makes no network calls and starts no subprocesses.** `./gradlew check` asserts both
  against the built distribution rather than against the sources. A way to reach a socket or a
  process from shipped code — including through a dependency — is in scope, and so is a hole in the
  checks themselves. [`THREAT-MODEL.md`](THREAT-MODEL.md) states what those checks do and do not
  cover; the gaps named there are already known, and a report that sharpens one is still welcome.

The placeholder mapping is stored unencrypted, by design and on the record — it is a file of your
own names, on your own disk, readable by anything that can already read your source. That is
documented rather than defended, so it is not a finding on its own. A way to read it from outside
that boundary is.

## What is not

Findings against the JetBrains Platform itself belong to JetBrains, not here.

## Supported versions

The latest published version, and no back-branch. When a fix ships it ships forward, on the
Marketplace, in the next version.
