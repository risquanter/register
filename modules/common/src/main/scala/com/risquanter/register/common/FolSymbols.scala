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
  */
object FolSymbols:

  /** A manually maintained mirror of the predicate and function symbol names
    * declared in the `RiskTreeKnowledgeBase` catalog, plus the catalog's two
    * constants, which are the `ValuationBasis` symbols and are read from that
    * enum rather than restated. The mirror lives in `common` because the
    * DTO-boundary gate needs it here, where the `server`-side catalog is not on
    * the dependency graph and its symbol union cannot be computed. The C4 test
    * in `RiskTreeKnowledgeBaseSpec` guards the mirror against drift — it
    * asserts `reservedFolNames` equals the catalog's function ∪ predicate ∪
    * constant symbol names.
    */
  val reservedNames: Set[String] = Set(
    // predicates
    "leaf", "portfolio", "child_of", "descendant_of", "leaf_descendant_of",
    "gt_loss", "gt_prob", "eq", "named_risk", "risk_id",
    "named_mitigation", "mitigation_id", "mitigate", "mitigated", "unmitigated",
    // functions
    "p95", "p99", "lec"
  ) ++ ValuationBasis.values.map(_.symbol)
