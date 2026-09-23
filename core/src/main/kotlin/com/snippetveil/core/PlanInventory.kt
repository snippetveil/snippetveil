package com.snippetveil.core

/**
 * **The two closures a plan is read under, stated together** — because a reader who meets either one
 * alone will generalise it into the other's territory.
 *
 * > **Field closure: the vocabulary's *field* set is closed, and a field it does not hold *refuses
 * > the input*.**
 * >
 * > **Token residual: inside a field the vocabulary does hold, an unrecognised *token* anonymizes.**
 *
 * They run in opposite directions and they answer different questions.
 *
 * A **token** arrives inside a slot whose boundaries the engine's own escaping already fixed. The
 * field is known to be an expression; what is in doubt is only whether one word in it is a name. So
 * anonymizing the surprise is safe by construction, and it costs fidelity the user can see. See
 * [scanOf].
 *
 * A **field** arrives with no such warrant. Nothing says whether it holds a name, a magnitude, a
 * hostname or the user's own query — and **refuse is the only answer that does not guess.** The same
 * reasoning that makes the residual safe is exactly what makes field closure necessary: the residual
 * is safe *because* something already decided what the slot is, and for a new field nothing has.
 *
 * **The cost is real and it is accepted here rather than discovered later.** An engine release that
 * adds a field refuses every plan carrying it until a capture and a row in this file follow. The
 * recourse is at the engine — the user re-runs without the option that emitted the new field — and,
 * failing that, at the release: **a vocabulary row is a routine patch, and this container is expected
 * to ship them.** Such a refusal is *not a readable plan*: it is not a recognised refused form and it
 * adds no recourse, because there is nothing for the user to do differently that this product knows
 * about.
 */
internal sealed class PlanTreatment {

    /**
     * **A container**: read the thing inside it against a field set of its own.
     *
     * Per-container rather than one flat set, because the same label means different things in
     * different places — `Inlining` is a boolean under a JIT node's `Options` and a duration under
     * its `Timing` — and a flat set would have to pick one of the two and be wrong about the other.
     *
     * A lambda rather than a map because the inventory is **recursive**: a plan node holds plans.
     */
    class Subtree(val fields: () -> Map<String, PlanTreatment>) : PlanTreatment()

    /** **A name of one of the four kinds** — a relation, a column, a schema, an access path. */
    class Name(val kind: SymbolRole) : PlanTreatment()

    /**
     * **A name written inside the engine's own delimiters, with the closing one doubled inside** —
     * `[Visits]`, `[Vis]]its]`.
     *
     * Its own row rather than a [Name], because **the delimiters are not part of the name and the
     * doubling is not part of it either**. Three things follow, and none of them is true of a [Name]:
     *
     *  - The placeholder is written **inside** the delimiters, so `[Visits]` renders `[table1]` and
     *    the value still reads as the bracketed name the engine printed.
     *  - The key is the **recovered** spelling, so a table named `Vis]its` is one symbol however many
     *    times the engine doubled its bracket — and the same name printed in a plan's XML and in its
     *    text rowset keys together.
     *  - A value that is not exactly one soundly delimited name **refuses the input**. An opener that
     *    never closes is the shape a forged row runs into, and guessing where it ended is the one
     *    thing no reader here does.
     */
    class Bracketed(val kind: SymbolRole) : PlanTreatment()

    /**
     * **A bracketed name the plan itself introduces** — an alias.
     *
     * [Bracketed] is to [Name] what this is to [Declared], and it is a row of its own for the reason
     * [Declared] is: the key differs. See [PlanKeys.declared].
     */
    object BracketedDeclaration : PlanTreatment()

    /**
     * **An expression field in which the engine bracketed every identifier it printed.**
     *
     * Its own row rather than an [Expression], because the two rest on opposite readings of a bare
     * word. An [Expression] is scanned against a vocabulary, and a bare word the vocabulary knows is
     * preserved *as a spelling the engine's quoting rule cannot have given a user's identifier*. Here
     * the quoting rule is stronger and the reading is simpler: **every identifier is delimited, so
     * every undelimited character is the engine's own** and is preserved by not being reported at
     * all. There is no word list, and a release that invents a new keyword costs nothing.
     *
     * What is read out of it is therefore exactly three things — the bracketed names, the quoted
     * literals, and, where the engine wrote one, the object reference that says which of its parts is
     * a schema and which a relation. See [PlanSymbols.readBracketed].
     */
    object BracketedExpression : PlanTreatment()

