package com.risquanter.register.http.errors

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.GreaterEqual
import sttp.tapir.{Schema, Validator}
import sttp.tapir.generic.Configuration
import zio.json.{DeriveJsonCodec, JsonCodec, JsonDecoder, JsonEncoder, jsonDiscriminator}
import zio.prelude.NonEmptyList
import lexer.{Found, LexFailure, TokenKind, VariableText}
import parser.{SourcePosition, SyntaxFailure, ParseError as FormulaParseError}
import vql.error.{ParseRefusal, QueryError}

import com.risquanter.register.common.FolSymbols.QueryGrammarSymbol
import com.risquanter.register.domain.data.iron.{NonNegativeInt, QueryDecimal, QueryLiteral, QueryVariable, QueryWord, given}
import com.risquanter.register.http.codecs.IronTapirCodecs.given

/** Where in the submitted query text a refusal points.
  *
  * Every query refusal carries one. `Unknown` is the case for a refusal the
  * engine reports with no position, so a missing position is a value a reader
  * sees rather than an absent field. This service computes no position itself.
  */
@jsonDiscriminator(QueryPosition.TagField)
enum QueryPosition:
  /** The character offset into the query text, counted from zero. */
  case At(offset: NonNegativeInt)

  /** The engine reported no position for this refusal. */
  case Unknown()

object QueryPosition:
  /** The key naming which case a value is. Read by the decoder and published
    * in the schema, and stated here once so the two cannot disagree. The
    * other enums of a query refusal use the same key. */
  final val TagField = "kind"

  given codec: JsonCodec[QueryPosition] = DeriveJsonCodec.gen[QueryPosition]

  given schema: Schema[QueryPosition] =
    given Configuration = Configuration.default.withDiscriminator(TagField)
    Schema.derived[QueryPosition]

  /** The clause a sentence about a refused part of a query ends with.
    *
    * {{{
    * clause(At(14))    // " (at character 14)"
    * clause(Unknown()) // ""
    * }}}
    */
  def clause(position: QueryPosition): String = position match
    case At(offset) => s" (at character $offset)"
    case Unknown()  => ""

  /** The position the engine reported, or the reason it is a defect: the
    * engine counts offsets from zero. */
  def fromSource(position: SourcePosition): Either[String, QueryPosition] = position match
    case SourcePosition.At(offset) =>
      offset.refineEither[GreaterEqual[0]].map(At(_)).left.map(_ => "the engine reported a negative offset")
    case SourcePosition.Unknown    => Right(Unknown())

/** A refused part of a query beside the position it stands at, as a refusal's
  * location stands beside its problem. */
final case class Positioned[P](position: QueryPosition, problem: P)

object Positioned:
  given codec[P: JsonEncoder: JsonDecoder]: JsonCodec[Positioned[P]] = DeriveJsonCodec.gen[Positioned[P]]
  given schema[P: Schema]: Schema[Positioned[P]] = Schema.derived[Positioned[P]]

/** What a token of the query is, as a refusal names it. The engine's
  * `lexer.TokenKind`.
  *
  * Only a word, a decimal or a literal carries its text, because only those
  * have a bounded alphabet. An operator the language does not know and a
  * character outside every token class cross as their kind alone.
  */
@jsonDiscriminator(QueryPosition.TagField)
enum QueryToken:
  case Word(text: QueryWord.QueryWord)
  case Decimal(text: QueryDecimal.QueryDecimal)
  case Literal(content: QueryLiteral.QueryLiteral)
  case Symbol(symbol: QueryGrammarSymbol)
  case UnknownOperator()
  case StrayCharacter()

