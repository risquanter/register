package com.risquanter.register.foladapter

import com.risquanter.register.domain.data.{RiskTree, RiskPortfolio, LossDistribution, MitigationSelection, ScopeRestriction}
import com.risquanter.register.domain.data.iron.{NodeId, MitigationId}
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
  * structural predicates with `TreeIndex`, simulation functions with
  * per-selection `LossDistribution` data, and mitigation predicates with the
  * server-computed resolved scopes.
  *
  * == Sort System ==
  *
  * | Sort                  | Scala carrier  | Description |
  * |-----------------------|----------------|-------------|
  * | Node                  | `NodeId`       | Tree node identity (leaves and portfolios) |
  * | NodeNameLiteral       | `NodeId`       | A node named by a quoted name literal (`named_risk`) |
  * | NodeIdLiteral         | `NodeId`       | A node named by a quoted id literal (`risk_id`) |
  * | Mitigation            | `MitigationId` | Tree-level mitigation identity (quantifiable) |
  * | MitigationNameLiteral | `MitigationId` | A mitigation named by a quoted name literal (`named_mitigation`) |
  * | MitigationIdLiteral   | `MitigationId` | A mitigation named by a quoted id literal (`mitigation_id`) |
  * | Loss                  | Long           | Monetary loss values |
  * | Probability           | Double         | Exceedance probabilities |
  *
  * == Constants ==
  *
  * | Name     | Sort       | Denotes |
  * |----------|------------|---------|
  * | inherent | Mitigation | The mitigation-free (raw) valuation, `MitigationSelection.Inherent` |
  * | residual | Mitigation | The all-applicable valuation, `MitigationSelection.Residual` |
  *
  * == Functions ==
  *
  * | Name | Signature | Backed by |
  * |------|-----------|-----------|
  * | p95  | (Node, Mitigation) → Loss | 95th percentile of the selection's result |
  * | p99  | (Node, Mitigation) → Loss | 99th percentile of the selection's result |
  * | lec  | (Node, Loss, Mitigation) → Probability | `probOfExceedance(threshold)` of the selection's result |
  *
  * The trailing `Mitigation` slot names which valuation the function reads: the
  * `inherent`/`residual` constants, or a bound `∃m : mitigation` variable
  * denoting a single-mitigation `Selected` valuation.
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
  * | named_risk | (Node, NodeNameLiteral) | pins a node by name; `NodeId` equality |
  * | risk_id | (Node, NodeIdLiteral) | pins a node by id; `NodeId` equality |
  * | named_mitigation | (Mitigation, MitigationNameLiteral) | pins a mitigation by name; `MitigationId` equality |
  * | mitigation_id | (Mitigation, MitigationIdLiteral) | pins a mitigation by id; `MitigationId` equality |
  * | mitigate | (Node, Mitigation) | node is in the mitigation's resolved scope |
  * | mitigated | (Node) | node is in the union of all resolved scopes |
  * | unmitigated | (Node) | node is in no resolved scope (complement of `mitigated`) |
  *
  * @param tree               Risk tree providing structure (TreeIndex), node metadata, and mitigations
  * @param resultsBySelection Simulation results per referenced `MitigationSelection` (from `CachedResultResolver.ensureCachedAll`); an empty map is valid when the query uses no value function
  * @param resolvedScopes     Per-mitigation resolved node scopes (`ResolvedScopes.appliedScopes`; Failed outcomes already excluded)
  */