    /**
     * **A field whose reading depends on whether a *sibling* field is there at all** — and the one
     * rule that says when reading such a thing is allowed.
     *
     * > **A structural discriminator is admissible only when every branch anonymizes.**
     *
     * SQL Server's `<ColumnReference>` is the case: one carrying a `Table` is a column of that table,
     * one without it is a column the optimizer computed. The first is a column name and the second is
     * a name of nothing anybody wrote, so the two take different treatments — and reading the
     * discriminator is safe **because both of them replace what they find**. A discriminator with a
     * preserving branch would be a document deciding, by a shape a user can influence, that something
     * of theirs may be emitted as written; there is no fail-closed version of that, so it is refused
     * here rather than argued about per field. The [init] block is what makes the rule a property of
     * the type instead of a habit each row has to keep.
     *
     * @param by the sibling label whose **presence** picks [present] over [absent]
     */
    class Discriminated(val by: String, val present: PlanTreatment, val absent: PlanTreatment) : PlanTreatment() {

        init {
            require(anonymizes(present) && anonymizes(absent)) {
                "a discriminator on `$by` has a branch that does not anonymize"
            }
        }
    }

    /**
     * **A name the plan itself introduces** — an alias, a CTE, a subplan's own name.
     *
     * Its own row rather than a [Name] of kind `TABLE`, because the key differs: a declared name
     * belongs to **the plan**, and two plans each calling something `v` are not talking about one
     * thing. See [PlanKeys.declared].
     */
    object Declared : PlanTreatment()

    /** **An expression field, handed to the lexer** — where the token residual runs. See [scanOf]. */
    object Expression : PlanTreatment()

    /**
     * **A qualified reference, split by `.` and read by position** — `schema.alias.column`.
     *
     * Its own row rather than an [Expression], because the two are protected by opposite arguments.
     * An expression field is scanned, and the residual is safe *because* the slot's boundaries were
     * fixed by the engine's escaping. A reference field is not scanned at all: it is a fixed number
     * of parts, and **a split that does not give exactly that many refuses the input.**
     *
     * The guard is a name with a `.` in it. An alias a user wrote as `a.b` is printed into a
     * reference with nothing around it, so the reference has one part more than it should — and the
     * reader that shrugged and took the last part as the column would have read somebody's alias as
     * a relation and their relation as a schema. Counting is the only thing here that can tell the
     * two apart, so counting is what decides.
     *
     * @param kinds what each part is, in order — as many entries as the reference has parts
     */
    class Reference(val kinds: List<SymbolRole>) : PlanTreatment()

    /**
     * **A rendered line, cross-checked against a template instantiated from the node's own fields.**
     *
     * The line is prose the engine assembled, and it is **parsed against the templates rather than
     * read as prose**: each template is instantiated from the sibling fields of the node the line
     * belongs to, and the line has to equal exactly one of the results. A line that matches none
     * refuses.
     *
     * **Nothing here ever scans the line for something name-shaped**, and that is the whole reason
     * the row exists. A scanner would have to decide, out of a sentence, which words are the user's —
     * on a line where the engine's own keywords are spelled exactly as a user's table could be. The
     * template says where each name is because the engine put it there, and every character the
     * template did not account for is the engine's own.
     *
     * Each part the template took from a field is then read **under that field's own row**, so a name
     * in the line takes the placeholder its field takes and a masked value in the line is masked.
     *
     * @param templates the fixed set the line is assembled from. See [PlanTemplate].
     */
    class Rendered(val templates: List<PlanTemplate>) : PlanTreatment()

