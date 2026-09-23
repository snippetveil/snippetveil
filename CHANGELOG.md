# Changelog

## [Unreleased]

- **An execution plan can be anonymized from the clipboard.** `Anonymize Execution Plan…`, the
  fifth item in the SnippetVeil menu, reads a PostgreSQL `EXPLAIN` plan off the clipboard and gives
  back the same plan with the relation, column, alias and index names replaced — `Index Scan using
  idx1 on table2 table3` — while every cost, row estimate, width and timing stays exactly as it was
  printed, which is what a plan is pasted for. An index gets a kind of its own, `idx`, because an
  index is not a table. The item is always enabled and reads the clipboard only when you invoke it.
  It always opens the preview and has no fast path: the plan never passed through an editor, so the
  dialog is the only place to read what will be copied, and its button reads `Copy Anonymized Plan`.
  The counts there are `renamed` and `preserved` and nothing else, and there is no keep-comments
  tick, because a plan has neither unresolved names nor comments. `Export Mapping…` is where it
  always was.
- **A plan's `Filter`, `Index Cond` and output lists are read word by word, and the values in them
  are redacted.** `Filter: ((o.status)::text = 'ACTIVE'::text)` used to come out with the names
  replaced and `'ACTIVE'` copied through; it now comes out as `Filter: ((table1.col2)::text =
  'str3'::text)`. The operators, the brackets, the casts, the numbers and `true`/`false` are kept as
  printed, and so are PostgreSQL's own keywords, built-in functions such as `count` and `now`, and
  its type names — including the ones written with a space, `double precision` and `timestamp
  without time zone`. **Anything else in one of those fields is treated as a name and replaced**, so
  a function or a type of your own comes out as a placeholder rather than being copied. A name the
  plan printed in quotes is always replaced, whatever it is spelled like. Two printings of one value
  share one `str` number, so a predicate that repeats a value still reads as one. A field this
  cannot read cleanly — a value the engine printed with a line break in it, say — is replaced whole
  by a single `str`, never in part. One limit: a column of yours that is spelled like a built-in
  function, such as `count`, is kept under its own name, because a plan prints the two identically.
- **A plan's query text, query parameters and query identifier are redacted.** Where a plan carries
  the statement it was produced for — `auto_explain`'s `Query Text`, a foreign scan's `Remote SQL` —
  the whole line is replaced by one `str` and nothing is read out of it. A `Query Parameters` value
  that is one string is replaced by its own `str`, a number or a `NULL` is kept as printed, and
  anything else is replaced whole. `Query Identifier` is a hash of your statement, so it is replaced
  too, with the same number wherever it repeats. Row counts, timings, buffers and memory figures are
  untouched, as they always were.
- **A plan's placeholders are not remembered.** A plan carries no declarations to file placeholders
  under, so the same plan pasted twice comes back under different numbers and a plan's names never
  join the mapping an earlier snippet was sent under. A refusal leaves the clipboard exactly as it
  was and never quotes what was on it.
- **PostgreSQL's JSON, YAML and XML plans are read too.** `EXPLAIN (FORMAT JSON)`, `FORMAT YAML` and
  `FORMAT XML` join the default text output, and each is recognised on its own: a text that looks
  like more than one of them is refused rather than read as whichever was tried first. The names,
  the values and the numbers are treated exactly as they are in a text plan, and the document comes
  back in the format it arrived in.
- **A field SnippetVeil has no rule for refuses the whole plan.** Inside a field it does know, an
  unrecognised word is still replaced; a *field* is different, because nothing says whether it holds
  a name, a magnitude, a hostname or your query — so the plan is refused instead of guessed at. The
  cost is real and deliberate: a PostgreSQL release that adds a field refuses every plan carrying it
  until SnippetVeil ships a row for it. Re-running without the option that emitted the new field is
  the immediate way out.
- **A plan's `Settings` are read by key.** In the JSON, YAML and XML formats, a core planner setting
  is kept as printed — `work_mem`, `random_page_cost`, `enable_seqscan` and the rest of what
  actually explains a plan choice. `search_path` is read as the list of schema names it is, so a
  schema on the path gets the same placeholder as that schema on a relation, and `"$user"` is kept
  because PostgreSQL always substitutes it. A key SnippetVeil has no row for — an extension's, or a
  setting flagged after this release — has **both its key and its value** replaced. The text
  format's `Settings:` row is refused instead, because its values are quoted without escaping.
- **Three text-format rows are refused with the fix.** `Settings:`, `Conflict Arbiter Indexes:` and
  the `Trigger …` rows print names PostgreSQL did not quote, and they cannot be recovered from the
  text. A plan carrying one now says so and names the option that works: re-run with `EXPLAIN
  (FORMAT JSON)`. Any other line SnippetVeil does not recognise refuses with the general message —
  which also closes the case where a value containing a line break used to let its second line
  through.
