package com.snippetveil.core

/**
 * **An operation as this printer writes one** — `TABLE ACCESS BY INDEX ROWID BATCHED`,
 * `PX SEND QC (RANDOM)`, `MAT_VIEW ACCESS FULL`, `INDEX RANGE SCAN (MIN/MAX)`.
 *
 * A shape rather than a list of the two hundred spellings Oracle prints, for the reason SQL Server's
 * operator vocabulary is a class rather than a list: an operation this product has never seen is as
 * safe to read as one it has, because **the cell it sits in is fixed by the grid** and nothing in it
 * reaches a name. What the shape catches is a cell that stopped looking like an operation at all, and
 * an off-shape value is masked rather than refused — a compatibility event this container is expected
 * to survive.
 */
internal val ORACLE_OPERATION = Regex("""[A-Z][A-Z0-9_]*( [A-Z0-9_()*/'-]+)*""")

/** The status of an execution, which is one of this engine's own spellings. */
internal val ORACLE_STATUS = Regex("""[A-Z][A-Z ]*(\([A-Z ]+\))?""")

/**
 * **The type codes Oracle numbers its numeric types with** — `NUMBER`, and the two machine floats.
 *
 * A closed list, and the one branch of the bind's value that can emit anything as written. Everything
 * outside it — a string, a date, an interval, a LOB, a raw — takes the redacted-literal family, which
 * is the fail-closed direction: a code this list does not hold is a type this product has not seen,
 * and a value of an unseen type is not a number.
 */
internal val ORACLE_NUMERIC_TYPE_CODES: Set<String> = setOf("2", "100", "101")

/**
 * **Oracle's field inventories** — the columns of its grid text output, the entries of its SQL
 * Monitor XML report, and the hint grammar its outline is read against.
 *
 * ### Written from a reading of the engine's output, not from a capture in this repository
 *
 * The rows here are what Oracle's own printers are understood to write for the shapes this product
 * has been shown, and **no default-settings capture of them was taken in this repository**. That is
 * the same gap MySQL's and SQL Server's inventories record, and it is recorded here for the same
 * reason: the field closure is what makes being wrong about a row survivable — a field with no row
 * **refuses the plan carrying it** rather than being read under a guess — but it is not what makes
 * being wrong about one *invisible*. A capture is what would close it.
 *
 * ### Two shapes, and the drop rule arrives in the second
 *
 *  - **The grid text output** is columns, read by counting separators. The column vocabulary below is
 *    what says a cell holds a name, an operation or a measurement, and a column it does not hold
 *    refuses the plan.
 *  - **The SQL Monitor XML report** carries the bind list, and a bind is where a number that varies
 *    with a masked value sits beside it. See [PlanTreatment.Dropped].
 *
 * ### What is deliberately absent, and therefore refuses
 *
 * The parallel columns — `TQ`, `IN-OUT`, `PQ Distrib` — and the partition columns `Pstart` and
 * `Pstop` have **no row here**, so a parallel or partitioned plan refuses until a capture and a row
 * follow. They are not hard to guess at, and guessing is exactly what a vocabulary row may not be:
 * the cost of the omission is a refusal the user can act on, and the cost of a wrong row is a cell
 * read under a rule nobody checked.
 */
internal val ORACLE_GRID_COLUMNS: Map<String, PlanTreatment> = buildMap {
    // **The operation**, which is the engine's own enumeration and the whole of what the tree says.
    // Its indentation is layout: the shape is matched against the trimmed cell, so the spaces the
    // printer draws depth with survive by not being reported at all.
    put(OPERATION_COLUMN, PlanTreatment.Fact(ORACLE_OPERATION))

    // **The object the operation names.** Which *kind* of name it is, is decided by the operation
    // beside it — see [oracleNameKindOf] — which is a sibling read admitted under the rule
    // [PlanTreatment.Discriminated] states: both branches anonymize, so nothing a user can influence
    // decides that something of theirs may be emitted as written. The row holds the kind a row with
    // no operation cell at all would take.
    put(NAME_COLUMN, PlanTreatment.Name(SymbolRole.TABLE))

    // **The measurements** — the estimates, the actuals, the memory and the timings. A plan is
    // pasted *for* these, and every one of them is reported as nothing at all.
    for (column in listOf(
        "Id", "Rows", "Bytes", "TempSpc", "Cost (%CPU)", "Cost", "Time", "E-Rows", "A-Rows", "A-Time",
        "Starts", "Buffers", "Reads", "Writes", "OMem", "1Mem", "O/1/M", "Used-Mem", "Used-Tmp",
    )) {
        put(column, PlanTreatment.Measured)
    }
}

