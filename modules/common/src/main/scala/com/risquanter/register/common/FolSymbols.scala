package com.risquanter.register.common

/** FOL symbol names that are reserved by the vague-quantifier query engine.
  *
  * These names may not be used as risk-tree node names. If a node name
  * collides with an entry here, it would shadow a query predicate or function
  * symbol, producing surprising or silently incorrect query results.
  *
  * Single source of truth shared by:
  *  - `RiskTreeRequests.requireNoReservedNames` (DTO-boundary gate in `common`)
  *  - `RiskTreeKnowledgeBase.reservedFolNames` (alarm-on-bypass safety net in `server`)
  *
  * Per PLAN-QUERY-NODE-NAME-LITERALS §6 D-2.b (combined A+B+C).
  */
object FolSymbols:

  /** Union of all predicate and function symbol names declared in the
    * `RiskTreeKnowledgeBase` catalog. Must be kept in sync with the symbol
    * declarations in `RiskTreeKnowledgeBase` — the C4 test in
    * `RiskTreeKnowledgeBaseSpec` asserts that `reservedFolNames` equals this set.
    */
  val reservedNames: Set[String] = Set(
    // predicates
    "leaf", "portfolio", "child_of", "descendant_of", "leaf_descendant_of",
    "gt_loss", "gt_prob",
    // functions
    "p95", "p99", "lec"
  )
