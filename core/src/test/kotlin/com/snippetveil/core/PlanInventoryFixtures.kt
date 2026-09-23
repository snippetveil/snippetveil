package com.snippetveil.core

/**
 * **Every row of the structured inventory, in one plan** — the fixture the coverage claim is made
 * over.
 *
 * It is not a plan any one query produces: no node is a sample scan *and* a memoize *and* a modify
 * table. It is **every field this product says it can read**, written in the format that spells all
 * of them naturally — XML, which is the one that names a list's members with the container's own
 * singular. A row added to the inventory and not to this fixture fails the test below, which is the
 * discipline the field closure needs: a row nothing exercises is a row nobody has checked reads.
 */
internal val EXHAUSTIVE_XML_PLAN = """
    |<explain xmlns="http://www.postgresql.org/2009/explain">
    |  <Query>
    |    <Plan>
    |      <Node-Type>Seq Scan</Node-Type>
    |      <Strategy>Plain</Strategy>
    |      <Partial-Mode>Simple</Partial-Mode>
    |      <Operation>Insert</Operation>
    |      <Join-Type>Inner</Join-Type>
    |      <Scan-Direction>Forward</Scan-Direction>
    |      <Parent-Relationship>Outer</Parent-Relationship>
    |      <Conflict-Resolution>UPDATE</Conflict-Resolution>
    |      <Cache-Mode>logical</Cache-Mode>
    |      <Sampling-Method>bernoulli</Sampling-Method>
    |      <Parallel-Aware>false</Parallel-Aware>
    |      <Async-Capable>false</Async-Capable>
    |      <Inner-Unique>true</Inner-Unique>
    |      <Single-Copy>true</Single-Copy>
    |      <Relation-Name>visits</Relation-Name>
    |      <Schema>billing</Schema>
    |      <Index-Name>visits_pkey</Index-Name>
    |      <Function-Name>generate_series</Function-Name>
    |      <Alias>v</Alias>
    |      <CTE-Name>recent</CTE-Name>
    |      <Tuplestore-Name>tuplestore</Tuplestore-Name>
    |      <Subplan-Name>CTE recent</Subplan-Name>
    |      <Custom-Plan-Provider>acmescan</Custom-Plan-Provider>
    |      <Conflict-Arbiter-Indexes><Item>visits_state_idx</Item></Conflict-Arbiter-Indexes>
    |      <Output><Item>(v.state = 1)</Item></Output>
    |      <Filter><Item>(v.state = 1)</Item></Filter>
    |      <Index-Cond><Item>(v.state = 1)</Item></Index-Cond>
    |      <Recheck-Cond><Item>(v.state = 1)</Item></Recheck-Cond>
    |      <TID-Cond><Item>(v.state = 1)</Item></TID-Cond>
    |      <Join-Filter><Item>(v.state = 1)</Item></Join-Filter>
    |      <Hash-Cond><Item>(v.state = 1)</Item></Hash-Cond>
    |      <Merge-Cond><Item>(v.state = 1)</Item></Merge-Cond>
    |      <One-Time-Filter><Item>(v.state = 1)</Item></One-Time-Filter>
    |      <Conflict-Filter><Item>(v.state = 1)</Item></Conflict-Filter>
    |      <Sort-Key><Item>(v.state = 1)</Item></Sort-Key>
    |      <Presorted-Key><Item>(v.state = 1)</Item></Presorted-Key>
    |      <Group-Key><Item>(v.state = 1)</Item></Group-Key>
    |      <Hash-Key><Item>(v.state = 1)</Item></Hash-Key>
    |      <Cache-Key><Item>(v.state = 1)</Item></Cache-Key>
    |      <Function-Call><Item>(v.state = 1)</Item></Function-Call>
    |      <Order-By><Item>(v.state = 1)</Item></Order-By>
    |      <Repeatable-Seed><Item>(v.state = 1)</Item></Repeatable-Seed>
    |      <Sampling-Parameters><Item>10</Item></Sampling-Parameters>
    |      <Startup-Cost>1</Startup-Cost>
    |      <Total-Cost>1</Total-Cost>
    |      <Plan-Rows>1</Plan-Rows>
    |      <Plan-Width>1</Plan-Width>
    |      <Actual-Startup-Time>1</Actual-Startup-Time>
    |      <Actual-Total-Time>1</Actual-Total-Time>
    |      <Actual-Rows>1</Actual-Rows>
    |      <Actual-Loops>1</Actual-Loops>
    |      <Disabled-Nodes>1</Disabled-Nodes>
    |      <Subplans-Removed>1</Subplans-Removed>
    |      <Rows-Removed-by-Filter>1</Rows-Removed-by-Filter>
    |      <Rows-Removed-by-Index-Recheck>1</Rows-Removed-by-Index-Recheck>
    |      <Rows-Removed-by-Join-Filter>1</Rows-Removed-by-Join-Filter>
    |      <Rows-Removed-by-Conflict-Filter>1</Rows-Removed-by-Conflict-Filter>
    |      <Heap-Fetches>1</Heap-Fetches>
    |      <Exact-Heap-Blocks>1</Exact-Heap-Blocks>
    |      <Lossy-Heap-Blocks>1</Lossy-Heap-Blocks>
    |      <Index-Searches>1</Index-Searches>
    |      <Sort-Method>1</Sort-Method>
    |      <Sort-Space-Used>1</Sort-Space-Used>
    |      <Sort-Space-Type>1</Sort-Space-Type>
    |      <Workers-Planned>1</Workers-Planned>
    |      <Workers-Launched>1</Workers-Launched>
    |      <Hash-Buckets>1</Hash-Buckets>
    |      <Original-Hash-Buckets>1</Original-Hash-Buckets>
    |      <Hash-Batches>1</Hash-Batches>
    |      <Original-Hash-Batches>1</Original-Hash-Batches>
    |      <Peak-Memory-Usage>1</Peak-Memory-Usage>
    |      <Disk-Usage>1</Disk-Usage>
    |      <Cache-Hits>1</Cache-Hits>
    |      <Cache-Misses>1</Cache-Misses>
    |      <Cache-Evictions>1</Cache-Evictions>
    |      <Cache-Overflows>1</Cache-Overflows>
    |      <Storage>1</Storage>
    |      <Maximum-Storage>1</Maximum-Storage>
    |      <Tuples-Inserted>1</Tuples-Inserted>
    |      <Conflicting-Tuples>1</Conflicting-Tuples>
    |      <Worker-Number>1</Worker-Number>
    |      <Shared-Hit-Blocks>1</Shared-Hit-Blocks>
    |      <Shared-Read-Blocks>1</Shared-Read-Blocks>
    |      <Shared-Dirtied-Blocks>1</Shared-Dirtied-Blocks>
    |      <Shared-Written-Blocks>1</Shared-Written-Blocks>
    |      <Local-Hit-Blocks>1</Local-Hit-Blocks>
    |      <Local-Read-Blocks>1</Local-Read-Blocks>
    |      <Local-Dirtied-Blocks>1</Local-Dirtied-Blocks>
    |      <Local-Written-Blocks>1</Local-Written-Blocks>
    |      <Temp-Read-Blocks>1</Temp-Read-Blocks>
    |      <Temp-Written-Blocks>1</Temp-Written-Blocks>
    |      <I-O-Read-Time>1</I-O-Read-Time>
    |      <I-O-Write-Time>1</I-O-Write-Time>
    |      <Shared-I-O-Read-Time>1</Shared-I-O-Read-Time>
    |      <Shared-I-O-Write-Time>1</Shared-I-O-Write-Time>
    |      <Local-I-O-Read-Time>1</Local-I-O-Read-Time>
    |      <Local-I-O-Write-Time>1</Local-I-O-Write-Time>
    |      <Temp-I-O-Read-Time>1</Temp-I-O-Read-Time>
    |      <Temp-I-O-Write-Time>1</Temp-I-O-Write-Time>
    |      <WAL-Records>1</WAL-Records>
    |      <WAL-FPI>1</WAL-FPI>
    |      <WAL-Bytes>1</WAL-Bytes>
    |      <WAL-Buffers-Full>1</WAL-Buffers-Full>
    |      <Grouping-Sets>
    |        <Grouping-Set>
    |          <Group-Keys><Item>(v.state)</Item></Group-Keys>
    |          <Hash-Keys><Item>(v.kind)</Item></Hash-Keys>
    |        </Grouping-Set>
    |      </Grouping-Sets>
    |      <Full-sort-Groups>
    |        <Group-Count>2</Group-Count>
    |        <Sort-Methods-Used><Item>quicksort</Item></Sort-Methods-Used>
    |        <Sort-Space-Memory>
    |          <Average-Sort-Space-Used>25</Average-Sort-Space-Used>
    |          <Peak-Sort-Space-Used>30</Peak-Sort-Space-Used>
    |        </Sort-Space-Memory>
    |        <Sort-Space-Disk>
    |          <Average-Sort-Space-Used>0</Average-Sort-Space-Used>
    |          <Peak-Sort-Space-Used>0</Peak-Sort-Space-Used>
    |        </Sort-Space-Disk>
    |      </Full-sort-Groups>
    |      <Pre-sorted-Groups>
    |        <Group-Count>1</Group-Count>
    |      </Pre-sorted-Groups>
    |      <JIT>
    |        <Worker-Number>0</Worker-Number>
    |        <Functions>2</Functions>
    |        <Options>
    |          <Inlining>false</Inlining>
    |          <Optimization>false</Optimization>
    |          <Expressions>false</Expressions>
    |          <Deforming>false</Deforming>
    |        </Options>
    |        <Timing>
    |          <Generation>0.1</Generation>
    |          <Inlining>0.1</Inlining>
    |          <Optimization>0.1</Optimization>
    |          <Emission>0.1</Emission>
    |          <Total>0.1</Total>
    |        </Timing>
    |      </JIT>
    |      <Workers>
    |        <Worker>
    |          <Worker-Number>0</Worker-Number>
    |        </Worker>
    |      </Workers>
    |      <Plans>
    |        <Plan>
    |          <Node-Type>Result</Node-Type>
    |        </Plan>
    |      </Plans>
    |    </Plan>
    |    <Query-Text>SELECT 1</Query-Text>
    |    <Query-Parameters>$1 = 'acme'</Query-Parameters>
    |    <Query-Identifier>8034093347371450093</Query-Identifier>
    |    <Planning>
    |      <Shared-Hit-Blocks>1</Shared-Hit-Blocks>
    |      <Shared-Read-Blocks>1</Shared-Read-Blocks>
    |      <Shared-Dirtied-Blocks>1</Shared-Dirtied-Blocks>
    |      <Shared-Written-Blocks>1</Shared-Written-Blocks>
    |      <Local-Hit-Blocks>1</Local-Hit-Blocks>
    |      <Local-Read-Blocks>1</Local-Read-Blocks>
    |      <Local-Dirtied-Blocks>1</Local-Dirtied-Blocks>
    |      <Local-Written-Blocks>1</Local-Written-Blocks>
    |      <Temp-Read-Blocks>1</Temp-Read-Blocks>
    |      <Temp-Written-Blocks>1</Temp-Written-Blocks>
    |      <I-O-Read-Time>1</I-O-Read-Time>
    |      <I-O-Write-Time>1</I-O-Write-Time>
    |      <Shared-I-O-Read-Time>1</Shared-I-O-Read-Time>
    |      <Shared-I-O-Write-Time>1</Shared-I-O-Write-Time>
    |      <Local-I-O-Read-Time>1</Local-I-O-Read-Time>
    |      <Local-I-O-Write-Time>1</Local-I-O-Write-Time>
    |      <Temp-I-O-Read-Time>1</Temp-I-O-Read-Time>
    |      <Temp-I-O-Write-Time>1</Temp-I-O-Write-Time>
    |      <WAL-Records>1</WAL-Records>
    |      <WAL-FPI>1</WAL-FPI>
    |      <WAL-Bytes>1</WAL-Bytes>
    |      <WAL-Buffers-Full>1</WAL-Buffers-Full>
    |    </Planning>
    |    <Planning-Time>0.123</Planning-Time>
    |    <Execution-Time>0.456</Execution-Time>
    |    <Total-Runtime>0.600</Total-Runtime>
    |    <Triggers>
    |      <Trigger>
    |        <Trigger-Name>trg_audit</Trigger-Name>
    |        <Constraint-Name>fk_visits</Constraint-Name>
    |        <Relation>visits</Relation>
    |        <Time>0.010</Time>
    |        <Calls>1</Calls>
    |      </Trigger>
    |    </Triggers>
    |    <Settings>
    |      <search_path>billing, public</search_path>
    |    </Settings>
    |  </Query>
    |</explain>
""".trimMargin()

