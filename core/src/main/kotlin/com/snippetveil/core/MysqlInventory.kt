package com.snippetveil.core

/**
 * **MySQL's field inventories** — the whole of what this product reads out of a MySQL plan, and
 * therefore the whole of what it accepts from MySQL at all.
 *
 * ### Two JSON formats, admitted separately
 *
 * `EXPLAIN FORMAT=JSON` prints one of two documents depending on the release and on
 * `explain_json_format_version`, and **they are not versions of one format to this reader — they are
 * two formats.** Version 2 is a tree of operations whose prose is assembled from templates; version 1
 * is the older query-block document. Nothing here falls back from one to the other, for the reason no
 * format anywhere in this product falls back to another: a reader that tried both in turn would be a
 * reader that picks.
 *
 * ### Everything else MySQL prints is refused
 *
 * The `TREE` output, `EXPLAIN ANALYZE` and the tabular family have no inventory here, and that is
 * deliberate: there is no reading of them. They are recognised in [MYSQL_FORMATS] so that the refusal
 * can say which shape arrived, and nothing more of them is ever looked at.
 *
 * ### A missing row refuses
 *
 * Exactly as PostgreSQL's does. A MySQL release that adds a field refuses every plan carrying it
 * until a capture and a row here follow, and that cost is accepted rather than discovered later — see
 * [PlanTreatment] for the argument, and for why the opposite closure runs *inside* a field.
 */
internal val MYSQL_V2_QUERY_FIELDS: Map<String, PlanTreatment> = mapOf(
    // The statement the plan was produced for, written back verbatim — the user's own SQL, and one
    // redacted literal for the reason every echoed statement is one.
    "query" to PlanTreatment.EchoedQuery,
    "inputs" to PlanTreatment.Subtree { MYSQL_V2_NODE_FIELDS },
    "json_schema_version" to PlanTreatment.Fact(PlanShapes.VERSION),
)

/**
 * **The fixed set of sentences a version 2 plan's `operation` is assembled from.**
 *
 * The prose is the engine's, the fields in it are the node's own, and the reader's whole job is to
 * decide which of these produced the line in front of it — never to read the line. See [PlanTemplate]
 * for that argument and [PlanTreatment.Rendered] for why it is a treatment class of its own.
 *
 * **A sentence this list is missing refuses the plan carrying it.** That is the field closure applied
 * one level down, and it costs what the field closure costs: a release that renders a node a new way
 * refuses until a capture and a row follow. The alternative — a pattern loose enough never to miss a
 * sentence — is a pattern loose enough to accept a sentence a user's table name wrote.
 */
internal val MYSQL_V2_OPERATIONS: List<PlanTemplate> = listOf(
    "Table scan on {table_name}",
    "Index scan on {table_name} using {index_name}",
    "Covering index scan on {table_name} using {index_name}",
    "Index lookup on {table_name} using {index_name} ({lookup_condition})",
    "Single-row index lookup on {table_name} using {index_name} ({lookup_condition})",
    "Covering index lookup on {table_name} using {index_name} ({lookup_condition})",
    "Index range scan on {table_name} using {index_name} over {index_range}",
    "Filter: {condition}",
    "Sort: {sort_key}",
    "Limit: {limit_rows} row(s)",
    "Nested loop {join_type} join",
    "Inner hash join ({hash_condition})",
    "Aggregate: {aggregate_function}",
    "Materialize",
    "Stream results",
    "Rows fetched before execution",
).map(::PlanTemplate)

/**
 * **One operation of a version 2 plan** — the node, its measurements, and the rendered line the
 * engine assembled out of the rest of it.
 *
 * The names are what this file exists for. `table_name` holds **what the plan displays**, which is
 * the alias where the statement wrote one and the relation where it did not — the same thing MySQL's
 * tabular `table` column shows — so it is read as a rowset either way rather than as two rows that
 * would have to be told apart by something this reader cannot see.
 *
 * **The rendered conditions are masked whole**, and that is the one place MySQL's inventory differs
 * in kind from PostgreSQL's. PostgreSQL prints every identifier inside an expression through
 * `quote_identifier()`, which is what lets a bare token be preserved there; MySQL appends identifiers
 * to a rendered condition **with nothing around them**, so there is no quoting rule for a vocabulary
 * to lean on and no boundary for a lexer to find. See [PlanTreatment.Raw] and [MYSQL].
 */
