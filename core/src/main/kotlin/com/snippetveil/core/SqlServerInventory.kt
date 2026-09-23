package com.snippetveil.core

/** The namespace SQL Server's showplan writer declares, which is what says these tags are its. */
internal val SHOWPLAN_NAMESPACE = Regex("""http://schemas\.microsoft\.com/sqlserver/2004/07/showplan""")

/** The two XML Schema namespaces the same writer declares on its root alongside its own. */
internal val XML_SCHEMA_NAMESPACE = Regex("""http://www\.w3\.org/2001/XMLSchema(-instance)?""")

/**
 * **SQL Server's field inventory for Showplan XML**, and the operator sets its text rowset is read
 * against — the whole of what this product accepts from this engine.
 *
 * ### The content is in the attributes, and that changes nothing below the reader
 *
 * PostgreSQL writes one element per field; SQL Server writes
 * `<ColumnReference Table="[Visits]" Column="OwnerId"/>`. [attributedXmlDocumentIn] absorbs that
 * difference, so an attribute is an entry with a label, a name range and scalar content exactly as
 * an element is — and the rows below are the same kind of statement every other engine's are.
 *
 * ### The predicates are structural, so almost nothing here is scanned
 *
 * A column arrives as `Table` and `Column` attributes rather than as a rendered `t.c`, so the reader
 * is told where each name is instead of having to find it. The two fields that do carry rendered text
 * — a scalar operator's `ScalarString` and a constant's `ConstValue` — are read by
 * [readBracketedExpression], which needs no vocabulary because **this writer brackets every
 * identifier it prints**.
 *
 * ### Written from a reading of the engine's output, not from a capture in this repository
 *
 * The rows are what SQL Server's showplan schema writes for the shapes this product has been shown,
 * and the field closure is what makes that safe to be wrong about: a field with no row **refuses the
 * plan carrying it** rather than being read under a guess. `SqlServerInventoryTest` holds every row
 * here to a fixture that exercises it, so no row is one this product only believes in.
 */
internal val SQLSERVER_SHOWPLAN_FIELDS: Map<String, PlanTreatment> = mapOf(
    // **The namespace declarations**, which are the document's own machinery rather than its content
    // — and rows all the same, because nothing in this reader is skipped silently. A document
    // declaring a namespace this writer does not is one whose element names mean something else.
    "xmlns" to PlanTreatment.Fact(SHOWPLAN_NAMESPACE),
    "xmlns:xsi" to PlanTreatment.Fact(XML_SCHEMA_NAMESPACE),
    "xmlns:xsd" to PlanTreatment.Fact(XML_SCHEMA_NAMESPACE),
    "Version" to PlanTreatment.Fact(PlanShapes.VERSION),
    "Build" to PlanTreatment.Fact(PlanShapes.VERSION),
    "BatchSequence" to PlanTreatment.Subtree { SQLSERVER_BATCH_SEQUENCE_FIELDS },
)

internal val SQLSERVER_BATCH_SEQUENCE_FIELDS: Map<String, PlanTreatment> =
    mapOf("Batch" to PlanTreatment.Subtree { SQLSERVER_BATCH_FIELDS })

internal val SQLSERVER_BATCH_FIELDS: Map<String, PlanTreatment> =
    mapOf("Statements" to PlanTreatment.Subtree { SQLSERVER_STATEMENTS_FIELDS })

/**
 * **The statement shapes this product reads**, which is the simple one and no other.
 *
 * A conditional, a cursor or a `USE` statement prints an element of its own, and each is a capture
 * this product has not taken — so a batch carrying one refuses under the field closure rather than
 * being read as a simple statement with unfamiliar contents.
 */
internal val SQLSERVER_STATEMENTS_FIELDS: Map<String, PlanTreatment> =
    mapOf("StmtSimple" to PlanTreatment.Subtree { SQLSERVER_STATEMENT_FIELDS })

/**
 * **One statement of a batch** — and the field that makes this engine one of the two whose plans
 * carry the user's own SQL back out.
 *
 * `StatementText` is the statement the plan was produced for and `ParameterizedText` is what the
 * optimizer rewrote it to. Both are **one redacted literal and neither is parsed**, which is the
 * reading every echoed statement gets and for the reason [PlanTreatments.echoedQuery] gives: parsing
 * one needs the dialect grammar this container refuses to carry.
 */