/** The column whose cell says what the row's operation is. See [ORACLE_GRID_COLUMNS]. */
internal const val OPERATION_COLUMN = "Operation"

/** The column whose cell names the object the row's operation works on. */
internal const val NAME_COLUMN = "Name"

/**
 * **What kind of name the `Name` cell of a row running [operation] holds** — an access path, or a
 * rowset.
 *
 * Oracle writes a table, a view, a materialized view and an index into one column, and the operation
 * beside it is what says which. The rule is the operation's **own first words**, which is text the
 * printer produced: an operation that opens `INDEX` or `BITMAP INDEX` works on an access path, and
 * everything else works on a rowset.
 *
 * **Both answers replace what they find**, which is what makes reading the sibling admissible at all
 * — the rule [PlanTreatment.Discriminated] states, applied to a grid. Reading them all as rowsets was
 * refused for the reason [SymbolRole.INDEX] gives: `INDEX RANGE SCAN` of `table5` asserts a rowset
 * where there is an access path, and a reader mapping the reply back would look for a table that does
 * not exist.
 */
internal fun oracleNameKindOf(operation: String): SymbolRole {
    val written = operation.trim()
    return if (written.startsWith(INDEX_OPERATION) || written.startsWith(BITMAP_INDEX_OPERATION)) {
        SymbolRole.INDEX
    } else {
        SymbolRole.TABLE
    }
}

/** The two openings that say a row's object is an access path. See [oracleNameKindOf]. */
private const val INDEX_OPERATION = "INDEX"

private const val BITMAP_INDEX_OPERATION = "BITMAP INDEX"

/**
 * **Oracle's SQL Monitor XML report** — the whole of what this product accepts out of a document of
 * this engine's, and the one document in this product carrying a field that is **removed**.
 *
 * The report describes one execution: what ran, what it bound, and the plan it ran. The content is in
 * the attributes, as SQL Server's is, and in the **element content** besides — a bind's value sits
 * inside the element that describes it, which is what [contentXmlDocumentIn] absorbs and what
 * [XML_CHARACTER_DATA] is the label for.
 */
internal val ORACLE_MONITOR_FIELDS: Map<String, PlanTreatment> = mapOf(
    "db_version" to PlanTreatment.Fact(PlanShapes.VERSION),
    "report_id" to PlanTreatment.Identifying,
    "report_parameters" to PlanTreatment.Subtree { ORACLE_REPORT_PARAMETER_FIELDS },
    "sql_monitor_report" to PlanTreatment.Subtree { ORACLE_MONITOR_REPORT_FIELDS },
)

/** What the report says it was asked for — the statement it is about, and how much of it to print. */
internal val ORACLE_REPORT_PARAMETER_FIELDS: Map<String, PlanTreatment> = mapOf(
    // **The statement id is masked**, for the reason every plan hash in this product is: it is
    // derived from the statement text, so preserving one lets a receiver **confirm a guessed query**
    // — which undoes the rule that has the statement itself redacted.
    "sql_id" to PlanTreatment.Identifying,
    "type" to PlanTreatment.Fact(PlanShapes.ENGINE_TOKEN),
    "report_level" to PlanTreatment.Fact(PlanShapes.ENGINE_TOKEN),
)

/**
 * **One monitored execution** — its status, what it was run by, the statement, the binds and the
 * plan.
 *
 * Who ran it is **deployment** rather than a name: a database user, a service and a module are
 * organization-identifying strings that join no name identity, which is the reading
 * [PlanTreatments.deployment] exists for.
 */
