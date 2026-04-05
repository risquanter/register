package com.risquanter.register.domain.errors

import zio.test.*

import fol.error.QueryError as QE
import fol.datastore.RelationName

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
      unknownSymbolSuite,
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
    },
    test("LexicalError wraps position in Some") {
      val err = QE.LexicalError("Invalid character", '#', 5)
      FolQueryFailure.fromQueryError(err) match
        case FolQueryFailure.FolParseFailure(_, pos) =>
          assertTrue(pos == Some(5))
        case other => throw MatchError(other)
    }
  )

  // ── Schema/symbol errors → FolUnknownSymbol ─────────────────────────

  private val unknownSymbolSuite = suite("→ FolUnknownSymbol")(
    test("RelationNotFoundError preserves relation name and available list") {
      val err = QE.RelationNotFoundError(
        RelationName("foo"),
        Set(RelationName("leaf"), RelationName("portfolio"))
      )
      FolQueryFailure.fromQueryError(err) match
        case FolQueryFailure.FolUnknownSymbol(sym, avail) =>
          assertTrue(sym == "foo", avail.toSet == Set("leaf", "portfolio"))
        case other => throw MatchError(other)
    },
    test("UninterpretedSymbolError preserves symbol name") {
      val err = QE.UninterpretedSymbolError("predicate", "unknown_pred", Set("leaf", "portfolio"))
      FolQueryFailure.fromQueryError(err) match
        case FolQueryFailure.FolUnknownSymbol(sym, avail) =>
          assertTrue(sym == "unknown_pred", avail.toSet == Set("leaf", "portfolio"))
        case other => throw MatchError(other)
    },
    test("SchemaError maps to FolUnknownSymbol with empty available") {
      val err = QE.SchemaError("Arity mismatch", RelationName("leaf"), expectedArity = 1, actualArity = 2)
      FolQueryFailure.fromQueryError(err) match
        case FolQueryFailure.FolUnknownSymbol(sym, avail) =>
          assertTrue(sym == "leaf", avail.isEmpty)
        case other => throw MatchError(other)
    }
  )

  // ── Bind errors → FolBindFailure ──────────────────────────────────

  private val bindSuite = suite("→ FolBindFailure")(
    test("BindError with single error preserves message") {
      val err = QE.BindError(List("type 'Loss' is not a domain type and cannot be quantified over"))
      FolQueryFailure.fromQueryError(err) match
        case FolQueryFailure.FolBindFailure(errors) =>
          assertTrue(errors.size == 1, errors.head.contains("Loss"))
        case other => throw MatchError(other)
    },
    test("BindError with multiple errors preserves all") {
      val err = QE.BindError(List(
        "unknown predicate: foo",
        "arity mismatch for 'leaf': expected 1, actual 2"
      ))
      FolQueryFailure.fromQueryError(err) match
        case FolQueryFailure.FolBindFailure(errors) =>
          assertTrue(errors.size == 2, errors.head == "unknown predicate: foo")
        case other => throw MatchError(other)
    },
    test("BindError getMessage joins errors with semicolons") {
      val err = QE.BindError(List("error A", "error B"))
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
    test("ScopeEvaluationError maps with phase 'scope'") {
      val err = QE.ScopeEvaluationError("Scope failed", "some_formula", "some_element")
      FolQueryFailure.fromQueryError(err) match
        case FolQueryFailure.FolEvaluationFailure(_, phase) =>
          assertTrue(phase == "scope")
        case other => throw MatchError(other)
    },
    test("TypeMismatchError maps with phase 'type_check'") {
      val err = QE.TypeMismatchError("Expected Int, got String", "Int", "String", "arg0")
      FolQueryFailure.fromQueryError(err) match
        case FolQueryFailure.FolEvaluationFailure(_, phase) =>
          assertTrue(phase == "type_check")
        case other => throw MatchError(other)
    },
    test("TimeoutError preserves operation field") {
      val err = QE.TimeoutError("evaluation", 5000L)
      FolQueryFailure.fromQueryError(err) match
        case FolQueryFailure.FolEvaluationFailure(_, phase) =>
          assertTrue(phase == "evaluation")
        case other => throw MatchError(other)
    },
    test("QuantifierError maps with phase 'quantifier'") {
      val err = QE.QuantifierError("k must be <= n", 5, 3, 0.0)
      FolQueryFailure.fromQueryError(err) match
        case FolQueryFailure.FolEvaluationFailure(_, phase) =>
          assertTrue(phase == "quantifier")
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
    test("QueryStructureError maps with phase 'query_structure'") {
      val err = QE.QueryStructureError("Bad structure", "range")
      FolQueryFailure.fromQueryError(err) match
        case FolQueryFailure.FolEvaluationFailure(_, phase) =>
          assertTrue(phase == "query_structure")
        case other => throw MatchError(other)
    },
    test("ResourceError maps with phase 'resource'") {
      val err = QE.ResourceError("Out of memory", "thread_pool")
      FolQueryFailure.fromQueryError(err) match
        case FolQueryFailure.FolEvaluationFailure(_, phase) =>
          assertTrue(phase == "resource")
        case other => throw MatchError(other)
    },
    test("DataStoreError maps with phase 'data_store'") {
      val err = QE.DataStoreError("Read failed", "lookup")
      FolQueryFailure.fromQueryError(err) match
        case FolQueryFailure.FolEvaluationFailure(_, phase) =>
          assertTrue(phase == "data_store")
        case other => throw MatchError(other)
    },
    test("PositionOutOfBoundsError maps with phase 'position_bounds'") {
      val err = QE.PositionOutOfBoundsError("Out of bounds", RelationName("leaf"), arity = 1, position = 5)
      FolQueryFailure.fromQueryError(err) match
        case FolQueryFailure.FolEvaluationFailure(_, phase) =>
          assertTrue(phase == "position_bounds")
        case other => throw MatchError(other)
    },
    test("UnboundVariableError maps with phase 'unbound_variable'") {
      val err = QE.UnboundVariableError("x", Set("y", "z"))
      FolQueryFailure.fromQueryError(err) match
        case FolQueryFailure.FolEvaluationFailure(_, phase) =>
          assertTrue(phase == "unbound_variable")
        case other => throw MatchError(other)
    },
    test("ConnectionError maps with phase 'connection'") {
      val err = QE.ConnectionError("Refused", "localhost:8080")
      FolQueryFailure.fromQueryError(err) match
        case FolQueryFailure.FolEvaluationFailure(_, phase) =>
          assertTrue(phase == "connection")
        case other => throw MatchError(other)
    },
    test("ConfigError maps with phase 'config'") {
      val err = QE.ConfigError("Missing key", "timeout")
      FolQueryFailure.fromQueryError(err) match
        case FolQueryFailure.FolEvaluationFailure(_, phase) =>
          assertTrue(phase == "config")
        case other => throw MatchError(other)
    }
  )

end FolQueryFailureFromQueryErrorSpec