    /**
     * **A field the engine writes without escaping it** — admitted only for the values the engine
     * itself can produce, and **an off-list value refuses the input.**
     *
     * Refusing rather than masking, which is the opposite of what [Fact] does with a surprise, and
     * the difference is the escaping. A [Fact] arrives inside delimiters the engine maintained, so a
     * value nobody recognises is at worst a value: mask it and nothing leaks. An unescaped field's
     * value was written into the document **with no delimiter this product can trust**, so a value
     * that is not one the engine writes may have closed its own slot and **forged its siblings** —
     * and a mask cannot see a sibling that was never real. There is nothing safe to keep, so nothing
     * is kept.
     *
     * @param admits whether the engine itself can print this value — a closed list per engine
     *   release, or the shape that release's writer is limited to
     */
    class Unescaped(val admits: (String) -> Boolean) : PlanTreatment()

    /**
     * **A field the engine assembled with names appended raw: one redacted literal, whole.**
     *
     * The same output as [PlanTreatments.unreadable] and a different reason, which is why it is a row
     * of its own: nothing failed to parse here. The field is a rendered condition, and the engine
     * that rendered it put every identifier in bare — so there is no delimiter to read a name's
     * boundary from, and no quoting rule to preserve a bare word under. Scanning it would be the
     * prose-reading that [Rendered] exists to refuse, one layer down.
     */
    object AppendedRaw : PlanTreatment()

    /** **A measured quantity, preserved as printed.** See [PlanTreatments.measured]. */
    object Measured : PlanTreatment()

    /** **An engine fact, preserved behind a shape check.** See [PlanTreatments.engineFact]. */
    class Fact(val shape: Regex) : PlanTreatment()

    /** **The user's own statement text: one redacted literal.** See [PlanTreatments.echoedQuery]. */
    object EchoedQuery : PlanTreatment()

    /** **A parameter list, each value a literal.** See [PlanTreatments.parameters]. */
    object Parameters : PlanTreatment()

    /** **A value that identifies rather than describes, masked.** See [PlanTreatments.identifying]. */
    object Identifying : PlanTreatment()

    /** **A deployment or object identifier, masked.** See [PlanTreatments.deployment]. */
    object Deployment : PlanTreatment()

    /**
     * **The settings map, typed by key** — the one field whose *keys* carry a rule.
     *
     * It is a treatment rather than a [Subtree] because its key set is **open where every other one
     * is closed**: an unknown field refuses, and an unknown *setting* is masked. See
     * [PlanStructureReader.readSettings] for why the two differ.
     */
    object SettingsMap : PlanTreatment()
}

/**
 * Whether [treatment] replaces whatever it is given — every name kind, every declaration, and every
 * class that masks. See [PlanTreatment.Discriminated] for the rule this answers.
 *
 * **A second cascade over the sealed type, and a deliberate one.** [PlanSymbols.readSlot] is still
 * the only cascade that *reads* anything; this one reads nothing and decides nothing about a slot —
 * it answers a question about the **class**, asked once at construction. A property on each class
 * would spread that answer over twenty declarations, and *which treatments may emit what they were
 * given* is exactly the kind of question a reviewer wants answered in one list.
 *
 * It has no default arm, so a treatment class added later has to be placed here rather than falling
 * quietly onto one side of the question.
 */
private fun anonymizes(treatment: PlanTreatment): Boolean = when (treatment) {
    is PlanTreatment.Name, is PlanTreatment.Bracketed, is PlanTreatment.Reference,
    PlanTreatment.Declared, PlanTreatment.BracketedDeclaration,
    PlanTreatment.EchoedQuery, PlanTreatment.Identifying, PlanTreatment.Deployment,
    PlanTreatment.AppendedRaw,
    -> true

    is PlanTreatment.Discriminated -> anonymizes(treatment.present) && anonymizes(treatment.absent)

    // Everything else can emit what it was given: a fact and an unescaped value survive their check,
    // a measurement is preserved whole, a scanned field preserves the tokens its vocabulary knows,
    // and a container is not a value at all.
    is PlanTreatment.Subtree, is PlanTreatment.Fact, is PlanTreatment.Unescaped,
    is PlanTreatment.Rendered, PlanTreatment.Expression, PlanTreatment.BracketedExpression,
    PlanTreatment.Measured, PlanTreatment.Parameters, PlanTreatment.SettingsMap,
    -> false
}