internal val ORACLE_MONITOR_REPORT_FIELDS: Map<String, PlanTreatment> = mapOf(
    "version" to PlanTreatment.Fact(PlanShapes.VERSION),
    "status" to PlanTreatment.Fact(ORACLE_STATUS),
    "sql_id" to PlanTreatment.Identifying,
    "sql_exec_id" to PlanTreatment.Measured,
    "sql_exec_start" to PlanTreatment.Measured,
    "user" to PlanTreatment.Deployment,
    "module" to PlanTreatment.Deployment,
    "service" to PlanTreatment.Deployment,
    "instance_id" to PlanTreatment.Measured,
    "session_id" to PlanTreatment.Measured,
    // The statement the report is about, written back verbatim: one redacted literal, never parsed.
    "sql_fulltext" to PlanTreatment.EchoedQuery,
    "binds" to PlanTreatment.Subtree { ORACLE_BINDS_FIELDS },
    "stats" to PlanTreatment.Subtree { ORACLE_STATS_FIELDS },
    "plan" to PlanTreatment.Subtree { ORACLE_PLAN_FIELDS },
)

internal val ORACLE_BINDS_FIELDS: Map<String, PlanTreatment> =
    mapOf("bind" to PlanTreatment.Subtree { ORACLE_BIND_FIELDS })

/**
 * **One bind: the value the statement bound, and everything the report says about it** — the field
 * set the drop rule was written for.
 *
 * Four rows and four different answers, and the rule that tells them apart is one sentence:
 *
 * > **A number that varies with a value SnippetVeil masks is dropped. A number describing the slot
 * > the value sits in is preserved.**
 *
 *  - **`len` and `maxlen` are dropped.** A length beside a masked value narrows it: a length of 7
 *    next to `city = :city` is a short list of cities, and 16 next to a masked string is a card
 *    number. Dropped rather than masked because equal spellings share a token — two binds of equal
 *    length would take **the same placeholder**, and the receiver would read length equality off the
 *    mechanism meant to close it. See [PlanTreatment.Dropped].
 *  - **`dtystr` keeps its type name and loses its size.** The type explains an implicit conversion,
 *    which is a top-tier reason to read a plan; the declared width explains no plan. See
 *    [PlanTreatment.TypeName].
 *  - **`dty` and `csid` are preserved as engine facts.** A type code and a character-set id are the
 *    engine's own numbering, and they carry the fact a plan reader needs — the bind's **type** —
 *    whole.
 *  - **`name` is masked**, because a bind's name is routinely the column it filters spelled out.
 *
 * And the value itself is **typed by the bind's type code** rather than by lexing it: a numeric bind
 * is a number where it lexes as one, and everything else — a string, a date, an interval, a raw — is
 * one redacted literal. See [PlanTreatment.Typed] for why reading a sibling's value is admitted here.
 */
internal val ORACLE_BIND_FIELDS: Map<String, PlanTreatment> = mapOf(
    "name" to PlanTreatment.BoundName,
    "pos" to PlanTreatment.Measured,
    "dty" to PlanTreatment.Fact(PlanShapes.COUNT),
    "dtystr" to PlanTreatment.TypeName,
    "maxlen" to PlanTreatment.Dropped,
    "len" to PlanTreatment.Dropped,
    "csid" to PlanTreatment.Fact(PlanShapes.COUNT),
    XML_CHARACTER_DATA to PlanTreatment.Typed(
        by = "dty",
        branches = ORACLE_NUMERIC_TYPE_CODES.associateWith { PlanTreatment.BoundNumber },
        otherwise = PlanTreatment.BoundValue,
    ),
)

/** The statistics the report prints for one execution, each a magnitude under its own name. */
internal val ORACLE_STATS_FIELDS: Map<String, PlanTreatment> = mapOf(
    "type" to PlanTreatment.Fact(PlanShapes.ENGINE_TOKEN),
    "stat" to PlanTreatment.Subtree { ORACLE_STAT_FIELDS },
)

internal val ORACLE_STAT_FIELDS: Map<String, PlanTreatment> = mapOf(
    "name" to PlanTreatment.Fact(PlanShapes.ENGINE_TOKEN),
    XML_CHARACTER_DATA to PlanTreatment.Measured,
)

/**
 * **The plan the execution ran**, as a tree of operations — the same tree the grid text output draws
 * with `|` and dashes, with every part of it in a slot of its own.
 */
internal val ORACLE_PLAN_FIELDS: Map<String, PlanTreatment> =
    mapOf("operation" to PlanTreatment.Subtree { ORACLE_OPERATION_FIELDS })

