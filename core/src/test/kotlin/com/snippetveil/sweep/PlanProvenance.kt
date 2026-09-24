package com.snippetveil.sweep

import com.snippetveil.core.MYSQL_ACCESS_TYPES
import com.snippetveil.core.MYSQL_MESSAGES
import com.snippetveil.core.MYSQL_V1_QUERY_FIELDS
import com.snippetveil.core.MYSQL_V2_QUERY_FIELDS
import com.snippetveil.core.ORACLE_GRID_COLUMNS
import com.snippetveil.core.ORACLE_HINT_WORDS
import com.snippetveil.core.ORACLE_MONITOR_FIELDS
import com.snippetveil.core.ORACLE_OUTLINE_HINTS
import com.snippetveil.core.ORACLE_QUERY_BLOCK
import com.snippetveil.core.ORACLE_TYPE_WORDS
import com.snippetveil.core.ORACLE_VERSION_HINTS
import com.snippetveil.core.POSTGRES_FLAGGED_SETTINGS
import com.snippetveil.core.POSTGRES_QUERY_FIELDS
import com.snippetveil.core.POSTGRES_TEXT_FIELDS
import com.snippetveil.core.PlanEngine
import com.snippetveil.core.SQLSERVER_SHOWPLAN_FIELDS
import com.snippetveil.core.flattenedFieldsOf

/**
 * **The fixture-provenance trap: the entire licence for committing real captures.**
 *
 * The project's fixtures are synthetic, twice stated, and both times the reason is that committing a
 * real artifact risks committing a leak. **That reason is absent here.** A plan capture is taken by a
 * generator, in a container, against a throwaway schema; there is nothing in it to leak, and
 * extending the rule past its reason would cost something real — **a hand-written plan is a guess at
 * a format whose every surprise this work paid to discover.** One engine printing a newline inside a
 * name as a space, another printing an undoubled quote, a third emitting JSON that is not valid
 * JSON: no fixture author invents those.
 *
 * So the rule is relaxed, and **the relaxation is converted into an assertion rather than left as an
 * instruction**:
 *
 * > **Every identifier in a committed plan fixture is drawn from the generator's fixed schema
 * > vocabulary.**
 *
 * *Only commit captures from the generator* is precisely the category a self-asserting harness exists
 * to replace, and it is a requirement that fails in the **green** direction — nobody notices the day
 * it stops being kept. This goes red instead, the day somebody drops in a capture from a real
 * database. **If the trap is ever removed, the relaxation goes with it.**
 *
 * ### What the trap does not do, stated because it will otherwise be assumed
 *
 * It certifies that a fixture carries **no foreign identifier**. It does **not** certify that a
 * fixture came from a capture: a hand-written fixture using only the schema vocabulary passes, and
 * every fixture committed here today is exactly that. See [PlanCaptureOrigin], which records the
 * provenance of each row truthfully, and `PlanSweepReport`, which reports the split.
 *
 * ### The one blind spot, stated rather than hidden
 *
 * **A chrome spelling is allowed wherever it appears — including where the engine delimited it.** So
 * a real database's relation genuinely called `Message`, `Name` or `Level` would pass this trap.
 *
 * The oracle next door does not have that hole: it keeps a delimited spelling whatever it is spelled,
 * because a delimited token is always a name. The trap cannot copy that, and the reason is the
 * reading rather than a preference. [spellingsIn] is engine-neutral text splitting: it cannot tell an
 * engine's identifier delimiter from a document's own string quoting, so **every quoted value in a
 * JSON or XML plan comes back delimited** — every node type, every enumerated attribute, every word
 * of a namespace URI. A trap that refused chrome inside delimiters would flag `Seq`, `Scan` and
 * `Compute` in every structured capture ever committed, which is a trap nobody keeps.
 *
 * The hole is narrower than it sounds and it is narrowed on purpose: the generator's schema names its
 * keyword-collision relations itself — `Sort`, `Hash`, `Filter` are [SCHEMA_VOCABULARY] rows — so the
 * case the named assertion is about is carried by the schema half rather than by this one.
 *
 * ### Why the allowance has two halves
 *
 * A capture is mostly the engine's own words. So an identifier-shaped token is allowed when it is in
 * the [SCHEMA_VOCABULARY] — the generator's own schema — **or** when it is a spelling the engine's
 * own printer writes, which is [chrome]. The second half is built from the product's existing
 * vocabulary and inventory rows wherever one holds the spelling, and from [PRINTER_SPELLINGS] where
 * none does. A fixture that introduces a spelling in neither is a fixture whose provenance is in
 * doubt, which is exactly the question being asked.
 */
internal object PlanProvenance {

