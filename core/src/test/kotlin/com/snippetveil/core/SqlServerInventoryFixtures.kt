package com.snippetveil.core

/**
 * **One Showplan XML document carrying every row of SQL Server's inventory**, so that no row is one
 * this product only believes in.
 *
 * It is not a plan any single statement produces, and it is not meant to be: it is a plan-shaped
 * document in which every field the inventory holds is written once, nested where that field is
 * actually printed. The claim it supports is `SqlServerInventoryTest`'s — **a row nothing exercises
 * is a row nobody has checked reads** — and the fixture reading at all is half of it.
 */
internal val EXHAUSTIVE_SQLSERVER_XML_PLAN = """
    |<ShowPlanXML xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema" Version="1.552" Build="16.0.4135.4" xmlns="http://schemas.microsoft.com/sqlserver/2004/07/showplan">
    |  <BatchSequence>
    |    <Batch>
    |      <Statements>
    |        <StmtSimple StatementCompId="1" StatementEstRows="2" StatementId="1" StatementOptmLevel="FULL" StatementOptmEarlyAbortReason="GoodEnoughPlanFound" StatementSubTreeCost="0.0065704" StatementText="SELECT v.Id FROM shop.dbo.Visits AS v WHERE v.Status = 'open'" ParameterizedText="(@1 varchar(8000))SELECT v.Id FROM shop.dbo.Visits AS v WHERE v.Status = @1" StatementType="SELECT" QueryHash="0x9A1B2C3D4E5F6071" QueryPlanHash="0x0102030405060708" ParameterizedPlanHandle="0x060001000ABCDEF0" RetrievedFromCache="true" SecurityPolicyApplied="false" CardinalityEstimationModelVersion="160">
    |          <StatementSetOptions ANSI_NULLS="true" ANSI_PADDING="true" ANSI_WARNINGS="true" ARITHABORT="true" CONCAT_NULL_YIELDS_NULL="true" NUMERIC_ROUNDABORT="false" QUOTED_IDENTIFIER="true" />
    |          <QueryPlan DegreeOfParallelism="1" MemoryGrant="1024" CachedPlanSize="24" CompileTime="2" CompileCPU="2" CompileMemory="192" NonParallelPlanReason="MaxDOPSetToOne" ContainsInterleavedExecutionCandidates="false">
    |            <MemoryGrantInfo SerialRequiredMemory="0" SerialDesiredMemory="16" RequiredMemory="24" DesiredMemory="40" RequestedMemory="1024" GrantWaitTime="0" GrantedMemory="1024" MaxUsedMemory="16" />
    |            <OptimizerHardwareDependentProperties EstimatedAvailableMemoryGrant="104857" EstimatedPagesCached="6553" EstimatedAvailableDegreeOfParallelism="2" MaxCompileMemory="2048" />
    |            <OptimizerStatsUsage>
    |              <StatisticsInfo Database="[shop]" Schema="[dbo]" Table="[Visits]" Statistics="[IX_Visits_OwnerId]" ModificationCount="12" SamplingPercent="100" LastUpdate="2024-05-01T09:12:44.11" />
    |            </OptimizerStatsUsage>
    |            <TraceFlags IsCompileTime="true">
    |              <TraceFlag Value="4199" Scope="Global" />
    |            </TraceFlags>
    |            <ParameterList>
    |              <ColumnReference Column="@1" ParameterDataType="varchar(8000)" ParameterCompiledValue="'open'" ParameterRuntimeValue="'open'" />
    |            </ParameterList>
    |            <Warnings>
    |              <PlanAffectingConvert ConvertIssue="Cardinality Estimate" Expression="CONVERT_IMPLICIT(nvarchar(30),[shop].[dbo].[Visits].[Status],0)=[@1]" />
    |              <SpillToTempDb SpillLevel="1" SpilledThreadCount="4" />
    |              <ColumnsWithNoStatistics>
    |                <ColumnReference Database="[shop]" Schema="[dbo]" Table="[Visits]" Column="Note" />
    |              </ColumnsWithNoStatistics>
    |            </Warnings>
    |            <RelOp NodeId="0" PhysicalOp="Nested Loops" LogicalOp="Inner Join" EstimateRows="2" EstimateIO="0" EstimateCPU="0.00000418" AvgRowSize="47" EstimatedTotalSubtreeCost="0.0065704" TableCardinality="1000" Parallel="0" EstimateRebinds="0" EstimateRewinds="0" EstimateRowsWithoutRowGoal="2" EstimatedExecutionMode="Row">
    |              <OutputList>
    |                <ColumnReference Database="[shop]" Schema="[dbo]" Table="[Visits]" Alias="[v]" Column="Id" />
    |              </OutputList>
    |              <NestedLoops Optimized="false" WithOrderedPrefetch="true" WithUnorderedPrefetch="false">
    |                <DefinedValues>
    |                  <DefinedValue>
    |                    <ColumnReference Database="[shop]" Schema="[dbo]" Table="[Visits]" Alias="[v]" Column="Id" />
    |                  </DefinedValue>
    |                </DefinedValues>
    |                <OuterReferences>
    |                  <ColumnReference Database="[shop]" Schema="[dbo]" Table="[Visits]" Alias="[v]" Column="Id" />
    |                </OuterReferences>
    |                <Predicate>
    |                  <ScalarOperator ScalarString="[billing].[dbo].[Invoices].[VisitId] as [i].[VisitId]=[shop].[dbo].[Visits].[Id] as [v].[Id]">
    |                    <Compare CompareOp="EQ">
    |                      <ScalarOperator>
    |                        <Identifier>
    |                          <ColumnReference Database="[billing]" Schema="[dbo]" Table="[Invoices]" Alias="[i]" Column="VisitId" />
    |                        </Identifier>
    |                      </ScalarOperator>
    |                      <ScalarOperator>
    |                        <Const ConstValue="(42)" />
    |                      </ScalarOperator>
    |                    </Compare>
    |                  </ScalarOperator>
    |                </Predicate>
    |                <RelOp NodeId="1" PhysicalOp="Index Seek" LogicalOp="Index Seek" EstimateRows="2" EstimateIO="0.003125" EstimateCPU="0.0001581" AvgRowSize="36" EstimatedTotalSubtreeCost="0.0033" TableCardinality="1000" Parallel="0" EstimateRebinds="0" EstimateRewinds="0" EstimatedExecutionMode="Row">
    |                  <OutputList>
    |                    <ColumnReference Database="[shop]" Schema="[dbo]" Table="[Visits]" Alias="[v]" Column="Id" />
    |                  </OutputList>
    |                  <IndexScan Ordered="1" ScanDirection="FORWARD" ForcedIndex="0" ForceSeek="0" ForceScan="0" NoExpandHint="0" Lookup="0" Storage="RowStore">
    |                    <DefinedValues>
    |                      <DefinedValue>
    |                        <ColumnReference Database="[shop]" Schema="[dbo]" Table="[Visits]" Alias="[v]" Column="Id" />
    |                      </DefinedValue>
    |                    </DefinedValues>
    |                    <Object Database="[shop]" Schema="[dbo]" Table="[Visits]" Index="[IX_Visits_OwnerId]" Alias="[v]" IndexKind="NonClustered" Storage="RowStore" TableReferenceId="-1" CloneAccessScope="Primary" />
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
    |                          <StartRange ScanType="GE">
    |                            <RangeColumns>
    |                              <ColumnReference Database="[shop]" Schema="[dbo]" Table="[Visits]" Alias="[v]" Column="CreatedAt" />
    |                            </RangeColumns>
    |                            <RangeExpressions>
    |                              <ScalarOperator ScalarString="'2024-01-01'">
    |                                <Const ConstValue="'2024-01-01'" />
    |                              </ScalarOperator>
    |                            </RangeExpressions>
    |                          </StartRange>
    |                          <EndRange ScanType="LT">
    |                            <RangeColumns>
    |                              <ColumnReference Database="[shop]" Schema="[dbo]" Table="[Visits]" Alias="[v]" Column="CreatedAt" />
    |                            </RangeColumns>
    |                            <RangeExpressions>
    |                              <ScalarOperator ScalarString="'2024-02-01'">
    |                                <Const ConstValue="'2024-02-01'" />
    |                              </ScalarOperator>
    |                            </RangeExpressions>
    |                          </EndRange>
    |                          <IsNotNull ScanType="NE">
    |                            <RangeColumns>
    |                              <ColumnReference Database="[shop]" Schema="[dbo]" Table="[Visits]" Alias="[v]" Column="OwnerId" />
    |                            </RangeColumns>
    |                          </IsNotNull>
    |                        </SeekKeys>
    |                      </SeekPredicateNew>
    |                      <SeekPredicate>
    |                        <Prefix ScanType="EQ">
    |                          <RangeColumns>
    |                            <ColumnReference Database="[shop]" Schema="[dbo]" Table="[Visits]" Alias="[v]" Column="OwnerId" />
    |                          </RangeColumns>
    |                        </Prefix>
    |                      </SeekPredicate>
    |                    </SeekPredicates>
    |                    <Predicate>
    |                      <ScalarOperator ScalarString="[shop].[dbo].[Visits].[Status] as [v].[Status]='open'">
    |                        <Const ConstValue="'open'" />
    |                      </ScalarOperator>
    |                    </Predicate>
    |                  </IndexScan>
    |                </RelOp>
    |                <RelOp NodeId="2" PhysicalOp="Hash Match" LogicalOp="Inner Join" EstimateRows="2" EstimateIO="0" EstimateCPU="0.001" AvgRowSize="40" EstimatedTotalSubtreeCost="0.0032" Parallel="0" EstimateRebinds="0" EstimateRewinds="0" EstimatedExecutionMode="Row">
    |                  <OutputList>
    |                    <ColumnReference Database="[billing]" Schema="[dbo]" Table="[Invoices]" Alias="[i]" Column="Total" />
    |                  </OutputList>
    |                  <Hash BitmapCreator="false">
    |                    <DefinedValues />
    |                    <HashKeysBuild>
    |                      <ColumnReference Database="[shop]" Schema="[dbo]" Table="[Visits]" Alias="[v]" Column="Id" />
    |                    </HashKeysBuild>
    |                    <HashKeysProbe>
    |                      <ColumnReference Database="[billing]" Schema="[dbo]" Table="[Invoices]" Alias="[i]" Column="VisitId" />
    |                    </HashKeysProbe>
    |                    <ProbeResidual>
    |                      <ScalarOperator ScalarString="[billing].[dbo].[Invoices].[VisitId] as [i].[VisitId]=[shop].[dbo].[Visits].[Id] as [v].[Id]" />
    |                    </ProbeResidual>
    |                    <RelOp NodeId="3" PhysicalOp="Table Scan" LogicalOp="Table Scan" EstimateRows="1000" EstimateIO="0.01" EstimateCPU="0.001" AvgRowSize="32" EstimatedTotalSubtreeCost="0.011" TableCardinality="1000" Parallel="0" EstimateRebinds="0" EstimateRewinds="0" EstimatedExecutionMode="Row">
    |                      <OutputList>
    |                        <ColumnReference Database="[billing]" Schema="[dbo]" Table="[Invoices]" Alias="[i]" Column="Total" />
    |                      </OutputList>
    |                      <TableScan Ordered="0" ForcedIndex="0" ForceScan="0" NoExpandHint="0" Storage="RowStore">
    |                        <DefinedValues />
    |                        <Object Database="[billing]" Schema="[dbo]" Table="[Invoices]" Alias="[i]" IndexKind="Heap" Storage="RowStore" />
    |                        <Predicate>
    |                          <ScalarOperator ScalarString="[billing].[dbo].[Invoices].[Total] as [i].[Total]&gt;(0)" />
    |                        </Predicate>
    |                      </TableScan>
    |                    </RelOp>
    |                    <RelOp NodeId="4" PhysicalOp="Sort" LogicalOp="Sort" EstimateRows="2" EstimateIO="0.0112" EstimateCPU="0.0001" AvgRowSize="36" EstimatedTotalSubtreeCost="0.0114" Parallel="0" EstimateRebinds="0" EstimateRewinds="0" EstimatedExecutionMode="Row">
    |                      <OutputList>
    |                        <ColumnReference Database="[shop]" Schema="[dbo]" Table="[Visits]" Alias="[v]" Column="CreatedAt" />
    |                      </OutputList>
    |                      <Sort Distinct="false">
    |                        <DefinedValues />
    |                        <OrderBy>
    |                          <OrderByColumn Ascending="true">
    |                            <ColumnReference Database="[shop]" Schema="[dbo]" Table="[Visits]" Alias="[v]" Column="CreatedAt" />
    |                          </OrderByColumn>
    |                        </OrderBy>
    |                        <RelOp NodeId="5" PhysicalOp="Compute Scalar" LogicalOp="Compute Scalar" EstimateRows="2" EstimateIO="0" EstimateCPU="0.0000002" AvgRowSize="36" EstimatedTotalSubtreeCost="0.0113" Parallel="0" EstimateRebinds="0" EstimateRewinds="0" EstimatedExecutionMode="Row">
    |                          <OutputList>
    |                            <ColumnReference Column="Expr1002" />
    |                          </OutputList>
    |                          <ComputeScalar ComputeSequence="false">
    |                            <DefinedValues>
    |                              <DefinedValue>
    |                                <ColumnReference Column="Expr1002" ComputedColumn="true" />
    |                                <ScalarOperator ScalarString="CONVERT_IMPLICIT(numeric(18,2),[shop].[dbo].[Visits].[Status] as [v].[Status],0)">
    |                                  <Logical Operation="AND">
    |                                    <ScalarOperator>
    |                                      <Compare CompareOp="EQ">
    |                                        <ScalarOperator>
    |                                          <Identifier>
    |                                            <ColumnReference Database="[shop]" Schema="[dbo]" Table="[Visits]" Alias="[v]" Column="Status" />
    |                                          </Identifier>
    |                                        </ScalarOperator>
    |                                        <ScalarOperator>
    |                                          <Const ConstValue="'open'" />
    |                                        </ScalarOperator>
    |                                      </Compare>
    |                                    </ScalarOperator>
    |                                    <ScalarOperator>
    |                                      <Convert DataType="numeric" Length="9" Precision="18" Scale="2" Style="0" Implicit="1">
    |                                        <ScalarOperator>
    |                                          <Arithmetic Operation="ADD">
    |                                            <ScalarOperator>
    |                                              <Aggregate AggType="countstar" Distinct="false" />
    |                                            </ScalarOperator>
    |                                            <ScalarOperator>
    |                                              <Intrinsic FunctionName="datediff">
    |                                                <ScalarOperator>
    |                                                  <Const ConstValue="(1)" />
    |                                                </ScalarOperator>
    |                                              </Intrinsic>
    |                                            </ScalarOperator>
    |                                          </Arithmetic>
    |                                        </ScalarOperator>
    |                                      </Convert>
    |                                    </ScalarOperator>
    |                                  </Logical>
    |                                </ScalarOperator>
    |                              </DefinedValue>
    |                            </DefinedValues>
    |                            <RelOp NodeId="6" PhysicalOp="Filter" LogicalOp="Filter" EstimateRows="2" EstimateIO="0" EstimateCPU="0.0000002" AvgRowSize="36" EstimatedTotalSubtreeCost="0.0112" Parallel="0" EstimateRebinds="0" EstimateRewinds="0" EstimatedExecutionMode="Row">
    |                              <OutputList>
    |                                <ColumnReference Database="[shop]" Schema="[dbo]" Table="[Visits]" Alias="[v]" Column="Status" />
    |                              </OutputList>
    |                              <Filter StartupExpression="false">
    |                                <DefinedValues />
    |                                <Predicate>
    |                                  <ScalarOperator ScalarString="[shop].[dbo].[Visits].[Status] as [v].[Status]='open'" />
    |                                </Predicate>
    |                                <RelOp NodeId="7" PhysicalOp="Stream Aggregate" LogicalOp="Aggregate" EstimateRows="1" EstimateIO="0" EstimateCPU="0.00001" AvgRowSize="11" EstimatedTotalSubtreeCost="0.0111" Parallel="0" EstimateRebinds="0" EstimateRewinds="0" EstimatedExecutionMode="Row">
    |                                  <OutputList>
    |                                    <ColumnReference Column="Expr1003" />
    |                                  </OutputList>
    |                                  <StreamAggregate>
    |                                    <DefinedValues />
    |                                    <GroupBy>
    |                                      <ColumnReference Database="[shop]" Schema="[dbo]" Table="[Visits]" Alias="[v]" Column="OwnerId" />
    |                                    </GroupBy>
    |                                    <RelOp NodeId="8" PhysicalOp="Top" LogicalOp="Top" EstimateRows="1" EstimateIO="0" EstimateCPU="0.0000001" AvgRowSize="11" EstimatedTotalSubtreeCost="0.011" Parallel="0" EstimateRebinds="0" EstimateRewinds="0" EstimatedExecutionMode="Row">
    |                                      <OutputList>
    |                                        <ColumnReference Database="[shop]" Schema="[dbo]" Table="[Visits]" Alias="[v]" Column="OwnerId" />
    |                                      </OutputList>
    |                                      <Top RowCount="false" IsPercent="false" WithTies="false" Rows="10">
    |                                        <DefinedValues />
    |                                        <TopExpression>
    |                                          <ScalarOperator ScalarString="(10)">
    |                                            <Const ConstValue="(10)" />
    |                                          </ScalarOperator>
    |                                        </TopExpression>
    |                                        <RelOp NodeId="9" PhysicalOp="Table Scan" LogicalOp="Table Scan" EstimateRows="1000" EstimateIO="0.01" EstimateCPU="0.001" AvgRowSize="32" EstimatedTotalSubtreeCost="0.011" TableCardinality="1000" Parallel="0" EstimateRebinds="0" EstimateRewinds="0" EstimatedExecutionMode="Row">
    |                                          <OutputList>
    |                                            <ColumnReference Database="[shop]" Schema="[dbo]" Table="[Visits]" Alias="[v]" Column="OwnerId" />
    |                                          </OutputList>
    |                                          <TableScan Ordered="0" ForcedIndex="0" ForceScan="0" NoExpandHint="0" Storage="RowStore">
    |                                            <DefinedValues />
    |                                            <Object Database="[shop]" Schema="[dbo]" Table="[Visits]" Alias="[v]" IndexKind="Heap" Storage="RowStore" />
    |                                          </TableScan>
    |                                        </RelOp>
    |                                      </Top>
    |                                    </RelOp>
    |                                  </StreamAggregate>
    |                                </RelOp>
    |                              </Filter>
    |                            </RelOp>
    |                          </ComputeScalar>
    |                        </RelOp>
    |                      </Sort>
    |                    </RelOp>
    |                  </Hash>
    |                </RelOp>
    |              </NestedLoops>
    |            </RelOp>
    |          </QueryPlan>
    |        </StmtSimple>
    |      </Statements>
    |    </Batch>
    |  </BatchSequence>
    |</ShowPlanXML>
""".trimMargin()