internal val ORACLE_OPERATION_FIELDS: Map<String, PlanTreatment> = mapOf(
    "id" to PlanTreatment.Measured,
    "name" to PlanTreatment.Fact(ORACLE_OPERATION),
    "options" to PlanTreatment.Fact(ORACLE_OPERATION),
    "depth" to PlanTreatment.Measured,
    "cost" to PlanTreatment.Measured,
    "cardinality" to PlanTreatment.Measured,
    "bytes" to PlanTreatment.Measured,
    "object" to PlanTreatment.Subtree { ORACLE_OBJECT_FIELDS },
    "operation" to PlanTreatment.Subtree { ORACLE_OPERATION_FIELDS },
)

/**
 * **The object an operation works on** — and the second place the kind of a name is read off a
 * sibling rather than guessed.
 *
 * The document says what the object **is**, so the reader is told rather than deciding: an object
 * typed `INDEX` is an access path and everything else is a rowset. Both branches anonymize, which is
 * the rule under which reading a sibling is admitted at all — and an object with no type attribute
 * takes the rowset branch, which replaces what it finds just as surely.
 */
internal val ORACLE_OBJECT_FIELDS: Map<String, PlanTreatment> = mapOf(
    "name" to PlanTreatment.Typed(
        by = "type",
        branches = mapOf("INDEX" to PlanTreatment.Name(SymbolRole.INDEX)),
        otherwise = PlanTreatment.Name(SymbolRole.TABLE),
    ),
    "owner" to PlanTreatment.Name(SymbolRole.SCHEMA),
    "alias" to PlanTreatment.Declared,
    "type" to PlanTreatment.Fact(PlanShapes.ENGINE_ENUM),
)

/**
 * **Oracle's rider** — the argument that lets a bare token be preserved in this engine's plans,
 * written from its printers' quoting rules rather than measured from a corpus.
 *
 * `DBMS_XPLAN` writes **every identifier it prints into a predicate or an outline hint inside double
 * quotes**: a filter reads `"V"."STATUS"='ACTIVE'` and a hint reads `FULL(@"SEL$1" "V"@"SEL$1")`,
 * whatever the identifier is spelled like and whether or not it needed quoting to be legal. So a bare
 * word in one of those fields **cannot be a user's identifier**, and the words below are safe as
 * written for that reason rather than because they look like keywords.
 *
 * The residual still runs toward replacement, and that is the point of keeping the list honest rather
 * than long: a word this vocabulary lacks comes out `col1`, which costs fidelity a reader can see and
 * leaks nothing.
 *
 * [PlanVocabulary.builtins] is the stated exception here as everywhere: Oracle prints a user's
 * `count` function exactly as it prints its own — inside quotes only if the *object* is quoted — so a
 * function name is preserved and the exposure is recorded rather than argued away.
 */