    /**
     * **The generator's fixed schema vocabulary** — every name and value its throwaway schemas hold,
     * and the whole of what a committed capture may carry that is not the engine's own word.
     *
     * It is small and it is fixed on purpose: the trap's question is *did this come from the
     * generator*, and a vocabulary that grew whenever a fixture needed a new name would answer *yes*
     * by construction. A capture needing a name that is not here needs the generator's schema
     * changed and the corpus regenerated — which is the cost that makes the assertion mean something.
     *
     * **Its first reading was taken off the fixtures already committed, and that is worth saying
     * plainly**, because it is the *yes by construction* failure above in its first and weakest
     * form: there is no generator yet, so the only schema available to write down was the cast those
     * fixtures already use. What the trap asserts today is therefore *nothing here came from outside
     * that cast* — which is a real assertion, and it is weaker than the one it will make the day a
     * generator declares this schema and the fixtures are regenerated against it. It goes red on a
     * capture from a real database either way, which is the property the relaxation rests on.
     *
     * The spellings are one schema written the way each engine folds it: PostgreSQL and MySQL print
     * lower case, Oracle upper, SQL Server as declared.
     */
    val SCHEMA_VOCABULARY: Set<String> = buildSet {
        // The relations, the schemas and the access paths over them.
        addAll(listOf("visits", "invoices", "billing", "public", "shop", "dbo"))
        addAll(listOf("visits_pkey", "visits_by_owner", "invoices_pkey"))
        addAll(listOf("Visits", "Invoices", "IX_Visits_OwnerId", "PK_Invoices"))
        addAll(listOf("VISITS", "INVOICES", "VISITS_PKEY", "INVOICES_PKEY"))

        // **The relations the generator deliberately names after engine keywords.** They are here
        // rather than allowed as chrome because they are the generator's schema: the named assertion
        // that closes the oracle's one permanent hole needs a relation genuinely called `Sort`, and
        // a fixture carrying one has to pass the trap on the schema half.
        addAll(listOf("Sort", "Hash", "Filter"))

        // The columns.
        addAll(listOf("id", "status", "owner_id", "visit_id", "created_at", "amount"))
        addAll(listOf("Id", "Status", "OwnerId", "VisitId", "CreatedAt", "Amount"))
        addAll(listOf("ID", "STATUS", "OWNER_ID", "VISIT_ID", "CREATED_AT", "AMOUNT"))

        // The aliases a generated statement writes, and the bind names it binds by.
        addAll(listOf("v", "i", "V", "I", "city", "opened", "limit"))

        // The values the generated data holds. A literal is not a name, and it is here for the same
        // reason the names are: it is the generator's, and a value from somewhere else is a finding.
        addAll(listOf("open", "closed", "paid", "verbose", "Portland", "SEP"))

        // The application-scoped settings and namespaces a generated session sets, which are named
        // by whoever set them rather than by the engine.
        addAll(listOf("acme", "audit_level", "acme.audit_level"))

        // The deployment names a generated container carries — the schema owner the statement runs
        // as, the pluggable database it runs in, and the linked server it reaches through. Each is a
        // database object outside the four name kinds, and each is the generator's all the same.
        addAll(listOf("APP_OWNER", "orclpdb", "LEDGER"))
    }

