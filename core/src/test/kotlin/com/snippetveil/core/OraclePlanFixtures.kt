package com.snippetveil.core

/**
 * **One Oracle plan, printed the two ways this product reads** — and the shapes around it that are
 * refused.
 *
 * ### Written from a reading of the engine's output, not from a capture in this repository
 *
 * These fixtures are what Oracle's printers are understood to write, and **no default-settings
 * capture of either format was taken here** — the same gap `MysqlPlanFixtures` and
 * `SqlServerPlanFixtures` record. It bounds what the tests below can be evidence of: they hold the
 * *rules* — the counting argument, the drop, the hint grammar, the closures — over text shaped like
 * the engine's, and they are not evidence that the text is shaped exactly like the engine's.
 *
 * The same query throughout: two relations, one aliased and indexed, a predicate carrying a literal,
 * and an outline naming the query block the plan was chosen for.
 */
internal val ORACLE_GRID_PLAN = """
    |Plan hash value: 2709293936
    |
    |-------------------------------------------------------------------------------------
    || Id  | Operation                    | Name        | Rows  | Bytes | Cost (%CPU)| Time     |
    |-------------------------------------------------------------------------------------
    ||   0 | SELECT STATEMENT             |             |     2 |   136 |     4   (0)| 00:00:01 |
    ||   1 |  NESTED LOOPS                |             |     2 |   136 |     4   (0)| 00:00:01 |
    ||*  2 |   TABLE ACCESS BY INDEX ROWID| VISITS      |     1 |    36 |     3   (0)| 00:00:01 |
    ||*  3 |    INDEX RANGE SCAN          | VISITS_PKEY |     1 |       |     2   (0)| 00:00:01 |
    ||*  4 |   TABLE ACCESS FULL          | INVOICES    |     2 |    64 |     1   (0)| 00:00:01 |
    |-------------------------------------------------------------------------------------
    |
    |Predicate Information (identified by operation id):
    |---------------------------------------------------
    |
    |   2 - filter("V"."STATUS"='open')
    |   3 - access("V"."ID"=42)
    |   4 - filter("I"."VISIT_ID"="V"."ID")
    |
    |Outline Data
    |-------------
    |
    |  /*+
    |      BEGIN_OUTLINE_DATA
    |      IGNORE_OPTIM_EMBEDDED_HINTS
    |      OPTIMIZER_FEATURES_ENABLE('19.1.0')
    |      DB_VERSION('19.1.0')
    |      ALL_ROWS
    |      OUTLINE_LEAF(@"SEL${'$'}1")
    |      INDEX_RS_ASC(@"SEL${'$'}1" "V"@"SEL${'$'}1" ("VISITS"."ID"))
    |      FULL(@"SEL${'$'}1" "I"@"SEL${'$'}1")
    |      LEADING(@"SEL${'$'}1" "V"@"SEL${'$'}1" "I"@"SEL${'$'}1")
    |      USE_NL(@"SEL${'$'}1" "I"@"SEL${'$'}1")
    |      END_OUTLINE_DATA
    |  */
""".trimMargin()

/**
 * **The same plan pasted from the grid's own rule**, which is what a copy that leaves the anchor line
 * behind begins with.
 *
 * It is the other admitted opening, and it is a fixture of its own because the predicate has to
 * accept both: the plan-hash line is the anchor the **cursor**-display variant needs, since that
 * variant prints the statement above the plan, and a plan displayed from the plan table has nothing
 * above its grid at all.
 */
internal val ORACLE_GRID_ROWS_ONLY = ORACLE_GRID_PLAN.substringAfter("\n\n")

/**
 * **The same grid printed with what actually ran**, which is the other half of this format's column
 * vocabulary.
 *
 * The estimated columns and the measured ones are never printed together, so the two fixtures are
 * what cover the vocabulary between them. Every cell here is a measurement, and the assertion they
 * carry is that **every one of them comes back exactly as the engine printed it**: a plan is pasted
 * for these numbers.
 */
