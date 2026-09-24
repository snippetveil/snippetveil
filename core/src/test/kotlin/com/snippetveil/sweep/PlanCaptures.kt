package com.snippetveil.sweep

import com.snippetveil.core.JSON_PLAN
import com.snippetveil.core.MARIADB_JSON_PLAN
import com.snippetveil.core.MARIADB_TABULAR_PLAN
import com.snippetveil.core.MYSQL_TABULAR_PLAN
import com.snippetveil.core.MYSQL_TREE_PLAN
import com.snippetveil.core.MYSQL_V1_PLAN
import com.snippetveil.core.MYSQL_V2_PLAN
import com.snippetveil.core.ORACLE_GRID_PLAN
import com.snippetveil.core.ORACLE_MONITOR_XML
import com.snippetveil.core.PLAN_FORMATS
import com.snippetveil.core.PlanEngine
import com.snippetveil.core.PlanFormat
import com.snippetveil.core.PlanReading
import com.snippetveil.core.PlanRefusedForm
import com.snippetveil.core.PlanVocabulary
import com.snippetveil.core.SQLSERVER_SHOWPLAN_ALL_PLAN
import com.snippetveil.core.SQLSERVER_STATISTICS_PROFILE_PLAN
import com.snippetveil.core.SQLSERVER_TEXT_PLAN
import com.snippetveil.core.SQLSERVER_TEXT_REMOTE_QUERY
import com.snippetveil.core.SQLSERVER_XML_PLAN
import com.snippetveil.core.TEXT_PLAN
import com.snippetveil.core.XML_PLAN
import com.snippetveil.core.YAML_PLAN
import com.snippetveil.core.framingOf
import com.snippetveil.core.parsePlan

/**
 * **What the corpus says one capture is** — a row of the format vocabulary, or a refusal message.
 *
 * The label is the capture's own claim about itself, made by whatever generated it and **never by
 * the parser**. That separation is the whole of the two zeros: a run that asked the parser what a
 * capture was and then checked the answer against itself would be green whatever the parser did.
 *
 * Two kinds rather than one, because an admitted format still has shapes inside it this product
 * recognises and refuses: a PostgreSQL text plan carrying a `Settings:` row is a capture of the
 * `text` row *and* a refusal, and a label that could only say `text` would make the stated refusal
 * look like a false admission row.
 */
internal sealed class PlanCaptureLabel {

    /** What the corpus spells this label, and what a report prints. */
    abstract val label: String

    /** **A capture of one vocabulary row's form.** The row's [PlanFormat.state] says what must happen. */
    class OfFormat(val format: PlanFormat) : PlanCaptureLabel() {
        override val label: String get() = format.name
    }

    /**
     * **A capture of a shape one refusal message names** — refused whichever format row recognised
     * it, and refused with that message and no other.
     */
    class OfRefusal(val form: PlanRefusedForm) : PlanCaptureLabel() {
        override val label: String get() = refusalLabelOf(form)
    }
}

/** How a refusal message is spelled as a corpus label — `refusal-mysql-tree`. */
internal fun refusalLabelOf(form: PlanRefusedForm): String =
    "refusal-" + form.name.lowercase().replace('_', '-')

/**
 * The label [name] stands for, or `null` where it names neither a vocabulary row nor a refusal
 * message — which the instrument reports as a finding rather than skipping. A capture nobody can say
 * what it is of is not a capture this run may count.
 */
internal fun captureLabelOf(name: String): PlanCaptureLabel? {
    PLAN_FORMATS.firstOrNull { it.name == name }?.let { return PlanCaptureLabel.OfFormat(it) }
    return PlanRefusedForm.entries.firstOrNull { refusalLabelOf(it) == name }?.let(PlanCaptureLabel::OfRefusal)
}

/**
 * **Where a committed capture came from** — recorded on the row rather than in a comment, because the
 * whole fixture rule here turns on it.
 *
 * > **Committed plan fixtures are lifted from the corpus, never hand-written.**
 *
 * That rule relaxes the project's all-synthetic rule, and it relaxes it **only** because the reason
 * behind that rule — committing a real artifact risks committing a leak — is absent for a capture
 * taken by a generator against a throwaway schema in a container. A hand-written plan is a guess at a
 * format whose every surprise this work paid to discover, which is why the relaxation is worth
 * having at all.
 *
 * So the provenance of each committed fixture is stated here, truthfully, and the instrument reports
 * the split. **It is not asserted**, and that is deliberate: an assertion that every fixture is
 * corpus-lifted would go red on the tree as it stands, and a rule enforced by turning the build red
 * on work nobody has done yet is a rule that gets deleted. What *is* asserted is the trap — see
 * [PlanProvenance] — which is the licence for the relaxation and holds whatever a row here claims.
 */
internal enum class PlanCaptureOrigin {

