package com.risquanter.register.http.requests

import zio.json.{DeriveJsonCodec, JsonCodec}
import zio.prelude.NonEmptyList
import vql.parser.VagueQueryParser
import vql.logic.ParsedQuery

import com.risquanter.register.domain.data.iron.QueryVariable
import com.risquanter.register.http.errors.{Positioned, QueryPosition, QueryProblem}

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
    * Parses the text with the engine's `VagueQueryParser.parse` and refuses a
    * query the service cannot answer: text the parser refused, and a query
    * naming answer variables, because the service evaluates yes-or-no queries
    * only. The controller turns the refusal into a failure.
    *
    * The parser returns only refusals of the text, and it accepts a variable
    * only under `QueryVariable`'s rule, so either other outcome is a defect and
    * throws.
    *
    * {{{
    * resolve(QueryRequest("Q[>=]^{1/2} x (leaf(x), leaf(x)) (y)"))
    * // Left(AnswerVariablesNotSupported(NonEmptyList(Positioned(Unknown(), y))))
    * }}}
    */
  def resolve(req: QueryRequest): Either[QueryProblem, ParsedQuery] =
    VagueQueryParser.parse(req.query) match
      case Left(err) =>
        Left(QueryProblem.fromQueryError(err).getOrElse(
          throw new IllegalStateException(s"The query parser returned an error that refuses no text: ${err.message}")))
      case Right(parsed) =>
        parsed.answerVars match
          case Nil          => Right(parsed)
          case head :: tail =>
            Left(QueryProblem.AnswerVariablesNotSupported(
              NonEmptyList(head, tail*).map(name => Positioned(QueryPosition.Unknown(), answerVariable(name)))))

  /** A name the parser accepted as a variable, as the variable it is. */
  private def answerVariable(name: String): QueryVariable.QueryVariable =
    QueryVariable.fromString(name).fold(
      reason => throw new IllegalStateException(s"The query parser accepted a variable outside QueryVariable's rule: $reason"),
      identity)