internal val ORACLE = PlanVocabulary(
    words = setOf(
        // What the predicate section labels its rows with. Lower case, and safe under the rider: an
        // identifier spelled `filter` is printed `"FILTER"` or `"filter"`, in quotes either way.
        "filter", "access", "storage",
        // The printer's keywords, upper case as it writes them.
        "AND", "OR", "NOT", "IS", "NULL", "IN", "EXISTS", "LIKE", "BETWEEN", "CASE", "WHEN", "THEN",
        "ELSE", "END", "ANY", "ALL", "SOME", "AS", "ASC", "DESC", "NULLS", "FIRST", "LAST",
        "DISTINCT", "ON", "USING", "ROWNUM", "ROWID", "LEVEL", "PRIOR", "CONNECT_BY_ROOT",
        "CONNECT_BY_ISLEAF", "CONNECT_BY_ISCYCLE", "SYSDATE", "SYSTIMESTAMP", "USER", "TRUE",
        "FALSE", "INTERVAL", "DATE", "TIMESTAMP", "TO", "FROM", "FOR", "OF", "ESCAPE", "PARTITION",
        "SUBPARTITION", "ORDER", "BY", "GROUP", "OVER", "PRECEDING", "FOLLOWING", "UNBOUNDED",
        "CURRENT", "ROW", "ROWS", "RANGE", "WITHIN",
    ),
    builtins = setOf(
        // Types, which is what a conversion in a predicate is written against.
        "NUMBER", "VARCHAR2", "NVARCHAR2", "CHAR", "NCHAR", "CLOB", "NCLOB", "BLOB", "BFILE", "RAW",
        "LONG", "FLOAT", "BINARY_FLOAT", "BINARY_DOUBLE", "ROWID", "UROWID", "XMLTYPE",
        // The functions Oracle prints into a predicate of its own accord.
        "ABS", "ADD_MONTHS", "AVG", "CAST", "CEIL", "CHR", "COALESCE", "CONCAT", "COUNT",
        "CURRENT_DATE", "CURRENT_TIMESTAMP", "DECODE", "DENSE_RANK", "EXTRACT", "FLOOR", "GREATEST",
        "INITCAP", "INSTR", "LAG", "LAST_VALUE", "LEAD", "LEAST", "LENGTH", "LENGTHB", "LOWER",
        "LPAD", "LTRIM", "MAX", "MIN", "MOD", "MONTHS_BETWEEN", "NULLIF", "NVL", "NVL2",
        "NUMTODSINTERVAL", "NUMTOYMINTERVAL", "POWER", "RANK", "REGEXP_LIKE", "REGEXP_REPLACE",
        "REGEXP_SUBSTR", "REPLACE", "ROUND", "ROW_NUMBER", "RPAD", "RTRIM", "SIGN", "SQRT",
        "SUBSTR", "SUBSTRB", "SUM", "SYS_EXTRACT_UTC", "SYS_GUID", "SYS_OP_C2C", "SYS_OP_DESCEND",
        "TO_CHAR", "TO_DATE", "TO_NUMBER", "TO_TIMESTAMP", "TRIM", "TRUNC", "UPPER",
    ),
    constants = setOf("NULL", "TRUE", "FALSE"),
    phrases = listOf(
        // The printer's own multi-word spellings. Each carries a word that is unsafe alone, which is
        // why it is a phrase: an identifier with a space in it must be quoted in this dialect, so a
        // fixed multi-word spelling cannot collide with a bare name at all.
        "IS NOT NULL",
        "IS NULL",
        "NOT IN",
        "NOT EXISTS",
        "NOT LIKE",
        "NOT BETWEEN",
        "TIMESTAMP WITH TIME ZONE",
        "TIMESTAMP WITH LOCAL TIME ZONE",
        "INTERVAL DAY TO SECOND",
        "INTERVAL YEAR TO MONTH",
    ),
)

/**
 * **The hint names Oracle's outline prints** — a closed **per-version** vocabulary, and the whole of
 * what the outline is parsed against.
 *
 * > **An off-list hint name, or a hint that does not parse, makes that one hint a redacted literal —
 * > and does not refuse the plan.**
 *
 * That is the one place in this product where a closed vocabulary's miss costs fidelity rather than
 * the input, and the difference is what the miss can *be*. A hint is a self-delimiting line inside a
 * comment the engine wrote, so a hint nobody recognises can be replaced whole, exactly, with nothing
 * of it emitted. A field has no such boundary, which is why a missing field row still refuses.
 *
 * **Rejected: treating every hint as one opaque literal.** It leaks nothing, and it throws the
 * section away — the outline stops lining up with the plan it describes, which is the whole reason
 * anyone reads it.
 */