    /** **Lifted from the corpus**, which is what every committed fixture is supposed to be. */
    CORPUS,

    /**
     * **Written from a reading of the engine's documented output**, in this repository, by the
     * session that added the format.
     *
     * Every committed fixture is this today. It is the same provenance `MysqlPlanFixtures`,
     * `SqlServerPlanFixtures` and `OraclePlanFixtures` record in their own headers, and it bounds
     * what any of them is evidence of: the rules hold over *these* texts, and none of them is
     * evidence that the engine's text is shaped this way.
     */
    WRITTEN_FROM_DOCUMENTATION,
}

/**
 * **One capture: what it is of, where it came from, and the text itself.**
 *
 * @param name what to call it in a report — a file name at corpus scale, a fixture's own name here
 */
internal class PlanCapture(
    val name: String,
    val label: PlanCaptureLabel,
    val text: String,
    val origin: PlanCaptureOrigin,
) {

    /**
     * The vocabulary row that recognised this text, or `null` where none or more than one did.
     *
     * **The client frame comes off first**, exactly as it does in the reading path: a `psql`-framed
     * plan is recognised by nobody with the frame still on, and a capture that fell out of the
     * engine question here would fall out of the oracle too — silently, which is the shape this
     * instrument exists to refuse.
     */
    val recognisedBy: PlanFormat?
        get() = framingOf(text).inner.let { inner -> PLAN_FORMATS.filter { it.recognises(inner) }.singleOrNull() }

    /**
     * **The vocabulary row this capture is a capture of**, by its own label — `null` where the label
     * names a refusal message or nothing at all.
     *
     * The walk from a label to a row recurs everywhere a number is attributed per format, and it is
     * here rather than at each of those sites so that *what the corpus claimed* is asked in one way.
     */
    val format: PlanFormat? get() = (label as? PlanCaptureLabel.OfFormat)?.format

    /** The engine whose rider reads this capture, by its label first and its recogniser second. */
    val engine: PlanEngine? get() = format?.engine ?: recognisedBy?.engine

    /** The rider a leak oracle over this capture is built under, or `null` for an engine with none. */
    val rider: PlanVocabulary? get() = engine?.let(::riderOf)

    /** What [parsePlan] makes of it — asked once, because a capture is read once. */
    val reading: PlanReading by lazy { parsePlan(text) }
}

/**
 * **A PostgreSQL text plan carrying the settings row** — the shape whose raw, unescaped single
 * quotes make a name unrecoverable from the text, and the one refusal in this product that hands the
 * user a better option instead of a verdict.
 */
internal val SETTINGS_ROW_PLAN: String = """
    |Seq Scan on visits  (cost=0.00..1.00 rows=1 width=4)
    |Settings: search_path = 'billing, "${'$'}user", public'
""".trimMargin()

/**
 * **A plan whose relation is named after an engine keyword** — the named assertion standing in for
 * the hole the oracle's subtraction opens.
 *
 * `Sort` is a word the PostgreSQL printer writes as a node type and a plan row, so the universe
 * subtracts it and no oracle built here can report it surviving. What answers that is this capture
 * and the assertion over it: the relation named `Sort` must come out a **table placeholder**, which
 * is a statement about the reader rather than about the oracle.
 *
 * It is a JSON plan because JSON is where the reader is told which slot holds a relation: the same
 * name in a text plan would be asserting something about the line rules instead.
 */
internal val KEYWORD_NAMED_TABLE_PLAN: String = """
    |[
    |  {
    |    "Plan": {
    |      "Node Type": "Seq Scan",
    |      "Parallel Aware": false,
    |      "Relation Name": "Sort",
    |      "Schema": "Hash",
    |      "Alias": "Filter",
    |      "Startup Cost": 0.00,
    |      "Total Cost": 1.00,
    |      "Plan Rows": 1,
    |      "Plan Width": 4
    |    },
    |    "Planning Time": 0.011,
    |    "Execution Time": 0.022
    |  }
    |]
""".trimMargin()

/**
 * **The committed plan fixtures: one per admitted format, one per refusal message.**
 *
 * This is the subset the two zeros run over **on every pull request**, and it is the subset the
 * fixture-provenance trap runs over. At corpus scale the same two zeros run in the instrument and
 * nowhere else; that is the source half's shape exactly — committed fixtures gate pull requests, the
 * real sweep is a human-run release gate.
 *
 * **Every row here is a fixture that already exists in `com.snippetveil.core`**, and none of them was
 * lifted from a corpus, because there is no corpus in or behind this repository yet. The registry
 * says so on each row rather than leaving it to be assumed, and `PlanCoverageTest` holds the two
 * coverage claims that *can* be held: one row per admitted format, and one per refusal message.
 *
 * **The clause's third arm is not met and is named rather than elided.** It reads *one per admitted
 * format, one per refusal message, **and every capture recorded as surprising***, and the third arm
 * is not a property of this list at all: it is a property of a corpus that marks a capture
 * surprising when it is taken. There is no such corpus, so there is nothing for a coverage rule to
 * read and nothing here pretends otherwise. What it will take is a generator that records the mark
 * and a rule in `PlanSweep` asserting every marked capture has a row here — a rule that cannot be
 * written against a corpus shape nobody has produced.
 */
