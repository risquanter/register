package com.risquanter.register.http.requests

import zio.json.{DeriveJsonCodec, JsonCodec}
import vql.parser.VagueQueryParser
import vql.logic.ParsedQuery
import vql.error.QueryError

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
    * Wraps `VagueQueryParser.parse()`, the library's syntactic parser, and
    * returns the library's `vql.error.QueryError` on failure. The controller
    * turns that error into a failure, so this DTO depends on no failure type.
    *
    * Analogous to `RiskTreeDefinitionRequest.resolve()` returning
    * `Validation[ValidationError, ResolvedCreate]` — the DTO owns the
    * parse step, the controller owns the error mapping.
    */
  def resolve(req: QueryRequest): Either[QueryError, ParsedQuery] =
    VagueQueryParser.parse(req.query)
