package com.snippetveil.core

/**
 * **The SQL Server captures every claim in `SqlServerPlanTest`, `SqlServerRefusalTest` and
 * `SqlServerInventoryTest` is asserted over.**
 *
 * One statement, printed the ways this engine prints it: `SET SHOWPLAN_XML ON`, the plan rowset of
 * `SET SHOWPLAN_TEXT ON`, that rowset with the statement echo above it, `SET SHOWPLAN_ALL ON`,
 * `SET STATISTICS PROFILE ON`, and the truncating results-to-text mode. Printings of one plan rather
 * than unrelated fixtures, because **the claims are comparisons**: the admitted forms have to carry
 * the same names the refused ones would have leaked, and the recognisers have to tell six shapes of
 * one engine apart.
 *
 * **They are written from a reading of this engine's output rather than pasted from a capture taken
 * in this repository**, and that is recorded here rather than left to be assumed. It is the same
 * provenance the MySQL fixtures have. What it costs is the direction a fixture can be wrong in:
 * everything asserted below holds of *these* texts, and a shape SQL Server prints that none of them
 * has refuses under the closures rather than being read wrongly.
 */
internal val SQLSERVER_XML_PLAN = """
    |<ShowPlanXML xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema" Version="1.552" Build="16.0.4135.4" xmlns="http://schemas.microsoft.com/sqlserver/2004/07/showplan">
    |  <BatchSequence>
    |    <Batch>
    |      <Statements>
    |        <StmtSimple StatementCompId="1" StatementEstRows="2" StatementId="1" StatementOptmLevel="FULL" StatementSubTreeCost="0.0065704" StatementText="SELECT v.Id FROM shop.dbo.Visits AS v WHERE v.OwnerId = 42 AND v.Status = 'open'" StatementType="SELECT" QueryHash="0x9A1B2C3D4E5F6071" QueryPlanHash="0x0102030405060708" RetrievedFromCache="true">
    |          <StatementSetOptions ANSI_NULLS="true" ANSI_PADDING="true" ANSI_WARNINGS="true" ARITHABORT="true" CONCAT_NULL_YIELDS_NULL="true" NUMERIC_ROUNDABORT="false" QUOTED_IDENTIFIER="true" />
    |          <QueryPlan DegreeOfParallelism="1" CachedPlanSize="24" CompileTime="2" CompileCPU="2" CompileMemory="192">
    |            <TraceFlags IsCompileTime="true">
    |              <TraceFlag Value="4199" Scope="Global" />
    |            </TraceFlags>
    |            <RelOp NodeId="0" PhysicalOp="Compute Scalar" LogicalOp="Compute Scalar" EstimateRows="2" EstimateIO="0" EstimateCPU="0.0000002" AvgRowSize="36" EstimatedTotalSubtreeCost="0.0065704" Parallel="0" EstimateRebinds="0" EstimateRewinds="0" EstimatedExecutionMode="Row">
    |              <OutputList>
    |                <ColumnReference Column="Expr1002" />
    |              </OutputList>
    |              <ComputeScalar ComputeSequence="false">
    |                <DefinedValues>
    |                  <DefinedValue>
    |                    <ColumnReference Column="Expr1002" ComputedColumn="true" />
    |                    <ScalarOperator ScalarString="CONVERT_IMPLICIT(nvarchar(30),[shop].[dbo].[Visits].[Status] as [v].[Status],0)">
    |                      <Convert DataType="nvarchar" Length="60" Style="0" Implicit="1">
    |                        <ScalarOperator>
    |                          <Identifier>
    |                            <ColumnReference Database="[shop]" Schema="[dbo]" Table="[Visits]" Alias="[v]" Column="Status" />
    |                          </Identifier>
    |                        </ScalarOperator>
    |                      </Convert>
    |                    </ScalarOperator>
    |                  </DefinedValue>
    |                </DefinedValues>
    |                <RelOp NodeId="1" PhysicalOp="Index Seek" LogicalOp="Index Seek" EstimateRows="2" EstimateIO="0.003125" EstimateCPU="0.0001581" AvgRowSize="36" EstimatedTotalSubtreeCost="0.0065702" TableCardinality="1000" Parallel="0" EstimateRebinds="0" EstimateRewinds="0" EstimatedExecutionMode="Row">
    |                  <OutputList>
    |                    <ColumnReference Database="[shop]" Schema="[dbo]" Table="[Visits]" Alias="[v]" Column="Id" />
    |                  </OutputList>
    |                  <IndexScan Ordered="1" ScanDirection="FORWARD" ForcedIndex="0" ForceSeek="0" ForceScan="0" NoExpandHint="0" Storage="RowStore">
    |                    <DefinedValues>
    |                      <DefinedValue>
    |                        <ColumnReference Database="[shop]" Schema="[dbo]" Table="[Visits]" Alias="[v]" Column="Id" />
    |                      </DefinedValue>
    |                    </DefinedValues>
    |                    <Object Database="[shop]" Schema="[dbo]" Table="[Visits]" Index="[IX_Visits_OwnerId]" Alias="[v]" IndexKind="NonClustered" Storage="RowStore" />
    |                    <SeekPredicates>
    |                      <SeekPredicateNew>
    |                        <SeekKeys>
    |                          <Prefix ScanType="EQ">
    |                            <RangeColumns>
    |                              <ColumnReference Database="[shop]" Schema="[dbo]" Table="[Visits]" Alias="[v]" Column="OwnerId" />
    |                            </RangeColumns>
    |                            <RangeExpressions>
    |                              <ScalarOperator ScalarString="(42)">
    |                                <Const ConstValue="(42)" />
    |                              </ScalarOperator>
    |                            </RangeExpressions>
    |                          </Prefix>
    |                        </SeekKeys>
    |                      </SeekPredicateNew>
    |                    </SeekPredicates>
    |                    <Predicate>
    |                      <ScalarOperator ScalarString="[shop].[dbo].[Visits].[Status] as [v].[Status]='open'">
    |                        <Compare CompareOp="EQ">
    |                          <ScalarOperator>
    |                            <Identifier>
    |                              <ColumnReference Database="[shop]" Schema="[dbo]" Table="[Visits]" Alias="[v]" Column="Status" />
    |                            </Identifier>
    |                          </ScalarOperator>
    |                          <ScalarOperator>
    |                            <Const ConstValue="'open'" />
    |                          </ScalarOperator>
    |                        </Compare>
    |                      </ScalarOperator>
    |                    </Predicate>
    |                  </IndexScan>
    |                </RelOp>
    |              </ComputeScalar>
    |            </RelOp>
    |          </QueryPlan>
    |        </StmtSimple>
    |      </Statements>
    |    </Batch>
    |  </BatchSequence>
    |</ShowPlanXML>
""".trimMargin()