internal val SQLSERVER_STATEMENT_FIELDS: Map<String, PlanTreatment> = buildMap {
    put("StatementText", PlanTreatment.EchoedQuery)
    put("ParameterizedText", PlanTreatment.EchoedQuery)

    // **The hashes, masked.** A plan hash is derived from the statement, so preserving one would let
    // a receiver confirm a guessed query — which is the attack [PlanTreatments.identifying] exists to
    // refuse, stated next to the redacted statement it would otherwise undo.
    for (label in listOf("QueryHash", "QueryPlanHash", "ParameterizedPlanHandle")) {
        put(label, PlanTreatment.Identifying)
    }

    for (label in listOf("StatementId", "StatementCompId", "StatementEstRows", "StatementSubTreeCost")) {
        put(label, PlanTreatment.Measured)
    }
    for (label in listOf("StatementType", "StatementOptmLevel", "StatementOptmEarlyAbortReason")) {
        put(label, PlanTreatment.Fact(PlanShapes.ENGINE_ENUM))
    }
    put("CardinalityEstimationModelVersion", PlanTreatment.Fact(PlanShapes.VERSION))
    for (label in listOf("RetrievedFromCache", "SecurityPolicyApplied")) {
        put(label, PlanTreatment.Fact(PlanShapes.XML_BOOLEAN))
    }

    put("StatementSetOptions", PlanTreatment.Subtree { SQLSERVER_SET_OPTION_FIELDS })
    put("QueryPlan", PlanTreatment.Subtree { SQLSERVER_QUERY_PLAN_FIELDS })
}

/** **The session settings the plan was compiled under**, every one of them a boolean of the engine's. */
internal val SQLSERVER_SET_OPTION_FIELDS: Map<String, PlanTreatment> = listOf(
    "ANSI_NULLS", "ANSI_PADDING", "ANSI_WARNINGS", "ARITHABORT", "CONCAT_NULL_YIELDS_NULL",
    "NUMERIC_ROUNDABORT", "QUOTED_IDENTIFIER",
).associateWith { PlanTreatment.Fact(PlanShapes.XML_BOOLEAN) }

/**
 * **The plan the optimizer produced** — what it cost to compile, what it asked for, and the tree.
 *
 * `CachedPlanSize` is **a measured quantity and stays one**, stated here rather than left to be
 * inferred: it is a magnitude about the plan itself, with no value of anybody's in it, and a later
 * rule that swept every size-looking field into a mask would take it with them.
 */
internal val SQLSERVER_QUERY_PLAN_FIELDS: Map<String, PlanTreatment> = buildMap {
    for (label in listOf(
        "DegreeOfParallelism", "MemoryGrant", "CachedPlanSize", "CompileTime", "CompileCPU",
        "CompileMemory",
    )) {
        put(label, PlanTreatment.Measured)
    }
    put("NonParallelPlanReason", PlanTreatment.Fact(PlanShapes.ENGINE_ENUM))
    put("ContainsInterleavedExecutionCandidates", PlanTreatment.Fact(PlanShapes.XML_BOOLEAN))

    put("MemoryGrantInfo", PlanTreatment.Subtree { SQLSERVER_MEMORY_GRANT_FIELDS })
    put("OptimizerHardwareDependentProperties", PlanTreatment.Subtree { SQLSERVER_HARDWARE_FIELDS })
    put("OptimizerStatsUsage", PlanTreatment.Subtree { SQLSERVER_STATS_USAGE_FIELDS })
    put("TraceFlags", PlanTreatment.Subtree { SQLSERVER_TRACE_FLAGS_FIELDS })
    put("ParameterList", PlanTreatment.Subtree { SQLSERVER_COLUMN_REFERENCES })
    put("Warnings", PlanTreatment.Subtree { SQLSERVER_WARNING_FIELDS })
    put("RelOp", PlanTreatment.Subtree { SQLSERVER_REL_OP_FIELDS })
}

/** **What the plan asked the memory broker for**, and what it was given. */
internal val SQLSERVER_MEMORY_GRANT_FIELDS: Map<String, PlanTreatment> = listOf(
    "SerialRequiredMemory", "SerialDesiredMemory", "RequiredMemory", "DesiredMemory",
    "RequestedMemory", "GrantWaitTime", "GrantedMemory", "MaxUsedMemory",
).associateWith { PlanTreatment.Measured }

/** **The machine the plan was compiled for**, as the optimizer measured it. */
internal val SQLSERVER_HARDWARE_FIELDS: Map<String, PlanTreatment> = listOf(
    "EstimatedAvailableMemoryGrant", "EstimatedPagesCached",
    "EstimatedAvailableDegreeOfParallelism", "MaxCompileMemory",
).associateWith { PlanTreatment.Measured }