/**
 * **PostgreSQL's field inventory, for the three structured formats** — the whole of what this
 * product will read out of a JSON, YAML or XML plan, and therefore the whole of what it accepts.
 *
 * One inventory for the three, because they print **the same tree**: the labels are identical in
 * JSON and YAML, and XML writes each of them through [xmlTagOf]. Three inventories would be one
 * argument re-made three times, and the third copy is where the row goes missing.
 *
 * The top level is one query, which is what each format's document reader hands back: JSON's array
 * members, YAML's sequence items, XML's `<Query>` elements.
 */
internal val POSTGRES_QUERY_FIELDS: Map<String, PlanTreatment> = mapOf(
    "Plan" to PlanTreatment.Subtree { POSTGRES_PLAN_FIELDS },
    "Query Text" to PlanTreatment.EchoedQuery,
    "Query Parameters" to PlanTreatment.Parameters,
    "Query Identifier" to PlanTreatment.Identifying,
    "Planning" to PlanTreatment.Subtree { POSTGRES_BUFFER_FIELDS },
    "Planning Time" to PlanTreatment.Measured,
    "Execution Time" to PlanTreatment.Measured,
    "Total Runtime" to PlanTreatment.Measured,
    "Triggers" to PlanTreatment.Subtree { POSTGRES_TRIGGER_FIELDS },
    "JIT" to PlanTreatment.Subtree { POSTGRES_JIT_FIELDS },
    "Settings" to PlanTreatment.SettingsMap,
)

/**
 * **The buffer and write-ahead-log counters**, which one node, a worker and the planning phase all
 * print alike — so they are one row set read from three places rather than three copies.
 */
internal val POSTGRES_BUFFER_FIELDS: Map<String, PlanTreatment> = listOf(
    "Shared Hit Blocks", "Shared Read Blocks", "Shared Dirtied Blocks", "Shared Written Blocks",
    "Local Hit Blocks", "Local Read Blocks", "Local Dirtied Blocks", "Local Written Blocks",
    "Temp Read Blocks", "Temp Written Blocks", "I/O Read Time", "I/O Write Time",
    "Shared I/O Read Time", "Shared I/O Write Time", "Local I/O Read Time", "Local I/O Write Time",
    "Temp I/O Read Time", "Temp I/O Write Time", "WAL Records", "WAL FPI", "WAL Bytes",
    "WAL Buffers Full",
).associateWith { PlanTreatment.Measured }

/**
 * **One plan node** — the largest field set in the inventory, and the one a new engine release is
 * most likely to add to.
 *
 * The names are what this file exists for; everything else is here so that the closure can be closed
 * at all. A row is a statement that *this slot holds that kind of thing*, and the kinds are
 * [PlanTreatment]'s.
 */
