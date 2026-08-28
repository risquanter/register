package com.risquanter.register.foladapter

import com.risquanter.register.domain.data.{RiskTree, RiskPortfolio, LossDistribution}
import com.risquanter.register.domain.data.iron.NodeId
import com.risquanter.register.domain.tree.TreeIndex
import com.risquanter.register.simulation.LECGenerator
import com.risquanter.register.common.FolSymbols

import vql.typed.{TypeCatalog, TypeDecl, TypeId, SymbolName, FunctionSig, PredicateSig, RuntimeModel, Value}
import vql.typed.{Extract, extract}
import vql.typed.MapDispatcher

/** Bridges the register domain (RiskTree + simulation results) to the vql-engine
  * typed evaluation pipeline.
  *
  * Provides a `TypeCatalog` declaring register's many-sorted schema and a
  * `RuntimeModel` containing domain elements and a dispatcher that backs
  * structural predicates with `TreeIndex` and simulation functions with
  * `LossDistribution` data.
  *
  * == Sort System ==
  *
  * | Sort            | Scala carrier | Description |
  * |-----------------|---------------|-------------|
  * | Node            | `NodeId`      | Tree node identity (leaves and portfolios) |
  * | NodeNameLiteral | `NodeId`      | A node named by a quoted name literal (`named`) |
  * | NodeIdLiteral   | `NodeId`      | A node named by a quoted id literal (`has_id`) |
  * | Loss            | Long          | Monetary loss values |
  * | Probability     | Double        | Exceedance probabilities |
  *
  * == Functions ==
  *
  * | Name | Signature | Backed by |
  * |------|-----------|-----------|
  * | p95  | Node → Loss | 95th percentile from `outcomeCount` |
  * | p99  | Node → Loss | 99th percentile from `outcomeCount` |
  * | lec  | (Node, Loss) → Probability | `probOfExceedance(threshold)` |
  *
  * == Predicates ==
  *
  * | Name | Signature | Backed by |
  * |------|-----------|-----------|
  * | leaf | (Node) | `TreeIndex.leafIds` |
  * | portfolio | (Node) | node is `RiskPortfolio` |
  * | child_of | (Node, Node) | `TreeIndex.children` |
  * | descendant_of | (Node, Node) | `TreeIndex.isAncestor` (strict) |
  * | leaf_descendant_of | (Node, Node) | `isAncestor` (strict) ∩ leafIds |
  * | gt_loss | (Loss, Loss) | `a > b` (Long) |
  * | gt_prob | (Probability, Probability) | `a > b` (Double) |
  * | eq | (Node, Node) | `NodeId` equality (node identity between two variables) |
  * | named | (Node, NodeNameLiteral) | pins a node by name; `NodeId` equality |
  * | has_id | (Node, NodeIdLiteral) | pins a node by id; `NodeId` equality |
  *
  * @param tree    Risk tree providing structure (TreeIndex) and node metadata
  * @param results Simulation results indexed by NodeId (from CachedResultResolver.ensureCachedAll)
  */