internal val SQLSERVER_STATS_USAGE_FIELDS: Map<String, PlanTreatment> =
    mapOf("StatisticsInfo" to PlanTreatment.Subtree { SQLSERVER_STATISTICS_INFO_FIELDS })

/**
 * **One statistics object the optimizer read** — three names of the object it describes, and the one
 * name that is an access path rather than a rowset.
 *
 * `LastUpdate` is a measurement like the rest of the row: it is when the engine last sampled the
 * table, which says nothing about any value in it.
 */
internal val SQLSERVER_STATISTICS_INFO_FIELDS: Map<String, PlanTreatment> = mapOf(
    "Database" to PlanTreatment.Bracketed(SymbolRole.SCHEMA),
    "Schema" to PlanTreatment.Bracketed(SymbolRole.SCHEMA),
    "Table" to PlanTreatment.Bracketed(SymbolRole.TABLE),
    "Statistics" to PlanTreatment.Bracketed(SymbolRole.INDEX),
    "ModificationCount" to PlanTreatment.Measured,
    "SamplingPercent" to PlanTreatment.Measured,
    "LastUpdate" to PlanTreatment.Measured,
)

/**
 * **The trace flags the plan was compiled under** — a number and an enumeration, both the engine's
 * own and both **preserved behind the shape check**.
 *
 * They are stated as preserved rather than left to a default because they are a top reason a plan
 * looks the way it does: a reader told a flag was set can explain the shape, and a reader handed
 * `str4` cannot.
 */
internal val SQLSERVER_TRACE_FLAGS_FIELDS: Map<String, PlanTreatment> = mapOf(
    "IsCompileTime" to PlanTreatment.Fact(PlanShapes.XML_BOOLEAN),
    "TraceFlag" to PlanTreatment.Subtree { SQLSERVER_TRACE_FLAG_FIELDS },
)

internal val SQLSERVER_TRACE_FLAG_FIELDS: Map<String, PlanTreatment> = mapOf(
    "Value" to PlanTreatment.Fact(PlanShapes.COUNT),
    "Scope" to PlanTreatment.Fact(PlanShapes.ENGINE_ENUM),
)

/** A container whose members are column references — an output list, a key list, a parameter list. */
internal val SQLSERVER_COLUMN_REFERENCES: Map<String, PlanTreatment> =
    mapOf("ColumnReference" to PlanTreatment.Subtree { SQLSERVER_COLUMN_REFERENCE_FIELDS })

/**
 * **One column reference — and the structural discriminator this engine's admission turns on.**
 *
 * > A column element carrying a `Table` is a column **of that table**; one without it is a column the
 * > optimizer **computed**.
 *
 * Reading that difference is allowed **because both branches replace what they find**: the first
 * takes a column's placeholder and the second a redacted literal, so no shape a user can influence
 * decides that something of theirs may be emitted as written. See [PlanTreatment.Discriminated],
 * where the rule is enforced rather than remembered.
 *
 * The computed branch is masked rather than given a column's placeholder because the name there is
 * not a column of anybody's: it is `Expr1002`, which the optimizer invented, or `@p1`, which is a
 * parameter and not a column at all. Masking says the one true thing — *something was named here* —
 * and equal spellings share a token, so a computed column defined at one node and used at another
 * still reads as one thing. It is the reading [PlanTreatments.deployment] gives every database
 * object outside the four name kinds.
 *
 * **The parameter's declared data type is preserved**, and deliberately: the optimizer emits it
 * identically for every auto-parameterized value of that slot, so it describes the slot and not the
 * value — and an implicit conversion is a top-tier reason to read a plan at all.
 */
internal val SQLSERVER_COLUMN_REFERENCE_FIELDS: Map<String, PlanTreatment> = mapOf(
    "Database" to PlanTreatment.Bracketed(SymbolRole.SCHEMA),
    "Schema" to PlanTreatment.Bracketed(SymbolRole.SCHEMA),
    "Table" to PlanTreatment.Bracketed(SymbolRole.TABLE),
    "Alias" to PlanTreatment.BracketedDeclaration,
    "Column" to PlanTreatment.Discriminated(
        by = "Table",
        present = PlanTreatment.Name(SymbolRole.COLUMN),
        absent = PlanTreatment.Deployment,
    ),
    "ComputedColumn" to PlanTreatment.Fact(PlanShapes.XML_BOOLEAN),
    "ParameterDataType" to PlanTreatment.Fact(PlanShapes.DATA_TYPE),
    "ParameterCompiledValue" to PlanTreatment.BracketedExpression,
    "ParameterRuntimeValue" to PlanTreatment.BracketedExpression,
)

