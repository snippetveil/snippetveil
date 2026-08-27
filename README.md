# SnippetVeil

<!-- Everything between the two `listing copy` markers below is the plugin descriptor's
     `<description>`, generated from these exact lines by `plugin/build.gradle.kts`. There is no
     second copy of this text anywhere: the Marketplace listing and this README are the same
     strings, and `assertTheListingCopyIsTheReadme` fails the build if they ever stop being.

     Consequence, and it is the point: **the Marketplace Approval Guidelines govern this block** —
     no third-party brand references, no marketing adjectives, no unverifiable claims, English
     first, HTTPS links only. The strictest surface wins automatically. -->

<!-- listing copy -->

**Anonymize a Java snippet before you paste it into an AI chat.**

Every name your project owns — classes, methods, fields, packages, string literals — is
replaced with a placeholder before the snippet leaves the editor. The structure, the
control flow, and the libraries you call go as-is.

### How it works

- Select Java code, right-click, **Copy Anonymized**. The anonymized text is on your clipboard.
- **Anonymize with Preview…** shows the code that will be copied beside its mapping table,
  with counts: `14 renamed · 3 unknown · 22 preserved · 2 comments stripped`. It is the only
  place you can anonymize *less* — keep comments for this snippet, or preserve a name
  SnippetVeil could not resolve.
- **De-anonymize Clipboard** turns the placeholders in a reply back into your real names.
- The mapping is per-project and stays on your machine. Placeholders are stable across
  invocations and IDE restarts, so a second snippet never contradicts the first.

### No network

SnippetVeil makes no network calls. No networking code — enforced on every pull request,
scanned in every release build, and public from the first commit.

### What SnippetVeil does not hide

- **Your stack.** Frameworks, libraries, the identity provider, the persistence layer —
  preserved by design, because they are what make the snippet answerable.
- **Your structure and your algorithm.** Control flow and logic survive intact. If your
  proprietary value *is* the algorithm, SnippetVeil does nothing for you.
- **The prompt you type around the snippet.** SnippetVeil anonymizes the code, not the
  sentence above it.
- **Your stack trace** — it is not anonymized at all.

### What it does not preserve

- **The text inside your strings** — a string literal in your own code becomes `"str1"`.
- **Your comments**, by default.

### Non-goals

Not a secret scanner. Java files only — the action does not appear anywhere else.

### Source

Apache-2.0. Source and issue tracker: `https://github.com/snippetveil/snippetveil` ·
Threat model, including the limits of the no-network checks:
`https://github.com/snippetveil/snippetveil/blob/main/THREAT-MODEL.md`

<!-- listing copy end -->

---

## About this repository

An IntelliJ IDEA plugin, for IDEA Community, IDEA Ultimate and Android Studio.

**Status: published.**
[SnippetVeil on the JetBrains Marketplace](https://plugins.jetbrains.com/plugin/33770-snippetveil) —
**Settings → Plugins → Marketplace**, and search for SnippetVeil. Every release is signed, and the
distribution zip is attached to the matching
[GitHub Release](https://github.com/snippetveil/snippetveil/releases) for anyone who would rather
install it from disk and check the bytes first.

This repository is public from its first commit, deliberately — a continuous commit history is the
evidence behind the no-network claim, and it can only be accumulated by starting early.

Work in progress is tracked in this repository's [issues](https://github.com/snippetveil/snippetveil/issues).

**Every fixture in this repository is synthetic.** The anonymiser is exercised against real
codebases by a local instrument that is never run in CI and writes its findings outside the
repository tree — and a bug it finds earns a *synthetic* fixture reproducing the shape, never the
real code that revealed it. Running the anonymiser over a real codebase and committing the output
would be exactly circular: if it leaked, the leak would be in a public repository, permanently.
See [CONTRIBUTING.md](CONTRIBUTING.md).

The `demo/` directory is the sample project the Marketplace screenshots are shot from. It is not
part of the build and is not in the distribution — see [demo/README.md](demo/README.md).

## Documents

- [THREAT-MODEL.md](THREAT-MODEL.md) — what SnippetVeil conceals, what it discloses, and what
  defeats it.
- [PRIVACY.md](PRIVACY.md) — what SnippetVeil stores and where. A factual statement, not a policy.
- [CONTRIBUTING.md](CONTRIBUTING.md) — building, the trust checks, the DCO, and the inbound
  dependency policy.
- [LICENSE](LICENSE) and [NOTICE](NOTICE) — Apache-2.0.

## Support

SnippetVeil is free and Apache-2.0, and anonymizing your code is not something you will be asked to
pay for.

If it saves you time and you want to fund the maintenance, there is a Sponsor button at the top of
this repository, and the same link on the
[Marketplace listing](https://plugins.jetbrains.com/plugin/33770-snippetveil). It is optional and it
unlocks nothing — no features, no priority, no private builds. The plugin itself never asks: it
makes no network calls, so it has no idea whether you sponsored it.

Two things help more than money. A bug report carrying the *shape* of the code that broke —
synthetic, never your real source ([CONTRIBUTING.md](CONTRIBUTING.md)). And a rating on the
Marketplace, which is one of the few signals this project gets, because it collects no telemetry.
