# Changelog

## [Unreleased]

- **A Java stack trace can be anonymized from the clipboard.** `Anonymize Stack Trace…`, the sixth
  item in the SnippetVeil menu, reads an exception trace off the clipboard and replaces every name
  in it that belongs to your project with the placeholder your snippets already use, so a frame
  reads `at com.pkg1.pkg2.Type3.method4(Type3.java:42)` and lines up with the code you sent
  alongside it. A name the trace mentions for the first time gets a new placeholder and is
  remembered, as it would be from a snippet. JDK and library frames are kept as printed. A class or
  method that no longer resolves becomes a single `Unknown`, and so does a file name that no public
  class is named after. An inner class's file is named after its outer class. Line numbers,
  `(Native Method)`, `(Unknown Source)`, `Caused by:`, `Suppressed:` and `... 12 more` are kept as
  printed; the thread name and every exception message, a library exception's included, become
  `str` literals. Module and classloader prefixes such as `java.base/` and `app//` are removed, so
  the output is not the input byte for byte. The item is enabled whatever is on the clipboard and
  reads the clipboard only when you invoke it. Like `Copy Anonymized`, it is greyed out while the
  IDE is indexing. It always opens the preview, whose
  button reads `Copy Anonymized Trace`. The clipboard has to hold the trace and nothing else: a
  line that is not part of a trace, such as a log line above it, refuses the whole paste and leaves
  the clipboard as it was. Two shapes are not read yet and are refused the same way: a message that
  runs over more than one line, and a frame of a hidden class such as a lambda
  (`Foo$$Lambda/0x…`). A JDK or library frame whose method the IDE cannot find, such as
  `lambda$main$0`, has that method replaced with an `Unknown`.
- **A stack trace frame of a generated class resolves as far as the IDE can take it.** A lambda, a
  coroutine, a proxy or an anonymous class used to turn the whole frame into `Unknown`s even when its
  owning class was yours and resolved. Now the longest part of the name the IDE finds is replaced as
  usual and only the rest becomes one `Unknown`:
  `com.acme.BillingService$charge$1.invokeSuspend(BillingService.java:42)` reads
  `com.pkg1.Type2$Unknown3.invokeSuspend(Type2.java:42)`, and `charge$suspendImpl` reads
  `method4$Unknown5`. `invokeSuspend` is kept as printed, like `<init>`. The part that did not
  resolve is replaced whole, so no piece of it, such as `charge` in `$charge$1`, is ever printed.
  In the preview its row reads `(generated)` rather than its text, and it has no `Preserve` box,
  because preserving it would print your own method name; an `Unknown` for a whole frame still has
  one. `De-anonymize Clipboard` restores these names exactly, and an exported mapping lists what
  each one stood for. **The `unknown` count falls for the same trace**: the frame above used to
  count three unknowns, its class, its method and its file name, and now counts one, because more
  of it resolved.
- **A Kotlin coroutine trace can be anonymized, and a coroutine dump is refused by name.** A failing
  coroutine's trace carries marker lines that `kotlinx.coroutines` writes into it, and these used to
  refuse the whole trace as not a stack trace. They are now kept as printed and count as nothing:
  `at _COROUTINE._BOUNDARY._(CoroutineDebugging.kt:42)` and `at _COROUTINE._CREATION._(…)` whatever
  line number your version of the library puts there, and the older markers from before its 1.7.0
  release, `(Coroutine boundary)` and `(Coroutine creation stacktrace)`, in both the form a terminal
  shows and the form a copy holds, with its backspace characters. The frames under a creation marker
  are anonymized like the rest of the trace. `kotlinx.*` frames are library frames and are kept as
  printed, so a coroutine trace comes out mostly as it went in. In a Kotlin frame, the file name is
  replaced with the placeholder of the file's facade class, so `Billing.kt` reads `Type1.kt` beside a
  frame of any class in that file. A `.kt` file with no top-level function or property has no
  facade, and its file name becomes a bare `Unknown`. The output of `DebugProbes.dumpCoroutines` and
  a `printJob` tree are refused with a message that says the clipboard holds a coroutine dump rather
  than a stack trace; the clipboard is left as it was. A trace containing
  `[CIRCULAR REFERENCE: …]` is still refused as not a stack trace.