- **`psql`'s own table around a plan is read rather than refused.** The `QUERY PLAN` header, the rule
  of dashes, the leading space, the padding and the `(n rows)` count are peeled off, the plan inside
  is anonymized, and the table comes back byte for byte — for text and JSON plans alike, in the
  unicode line style as well as the default one. `psql` at a non-default setting is refused, and so
  is an input carrying an echoed statement or a prompt: a table is decoration, a statement is not.
- **MySQL plans are read, as `EXPLAIN FORMAT=JSON` and in no other form.** Both of MySQL's JSON
  schema versions are accepted, and in them the relation, schema, index and column names are replaced
  while every cost, row estimate and timing stays as printed, exactly as in a PostgreSQL plan. The
  node line each operation is described by is **rebuilt from that node's own fields and compared**
  rather than read as prose — nothing in SnippetVeil ever looks in it for something that resembles a
  name — and a line that does not match refuses. A dotted reference is split by position, and one
  whose parts do not come out to the expected count refuses too, which is what an alias with a `.` in
  it produces. In the older schema version, `access_type`, `key_length` and `message` are written
  without escaping, so each is accepted only for a value MySQL itself writes: anything else refuses
  rather than being replaced, because a value that could have ended its own slot could have invented
  the fields printed after it.
- **MySQL's `TREE` output, `EXPLAIN ANALYZE` and the plain `EXPLAIN` table are refused, and each says
  which one it recognised.** This is the uncomfortable half and it is worth saying plainly: **what
  you get from MySQL without asking for anything is refused.** The table writes names into its cells
  unquoted and separates the cells with a bare `|`; `TREE` and `EXPLAIN ANALYZE` append aliases and
  index names with nothing around them at all, so a name someone chose can forge a line that reads as
  well-formed. None of it can be recovered from the text. Each of them names the one thing that
  works — re-run with `EXPLAIN FORMAT=JSON` — and the table's message describes the table rather than
  borrowing a sentence about some other shape. Your clipboard is left exactly as it was.
- **MariaDB is refused in every form, and deliberately offers nothing instead.** MariaDB's JSON
  writer does not escape the strings it writes, so what comes out is not valid JSON, and its other
  forms carry the same unquoted names its tables do. There is no output of MariaDB's that SnippetVeil
  knows would work, so the message names none: telling a MariaDB user to run a MySQL command would be
  a false statement about the engine they are running. MariaDB is recognised by **its own** output
  rather than as a broken MySQL, and where the two engines print something genuinely identical the
  paste is refused as *not a readable plan* instead of being attributed to either.
- **The `mysql` client's `\G` frame is read rather than refused.** The row banner, the `EXPLAIN:`
  label and the `1 row in set` count are peeled off, the plan inside is anonymized, and the frame
  comes back byte for byte — `\G` is a keystroke you type per statement rather than a client setting,
  so the frame it draws is fixed. The bordered table the default `;` draws around a JSON plan is a
  different thing and is refused until SnippetVeil has a capture of it.
- **A JPQL query in a Java string is anonymized name by name**, when every name in it resolves — and
  so is a query in the other persistence query languages the IDE reads the same way.
  `@NamedQuery(query = "SELECT c FROM Customer c WHERE c.merchantRef = :ref")` used to come out as
  `query = "str1"`; it now comes out as `query = "SELECT local2 FROM Type1 local2 WHERE local2.field3
  = :local4"`, where `Type1` and `field3` are the placeholders the `Customer` class and its
  `merchantRef` field get everywhere else. A string inside the query is replaced by its own `'str5'`,
  and a comment inside it is stripped and counted like any other comment. A query with any name that
  does not resolve is still replaced whole, and so is every query in an IDE that does not read the
  query language at all — IntelliJ IDEA Community among them.
- **A SQL string in a Java file is anonymized name by name**, where the IDE injects SQL into it — a JDBC call, a
  native `@Query`, a `// language=SQL` literal. `"SELECT state FROM billing.customers"` used to come
  out as `"str1"`; it now comes out as `"SELECT col1 FROM schema2.table3"`. Tables, columns and
  schemas get placeholders of their own, `table`, `col` and `schema`, and nothing is looked up in a
  database: which name is which comes from where it sits in the query. Keywords, operators, numbers
  and built-in functions such as `count` and `upper` are kept. A query holding anything else — a
  parameter such as `?` or `:ref`, a string, a comment, an alias, a function of your own — is still
  replaced whole, and so is every SQL string in an IDE without the Database Tools and SQL plugin,
  IntelliJ IDEA Community among them, and every SQL string in a Kotlin file. Nothing tells you which
  of these happened.
- **A query read name by name shows its names in the preview and in Show mapping**, as `table`,
  `col` and `schema` rows. You can rename one, like any name the snippet introduced; the new name
  lasts for that snippet, and in the next one the table is a plain `table` again. You can preserve
  one only after `Unlock Preserve for resolved names…`, like any other resolved name. No count is
  added: query names are counted with every other name, and nothing says how many came from a query.