internal val ORACLE_GRID_ACTUALS = """
    |Plan hash value: 2709293936
    |
    |-------------------------------------------------------------------------------------------
    || Id  | Operation        | Name   | Starts | E-Rows | Cost | A-Rows |   A-Time   | Buffers | Reads | Writes | TempSpc | OMem | 1Mem | O/1/M | Used-Mem | Used-Tmp |
    |-------------------------------------------------------------------------------------------
    ||   0 | SELECT STATEMENT |        |      1 |        |    4 |      2 |00:00:00.01 |       7 |     0 |      0 |         |      |      |       |          |          |
    ||   1 |  SORT ORDER BY   |        |      1 |      2 |    4 |      2 |00:00:00.01 |       7 |     0 |      0 |    1024 | 2048 | 2048 | 1/0/0 |     2048 |     1024 |
    ||   2 |   TABLE ACCESS FULL| VISITS |    1 |      2 |    3 |      2 |00:00:00.01 |       7 |     0 |      0 |         |      |      |       |          |          |
    |-------------------------------------------------------------------------------------------
""".trimMargin()

/**
 * **A name carrying the grid's own separator** — the case the whole admission of this format rests
 * on.
 *
 * Oracle escapes nothing inside the grid, so a table named `A|B` prints raw and **adds a cell**. The
 * row then draws one more separator than the header did, which is the check that refuses it. Nothing
 * is hidden by the character; it can only reveal the row as malformed.
 */
internal val ORACLE_GRID_FORGED_CELL = """
    |Plan hash value: 2709293936
    |
    |----------------------------------------------------------
    || Id  | Operation          | Name        | Rows  | Bytes |
    |----------------------------------------------------------
    ||   0 | SELECT STATEMENT   |             |     2 |   136 |
    ||   1 |  TABLE ACCESS FULL | ODD|TABLE   |     2 |    64 |
    |----------------------------------------------------------
""".trimMargin()

/**
 * **A name written in wide characters**, whose display width moves every separator after it.
 *
 * A reader slicing cells by column offset mis-slices this row and takes half a name; a reader
 * counting separators does not. The padding is deliberately what a terminal would draw for the wide
 * name rather than what a character count would.
 */
internal val ORACLE_GRID_WIDE_NAME = """
    |Plan hash value: 2709293936
    |
    |----------------------------------------------------------
    || Id  | Operation          | Name     | Rows  | Bytes |
    |----------------------------------------------------------
    ||   0 | SELECT STATEMENT   |          |     2 |   136 |
    ||   1 |  TABLE ACCESS FULL | 訪問記録 |     2 |    64 |
    |----------------------------------------------------------
""".trimMargin()

/**
 * **The grid as the standard client wraps it at its default line width.**
 *
 * Every row breaks where the client's line ends, so the rows that continue have lost the separator
 * that closed them. This is **the natural default-settings Oracle paste**, and it refuses — with the
 * general verdict, because what arrived is an admitted form the client damaged rather than a form
 * this product recognises and rejects.
 */
internal val ORACLE_GRID_WRAPPED = """
    |Plan hash value: 2709293936
    |
    |--------------------------------------------------------------
    |------------------------
    || Id  | Operation                    | Name        | Rows  | B
    |ytes | Cost (%CPU)| Time     |
    |--------------------------------------------------------------
    |------------------------
    ||   0 | SELECT STATEMENT             |             |     2 |
    |136 |     4   (0)| 00:00:01 |
""".trimMargin()

/**
 * **A plan carrying the peeked-bind section**, which prints a value and a size on one line.
 *
 * Its treatment is decided and no capture of it exists, so an input carrying it refuses — recognised
 * on its header, and answered with the general verdict.
 */
internal val ORACLE_GRID_PEEKED_BINDS = ORACLE_GRID_PLAN + "\n" + """
    |
    |Peeked Binds (identified by position):
    |--------------------------------------
    |
    |   1 - :1 (VARCHAR2(30), CSID=873): 'Portland'
""".trimMargin()