object QueryToken:
  given codec: JsonCodec[QueryToken] = DeriveJsonCodec.gen[QueryToken]

  given schema: Schema[QueryToken] =
    given Configuration = Configuration.default.withDiscriminator(QueryPosition.TagField)
    Schema.derived[QueryToken]

  /** The token the engine reported, refined. A `Left` is a defect: each type
    * states what the engine's lexer emits. */
  private[errors] def fromEngine(kind: TokenKind): Either[String, QueryToken] = kind match
    case TokenKind.Word(text)       => QueryWord.fromString(text.value).map(Word(_))
    case TokenKind.Decimal(text)    => QueryDecimal.fromString(text.value).map(Decimal(_))
    case TokenKind.Literal(content) => QueryLiteral.fromString(content.value).map(Literal(_))
    case TokenKind.Symbol(symbol)   => Right(Symbol(QueryGrammarSymbol.fromEngine(symbol)))
    case TokenKind.UnknownOperator  => Right(UnknownOperator())
    case TokenKind.StrayCharacter   => Right(StrayCharacter())

  /** The token as a sentence names it. */
  private[errors] def words(token: QueryToken): String = token match
    case Word(text)        => s"'${text.value}'"
    case Decimal(text)     => s"'${text.value}'"
    case Literal(content)  => "\"" + content.value + "\""
    case Symbol(symbol)    => s"'${symbol.text}'"
    case UnknownOperator() => "an operator the query language does not have"
    case StrayCharacter()  => "a character the query language does not use"

/** What a parser found where its rule needed something else. The engine's
  * `lexer.Found`. */
@jsonDiscriminator(QueryPosition.TagField)
enum QueryFound:
  case Token(token: QueryToken)
  case EndOfInput()

object QueryFound:
  given codec: JsonCodec[QueryFound] = DeriveJsonCodec.gen[QueryFound]

  given schema: Schema[QueryFound] =
    given Configuration = Configuration.default.withDiscriminator(QueryPosition.TagField)
    Schema.derived[QueryFound]

  private[errors] def fromEngine(found: Found): Either[String, QueryFound] = found match
    case Found.Token(kind) => QueryToken.fromEngine(kind).map(Token(_))
    case Found.EndOfInput  => Right(EndOfInput())

  private[errors] def words(found: QueryFound): String = found match
    case Token(token) => QueryToken.words(token)
    case EndOfInput() => "the end of the text"

/** The name of a variable in a formula: a word, or a decimal the parser
  * merged from two digit words. The engine's `lexer.VariableText`.
  *
  * Wider than `QueryVariable`, which is the rule the parser applies to a
  * quantified or answer variable. A variable bound inside a formula follows
  * only the lexer's rule (ADR-018).
  */
@jsonDiscriminator(QueryPosition.TagField)
enum QueryVariableName:
  case Word(text: QueryWord.QueryWord)
  case Decimal(text: QueryDecimal.QueryDecimal)

object QueryVariableName:
  given codec: JsonCodec[QueryVariableName] = DeriveJsonCodec.gen[QueryVariableName]

  given schema: Schema[QueryVariableName] =
    given Configuration = Configuration.default.withDiscriminator(QueryPosition.TagField)
    Schema.derived[QueryVariableName]

  /** The name as it is written in the query. */
  private[errors] def text(name: QueryVariableName): String = name match
    case Word(text)    => text.value
    case Decimal(text) => text.value

  /** The engine's variable name, refined. A `Left` is a defect: both types
    * state what the engine's lexer emits. */
  private[errors] def fromEngine(name: VariableText): Either[String, QueryVariableName] = name match
    case VariableText.Word(text)    => QueryWord.fromString(text.value).map(Word(_))
    case VariableText.Decimal(text) => QueryDecimal.fromString(text.value).map(Decimal(_))

/** The integer of a quantifier `Q[op]^{k/n}` the parser was reading. The
  * engine's `vql.error.QuantifierPart`. */
@jsonDiscriminator(QueryPosition.TagField)
enum QuantifierPart:
  case Numerator, Denominator

object QuantifierPart:
  given codec: JsonCodec[QuantifierPart] = DeriveJsonCodec.gen[QuantifierPart]

  given schema: Schema[QuantifierPart] =
    given Configuration = Configuration.default.withDiscriminator(QueryPosition.TagField)
    Schema.derived[QuantifierPart]

/** What the parser was about to read when it needed a symbol. The engine's
  * `vql.error.ParseContext`. */
@jsonDiscriminator(QueryPosition.TagField)
enum ParseContext:
  case RangeFormula, ScopeFormula, AnswerVariables, EndOfAnswerVariables, Denominator, ToleranceOrEnd

object ParseContext:
  given codec: JsonCodec[ParseContext] = DeriveJsonCodec.gen[ParseContext]

  given schema: Schema[ParseContext] =
    given Configuration = Configuration.default.withDiscriminator(QueryPosition.TagField)
    Schema.derived[ParseContext]