- **A stack trace's own frames decide which libraries are your organization's.** For a snippet,
  a library class under your file's root package, such as `com.acme`, is treated as your
  organization's code and anonymized. A trace has no file, so its library frames used to be kept as
  printed, and `com.acme.platform.HttpClient` showed your organization's package names. Now the
  root packages come from the trace's frames that resolve to your project:
  `com.acme.billing.BillingService` in your project makes `com.acme` a root, and a
  `com.acme.platform.HttpClient` frame is anonymized. Frames under a coroutine creation marker count
  too. Your internal-library prefix list applies as before.
  **A trace from another project comes back mostly `Unknown`, and that is intended.** A trace from
  a colleague's project or an old build has classes your IDE cannot find. Each one becomes an
  `Unknown`, and the preview opens as usual. Nothing in such a trace resolves to your project, so it
  has no root package, and only your prefix list decides which libraries are anonymized. The same
  `com.acme.platform.HttpClient` frame can then be kept as printed in that trace and anonymized in
  one of your own. A frame whose class resolves gets a placeholder and is remembered, as it would be
  from a snippet. A frame whose class does not resolve is not remembered. Cancelling the preview
  leaves your placeholders and their numbering as they were. **Confirming a foreign trace advances
  the placeholder numbers**: each `Unknown` uses up a number that is not remembered, so after a few
  such traces your next snippet's placeholders can jump, from `Type4` to `Type340` for example.
- **A stack trace indented with spaces is read.** `Anonymize Stack Trace…` used to read a trace only
  when its frames were indented with tabs, as the JVM prints them. Traces copied from chat, issue
  trackers and web pages have usually had their tabs turned into spaces, or into no-break spaces,
  and were refused with *Clipboard is not a stack trace*. Now a frame, `Suppressed:`, `... 12 more`
  or coroutine marker line may be indented with tabs, spaces or no-break spaces, in any mix and any
  amount, and the output keeps each line's indentation exactly as it was pasted. The first line still
  has to start at the left edge, and an indented line that is not part of a trace, such as a log
  line, still refuses the whole paste. A coroutine dump indented with spaces is still refused as a
  dump.
- **The stack trace preview says when Kotlin support is unavailable.** `Anonymize Stack Trace…`
  reads the clipboard rather than a file, so it never showed the *Kotlin support is not available*
  message, and on an IDE where SnippetVeil's Kotlin support was missing a Kotlin frame simply came
  back `Unknown`. Now, when Kotlin support is unavailable and the trace has at least one `Unknown`,
  the preview adds a line under the counts naming the cause, with the same link the message offers:
  `Open Plugins` when the Kotlin plugin is not running, and `Open Kotlin settings` when it is
  running in K1 mode. The line says this is one possible reason a name is unknown, not that it is
  the reason: it appears on a Java-only trace too, because SnippetVeil does not guess a trace's
  language from its text. The trace is anonymized and copied as before.
- **Commented-out Java and Kotlin code is kept, with its names replaced.** `Copy Anonymized` used to
  strip every comment, so a line such as `// this.customer.setOrder(order);` was lost on every
  paste. In a `.java` or `.kt` file a comment whose text parses as code of that language where it is
  written is now kept, and every name in it is replaced with the placeholder that name gets in the
  code around it: `// this.field2.method3(param4);`. A string in it becomes `"str1"`, as it does
  anywhere else. A commented-out field or property, method or function, import or top-level class
  counts, not only a statement. A comment that does not parse — a TODO, an explanation, a javadoc or
  KDoc block with `@param` in it — is still stripped, and so is prose written after the code on a
  kept line, or inside a kept block comment. A name in the kept line that no longer resolves becomes
  an `Unknown`, and the line is kept. **Prose that parses is prose in both languages**: Kotlin reads
  `// retry on timeout`, `// TODO` or `// value in range` as code — a call of three names, a name on
  its own, a containment check — and such a comment is stripped in a `.kt` file exactly as it is in
  a `.java` file. A comment is kept as code only when it holds something prose does not: a bracket,
  `=`, `.`, `;`, `::` or `->`, one of the operators `+=`, `-=`, `==`, `!=`, `?.`, `<=` or `>=`, a
  literal, or a keyword such as `val`, `fun` or `if`. So `// retry(onTimeout)`, `// x = 1`,
  `// x += y` and `// foo?.bar` are kept, and `// it is fine`, `// done as planned` and
  `// return later` are stripped. Other operators do not count: `// x *= y` is stripped. A real
  commented-out line with none of these, such as `// return result`, is stripped with the prose;
  `// return result;` and `// return total(items)` are kept. A commented-out Kotlin enum entry or function argument does not parse where it is
  written and is stripped.