internal val POSTGRES_PLAN_FIELDS: Map<String, PlanTreatment> = buildMap {
    // **The names.** A relation, its schema and the access path over it are the invocation's;
    // an alias, a CTE and a subplan's own name are the plan's. See [PlanKeys].
    put("Relation Name", PlanTreatment.Name(SymbolRole.TABLE))
    put("Schema", PlanTreatment.Name(SymbolRole.SCHEMA))
    put("Index Name", PlanTreatment.Name(SymbolRole.INDEX))
    put("Conflict Arbiter Indexes", PlanTreatment.Name(SymbolRole.INDEX))
    // A function scan's function occupies the `on` position a relation would, and is read as the
    // rowset it stands in for — the same reading the text format gives it.
    put("Function Name", PlanTreatment.Name(SymbolRole.TABLE))
    put("Alias", PlanTreatment.Declared)
    put("CTE Name", PlanTreatment.Declared)
    put("Tuplestore Name", PlanTreatment.Declared)
    put("Subplan Name", PlanTreatment.Declared)

    // **The engine's own enums and booleans**, preserved behind a shape check — and masked rather
    // than refused when a release changes one, which is a compatibility event this container is
    // expected to survive.
    for (label in listOf(
        "Node Type", "Strategy", "Partial Mode", "Operation", "Join Type", "Scan Direction",
        "Parent Relationship", "Conflict Resolution", "Cache Mode", "Sampling Method",
    )) {
        put(label, PlanTreatment.Fact(PlanShapes.ENGINE_ENUM))
    }
    for (label in listOf("Parallel Aware", "Async Capable", "Inner Unique", "Single Copy")) {
        put(label, PlanTreatment.Fact(PlanShapes.BOOLEAN))
    }

    // **The engine's enums that arrive as strings.** They are facts rather than measurements, and
    // the difference is not cosmetic: a measured field reports nothing at all, so a surprise in one
    // of these would be emitted verbatim. The text format keeps them measured, because there one of
    // these labels heads a **row of several measurements** — `Sort Method: quicksort  Memory: 25kB`
    // — which no shape covers and which a mask would take the numbers out of.
    for (label in listOf("Sort Method", "Sort Space Type", "Sort Methods Used", "Storage")) {
        put(label, PlanTreatment.Fact(PlanShapes.ENGINE_ENUM))
    }

    // An extension's custom-scan provider names the deployment rather than anything in the data, and
    // a private extension's name is exactly the kind of string this product exists to replace.
    put("Custom Plan Provider", PlanTreatment.Deployment)

    // The statement a foreign scan is about to send to another server — the user's own SQL, printed
    // by every format and not only by the text one.
    put("Remote SQL", PlanTreatment.EchoedQuery)

    // **The expression fields**, where the token residual runs. See [scanOf].
    for (label in listOf(
        "Output", "Filter", "Index Cond", "Recheck Cond", "TID Cond", "Join Filter", "Hash Cond",
        "Merge Cond", "One-Time Filter", "Conflict Filter", "Sort Key", "Presorted Key", "Group Key",
        "Hash Key", "Cache Key", "Function Call", "Order By", "Sampling Parameters",
        "Repeatable Seed", "Group Keys", "Hash Keys",
    )) {
        put(label, PlanTreatment.Expression)
    }

    // **The measured quantities** — the numbers a plan is pasted *for*, preserved exactly as
    // printed and reported as nothing at all. See [PlanTreatments.measured].
    for (label in listOf(
        "Startup Cost", "Total Cost", "Plan Rows", "Plan Width", "Actual Startup Time",
        "Actual Total Time", "Actual Rows", "Actual Loops", "Disabled Nodes", "Subplans Removed",
        "Rows Removed by Filter", "Rows Removed by Index Recheck", "Rows Removed by Join Filter",
        "Rows Removed by Conflict Filter", "Heap Fetches", "Exact Heap Blocks", "Lossy Heap Blocks",
        "Index Searches", "Sort Space Used",
        "Workers Planned", "Workers Launched", "Hash Buckets", "Original Hash Buckets",
        "Hash Batches", "Original Hash Batches", "Peak Memory Usage", "Disk Usage", "Cache Hits",
        "Cache Misses", "Cache Evictions", "Cache Overflows", "Maximum Storage",
        // What a write node counted, including the four a `MERGE` reports separately.
        "Tuples Inserted", "Conflicting Tuples", "Tuples Updated", "Tuples Deleted", "Tuples Skipped",
        "Worker Number",
    )) {
        put(label, PlanTreatment.Measured)
    }
    putAll(POSTGRES_BUFFER_FIELDS)

    // **The containers a node holds** — each with the **singular** the XML printer names a member
    // of one by. JSON and YAML write a list as a list; XML writes `<Plans><Plan>…</Plan></Plans>`,
    // so the member's own name is a row like any other and the closure would refuse it without one.
    put("Plans", PlanTreatment.Subtree { POSTGRES_PLAN_FIELDS })
    put("Plan", PlanTreatment.Subtree { POSTGRES_PLAN_FIELDS })
    put("Workers", PlanTreatment.Subtree { POSTGRES_PLAN_FIELDS })
    put("Worker", PlanTreatment.Subtree { POSTGRES_PLAN_FIELDS })
    put("JIT", PlanTreatment.Subtree { POSTGRES_JIT_FIELDS })
    put("Grouping Sets", PlanTreatment.Subtree { POSTGRES_PLAN_FIELDS })
    put("Grouping Set", PlanTreatment.Subtree { POSTGRES_PLAN_FIELDS })
    put("Full-sort Groups", PlanTreatment.Subtree { POSTGRES_SORT_GROUP_FIELDS })
    put("Pre-sorted Groups", PlanTreatment.Subtree { POSTGRES_SORT_GROUP_FIELDS })
}

