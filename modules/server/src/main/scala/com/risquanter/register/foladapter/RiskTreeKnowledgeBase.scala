package com.risquanter.register.foladapter

import com.risquanter.register.domain.data.{RiskTree, RiskNode, RiskLeaf, RiskPortfolio, LossDistribution, RiskResult}
import com.risquanter.register.domain.data.iron.NodeId
import com.risquanter.register.domain.tree.TreeIndex

import fol.typed.{TypeCatalog, TypeDecl, TypeId, SymbolName, FunctionSig, PredicateSig, RuntimeModel, RuntimeDispatcher, Value, LiteralValue}

/** Bridges the register domain (RiskTree + simulation results) to the fol-engine
  * typed evaluation pipeline.
  *
  * Provides a `TypeCatalog` declaring register's many-sorted schema and a
  * `RuntimeModel` containing domain elements and a dispatcher that backs
  * structural predicates with `TreeIndex` and simulation functions with
  * `LossDistribution` data.
  *
  * == Sort System ==
  *
  * | Sort        | Scala type | Description |
  * |-------------|------------|-------------|
  * | Asset       | String     | Node names (domain elements) |
  * | Loss        | Long       | Monetary loss values |
  * | Probability | Double     | Exceedance probabilities |
  * | Bool        | Boolean    | Truth values (range of predicates) |
  *
  * == Functions ==
  *
  * | Name | Signature | Backed by |
  * |------|-----------|-----------|
  * | p95  | Asset → Loss | 95th percentile from `outcomeCount` |
  * | p99  | Asset → Loss | 99th percentile from `outcomeCount` |
  * | lec  | (Asset, Loss) → Probability | `probOfExceedance(threshold)` |
  *
  * == Predicates ==
  *
  * | Name | Signature | Backed by |
  * |------|-----------|-----------|
  * | leaf | (Asset) | `TreeIndex.leafIds` |
  * | portfolio | (Asset) | node is `RiskPortfolio` |
  * | child_of | (Asset, Asset) | `TreeIndex.children` |
  * | descendant_of | (Asset, Asset) | `TreeIndex.descendants` |
  * | leaf_descendant_of | (Asset, Asset) | descendants ∩ leafIds |
  * | gt_loss | (Loss, Loss) | `a > b` (Long) |
  * | gt_prob | (Probability, Probability) | `a > b` (Double) |
  *
  * @param tree    Risk tree providing structure (TreeIndex) and node metadata
  * @param results Simulation results indexed by NodeId (from RiskResultResolver.ensureCachedAll)
  */
