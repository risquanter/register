package com.risquanter.register.domain.errors

import zio.prelude.NonEmptyList
import zio.test.*

import vql.error.QueryError as QE
import vql.error.BindErrorDetail

import com.risquanter.register.http.errors.QueryProblem
import com.risquanter.register.services.QueryService

/** Tests for `QueryProblem.fromQueryError`, which reads the engine's
  * `QueryError` as the refusal a caller can act on, and for
  * `QueryService.refused`, which turns that reading into a failure.
  */
object QueryProblemFromQueryErrorSpec extends ZIOSpecDefault:

  override def spec: Spec[TestEnvironment & zio.Scope, Any] =
    suite("QueryProblem.fromQueryError")(
      parseSuite,
      bindSuite,
      domainNotFoundSuite,
      wiringSuite,
      refusedSuite
    )

  // ── Errors in the query's own text → Unparseable ────────────────────

  private val parseSuite = suite("→ Unparseable")(
    test("ParseError with position") {
      val err = QE.ParseError("Unexpected token", "Q[>=]^{2/3 x", position = Some(10))
      assertTrue(QueryProblem.fromQueryError(err) == Some(QueryProblem.Unparseable("Unexpected token", Some(10))))
    },
    test("ParseError without position") {
      val err = QE.ParseError("Empty input", "")
      assertTrue(QueryProblem.fromQueryError(err) == Some(QueryProblem.Unparseable("Empty input", None)))
    },
    test("ValidationError, raised from the query's own variable rules, is the caller's text refused") {
      val err = QE.ValidationError("Variable x is bound twice", "query")
      assertTrue(QueryProblem.fromQueryError(err) == Some(QueryProblem.Unparseable("Variable x is bound twice", None)))
    }
  )

  // ── Bind errors → UnknownReferences or TypeCheckFailed ──────────────

  /** A bind-phase unparseable-constant detail with a synthetic rendered message. */
  private def unparseable(name: String, sortName: String): BindErrorDetail =
    BindErrorDetail.UnparseableConstant(
      name, sortName, sourceText = s""""$name"""",
      rendered = s"Unknown $sortName reference: '$name'"
    )

  private val bindSuite = suite("BindError classification")(
    test("every detail an unresolved node → UnknownReferences carrying the rendered messages") {
      val err = QE.BindError(List(
        unparseable("Foo", FolQueryFailure.NodeSortName),
        unparseable("Bar", FolQueryFailure.NodeSortName)
      ))
      assertTrue(QueryProblem.fromQueryError(err) == Some(QueryProblem.UnknownReferences(err.messages)))
    },
    test("an unresolved node beside a genuine type error → TypeCheckFailed") {
      val err = QE.BindError(List(
        unparseable("Foo", FolQueryFailure.NodeSortName),
        BindErrorDetail.ArityMismatch(
          symbol = "leaf", expected = 1, actual = 2,
          rendered = "arity mismatch for 'leaf': expected 1, actual 2"
        )
      ))
      assertTrue(QueryProblem.fromQueryError(err) == Some(QueryProblem.TypeCheckFailed(err.messages)))
    },
    test("a name literal naming no node → UnknownReferences") {
      // named_risk(x, "Nonexistent") fails on the NodeNameLiteral sort, which is
      // the same missing node as a name in a node slot.
      val err = QE.BindError(List(unparseable("Nonexistent", FolQueryFailure.NodeNameLiteralSortName)))
      assertTrue(QueryProblem.fromQueryError(err) == Some(QueryProblem.UnknownReferences(err.messages)))
    },
    test("a node slot and a name literal both unresolved → UnknownReferences") {
      val err = QE.BindError(List(
        unparseable("Foo", FolQueryFailure.NodeSortName),
        unparseable("Bar", FolQueryFailure.NodeNameLiteralSortName)
      ))
      assertTrue(QueryProblem.fromQueryError(err) == Some(QueryProblem.UnknownReferences(err.messages)))
    },
    test("a malformed id literal → TypeCheckFailed") {
      // risk_id(x, "not-an-id") is a type error, so NodeIdLiteral is not a
      // node-reference sort.
      val err = QE.BindError(List(unparseable("not-an-id", "NodeIdLiteral")))
      assertTrue(QueryProblem.fromQueryError(err) == Some(QueryProblem.TypeCheckFailed(err.messages)))
    },
    test("a non-node literal → TypeCheckFailed") {
      val err = QE.BindError(List(unparseable("notaloss", "Loss")))
      assertTrue(QueryProblem.fromQueryError(err) == Some(QueryProblem.TypeCheckFailed(err.messages)))
    },
    test("an unknown constant or literal → UnknownReferences") {
      // Reachable through a quoted literal in the mitigation slot, such as
      // p95(x, "foo"), because no literal validator exists for that sort.
      val err = QE.UnknownConstantOrLiteralError("foo")
      assertTrue(QueryProblem.fromQueryError(err) == Some(QueryProblem.UnknownReferences(List(err.message))))
    }
  )

  // ── Domain not found → NotQuantifiable ──────────────────────────────

  private val domainNotFoundSuite = suite("→ NotQuantifiable")(
    test("DomainNotFoundError names the type and the quantifiable types") {
      val err = QE.DomainNotFoundError("Loss", Set("Asset"))
      assertTrue(QueryProblem.fromQueryError(err) == Some(QueryProblem.NotQuantifiable(
        "Queries can only range over tree nodes (type 'Asset'). " +
          "The type 'Loss' cannot be enumerated. " +
          "Available quantifiable types: Asset"
      )))
    }
  )

  // ── Wiring faults → None ────────────────────────────────────────────

  private val wiringSuite = suite("→ None")(
    test("ModelValidationError") {
      assertTrue(QueryProblem.fromQueryError(QE.ModelValidationError(List("Missing domain for type: Asset"))).isEmpty)
    },
    test("EvaluationError") {
      assertTrue(QueryProblem.fromQueryError(QE.EvaluationError("Division by zero", "scope_eval")).isEmpty)
    },
    test("UnboundVariableError") {
      assertTrue(QueryProblem.fromQueryError(QE.UnboundVariableError("x", Set("y", "z"))).isEmpty)
    }
  )

  // ── QueryService.refused ────────────────────────────────────────────

  private val refusedSuite = suite("QueryService.refused")(
    test("a parse failure is Rejected at Client at the whole request") {
      val err = QE.ParseError("Expected '}'", "Q[>=]^{2/3 x", Some(14))
      assertTrue(QueryService.refused(err) == Failure.rejected(
        TrustZone.Client,
        NonEmptyList(ValidationError(ErrorLocation.Whole, FieldProblem.Query(QueryProblem.Unparseable("Expected '}'", Some(14))))),
        Nil
      ))
    },
    test("a model that does not satisfy its catalog is a model-validation fault") {
      val err = QE.ModelValidationError(List("Missing function implementation: lec"))
      assertTrue(QueryService.refused(err) == FolQueryFailure.FolModelValidationFailure(List("Missing function implementation: lec")))
    },
    test("an evaluation failure keeps its message and phase") {
      val err = QE.EvaluationError("Division by zero", "scope_eval")
      assertTrue(QueryService.refused(err) == FolQueryFailure.FolEvaluationFailure("Division by zero", "scope_eval"))
    },
    test("an unbound variable is an evaluation fault") {
      val err = QE.UnboundVariableError("x", Set("y", "z"))
      assertTrue(QueryService.refused(err) == FolQueryFailure.FolEvaluationFailure(err.message, "unbound_variable"))
    }
  )

end QueryProblemFromQueryErrorSpec