/** **One fired trigger**, whose two names are database objects outside the four name kinds. */
internal val POSTGRES_TRIGGER_FIELDS: Map<String, PlanTreatment> = mapOf(
    "Trigger" to PlanTreatment.Subtree { POSTGRES_TRIGGER_FIELDS },
    "Trigger Name" to PlanTreatment.Deployment,
    "Constraint Name" to PlanTreatment.Deployment,
    "Relation" to PlanTreatment.Name(SymbolRole.TABLE),
    "Time" to PlanTreatment.Measured,
    "Calls" to PlanTreatment.Measured,
)

/** **The just-in-time compiler's report** — counts, the options it ran under, and its timings. */
internal val POSTGRES_JIT_FIELDS: Map<String, PlanTreatment> = mapOf(
    "Worker Number" to PlanTreatment.Measured,
    "Functions" to PlanTreatment.Measured,
    "Options" to PlanTreatment.Subtree { POSTGRES_JIT_OPTION_FIELDS },
    "Timing" to PlanTreatment.Subtree { POSTGRES_JIT_TIMING_FIELDS },
)

/** The compiler's switches, which are the engine's own booleans. */
internal val POSTGRES_JIT_OPTION_FIELDS: Map<String, PlanTreatment> =
    listOf("Inlining", "Optimization", "Expressions", "Deforming")
        .associateWith { PlanTreatment.Fact(PlanShapes.BOOLEAN) }

/** The compiler's timings, which are measurements under the same labels its switches use. */
internal val POSTGRES_JIT_TIMING_FIELDS: Map<String, PlanTreatment> =
    listOf("Generation", "Inlining", "Optimization", "Emission", "Total")
        .associateWith { PlanTreatment.Measured }

/** An incremental sort's group statistics. */
internal val POSTGRES_SORT_GROUP_FIELDS: Map<String, PlanTreatment> = mapOf(
    "Group Count" to PlanTreatment.Measured,
    "Sort Methods Used" to PlanTreatment.Measured,
    "Sort Space Memory" to PlanTreatment.Subtree { POSTGRES_SORT_SPACE_FIELDS },
    "Sort Space Disk" to PlanTreatment.Subtree { POSTGRES_SORT_SPACE_FIELDS },
)

internal val POSTGRES_SORT_SPACE_FIELDS: Map<String, PlanTreatment> =
    listOf("Average Sort Space Used", "Peak Sort Space Used").associateWith { PlanTreatment.Measured }

/**
 * **PostgreSQL's field inventory for the text format** — flat, because the text format's structure is
 * indentation rather than nesting and a label means the same thing at every depth.
 *
 * It is a separate inventory from [POSTGRES_QUERY_FIELDS] and not a projection of it, because the
 * two formats print **different field sets**: the text format writes a node's type as the line's own
 * label rather than as a field, folds `Buckets`, `Batches` and `Memory Usage` onto one row, and
 * prints three rows the structured formats do not print at all. See [TEXT_RAW_NAME_ROWS] for those.
 */
