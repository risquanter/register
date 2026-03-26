package com.risquanter.register.http.requests

import zio.json.{DeriveJsonCodec, JsonCodec}
import fol.parser.VagueQueryParser
import fol.logic.ParsedQuery
import fol.error.QueryError

/** Request body for vague-quantifier query evaluation.
  *
  * Contains the raw query string in the FOL vague-quantifier syntax.
  * Parsing and validation happen at the boundary via `resolve()`.
  *
  * @param query FOL query string, e.g. `Q[>=]^{2/3} x (leaf(x), >(p95(x), 5000000))`
  */
final case class QueryRequest(query: String)

object QueryRequest:
  given JsonCodec[QueryRequest] = DeriveJsonCodec.gen

  /** Inbound parse boundary (ADR-001 §1 — parse-don't-validate).
    *
    * Wraps `VagueQueryParser.parse()` — the library's syntactic parser.
    * Returns the library's `fol.error.QueryError` on failure (not register's
    * `FolQueryFailure`); the controller maps the error type at the wiring
    * layer, keeping this DTO free of `AppError` dependencies.
    *
    * Analogous to `RiskTreeDefinitionRequest.resolve()` returning
    * `Validation[ValidationError, ResolvedCreate]` — the DTO owns the
    * parse step, the controller owns the error mapping.
    */
  def resolve(req: QueryRequest): Either[QueryError, ParsedQuery] =
    VagueQueryParser.parse(req.query)