/** Why the formula parser could not build a formula. The engine's
  * `parser.SyntaxFailure`. */
@jsonDiscriminator(QueryPosition.TagField)
enum SyntaxProblem:
  case FormulaExpected()
  case QuantifierBodyExpected(found: QueryFound)
  case AtomExpected(found: QueryFound)
  case TermExpected(found: QueryFound)
  case ClosingBracketExpected(bracket: QueryToken, found: QueryFound)
  case NotAnInfixAtom()

object SyntaxProblem:
  given codec: JsonCodec[SyntaxProblem] = DeriveJsonCodec.gen[SyntaxProblem]

  given schema: Schema[SyntaxProblem] =
    given Configuration = Configuration.default.withDiscriminator(QueryPosition.TagField)
    Schema.derived[SyntaxProblem]

/** Why the lexer refused the text. The engine's `lexer.LexFailure`. */
@jsonDiscriminator(QueryPosition.TagField)
enum LexProblem:
  case UnterminatedLiteral, NewlineInLiteral, RefusedLiteralCharacter

object LexProblem:
  given codec: JsonCodec[LexProblem] = DeriveJsonCodec.gen[LexProblem]

  given schema: Schema[LexProblem] =
    given Configuration = Configuration.default.withDiscriminator(QueryPosition.TagField)
    Schema.derived[LexProblem]

/** Why the query's text was refused: the parser's refusals, and the range
  * formula's two variable rules. A targeting predicate's text is refused by
  * the same formula parser, so its refusal is one of these too. */
@jsonDiscriminator(QueryPosition.TagField)
enum ParseProblem:
  case QuantifierExpected(found: List[QueryToken])
  case UnknownQuantifierOperator(found: QueryToken)
  case IntegerExpected(part: QuantifierPart, found: QueryFound)
  case IntegerOutOfRange(part: QuantifierPart, digits: QueryWord.QueryWord)

  /** The threshold `k/n` is no proportion. */
  case FractionOutOfRange(numerator: NonNegativeInt, denominator: NonNegativeInt)

  /** The tolerance lies above 1. It carries no value: the parser reads only
    * non-negative digits, and a long digit run reads as infinity, which JSON
    * cannot hold. */
  case ToleranceOutOfRange()
  case VariableExpected(found: QueryFound)
  case AnswerVariableExpected(found: QueryFound)
  case SymbolExpected(expected: QueryGrammarSymbol, before: ParseContext, found: QueryFound)
  case TrailingTokens(first: QueryToken, rest: List[QueryToken])
  case Formula(failure: SyntaxProblem)
  case Lexical(failure: LexProblem)
  case QuantifiedVariableNotInRange(variable: QueryVariableName, rangeVariables: Set[QueryVariableName])
  case RangeVariablesNotAnswered(rangeVariables: Set[QueryVariableName], answerVariables: List[QueryVariableName])