- **Keep comments and javadoc now keeps prose only.** Commented-out Java and Kotlin code is kept and
  anonymized whether the preview's tick is on or off; the tick keeps the comments that are not code,
  exactly as written, for that snippet.
- **The comment count reads `2 comments stripped`**, in the preview and in the notification. It used
  to add `, 1 of them commented-out code`, to point at the tick as the way to keep that code; in a
  `.java` or `.kt` file the code is kept without it now. The count includes only the comments that
  were removed.
- **A kept comment is counted: `1 comment anonymized`.** The preview's counts and the `Copy
  Anonymized` notification now say how many commented-out Java or Kotlin lines were kept with their
  names replaced. It appears only when there is at least one; a snippet with no commented-out code
  shows no such entry, not a zero. It is a count, beside the others, and not part of `2 comments
  stripped`, which still counts only the comments that were removed. A commented-out line with no
  name or string in it, such as `// return;`, has nothing to anonymize and is not counted.
- **The `unknown` count now says how many came from comments**: `3 unknown (1 from comments)`.
  Commented-out code is often out of date, so the names in it that no longer resolve used to swell
  `unknown` on every paste from a file with commented-out history. The total is unchanged; the
  bracket says how many of those names appear only in kept comments. A name that is unresolved in
  live code and in a comment is counted once, as live code. The bracket is absent when none came from
  comments. `Show mapping` shows the same numbers as the notification it was opened from. An
  execution plan's counts are unchanged.
- **The listing, the README and the threat model stop saying a stack trace is not anonymized.**
  *What SnippetVeil does not hide* no longer lists your stack trace, and nothing takes its place.
  *What it does not preserve* gains your exception messages, which `Anonymize Stack Trace…` always
  replaces: retype the message that matters into your prompt. The strings line above it gains the
  same kind of recourse: retype the value that matters into your prompt. `How it works` describes
  the action, and the first-run notice gives its path beside the execution plan's: copy the trace, then
  right-click → **SnippetVeil** → **Anonymize Stack Trace…**. The threat model's section on the
  stack trace now says what is true: the action is opt-in and reads the clipboard only when you
  invoke it, so a trace pasted straight from the run console is as exposed as before; the module and
  classloader prefix it drops makes a module-layer bug undiagnosable from the anonymized copy; the
  line numbers it keeps, with the shape of the trace, can identify a public codebase; and once you
  confirm the preview, every name in the trace that resolves to your project is added to the
  mapping, including classes you never selected.

## [1.5.0] - 2026-09-29