    /**
     * **The spellings an engine's printer writes that no vocabulary or inventory row holds** — the
     * residue, enumerated, with the reason each class of them is not a row somewhere else.
     *
     * Every entry here is a word this product's readers already walk past safely, in a position the
     * reader knows is the engine's: a node type, an enumerated attribute value, a word of a fixed
     * namespace URI, a grid's own section title. None of them is a *rule*, which is why none of them
     * is a vocabulary row: the readers are protected by the slot, and this list exists only so that a
     * trap reading the page as text can tell the engine's words from somebody's schema.
     *
     * **A fixture that needs a row added here is a fixture worth looking at twice.** That is the
     * cost, and it is the point.
     */
    val PRINTER_SPELLINGS: Set<String> = buildSet {
        // **`Sort`, `Hash` and `Filter` are deliberately absent**, although the printer writes all
        // three: they are the generator's keyword-collision relations and belong to
        // [SCHEMA_VOCABULARY]. A spelling claimed by both lists would make the trap's two halves one
        // list, which is what `PlanProvenanceTest` holds them apart over.
        // The node types, join strategies and scan directions PostgreSQL prints as enumerated
        // values. They are `Fact(ENGINE_ENUM)` slots to the reader — checked by shape, never by a
        // list — so there is no row anywhere holding the spellings themselves.
        addAll(
            listOf(
                "Nested", "Loop", "Seq", "Scan", "Index", "Only", "Bitmap", "Heap", "Tid", "Subquery",
                "Function", "Table", "Sample", "Values", "CTE", "Named", "Tuplestore", "WorkTable",
                "Foreign", "Custom", "Materialize", "Memoize", "Incremental", "Group",
                "Aggregate", "WindowAgg", "Unique", "SetOp", "LockRows", "Limit", "Merge",
                "Join", "Gather", "Append", "Recursive", "Union", "Result", "ProjectSet", "ModifyTable",
                "Insert", "Update", "Delete", "Inner", "Outer", "Left", "Right", "Full", "Semi", "Anti",
                "Forward", "Backward", "NoMovement", "Plain", "Sorted", "Hashed", "Mixed", "Simple",
                "Partial", "Finalize", "Subplan", "InitPlan", "Parent", "Relationship", "Member",
                "quicksort", "top", "N", "heapsort", "external", "sort", "Memory", "Disk", "Binary",
                // The words the text format's own line grammar writes, which are the line's
                // punctuation rather than a field label: `(cost=… width=…) (actual … loops=1)`.
                "width", "actual", "loops", "on", "ms", "rows", "cost", "using", "never", "executed",
                // The root element and namespace of PostgreSQL's XML plan, a word at a time.
                "explain", "postgresql", "search_path",
            ),
        )

        // The words of the two namespace URIs SQL Server's Showplan writes, and the XML attribute
        // prefixes that carry them. The URIs themselves are pinned as regexes in the inventory; a
        // trap reading the page as text meets them a word at a time.
        addAll(
            listOf(
                "xsi", "xsd", "xmlns", "http", "www", "w3", "org", "XMLSchema", "instance",
                "schemas", "microsoft", "com", "sqlserver", "showplan",
            ),
        )

        // SQL Server's enumerated operator and mode spellings, and the names its optimizer invents
        // for a computed column. `Expr1002` is not anybody's column: it is the name of nothing
        // anybody wrote, which is why the reader gives it a treatment of its own.
        addAll(
            listOf(
                "Compute", "Scalar", "Seek", "Clustered", "NonClustered", "Row", "Batch", "SELECT",
                "INSERT", "UPDATE", "DELETE", "FULL", "TRIVIAL", "CONVERT", "IMPLICIT", "OBJECT",
                "PLAN", "ROW", "Expr1002", "Expr1003", "Tbl1001", "nvarchar", "varchar", "Global",
                "Session", "ShowPlanXML", "CONVERT_IMPLICIT", "FORWARD", "RowStore", "EQ", "as",
                "OUTER", "REFERENCES", "SEEK", "SOURCE", "QUERY", "RMT",
                // The wide rowsets' column headings. Both forms are refused, so no inventory row
                // covers a single one of them.
                "StmtText", "StmtId", "NodeId", "Parent", "PhysicalOp", "LogicalOp", "Argument",
                "DefinedValues", "EstimateRows", "EstimateIO", "EstimateCPU", "AvgRowSize",
                "TotalSubtreeCost", "OutputList", "Warnings", "Parallel", "EstimateExecutions",
                "PLAN_ROW", "Executes", "Rows",
                // `Id` is deliberately absent: it is a column the generator's schema declares, and a
                // spelling claimed as both the generator's and the engine's would make the trap's
                // two halves one list. The same goes for `id` and `limit` below.
            ),
        )

        // MySQL's printer words for its tabular and tree forms, and the client's own column
        // headings. The tabular form is refused, so no inventory row covers its header at all.
        addAll(
            listOf(
                "select_type", "partitions", "possible_keys", "key_len", "ref", "filtered", "Extra",
                "const", "SIMPLE", "Level", "Code", "Message", "Note", "lookup", "using",
                "r_rows", "r_filtered", "r_loops", "r_total_time_ms", "index_lookup",
                // The keywords of the statement this engine echoes above its own plan, spelled the
                // way its printer spells them — lower case, where every rider row is upper.
                "select", "from", "where", "order", "by", "group", "having", "join", "on",
            ),
        )

        // Oracle's grid chrome: the section titles printed under the plan, the query block names the
        // optimizer invents, and the words of the hint grammar that are not hints.
        addAll(
            listOf(
                "Plan", "hash", "value", "Predicate", "Information", "identified", "by", "operation",
                "Outline", "Data", "SEL", "INS", "UPD", "DEL", "MRG", "SET", "access", "filter",
                "TABLE", "ACCESS", "INDEX", "RANGE", "SCAN", "ROWID", "NESTED", "LOOPS", "STATEMENT",
                "BY", "FULL", "UNIQUE", "Rows", "Bytes", "Cost", "CPU", "Time", "Operation", "Name",
                "Starts", "Buffers", "Used", "Mem", "Elapsed",
                // The SQL Monitor report's own words — the report element, the type it was asked
                // for, the status it finished in, the client that ran it, and the metric a monitor
                // list is ordered by.
                "report", "XML", "DONE", "EXECUTING", "Plus", "monitor", "elapsed_time",
            ),
        )
    }

