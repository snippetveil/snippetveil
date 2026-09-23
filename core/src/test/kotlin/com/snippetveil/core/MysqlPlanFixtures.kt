package com.snippetveil.core

/**
 * **The MySQL and MariaDB captures every claim in `MysqlPlanTest` and `MysqlRefusalTest` is asserted
 * over.**
 *
 * One statement, printed the ways the two engines print it: `EXPLAIN FORMAT=JSON` under each of the
 * two JSON schema versions, the `TREE` output, `EXPLAIN ANALYZE`, the default tabular table, and
 * MariaDB's own JSON and tables. Printings of one plan rather than unrelated fixtures, because **the
 * claims are comparisons**: the admitted formats have to carry the same names the refused ones would
 * have leaked, and the two engines' predicates have to disagree about texts that look alike.
 */
internal val MYSQL_V2_PLAN = """
    |{
    |  "query": "/* select#1 */ select `shop`.`v`.`id` from `shop`.`visits` `v` order by `shop`.`v`.`created_at`",
    |  "inputs": [
    |    {
    |      "operation": "Sort: shop.v.created_at",
    |      "access_type": "sort",
    |      "sort_key": "shop.v.created_at",
    |      "estimated_rows": 2.0,
    |      "estimated_total_cost": 0.85,
    |      "inputs": [
    |        {
    |          "operation": "Index lookup on v using visits_by_owner (owner_id = 42)",
    |          "access_type": "index",
    |          "index_access_type": "index_lookup",
    |          "index_name": "visits_by_owner",
    |          "lookup_condition": "owner_id = 42",
    |          "covering": false,
    |          "table_name": "v",
    |          "schema_name": "shop",
    |          "estimated_rows": 2.0,
    |          "estimated_total_cost": 0.70
    |        }
    |      ]
    |    }
    |  ]
    |}
""".trimMargin()

/**
 * **The same statement as MySQL's JSON version 1 prints it** — the older query-block document, and
 * the one whose `access_type`, `key_length` and `message` are written unescaped.
 */
internal val MYSQL_V1_PLAN = """
    |{
    |  "query_block": {
    |    "select_id": 1,
    |    "cost_info": {
    |      "query_cost": "0.85"
    |    },
    |    "ordering_operation": {
    |      "using_filesort": true,
    |      "table": {
    |        "table_name": "v",
    |        "access_type": "ref",
    |        "possible_keys": [
    |          "visits_by_owner"
    |        ],
    |        "key": "visits_by_owner",
    |        "used_key_parts": [
    |          "owner_id"
    |        ],
    |        "key_length": "4,8",
    |        "ref": [
    |          "const"
    |        ],
    |        "rows_examined_per_scan": 2,
    |        "rows_produced_per_join": 2,
    |        "filtered": "100.00",
    |        "cost_info": {
    |          "read_cost": "0.60",
    |          "eval_cost": "0.20",
    |          "prefix_cost": "0.85",
    |          "data_read_per_join": "32"
    |        },
    |        "used_columns": [
    |          "id",
    |          "created_at"
    |        ],
    |        "attached_condition": "(`shop`.`v`.`status` = 'open')"
    |      }
    |    }
    |  }
    |}
""".trimMargin()

/**
 * **MariaDB's JSON**, recognised by the keys MariaDB's own writer prints — `cost` where MySQL writes
 * `cost_info`, and the `r_` measurements `ANALYZE` adds.
 *
 * It is never read, so the fixture only has to be what MariaDB prints at the head. What is below the
 * head is here so that the fixture is a plan rather than a snippet of one.
 */
internal val MARIADB_JSON_PLAN = """
    |{
    |  "query_block": {
    |    "select_id": 1,
    |    "cost": 0.0085,
    |    "nested_loop": [
    |      {
    |        "table": {
    |          "table_name": "v",
    |          "access_type": "ref",
    |          "possible_keys": ["visits_by_owner"],
    |          "key": "visits_by_owner",
    |          "key_length": "4",
    |          "used_key_parts": ["owner_id"],
    |          "ref": ["const"],
    |          "rows": 2,
    |          "filtered": 100,
    |          "attached_condition": "v.status = 'open'"
    |        }
    |      }
    |    ]
    |  }
    |}
""".trimMargin()

