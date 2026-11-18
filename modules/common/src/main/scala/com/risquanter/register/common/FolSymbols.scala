package com.risquanter.register.common

import lexer.GrammarSymbol
import sttp.tapir.Schema
import sttp.tapir.generic.Configuration
import vql.typed.{FunctionSig, PredicateSig, SymbolName, TypeCatalog, TypeDecl, TypeId}
import zio.json.{DeriveJsonCodec, JsonCodec, jsonDiscriminator}

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

  /** The query language's node-returning functions: each names one node by a
    * literal, and may stand in any position that takes a node.
    *
    * `symbol` is the name the query engine binds, so it is declared rather than
    * derived from the case name (ADR-037).
    *
    * {{{
    * descendant_of(x, node_id("01HQ8ZK3…"))
    * eq(x, node_named("Ransomware Attack"))
    * }}}
    */
  enum NodeFunction(val symbol: String):
    case ById   extends NodeFunction("node_id")
    case ByName extends NodeFunction("node_named")

    /** The name the engine's catalog declares for this symbol. */
    def symbolName: SymbolName = SymbolName(symbol)

  /** The query language's predicates. `symbol` is the name the engine binds
    * (ADR-037). */
  enum QueryPredicate(val symbol: String):
    case Leaf             extends QueryPredicate("leaf")
    case Portfolio        extends QueryPredicate("portfolio")
    case ChildOf          extends QueryPredicate("child_of")
    case DescendantOf     extends QueryPredicate("descendant_of")
    case LeafDescendantOf extends QueryPredicate("leaf_descendant_of")
    case GtLoss           extends QueryPredicate("gt_loss")
    case GtProb           extends QueryPredicate("gt_prob")
    case Eq               extends QueryPredicate("eq")
    case MitigationNamed  extends QueryPredicate("mitigation_named")
    case MitigationIdOf   extends QueryPredicate("mitigation_id")
    case Mitigate         extends QueryPredicate("mitigate")
    case Mitigated        extends QueryPredicate("mitigated")
    case Unmitigated      extends QueryPredicate("unmitigated")

    /** The name the engine's catalog declares for this symbol. */
    def symbolName: SymbolName = SymbolName(symbol)

    /** Whether a targeting predicate may name this predicate. A mitigation
      * must not target by the state it changes, so the three mitigation-state
      * predicates are refused. Every case is listed, so a new predicate is
      * classified where it is declared. */
    def admittedInTarget: Boolean = this match
      case Mitigate | Mitigated | Unmitigated => false
      case Leaf | Portfolio | ChildOf | DescendantOf | LeafDescendantOf | GtLoss | GtProb | Eq |
           MitigationNamed | MitigationIdOf => true

  /** The sorts the knowledge base's catalog declares.
    *
    * `symbol` is the type name the engine binds and reports back in a bind
    * failure, so it is a contract with the engine and is declared rather than
    * derived from the case name (ADR-037). The catalog declares each sort from
    * this enum, so the two cannot drift.
    */
  @jsonDiscriminator(QuerySort.TagField)
  enum QuerySort(val symbol: String):
    case Node                  extends QuerySort("Node")
    case NodeNameLiteral       extends QuerySort("NodeNameLiteral")
    case NodeIdLiteral         extends QuerySort("NodeIdLiteral")
    case Mitigation            extends QuerySort("Mitigation")
    case MitigationNameLiteral extends QuerySort("MitigationNameLiteral")
    case MitigationIdLiteral   extends QuerySort("MitigationIdLiteral")
    case Loss                  extends QuerySort("Loss")
    case Probability           extends QuerySort("Probability")

    /** The type the engine's catalog declares for this sort. */
    def typeId: TypeId = TypeId(symbol)

  object QuerySort:
    /** The key naming which sort a payload states, read by the decoder and
      * published in the schema. */
    final val TagField = "kind"

    // A sort crosses the wire inside a refused query's payload. Its wire
    // spelling is the case name, which the codec derives; `symbol` is the
    // engine's contract and is not what the wire carries.
    given codec: JsonCodec[QuerySort] = DeriveJsonCodec.gen[QuerySort]

    given schema: Schema[QuerySort] =
      given Configuration = Configuration.default.withDiscriminator(TagField)
      Schema.derived[QuerySort]

    /** The sort a reported type name names, if it names one.
      *
      * {{{
      * fromSymbol("NodeNameLiteral") // Some(NodeNameLiteral)
      * fromSymbol("Asset")           // None
      * }}}
      */
    def fromSymbol(symbol: String): Option[QuerySort] =
      QuerySort.values.find(_.symbol == symbol)

    /** Sorts whose unreadable literal names a node the tree does not hold: the
      * `node_named` name literal. The id-literal sort is not one of them,
      * because a malformed id is a type error and a well-formed absent id binds
      * and evaluates false. The node sort reads no literal at all. */
    val NodeReferences: Set[QuerySort] = Set(NodeNameLiteral)

  /** The query language's punctuation and operators, as a refused query names
    * one. The engine's `lexer.GrammarSymbol`, case for case.
    *
    * `text` is the symbol's spelling in query text, a contract with the query
    * language, so it is declared rather than derived from the case name
    * (ADR-037).
    */
  @jsonDiscriminator(QueryGrammarSymbol.TagField)
  enum QueryGrammarSymbol(val text: String):
    case LParen       extends QueryGrammarSymbol("(")
    case RParen       extends QueryGrammarSymbol(")")
    case LBracket     extends QueryGrammarSymbol("[")
    case RBracket     extends QueryGrammarSymbol("]")
    case LBrace       extends QueryGrammarSymbol("{")
    case RBrace       extends QueryGrammarSymbol("}")
    case Comma        extends QueryGrammarSymbol(",")
    case Dot          extends QueryGrammarSymbol(".")
    case And          extends QueryGrammarSymbol("/\\")
    case Or           extends QueryGrammarSymbol("\\/")
    case Implies      extends QueryGrammarSymbol("==>")
    case Iff          extends QueryGrammarSymbol("<=>")
    case Not          extends QueryGrammarSymbol("~")
    case Equal        extends QueryGrammarSymbol("=")
    case Less         extends QueryGrammarSymbol("<")
    case LessEqual    extends QueryGrammarSymbol("<=")
    case Greater      extends QueryGrammarSymbol(">")
    case GreaterEqual extends QueryGrammarSymbol(">=")
    case Cons         extends QueryGrammarSymbol("::")
    case Plus         extends QueryGrammarSymbol("+")
    case Minus        extends QueryGrammarSymbol("-")
    case Times        extends QueryGrammarSymbol("*")
    case Divide       extends QueryGrammarSymbol("/")
    case Power        extends QueryGrammarSymbol("^")

  object QueryGrammarSymbol:
    /** The key naming which symbol a payload states, read by the decoder and
      * published in the schema. */
    final val TagField = "kind"

    given codec: JsonCodec[QueryGrammarSymbol] = DeriveJsonCodec.gen[QueryGrammarSymbol]

    given schema: Schema[QueryGrammarSymbol] =
      given Configuration = Configuration.default.withDiscriminator(TagField)
      Schema.derived[QueryGrammarSymbol]

    /** The symbol the engine reported. */
    def fromEngine(symbol: GrammarSymbol): QueryGrammarSymbol = symbol match
      case GrammarSymbol.LParen       => LParen
      case GrammarSymbol.RParen       => RParen
      case GrammarSymbol.LBracket     => LBracket
      case GrammarSymbol.RBracket     => RBracket
      case GrammarSymbol.LBrace       => LBrace
      case GrammarSymbol.RBrace       => RBrace
      case GrammarSymbol.Comma        => Comma
      case GrammarSymbol.Dot          => Dot
      case GrammarSymbol.And          => And
      case GrammarSymbol.Or           => Or
      case GrammarSymbol.Implies      => Implies
      case GrammarSymbol.Iff          => Iff
      case GrammarSymbol.Not          => Not
      case GrammarSymbol.Equal        => Equal
      case GrammarSymbol.Less         => Less
      case GrammarSymbol.LessEqual    => LessEqual
      case GrammarSymbol.Greater      => Greater
      case GrammarSymbol.GreaterEqual => GreaterEqual
      case GrammarSymbol.Cons         => Cons
      case GrammarSymbol.Plus         => Plus
      case GrammarSymbol.Minus        => Minus
      case GrammarSymbol.Times        => Times
      case GrammarSymbol.Divide       => Divide
      case GrammarSymbol.Power        => Power

  /** The tree-independent half of the knowledge base's catalog: every sort,
    * constant, function and predicate signature. The server adds the literal
    * validators, which read the tree.
    */
  object Signatures:
    private val node: TypeId            = QuerySort.Node.typeId
    private val nodeNameLiteral: TypeId = QuerySort.NodeNameLiteral.typeId
    private val nodeIdLiteral: TypeId   = QuerySort.NodeIdLiteral.typeId
    private val mitigation: TypeId      = QuerySort.Mitigation.typeId
    private val loss: TypeId            = QuerySort.Loss.typeId
    private val probability: TypeId     = QuerySort.Probability.typeId

    /** Node and mitigation are domain sorts, so a query may quantify over
      * them. Every other sort is a value sort that only flows through an
      * argument position. */
    val types: Set[TypeDecl] = QuerySort.values.toSet.map(sort =>
      sort match
        case QuerySort.Node | QuerySort.Mitigation => TypeDecl.DomainType(sort.typeId)
        case QuerySort.NodeNameLiteral | QuerySort.NodeIdLiteral | QuerySort.MitigationNameLiteral |
             QuerySort.MitigationIdLiteral | QuerySort.Loss | QuerySort.Probability =>
          TypeDecl.ValueType(sort.typeId))

    /** The two valuation bases, which are mitigation-sort constants. They
      * resolve by name and are not members of `∃m : mitigation`. */
    val constants: Map[String, TypeId] = ValuationBasis.values.map(_.symbol -> mitigation).toMap

    val functions: Map[SymbolName, FunctionSig] = Map(
      SymbolName(ValueFunction.P95.symbol)   -> FunctionSig(List(node, mitigation), loss),
      SymbolName(ValueFunction.P99.symbol)   -> FunctionSig(List(node, mitigation), loss),
      SymbolName(ValueFunction.Lec.symbol)   -> FunctionSig(List(node, loss, mitigation), probability),
      NodeFunction.ById.symbolName           -> FunctionSig(List(nodeIdLiteral), node),
      NodeFunction.ByName.symbolName         -> FunctionSig(List(nodeNameLiteral), node)
    )

    val predicates: Map[SymbolName, PredicateSig] =
      QueryPredicate.values.map(p => p.symbolName -> signature(p)).toMap

    private def signature(predicate: QueryPredicate): PredicateSig = predicate match
      case QueryPredicate.Leaf | QueryPredicate.Portfolio | QueryPredicate.Mitigated |
           QueryPredicate.Unmitigated => PredicateSig(List(node))
      case QueryPredicate.ChildOf | QueryPredicate.DescendantOf | QueryPredicate.LeafDescendantOf |
           QueryPredicate.Eq => PredicateSig(List(node, node))
      case QueryPredicate.GtLoss          => PredicateSig(List(loss, loss))
      case QueryPredicate.GtProb          => PredicateSig(List(probability, probability))
      case QueryPredicate.MitigationNamed => PredicateSig(List(mitigation, QuerySort.MitigationNameLiteral.typeId))
      case QueryPredicate.MitigationIdOf  => PredicateSig(List(mitigation, QuerySort.MitigationIdLiteral.typeId))
      case QueryPredicate.Mitigate        => PredicateSig(List(node, mitigation))

    /** The signatures as a catalog with no literal validator. It decides which
      * functions a targeting predicate admits, and binds nothing. */
    val catalog: TypeCatalog = TypeCatalog.unsafe(types, constants, functions, predicates, Map.empty)

    /** The functions a targeting predicate admits: those returning a node,
      * except `node_named`, so a rename cannot move a scope onto another node.
      *
      * {{{
      * targetingFunctions  // Set(SymbolName("node_id"))
      * }}}
      */
    val targetingFunctions: Set[SymbolName] =
      catalog.domainReturningFunctions - NodeFunction.ByName.symbolName

    /** The predicates a targeting predicate may not name.
      *
      * {{{
      * targetingForbiddenPredicates  // Set(SymbolName("mitigate"), SymbolName("mitigated"), SymbolName("unmitigated"))
      * }}}
      */
    val targetingForbiddenPredicates: Set[SymbolName] =
      QueryPredicate.values.filterNot(_.admittedInTarget).map(_.symbolName).toSet

  /** Every function, predicate and constant name of the catalog. */
  val reservedNames: Set[String] =
    Signatures.functions.keySet.map(_.value) ++ Signatures.predicates.keySet.map(_.value) ++
      Signatures.constants.keySet
