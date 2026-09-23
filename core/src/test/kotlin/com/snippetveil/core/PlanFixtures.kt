package com.snippetveil.core

/**
 * **One plan, printed four ways** — the corpus every statement about *the formats* is asserted over.
 *
 * It is the same query in PostgreSQL's text, JSON, YAML and XML outputs: two relations, one of them
 * schema-qualified and both aliased, an index, a cast, a literal, a `search_path` naming one of the
 * schemas, a flagged setting, a setting no vocabulary row covers, and the two timings. Four printings
 * of one plan rather than four unrelated fixtures, because **the claims are comparisons**: the
 * predicates must be pairwise exclusive over it, and a schema on the path must share its placeholder
 * with the same schema in a qualified relation.
 */
internal val TEXT_PLAN = JOIN

/** The same plan as `EXPLAIN (FORMAT JSON)` prints it. See [TEXT_PLAN]. */
internal val JSON_PLAN = """
    |[
    |  {
    |    "Plan": {
    |      "Node Type": "Nested Loop",
    |      "Parallel Aware": false,
    |      "Join Type": "Inner",
    |      "Startup Cost": 0.29,
    |      "Total Cost": 16.97,
    |      "Plan Rows": 2,
    |      "Plan Width": 68,
    |      "Inner Unique": true,
    |      "Plans": [
    |        {
    |          "Node Type": "Index Scan",
    |          "Parent Relationship": "Outer",
    |          "Parallel Aware": false,
    |          "Scan Direction": "Forward",
    |          "Index Name": "visits_pkey",
    |          "Relation Name": "visits",
    |          "Schema": "public",
    |          "Alias": "v",
    |          "Startup Cost": 0.29,
    |          "Total Cost": 8.30,
    |          "Plan Rows": 1,
    |          "Plan Width": 36,
    |          "Index Cond": "(id = 42)",
    |          "Filter": "((status)::text = 'open'::text)"
    |        },
    |        {
    |          "Node Type": "Seq Scan",
    |          "Parent Relationship": "Inner",
    |          "Parallel Aware": false,
    |          "Relation Name": "invoices",
    |          "Schema": "billing",
    |          "Alias": "i",
    |          "Startup Cost": 0.00,
    |          "Total Cost": 8.65,
    |          "Plan Rows": 1,
    |          "Plan Width": 32,
    |          "Filter": "(i.visit_id = v.id)"
    |        }
    |      ]
    |    },
    |    "Settings": {
    |      "search_path": "billing, \"${'$'}user\", public",
    |      "work_mem": "8MB",
    |      "acme.audit_level": "verbose"
    |    },
    |    "Planning Time": 0.123,
    |    "Execution Time": 0.456
    |  }
    |]
""".trimMargin()

/** The same plan as `EXPLAIN (FORMAT YAML)` prints it. See [TEXT_PLAN]. */
internal val YAML_PLAN = """
    |- Plan:
    |    Node Type: "Nested Loop"
    |    Parallel Aware: false
    |    Join Type: "Inner"
    |    Startup Cost: 0.29
    |    Total Cost: 16.97
    |    Plan Rows: 2
    |    Plan Width: 68
    |    Inner Unique: true
    |    Plans:
    |      - Node Type: "Index Scan"
    |        Parent Relationship: "Outer"
    |        Parallel Aware: false
    |        Scan Direction: "Forward"
    |        Index Name: "visits_pkey"
    |        Relation Name: "visits"
    |        Schema: "public"
    |        Alias: "v"
    |        Startup Cost: 0.29
    |        Total Cost: 8.30
    |        Plan Rows: 1
    |        Plan Width: 36
    |        Index Cond: "(id = 42)"
    |        Filter: "((status)::text = 'open'::text)"
    |      - Node Type: "Seq Scan"
    |        Parent Relationship: "Inner"
    |        Parallel Aware: false
    |        Relation Name: "invoices"
    |        Schema: "billing"
    |        Alias: "i"
    |        Startup Cost: 0.00
    |        Total Cost: 8.65
    |        Plan Rows: 1
    |        Plan Width: 32
    |        Filter: "(i.visit_id = v.id)"
    |  Settings:
    |    search_path: "billing, \"${'$'}user\", public"
    |    work_mem: "8MB"
    |    acme.audit_level: "verbose"
    |  Planning Time: 0.123
    |  Execution Time: 0.456
""".trimMargin()

/** The same plan as `EXPLAIN (FORMAT XML)` prints it. See [TEXT_PLAN]. */
internal val XML_PLAN = """
    |<explain xmlns="http://www.postgresql.org/2009/explain">
    |  <Query>
    |    <Plan>
    |      <Node-Type>Nested Loop</Node-Type>
    |      <Parallel-Aware>false</Parallel-Aware>
    |      <Join-Type>Inner</Join-Type>
    |      <Startup-Cost>0.29</Startup-Cost>
    |      <Total-Cost>16.97</Total-Cost>
    |      <Plan-Rows>2</Plan-Rows>
    |      <Plan-Width>68</Plan-Width>
    |      <Inner-Unique>true</Inner-Unique>
    |      <Plans>
    |        <Plan>
    |          <Node-Type>Index Scan</Node-Type>
    |          <Parent-Relationship>Outer</Parent-Relationship>
    |          <Parallel-Aware>false</Parallel-Aware>
    |          <Scan-Direction>Forward</Scan-Direction>
    |          <Index-Name>visits_pkey</Index-Name>
    |          <Relation-Name>visits</Relation-Name>
    |          <Schema>public</Schema>
    |          <Alias>v</Alias>
    |          <Startup-Cost>0.29</Startup-Cost>
    |          <Total-Cost>8.30</Total-Cost>
    |          <Plan-Rows>1</Plan-Rows>
    |          <Plan-Width>36</Plan-Width>
    |          <Index-Cond>(id = 42)</Index-Cond>
    |          <Filter>((status)::text = 'open'::text)</Filter>
    |        </Plan>
    |        <Plan>
    |          <Node-Type>Seq Scan</Node-Type>
    |          <Parent-Relationship>Inner</Parent-Relationship>
    |          <Parallel-Aware>false</Parallel-Aware>
    |          <Relation-Name>invoices</Relation-Name>
    |          <Schema>billing</Schema>
    |          <Alias>i</Alias>
    |          <Startup-Cost>0.00</Startup-Cost>
    |          <Total-Cost>8.65</Total-Cost>
    |          <Plan-Rows>1</Plan-Rows>
    |          <Plan-Width>32</Plan-Width>
    |          <Filter>(i.visit_id = v.id)</Filter>
    |        </Plan>
    |      </Plans>
    |    </Plan>
    |    <Settings>
    |      <search_path>billing, "${'$'}user", public</search_path>
    |      <work_mem>8MB</work_mem>
    |      <acme.audit_level>verbose</acme.audit_level>
    |    </Settings>
    |    <Planning-Time>0.123</Planning-Time>
    |    <Execution-Time>0.456</Execution-Time>
    |  </Query>
    |</explain>
""".trimMargin()

/** The three structured printings, named, so a pairwise claim can be written once. */
internal val STRUCTURED_PLANS: Map<String, String> =
    mapOf("json" to JSON_PLAN, "yaml" to YAML_PLAN, "xml" to XML_PLAN)