/** A container whose members are scalar operators — a predicate, a range expression, a top count. */
internal val SQLSERVER_SCALAR_OPERATORS: Map<String, PlanTreatment> =
    mapOf("ScalarOperator" to PlanTreatment.Subtree { SQLSERVER_SCALAR_OPERATOR_FIELDS })

/**
 * **One scalar operator** — the tree the engine built, and the one line it rendered out of it.
 *
 * `ScalarString` is that line, and it is **scanned rather than masked** for the reason this engine
 * needs no vocabulary: every identifier in it is bracketed, so every character outside a bracket or a
 * literal is the engine's own. See [readBracketedExpression].
 *
 * The tree underneath says the same thing structurally, and both are read — a name written twice
 * takes one placeholder both times, because the key is the name rather than the place it was
 * printed.
 */
internal val SQLSERVER_SCALAR_OPERATOR_FIELDS: Map<String, PlanTreatment> = mapOf(
    "ScalarString" to PlanTreatment.BracketedExpression,
    "Identifier" to PlanTreatment.Subtree { SQLSERVER_COLUMN_REFERENCES },
    "Const" to PlanTreatment.Subtree { SQLSERVER_CONST_FIELDS },
    "Compare" to PlanTreatment.Subtree { SQLSERVER_COMPARE_FIELDS },
    "Convert" to PlanTreatment.Subtree { SQLSERVER_CONVERT_FIELDS },
    "Arithmetic" to PlanTreatment.Subtree { SQLSERVER_OPERATION_FIELDS },
    "Logical" to PlanTreatment.Subtree { SQLSERVER_OPERATION_FIELDS },
    "Aggregate" to PlanTreatment.Subtree { SQLSERVER_AGGREGATE_FIELDS },
    "Intrinsic" to PlanTreatment.Subtree { SQLSERVER_INTRINSIC_FIELDS },
)

/**
 * **A constant the optimizer printed** — `(42)`, `'open'`, `N'open'`.
 *
 * It is the user's value and it is read the way the rendered line is: the literal's content is
 * masked, its delimiters stay, and a number survives by not being reported. So `='open'` becomes
 * `='str1'` and the predicate still reads as the predicate it is.
 */
internal val SQLSERVER_CONST_FIELDS: Map<String, PlanTreatment> =
    mapOf("ConstValue" to PlanTreatment.BracketedExpression)

internal val SQLSERVER_COMPARE_FIELDS: Map<String, PlanTreatment> = mapOf(
    "CompareOp" to PlanTreatment.Fact(PlanShapes.ENGINE_ENUM),
    "ScalarOperator" to PlanTreatment.Subtree { SQLSERVER_SCALAR_OPERATOR_FIELDS },
)

/**
 * **A conversion, and the declared length that explains one.**
 *
 * `Length` is **preserved**, for the reason a parameter's declared type is: it is the target column's
 * width, constant across every value that slot ever holds, so it describes the column rather than
 * anything in it. An implicit conversion is one of the first things a reader looks for in a plan, and
 * these two fields are the whole of what explains it.
 */
internal val SQLSERVER_CONVERT_FIELDS: Map<String, PlanTreatment> = mapOf(
    "DataType" to PlanTreatment.Fact(PlanShapes.DATA_TYPE),
    "Length" to PlanTreatment.Fact(PlanShapes.COUNT),
    "Precision" to PlanTreatment.Fact(PlanShapes.COUNT),
    "Scale" to PlanTreatment.Fact(PlanShapes.COUNT),
    "Style" to PlanTreatment.Measured,
    "Implicit" to PlanTreatment.Fact(PlanShapes.XML_BOOLEAN),
    "ScalarOperator" to PlanTreatment.Subtree { SQLSERVER_SCALAR_OPERATOR_FIELDS },
)

internal val SQLSERVER_OPERATION_FIELDS: Map<String, PlanTreatment> = mapOf(
    "Operation" to PlanTreatment.Fact(PlanShapes.ENGINE_ENUM),
    "ScalarOperator" to PlanTreatment.Subtree { SQLSERVER_SCALAR_OPERATOR_FIELDS },
)