class RiskTreeKnowledgeBase(tree: RiskTree, results: Map[NodeId, LossDistribution]):

  import RiskTreeKnowledgeBase.given

  // ── Sort declarations ──────────────────────────────────────────────

  val nodeSort: TypeId            = RiskTreeKnowledgeBase.NodeSort
  val lossSort: TypeId            = TypeId("Loss")
  val probabilitySort: TypeId     = TypeId("Probability")
  val nodeNameLiteralSort: TypeId = RiskTreeKnowledgeBase.NodeNameLiteralSort
  val nodeIdLiteralSort: TypeId   = RiskTreeKnowledgeBase.NodeIdLiteralSort

  // ── Percentile computation ─────────────────────────────────────────

  /** Compute the unconditional VaR at a given percentile.
    *
    * Delegates to `LECGenerator.unconditionalQuantile` — the single
    * canonical implementation of Q(p) = X_{(⌈Np⌉)} over the full
    * empirical CDF including implicit zero-loss mass from non-occurring
    * trials.
    *
    * @param result Simulation result (carries nTrials and sparse outcomeCount)
    * @param p      Percentile as fraction in [0.0, 1.0]
    * @return Loss value at the unconditional percentile, or 0L if empty
    */
  private def percentile(result: LossDistribution, p: Double): Long =
    LECGenerator.unconditionalQuantile(result, p)

  // ── TypeCatalog ────────────────────────────────────────────────────

  /** Names that, if used as node names, would shadow this catalog's own
    * function or predicate symbols. Bare references to these names go through
    * the formula/term parser's symbol arms (e.g. `p95(x)` → `Fn("p95",[x])`),
    * so a name binding for the same symbol would only be reachable via the
    * quoted-literal path (`"p95"`). Allowing it would silently bind the node
    * with that name through the node-sort literal validator and produce
    * surprising behaviour.
    *
    * This is an **alarm-on-bypass** safety net: the supported flow is for the DTO
    * validators to reject such names at create-tree time. If a tree carrying
    * a colliding name reaches the KB anyway (direct repo write, migration,
    * Irmin merge), we exclude the entry from [[nameToId]] and surface it via
    * [[nameCollisions]] for the orchestrating service to log.
    *
    * The set is the union of this catalog's own function and predicate symbol
    * names.
    */
  val reservedFolNames: Set[String] = FolSymbols.reservedNames

  /** Diagnostic record of node names that were skipped (reserved-symbol
    * collision) or coalesced (duplicate name, last-write-wins) when building
    * [[nameToId]]. Empty in the supported flow — the DTO validators
    * (`requireUniqueNames`, `requireNoReservedNames`) gate at create-tree time.
    *
    * Surfaced for the orchestrating service (e.g. `QueryServiceLive`) to log
    * via `ZIO.logWarning` so any DTO-bypass path is observable.
    */
  val nameCollisions: List[String] =
    val allNames = tree.index.nodes.values.map(_.name.value).toList
    val reserved = allNames.filter(reservedFolNames.contains).distinct.sorted
      .map(n => s"reserved:$n")
    val duplicates = allNames
      .filterNot(reservedFolNames.contains)
      .groupBy(identity).collect { case (n, xs) if xs.size > 1 => n }
      .toList.sorted
      .map(n => s"duplicate:$n")
    reserved ++ duplicates

  /** Node name → `NodeId`, backing the name branch of the node-sort literal
    * validator so a quoted node name (`child_of(x, "IT Risk")`) resolves to a
    * node id. Reserved-symbol names are excluded (see [[reservedFolNames]] /
    * [[nameCollisions]]); duplicate names are last-write-wins until
    * `RiskTree.fromNodes` enforces node-name uniqueness. The validator returns
    * a `NodeId`, never a raw string, so the engine carries node identity, not
    * a name.
    */
  val nameToId: Map[String, NodeId] =
    tree.index.nodes.iterator.collect {
      case (id, node) if !reservedFolNames.contains(node.name.value) => node.name.value -> id
    }.toMap

  val catalog: TypeCatalog = TypeCatalog.unsafe(
    types = Set(
      TypeDecl.DomainType(nodeSort),
      TypeDecl.ValueType(lossSort),
      TypeDecl.ValueType(probabilitySort),
      TypeDecl.ValueType(nodeNameLiteralSort),
      TypeDecl.ValueType(nodeIdLiteralSort)
    ),
    // No constant symbols: node names resolve on demand through the node-sort
    // literal validator below, not as pre-registered constants. This is where a
    // constant (a fixed named value in the query vocabulary) would be declared.
    constants = Map.empty,
    functions = Map(
      SymbolName("p95") -> FunctionSig(List(nodeSort), lossSort),
      SymbolName("p99") -> FunctionSig(List(nodeSort), lossSort),
      SymbolName("lec") -> FunctionSig(List(nodeSort, lossSort), probabilitySort)
    ),
    predicates = Map(
      SymbolName("leaf")               -> PredicateSig(List(nodeSort)),
      SymbolName("portfolio")          -> PredicateSig(List(nodeSort)),
      SymbolName("child_of")           -> PredicateSig(List(nodeSort, nodeSort)),
      SymbolName("descendant_of")      -> PredicateSig(List(nodeSort, nodeSort)),
      SymbolName("leaf_descendant_of") -> PredicateSig(List(nodeSort, nodeSort)),
      SymbolName("gt_loss")            -> PredicateSig(List(lossSort, lossSort)),
      SymbolName("gt_prob")            -> PredicateSig(List(probabilitySort, probabilitySort)),
      SymbolName("eq")                 -> PredicateSig(List(nodeSort, nodeSort)),
      SymbolName("named")              -> PredicateSig(List(nodeSort, nodeNameLiteralSort)),
      SymbolName("has_id")             -> PredicateSig(List(nodeSort, nodeIdLiteralSort))
    ),
    literalValidators = Map(
      // Node slots resolve a quoted literal by NAME only: an id in a structural
      // node slot (`child_of(x, "01BX…")`) no longer binds — use `has_id`.
      nodeSort            -> ((s: String) => nameToId.get(s)),
      nodeNameLiteralSort -> ((s: String) => nameToId.get(s)),                // named's 2nd arg
      nodeIdLiteralSort   -> ((s: String) => NodeId.fromString(s).toOption),  // has_id's 2nd arg
      lossSort            -> ((s: String) => s.toLongOption.filter(_ >= 0L)),
      probabilitySort     -> ((s: String) => s.toDoubleOption.filter(d => d >= 0.0 && d <= 1.0))
    )
  )

  // ── RuntimeDispatcher ──────────────────────────────────────────────

  private val index: TreeIndex = tree.index

  private val leafIdSet: Set[NodeId] = index.leafIds

  private val portfolioIds: Set[NodeId] =
    index.nodes.collect { case (id, _: RiskPortfolio) => id }.toSet

  private def lookupResult(id: NodeId, ctx: String): Either[String, LossDistribution] =
    results.get(id).toRight(s"$ctx: no simulation result for node '${id.value}'")

  /** Shared node-identity relation backing `eq`, `named`, and `has_id`. By the
    * time an argument reaches the dispatcher its literal validator has already
    * resolved the quoted string to a `NodeId`, so all three reduce to `NodeId`
    * equality; they differ only at bind time, in which validator accepts the
    * literal (`nameToId.get` for `eq` / `named`, `NodeId.fromString` for
    * `has_id`). */
  private val nodeIdentity: List[Value] => Either[String, Boolean] = args =>
    for
      a <- args(0).extract[NodeId]
      b <- args(1).extract[NodeId]
    yield a == b

  val dispatcher: MapDispatcher = MapDispatcher(
    functions = Map(
      SymbolName("p95") -> { args =>
        for
          id     <- args(0).extract[NodeId]
          result <- lookupResult(id, "p95")
        yield percentile(result, 0.95)
      },
      SymbolName("p99") -> { args =>
        for
          id     <- args(0).extract[NodeId]
          result <- lookupResult(id, "p99")
        yield percentile(result, 0.99)
      },
      SymbolName("lec") -> { args =>
        for
          id        <- args(0).extract[NodeId]
          threshold <- args(1).extract[Long]
          result    <- lookupResult(id, "lec")
        yield result.probOfExceedance(threshold)
      }
    ),
    predicates = Map(
      SymbolName("leaf") -> { args =>
        args(0).extract[NodeId].map(leafIdSet.contains)
      },
      SymbolName("portfolio") -> { args =>
        args(0).extract[NodeId].map(portfolioIds.contains)
      },
      SymbolName("child_of") -> { args =>
        for
          child  <- args(0).extract[NodeId]
          parent <- args(1).extract[NodeId]
        yield index.children.getOrElse(parent, Nil).contains(child)
      },
      SymbolName("descendant_of") -> { args =>
        for
          desc     <- args(0).extract[NodeId]
          ancestor <- args(1).extract[NodeId]
        // Walk desc's parent chain upward (O(depth)) rather than building
        // ancestor's whole subtree. isAncestor is reflexive; the desc != ancestor
        // guard keeps descendant_of strict (irreflexive).
        yield desc != ancestor && index.isAncestor(ancestor, desc)
      },
      SymbolName("leaf_descendant_of") -> { args =>
        for
          desc     <- args(0).extract[NodeId]
          ancestor <- args(1).extract[NodeId]
        yield desc != ancestor && index.isAncestor(ancestor, desc) && leafIdSet.contains(desc)
      },
      SymbolName("gt_loss") -> { args =>
        for
          a <- args(0).extract[Long]
          b <- args(1).extract[Long]
        yield a > b
      },
      SymbolName("gt_prob") -> { args =>
        for
          a <- args(0).extract[Double]
          b <- args(1).extract[Double]
        yield a > b
      },
      SymbolName("eq")     -> nodeIdentity,
      SymbolName("named")  -> nodeIdentity,
      SymbolName("has_id") -> nodeIdentity
    )
  )

  // ── RuntimeModel ───────────────────────────────────────────────────

  /** Domain elements: one `Value(Node, nodeId)` per tree node. */
  private val nodeDomain: Set[Value] =
    tree.index.nodes.keys.map(id => Value(nodeSort, id)).toSet

  val model: RuntimeModel = RuntimeModel(
    domains = Map(nodeSort -> nodeDomain),
    dispatcher = dispatcher
  )