    /**
     * **The shapes an engine generates an identifier in** — a shape rather than a list, for the
     * reason every shape in this product is a shape: the list would have to be per capture.
     *
     * A SQL id, a plan hash and a query hash are printed into a plan and are nobody's schema. They
     * differ in every capture, so a trap holding their spellings would go red on the next capture
     * from the same generator — which is a trap that gets deleted rather than one that catches
     * anything.
     *
     * What they have in common is **alternation**: a generated token runs letters into digits and
     * back several times, which a name somebody chose does not. Three alternations is the threshold,
     * and its cost is stated rather than hidden — a schema object genuinely spelled like a digest is
     * one this trap allows. That is a narrower hole than the one a per-capture list would open.
     */
    fun isGenerated(spelling: String): Boolean {
        if (spelling.length < MINIMUM_GENERATED_LENGTH || spelling.any { !it.isLetterOrDigit() }) return false
        val alternations = (1 until spelling.length).count { spelling[it].isDigit() != spelling[it - 1].isDigit() }
        return alternations >= MINIMUM_ALTERNATIONS
    }

    /** Shorter than this and a spelling is a name somebody chose. See [isGenerated]. */
    private const val MINIMUM_GENERATED_LENGTH = 8

    /** How many letter-to-digit turns say *generated*. See [isGenerated]. */
    private const val MINIMUM_ALTERNATIONS = 3

    /**
     * Every spelling in [capture] that the generator's schema does not hold and the engine's printer
     * does not write — the trap's finding, and the whole of what it reports.
     *
     * The universe is [spellingsIn]'s, not [universeOf]'s: the rider's keyword subtraction is part of
     * [chrome] here, so the trap allows what the oracle merely declines to report, and the two stay
     * one reading of the page rather than two.
     */
    fun foreignIn(text: String): List<String> {
        val allowed = SCHEMA_VOCABULARY + chrome
        return spellingsIn(text).map { it.spelling }
            .filter { it !in allowed && !isGenerated(it) && !isAQueryBlock(it) }
    }

    /**
     * Whether this is a name Oracle's optimizer gives a query block — `SEL$1`, `INS$2`.
     *
     * It is the one generated spelling that is **not** a digest, so [isGenerated] does not reach it,
     * and the shape is already pinned in the inventory rather than re-spelled here.
     */
    private fun isAQueryBlock(spelling: String): Boolean = ORACLE_QUERY_BLOCK.matches(spelling)

    /**
     * **Everything the engine's own printer writes**, from the rows this product already keeps plus
     * [PRINTER_SPELLINGS].
     *
     * **One set for every engine, and not a set per engine.** A trap that narrowed by engine would
     * be a second place where *which engine is this* is decided, and the reading path is built so
     * that nothing decides that before a format is chosen — so there is deliberately nothing here to
     * pass an engine to. What it costs is a spelling one engine writes being allowed in another's
     * capture, which is not the question this trap is asking.
     */
    val chrome: Set<String> by lazy {
        buildSet {
            for (engine in PlanEngine.entries) {
                val rider = riderOf(engine) ?: continue
                addAll(rider.words)
                addAll(rider.builtins)
                addAll(rider.constants)
                addAll(rider.phraseWords)
            }
            for (root in INVENTORY_ROOTS) addAll(wordsIn(flattenedFieldsOf(root).keys))
            addAll(wordsIn(POSTGRES_FLAGGED_SETTINGS))
            addAll(wordsIn(MYSQL_MESSAGES))
            addAll(wordsIn(MYSQL_ACCESS_TYPES))
            addAll(wordsIn(ORACLE_TYPE_WORDS))
            addAll(wordsIn(ORACLE_OUTLINE_HINTS))
            addAll(wordsIn(ORACLE_VERSION_HINTS))
            addAll(wordsIn(ORACLE_HINT_WORDS))
            addAll(PRINTER_SPELLINGS)
        }
    }

    /** Every field inventory in the product, by its root. A label is chrome wherever it is written. */
    private val INVENTORY_ROOTS = listOf(
        POSTGRES_QUERY_FIELDS,
        POSTGRES_TEXT_FIELDS,
        MYSQL_V1_QUERY_FIELDS,
        MYSQL_V2_QUERY_FIELDS,
        SQLSERVER_SHOWPLAN_FIELDS,
        ORACLE_GRID_COLUMNS,
        ORACLE_MONITOR_FIELDS,
    )

    /** The identifier runs inside a set of labels — `Relation Name` is two spellings on the page. */
    private fun wordsIn(labels: Collection<String>): Set<String> =
        labels.flatMapTo(mutableSetOf()) { label -> spellingsIn(label).map { it.spelling } }
}