internal val SQLSERVER_AGGREGATE_FIELDS: Map<String, PlanTreatment> = mapOf(
    "AggType" to PlanTreatment.Fact(PlanShapes.ENGINE_ENUM),
    "Distinct" to PlanTreatment.Fact(PlanShapes.XML_BOOLEAN),
    "ScalarOperator" to PlanTreatment.Subtree { SQLSERVER_SCALAR_OPERATOR_FIELDS },
)

/**
 * **A function the engine itself owns**, named as the engine spells it.
 *
 * It is a fact rather than a name because an intrinsic is the engine's; a **user's** function is
 * printed in a `<UDF>` element instead, which has no row here and therefore refuses.
 */
internal val SQLSERVER_INTRINSIC_FIELDS: Map<String, PlanTreatment> = mapOf(
    "FunctionName" to PlanTreatment.Fact(PlanShapes.ENGINE_TOKEN),
    "ScalarOperator" to PlanTreatment.Subtree { SQLSERVER_SCALAR_OPERATOR_FIELDS },
)

/** **What the optimizer wants the reader to know went wrong**, which is where a conversion surfaces. */
internal val SQLSERVER_WARNING_FIELDS: Map<String, PlanTreatment> = mapOf(
    "PlanAffectingConvert" to PlanTreatment.Subtree { SQLSERVER_CONVERT_WARNING_FIELDS },
    "SpillToTempDb" to PlanTreatment.Subtree { SQLSERVER_SPILL_FIELDS },
    "ColumnsWithNoStatistics" to PlanTreatment.Subtree { SQLSERVER_COLUMN_REFERENCES },
)

internal val SQLSERVER_CONVERT_WARNING_FIELDS: Map<String, PlanTreatment> = mapOf(
    "ConvertIssue" to PlanTreatment.Fact(PlanShapes.ENGINE_ENUM),
    "Expression" to PlanTreatment.BracketedExpression,
)

internal val SQLSERVER_SPILL_FIELDS: Map<String, PlanTreatment> = mapOf(
    "SpillLevel" to PlanTreatment.Measured,
    "SpilledThreadCount" to PlanTreatment.Measured,
)

/**
 * **One node of the plan tree** — its estimates, the two names the optimizer gave the operation, and
 * the operator element that says what it actually did.
 *
 * `AvgRowSize` is **a measured quantity and stays one**, for the reason `CachedPlanSize` is: it is a
 * magnitude about the table's rows rather than about any value in one.
 */
internal val SQLSERVER_REL_OP_FIELDS: Map<String, PlanTreatment> = buildMap {
    for (label in listOf(
        "NodeId", "EstimateRows", "EstimateIO", "EstimateCPU", "AvgRowSize",
        "EstimatedTotalSubtreeCost", "TableCardinality", "EstimateRebinds", "EstimateRewinds",
        "EstimateRowsWithoutRowGoal",
    )) {
        put(label, PlanTreatment.Measured)
    }
    for (label in listOf("PhysicalOp", "LogicalOp", "EstimatedExecutionMode")) {
        put(label, PlanTreatment.Fact(PlanShapes.ENGINE_ENUM))
    }
    put("Parallel", PlanTreatment.Fact(PlanShapes.XML_BOOLEAN))

    put("OutputList", PlanTreatment.Subtree { SQLSERVER_COLUMN_REFERENCES })
    put("Warnings", PlanTreatment.Subtree { SQLSERVER_WARNING_FIELDS })
    put("RelOp", PlanTreatment.Subtree { SQLSERVER_REL_OP_FIELDS })

    // **The operator elements**, each of which is what one node actually did. A node whose operator
    // has no row here refuses the plan carrying it, which is the field closure reaching the one part
    // of this document that says what happened.
    put("IndexScan", PlanTreatment.Subtree { SQLSERVER_INDEX_SCAN_FIELDS })
    put("TableScan", PlanTreatment.Subtree { SQLSERVER_TABLE_SCAN_FIELDS })
    put("NestedLoops", PlanTreatment.Subtree { SQLSERVER_NESTED_LOOPS_FIELDS })
    put("Hash", PlanTreatment.Subtree { SQLSERVER_HASH_FIELDS })
    put("Sort", PlanTreatment.Subtree { SQLSERVER_SORT_FIELDS })
    put("ComputeScalar", PlanTreatment.Subtree { SQLSERVER_COMPUTE_SCALAR_FIELDS })
    put("Filter", PlanTreatment.Subtree { SQLSERVER_FILTER_FIELDS })
    put("StreamAggregate", PlanTreatment.Subtree { SQLSERVER_STREAM_AGGREGATE_FIELDS })
    put("Top", PlanTreatment.Subtree { SQLSERVER_TOP_FIELDS })
}