object ParseProblem:
  given codec: JsonCodec[ParseProblem] = DeriveJsonCodec.gen[ParseProblem]

  given schema: Schema[ParseProblem] =
    given Configuration = Configuration.default.withDiscriminator(QueryPosition.TagField)
    Schema.derived[ParseProblem]

  /** The parser's refusal as this service's value, or the reason it is a
    * defect.
    *
    * {{{
    * fromRefusal(ParseRefusal.Lexical(LexFailure.NewlineInLiteral))
    * // Right(Lexical(LexProblem.NewlineInLiteral))
    * }}}
    */
  def fromRefusal(refusal: ParseRefusal): Either[String, ParseProblem] = refusal match
    case ParseRefusal.QuantifierExpected(found)        => tokens(found).map(QuantifierExpected(_))
    case ParseRefusal.UnknownQuantifierOperator(found) => QueryToken.fromEngine(found).map(UnknownQuantifierOperator(_))
    case ParseRefusal.IntegerExpected(part, found)     => QueryFound.fromEngine(found).map(IntegerExpected(quantifierPart(part), _))
    case ParseRefusal.IntegerOutOfRange(part, digits)  => QueryWord.fromString(digits.value).map(IntegerOutOfRange(quantifierPart(part), _))
    case ParseRefusal.FractionOutOfRange(k, n)         =>
      for
        k <- count(k, "FractionOutOfRange")
        n <- count(n, "FractionOutOfRange")
      yield FractionOutOfRange(k, n)
    case ParseRefusal.ToleranceOutOfRange(_)           => Right(ToleranceOutOfRange())
    case ParseRefusal.VariableExpected(found)          => QueryFound.fromEngine(found).map(VariableExpected(_))
    case ParseRefusal.AnswerVariableExpected(found)    => QueryFound.fromEngine(found).map(AnswerVariableExpected(_))
    case ParseRefusal.SymbolExpected(expected, before, found) =>
      QueryFound.fromEngine(found).map(SymbolExpected(QueryGrammarSymbol.fromEngine(expected), parseContext(before), _))
    case ParseRefusal.TrailingTokens(first, rest)      => trailing(first, rest)
    case ParseRefusal.Formula(failure)                 => syntax(failure).map(Formula(_))
    case ParseRefusal.Lexical(failure)                 => Right(Lexical(lex(failure)))

  /** A range formula's broken variable rule as this service's value, or the
    * reason the problem is a defect. This service evaluates every query with
    * an empty answer tuple, and refuses every answer variable before the
    * engine sees it, so the two answer-tuple problems are defects. */
  def fromValidation(problem: QueryError.ValidationProblem): Either[String, ParseProblem] = problem match
    case QueryError.ValidationProblem.QuantifiedVariableNotInRange(variable, rangeVariables) =>
      for
        variable       <- QueryVariableName.fromEngine(variable)
        rangeVariables <- traverse(rangeVariables.toList)(QueryVariableName.fromEngine)
      yield QuantifiedVariableNotInRange(variable, rangeVariables.toSet)
    case QueryError.ValidationProblem.RangeVariablesNotAnswered(rangeVariables, answerVariables) =>
      for
        rangeVariables  <- traverse(rangeVariables.toList)(QueryVariableName.fromEngine)
        answerVariables <- traverse(answerVariables)(QueryVariableName.fromEngine)
      yield RangeVariablesNotAnswered(rangeVariables.toSet, answerVariables)
    case QueryError.ValidationProblem.MissingAnswerValue(variable) =>
      Left(s"an answer tuple was checked for a query with answer variables: ${variable.value}")
    case QueryError.ValidationProblem.AnswerSortMismatch(variable, _, _) =>
      Left(s"an answer tuple was checked for a query with answer variables: ${variable.value}")

  /** The formula parser's refusal of a targeting predicate, at the position it
    * reported, or the reason it is a defect. */
  def fromFormulaError(error: FormulaParseError): Either[String, Positioned[ParseProblem]] = error match
    case FormulaParseError.Syntax(failure, position) =>
      for
        position <- QueryPosition.fromSource(position)
        failure  <- syntax(failure)
      yield Positioned(position, Formula(failure))
    case FormulaParseError.Trailing(first, rest, position) =>
      for
        position <- QueryPosition.fromSource(position)
        problem  <- trailing(first, rest)
      yield Positioned(position, problem)
    case FormulaParseError.Lex(failure, position) =>
      QueryPosition.fromSource(position).map(Positioned(_, Lexical(lex(failure))))

  /** The sentence for a refused text, ending with the position it stands at.
    *
    * {{{
    * describe(Positioned(QueryPosition.At(9), ParseProblem.Lexical(LexProblem.UnterminatedLiteral)))
    * // A quoted literal is not closed with '"' (at character 9)
    * }}}
    */
  def describe(problem: Positioned[ParseProblem]): String =
    sentence(problem.problem) + QueryPosition.clause(problem.position)

  private def sentence(problem: ParseProblem): String = problem match
    case QuantifierExpected(found) =>
      s"A query starts with a quantifier such as Q[>=]^{2/3}, and this one starts with ${tokensWords(found)}"
    case UnknownQuantifierOperator(found) =>
      s"A quantifier's operator is ~, >= or <=, and this one is ${QueryToken.words(found)}"
    case IntegerExpected(part, found) =>
      s"The quantifier's ${partWord(part)} must be a whole number, and the query has ${QueryFound.words(found)}"
    case IntegerOutOfRange(part, digits) =>
      s"The quantifier's ${partWord(part)} ${digits.value} is too large"
    case FractionOutOfRange(numerator, denominator) =>
      s"The quantifier's proportion $numerator/$denominator must lie between 0 and 1, with a denominator above 0"
    case ToleranceOutOfRange() =>
      "The quantifier's tolerance must lie between 0 and 1"
    case VariableExpected(found) =>
      s"The quantifier must name its variable next, and the query has ${QueryFound.words(found)}"
    case AnswerVariableExpected(found) =>
      s"The list of answer variables must hold variable names, and the query has ${QueryFound.words(found)}"
    case SymbolExpected(expected, before, found) =>
      s"The query needs '${expected.text}' before the ${contextWord(before)}, and has ${QueryFound.words(found)}"
    case TrailingTokens(first, rest) =>
      s"The text continues after its end: ${(first :: rest).map(QueryToken.words).mkString(" ")}"
    case Formula(failure) =>
      syntaxSentence(failure)
    case Lexical(failure) =>
      lexSentence(failure)
    case QuantifiedVariableNotInRange(variable, _) =>
      s"The quantified variable '${QueryVariableName.text(variable)}' must occur free in the range formula"
    case RangeVariablesNotAnswered(rangeVariables, answerVariables) =>
      val unanswered = (rangeVariables -- answerVariables.toSet).toList.map(QueryVariableName.text).sorted
      s"The range formula's variables ${unanswered.map(v => s"'$v'").mkString(", ")} must also be answer variables"

  private def syntaxSentence(failure: SyntaxProblem): String = failure match
    case SyntaxProblem.FormulaExpected() =>
      "A formula is expected here"
    case SyntaxProblem.QuantifierBodyExpected(found) =>
      s"A quantified formula needs a body, and the text has ${QueryFound.words(found)}"
    case SyntaxProblem.AtomExpected(found) =>
      s"A predicate is expected, and the text has ${QueryFound.words(found)}"
    case SyntaxProblem.TermExpected(found) =>
      s"A term is expected, and the text has ${QueryFound.words(found)}"
    case SyntaxProblem.ClosingBracketExpected(bracket, found) =>
      s"The closing bracket ${QueryToken.words(bracket)} is expected, and the text has ${QueryFound.words(found)}"
    case SyntaxProblem.NotAnInfixAtom() =>
      "The operator here does not compare two terms"

  private def lexSentence(failure: LexProblem): String = failure match
    case LexProblem.UnterminatedLiteral     => "A quoted literal is not closed with '\"'"
    case LexProblem.NewlineInLiteral        => "A quoted literal holds a line break before its closing '\"'"
    case LexProblem.RefusedLiteralCharacter =>
      "A quoted literal holds a character other than letters, digits, spaces and / _ . + -"

  private def tokensWords(tokens: List[QueryToken]): String = tokens match
    case Nil    => "nothing"
    case tokens => tokens.map(QueryToken.words).mkString(" ")

  private def partWord(part: QuantifierPart): String = part match
    case QuantifierPart.Numerator   => "numerator"
    case QuantifierPart.Denominator => "denominator"

  private def contextWord(context: ParseContext): String = context match
    case ParseContext.RangeFormula         => "range formula"
    case ParseContext.ScopeFormula         => "scope formula"
    case ParseContext.AnswerVariables      => "answer variables"
    case ParseContext.EndOfAnswerVariables => "end of the answer variables"
    case ParseContext.Denominator          => "denominator"
    case ParseContext.ToleranceOrEnd       => "tolerance or the end of the quantifier"

  private def quantifierPart(part: vql.error.QuantifierPart): QuantifierPart = part match
    case vql.error.QuantifierPart.Numerator   => QuantifierPart.Numerator
    case vql.error.QuantifierPart.Denominator => QuantifierPart.Denominator

  private def parseContext(context: vql.error.ParseContext): ParseContext = context match
    case vql.error.ParseContext.RangeFormula         => ParseContext.RangeFormula
    case vql.error.ParseContext.ScopeFormula         => ParseContext.ScopeFormula
    case vql.error.ParseContext.AnswerVariables      => ParseContext.AnswerVariables
    case vql.error.ParseContext.EndOfAnswerVariables => ParseContext.EndOfAnswerVariables
    case vql.error.ParseContext.Denominator          => ParseContext.Denominator
    case vql.error.ParseContext.ToleranceOrEnd       => ParseContext.ToleranceOrEnd

  private def lex(failure: LexFailure): LexProblem = failure match
    case LexFailure.UnterminatedLiteral     => LexProblem.UnterminatedLiteral
    case LexFailure.NewlineInLiteral        => LexProblem.NewlineInLiteral
    case LexFailure.RefusedLiteralCharacter => LexProblem.RefusedLiteralCharacter

  private def syntax(failure: SyntaxFailure): Either[String, SyntaxProblem] = failure match
    case SyntaxFailure.FormulaExpected                 => Right(SyntaxProblem.FormulaExpected())
    case SyntaxFailure.QuantifierBodyExpected(found)   => QueryFound.fromEngine(found).map(SyntaxProblem.QuantifierBodyExpected(_))
    case SyntaxFailure.AtomExpected(found)             => QueryFound.fromEngine(found).map(SyntaxProblem.AtomExpected(_))
    case SyntaxFailure.TermExpected(found)             => QueryFound.fromEngine(found).map(SyntaxProblem.TermExpected(_))
    case SyntaxFailure.ClosingBracketExpected(bracket, found) =>
      for
        bracket <- QueryToken.fromEngine(bracket)
        found   <- QueryFound.fromEngine(found)
      yield SyntaxProblem.ClosingBracketExpected(bracket, found)
    case SyntaxFailure.NotAnInfixAtom                  => Right(SyntaxProblem.NotAnInfixAtom())

  private def trailing(first: TokenKind, rest: List[TokenKind]): Either[String, ParseProblem] =
    for
      first <- QueryToken.fromEngine(first)
      rest  <- tokens(rest)
    yield TrailingTokens(first, rest)

  private def tokens(kinds: List[TokenKind]): Either[String, List[QueryToken]] =
    traverse(kinds)(QueryToken.fromEngine)

  private def count(n: Int, engineCase: String): Either[String, NonNegativeInt] =
    n.refineEither[GreaterEqual[0]].left.map(_ => s"$engineCase carries a negative integer")

