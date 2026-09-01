# Privacy

**This is a statement of fact, not a privacy policy.**

A privacy policy is a document about how collected personal data is handled. SnippetVeil collects
none, and publishing a policy anyway would imply a collection that does not happen — which is a
worse thing to do than to say nothing. So this file says what SnippetVeil stores, where it puts it,
and what leaves the machine.

## What is collected

Nothing.

- No telemetry, no analytics, no usage counts, no crash reports.
- No account, no licence check, no activation, no update ping.
- **No network calls at all.** SnippetVeil contains no networking code. That claim is checked on
  every pull request and scanned in every release build, and the checks run from a clone with
  `./gradlew check` — see [CONTRIBUTING.md](CONTRIBUTING.md) for what each one covers, and
  [THREAT-MODEL.md](THREAT-MODEL.md) for what none of them can establish.

There is no server. There is nothing to opt out of, and nothing to request a copy of.

## What is stored on your machine

Three files, all of them local, all plaintext, none of them synced.

| What | Where | Why there |
|---|---|---|
| **The placeholder mapping** — every placeholder and the name it stands for, filed under the project it belongs to, plus the words SnippetVeil has named a placeholder with — the stems you typed in the preview and accepted, and nothing about which name each one stood for | `snippetveil-placeholders.xml` in the IDE's configuration directory | Outside the project tree, so it cannot be committed; outside the cache tier, so *Invalidate Caches* cannot destroy your ability to read an old reply |
| **The recent-history sidecar** — the symbol table of roughly the last 50 invocations, including the text of replaced string literals, capped at 30 days | the platform's per-project cache slot, `cache-state.xml` | It is cache: losing it costs recovery of recent detail, never a wrong name |
| **The one remembered setting** — whether libraries under the project's root package count as project code, and the package prefixes that correct it | `snippetveil.xml` in the IDE's configuration directory | It is a setting, and it holds package prefixes rather than a vocabulary |

Every one of them is declared `RoamingType.DISABLED`, which is an absolute rule in this codebase
with a test of its own: **nothing SnippetVeil stores is copied to JetBrains' servers by Settings
Sync, and none of it is included in Export Settings**, which collects roamable components only.

The mapping is plaintext deliberately. It holds names that are already sitting in plaintext `.java`
files on the same disk; a compromised local machine is outside the threat model, and greppable state
is worth more than encryption to a product asking to be audited. `Settings > Tools > SnippetVeil`
prints the full path of the file, so where it lives can be checked rather than taken on trust.

## What leaves your machine

Two things, and you perform both of them.

- **The anonymized snippet you copy**, when you paste it somewhere. What is in it, and what is not,
  is the subject of [THREAT-MODEL.md](THREAT-MODEL.md).
- **A mapping CSV**, if you use *Export Mapping…* and choose a file. It carries one invocation's
  table — placeholder, original and kind — and no action anywhere exports the whole stored mapping
  or puts a mapping on the clipboard.

## Removing it

*Reset Mappings…* in `Settings > Tools > SnippetVeil` clears the mapping and the recent-history
window for the current project, after a confirmation that says what that costs. It leaves the
package prefixes alone.

Uninstalling the plugin does not delete the three files above; the IDE leaves plugin state in place,
and they are ordinary files you can delete yourself. The table says where each one is.

## Contact

`hello@snippetveil.com`, or an issue at
`https://github.com/snippetveil/snippetveil/issues`.