/**
 * **A document whose opening key both engines write** — a query block going straight to its table.
 *
 * It is not a MySQL plan and it is not a MariaDB plan: it is a text this product cannot tell apart,
 * and the whole of what it is here to assert is that such a text takes the general refusal instead of
 * one engine's message.
 */
internal val AMBIGUOUS_JSON_PLAN = """
    |{
    |  "query_block": {
    |    "select_id": 1,
    |    "table": {
    |      "table_name": "v",
    |      "access_type": "ALL",
    |      "rows_examined_per_scan": 2
    |    }
    |  }
    |}
""".trimMargin()

/** **MySQL's `TREE` output**, whose aliases and index names are appended with nothing around them. */
internal val MYSQL_TREE_PLAN = """
    |-> Sort: shop.v.created_at  (cost=0.85 rows=2)
    |    -> Index lookup on v using visits_by_owner (owner_id = 42)  (cost=0.70 rows=2)
""".trimMargin()

/** **`EXPLAIN ANALYZE`**, which is the `TREE` output with the measurements filled in. */
internal val MYSQL_ANALYZE_PLAN = """
    |-> Sort: shop.v.created_at  (cost=0.85 rows=2) (actual time=0.041..0.042 rows=2 loops=1)
    |    -> Index lookup on v using visits_by_owner (owner_id = 42)  (cost=0.70 rows=2) (actual time=0.028..0.030 rows=2 loops=1)
""".trimMargin()

/**
 * **The default `EXPLAIN`** — the traditional table, inside the bordered frame the client's default
 * terminator draws around it.
 *
 * This is the paste a MySQL user arrives with having asked for nothing, which is why the refusal it
 * produces has a sentence of its own.
 */
internal val MYSQL_TABULAR_PLAN = """
    |+----+-------------+-------+------------+------+------------------+------------------+---------+-------+------+----------+-------+
    || id | select_type | table | partitions | type | possible_keys    | key              | key_len | ref   | rows | filtered | Extra |
    |+----+-------------+-------+------------+------+------------------+------------------+---------+-------+------+----------+-------+
    ||  1 | SIMPLE      | v     | NULL       | ref  | visits_by_owner  | visits_by_owner  | 4       | const |    2 |   100.00 | NULL  |
    |+----+-------------+-------+------------+------+------------------+------------------+---------+-------+------+----------+-------+
""".trimMargin()

/** **MariaDB's default `EXPLAIN`**, which prints neither of the two columns MySQL's table has. */
internal val MARIADB_TABULAR_PLAN = """
    |+------+-------------+-------+------+------------------+------------------+---------+-------+------+-------+
    || id   | select_type | table | type | possible_keys    | key              | key_len | ref   | rows | Extra |
    |+------+-------------+-------+------+------------------+------------------+---------+-------+------+-------+
    ||    1 | SIMPLE      | v     | ref  | visits_by_owner  | visits_by_owner  | 4       | const |    2 |       |
    |+------+-------------+-------+------+------------------+------------------+---------+-------+------+-------+
""".trimMargin()

/** **MariaDB's `ANALYZE`**, whose table carries the two `r_` columns MySQL has no equivalent of. */
internal val MARIADB_ANALYZE_PLAN = """
    |+------+-------------+-------+------+------------------+------------------+---------+-------+------+--------+----------+------------+-------+
    || id   | select_type | table | type | possible_keys    | key              | key_len | ref   | rows | r_rows | filtered | r_filtered | Extra |
    |+------+-------------+-------+------+------------------+------------------+---------+-------+------+--------+----------+------------+-------+
    ||    1 | SIMPLE      | v     | ref  | visits_by_owner  | visits_by_owner  | 4       | const |    2 |   2.00 |   100.00 |     100.00 |       |
    |+------+-------------+-------+------+------------------+------------------+---------+-------+------+--------+----------+------------+-------+
""".trimMargin()