/**
 * **The plan rowset of `SET SHOWPLAN_TEXT ON`, copied from the grid with its header** — which is one
 * of the two shapes this product admits and the one a user gets from *copy with headers*.
 *
 * The statement echo that this rowset's own result set is preceded by is deliberately **not** here:
 * an input carrying it is a transcript, and `SQLSERVER_TEXT_WITH_ECHO` is what asserts so.
 */
internal val SQLSERVER_TEXT_PLAN = """
    |StmtText
    |  |--Nested Loops(Inner Join, OUTER REFERENCES:([v].[Id]))
    |       |--Index Seek(OBJECT:([shop].[dbo].[Visits].[IX_Visits_OwnerId] AS [v]), SEEK:([v].[OwnerId]=(42)), WHERE:([shop].[dbo].[Visits].[Status] as [v].[Status]='open') ORDERED FORWARD)
    |       |--Clustered Index Seek(OBJECT:([billing].[dbo].[Invoices].[PK_Invoices] AS [i]), SEEK:([i].[VisitId]=[shop].[dbo].[Visits].[Id] as [v].[Id]) ORDERED FORWARD)
""".trimMargin()

/** The same rows with no header above them, which is what the grid's plain copy gives. */
internal val SQLSERVER_TEXT_ROWS_ONLY = SQLSERVER_TEXT_PLAN.substringAfter("\n")

/**
 * **The natural paste: the statement echo, then the plan** — the shape a user gets by selecting both
 * result sets, and the shape that refuses.
 */
internal val SQLSERVER_TEXT_WITH_ECHO = """
    |StmtText
    |SELECT v.Id FROM shop.dbo.Visits AS v WHERE v.OwnerId = 42
    |
    |StmtText
    |  |--Index Seek(OBJECT:([shop].[dbo].[Visits].[IX_Visits_OwnerId] AS [v]), SEEK:([v].[OwnerId]=(42)) ORDERED FORWARD)
""".trimMargin()

/**
 * **A batch of two statements, pasted from the first statement's plan rowset onwards** — so the paste
 * begins where this product admits one and still carries the next statement being echoed.
 */
internal val SQLSERVER_TEXT_TWO_STATEMENTS = """
    |StmtText
    |  |--Index Seek(OBJECT:([shop].[dbo].[Visits].[IX_Visits_OwnerId] AS [v]), SEEK:([v].[OwnerId]=(42)) ORDERED FORWARD)
    |
    |StmtText
    |SELECT i.Total FROM billing.dbo.Invoices AS i
    |
    |StmtText
    |  |--Clustered Index Scan(OBJECT:([billing].[dbo].[Invoices].[PK_Invoices] AS [i]))
""".trimMargin()

/**
 * **A table named with a closing bracket and a comma in it**, written the way this engine writes one:
 * the bracket doubled, and the comma left alone because the bracket is what delimits.
 *
 * The table is called `Odd]Table, Two`. A reader that took the first `]` for the end of the name
 * would report a table called `Odd` and then try to place `Table, Two]` — which is how a name comes
 * to forge a cell.
 */
internal val SQLSERVER_TEXT_AWKWARD_NAME = """
    |StmtText
    |  |--Table Scan(OBJECT:([shop].[dbo].[Odd]]Table, Two] AS [t]), WHERE:([shop].[dbo].[Odd]]Table, Two].[Status] as [t].[Status]='open'))
""".trimMargin()