/** **An index scan or seek** — the access path it ran over, and the keys it ran with. */
internal val SQLSERVER_INDEX_SCAN_FIELDS: Map<String, PlanTreatment> = buildMap {
    for (label in listOf("Ordered", "ForcedIndex", "ForceSeek", "ForceScan", "NoExpandHint", "Lookup")) {
        put(label, PlanTreatment.Fact(PlanShapes.XML_BOOLEAN))
    }
    put("ScanDirection", PlanTreatment.Fact(PlanShapes.ENGINE_ENUM))
    put("Storage", PlanTreatment.Fact(PlanShapes.ENGINE_ENUM))
    put("DefinedValues", PlanTreatment.Subtree { SQLSERVER_DEFINED_VALUES_FIELDS })
    put("Object", PlanTreatment.Subtree { SQLSERVER_OBJECT_FIELDS })
    put("SeekPredicates", PlanTreatment.Subtree { SQLSERVER_SEEK_PREDICATES_FIELDS })
    put("Predicate", PlanTreatment.Subtree { SQLSERVER_SCALAR_OPERATORS })
}

internal val SQLSERVER_TABLE_SCAN_FIELDS: Map<String, PlanTreatment> = buildMap {
    for (label in listOf("Ordered", "ForcedIndex", "ForceScan", "NoExpandHint")) {
        put(label, PlanTreatment.Fact(PlanShapes.XML_BOOLEAN))
    }
    put("Storage", PlanTreatment.Fact(PlanShapes.ENGINE_ENUM))
    put("DefinedValues", PlanTreatment.Subtree { SQLSERVER_DEFINED_VALUES_FIELDS })
    put("Object", PlanTreatment.Subtree { SQLSERVER_OBJECT_FIELDS })
    put("Predicate", PlanTreatment.Subtree { SQLSERVER_SCALAR_OPERATORS })
}

/**
 * **The object one node ran over** — every part of it bracketed, and the alias the statement wrote
 * for it.
 *
 * The alias is a name **the plan declares**: two plans each calling something `v` are not talking
 * about one thing, and the `[v]` of a seek predicate is this one because both key on the recovered
 * spelling. See [PlanSymbols.declareBracketed].
 */
internal val SQLSERVER_OBJECT_FIELDS: Map<String, PlanTreatment> = mapOf(
    "Database" to PlanTreatment.Bracketed(SymbolRole.SCHEMA),
    "Schema" to PlanTreatment.Bracketed(SymbolRole.SCHEMA),
    "Table" to PlanTreatment.Bracketed(SymbolRole.TABLE),
    "Index" to PlanTreatment.Bracketed(SymbolRole.INDEX),
    "Alias" to PlanTreatment.BracketedDeclaration,
    "IndexKind" to PlanTreatment.Fact(PlanShapes.ENGINE_ENUM),
    "Storage" to PlanTreatment.Fact(PlanShapes.ENGINE_ENUM),
    "TableReferenceId" to PlanTreatment.Measured,
    "CloneAccessScope" to PlanTreatment.Fact(PlanShapes.ENGINE_ENUM),
)

internal val SQLSERVER_SEEK_PREDICATES_FIELDS: Map<String, PlanTreatment> = mapOf(
    "SeekPredicateNew" to PlanTreatment.Subtree { SQLSERVER_SEEK_PREDICATE_FIELDS },
    "SeekPredicate" to PlanTreatment.Subtree { SQLSERVER_SEEK_KEY_FIELDS },
)

internal val SQLSERVER_SEEK_PREDICATE_FIELDS: Map<String, PlanTreatment> =
    mapOf("SeekKeys" to PlanTreatment.Subtree { SQLSERVER_SEEK_KEY_FIELDS })

internal val SQLSERVER_SEEK_KEY_FIELDS: Map<String, PlanTreatment> =
    listOf("Prefix", "StartRange", "EndRange", "IsNotNull")
        .associateWith { PlanTreatment.Subtree { SQLSERVER_SEEK_RANGE_FIELDS } }

internal val SQLSERVER_SEEK_RANGE_FIELDS: Map<String, PlanTreatment> = mapOf(
    "ScanType" to PlanTreatment.Fact(PlanShapes.ENGINE_ENUM),
    "RangeColumns" to PlanTreatment.Subtree { SQLSERVER_COLUMN_REFERENCES },
    "RangeExpressions" to PlanTreatment.Subtree { SQLSERVER_SCALAR_OPERATORS },
)