internal val POSTGRES_TEXT_FIELDS: Map<String, PlanTreatment> = buildMap {
    put("Subplan Name", PlanTreatment.Declared)

    for (label in listOf(
        "Output", "Filter", "Index Cond", "Recheck Cond", "TID Cond", "Join Filter", "Hash Cond",
        "Merge Cond", "One-Time Filter", "Conflict Filter", "Sort Key", "Presorted Key", "Group Key",
        "Hash Key", "Cache Key", "Function Call", "Order By", "Sampling",
    )) {
        put(label, PlanTreatment.Expression)
    }

    put("Single Copy", PlanTreatment.Fact(PlanShapes.BOOLEAN))
    put("Conflict Resolution", PlanTreatment.Fact(PlanShapes.ENGINE_ENUM))
    put("Cache Mode", PlanTreatment.Fact(PlanShapes.ENGINE_ENUM))

    put("Query Text", PlanTreatment.EchoedQuery)
    put("Remote SQL", PlanTreatment.EchoedQuery)
    put("Query Parameters", PlanTreatment.Parameters)
    put("Query Identifier", PlanTreatment.Identifying)

    for (label in listOf(
        "Planning Time", "Execution Time", "Planning", "Execution", "Total Runtime", "Buffers",
        "I/O Timings", "WAL", "Sort Method", "Sort Space Used", "Sort Space Type",
        "Sort Methods Used", "Sort Space Memory", "Average Sort Space Used", "Peak Sort Space Used",
        "Group Count", "Workers Planned", "Workers Launched", "Worker", "Heap Blocks",
        "Exact Heap Blocks", "Lossy Heap Blocks", "Buckets", "Batches", "Memory Usage",
        "Peak Memory Usage", "Disk Usage", "Rows Removed by Filter", "Rows Removed by Index Recheck",
        "Rows Removed by Join Filter", "Rows Removed by Conflict Filter", "Functions", "Options",
        "Timing", "JIT", "Full-sort Groups", "Pre-sorted Groups", "Hits", "Misses", "Evictions",
        "Overflows", "Storage", "Maximum Storage", "Tuples Inserted", "Conflicting Tuples",
        "Heap Fetches", "Index Searches", "Subplans Removed", "Disabled Nodes", "Time", "Calls",
        "Tuples Updated", "Tuples Deleted", "Tuples Skipped",
    )) {
        put(label, PlanTreatment.Measured)
    }
}

/**
 * **The rows the text format prints with a raw, unquoted name in them** — a closed list, and the one
 * refusal in this product that hands the user a better option instead of a verdict.
 *
 * Every name a plan's text output prints goes through `quote_identifier()` **except** on these rows.
 * A comma-separated list of index names is printed bare, so an index named `a,b` cannot be told from
 * two indexes; a trigger row writes its trigger and its relation into a sentence with no delimiters
 * at all; and `Settings:` writes each value in single quotes **without escaping a single quote
 * inside one**, so a value carrying one runs into the next field.
 *
 * None of that can be recovered soundly from the text, and **the engine already offers a form where
 * it can be**: the same plan under `FORMAT JSON` escapes every one of them. So this is a *recognised
 * refused shape* rather than an unreadable one, and the message says so — see [PlanRecourse].
 *
 * **The list is closed, and anything outside it falls to the general refusal.** A row this product
 * has never seen is not a row it knows a better form for.
 */
internal val TEXT_RAW_NAME_ROWS: List<String> = listOf("Settings:", "Conflict Arbiter Indexes:", "Trigger ")

/**
 * **The core settings PostgreSQL flags for `EXPLAIN (SETTINGS)`** — the per-version vocabulary row
 * that decides which settings are preserved and which are masked.
 *
 * `EXPLAIN (SETTINGS)` prints every `GUC_EXPLAIN` setting whose value differs from its built-in
 * default. Which settings carry that flag is **a fact about an engine release**, so this is a
 * vocabulary row like any other: a newly flagged setting is a compatibility event, and it is
 * **masked until the row follows** rather than refusing the plan — a settings map is typed by key
 * and its key set is the one open set in the inventory. See [PlanStructureReader.readSettings].
 *
 * `search_path` is deliberately absent: its value is a list of **schema names**, which is a rule of
 * its own rather than an engine fact. See [searchPathIn].
 */