class RiskTreeKnowledgeBase(tree: RiskTree, results: Map[NodeId, RiskResult]):

  // ── Sort declarations ──────────────────────────────────────────────

  val assetSort: TypeId       = TypeId("Asset")
  val lossSort: TypeId        = TypeId("Loss")
  val probabilitySort: TypeId = TypeId("Probability")
  val boolSort: TypeId        = TypeId("Bool")

  // ── Name → NodeId bidirectional lookup ─────────────────────────────

  /** Maps node name → NodeId for reverse lookups from evaluation output. */
  val nameToNodeId: Map[String, NodeId] =
    tree.index.nodes.map { case (nodeId, node) => node.name -> nodeId }

  /** Maps node name → RiskResult for simulation dispatch. */
  private val nameToResult: Map[String, RiskResult] =
    results.flatMap { case (nodeId, result) =>
      tree.index.nodes.get(nodeId).map(_.name -> result)
    }

  // ── Percentile computation ─────────────────────────────────────────

  /** Compute the loss value at a given percentile from the empirical distribution.
    *
    * Walks the sorted `outcomeCount: TreeMap[Loss, Int]` building a cumulative
    * frequency ratio. Returns the first loss whose cumulative proportion ≥ p.
    *
    * @param result Simulation result with outcome counts
    * @param p      Percentile as fraction in [0.0, 1.0]
    * @return Loss value at the given percentile, or 0L if no outcomes
    */
  private def percentile(result: RiskResult, p: Double): Long =
    val outcomes = result.outcomeCount
    val totalTrials = outcomes.values.sum
    if outcomes.isEmpty || totalTrials == 0 then 0L
    else
      val target = totalTrials.toDouble * p
      outcomes.iterator
        .scanLeft((0L, 0L)) { case ((_, cum), (loss, count)) => (loss, cum + count) }
        .drop(1)
        .find(_._2.toDouble >= target)
        .map(_._1)
        .getOrElse(outcomes.lastKey)

  // ── TypeCatalog ────────────────────────────────────────────────────

  val catalog: TypeCatalog = TypeCatalog.unsafe(
    types = Set(
      TypeDecl.DomainType(assetSort),
      TypeDecl.ValueType(lossSort),
      TypeDecl.ValueType(probabilitySort),
      TypeDecl.ValueType(boolSort)
    ),
    functions = Map(
      SymbolName("p95") -> FunctionSig(List(assetSort), lossSort),
      SymbolName("p99") -> FunctionSig(List(assetSort), lossSort),
      SymbolName("lec") -> FunctionSig(List(assetSort, lossSort), probabilitySort)
    ),
    predicates = Map(
      SymbolName("leaf")               -> PredicateSig(List(assetSort)),
      SymbolName("portfolio")          -> PredicateSig(List(assetSort)),
      SymbolName("child_of")           -> PredicateSig(List(assetSort, assetSort)),
      SymbolName("descendant_of")      -> PredicateSig(List(assetSort, assetSort)),
      SymbolName("leaf_descendant_of") -> PredicateSig(List(assetSort, assetSort)),
      SymbolName("gt_loss")            -> PredicateSig(List(lossSort, lossSort)),
      SymbolName("gt_prob")            -> PredicateSig(List(probabilitySort, probabilitySort))
    ),
    literalValidators = Map(
      lossSort        -> ((s: String) => if s.nonEmpty && s.forall(_.isDigit) then Some(LiteralValue.IntLiteral(s.toLong)) else None),
      probabilitySort -> ((s: String) => if s.matches("[0-9]+(\\.[0-9]+)?") then Some(LiteralValue.FloatLiteral(s.toDouble)) else None)
    )
  )

  // ── RuntimeDispatcher ──────────────────────────────────────────────

  private val index: TreeIndex = tree.index

  private val leafNames: Set[String] =
    index.leafIds.flatMap(id => index.nodes.get(id).map(_.name))

  private val portfolioNames: Set[String] =
    index.nodes.collect { case (_, p: RiskPortfolio) => p.name }.toSet

  /** Pre-computed children lookup: parent name → Set[child name]. */
  private val childrenByName: Map[String, Set[String]] =
    index.children.map { case (parentId, childIds) =>
      val parentName = index.nodes.get(parentId).map(_.name).getOrElse("")
      val childNames = childIds.flatMap(id => index.nodes.get(id).map(_.name)).toSet
      parentName -> childNames
    }

  /** Pre-computed strict descendants lookup: ancestor name → Set[descendant name].
    *
    * Uses standard graph-theory / FOL semantics: the descendant relation is
    * '''irreflexive''' — a node is never its own descendant.  `TreeIndex.descendants`
    * includes the node itself, so we subtract it here.
    */
  private val descendantsByName: Map[String, Set[String]] =
    index.nodes.map { case (nodeId, node) =>
      val descIds = index.descendants(nodeId) - nodeId  // strict (irreflexive)
      val descNames = descIds.flatMap(id => index.nodes.get(id).map(_.name))
      node.name -> descNames
    }

  val dispatcher: RuntimeDispatcher = new RuntimeDispatcher:

    override def functionSymbols: Set[SymbolName] =
      Set(SymbolName("p95"), SymbolName("p99"), SymbolName("lec"))

    override def predicateSymbols: Set[SymbolName] =
      Set(
        SymbolName("leaf"), SymbolName("portfolio"),
        SymbolName("child_of"), SymbolName("descendant_of"),
        SymbolName("leaf_descendant_of"),
        SymbolName("gt_loss"), SymbolName("gt_prob")
      )

    override def evalFunction(name: SymbolName, args: List[Value]): Either[String, LiteralValue] =
      name.value match
        case "p95" =>
          for
            assetName <- extractString(args, 0, "p95")
            result    <- lookupResult(assetName, "p95")
          yield LiteralValue.IntLiteral(percentile(result, 0.95))

        case "p99" =>
          for
            assetName <- extractString(args, 0, "p99")
            result    <- lookupResult(assetName, "p99")
          yield LiteralValue.IntLiteral(percentile(result, 0.99))

        case "lec" =>
          for
            assetName <- extractString(args, 0, "lec")
            threshold <- extractLong(args, 1, "lec")
            result    <- lookupResult(assetName, "lec")
          yield LiteralValue.FloatLiteral(result.probOfExceedance(threshold))

        case other =>
          Left(s"Unknown function: $other")

    override def evalPredicate(name: SymbolName, args: List[Value]): Either[String, Boolean] =
      name.value match
        case "leaf" =>
          extractString(args, 0, "leaf").map(leafNames.contains)

        case "portfolio" =>
          extractString(args, 0, "portfolio").map(portfolioNames.contains)

        case "child_of" =>
          for
            child  <- extractString(args, 0, "child_of")
            parent <- extractString(args, 1, "child_of")
          yield childrenByName.getOrElse(parent, Set.empty).contains(child)

        case "descendant_of" =>
          for
            desc     <- extractString(args, 0, "descendant_of")
            ancestor <- extractString(args, 1, "descendant_of")
          yield descendantsByName.getOrElse(ancestor, Set.empty).contains(desc)

        case "leaf_descendant_of" =>
          for
            desc     <- extractString(args, 0, "leaf_descendant_of")
            ancestor <- extractString(args, 1, "leaf_descendant_of")
          yield
            val descs = descendantsByName.getOrElse(ancestor, Set.empty)
            descs.contains(desc) && leafNames.contains(desc)

        case "gt_loss" =>
          for
            a <- extractLong(args, 0, "gt_loss")
            b <- extractLong(args, 1, "gt_loss")
          yield a > b

        case "gt_prob" =>
          for
            a <- extractDouble(args, 0, "gt_prob")
            b <- extractDouble(args, 1, "gt_prob")
          yield a > b

        case other =>
          Left(s"Unknown predicate: $other")

    // ── Value extraction helpers ─────────────────────────────────────

    private def extractString(args: List[Value], idx: Int, ctx: String): Either[String, String] =
      args.lift(idx).toRight(s"$ctx: missing argument at index $idx").flatMap { v =>
        v.raw match
          case s: String => Right(s)
          case other     => Left(s"$ctx: expected String at index $idx, got ${other.getClass.getSimpleName}")
      }

    private def extractLong(args: List[Value], idx: Int, ctx: String): Either[String, Long] =
      args.lift(idx).toRight(s"$ctx: missing argument at index $idx").flatMap { v =>
        v.raw match
          case LiteralValue.IntLiteral(l)   => Right(l)
          case LiteralValue.FloatLiteral(d) => Right(d.toLong)
          case other      => Left(s"$ctx: expected numeric LiteralValue at index $idx, got ${other.getClass.getSimpleName}")
      }

    private def extractDouble(args: List[Value], idx: Int, ctx: String): Either[String, Double] =
      args.lift(idx).toRight(s"$ctx: missing argument at index $idx").flatMap { v =>
        v.raw match
          case LiteralValue.FloatLiteral(d) => Right(d)
          case LiteralValue.IntLiteral(l)   => Right(l.toDouble)
          case other      => Left(s"$ctx: expected numeric LiteralValue at index $idx, got ${other.getClass.getSimpleName}")
      }

    private def lookupResult(assetName: String, ctx: String): Either[String, RiskResult] =
      nameToResult.get(assetName).toRight(s"$ctx: no simulation result for asset '$assetName'")

  end dispatcher

  // ── RuntimeModel ───────────────────────────────────────────────────

  /** Domain elements: one `Value(Asset, nodeName)` per tree node. */
  private val assetDomain: Set[Value] =
    tree.index.nodes.values.map(node => Value(assetSort, node.name)).toSet

  val model: RuntimeModel = RuntimeModel(
    domains = Map(assetSort -> assetDomain),
    dispatcher = dispatcher
  )

end RiskTreeKnowledgeBase
