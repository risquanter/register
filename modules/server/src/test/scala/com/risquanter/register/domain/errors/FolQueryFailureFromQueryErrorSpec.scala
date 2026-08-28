package com.risquanter.register.domain.errors

import zio.test.*

import vql.error.QueryError as QE
import vql.error.BindErrorDetail

/** Tests for [[FolQueryFailure.fromQueryError]] — the centralised mapping
  * from fol-engine's `QueryError` algebra to register's error hierarchy.
  *
  * Covers the exhaustive match: every concrete `QueryError` subtype maps
  * to the correct `FolQueryFailure` variant, preserving relevant fields.
  */
object FolQueryFailureFromQueryErrorSpec extends ZIOSpecDefault:

  override def spec: Spec[TestEnvironment & zio.Scope, Any] =
    suite("FolQueryFailure.fromQueryError")(
      parseSuite,
      bindSuite,
      domainNotFoundSuite,
      modelValidationSuite,
      evaluationSuite,
      catchAllSuite
    )

  // ── Parse errors → FolParseFailure ──────────────────────────────────

  private val parseSuite = suite("→ FolParseFailure")(
    test("ParseError with position") {
      val err = QE.ParseError("Unexpected token", "Q[>=]^{2/3 x", position = Some(10))
      FolQueryFailure.fromQueryError(err) match
        case FolQueryFailure.FolParseFailure(msg, pos) =>
          assertTrue(msg == "Unexpected token", pos == Some(10))
        case other => throw MatchError(other)
    },
    test("ParseError without position") {
      val err = QE.ParseError("Empty input", "")
      FolQueryFailure.fromQueryError(err) match
        case FolQueryFailure.FolParseFailure(_, pos) =>
          assertTrue(pos.isEmpty)
        case other => throw MatchError(other)
    }
  )

  // ── Bind errors → classification (UNKNOWN_REFERENCE vs BIND_FAILED) ──

  /** A bind-phase unparseable-constant detail with a synthetic rendered message. */
  private def unparseable(name: String, sortName: String): BindErrorDetail =
    BindErrorDetail.UnparseableConstant(
      name, sortName, sourceText = s""""$name"""",
      rendered = s"Unknown $sortName reference: '$name'"
    )

  private val bindSuite = suite("BindError classification")(
    test("all-node-unresolved → FolUnknownReference carrying the rendered messages") {
      val err = QE.BindError(List(
        unparseable("Foo", FolQueryFailure.NodeSortName),
        unparseable("Bar", FolQueryFailure.NodeSortName)
      ))
      FolQueryFailure.fromQueryError(err) match
        case FolQueryFailure.FolUnknownReference(messages) =>
          assertTrue(messages == err.messages, messages.exists(_.contains("Foo")))
        case other => throw MatchError(other)
    },
    test("node-unresolved mixed with a genuine type error → FolBindFailure") {
      val err = QE.BindError(List(
        unparseable("Foo", FolQueryFailure.NodeSortName),
        BindErrorDetail.Other("arity mismatch for 'leaf': expected 1, actual 2")
      ))
      FolQueryFailure.fromQueryError(err) match
        case FolQueryFailure.FolBindFailure(errors) =>
          assertTrue(errors == err.messages, errors.exists(_.contains("arity mismatch")))
        case other => throw MatchError(other)
    },
    test("homogeneous NodeNameLiteral unparseable constant → FolUnknownReference") {
      // named(x, "Nonexistent") fails on the NodeNameLiteral sort — the same
      // nonexistent-node error as a Node-slot name, so it classifies as
      // UNKNOWN_REFERENCE, not BIND_FAILED (Option B).
      val err = QE.BindError(List(
        unparseable("Nonexistent", FolQueryFailure.NodeNameLiteralSortName)
      ))
      FolQueryFailure.fromQueryError(err) match
        case FolQueryFailure.FolUnknownReference(messages) =>
          assertTrue(messages == err.messages, messages.exists(_.contains("Nonexistent")))
        case other => throw MatchError(other)
    },
    test("Node and NodeNameLiteral mixed unresolved → FolUnknownReference") {
      // A query naming a nonexistent node in both a structural slot and via
      // named still classifies wholly as UNKNOWN_REFERENCE.
      val err = QE.BindError(List(
        unparseable("Foo", FolQueryFailure.NodeSortName),
        unparseable("Bar", FolQueryFailure.NodeNameLiteralSortName)
      ))
      FolQueryFailure.fromQueryError(err) match
        case FolQueryFailure.FolUnknownReference(messages) =>
          assertTrue(messages == err.messages)
        case other => throw MatchError(other)
    },
    test("homogeneous NodeIdLiteral unparseable constant → FolBindFailure") {
      // A malformed id (has_id(x, "not-an-id")) is genuine bind failure, not a
      // nonexistent-node reference — NodeIdLiteral is excluded from the set.
      val err = QE.BindError(List(unparseable("not-an-id", "NodeIdLiteral")))
      FolQueryFailure.fromQueryError(err) match
        case FolQueryFailure.FolBindFailure(errors) =>
          assertTrue(errors.size == 1, errors.head.contains("not-an-id"))
        case other => throw MatchError(other)
    },
    test("homogeneous non-node unparseable constant → FolBindFailure") {
      val err = QE.BindError(List(unparseable("notaloss", "Loss")))
      FolQueryFailure.fromQueryError(err) match
        case FolQueryFailure.FolBindFailure(errors) =>
          assertTrue(errors.size == 1, errors.head.contains("notaloss"))
        case other => throw MatchError(other)
    },
    test("BindError getMessage joins rendered messages with semicolons") {
      val err = QE.BindError(List(
        BindErrorDetail.Other("error A"),
        BindErrorDetail.Other("error B")
      ))
      val mapped = FolQueryFailure.fromQueryError(err)
      assertTrue(mapped.getMessage.contains("error A; error B"))
    }
  )

  // ── Domain-not-found errors → FolDomainNotQuantifiable ──────────────

  private val domainNotFoundSuite = suite("→ FolDomainNotQuantifiable")(
    test("DomainNotFoundError preserves typeName and availableTypes") {
      val err = QE.DomainNotFoundError("Loss", Set("Asset"))
      FolQueryFailure.fromQueryError(err) match
        case FolQueryFailure.FolDomainNotQuantifiable(tn, avail) =>
          assertTrue(tn == "Loss", avail == Set("Asset"))
        case other => throw MatchError(other)
    },
    test("DomainNotFoundError getMessage mentions type and Asset") {
      val err = QE.DomainNotFoundError("Probability", Set("Asset"))
      val mapped = FolQueryFailure.fromQueryError(err)
      assertTrue(
        mapped.getMessage.contains("Probability"),
        mapped.getMessage.contains("Asset")
      )
    }
  )

  // ── Model validation errors → FolModelValidationFailure ─────────────

  private val modelValidationSuite = suite("→ FolModelValidationFailure")(
    test("ModelValidationError preserves error list") {
      val err = QE.ModelValidationError(List(
        "Missing function implementation: lec",
        "Missing domain for type: Asset"
      ))
      FolQueryFailure.fromQueryError(err) match
        case FolQueryFailure.FolModelValidationFailure(errors) =>
          assertTrue(errors.size == 2, errors.head == "Missing function implementation: lec")
        case other => throw MatchError(other)
    },
    test("ModelValidationError getMessage joins errors") {
      val err = QE.ModelValidationError(List("error X"))
      val mapped = FolQueryFailure.fromQueryError(err)
      assertTrue(mapped.getMessage.contains("error X"))
    }
  )

  // ── Evaluation errors → FolEvaluationFailure ─────────────────────────

  private val evaluationSuite = suite("→ FolEvaluationFailure")(
    test("EvaluationError preserves message and phase") {
      val err = QE.EvaluationError("Division by zero", "scope_eval")
      FolQueryFailure.fromQueryError(err) match
        case FolQueryFailure.FolEvaluationFailure(msg, phase) =>
          assertTrue(msg == "Division by zero", phase == "scope_eval")
        case other => throw MatchError(other)
    },
    test("fol ValidationError maps with phase 'validation'") {
      val err = QE.ValidationError("Invalid field", "query")
      FolQueryFailure.fromQueryError(err) match
        case FolQueryFailure.FolEvaluationFailure(_, phase) =>
          assertTrue(phase == "validation")
        case other => throw MatchError(other)
    }
  )

  // ── Catch-all for remaining subtypes ────────────────────────────────

  private val catchAllSuite = suite("remaining subtypes → FolEvaluationFailure")(
    test("UnboundVariableError maps with phase 'unbound_variable'") {
      val err = QE.UnboundVariableError("x", Set("y", "z"))
      FolQueryFailure.fromQueryError(err) match
        case FolQueryFailure.FolEvaluationFailure(_, phase) =>
          assertTrue(phase == "unbound_variable")
        case other => throw MatchError(other)
    }
  )

end FolQueryFailureFromQueryErrorSpec