internal val POSTGRES_FLAGGED_SETTINGS: Set<String> = setOf(
    // The planner's method switches.
    "enable_async_append", "enable_bitmapscan", "enable_gathermerge", "enable_group_by_reordering",
    "enable_hashagg", "enable_hashjoin", "enable_incremental_sort", "enable_indexonlyscan",
    "enable_indexscan", "enable_material", "enable_memoize", "enable_mergejoin", "enable_nestloop",
    "enable_parallel_append", "enable_parallel_hash", "enable_partition_pruning",
    "enable_partitionwise_aggregate", "enable_partitionwise_join", "enable_presorted_aggregate",
    "enable_seqscan", "enable_sort", "enable_tidscan",
    // The cost model.
    "cpu_index_tuple_cost", "cpu_operator_cost", "cpu_tuple_cost", "cursor_tuple_fraction",
    "effective_cache_size", "jit_above_cost", "jit_inline_above_cost", "jit_optimize_above_cost",
    "min_parallel_index_scan_size", "min_parallel_table_scan_size", "parallel_setup_cost",
    "parallel_tuple_cost", "random_page_cost", "recursive_worktable_factor", "seq_page_cost",
    // Memory, parallelism and the rest of what changes a plan.
    "default_statistics_target", "effective_io_concurrency", "from_collapse_limit", "geqo",
    "geqo_effort", "geqo_generations", "geqo_pool_size", "geqo_seed", "geqo_selection_bias",
    "geqo_threshold", "hash_mem_multiplier", "join_collapse_limit", "jit", "maintenance_io_concurrency",
    "max_parallel_workers", "max_parallel_workers_per_gather", "parallel_leader_participation",
    "plan_cache_mode", "constraint_exclusion", "quote_all_identifiers", "temp_buffers", "work_mem",
    "debug_parallel_query", "force_parallel_mode",
)

/**
 * **Every row of a nested inventory, flattened** — each label once, with the treatment it first
 * takes.
 *
 * It exists so that a statement about *the inventory* can be made in one place rather than per
 * container: the XML spelling table below is built from it, and the test that every row has a
 * fixture exercising it reads it. Containers are visited by identity, so the inventory's recursion —
 * a plan node holds plan nodes — terminates.
 */
internal fun flattenedFieldsOf(root: Map<String, PlanTreatment>): Map<String, PlanTreatment> {
    val flat = LinkedHashMap<String, PlanTreatment>()
    val visited = mutableListOf<Map<String, PlanTreatment>>()

    fun walk(fields: Map<String, PlanTreatment>) {
        if (visited.any { it === fields }) return
        visited += fields
        for ((label, treatment) in fields) {
            flat.putIfAbsent(label, treatment)
            if (treatment is PlanTreatment.Subtree) walk(treatment.fields())
        }
    }

    walk(root)
    return flat
}

/**
 * **How the XML printer spells each label in the inventory**, read backwards — from the tag on the
 * page to the label the inventory is written in.
 *
 * Backwards because [xmlTagOf] is not invertible: it writes every character outside `A-Za-z0-9-_.`
 * as `-`, so nothing can reconstruct a label from a tag. Putting the *known* labels through the
 * transform is what lets one inventory serve all four formats, and a tag that is no label's image is
 * an unknown field like any other.
 */
internal val POSTGRES_XML_LABELS: Map<String, String> =
    flattenedFieldsOf(POSTGRES_QUERY_FIELDS).keys.associateBy { xmlTagOf(it) }

/** The settings key whose value is a list of schema names rather than an engine fact. */
internal const val SEARCH_PATH = "search_path"

/**
 * **The one `search_path` element that survives verbatim**, and it survives **by the value grammar**
 * rather than by appearing on a list of known spellings.
 *
 * PostgreSQL always substitutes the session user for a `search_path` element written exactly
 * `"$user"`, so **no schema can be named onto the path by that spelling** — the engine takes the
 * quoted form for the substitution before any schema of that name could be reached. There is
 * therefore nothing of the user's in it to withhold, and that is a statement about how the value is
 * read rather than about what happens to be on a list.
 */
internal const val SESSION_USER_ELEMENT = "\$user"
