package com.risquanter.register.http.errors

import io.github.iltotore.iron.autoRefine
import zio.json.*
import zio.test.*
import lexer.{LiteralText, SymbolText, VariableText}
import vql.error.TypeCheckError
import vql.id.TypeId

import com.risquanter.register.common.FolSymbols.{QueryGrammarSymbol, QuerySort}
import com.risquanter.register.domain.data.iron.{QueryLiteral, QueryWord}

/** The bind refusal's translation from the engine, its wire shape and its
  * sentences. */
object BindProblemSpec extends ZIOSpecDefault:

  private def word(s: String): QueryWord.QueryWord          = QueryWord.fromString(s).toOption.get
  private def literal(s: String): QueryLiteral.QueryLiteral = QueryLiteral.fromString(s).toOption.get
  private def symbol(s: String): QuerySymbolName            = QuerySymbolName.Word(word(s))
  private def variable(s: String): QueryVariableName        = QueryVariableName.Word(word(s))

  private val node: TypeId = TypeId(QuerySort.Node.symbol)
  private val loss: TypeId = TypeId(QuerySort.Loss.symbol)

  override def spec: Spec[TestEnvironment & zio.Scope, Any] =
    suite("BindProblem")(
      suite("fromTypeCheckError")(
        test("translates each reachable bind error case for case") {
          assertTrue(
            BindProblem.fromTypeCheckError(TypeCheckError.UnknownPredicate(SymbolText.of("lef"))) ==
              Right(BindProblem.UnknownPredicate(symbol("lef"))),
            BindProblem.fromTypeCheckError(TypeCheckError.UnknownFunction(SymbolText.of("p90"))) ==
              Right(BindProblem.UnknownFunction(symbol("p90"))),
            BindProblem.fromTypeCheckError(TypeCheckError.ArityMismatch(SymbolText.of("leaf"), vql.error.SymbolKind.Predicate, 1, 2)) ==
              Right(BindProblem.ArityMismatch(symbol("leaf"), SymbolKind.Predicate, 1, 2)),
            BindProblem.fromTypeCheckError(TypeCheckError.UnknownConstantOrLiteral(LiteralText("Cyber"))) ==
              Right(BindProblem.UnknownConstant(literal("Cyber"))),
            BindProblem.fromTypeCheckError(TypeCheckError.TypeMismatch(node, loss, vql.error.MismatchSite.Variable(VariableText.of("x")))) ==
              Right(BindProblem.SortMismatch(MismatchSite.Variable(variable("x")), QuerySort.Node, QuerySort.Loss)),
            BindProblem.fromTypeCheckError(TypeCheckError.UnconstrainedVar(VariableText.of("x"))) ==
              Right(BindProblem.UnconstrainedVariable(variable("x"))),
            BindProblem.fromTypeCheckError(TypeCheckError.ConflictingTypes(VariableText.of("x"), node, loss)) ==
              Right(BindProblem.ConflictingSorts(variable("x"), QuerySort.Node, QuerySort.Loss)),
            BindProblem.fromTypeCheckError(TypeCheckError.UnparseableConstant(
              LiteralText("Cyber"), TypeId(QuerySort.NodeNameLiteral.symbol), LiteralText("Cyber"))) ==
              Right(BindProblem.UnreadableLiteral(literal("Cyber"), QuerySort.NodeNameLiteral)),
            BindProblem.fromTypeCheckError(TypeCheckError.TypeNotQuantifiable(loss)) ==
              Right(BindProblem.NotQuantifiable(QuerySort.Loss))
          )
        },
        test("an operator names a predicate or a function as an operator") {
          assertTrue(
            BindProblem.fromTypeCheckError(TypeCheckError.UnknownPredicate(SymbolText.of("<"))) ==
              Right(BindProblem.UnknownPredicate(QuerySymbolName.Operator(QueryGrammarSymbol.Less))),
            BindProblem.fromTypeCheckError(TypeCheckError.TypeMismatch(loss, node, vql.error.MismatchSite.FunctionReturn(SymbolText.of("+")))) ==
              Right(BindProblem.SortMismatch(MismatchSite.FunctionReturn(QuerySymbolName.Operator(QueryGrammarSymbol.Plus)), QuerySort.Loss, QuerySort.Node))
          )
        },
        test("an answer variable and a second free variable are defects, and so is a sort the catalog does not declare") {
          assertTrue(
            BindProblem.fromTypeCheckError(TypeCheckError.UnboundAnswerVar(VariableText.of("y"))).isLeft,
            BindProblem.fromTypeCheckError(TypeCheckError.UnexpectedFreeVar(VariableText.of("y"))).isLeft,
            BindProblem.fromTypeCheckError(TypeCheckError.TypeNotQuantifiable(TypeId("Asset"))).isLeft
          )
        }
      ),
      suite("namesAbsentNode")(
        test("holds for an unknown constant and a literal of a node-reference sort, and for nothing else") {
          assertTrue(
            BindProblem.namesAbsentNode(BindProblem.UnknownConstant(literal("Cyber"))),
            BindProblem.namesAbsentNode(BindProblem.UnreadableLiteral(literal("Cyber"), QuerySort.NodeNameLiteral)),
            !BindProblem.namesAbsentNode(BindProblem.UnreadableLiteral(literal("bad"), QuerySort.NodeIdLiteral)),
            !BindProblem.namesAbsentNode(BindProblem.UnknownPredicate(symbol("lef")))
          )
        }
      ),
      suite("on the wire")(
        test("every case round-trips through JSON") {
          val problems: List[BindProblem] = List(
            BindProblem.UnknownPredicate(QuerySymbolName.Operator(QueryGrammarSymbol.Less)),
            BindProblem.UnknownFunction(symbol("p90")),
            BindProblem.ArityMismatch(symbol("leaf"), SymbolKind.Function, 1, 2),
            BindProblem.UnknownConstant(literal("Cyber")),
            BindProblem.UnreadableLiteral(literal("Cyber"), QuerySort.NodeNameLiteral),
            BindProblem.SortMismatch(MismatchSite.Constant(literal("1")), QuerySort.Loss, QuerySort.Node),
            BindProblem.ConflictingSorts(variable("x"), QuerySort.Node, QuerySort.Mitigation),
            BindProblem.UnconstrainedVariable(variable("x")),
            BindProblem.NotQuantifiable(QuerySort.Probability)
          )
          assertTrue(problems.forall(p => p.toJson.fromJson[BindProblem] == Right(p)))
        },
        test("a payload naming a sort the catalog does not declare does not decode") {
          assertTrue("""{"kind":"NotQuantifiable","sort":{"kind":"Asset"}}""".fromJson[BindProblem].isLeft)
        }
      ),
      suite("describe")(
        test("renders each problem from its fields") {
          assertTrue(
            BindProblem.describe(BindProblem.UnknownPredicate(symbol("lef"))) ==
              "The query uses 'lef' as a predicate, and no predicate has that name",
            BindProblem.describe(BindProblem.UnknownFunction(QuerySymbolName.Operator(QueryGrammarSymbol.Plus))) ==
              "The query uses '+' as a function, and no function has that name",
            BindProblem.describe(BindProblem.ArityMismatch(symbol("leaf"), SymbolKind.Predicate, 1, 2)) ==
              "The predicate 'leaf' takes 1 argument, and the query gives it 2",
            BindProblem.describe(BindProblem.ArityMismatch(symbol("p95"), SymbolKind.Function, 2, 1)) ==
              "The function 'p95' takes 2 arguments, and the query gives it 1",
            BindProblem.describe(BindProblem.UnknownConstant(literal("Cyber"))) ==
              "Nothing in this tree is named \"Cyber\"",
            BindProblem.describe(BindProblem.UnreadableLiteral(literal("Cyber"), QuerySort.NodeNameLiteral)) ==
              "No node is named \"Cyber\" in this tree",
            BindProblem.describe(BindProblem.UnreadableLiteral(literal("abc"), QuerySort.Loss)) ==
              "\"abc\" cannot be read as a loss",
            BindProblem.describe(BindProblem.SortMismatch(MismatchSite.Variable(variable("x")), QuerySort.Node, QuerySort.Loss)) ==
              "The variable 'x' is a loss where a node is expected",
            BindProblem.describe(BindProblem.SortMismatch(MismatchSite.FunctionReturn(symbol("p95")), QuerySort.Probability, QuerySort.Loss)) ==
              "What the function 'p95' returns is a loss where a probability is expected",
            BindProblem.describe(BindProblem.ConflictingSorts(variable("x"), QuerySort.Node, QuerySort.Mitigation)) ==
              "The variable 'x' is used both as a node and as a mitigation",
            BindProblem.describe(BindProblem.UnconstrainedVariable(variable("x"))) ==
              "Nothing in the query says what the variable 'x' ranges over",
            BindProblem.describe(BindProblem.NotQuantifiable(QuerySort.Loss)) ==
              "A query cannot range over a loss, because it is a value rather than a set of things"
          )
        }
      )
    )

end BindProblemSpec