/**
 * **A column whose name carries a tab**, which is the character a grid copy separates its cells with.
 *
 * The bracket is what delimits the name, so the tab inside one is just a character — nothing closes
 * early and no cell is forged. It is the case worth its own fixture because it is the one where the
 * client's own separator is inside a name, and where a reader that leaned on the separator rather
 * than on the bracket would cut the name in half.
 */
internal val SQLSERVER_TEXT_TABBED_COLUMN = """
    |StmtText
    |  |--Table Scan(OBJECT:([shop].[dbo].[Visits] AS [v]), WHERE:([shop].[dbo].[Visits].[Od${'\t'}d] as [v].[Od${'\t'}d]='open'))
""".trimMargin()

/**
 * **A name carrying a newline, which is what a forged row actually looks like.**
 *
 * The writer does not escape a newline inside a bracketed name, so the opener on the first row never
 * closes on it and the text below is a second row that this product is meant to read as a plan node.
 * It is not one: the bracket is unbalanced, and there is no sound reading of that.
 */
internal val SQLSERVER_TEXT_FORGED_ROW = """
    |StmtText
    |  |--Table Scan(OBJECT:([shop].[dbo].[evil
    |  |--Index Seek(OBJECT:([shop].[dbo].[Visits].[IX_Visits_OwnerId] AS [v]))] AS [t]))
""".trimMargin()

/** **A remote query row**, whose linked server is unbracketed and whose remote statement is verbatim. */
internal val SQLSERVER_TEXT_REMOTE_QUERY = """
    |StmtText
    |  |--Remote Query(SOURCE:(LEDGER), QUERY:(SELECT "Tbl1001"."Total" FROM "billing"."Invoices" "Tbl1001"))
""".trimMargin()

/** **A remote scan row**, refused for the same reason and with the same recourse. */
internal val SQLSERVER_TEXT_REMOTE_SCAN = """
    |StmtText
    |  |--Remote Scan(OBJECT:(LEDGER.billing.dbo.Invoices))
""".trimMargin()

/**
 * **`SET SHOWPLAN_ALL ON`, copied from the grid** — its header row, its statement row, and one plan
 * row, with the cells separated by the tab the client copies them with.
 *
 * The first row is what refuses it: the statement sits in the `StmtText` cell, and the tabs and
 * newlines in a statement are the same characters the grid separates cells and rows with.
 */
internal val SQLSERVER_SHOWPLAN_ALL_PLAN = listOf(
    listOf(
        "StmtText", "StmtId", "NodeId", "Parent", "PhysicalOp", "LogicalOp", "Argument",
        "DefinedValues", "EstimateRows", "EstimateIO", "EstimateCPU", "AvgRowSize",
        "TotalSubtreeCost", "OutputList", "Warnings", "Type", "Parallel", "EstimateExecutions",
    ),
    listOf(
        "SELECT v.Id FROM shop.dbo.Visits AS v WHERE v.OwnerId = 42", "1", "1", "NULL", "NULL",
        "NULL", "NULL", "NULL", "2", "NULL", "NULL", "NULL", "0.0065704", "NULL", "NULL", "SELECT",
        "NULL", "NULL",
    ),
    listOf(
        "  |--Index Seek(OBJECT:([shop].[dbo].[Visits].[IX_Visits_OwnerId] AS [v]))", "1", "1", "0",
        "Index Seek", "Index Seek", "OBJECT:([shop].[dbo].[Visits].[IX_Visits_OwnerId] AS [v])",
        "[v].[Id]", "2", "0.003125", "0.0001581", "36", "0.0065704", "[v].[Id]", "", "PLAN_ROW",
        "0", "1",
    ),
).joinToString("\n") { it.joinToString("\t") }

/** **`SET STATISTICS PROFILE ON`**, which is the same rowset with what actually ran in front of it. */
internal val SQLSERVER_STATISTICS_PROFILE_PLAN = SQLSERVER_SHOWPLAN_ALL_PLAN
    .lines()
    .mapIndexed { row, line -> if (row == 0) "Rows\tExecutes\t$line" else "2\t1\t$line" }
    .joinToString("\n")

/**
 * **The results-to-text client mode**, which pads every column to a fixed width and **truncates**
 * what does not fit.
 *
 * Two things are wrong with it at once, and either would be enough: the rule of dashes under the
 * header is a row this reader does not recognise, and the plan row is cut mid-name — the opening
 * bracket of `[IX_Visits_OwnerId]` has no closer left in the input.
 */
internal val SQLSERVER_RESULTS_TO_TEXT_PLAN = """
    |StmtText
    |------------------------------------------------------------------------
    |  |--Index Seek(OBJECT:([shop].[dbo].[Visits].[IX_Visits_Owne
""".trimMargin()

/** Every object name the fixtures above print, none of which may survive an anonymization. */
internal val SQLSERVER_NAMES = listOf(
    "shop", "dbo", "Visits", "IX_Visits_OwnerId", "billing", "Invoices", "PK_Invoices", "OwnerId",
    "Status", "VisitId",
)