- **Comments in Kotlin files are stripped.** Since 1.3.0, Copy Anonymized and Anonymize with Preview
  on a `.kt` file left every comment in the output exactly as written — line comments, block
  comments and KDoc — and the notification showed no comment count, because none had been stripped.
  Java files were not affected. Comments in Kotlin are now stripped by default and counted, as they
  are in Java, and Keep comments in the preview keeps them. With comments kept, a KDoc link such as
  `[merchantRef]` is renamed with the name it links to. If you copied Kotlin code with 1.3.0 or
  1.4.0, the comments in it went out unchanged. One limit: Kotlin reads some short prose comments as
  code, so the "commented-out code" part of the count can run high on a Kotlin file. The count
  decides nothing; every comment is stripped either way.
- **A selection that starts or ends inside a javadoc or KDoc block takes the whole block.** It used
  to take whole lines of the block instead — including the unselected part of the first and last
  line — and copy them unchanged, with `0 comments stripped`. This affected Java files in every
  version. The block is now selected whole, stripped like any other comment, and reported by
  "Selection expanded to whole tokens".
- The Original column of the preview and of Show mapping shows its text as plain text. A string
  literal beginning `<html>` was drawn as an HTML document, and an image tag inside one would have
  made the IDE fetch that image's address. The same applies to the package prefix table in Settings
  and to the list of words not restored.

- A placeholder you rename in the preview can no longer contain `$`. It is legal in a Java name
  but not in an unquoted SQL one, and a placeholder may be written into SQL. The editor says so
  when you type one, and a name with `$` in it that reaches SnippetVeil anyway is ignored: the
  placeholder keeps its usual name.

## [1.4.0] - 2026-09-17

- De-anonymize Clipboard restores a getter or setter the snippet never showed. A field copied without
  its getter used to leave only the field in the mapping, so a reply writing `getField1()` was reported
  as beyond the recent-history window — about a name SnippetVeil was holding — and De-anonymize
  Clipboard and Paste refused the whole reply over it. Every getter and setter the IDE reports for an
  anonymized Java field or Kotlin property is now written to the mapping beside it, spelled from the
  placeholder the field already has: no number is used up, and no placeholder already sent changes
  meaning. An existing mapping is not rewritten; it gains these rows the next time the field is
  anonymized.
- A placeholder the model spelled as an accessor SnippetVeil never sent — `setField1` against a `val`,
  `getIsField1` against `isField1` — is listed in Show details as a name this project knows, in a
  spelling SnippetVeil never sent. `setField1` used to be listed as beyond the recent-history window,
  which says the name is gone when the mapping holds it under another spelling; `getIsField1` was not
  counted at all, so De-anonymize Clipboard and Paste wrote it into the file. Either word is left as
  the reply wrote it and counts as not restored, so the paste refuses the reply.
- When De-anonymize Clipboard and Paste fails partway through inserting, the notification now says
  how to recover: "Paste failed — your clipboard was not changed. Part of the reply may already have
  been inserted; Undo reverts it in one step." It used to say only that the reply may be partly
  inserted, which sent people searching the file for what landed at each caret. One Undo reverts the
  whole insert, however many carets there were.

## [1.3.0] - 2026-09-11

- Kotlin files can be anonymized. Copy Anonymized and Anonymize with Preview are offered on `.kt`
  files, and a Kotlin declaration and a Java reference to it share one placeholder, so a snippet that
  crosses the two languages stays coherent. Gradle scripts are not included: `.kts` carries its
  secrets in strings rather than in names, and no menu item appears there. Where the IDE's Kotlin
  plugin is switched off, or is running in K1 mode, a `.kt` file says that Kotlin support is
  unavailable and leaves the clipboard alone rather than offering nothing — Java anonymization keeps
  working either way.
- Requires 2024.2 or later. Kotlin support uses the Kotlin Analysis API, which is not available in
  earlier builds. On 2024.1, the previous version remains available and continues to work.
- The first-run notification and the plugin description name the whole menu path: right-click →
  SnippetVeil → Copy Anonymized. Both left out the SnippetVeil submenu, so they pointed at a context
  menu that did not contain the item they named.

## [1.2.0] - 2026-09-01

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

[Unreleased]: https://github.com/snippetveil/snippetveil/compare/v1.4.0...HEAD
[1.4.0]: https://github.com/snippetveil/snippetveil/compare/v1.3.0...v1.4.0
[1.3.0]: https://github.com/snippetveil/snippetveil/compare/v1.2.0...v1.3.0
[1.2.0]: https://github.com/snippetveil/snippetveil/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/snippetveil/snippetveil/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/snippetveil/snippetveil/commits/v1.0.0