/**
 * **An outline carrying a hint no vocabulary row holds**, beside hints that parse.
 *
 * The off-list hint becomes one redacted literal and **the plan still reads**, which is the one place
 * in this product where a closed vocabulary's miss costs fidelity rather than the input.
 */
internal val ORACLE_GRID_OFF_LIST_HINT = ORACLE_GRID_PLAN.replace(
    "      ALL_ROWS\n",
    "      ALL_ROWS\n      SPACESHIP_JOIN(@\"SEL${'$'}1\" \"V\"@\"SEL${'$'}1\")\n",
)

/**
 * **A plan whose section this product has no capture of** — the note the optimizer prints under some
 * plans.
 *
 * It refuses, and the refusal is the field closure doing its work one line at a time: a line under no
 * section this reader knows is a line nothing classified, and reading past one is how a value leaves
 * in the part nobody looked at.
 */
internal val ORACLE_GRID_WITH_NOTE = ORACLE_GRID_PLAN + "\n" + """
    |
    |Note
    |-----
    |   - dynamic statistics used: dynamic sampling (level=2)
""".trimMargin()

/**
 * **Oracle's SQL Monitor report, as XML** — the format that carries the bind list, and therefore the
 * one the drop rule arrives in.
 *
 * Two of its binds carry **the same byte length**, which is the fixture the sharing claim is asserted
 * over: a length that was masked rather than dropped would hand both of them one placeholder, and the
 * receiver would read length equality off the mechanism meant to close it.
 *
 * The three binds are also the three readings a value takes: a string is one redacted literal, a date
 * is one redacted literal, and a number is a number.
 */
internal val ORACLE_MONITOR_XML = """
    |<report db_version="19.0.0.0.0">
    |  <report_id>ab1c9f2e</report_id>
    |  <report_parameters type="XML" sql_id="7ws837zynp1zv" report_level="ALL"/>
    |  <sql_monitor_report version="1.0">
    |    <status>DONE (ALL ROWS)</status>
    |    <sql_id>7ws837zynp1zv</sql_id>
    |    <sql_exec_id>16777216</sql_exec_id>
    |    <sql_exec_start>09/12/2025 11:04:21</sql_exec_start>
    |    <user>APP_OWNER</user>
    |    <module>SQL*Plus</module>
    |    <service>orclpdb</service>
    |    <instance_id>1</instance_id>
    |    <session_id>142</session_id>
    |    <sql_fulltext>select * from visits v where v.city = :city</sql_fulltext>
    |    <binds>
    |      <bind name=":city" pos="1" dty="1" dtystr="VARCHAR2(32)" maxlen="32" len="7" csid="873">Portland</bind>
    |      <bind name=":opened" pos="2" dty="12" dtystr="DATE" maxlen="7" len="7">10-SEP-25</bind>
    |      <bind name=":limit" pos="3" dty="2" dtystr="NUMBER" maxlen="22" len="2">25</bind>
    |    </binds>
    |    <stats type="monitor">
    |      <stat name="elapsed_time">1204</stat>
    |    </stats>
    |    <plan>
    |      <operation id="0" name="SELECT STATEMENT" depth="0" cost="4" cardinality="2" bytes="136">
    |        <operation id="1" name="TABLE ACCESS" options="BY INDEX ROWID" depth="1" cost="3" cardinality="1" bytes="36">
    |          <object name="VISITS" owner="APP_OWNER" alias="V@SEL${'$'}1" type="TABLE"/>
    |          <operation id="2" name="INDEX" options="RANGE SCAN" depth="2" cost="2" cardinality="1" bytes="0">
    |            <object name="VISITS_PKEY" owner="APP_OWNER" alias="V@SEL${'$'}1" type="INDEX"/>
    |          </operation>
    |        </operation>
    |      </operation>
    |    </plan>
    |  </sql_monitor_report>
    |</report>
""".trimMargin()

/**
 * **A bound value carrying the sequence that closes a character-data section**, which the engine
 * splits across two sections rather than emitting unescaped.
 *
 * The value is `a]]>b`. Written into one section it would end the section early and the rest of it
 * would be document rather than data — so the writer splits it, and the reader has to take the two
 * sections as **one value with the split inside it**. A sequence the engine cannot split is one it
 * fails its own report over, so there is nothing to paste.
 */
