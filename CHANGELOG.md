# Changelog

## [Unreleased]

- Placeholders can be renamed in Anonymize with Preview, so a snippet can carry the word the
  question is about: double-click the Placeholder cell and `Type1` becomes `FilterType1`. The stem
  is yours and the number always stays — a name cannot end in a digit and cannot lose its number, so
  the output still announces itself as anonymized. Only rows this invocation named are editable; a
  name an earlier snippet already used, an Unknown and a literal are not, and each says why. A
  renamed field carries its accessors with it. A renamed placeholder comes back on next week's paste
  through the same mapping row as any other, and the word you typed is recorded beside the mapping —
  a set of stems, filed under no key — so that De-anonymize Clipboard and Paste still recognises it
  as SnippetVeil's once the recent-history window has forgotten the snippet, and refuses rather than
  writing it into your source. Reset Mappings clears those words with everything else.
- Preserve in Anonymize with Preview reaches every name in the mapping table, not only the ones
  SnippetVeil could not resolve — a name that carries the context making a snippet answerable can be
  sent as written. It is behind an explicit unlock that warns what a preserved name is, and the
  unlock is locked again on every open: neither it nor any tick is stored anywhere, and Copy
  Anonymized still reads no reduction at all. Literals stay non-preservable, and a preserved type
  keeps its simple name while its package renames around it.
- The mapping file is written when the mapping changes, rather than whenever the IDE next gets round
  to saving its settings. The settings page shows that file's path and invites you to go and look,
  and an IDE can run for days without writing it — so the page could name a path holding no file
  while the count above it said five. The same delay could rewind the placeholder counter if a
  session ended in a crash or a force-quit, handing a number that had already been pasted into a
  conversation to a different symbol later; the mapping file is now written on every copy and on
  Reset Mappings, in the background, so a copy is not slowed down by it.
- The settings page carries a Report a problem link. SnippetVeil collects no telemetry, so a problem
  only reaches the maintainer if it is reported, and every other route to the tracker appears on an
  error balloon after something has already gone wrong.
- Report an issue on the error balloons opens the issue chooser rather than a blank form, so the
  instruction to use a synthetic example is on screen before there is a box to type into.

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