internal val MYSQL_V2_NODE_FIELDS: Map<String, PlanTreatment> = buildMap {
    // **The rendered line**, cross-checked against the templates it was assembled from and never
    // scanned. See [MYSQL_V2_OPERATIONS].
    put("operation", PlanTreatment.Rendered(MYSQL_V2_OPERATIONS))

    // **The names.**
    put("table_name", PlanTreatment.Name(SymbolRole.TABLE))
    put("schema_name", PlanTreatment.Name(SymbolRole.SCHEMA))
    put("index_name", PlanTreatment.Name(SymbolRole.INDEX))

    // **A qualified reference, read by position and refused on the wrong part count** — the one field
    // of this format that carries several names with no field of its own around each.
    put("sort_key", PlanTreatment.Reference(listOf(SymbolRole.SCHEMA, SymbolRole.TABLE, SymbolRole.COLUMN)))

    // **The engine's own enums and booleans**, preserved behind a shape check — and masked rather
    // than refused when a release changes one, because these arrive escaped and a surprise in one of
    // them is at worst a value.
    for (label in listOf("access_type", "index_access_type", "join_algorithm", "join_type")) {
        put(label, PlanTreatment.Fact(PlanShapes.ENGINE_TOKEN))
    }
    put("covering", PlanTreatment.Fact(PlanShapes.BOOLEAN))

    // **The rendered conditions**, each one redacted literal. See the note above.
    for (label in listOf("condition", "lookup_condition", "hash_condition", "index_range", "aggregate_function")) {
        put(label, PlanTreatment.Raw)
    }

    // **The measured quantities** — the numbers a plan is pasted *for*, preserved exactly as printed
    // and reported as nothing at all. See [PlanTreatments.measured].
    for (label in listOf(
        "estimated_rows", "estimated_total_cost", "estimated_first_row_cost", "actual_rows",
        "actual_loops", "actual_first_row_ms", "actual_last_row_ms", "rows_per_scan", "limit_rows",
        "heap_usage_bytes",
    )) {
        put(label, PlanTreatment.Measured)
    }

    // **The containers a node holds**, each of them more nodes.
    put("inputs", PlanTreatment.Subtree { MYSQL_V2_NODE_FIELDS })
    put("subqueries", PlanTreatment.Subtree { MYSQL_V2_NODE_FIELDS })
    put("materialized_from_subquery", PlanTreatment.Subtree { MYSQL_V2_NODE_FIELDS })
}

/**
 * **MySQL's JSON version 1, at the top** — the older document, whose whole content is one query
 * block.
 */
internal val MYSQL_V1_QUERY_FIELDS: Map<String, PlanTreatment> = mapOf(
    "query_block" to PlanTreatment.Subtree { MYSQL_V1_BLOCK_FIELDS },
)

/**
 * **One query block of a version 1 plan** — the select it describes, the operations layered over it,
 * and the one-line message the optimizer prints where there is no table to describe at all.
 */
internal val MYSQL_V1_BLOCK_FIELDS: Map<String, PlanTreatment> = buildMap {
    put("select_id", PlanTreatment.Measured)
    put("cost_info", PlanTreatment.Subtree { MYSQL_V1_COST_FIELDS })
    put("table", PlanTreatment.Subtree { MYSQL_V1_TABLE_FIELDS })
    put("nested_loop", PlanTreatment.Subtree { MYSQL_V1_BLOCK_FIELDS })

    // **The optimizer's one-line verdict**, which is unescaped and therefore a closed list. See
    // [MYSQL_MESSAGES].
    put("message", PlanTreatment.Unescaped { it in MYSQL_MESSAGES })

    for (label in listOf("using_filesort", "using_temporary_table", "cacheable", "dependent")) {
        put(label, PlanTreatment.Fact(PlanShapes.BOOLEAN))
    }

    for (label in listOf(
        "ordering_operation", "grouping_operation", "duplicates_removal", "attached_subqueries",
        "materialized_from_subquery", "query_specifications", "query_block",
    )) {
        put(label, PlanTreatment.Subtree { MYSQL_V1_BLOCK_FIELDS })
    }
    put("union_result", PlanTreatment.Subtree { MYSQL_V1_UNION_FIELDS })
}

/** **The union's own node**, which names a temporary result of the engine's and holds the selects. */
internal val MYSQL_V1_UNION_FIELDS: Map<String, PlanTreatment> = mapOf(
    // `<union1,2>` is a name the optimizer invented for a result it materialised; it is not a
    // relation anybody wrote, so it is masked rather than given a rowset's placeholder — the reading
    // a trigger gets in PostgreSQL's inventory, for the same reason.
    "table_name" to PlanTreatment.Deployment,
    "access_type" to PlanTreatment.Unescaped { it in MYSQL_ACCESS_TYPES },
    "using_temporary_table" to PlanTreatment.Fact(PlanShapes.BOOLEAN),
    "query_specifications" to PlanTreatment.Subtree { MYSQL_V1_BLOCK_FIELDS },
)

/**
 * **One table of a version 1 plan** — and the three unescaped fields that make this format's
 * admission a narrower thing than version 2's.
 *
 * `access_type`, `key_length` and `message` are written into the document **without escaping**, so
 * each is admitted only for values the engine itself writes and **an off-list value refuses**. See
 * [PlanTreatment.Unescaped] for why masking would not be enough.
 */