internal val SQLSERVER_DEFINED_VALUES_FIELDS: Map<String, PlanTreatment> =
    mapOf("DefinedValue" to PlanTreatment.Subtree { SQLSERVER_DEFINED_VALUE_FIELDS })

internal val SQLSERVER_DEFINED_VALUE_FIELDS: Map<String, PlanTreatment> = mapOf(
    "ColumnReference" to PlanTreatment.Subtree { SQLSERVER_COLUMN_REFERENCE_FIELDS },
    "ScalarOperator" to PlanTreatment.Subtree { SQLSERVER_SCALAR_OPERATOR_FIELDS },
)

internal val SQLSERVER_NESTED_LOOPS_FIELDS: Map<String, PlanTreatment> = buildMap {
    for (label in listOf("Optimized", "WithOrderedPrefetch", "WithUnorderedPrefetch")) {
        put(label, PlanTreatment.Fact(PlanShapes.XML_BOOLEAN))
    }
    put("OuterReferences", PlanTreatment.Subtree { SQLSERVER_COLUMN_REFERENCES })
    put("Predicate", PlanTreatment.Subtree { SQLSERVER_SCALAR_OPERATORS })
    put("DefinedValues", PlanTreatment.Subtree { SQLSERVER_DEFINED_VALUES_FIELDS })
    put("RelOp", PlanTreatment.Subtree { SQLSERVER_REL_OP_FIELDS })
}

internal val SQLSERVER_HASH_FIELDS: Map<String, PlanTreatment> = mapOf(
    "BitmapCreator" to PlanTreatment.Fact(PlanShapes.XML_BOOLEAN),
    "DefinedValues" to PlanTreatment.Subtree { SQLSERVER_DEFINED_VALUES_FIELDS },
    "HashKeysBuild" to PlanTreatment.Subtree { SQLSERVER_COLUMN_REFERENCES },
    "HashKeysProbe" to PlanTreatment.Subtree { SQLSERVER_COLUMN_REFERENCES },
    "ProbeResidual" to PlanTreatment.Subtree { SQLSERVER_SCALAR_OPERATORS },
    "RelOp" to PlanTreatment.Subtree { SQLSERVER_REL_OP_FIELDS },
)

internal val SQLSERVER_SORT_FIELDS: Map<String, PlanTreatment> = mapOf(
    "Distinct" to PlanTreatment.Fact(PlanShapes.XML_BOOLEAN),
    "DefinedValues" to PlanTreatment.Subtree { SQLSERVER_DEFINED_VALUES_FIELDS },
    "OrderBy" to PlanTreatment.Subtree { SQLSERVER_ORDER_BY_FIELDS },
    "RelOp" to PlanTreatment.Subtree { SQLSERVER_REL_OP_FIELDS },
)

internal val SQLSERVER_ORDER_BY_FIELDS: Map<String, PlanTreatment> =
    mapOf("OrderByColumn" to PlanTreatment.Subtree { SQLSERVER_ORDER_BY_COLUMN_FIELDS })

internal val SQLSERVER_ORDER_BY_COLUMN_FIELDS: Map<String, PlanTreatment> = mapOf(
    "Ascending" to PlanTreatment.Fact(PlanShapes.XML_BOOLEAN),
    "ColumnReference" to PlanTreatment.Subtree { SQLSERVER_COLUMN_REFERENCE_FIELDS },
)

internal val SQLSERVER_COMPUTE_SCALAR_FIELDS: Map<String, PlanTreatment> = mapOf(
    "ComputeSequence" to PlanTreatment.Fact(PlanShapes.XML_BOOLEAN),
    "DefinedValues" to PlanTreatment.Subtree { SQLSERVER_DEFINED_VALUES_FIELDS },
    "RelOp" to PlanTreatment.Subtree { SQLSERVER_REL_OP_FIELDS },
)

internal val SQLSERVER_FILTER_FIELDS: Map<String, PlanTreatment> = mapOf(
    "StartupExpression" to PlanTreatment.Fact(PlanShapes.XML_BOOLEAN),
    "DefinedValues" to PlanTreatment.Subtree { SQLSERVER_DEFINED_VALUES_FIELDS },
    "Predicate" to PlanTreatment.Subtree { SQLSERVER_SCALAR_OPERATORS },
    "RelOp" to PlanTreatment.Subtree { SQLSERVER_REL_OP_FIELDS },
)

