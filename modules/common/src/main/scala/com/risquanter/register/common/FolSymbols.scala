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

  /** The query language's value functions: the figures a query reads off a node.
    *
    * `symbol` is the name the query engine binds, so it is declared rather than
    * derived from the case name: renaming a case must not silently rename a
    * keyword of the query language (ADR-037).
    */
  enum ValueFunction(val symbol: String):
    case P95 extends ValueFunction("p95")
    case P99 extends ValueFunction("p99")
    case Lec extends ValueFunction("lec")

  object ValueFunction:
    /** The value function a bound symbol names, if it names one.
      *
      * {{{
      * fromSymbol("p95")  // Some(P95)
      * fromSymbol("leaf") // None
      * }}}
      */
    def fromSymbol(symbol: String): Option[ValueFunction] =
      ValueFunction.values.find(_.symbol == symbol)

  /** A manually maintained mirror of the predicate symbol names declared in the
    * `RiskTreeKnowledgeBase` catalog, plus its function symbols and its two
    * constants, which are read from `ValueFunction` and `ValuationBasis` rather
    * than restated. The mirror lives in `common` because the
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
    "named_mitigation", "mitigation_id", "mitigate", "mitigated", "unmitigated"
  ) ++ ValueFunction.values.map(_.symbol) ++ ValuationBasis.values.map(_.symbol)