internal val MYSQL_V1_TABLE_FIELDS: Map<String, PlanTreatment> = buildMap {
    put("table_name", PlanTreatment.Name(SymbolRole.TABLE))
    put("key", PlanTreatment.Name(SymbolRole.INDEX))
    put("possible_keys", PlanTreatment.Name(SymbolRole.INDEX))

    // **The three unescaped fields.** See the note above.
    put("key_length", PlanTreatment.Unescaped { MYSQL_KEY_LENGTH.matches(it) })
    put("access_type", PlanTreatment.Unescaped { it in MYSQL_ACCESS_TYPES })
    put("message", PlanTreatment.Unescaped { it in MYSQL_MESSAGES })

    // **A column name, read as a reference of exactly one part** — so a column a user spelled with a
    // `.` in it refuses rather than being reported as a qualified name it is not.
    put("used_columns", PlanTreatment.Reference(listOf(SymbolRole.COLUMN)))
    put("used_key_parts", PlanTreatment.Reference(listOf(SymbolRole.COLUMN)))

    // A partition is an object of the deployment's rather than one of the four name kinds.
    put("partitions", PlanTreatment.Deployment)

    // **The rendered conditions and the reference list**, each one redacted literal: every identifier
    // in them was appended by a printer that delimits nothing this reader can trust.
    for (label in listOf("attached_condition", "index_condition", "ref")) {
        put(label, PlanTreatment.Raw)
    }

    for (label in listOf("using_index", "using_temporary_table", "using_filesort")) {
        put(label, PlanTreatment.Fact(PlanShapes.BOOLEAN))
    }

    for (label in listOf("rows_examined_per_scan", "rows_produced_per_join", "filtered")) {
        put(label, PlanTreatment.Measured)
    }
    put("cost_info", PlanTreatment.Subtree { MYSQL_V1_COST_FIELDS })
    put("materialized_from_subquery", PlanTreatment.Subtree { MYSQL_V1_BLOCK_FIELDS })
    put("attached_subqueries", PlanTreatment.Subtree { MYSQL_V1_BLOCK_FIELDS })
}

/** **The optimizer's costs**, which are measurements printed as strings rather than as numbers. */
internal val MYSQL_V1_COST_FIELDS: Map<String, PlanTreatment> = listOf(
    "query_cost", "read_cost", "eval_cost", "prefix_cost", "data_read_per_join", "sort_cost",
).associateWith { PlanTreatment.Measured }

/**
 * **The one-line messages MySQL's optimizer prints**, as a closed list — a per-release vocabulary row
 * like any other, and the one that makes version 1's `message` field admissible at all.
 *
 * Every one of these is a **fixed string in the engine**: the optimizer chooses a constant, and there
 * is no path by which a user's table, column or value reaches this field. That is what makes the list
 * a safe thing to enumerate, and it is also why **a value outside it refuses rather than being
 * masked**: the field is written unescaped, so a value the engine cannot have written is a value that
 * may have ended its own slot and forged the fields printed after it. See [PlanTreatment.Unescaped].
 */
internal val MYSQL_MESSAGES: Set<String> = setOf(
    "No tables used",
    "No matching rows after partition pruning",
    "no matching row in const table",
    "Impossible WHERE",
    "Impossible WHERE noticed after reading const tables",
    "Impossible HAVING",
    "Impossible HAVING noticed after reading const tables",
    "Select tables optimized away",
    "No matching min/max row",
    "unique row not found",
    "Zero limit",
    "Deleting all rows",
    "Table is already up to date",
    "Const row not found",
    "Not exists",
    "Recursive",
    "Materialize",
)

/**
 * **The access types MySQL's optimizer can print**, as a closed list.
 *
 * The second of version 1's unescaped fields: an enumeration of the engine's own, admitted by
 * membership rather than by shape, because a shape broad enough to cover `eq_ref` and `index_merge`
 * is broad enough to cover a word somebody else wrote.
 */
internal val MYSQL_ACCESS_TYPES: Set<String> = setOf(
    "system", "const", "eq_ref", "ref", "fulltext", "ref_or_null", "index_merge", "unique_subquery",
    "index_subquery", "range", "index", "ALL",
)

/**
 * **A key length as MySQL writes one** — digits, and the commas that join the lengths of a composite
 * key's parts.
 *
 * The third unescaped field, and the one admitted by a shape rather than by a list: the writer prints
 * an integer per key part and nothing else, so here the shape *is* the closed set. A value with
 * anything else in it is not one this writer produced.
 */
internal val MYSQL_KEY_LENGTH: Regex = Regex("""\d+(,\d+)*""")

/**
 * **MySQL's vocabulary, which is empty — and empty for a reason worth stating rather than hiding.**
 *
 * [POSTGRES]'s rider is its quoting rule: `EXPLAIN` prints every identifier through
 * `quote_identifier()`, so a bare `AND` in an expression field cannot be an identifier and preserving
 * it is safe by construction. **MySQL's rendered output has no such rider.** Identifiers are appended
 * to a rendered condition with nothing around them, so there is no spelling in a MySQL plan that a
 * user's object cannot also be printed under — and a `words` list here would be a list of names this
 * product had decided to hand back.
 *
 * So MySQL's expression-shaped fields are not scanned at all: they are [PlanTreatment.Raw], one
 * redacted literal each. This vocabulary exists because [PlanSymbols] takes one, and it is empty
 * because the honest answer to *which bare words may be preserved* is none.
 */
internal val MYSQL = PlanVocabulary(
    words = emptySet(),
    builtins = emptySet(),
    constants = emptySet(),
    phrases = emptyList(),
)
