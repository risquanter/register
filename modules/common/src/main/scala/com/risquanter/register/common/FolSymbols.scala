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

  /** A manually maintained mirror of the predicate and function symbol names
    * declared in the `RiskTreeKnowledgeBase` catalog. This literal lives in
    * `common` because the DTO-boundary gate needs it here, where the
    * `server`-side catalog is not on the dependency graph and its symbol union
    * cannot be computed. The C4 test in `RiskTreeKnowledgeBaseSpec` guards the
    * mirror against drift — it asserts `reservedFolNames` equals the catalog's
    * function ∪ predicate symbol names.
    */
  val reservedNames: Set[String] = Set(
    // predicates
    "leaf", "portfolio", "child_of", "descendant_of", "leaf_descendant_of",
    "gt_loss", "gt_prob", "eq", "named", "has_id",
    // functions
    "p95", "p99", "lec"
  )
