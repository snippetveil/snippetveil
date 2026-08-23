# SnippetVeil

Anonymize a Java snippet before you paste it into an AI chat.

An IntelliJ IDEA plugin. Every name your project owns — classes, methods, fields, packages,
string literals — is replaced with a placeholder before the snippet leaves the editor. The
structure, the control flow, and the libraries you call go as-is.

SnippetVeil makes no network calls.

**Every fixture in this repository is synthetic.** The anonymiser is exercised against real
codebases by a local instrument that is never run in CI and writes its findings outside the
repository tree — and a bug it finds earns a *synthetic* fixture reproducing the shape, never the
real code that revealed it. Running the anonymiser over a real codebase and committing the output
would be exactly circular: if it leaked, the leak would be in a public repository, permanently.
See [CONTRIBUTING.md](CONTRIBUTING.md).

---

**Status: pre-release.** This repository is public from its first commit, deliberately — a
continuous commit history is the evidence behind the no-network claim, and it can only be
accumulated by starting early. The plugin is not yet published to the JetBrains Marketplace.

Work in progress is tracked in this repository's issues.

Licence: Apache-2.0.
