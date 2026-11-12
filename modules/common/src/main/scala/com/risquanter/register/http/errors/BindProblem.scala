package com.risquanter.register.http.errors

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.GreaterEqual
import sttp.tapir.Schema
import sttp.tapir.generic.Configuration
import zio.json.{DeriveJsonCodec, JsonCodec, jsonDiscriminator}
import lexer.{LiteralText, SymbolText}
import vql.error.TypeCheckError
import vql.id.TypeId

import com.risquanter.register.common.FolSymbols.{QueryGrammarSymbol, QuerySort}
import com.risquanter.register.domain.data.iron.{NonNegativeInt, QueryDecimal, QueryLiteral, QueryWord, given}
import com.risquanter.register.http.codecs.IronTapirCodecs.given

/** The name of a predicate or function: a word, a decimal, or an operator.
  * The engine's `lexer.SymbolText`. The engine reads `x < 5` as the predicate
  * `<` and `x + 1` as the function `+`.
  */
@jsonDiscriminator(BindProblem.TagField)
enum QuerySymbolName:
  case Word(text: QueryWord.QueryWord)
  case Decimal(text: QueryDecimal.QueryDecimal)
  case Operator(symbol: QueryGrammarSymbol)

object QuerySymbolName:
  given codec: JsonCodec[QuerySymbolName] = DeriveJsonCodec.gen[QuerySymbolName]

  given schema: Schema[QuerySymbolName] =
    given Configuration = Configuration.default.withDiscriminator(BindProblem.TagField)
    Schema.derived[QuerySymbolName]

  /** The name as it is written in the query. */
  private[errors] def text(name: QuerySymbolName): String = name match
    case Word(text)       => text.value
    case Decimal(text)    => text.value
    case Operator(symbol) => symbol.text

  private[errors] def fromEngine(name: SymbolText): Either[String, QuerySymbolName] = name match
    case SymbolText.Word(text)       => QueryWord.fromString(text.value).map(Word(_))
    case SymbolText.Decimal(text)    => QueryDecimal.fromString(text.value).map(Decimal(_))
    case SymbolText.Operator(symbol) => Right(Operator(QueryGrammarSymbol.fromEngine(symbol)))

/** Which kind of catalog symbol a bind problem names. The engine's
  * `vql.error.SymbolKind`. */
@jsonDiscriminator(BindProblem.TagField)
enum SymbolKind:
  case Predicate
  case Function

object SymbolKind:
  given codec: JsonCodec[SymbolKind] = DeriveJsonCodec.gen[SymbolKind]

  given schema: Schema[SymbolKind] =
    given Configuration = Configuration.default.withDiscriminator(BindProblem.TagField)
    Schema.derived[SymbolKind]

/** The term whose sort did not match. The engine's `vql.error.MismatchSite`. */
@jsonDiscriminator(BindProblem.TagField)
enum MismatchSite:
  case Variable(name: QueryVariableName)
  case Constant(name: QueryLiteral.QueryLiteral)
  case FunctionReturn(name: QuerySymbolName)

object MismatchSite:
  given codec: JsonCodec[MismatchSite] = DeriveJsonCodec.gen[MismatchSite]

  given schema: Schema[MismatchSite] =
    given Configuration = Configuration.default.withDiscriminator(BindProblem.TagField)
    Schema.derived[MismatchSite]

/** One reason the engine's binder refused a parsed query, with the values the
  * sentence is built from. The engine's `TypeCheckError`, refined. The two
  * cases a yes-or-no query over a validated model cannot reach are defects and
  * have no case here.
  */
@jsonDiscriminator(BindProblem.TagField)
enum BindProblem:
  case UnknownPredicate(name: QuerySymbolName)
  case UnknownFunction(name: QuerySymbolName)
  case ArityMismatch(symbol: QuerySymbolName, symbolKind: SymbolKind, expected: NonNegativeInt, actual: NonNegativeInt)

  /** A quoted literal that is neither a constant nor readable as any sort. */
  case UnknownConstant(text: QueryLiteral.QueryLiteral)

  /** A literal the sort it stands in cannot read. */
  case UnreadableLiteral(text: QueryLiteral.QueryLiteral, sort: QuerySort)
  case SortMismatch(site: MismatchSite, expected: QuerySort, actual: QuerySort)
  case ConflictingSorts(variable: QueryVariableName, left: QuerySort, right: QuerySort)

  /** A quantified variable no predicate gives a sort. */
  case UnconstrainedVariable(variable: QueryVariableName)

  /** A quantifier over a sort that is a value rather than a set of things. */
  case NotQuantifiable(sort: QuerySort)