class RiskTreeKnowledgeBase(
  tree:               RiskTree,
  resultsBySelection: Map[MitigationSelection, Map[NodeId, LossDistribution]],
  resolvedScopes:     Map[MitigationId, Set[NodeId]]
):

  import RiskTreeKnowledgeBase.given

  // ── Sort declarations ──────────────────────────────────────────────

  val nodeSort: TypeId                  = RiskTreeKnowledgeBase.NodeSort
  val lossSort: TypeId                  = TypeId("Loss")
  val probabilitySort: TypeId           = TypeId("Probability")
  val nodeNameLiteralSort: TypeId       = RiskTreeKnowledgeBase.NodeNameLiteralSort
  val nodeIdLiteralSort: TypeId         = RiskTreeKnowledgeBase.NodeIdLiteralSort
  val mitigationSort: TypeId            = RiskTreeKnowledgeBase.MitigationSort
  val mitigationNameLiteralSort: TypeId = RiskTreeKnowledgeBase.MitigationNameLiteralSort
  val mitigationIdLiteralSort: TypeId   = RiskTreeKnowledgeBase.MitigationIdLiteralSort

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

  /** Names that, if used as a node or mitigation name, would shadow this
    * catalog's own function, predicate, or constant symbols. Functions and
    * predicates are written bare and parse through the application arm
    * (`p95(x, "inherent")`, `leaf(x)`); constants are reached only through the
    * quoted-literal arm (`"inherent"` → `Const("inherent")`), because a bare
    * word that is not numeric or `nil` parses as a variable, not a constant —
    * the same quoted-literal path a node- or mitigation-name literal takes. A
    * node or mitigation named after a symbol therefore collides with it on
    * that path; allowing it would silently bind the entity with that name and
    * produce surprising behaviour.
    *
    * This is an **alarm-on-bypass** safety net: the supported flow is for the DTO
    * validators to reject such names at create-tree time. If a tree carrying
    * a colliding name reaches the KB anyway (direct repo write, migration,
    * Irmin merge), we exclude the entry from [[nameToId]] / [[mitigationNameToId]]
    * and surface it via [[nameCollisions]] / [[mitigationNameCollisions]] for the
    * orchestrating service to log.
    *
    * The set is the union of this catalog's own function, predicate, and constant
    * symbol names.
    */
  val reservedFolNames: Set[String] = FolSymbols.reservedNames

  /** Diagnostic record of node names skipped because they collide with a
    * reserved catalog symbol when building [[nameToId]]. Empty in the supported
    * flow — the DTO validators (`requireUniqueNames`, `requireNoReservedNames`)
    * gate at create-tree time. Duplicate names cannot reach the KB at all:
    * `RiskTree.fromNodes` enforces node-name uniqueness on every construction
    * path (requests, merges, store-loads), so only reserved-symbol collisions
    * remain possible here.
    *
    * Surfaced for the orchestrating service (e.g. `QueryServiceLive`) to log
    * via `ZIO.logWarning` so any DTO-bypass path is observable.
    */
  val nameCollisions: List[String] =
    tree.index.nodes.values.map(_.name.value).toList
      .filter(reservedFolNames.contains).distinct.sorted
      .map(n => s"reserved:$n")

  /** Node name → `NodeId`, backing the name branch of the node-sort literal
    * validator so a quoted node name (`child_of(x, "IT Risk")`) resolves to a
    * node id. Reserved-symbol names are excluded (see [[reservedFolNames]] /
    * [[nameCollisions]]); every remaining name maps to exactly one node, because
    * `RiskTree.fromNodes` enforces node-name uniqueness. The validator returns
    * a `NodeId`, never a raw string, so the engine carries node identity, not
    * a name.
    */
  val nameToId: Map[String, NodeId] =
    tree.index.nodes.iterator.collect {
      case (id, node) if !reservedFolNames.contains(node.name.value) => node.name.value -> id
    }.toMap

  /** Mitigation name → `MitigationId`, backing the mitigation-name literal
    * validator (`named_mitigation(m, "IT Risk mitigation")`). Reserved-symbol
    * names are excluded and surfaced via [[mitigationNameCollisions]], exactly
    * mirroring the node `nameToId` / `nameCollisions` pair. */
  val mitigationNameToId: Map[String, MitigationId] =
    tree.mitigations.iterator.collect {
      case m if !reservedFolNames.contains(m.name.value) => m.name.value -> m.id
    }.toMap

  /** Mitigation names skipped because they collide with a reserved catalog
    * symbol/constant. Empty in the supported flow; surfaced for the
    * orchestrating service to log, exactly like [[nameCollisions]] for node
    * names. */
  val mitigationNameCollisions: List[String] =
    tree.mitigations.map(_.name.value).toList
      .filter(reservedFolNames.contains).distinct.sorted
      .map(n => s"reserved-mitigation:$n")

  val catalog: TypeCatalog = TypeCatalog.unsafe(
    types = Set(
      TypeDecl.DomainType(nodeSort),
      TypeDecl.DomainType(mitigationSort),
      TypeDecl.ValueType(lossSort),
      TypeDecl.ValueType(probabilitySort),
      TypeDecl.ValueType(nodeNameLiteralSort),
      TypeDecl.ValueType(nodeIdLiteralSort),
      TypeDecl.ValueType(mitigationNameLiteralSort),
      TypeDecl.ValueType(mitigationIdLiteralSort)
    ),
    // The two aggregate-valuation constants; a mitigation-sort constant is legal
    // (only functions returning a domain sort are rejected). They resolve by name
    // separately from the ∃-domain enumeration, so they are not members of
    // `∃m : mitigation`. Node/mitigation names resolve on demand through the
    // literal validators below, not as pre-registered constants.
    constants = Map(
      RiskTreeKnowledgeBase.InherentConst -> mitigationSort,
      RiskTreeKnowledgeBase.ResidualConst -> mitigationSort
    ),
    functions = Map(
      SymbolName("p95") -> FunctionSig(List(nodeSort, mitigationSort), lossSort),
      SymbolName("p99") -> FunctionSig(List(nodeSort, mitigationSort), lossSort),
      SymbolName("lec") -> FunctionSig(List(nodeSort, lossSort, mitigationSort), probabilitySort)
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
      SymbolName("named_risk")         -> PredicateSig(List(nodeSort, nodeNameLiteralSort)),
      SymbolName("risk_id")            -> PredicateSig(List(nodeSort, nodeIdLiteralSort)),
      SymbolName("named_mitigation")   -> PredicateSig(List(mitigationSort, mitigationNameLiteralSort)),
      SymbolName("mitigation_id")      -> PredicateSig(List(mitigationSort, mitigationIdLiteralSort)),
      SymbolName("mitigate")           -> PredicateSig(List(nodeSort, mitigationSort)),
      SymbolName("mitigated")          -> PredicateSig(List(nodeSort)),
      SymbolName("unmitigated")        -> PredicateSig(List(nodeSort))
    ),
    literalValidators = Map(
      // Node slots resolve a quoted literal by NAME only: an id in a structural
      // node slot (`child_of(x, "01BX…")`) does not bind — use `risk_id`.
      nodeSort                  -> ((s: String) => nameToId.get(s)),
      nodeNameLiteralSort       -> ((s: String) => nameToId.get(s)),                     // named_risk's 2nd arg
      nodeIdLiteralSort         -> ((s: String) => NodeId.fromString(s).toOption),       // risk_id's 2nd arg
      lossSort                  -> ((s: String) => s.toLongOption.filter(_ >= 0L)),
      probabilitySort           -> ((s: String) => s.toDoubleOption.filter(d => d >= 0.0 && d <= 1.0)),
      mitigationNameLiteralSort -> ((s: String) => mitigationNameToId.get(s)),           // named_mitigation's 2nd arg
      mitigationIdLiteralSort   -> ((s: String) => MitigationId.fromString(s).toOption)  // mitigation_id's 2nd arg
      // No validator for mitigationSort itself: the value functions' selection
      // slot accepts only the two constants or a bound variable — a bare quoted
      // literal there deliberately fails to bind.
    )
  )

  // ── RuntimeDispatcher ──────────────────────────────────────────────

  private val index: TreeIndex = tree.index

  private val leafIdSet: Set[NodeId] = index.leafIds

  private val portfolioIds: Set[NodeId] =
    index.nodes.collect { case (id, _: RiskPortfolio) => id }.toSet

  /** Maps a mitigation-sort argument `Value` to the selection it denotes. The
    * `inherent`/`residual` constants arrive as their own name string
    * (`ConstRef` → `Value(sort, name)`); a bound `∃m` carries a `MitigationId`
    * drawn from the runtime domain, which maps to a single-mitigation
    * `Selected`. */
  private def selectionOf(v: Value): Either[String, MitigationSelection] = v.raw match
    case RiskTreeKnowledgeBase.InherentConst => Right(MitigationSelection.Inherent)
    case RiskTreeKnowledgeBase.ResidualConst => Right(MitigationSelection.Residual)
    case id: MitigationId                    =>
      Right(MitigationSelection.Selected(Map(id -> ScopeRestriction.FullScope)))
    case other =>
      Left(s"mitigation selection: unrecognised carrier '$other' in sort '${v.sort.value}'")

  /** The precomputed result map for a selection. Absent only if
    * `referencedSelections` failed to enumerate the selection a bound term
    * denotes — a wiring invariant, surfaced as a dispatcher error. */
  private def resultsFor(sel: MitigationSelection): Either[String, Map[NodeId, LossDistribution]] =
    resultsBySelection.get(sel).toRight(
      s"no precomputed results for selection '$sel' (referencedSelections omitted it)"
    )

  private def lookupResult(rs: Map[NodeId, LossDistribution], id: NodeId, ctx: String): Either[String, LossDistribution] =
    rs.get(id).toRight(s"$ctx: no simulation result for node '${id.value}'")

  /** Union of every resolved mitigation scope. `mitigated(x)` reads this set and
    * `unmitigated(x)` its complement, so `unmitigated ≡ ¬mitigated` holds by
    * construction (M3-D5). Failed outcomes are already excluded upstream
    * (`ResolvedScopes.appliedScopes`, M3-D2). */
  private val mitigatedIds: Set[NodeId] =
    resolvedScopes.valuesIterator.foldLeft(Set.empty[NodeId])(_ union _)

  /** Shared node-identity relation backing `eq`, `named_risk`, and `risk_id`. By
    * the time an argument reaches the dispatcher its literal validator has
    * already resolved the quoted string to a `NodeId`, so all three reduce to
    * `NodeId` equality; they differ only at bind time, in which validator accepts
    * the literal (`nameToId.get` for `eq` / `named_risk`, `NodeId.fromString` for
    * `risk_id`). */
  private val nodeIdentity: List[Value] => Either[String, Boolean] = args =>
    for
      a <- args(0).extract[NodeId]
      b <- args(1).extract[NodeId]
    yield a == b

  /** Shared mitigation-identity relation backing `named_mitigation` and
    * `mitigation_id`; both reduce to `MitigationId` equality after their literal
    * validators resolve the quoted string to a `MitigationId`. */
  private val mitigationIdentity: List[Value] => Either[String, Boolean] = args =>
    for
      a <- args(0).extract[MitigationId]
      b <- args(1).extract[MitigationId]
    yield a == b

  val dispatcher: MapDispatcher = MapDispatcher(
    functions = Map(
      SymbolName("p95") -> { args =>
        for
          id  <- args(0).extract[NodeId]
          sel <- selectionOf(args(1))
          rs  <- resultsFor(sel)
          r   <- lookupResult(rs, id, "p95")
        yield percentile(r, 0.95)
      },
      SymbolName("p99") -> { args =>
        for
          id  <- args(0).extract[NodeId]
          sel <- selectionOf(args(1))
          rs  <- resultsFor(sel)
          r   <- lookupResult(rs, id, "p99")
        yield percentile(r, 0.99)
      },
      SymbolName("lec") -> { args =>
        for
          id        <- args(0).extract[NodeId]
          threshold <- args(1).extract[Long]
          sel       <- selectionOf(args(2))
          rs        <- resultsFor(sel)
          r         <- lookupResult(rs, id, "lec")
        yield r.probOfExceedance(threshold)
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
      SymbolName("eq")               -> nodeIdentity,
      SymbolName("named_risk")       -> nodeIdentity,
      SymbolName("risk_id")          -> nodeIdentity,
      SymbolName("named_mitigation") -> mitigationIdentity,
      SymbolName("mitigation_id")    -> mitigationIdentity,
      SymbolName("mitigate") -> { args =>
        for
          node <- args(0).extract[NodeId]
          mid  <- args(1).extract[MitigationId]
        yield resolvedScopes.getOrElse(mid, Set.empty).contains(node)
      },
      SymbolName("mitigated")   -> { args => args(0).extract[NodeId].map(mitigatedIds.contains) },
      SymbolName("unmitigated") -> { args => args(0).extract[NodeId].map(id => !mitigatedIds.contains(id)) }
    )
  )

  // ── RuntimeModel ───────────────────────────────────────────────────

  /** Domain elements: one `Value(Node, nodeId)` per tree node. */
  private val nodeDomain: Set[Value] =
    tree.index.nodes.keys.map(id => Value(nodeSort, id)).toSet

  /** Domain elements: one `Value(Mitigation, mitigationId)` per tree mitigation.
    * This is what `∃m : mitigation …` ranges over; the `inherent`/`residual`
    * constants are resolved separately and are not members. */
  private val mitigationDomain: Set[Value] =
    tree.mitigations.iterator.map(m => Value(mitigationSort, m.id)).toSet

  val model: RuntimeModel = RuntimeModel(
    domains = Map(nodeSort -> nodeDomain, mitigationSort -> mitigationDomain),
    dispatcher = dispatcher
  )

end RiskTreeKnowledgeBase

object RiskTreeKnowledgeBase:

  /** Canonical sort id for tree nodes (leaves and portfolios). Shared with
    * [[QueryResponseBuilder]] so the id projection uses one declaration. */
  val NodeSort: TypeId = TypeId("Node")

  /** Value sort for a node reference written as a quoted node NAME literal
    * (`named_risk(x, "IT Risk")`). Carrier: `NodeId` — the name is resolved to
    * the node's id at bind time by the literal validator (`nameToId.get`). A
    * `ValueType`: it flows through an argument slot and is never quantified over.
    * The sort string surfaces in a failed-literal bind message and is a
    * node-reference discriminator in [[FolQueryFailure.fromQueryError]]. */
  val NodeNameLiteralSort: TypeId = TypeId("NodeNameLiteral")

  /** Value sort for a node reference written as a quoted node ID literal
    * (`risk_id(x, "01BX…")`). Carrier: `NodeId` — the id string is parsed by
    * `NodeId.fromString` at bind time. A failed id literal is malformed syntax,
    * classified `BIND_FAILED` (unlike a failed name, which is a nonexistent
    * node). */
  val NodeIdLiteralSort: TypeId = TypeId("NodeIdLiteral")

  /** Domain sort for tree-level mitigations. Carrier: `MitigationId`. A
    * `DomainType` so `∃m : mitigation …` is well-typed and the runtime domain
    * enumerates the tree's mitigations. */
  val MitigationSort: TypeId = TypeId("Mitigation")

  /** Value sort for a mitigation reference written as a quoted NAME literal
    * (`named_mitigation(m, "IT Risk")`). Carrier: `MitigationId`, resolved at
    * bind time by the name→id validator. */
  val MitigationNameLiteralSort: TypeId = TypeId("MitigationNameLiteral")

  /** Value sort for a mitigation reference written as a quoted ID literal
    * (`mitigation_id(m, "01H…")`). Carrier: `MitigationId` via `fromString`. */
  val MitigationIdLiteralSort: TypeId = TypeId("MitigationIdLiteral")

  /** The two aggregate-valuation constants. Mitigation-sort so they sit in the
    * value functions' selection slot; reserved names (see `FolSymbols`) because
    * a constant of this sort used in any other slot binds as a type error. */
  val InherentConst: String = "inherent"
  val ResidualConst: String = "residual"

  /** Consumer carrier for the node sort (ADR-015 §2): the engine holds the
    * register `NodeId` opaquely in `Value.raw`; this lifts it back out. A
    * well-formed query never hits the left case (sort correctness is proven at
    * bind time) — it signals a carrier mismatch, not a user error. */
  given Extract[NodeId] with
    def apply(v: Value): Either[String, NodeId] = v.raw match
      case id: NodeId => Right(id)
      case other      =>
        Left(s"Extract[NodeId]: expected NodeId carrier for sort '${v.sort.value}', got $other")

  /** Consumer carrier for the mitigation sort, mirroring [[given_Extract_NodeId]]:
    * the engine holds the register `MitigationId` opaquely in `Value.raw`. A
    * well-formed query never hits the left case. */
  given Extract[MitigationId] with
    def apply(v: Value): Either[String, MitigationId] = v.raw match
      case id: MitigationId => Right(id)
      case other            =>
        Left(s"Extract[MitigationId]: expected MitigationId carrier for sort '${v.sort.value}', got $other")