internal val ORACLE_MONITOR_XML_SPLIT_VALUE = ORACLE_MONITOR_XML.replace(
    """<bind name=":city" pos="1" dty="1" dtystr="VARCHAR2(32)" maxlen="32" len="7" csid="873">Portland</bind>""",
    """<bind name=":city" pos="1" dty="1" dtystr="VARCHAR2(32)" maxlen="32" len="5" csid="873">""" +
        """<![CDATA[a]]]]><![CDATA[>b]]></bind>""",
)

/**
 * **The same report with everything the drop rule removes never written** — the two lengths, and the
 * declared size inside the type name.
 *
 * It is the comparison the counter, the ledger and the notices are asserted against, so it has to
 * differ from the report above **only** by what is dropped: a fixture that still carried the declared
 * size would leave that drop's non-counting unasserted.
 */
internal val ORACLE_MONITOR_XML_WITHOUT_LENGTHS = ORACLE_MONITOR_XML
    .replace(Regex(""" maxlen="\d+"| len="\d+""""), "")
    .replace("VARCHAR2(32)", "VARCHAR2")

/**
 * **A report whose bind value is a type no row types**, which is every type outside the numeric
 * codes — and one redacted literal.
 */
internal val ORACLE_MONITOR_XML_RAW_BIND = ORACLE_MONITOR_XML.replace(
    """dty="2" dtystr="NUMBER" maxlen="22" len="2">25<""",
    """dty="23" dtystr="RAW(16)" maxlen="16" len="16">0FA1<""",
)

/**
 * **Oracle's HTML report and its active report** — documents rather than paste shapes, and one of
 * them carrying its data compressed inside a script.
 *
 * Neither is recognised, so both take the general verdict, which names no recourse by construction.
 */
internal val ORACLE_HTML_REPORT = """
    |<html>
    |<head><title>SQL Monitoring Report</title></head>
    |<body><table><tr><td>Plan hash value</td><td>2709293936</td></tr></table></body>
    |</html>
""".trimMargin()

internal val ORACLE_ACTIVE_REPORT = """
    |<!DOCTYPE html>
    |<html><head><script type="text/javascript">var data="H4sIAAAAAAAAA6tWykvMTVWyUsp";</script></head>
    |<body><div id="report"></div></body>
    |</html>
""".trimMargin()

/** **Plan-table rows a client rendered as CSV**, which has thrown the grid's separators away. */
internal val ORACLE_PLAN_TABLE_CSV = """
    |ID,OPERATION,OPTIONS,OBJECT_NAME,CARDINALITY,BYTES,COST
    |0,SELECT STATEMENT,,,2,136,4
    |1,TABLE ACCESS,FULL,VISITS,2,136,4
""".trimMargin()

/**
 * **SQL Monitor's text report** — the same grid shape, with no anchor line and a transcript header
 * above it.
 *
 * The statement is in the report, under a heading of its own, which is what makes this a transcript
 * rather than a plan with chrome around it. It is not recognised, so it takes the general verdict.
 */
internal val ORACLE_MONITOR_TEXT = """
    |Global Information
    |------------------------------
    | Status              :  DONE (ALL ROWS)
    | Instance ID         :  1
    | SQL ID              :  7ws837zynp1zv
    |
    |SQL Text
    |------------------------------
    |select * from visits v where v.city = :city
    |
    |SQL Plan Monitoring Details (Plan Hash Value=2709293936)
    |=========================================================
    || Id |      Operation       |  Name  | Rows  | Cost |
    |=========================================================
    ||  0 | SELECT STATEMENT     |        |       |    4 |
    |=========================================================
""".trimMargin()

/** The names an Oracle fixture must not leave on the clipboard, whichever format printed it. */
internal val ORACLE_NAMES: List<String> =
    listOf("VISITS", "VISITS_PKEY", "INVOICES", "APP_OWNER", "STATUS", "VISIT_ID", "Portland")
