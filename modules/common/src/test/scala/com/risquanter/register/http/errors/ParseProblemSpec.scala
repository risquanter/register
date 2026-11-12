package com.risquanter.register.http.errors

import io.github.iltotore.iron.autoRefine
import zio.json.*
import zio.test.*
import lexer.{DecimalText, Found, GrammarSymbol, LexFailure, LiteralText, TokenKind, VariableText, WordText}
import parser.{SourcePosition, SyntaxFailure, ParseError as FormulaParseError}
import vql.error.{ParseRefusal, QueryError}
import vql.id.TypeId

import com.risquanter.register.common.FolSymbols.QueryGrammarSymbol
import com.risquanter.register.domain.data.iron.{QueryDecimal, QueryLiteral, QueryWord}

/** The parse refusal's translation from the engine, its wire shape and its
  * sentences. */
object ParseProblemSpec extends ZIOSpecDefault:

  private def word(s: String): QueryWord.QueryWord          = QueryWord.fromString(s).toOption.get
  private def literal(s: String): QueryLiteral.QueryLiteral = QueryLiteral.fromString(s).toOption.get
  private def wordVar(s: String): QueryVariableName         = QueryVariableName.Word(word(s))

  private val engineX: TokenKind = TokenKind.Word(WordText("x"))
  private val x: QueryToken      = QueryToken.Word(word("x"))
  private val foundX: QueryFound = QueryFound.Token(x)

  override def spec: Spec[TestEnvironment & zio.Scope, Any] =
    suite("ParseProblem")(
      suite("fromRefusal")(
        test("translates each of the parser's refusals case for case") {
          assertTrue(
            ParseProblem.fromRefusal(ParseRefusal.QuantifierExpected(List(engineX, TokenKind.Literal(LiteralText("a b"))))) ==
              Right(ParseProblem.QuantifierExpected(List(x, QueryToken.Literal(literal("a b"))))),
            ParseProblem.fromRefusal(ParseRefusal.UnknownQuantifierOperator(TokenKind.UnknownOperator)) ==
              Right(ParseProblem.UnknownQuantifierOperator(QueryToken.UnknownOperator())),
            ParseProblem.fromRefusal(ParseRefusal.IntegerExpected(vql.error.QuantifierPart.Numerator, Found.EndOfInput)) ==
              Right(ParseProblem.IntegerExpected(QuantifierPart.Numerator, QueryFound.EndOfInput())),
            ParseProblem.fromRefusal(ParseRefusal.IntegerOutOfRange(vql.error.QuantifierPart.Denominator, WordText("99999999999"))) ==
              Right(ParseProblem.IntegerOutOfRange(QuantifierPart.Denominator, word("99999999999"))),
            ParseProblem.fromRefusal(ParseRefusal.FractionOutOfRange(3, 2)) ==
              Right(ParseProblem.FractionOutOfRange(3, 2)),
            ParseProblem.fromRefusal(ParseRefusal.ToleranceOutOfRange(Double.PositiveInfinity)) ==
              Right(ParseProblem.ToleranceOutOfRange()),
            ParseProblem.fromRefusal(ParseRefusal.VariableExpected(Found.Token(TokenKind.StrayCharacter))) ==
              Right(ParseProblem.VariableExpected(QueryFound.Token(QueryToken.StrayCharacter()))),
            ParseProblem.fromRefusal(ParseRefusal.AnswerVariableExpected(Found.Token(TokenKind.Decimal(DecimalText("1.5"))))) ==
              Right(ParseProblem.AnswerVariableExpected(QueryFound.Token(QueryToken.Decimal(QueryDecimal.fromString("1.5").toOption.get)))),
            ParseProblem.fromRefusal(ParseRefusal.SymbolExpected(GrammarSymbol.RBrace, vql.error.ParseContext.ToleranceOrEnd, Found.Token(engineX))) ==
              Right(ParseProblem.SymbolExpected(QueryGrammarSymbol.RBrace, ParseContext.ToleranceOrEnd, foundX)),
            ParseProblem.fromRefusal(ParseRefusal.TrailingTokens(TokenKind.Symbol(GrammarSymbol.RParen), List(engineX))) ==
              Right(ParseProblem.TrailingTokens(QueryToken.Symbol(QueryGrammarSymbol.RParen), List(x))),
            ParseProblem.fromRefusal(ParseRefusal.Formula(SyntaxFailure.ClosingBracketExpected(TokenKind.Symbol(GrammarSymbol.RParen), Found.EndOfInput))) ==
              Right(ParseProblem.Formula(SyntaxProblem.ClosingBracketExpected(QueryToken.Symbol(QueryGrammarSymbol.RParen), QueryFound.EndOfInput()))),
            ParseProblem.fromRefusal(ParseRefusal.Lexical(LexFailure.NewlineInLiteral)) ==
              Right(ParseProblem.Lexical(LexProblem.NewlineInLiteral))
          )
        },
        test("a negative integer the parser cannot produce is a defect") {
          assertTrue(ParseProblem.fromRefusal(ParseRefusal.FractionOutOfRange(-1, 2)).isLeft)
        }
      ),
      suite("fromValidation")(
        test("the range formula's two variable rules are refusals of the text") {
          assertTrue(
            ParseProblem.fromValidation(QueryError.ValidationProblem.QuantifiedVariableNotInRange(
              VariableText.of("x"), Set(VariableText.of("y")))) ==
              Right(ParseProblem.QuantifiedVariableNotInRange(wordVar("x"), Set(wordVar("y")))),
            ParseProblem.fromValidation(QueryError.ValidationProblem.RangeVariablesNotAnswered(
              Set(VariableText.of("y"), VariableText.of("z")), List(VariableText.of("z")))) ==
              Right(ParseProblem.RangeVariablesNotAnswered(Set(wordVar("y"), wordVar("z")), List(wordVar("z"))))
          )
        },
        test("the two answer-tuple problems are defects") {
          assertTrue(
            ParseProblem.fromValidation(QueryError.ValidationProblem.MissingAnswerValue(VariableText.of("y"))).isLeft,
            ParseProblem.fromValidation(QueryError.ValidationProblem.AnswerSortMismatch(
              VariableText.of("y"), TypeId("Node"), TypeId("Loss"))).isLeft
          )
        }
      ),
      suite("fromFormulaError")(
        test("each of the formula parser's refusals keeps its position") {
          assertTrue(
            ParseProblem.fromFormulaError(FormulaParseError.Syntax(SyntaxFailure.FormulaExpected, SourcePosition.At(3))) ==
              Right(Positioned(QueryPosition.At(3), ParseProblem.Formula(SyntaxProblem.FormulaExpected()))),
            ParseProblem.fromFormulaError(FormulaParseError.Trailing(engineX, Nil, SourcePosition.Unknown)) ==
              Right(Positioned(QueryPosition.Unknown(), ParseProblem.TrailingTokens(x, Nil))),
            ParseProblem.fromFormulaError(FormulaParseError.Lex(LexFailure.RefusedLiteralCharacter, SourcePosition.At(12))) ==
              Right(Positioned(QueryPosition.At(12), ParseProblem.Lexical(LexProblem.RefusedLiteralCharacter)))
          )
        },
        test("a negative position is a defect") {
          assertTrue(ParseProblem.fromFormulaError(FormulaParseError.Syntax(SyntaxFailure.FormulaExpected, SourcePosition.At(-1))).isLeft)
        }
      ),
      suite("QueryGrammarSymbol.fromEngine")(
        test("maps every engine symbol to the case with its spelling, and no two to one case") {
          val mapped = GrammarSymbol.values.toList.map(QueryGrammarSymbol.fromEngine)
          assertTrue(
            GrammarSymbol.values.toList.zip(mapped).forall((engine, ours) => engine.text == ours.text),
            mapped.distinct.size == QueryGrammarSymbol.values.length
          )
        }
      ),
      suite("on the wire")(
        test("a positioned problem round-trips through JSON") {
          val problems: List[Positioned[ParseProblem]] = List(
            Positioned(QueryPosition.At(4), ParseProblem.SymbolExpected(QueryGrammarSymbol.RBrace, ParseContext.Denominator, foundX)),
            Positioned(QueryPosition.Unknown(), ParseProblem.RangeVariablesNotAnswered(Set(wordVar("y")), Nil)),
            Positioned(QueryPosition.Unknown(), ParseProblem.Lexical(LexProblem.UnterminatedLiteral)),
            Positioned(QueryPosition.Unknown(), ParseProblem.ToleranceOutOfRange())
          )
          assertTrue(problems.forall(p => p.toJson.fromJson[Positioned[ParseProblem]] == Right(p)))
        },
        test("a literal token holding a newline or an apostrophe does not decode") {
          assertTrue(
            """{"kind":"Literal","content":"a\nb"}""".fromJson[QueryToken].isLeft,
            """{"kind":"Literal","content":"O'Brien"}""".fromJson[QueryToken].isLeft
          )
        }
      ),
      suite("describe")(
        test("renders each refusal from its fields, ending with the position where it is known") {
          val at = (p: ParseProblem) => ParseProblem.describe(Positioned(QueryPosition.Unknown(), p))
          assertTrue(
            at(ParseProblem.QuantifierExpected(List(x))) ==
              "A query starts with a quantifier such as Q[>=]^{2/3}, and this one starts with 'x'",
            at(ParseProblem.QuantifierExpected(Nil)) ==
              "A query starts with a quantifier such as Q[>=]^{2/3}, and this one starts with nothing",
            at(ParseProblem.UnknownQuantifierOperator(QueryToken.UnknownOperator())) ==
              "A quantifier's operator is ~, >= or <=, and this one is an operator the query language does not have",
            at(ParseProblem.IntegerExpected(QuantifierPart.Numerator, QueryFound.EndOfInput())) ==
              "The quantifier's numerator must be a whole number, and the query has the end of the text",
            at(ParseProblem.IntegerOutOfRange(QuantifierPart.Denominator, word("99999999999"))) ==
              "The quantifier's denominator 99999999999 is too large",
            at(ParseProblem.FractionOutOfRange(3, 2)) ==
              "The quantifier's proportion 3/2 must lie between 0 and 1, with a denominator above 0",
            at(ParseProblem.ToleranceOutOfRange()) ==
              "The quantifier's tolerance must lie between 0 and 1",
            at(ParseProblem.VariableExpected(QueryFound.Token(QueryToken.StrayCharacter()))) ==
              "The quantifier must name its variable next, and the query has a character the query language does not use",
            at(ParseProblem.AnswerVariableExpected(QueryFound.Token(QueryToken.Literal(literal("a"))))) ==
              "The list of answer variables must hold variable names, and the query has \"a\"",
            at(ParseProblem.SymbolExpected(QueryGrammarSymbol.RBrace, ParseContext.ToleranceOrEnd, foundX)) ==
              "The query needs '}' before the tolerance or the end of the quantifier, and has 'x'",
            at(ParseProblem.TrailingTokens(QueryToken.Symbol(QueryGrammarSymbol.RParen), List(x))) ==
              "The text continues after its end: ')' 'x'",
            at(ParseProblem.Formula(SyntaxProblem.FormulaExpected())) ==
              "A formula is expected here",
            at(ParseProblem.Formula(SyntaxProblem.QuantifierBodyExpected(QueryFound.EndOfInput()))) ==
              "A quantified formula needs a body, and the text has the end of the text",
            at(ParseProblem.Formula(SyntaxProblem.AtomExpected(foundX))) ==
              "A predicate is expected, and the text has 'x'",
            at(ParseProblem.Formula(SyntaxProblem.TermExpected(foundX))) ==
              "A term is expected, and the text has 'x'",
            at(ParseProblem.Formula(SyntaxProblem.ClosingBracketExpected(QueryToken.Symbol(QueryGrammarSymbol.RParen), QueryFound.EndOfInput()))) ==
              "The closing bracket ')' is expected, and the text has the end of the text",
            at(ParseProblem.Formula(SyntaxProblem.NotAnInfixAtom())) ==
              "The operator here does not compare two terms",
            at(ParseProblem.Lexical(LexProblem.UnterminatedLiteral)) ==
              "A quoted literal is not closed with '\"'",
            at(ParseProblem.Lexical(LexProblem.NewlineInLiteral)) ==
              "A quoted literal holds a line break before its closing '\"'",
            at(ParseProblem.Lexical(LexProblem.RefusedLiteralCharacter)) ==
              "A quoted literal holds a character other than letters, digits, spaces and / _ . + -",
            at(ParseProblem.QuantifiedVariableNotInRange(wordVar("x"), Set(wordVar("y")))) ==
              "The quantified variable 'x' must occur free in the range formula",
            ParseProblem.describe(Positioned(QueryPosition.At(9), ParseProblem.Lexical(LexProblem.UnterminatedLiteral))) ==
              "A quoted literal is not closed with '\"' (at character 9)"
          )
        },
        test("names only the range variables that are not answer variables") {
          val problem = ParseProblem.RangeVariablesNotAnswered(Set(wordVar("z"), wordVar("y"), wordVar("w")), List(wordVar("w")))
          assertTrue(ParseProblem.describe(Positioned(QueryPosition.Unknown(), problem)) ==
            "The range formula's variables 'y', 'z' must also be answer variables")
        }
      )
    )

end ParseProblemSpec