/** Each element through `f`, or the first reason one failed. */
private[errors] def traverse[A, B](as: List[A])(f: A => Either[String, B]): Either[String, List[B]] =
  as.foldRight[Either[String, List[B]]](Right(Nil))((a, acc) => f(a).flatMap(b => acc.map(b :: _)))

/** Why the engine refused a query, with the values its sentence is built from.
  *
  * Every field is typed to the content the engine's lexer and binder
  * guarantee, and the sentence is this service's own (`describe`), so the log
  * line and the query pane show one sentence. A refusal crosses to the caller
  * and the browser puts it in the query pane (ADR-028). The caller's words and
  * literals cross within their alphabets, and nothing else of the query text
  * does (ADR-035).
  */
@jsonDiscriminator(QueryProblem.TagField)
enum QueryProblem:
  /** The text does not parse, or its range formula breaks a variable rule. */
  case Unparseable(problem: Positioned[ParseProblem])

  /** The text parses and does not bind against the tree's catalog. */
  case Unbound(problems: NonEmptyList[Positioned[BindProblem]])

  /** The query names answer variables, the `(y)` suffix, and this service
    * evaluates yes-or-no queries only. Names at least one variable. */
  case AnswerVariablesNotSupported(variables: NonEmptyList[Positioned[QueryVariable.QueryVariable]])