end RiskTreeKnowledgeBase

object RiskTreeKnowledgeBase:

  /** Canonical sort id for tree nodes (leaves and portfolios). Shared with
    * [[QueryResponseBuilder]] so the id projection uses one declaration. */
  val NodeSort: TypeId = TypeId("Node")

  /** Value sort for a node reference written as a quoted node NAME literal
    * (`named(x, "IT Risk")`). Carrier: `NodeId` — the name is resolved to the
    * node's id at bind time by the literal validator (`nameToId.get`). A
    * `ValueType` (ADR-014): it flows through an argument slot and is never
    * quantified over. The sort string surfaces in a failed-literal bind message
    * and is a node-reference discriminator in [[FolQueryFailure.fromQueryError]]. */
  val NodeNameLiteralSort: TypeId = TypeId("NodeNameLiteral")

  /** Value sort for a node reference written as a quoted node ID literal
    * (`has_id(x, "01BX…")`). Carrier: `NodeId` — the id string is parsed by
    * `NodeId.fromString` at bind time. A failed id literal is malformed syntax,
    * classified `BIND_FAILED` (unlike a failed name, which is a nonexistent
    * node). */
  val NodeIdLiteralSort: TypeId = TypeId("NodeIdLiteral")

  /** Consumer carrier for the node sort (ADR-015 §2): the engine holds the
    * register `NodeId` opaquely in `Value.raw`; this lifts it back out. A
    * well-formed query never hits the left case (sort correctness is proven at
    * bind time) — it signals a carrier mismatch, not a user error. */
  given Extract[NodeId] with
    def apply(v: Value): Either[String, NodeId] = v.raw match
      case id: NodeId => Right(id)
      case other      =>
        Left(s"Extract[NodeId]: expected NodeId carrier for sort '${v.sort.value}', got $other")
