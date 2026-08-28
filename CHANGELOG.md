# Changelog

## [Unreleased]

## [1.1.0] - 2026-08-28

- De-anonymize Clipboard and Paste restores a reply and inserts it at the caret in one invocation,
  replacing the selection if there is one, as a single undo step. It refuses to paste a reply it
  could not restore in full — a partial or empty reversal is reported and nothing is written, so
  unrestored placeholders cannot reach source code. The clipboard is never rewritten, so the
  anonymized reply stays available to quote back. Needs a writable editor and greys out without one;
  offered on every file type, like the reversal beside it.
- The Preserve column in Anonymize with Preview carries a header tooltip saying who the column is
  for: only names SnippetVeil could not resolve can be preserved, and an empty column means every
  reference resolved.

## [1.0.0] - 2026-08-24

- Copy Anonymized replaces project-owned names in a Java selection with stable placeholders and
  puts the result on the clipboard.
- Anonymize with Preview shows the anonymized text and its mapping in a dialog before anything is
  copied.
- De-anonymize Clipboard puts the real names back, on any file type.
- Export Mapping writes the placeholder-to-name mapping for one invocation.
- Unresolved references fail closed into an Unknown namespace rather than being left as written.
- Comments and javadoc are stripped by default, split by the parse verdict for the selection.
- A settings page under Tools carries the one persistent setting, the internal-organization package
  prefix, and a Reset Mappings action.
- The plugin makes no network calls and starts no subprocesses. `./gradlew check` asserts both
  against the built distribution rather than against the sources.

[Unreleased]: https://github.com/snippetveil/snippetveil/compare/v1.1.0...HEAD
[1.1.0]: https://github.com/snippetveil/snippetveil/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/snippetveil/snippetveil/commits/v1.0.0