internal val SQLSERVER_STREAM_AGGREGATE_FIELDS: Map<String, PlanTreatment> = mapOf(
    "DefinedValues" to PlanTreatment.Subtree { SQLSERVER_DEFINED_VALUES_FIELDS },
    "GroupBy" to PlanTreatment.Subtree { SQLSERVER_COLUMN_REFERENCES },
    "RelOp" to PlanTreatment.Subtree { SQLSERVER_REL_OP_FIELDS },
)

internal val SQLSERVER_TOP_FIELDS: Map<String, PlanTreatment> = mapOf(
    "RowCount" to PlanTreatment.Fact(PlanShapes.XML_BOOLEAN),
    "IsPercent" to PlanTreatment.Fact(PlanShapes.XML_BOOLEAN),
    "WithTies" to PlanTreatment.Fact(PlanShapes.XML_BOOLEAN),
    "Rows" to PlanTreatment.Measured,
    "TopExpression" to PlanTreatment.Subtree { SQLSERVER_SCALAR_OPERATORS },
    "DefinedValues" to PlanTreatment.Subtree { SQLSERVER_DEFINED_VALUES_FIELDS },
    "RelOp" to PlanTreatment.Subtree { SQLSERVER_REL_OP_FIELDS },
)

/**
 * **The operators this product reads out of a `SHOWPLAN_TEXT` rowset** — a closed list, and the line
 * closure of a format whose structure is a drawn tree rather than a nesting.
 *
 * It is [NODE_LABELS] for this engine and it does the same job: a row whose operator is not one of
 * these is a row nothing here classified, and **reading past one is how a value leaves in the part
 * nobody looked at**. A label this list is missing refuses the plan carrying it until a capture and a
 * row follow.
 */
internal val SQLSERVER_OPERATORS: Set<String> = setOf(
    "Adaptive Join", "Assert", "Bitmap", "Clustered Index Delete", "Clustered Index Insert",
    "Clustered Index Merge", "Clustered Index Scan", "Clustered Index Seek",
    "Clustered Index Update", "Collapse", "Columnstore Index Scan", "Compute Scalar",
    "Concatenation", "Constant Scan", "Deleted Scan", "Filter", "Hash Match", "Index Delete",
    "Index Insert", "Index Scan", "Index Seek", "Index Spool", "Index Update", "Inserted Scan",
    "Key Lookup", "Merge Interval", "Merge Join", "Nested Loops", "Parallelism",
    "Parameter Table Scan", "RID Lookup", "Row Count Spool", "Segment", "Sequence",
    "Sequence Project", "Sort", "Split", "Stream Aggregate", "Switch", "Table Delete",
    "Table Insert", "Table Merge", "Table Scan", "Table Spool", "Table Update",
    "Table-valued function", "Top", "UDX", "Window Aggregate", "Window Spool",
)

/**
 * **The two rows this writer prints raw** — recognised so that the plan carrying one can be refused
 * **with the option that would have escaped it**.
 *
 * Everything else in a `SHOWPLAN_TEXT` row is bracketed, and these are the exception the admission
 * rests on being told about: a remote row carries the **linked server's name unbracketed** and the
 * **remote statement verbatim**, so there is no delimiter to find either boundary from and the
 * statement is the user's own SQL sitting in the middle of a line. Nothing can be recovered from
 * that, and the same plan under `SET SHOWPLAN_XML ON` puts every one of them in a slot of its own.
 */
internal val SQLSERVER_REMOTE_OPERATORS: Set<String> = setOf("Remote Query", "Remote Scan")

/**
 * **SQL Server's vocabulary, which is empty — and empty for the opposite reason MySQL's is.**
 *
 * [MYSQL]'s is empty because that engine appends identifiers to a rendered line **with nothing around
 * them**, so there is no spelling a user's object cannot also be printed under and no bare word can
 * safely be kept. SQL Server's writer does the reverse: it **brackets every identifier**, so no bare
 * word can be a user's object and every one of them is safe to keep — without a list, because the
 * brackets rather than the spelling are what argue it.
 *
 * So this vocabulary is empty because nothing needs to look a word up, not because nothing may be
 * preserved. A bare word here is never reported at all, the way punctuation and whitespace are never
 * reported: see [readBracketedExpression]. It exists because [PlanSymbols] takes one.
 */
internal val SQLSERVER = PlanVocabulary(
    words = emptySet(),
    builtins = emptySet(),
    constants = emptySet(),
    phrases = emptyList(),
)
