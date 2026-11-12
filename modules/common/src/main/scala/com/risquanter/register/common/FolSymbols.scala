package com.risquanter.register.common

import lexer.GrammarSymbol
import sttp.tapir.Schema
import sttp.tapir.generic.Configuration
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

    /** Sorts whose unreadable literal names a node the tree does not hold: a
      * bare node slot and the `named_risk` name literal. The id-literal sort is
      * not one of them, because a malformed id is a type error and a
      * well-formed absent id binds and evaluates false. */
    val NodeReferences: Set[QuerySort] = Set(Node, NodeNameLiteral)

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