internal val COMMITTED_PLAN_CAPTURES: List<PlanCapture> = listOf(
    committed("postgres-text", "text", TEXT_PLAN),
    committed("postgres-json", "json", JSON_PLAN),
    committed("postgres-yaml", "yaml", YAML_PLAN),
    committed("postgres-xml", "xml", XML_PLAN),
    committed("mysql-json-v2", "mysql-json-v2", MYSQL_V2_PLAN),
    committed("mysql-json-v1", "mysql-json-v1", MYSQL_V1_PLAN),
    committed("mysql-tree", "mysql-tree", MYSQL_TREE_PLAN),
    committed("mysql-tabular", "mysql-tabular", MYSQL_TABULAR_PLAN),
    committed("mariadb-json", "mariadb-json", MARIADB_JSON_PLAN),
    committed("mariadb-tabular", "mariadb-tabular", MARIADB_TABULAR_PLAN),
    committed("sqlserver-showplan-xml", "sqlserver-showplan-xml", SQLSERVER_XML_PLAN),
    committed("sqlserver-showplan-text", "sqlserver-showplan-text", SQLSERVER_TEXT_PLAN),
    committed("sqlserver-showplan-all", "sqlserver-showplan-all", SQLSERVER_SHOWPLAN_ALL_PLAN),
    committed("sqlserver-statistics-profile", "sqlserver-statistics-profile", SQLSERVER_STATISTICS_PROFILE_PLAN),
    committed("oracle-grid-text", "oracle-grid-text", ORACLE_GRID_PLAN),
    committed("oracle-sql-monitor-xml", "oracle-sql-monitor-xml", ORACLE_MONITOR_XML),

    // The refusal messages, one capture each. Four of them are a format row's own message and are
    // already above; these are the three that are not — a shape inside an admitted format, and the
    // engine whose every form shares one message.
    committed("postgres-text-settings-row", refusalLabelOf(PlanRefusedForm.POSTGRES_TEXT_RAW_NAME_ROW), SETTINGS_ROW_PLAN),
    committed("mysql-tree-refusal", refusalLabelOf(PlanRefusedForm.MYSQL_TREE), MYSQL_TREE_PLAN),
    committed("mysql-tabular-refusal", refusalLabelOf(PlanRefusedForm.MYSQL_TABULAR), MYSQL_TABULAR_PLAN),
    committed("mariadb-refusal", refusalLabelOf(PlanRefusedForm.MARIADB), MARIADB_JSON_PLAN),
    committed(
        "sqlserver-showplan-text-remote",
        refusalLabelOf(PlanRefusedForm.SQLSERVER_SHOWPLAN_TEXT_REMOTE),
        SQLSERVER_TEXT_REMOTE_QUERY,
    ),
    committed(
        "sqlserver-showplan-all-refusal",
        refusalLabelOf(PlanRefusedForm.SQLSERVER_SHOWPLAN_ALL),
        SQLSERVER_SHOWPLAN_ALL_PLAN,
    ),
    committed(
        "sqlserver-statistics-profile-refusal",
        refusalLabelOf(PlanRefusedForm.SQLSERVER_STATISTICS_PROFILE),
        SQLSERVER_STATISTICS_PROFILE_PLAN,
    ),

    // **The named assertion that closes the oracle's one permanent hole.** Subtracting the rider's
    // keyword rows takes every token colliding with engine chrome out of the universe, so no oracle
    // here can catch a relation genuinely named after one. That is answered by naming the case
    // rather than by narrowing the universe — see `PlanCoverageTest`.
    committed("postgres-json-keyword-named-table", "json", KEYWORD_NAMED_TABLE_PLAN),
)

/**
 * A committed capture, whose origin is the one every committed fixture has today.
 *
 * The label is resolved rather than taken on trust: a fixture naming a format row that no longer
 * exists is a registry gone stale, and it fails here rather than quietly dropping out of the subset
 * the two zeros run over.
 */
private fun committed(name: String, label: String, text: String): PlanCapture {
    val resolved = requireNotNull(captureLabelOf(label)) {
        "the committed capture `$name` is labelled `$label`, which is neither a format row nor a refusal message"
    }
    return PlanCapture(name, resolved, text, PlanCaptureOrigin.WRITTEN_FROM_DOCUMENTATION)
}

/** The three keyword spellings [KEYWORD_NAMED_TABLE_PLAN] names its relation, schema and alias. */
internal val KEYWORD_NAMES: List<String> = listOf("Sort", "Hash", "Filter")