internal val ORACLE_OUTLINE_HINTS: Set<String> = setOf(
    // What the outline opens and closes with, and what it says about itself.
    "BEGIN_OUTLINE_DATA", "END_OUTLINE_DATA", "IGNORE_OPTIM_EMBEDDED_HINTS",
    "OPTIMIZER_FEATURES_ENABLE", "DB_VERSION", "ALL_ROWS", "FIRST_ROWS", "OPT_PARAM", "OPT_ESTIMATE",
    "ADAPTIVE_PLAN", "DYNAMIC_SAMPLING", "CURSOR_SHARING_EXACT",
    // The query blocks.
    "OUTLINE", "OUTLINE_LEAF", "QB_NAME", "NO_QUERY_TRANSFORMATION",
    // The access paths.
    "FULL", "INDEX", "INDEX_ASC", "INDEX_DESC", "INDEX_RS_ASC", "INDEX_RS_DESC", "INDEX_FFS",
    "INDEX_SS", "INDEX_SS_ASC", "INDEX_SS_DESC", "INDEX_JOIN", "INDEX_COMBINE", "NO_INDEX",
    "NO_ACCESS", "BITMAP_TREE", "AND_EQUAL", "ROWID",
    "BATCH_TABLE_ACCESS_BY_ROWID", "NO_BATCH_TABLE_ACCESS_BY_ROWID",
    // The joins.
    "LEADING", "ORDERED", "USE_NL", "USE_NL_WITH_INDEX", "NO_USE_NL", "USE_MERGE", "NO_USE_MERGE",
    "USE_HASH", "NO_USE_HASH", "NLJ_BATCHING", "NO_NLJ_BATCHING", "SWAP_JOIN_INPUTS",
    "NO_SWAP_JOIN_INPUTS", "SEMIJOIN", "SEMI_TO_INNER", "PARTIAL_JOIN", "NO_PARTIAL_JOIN",
    "USE_CONCAT", "NO_EXPAND", "ELIMINATE_JOIN", "NO_ELIMINATE_JOIN",
    // The transformations.
    "MERGE", "NO_MERGE", "UNNEST", "NO_UNNEST", "PUSH_PRED", "NO_PUSH_PRED", "PUSH_SUBQ",
    "NO_PUSH_SUBQ", "ORDER_SUBQ", "PLACE_GROUP_BY", "NO_PLACE_GROUP_BY", "GBY_PUSHDOWN",
    "NO_GBY_PUSHDOWN", "USE_HASH_AGGREGATION", "NO_USE_HASH_AGGREGATION", "STAR_TRANSFORMATION",
    "NO_STAR_TRANSFORMATION", "ELIMINATE_OBY", "NO_ELIMINATE_OBY", "NO_CONNECT_BY_FILTERING",
    "VECTOR_TRANSFORM", "NO_VECTOR_TRANSFORM",
    // Parallelism and the result cache.
    "PARALLEL", "NO_PARALLEL", "PQ_DISTRIBUTE", "PX_JOIN_FILTER", "NO_PX_JOIN_FILTER",
    "RESULT_CACHE", "NO_RESULT_CACHE",
)

/**
 * **The two hints whose one argument is a version the engine printed**, preserved behind the version
 * shape rather than read as an object.
 *
 * They are what makes an outline readable at all — which optimizer the plan was chosen by — and they
 * are the only hints in the outline whose argument is a quoted **value** rather than a quoted
 * **name**. A literal anywhere else in a hint is the user's, and is masked.
 */
internal val ORACLE_VERSION_HINTS: Set<String> = setOf("OPTIMIZER_FEATURES_ENABLE", "DB_VERSION")

/**
 * **The bare words the hint grammar admits inside a hint's arguments.**
 *
 * The outline quotes every identifier, so an unquoted word inside a hint is the printer's own — and
 * one this list is missing makes that **one hint** a redacted literal rather than being preserved on
 * the strength of looking like a keyword. The list is short because the outline's arguments are
 * nearly all quoted names: what is left is the handful of enumerations two or three hints take.
 */
internal val ORACLE_HINT_WORDS: Set<String> = setOf(
    // What `OPT_ESTIMATE` says it is estimating.
    "TABLE", "INDEX_SCAN", "INDEX_FILTER", "INDEX_SKIP_SCAN", "JOIN", "GROUP_BY", "ROWS",
    "SCALE_ROWS", "MIN", "MAX", "QUERY_BLOCK",
    // How `PQ_DISTRIBUTE` distributes, and how `PARALLEL` picks its degree.
    "HASH", "BROADCAST", "NONE", "PARTIAL", "RANDOM", "RANGE", "REPLICATE", "LOCAL", "SERIAL",
    "AUTO", "MANUAL", "DEFAULT",
)

/**
 * **A query block name the optimizer invented** — `SEL$1`, `INS$1`, `SEL$F5BB74E1`.
 *
 * It is preserved for the reason `SubPlan 1` is preserved in a PostgreSQL plan: the letters are the
 * printer's, the rest is its own counter or its own hash of a transformation, and **nothing of
 * anybody's is in it**. A query block a statement named for itself — `QB_NAME(recent_visits)` — does
 * not match this, and is masked.
 */
internal val ORACLE_QUERY_BLOCK = Regex("""[A-Z]{3}\$[0-9A-Z]+""")