object QueryProblem:
  /** The key naming which refusal a payload states. Read by this coproduct's
    * decoder and published in its schema, and stated here once so the two
    * cannot disagree. Each case's published value is its own name, which is
    * what the derived codec writes. */
  final val TagField = "kind"

  /** A non-empty list crosses as a JSON array, and the decoder refuses an
    * empty one, so a refusal that names nothing cannot be decoded either. */
  private given nonEmptyListEncoder[A: JsonEncoder]: JsonEncoder[NonEmptyList[A]] =
    JsonEncoder[List[A]].contramap(_.toList)
  private given nonEmptyListDecoder[A: JsonDecoder]: JsonDecoder[NonEmptyList[A]] =
    JsonDecoder[List[A]].mapOrFail(list => NonEmptyList.fromIterableOption(list).toRight("expected at least one element"))

  /** Published as an array of at least one item. */
  private given nonEmptyListSchema[A: Schema]: Schema[NonEmptyList[A]] =
    Schema.schemaForIterable[A, List]
      .validate(Validator.minSize[A, List](1))
      .map(list => NonEmptyList.fromIterableOption(list))(_.toList)

  given codec: JsonCodec[QueryProblem] = DeriveJsonCodec.gen[QueryProblem]

  /** Declared rather than left to a holder's derivation, so the payload's
    * published description is the cases rather than an unconstrained object,
    * and names the key that decides between them. */
  given schema: Schema[QueryProblem] =
    given Configuration = Configuration.default.withDiscriminator(TagField)
    Schema.derived[QueryProblem]

  /** The sentence for a refused query: the query pane shows it, and a server
    * log line records it.
    *
    * {{{
    * describe(AnswerVariablesNotSupported(NonEmptyList(Positioned(QueryPosition.Unknown(), y))))
    * // This service answers yes-or-no queries only and does not return answer variables yet: y
    * }}}
    */
  def describe(problem: QueryProblem): String = problem match
    case Unparseable(problem) =>
      ParseProblem.describe(problem)
    case Unbound(problems) =>
      problems.toList.map(p => BindProblem.describe(p.problem) + QueryPosition.clause(p.position)).mkString("; ")
    case AnswerVariablesNotSupported(variables) =>
      answerVariablesNotSupported(variables.toList.map(v => s"${v.problem.value}${QueryPosition.clause(v.position)}"))

  /** The sentence for a query naming answer variables, over each variable as
    * it is shown. */
  private def answerVariablesNotSupported(shown: List[String]): String =
    s"This service answers yes-or-no queries only and does not return answer variables yet: ${shown.mkString(", ")}"

  /** The refusal the caller can act on, or the reason the engine's error is a
    * defect.
    *
    * A model that does not satisfy its catalog, a failure after binding, and a
    * type with no domain are faults in how this service wires the knowledge
    * base, so nothing about the query would fix them. A reason names the
    * engine case and, where it has one, the variable, which the lexer bounds.
    * It never names `UnboundVariableError`'s bound variables, which hold
    * caller text of any kind.
    *
    * {{{
    * fromQueryError(QueryError.ParseError(ParseRefusal.Lexical(LexFailure.UnterminatedLiteral), SourcePosition.At(9)))
    * // Right(Unparseable(Positioned(QueryPosition.At(9), ParseProblem.Lexical(LexProblem.UnterminatedLiteral))))
    * }}}
    */
  def fromQueryError(err: QueryError): Either[String, QueryProblem] = err match
    case QueryError.ParseError(refusal, position) =>
      for
        position <- QueryPosition.fromSource(position)
        problem  <- ParseProblem.fromRefusal(refusal)
      yield Unparseable(Positioned(position, problem))
    case QueryError.ValidationError(validation, position) =>
      for
        position <- QueryPosition.fromSource(position)
        problem  <- ParseProblem.fromValidation(validation)
      yield Unparseable(Positioned(position, problem))
    case QueryError.UnknownConstantOrLiteralError(name) =>
      QueryLiteral.fromString(name.value).map(text => Unbound(NonEmptyList(unpositioned(BindProblem.UnknownConstant(text)))))
    case QueryError.BindError(Nil) =>
      Left("BindError carries no detail")
    case QueryError.BindError(head :: tail) =>
      for
        head <- BindProblem.fromTypeCheckError(head)
        tail <- traverse(tail)(BindProblem.fromTypeCheckError)
      yield Unbound(NonEmptyList.fromIterable(unpositioned(head), tail.map(unpositioned)))
    case _: QueryError.DomainNotFoundError =>
      Left("DomainNotFoundError after the model check")
    case _: QueryError.ModelValidationError =>
      Left("ModelValidationError after the model check")
    case QueryError.EvaluationError(failure) =>
      Left(s"EvaluationError: ${evaluationCase(failure)}")
    case QueryError.UnboundVariableError(variable, _) =>
      Left(s"UnboundVariableError: ${variable.value}")

  private def unpositioned[P](problem: P): Positioned[P] = Positioned(QueryPosition.Unknown(), problem)

  private def evaluationCase(failure: QueryError.EvaluationFailure): String = failure match
    case QueryError.EvaluationFailure.PredicateRefused(_, _)        => "PredicateRefused"
    case QueryError.EvaluationFailure.FunctionRefused(_, _)         => "FunctionRefused"
    case QueryError.EvaluationFailure.VariableSortMismatch(v, _, _) => s"VariableSortMismatch: ${v.value}"