/** **The warnings overlay**, which the two engines print identically. */
internal val WARNINGS_OVERLAY_PLAN = """
    |+-------+------+----------------------------------------+
    || Level | Code | Message                                |
    |+-------+------+----------------------------------------+
    || Note  | 1003 | /* select#1 */ select `shop`.`v`.`id`  |
    |+-------+------+----------------------------------------+
""".trimMargin()

/** The names the fixtures carry that must never reach a clipboard. */
internal val MYSQL_NAMES: List<String> = listOf("visits_by_owner", "created_at", "shop", "owner_id")

/** The same plan inside the frame the `mysql` client's vertical terminator draws around it. */
internal fun verticalFrameAround(plan: String, rows: Int = 1): String = """
    |*************************** 1. row ***************************
    |EXPLAIN: $plan
    |$rows row in set (0.00 sec)
""".trimMargin()

/** The same plan inside the bordered table the client's default terminator draws around it. */
internal fun borderedFrameAround(plan: String): String {
    val rule = "+" + "-".repeat(78) + "+"
    val rows = plan.lines().joinToString("\n") { "| " + it.padEnd(76) + " |" }
    return "$rule\n| EXPLAIN" + " ".repeat(70) + "|\n$rule\n$rows\n$rule\n1 row in set (0.00 sec)"
}

/**
 * **A version 2 plan carrying every row of its inventory and every sentence of its template set.**
 *
 * It is not a plan any one statement produces, and it is not meant to be: the claim it exists for is
 * that **every row reads**, and a row nothing exercises is a row whose first reader is a user whose
 * plan refuses — or, worse, one whose plan is read under a treatment nobody ever ran. See
 * `MysqlInventoryTest`.
 */
internal val EXHAUSTIVE_MYSQL_V2_PLAN = """
    |{
    |  "query": "/* select#1 */ select `shop`.`v`.`id` from `shop`.`visits` `v`",
    |  "json_schema_version": "2.0",
    |  "inputs": [
    |    {
    |      "operation": "Limit: 10 row(s)",
    |      "limit_rows": 10,
    |      "estimated_rows": 10.0,
    |      "estimated_total_cost": 4.25,
    |      "estimated_first_row_cost": 0.35,
    |      "inputs": [
    |        {
    |          "operation": "Stream results",
    |          "access_type": "stream",
    |          "rows_per_scan": 2.0
    |        },
    |        {
    |          "operation": "Materialize",
    |          "heap_usage_bytes": 4096
    |        },
    |        {
    |          "operation": "Rows fetched before execution",
    |          "actual_rows": 1.0,
    |          "actual_loops": 1
    |        },
    |        {
    |          "operation": "Nested loop inner join",
    |          "join_type": "inner",
    |          "join_algorithm": "nested_loop",
    |          "actual_first_row_ms": 0.021,
    |          "actual_last_row_ms": 0.044
    |        },
    |        {
    |          "operation": "Inner hash join (v.owner_id = o.id)",
    |          "hash_condition": "v.owner_id = o.id"
    |        },
    |        {
    |          "operation": "Aggregate: count(0)",
    |          "aggregate_function": "count(0)",
    |          "subqueries": [
    |            {
    |              "operation": "Table scan on totals",
    |              "table_name": "totals"
    |            }
    |          ]
    |        },
    |        {
    |          "operation": "Filter: (v.status = 'open')",
    |          "condition": "(v.status = 'open')",
    |          "materialized_from_subquery": {
    |            "operation": "Table scan on drafts",
    |            "table_name": "drafts"
    |          }
    |        },
    |        {
    |          "operation": "Sort: shop.v.created_at",
    |          "sort_key": "shop.v.created_at"
    |        },
    |        {
    |          "operation": "Table scan on v",
    |          "table_name": "v",
    |          "schema_name": "shop"
    |        },
    |        {
    |          "operation": "Index scan on v using visits_by_owner",
    |          "table_name": "v",
    |          "index_name": "visits_by_owner"
    |        },
    |        {
    |          "operation": "Covering index scan on v using visits_by_owner",
    |          "table_name": "v",
    |          "index_name": "visits_by_owner",
    |          "covering": true
    |        },
    |        {
    |          "operation": "Index lookup on v using visits_by_owner (owner_id = 42)",
    |          "table_name": "v",
    |          "index_name": "visits_by_owner",
    |          "index_access_type": "index_lookup",
    |          "lookup_condition": "owner_id = 42"
    |        },
    |        {
    |          "operation": "Single-row index lookup on v using visits_by_owner (owner_id = 42)",
    |          "table_name": "v",
    |          "index_name": "visits_by_owner",
    |          "lookup_condition": "owner_id = 42"
    |        },
    |        {
    |          "operation": "Covering index lookup on v using visits_by_owner (owner_id = 42)",
    |          "table_name": "v",
    |          "index_name": "visits_by_owner",
    |          "lookup_condition": "owner_id = 42"
    |        },
    |        {
    |          "operation": "Index range scan on v using visits_by_owner over (1 < owner_id)",
    |          "table_name": "v",
    |          "index_name": "visits_by_owner",
    |          "index_range": "(1 < owner_id)"
    |        }
    |      ]
    |    }
    |  ]
    |}
""".trimMargin()