- **An execution plan can be anonymized from the clipboard.** `Anonymize Execution Plan…`, the
  fifth item in the SnippetVeil menu, reads an execution plan off the clipboard (PostgreSQL, MySQL,
  SQL Server or Oracle, in the forms the entries below describe) and gives back the same plan with
  the relation, column, alias and index names replaced — `Index Scan using idx1 on table2 table3` —
  while every cost, row estimate, width and timing stays exactly as it was printed, which is what a
  plan is pasted for. An index gets a kind of its own, `idx`, because an
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
  well-formed. None of it can be recovered from the text. The warnings overlay `SHOW WARNINGS` hands
  back after an `EXPLAIN` is refused with them. Each of them names the one thing that works — re-run
  with `EXPLAIN FORMAT=JSON` — and the table's message describes the table rather than borrowing a
  sentence about some other shape. Your clipboard is left exactly as it was.
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
- **SQL Server plans are read, as Showplan XML and as the `SET SHOWPLAN_TEXT ON` plan rowset.**
  Showplan XML is the form you copy out of the result grid, and it needs no client frame peeled off
  it because SQL Server draws none — it is the one engine here that arrives bare. Its content is in
  its attributes rather than in elements, so a column arrives already split into the table it belongs
  to and its own name, and almost nothing in it has to be scanned: the relation, schema, index, alias
  and column names are replaced, the statement it echoes back is redacted whole, and every estimate,
  cost, row size and timing stays as printed. In the text rowset every name is bracketed with the
  closing bracket doubled, so a table called `Odd]Table, Two` comes back as one name rather than as
  two — and everything outside a bracket is SQL Server's own, so `Inner Join`, `SEEK:` and `ORDERED
  FORWARD` survive without SnippetVeil keeping a list of them.
- **Four of SQL Server's things are refused, and the two wide rowsets get different fixes.** A paste
  that begins at the statement echo is refused: `SHOWPLAN_TEXT` returns the statement in a rowset of
  its own above the plan, so copying both grids hands over the query — copy from the plan rowset, and
  a multi-statement output carrying a second echo is refused too. A plan with a **remote** row in it
  is refused — a remote query, scan, insert, update or delete — because every one of them prints the
  linked server unbracketed, and a remote query prints the remote statement verbatim;
  `SET SHOWPLAN_XML ON` is named as the fix. Every other row is read, including one whose operator
  SnippetVeil has never seen: in this format the brackets are what make a row safe, and they do not
  depend on knowing the operator's name. `SET SHOWPLAN_ALL ON`
  and `SET STATISTICS PROFILE ON` are refused because the first row of the plan rowset carries your
  statement in its text cell, in a grid whose tab and newline separators are the client's with
  nothing escaping them — and they name **different** fixes, `SET SHOWPLAN_XML ON` and `SET
  STATISTICS XML ON`, because telling somebody who asked for actual row counts to run the estimated
  plan would throw away what they came for. The results-to-text client mode, which truncates every
  column at a fixed width and so cuts names in half, is refused with the general message.
- **Trace flags, a parameter's declared type and a conversion's length are kept.** They are what
  explains an implicit conversion, which is one of the first things anyone reads a plan for: the
  declared type is the same for every value that slot ever holds and the length is the target
  column's width, so neither says anything about your data. The average row size and the cached plan
  size are kept for the same reason — they are magnitudes about the table and the plan.
- **Oracle plans are read, as the `DBMS_XPLAN` grid and as the SQL Monitor XML report.** The grid is
  the `|`-ruled table you get from `DBMS_XPLAN.DISPLAY` and `DISPLAY_CURSOR`: the object each row
  names is replaced, the operation, the estimates, the actuals and the timings stay as printed, the
  predicate section under it is read like any other expression field, and the outline under that is
  parsed hint by hint. Cells are read by **counting** the separators, never by column offset, so a
  name written in wide characters comes back whole. The plan hash on the header line is replaced —
  it is derived from your plan, and a receiver holding a candidate statement could confirm it by
  matching. Copy a cursor plan from its `Plan hash value:` line: what `DISPLAY_CURSOR` prints above
  that is your statement.
- **A grid row that does not draw the same number of separators as its header is refused.** Oracle
  escapes nothing inside the grid, so a table named `Odd|Table` prints raw — and that character can
  only ever *add* a cell, which is why counting is enough: nothing can be hidden by it, only revealed
  as broken. The practical consequence is that **the standard client at its default line width wraps
  the grid, and a wrapped paste is refused** as *not a readable plan*. Widening the client's line
  setting before you copy is what produces a plan SnippetVeil reads; the message does not say so,
  because naming a cause means having recognised one, and this is the refusal where nothing matched.
  A grid carrying a peeked-bind section is refused on that section's header, and a section
  SnippetVeil has no capture of — the note some plans print, the column projections, the hint report
  — refuses the plan carrying it.
- **An outline hint is parsed against a closed list, and an unknown hint is replaced rather than
  refused.** The hints keep their names, the query blocks the optimizer invented keep theirs, the
  tables and columns inside them are replaced, and `OPTIMIZER_FEATURES_ENABLE('19.1.0')` is kept
  because which optimizer chose the plan is what the section is read for. A hint SnippetVeil does not
  know, or one that does not parse, comes back as a single `str` — that one hint, not the outline and
  not the plan. Treating every hint as one opaque literal was rejected: it leaks nothing and stops
  the outline lining up with the plan it describes.
- **A bind's length is the first field SnippetVeil removes rather than replaces.** In a SQL Monitor
  report the bind list carries the value, its type and its **byte length** — and a length of 7 beside
  a masked city, or 16 beside a masked string, hands back what the mask took away. The length and
  maximum-length attributes are **gone from the output**: not blanked, not replaced, not counted and
  not announced. They are dropped rather than masked because equal values share a placeholder, so two
  binds of equal length would come back carrying one and the same `str` — and length equality would
  be readable off the very mechanism meant to close it. The declared type keeps its name and loses
  its size, `VARCHAR2(32)` coming back as `VARCHAR2`, because the type is what explains an implicit
  conversion and the width explains no plan; the type code and character-set id are kept as printed.
  A reader diffing against a real report will see an attribute missing.
- **A bind's value is typed by the report, not by how it looks.** A numeric bind comes back as the
  number it is; a string, a date, a raw — anything else — comes back as one `str`. Bind names are
  replaced too: `:city` beside a redacted value is the column it filters, spelled out.
- **Oracle's HTML report, its active report, plan-table rows a client rendered as CSV, and SQL
  Monitor's *text* report are refused, and none of them names a fix.** The first two are documents
  rather than pastes and one of them carries its data compressed inside a script; the CSV has thrown
  away the separators the grid argument rests on; and the text report puts your statement in a
  section of its own above a grid with no anchor line.
- **A JPQL query in a Java string is anonymized name by name**, when every name in it resolves — and
  so is a query in the other persistence query languages the IDE reads the same way.
  `@NamedQuery(query = "SELECT c FROM Customer c WHERE c.merchantRef = :ref")` used to come out as
  `query = "str1"`; it now comes out as `query = "SELECT local2 FROM Type1 local2 WHERE local2.field3
  = :local4"`, where `Type1` and `field3` are the placeholders the `Customer` class and its
  `merchantRef` field get everywhere else. A string inside the query is replaced by its own `'str5'`,
  and a comment inside it is stripped and counted like any other comment. A query with any name that
  does not resolve is still replaced whole, and so is every query in an IDE that does not read the
  query language at all — IntelliJ IDEA Community among them, and IntelliJ IDEA without a
  subscription, which does not load the persistence plugin that reads it.
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
- **The first-run notice names the plan action.** It used to say only *Select Java or Kotlin code,
  then right-click → SnippetVeil → Copy Anonymized*, which never leads to an item that reads the
  clipboard rather than a selection. It now goes on: *For an execution plan, copy it, then
  right-click → SnippetVeil → Anonymize Execution Plan…* The status-bar description of that item
  no longer says it reads PostgreSQL plans only.
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

[Unreleased]: https://github.com/snippetveil/snippetveil/compare/v1.5.0...HEAD
[1.5.0]: https://github.com/snippetveil/snippetveil/compare/v1.4.0...v1.5.0
[1.4.0]: https://github.com/snippetveil/snippetveil/compare/v1.3.0...v1.4.0
[1.3.0]: https://github.com/snippetveil/snippetveil/compare/v1.2.0...v1.3.0
[1.2.0]: https://github.com/snippetveil/snippetveil/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/snippetveil/snippetveil/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/snippetveil/snippetveil/commits/v1.0.0