object BindProblem:
  /** The key naming which case a payload states, read by the decoder and
    * published in the schema. Shared by the enums in this file. */
  final val TagField = "kind"

  given codec: JsonCodec[BindProblem] = DeriveJsonCodec.gen[BindProblem]

  given schema: Schema[BindProblem] =
    given Configuration = Configuration.default.withDiscriminator(TagField)
    Schema.derived[BindProblem]

  /** The engine's bind error as this service's value, or the reason it is a
    * defect. A reason names the engine case and, where it has one, the
    * variable, which the lexer bounds.
    *
    * {{{
    * fromTypeCheckError(TypeCheckError.TypeNotQuantifiable(TypeId("Loss")))
    * // Right(NotQuantifiable(QuerySort.Loss))
    * fromTypeCheckError(TypeCheckError.UnboundAnswerVar(VariableText.of("y")))
    * // Left("an answer variable reached the binder: y")
    * }}}
    */
  def fromTypeCheckError(error: TypeCheckError): Either[String, BindProblem] = error match
    case TypeCheckError.UnknownPredicate(name) =>
      QuerySymbolName.fromEngine(name).map(UnknownPredicate(_))
    case TypeCheckError.UnknownFunction(name) =>
      QuerySymbolName.fromEngine(name).map(UnknownFunction(_))
    case TypeCheckError.ArityMismatch(symbol, kind, expected, actual) =>
      for
        name     <- QuerySymbolName.fromEngine(symbol)
        expected <- count(expected, "ArityMismatch")
        actual   <- count(actual, "ArityMismatch")
      yield ArityMismatch(name, symbolKind(kind), expected, actual)
    case TypeCheckError.UnknownConstantOrLiteral(name) =>
      literal(name).map(UnknownConstant(_))
    case TypeCheckError.TypeMismatch(expected, actual, site) =>
      for
        site     <- mismatchSite(site)
        expected <- sort(expected, "TypeMismatch")
        actual   <- sort(actual, "TypeMismatch")
      yield SortMismatch(site, expected, actual)
    case TypeCheckError.UnboundAnswerVar(name) =>
      Left(s"an answer variable reached the binder: ${name.value}")
    case TypeCheckError.UnconstrainedVar(name) =>
      QueryVariableName.fromEngine(name).map(UnconstrainedVariable(_))
    case TypeCheckError.ConflictingTypes(name, left, right) =>
      for
        variable <- QueryVariableName.fromEngine(name)
        left     <- sort(left, "ConflictingTypes")
        right    <- sort(right, "ConflictingTypes")
      yield ConflictingSorts(variable, left, right)
    case TypeCheckError.UnparseableConstant(_, sortId, sourceText) =>
      for
        text <- literal(sourceText)
        sort <- sort(sortId, "UnparseableConstant")
      yield UnreadableLiteral(text, sort)
    case TypeCheckError.TypeNotQuantifiable(sortId) =>
      sort(sortId, "TypeNotQuantifiable").map(NotQuantifiable(_))
    case TypeCheckError.UnexpectedFreeVar(name) =>
      Left(s"a targeting predicate bound a second free variable: ${name.value}")

  /** Whether the problem names something the tree does not hold: an unknown
    * constant, or a literal a node-reference sort cannot read. */
  def namesAbsentNode(problem: BindProblem): Boolean = problem match
    case UnknownConstant(_)            => true
    case UnreadableLiteral(_, sort)    => QuerySort.NodeReferences.contains(sort)
    case UnknownPredicate(_) | UnknownFunction(_) | ArityMismatch(_, _, _, _) | SortMismatch(_, _, _) |
         ConflictingSorts(_, _, _) | UnconstrainedVariable(_) | NotQuantifiable(_) => false

  /** The sentence for one bind problem.
    *
    * {{{
    * describe(UnreadableLiteral(QueryLiteral("Cyber"), QuerySort.NodeNameLiteral))
    * // No node is named "Cyber" in this tree
    * }}}
    */
  def describe(problem: BindProblem): String = problem match
    case UnknownPredicate(name) =>
      s"The query uses ${quoted(QuerySymbolName.text(name))} as a predicate, and no predicate has that name"
    case UnknownFunction(name) =>
      s"The query uses ${quoted(QuerySymbolName.text(name))} as a function, and no function has that name"
    case ArityMismatch(symbol, symbolKind, expected, actual) =>
      s"The ${kindWord(symbolKind)} ${quoted(QuerySymbolName.text(symbol))} takes $expected ${argumentWord(expected)}, and the query gives it $actual"
    case UnknownConstant(text) =>
      s"Nothing in this tree is named ${doubleQuoted(text.value)}"
    case UnreadableLiteral(text, sort) if QuerySort.NodeReferences.contains(sort) =>
      s"No node is named ${doubleQuoted(text.value)} in this tree"
    case UnreadableLiteral(text, sort) =>
      s"${doubleQuoted(text.value)} cannot be read as ${sortWord(sort)}"
    case SortMismatch(site, expected, actual) =>
      s"${siteWords(site)} is ${sortWord(actual)} where ${sortWord(expected)} is expected"
    case ConflictingSorts(variable, left, right) =>
      s"The variable ${quoted(QueryVariableName.text(variable))} is used both as ${sortWord(left)} and as ${sortWord(right)}"
    case UnconstrainedVariable(variable) =>
      s"Nothing in the query says what the variable ${quoted(QueryVariableName.text(variable))} ranges over"
    case NotQuantifiable(sort) =>
      s"A query cannot range over ${sortWord(sort)}, because it is a value rather than a set of things"

  /** The sort's name in a sentence. */
  private def sortWord(sort: QuerySort): String = sort match
    case QuerySort.Node                  => "a node"
    case QuerySort.NodeNameLiteral       => "a node name"
    case QuerySort.NodeIdLiteral         => "a node id"
    case QuerySort.Mitigation            => "a mitigation"
    case QuerySort.MitigationNameLiteral => "a mitigation name"
    case QuerySort.MitigationIdLiteral   => "a mitigation id"
    case QuerySort.Loss                  => "a loss"
    case QuerySort.Probability           => "a probability"

  private def siteWords(site: MismatchSite): String = site match
    case MismatchSite.Variable(name)       => s"The variable ${quoted(QueryVariableName.text(name))}"
    case MismatchSite.Constant(name)       => s"The constant ${doubleQuoted(name.value)}"
    case MismatchSite.FunctionReturn(name) => s"What the function ${quoted(QuerySymbolName.text(name))} returns"

  private def kindWord(kind: SymbolKind): String = kind match
    case SymbolKind.Predicate => "predicate"
    case SymbolKind.Function  => "function"

  private def argumentWord(count: NonNegativeInt): String =
    if (count: Int) == 1 then "argument" else "arguments"

  private def quoted(text: String): String = s"'$text'"
  private def doubleQuoted(text: String): String = "\"" + text + "\""

  private def symbolKind(kind: vql.error.SymbolKind): SymbolKind = kind match
    case vql.error.SymbolKind.Predicate => SymbolKind.Predicate
    case vql.error.SymbolKind.Function  => SymbolKind.Function

  private def count(n: Int, engineCase: String): Either[String, NonNegativeInt] =
    n.refineEither[GreaterEqual[0]].left.map(_ => s"$engineCase carries a negative count")

  private def sort(id: TypeId, engineCase: String): Either[String, QuerySort] =
    QuerySort.fromSymbol(id.value).toRight(s"$engineCase names a sort the catalog does not declare")

  private def literal(text: LiteralText): Either[String, QueryLiteral.QueryLiteral] =
    QueryLiteral.fromString(text.value)

  private def mismatchSite(site: vql.error.MismatchSite): Either[String, MismatchSite] = site match
    case vql.error.MismatchSite.Variable(name)       => QueryVariableName.fromEngine(name).map(MismatchSite.Variable(_))
    case vql.error.MismatchSite.Constant(name)       => literal(name).map(MismatchSite.Constant(_))
    case vql.error.MismatchSite.FunctionReturn(name) => QuerySymbolName.fromEngine(name).map(MismatchSite.FunctionReturn(_))