/** **A version 1 plan carrying every row of its three field sets.** See [EXHAUSTIVE_MYSQL_V2_PLAN]. */
internal val EXHAUSTIVE_MYSQL_V1_PLAN = """
    |{
    |  "query_block": {
    |    "select_id": 1,
    |    "cost_info": {
    |      "query_cost": "4.25",
    |      "sort_cost": "0.20"
    |    },
    |    "ordering_operation": {
    |      "using_filesort": true,
    |      "grouping_operation": {
    |        "using_temporary_table": true,
    |        "duplicates_removal": {
    |          "nested_loop": [
    |            {
    |              "table": {
    |                "table_name": "v",
    |                "access_type": "ref",
    |                "possible_keys": [
    |                  "visits_by_owner"
    |                ],
    |                "key": "visits_by_owner",
    |                "used_key_parts": [
    |                  "owner_id"
    |                ],
    |                "key_length": "4,8",
    |                "partitions": [
    |                  "p2024"
    |                ],
    |                "ref": [
    |                  "const"
    |                ],
    |                "using_index": true,
    |                "using_temporary_table": false,
    |                "using_filesort": false,
    |                "rows_examined_per_scan": 2,
    |                "rows_produced_per_join": 2,
    |                "filtered": "100.00",
    |                "cost_info": {
    |                  "read_cost": "0.60",
    |                  "eval_cost": "0.20",
    |                  "prefix_cost": "0.85",
    |                  "data_read_per_join": "32"
    |                },
    |                "used_columns": [
    |                  "id",
    |                  "created_at"
    |                ],
    |                "index_condition": "(`shop`.`v`.`owner_id` = 42)",
    |                "attached_condition": "(`shop`.`v`.`status` = 'open')",
    |                "attached_subqueries": [
    |                  {
    |                    "dependent": true,
    |                    "cacheable": false,
    |                    "query_block": {
    |                      "select_id": 2,
    |                      "message": "No tables used"
    |                    }
    |                  }
    |                ],
    |                "materialized_from_subquery": {
    |                  "query_block": {
    |                    "select_id": 3,
    |                    "table": {
    |                      "table_name": "drafts",
    |                      "access_type": "ALL",
    |                      "message": "Impossible WHERE"
    |                    }
    |                  }
    |                }
    |              }
    |            }
    |          ]
    |        }
    |      }
    |    },
    |    "union_result": {
    |      "using_temporary_table": false,
    |      "table_name": "<union1,2>",
    |      "access_type": "ALL",
    |      "query_specifications": [
    |        {
    |          "query_block": {
    |            "select_id": 2,
    |            "message": "No tables used"
    |          }
    |        }
    |      ]
    |    }
    |  }
    |}
""".trimMargin()