/**
 * **Every row of the text inventory, on one plan** — the same claim for the format whose structure is
 * lines rather than nesting. See [EXHAUSTIVE_XML_PLAN].
 */
internal val EXHAUSTIVE_TEXT_PLAN = """
    |Seq Scan on visits  (cost=0.00..1.00 rows=1 width=4)
    |  Subplan Name: CTE recent
    |  Output: (v.state = 1)
    |  Filter: (v.state = 1)
    |  Index Cond: (v.state = 1)
    |  Recheck Cond: (v.state = 1)
    |  TID Cond: (v.state = 1)
    |  Join Filter: (v.state = 1)
    |  Hash Cond: (v.state = 1)
    |  Merge Cond: (v.state = 1)
    |  One-Time Filter: (v.state = 1)
    |  Conflict Filter: (v.state = 1)
    |  Sort Key: (v.state = 1)
    |  Presorted Key: (v.state = 1)
    |  Group Key: (v.state = 1)
    |  Hash Key: (v.state = 1)
    |  Cache Key: (v.state = 1)
    |  Function Call: (v.state = 1)
    |  Order By: (v.state = 1)
    |  Sampling: (v.state = 1)
    |  Single Copy: true
    |  Conflict Resolution: UPDATE
    |  Cache Mode: logical
    |  Query Text: SELECT 1
    |  Remote SQL: SELECT 1
    |  Query Parameters: $1 = 'acme'
    |  Query Identifier: 8034093347371450093
    |  Planning Time: 1
    |  Execution Time: 1
    |  Planning: 1
    |  Execution: 1
    |  Total Runtime: 1
    |  Buffers: 1
    |  I/O Timings: 1
    |  WAL: 1
    |  Sort Method: 1
    |  Sort Space Used: 1
    |  Sort Space Type: 1
    |  Sort Methods Used: 1
    |  Sort Space Memory: 1
    |  Average Sort Space Used: 1
    |  Peak Sort Space Used: 1
    |  Group Count: 1
    |  Workers Planned: 1
    |  Workers Launched: 1
    |  Worker 0: 1
    |  Heap Blocks: 1
    |  Exact Heap Blocks: 1
    |  Lossy Heap Blocks: 1
    |  Buckets: 1
    |  Batches: 1
    |  Memory Usage: 1
    |  Peak Memory Usage: 1
    |  Disk Usage: 1
    |  Rows Removed by Filter: 1
    |  Rows Removed by Index Recheck: 1
    |  Rows Removed by Join Filter: 1
    |  Rows Removed by Conflict Filter: 1
    |  Functions: 1
    |  Options: 1
    |  Timing: 1
    |  JIT: 1
    |  Full-sort Groups: 1
    |  Pre-sorted Groups: 1
    |  Hits: 1
    |  Misses: 1
    |  Evictions: 1
    |  Overflows: 1
    |  Storage: 1
    |  Maximum Storage: 1
    |  Tuples Inserted: 1
    |  Conflicting Tuples: 1
    |  Heap Fetches: 1
    |  Index Searches: 1
    |  Subplans Removed: 1
    |  Disabled Nodes: 1
    |  Time: 1
    |  Calls: 1
""".trimMargin()
