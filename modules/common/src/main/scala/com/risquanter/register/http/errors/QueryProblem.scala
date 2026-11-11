package com.risquanter.register.http.errors

import sttp.tapir.Schema
import sttp.tapir.generic.Configuration
import zio.json.{DeriveJsonCodec, JsonCodec, jsonDiscriminator}
import vql.error.{BindErrorDetail, QueryError}

import com.risquanter.register.domain.errors.FolQueryFailure

/** Why the engine refused a well-formed query.
  *
  * The engine's rendered messages are the useful content here and are written
  * for the person who typed the query, so this is an actionable failure: the
  * payload crosses and the browser puts it in the query pane (ADR-028).
  *
  * Two query failures are deliberately absent. A model-validation failure and an
  * evaluation failure are faults in how the knowledge base is wired rather than
  * problems with the query, so they project to `WireError.InternalFault`.
  */
@jsonDiscriminator(QueryProblem.TagField)
enum QueryProblem:
  /** Syntax the parser rejected. `position` is the character offset the parser
    * reported, when it reported one.
    */
  case Unparseable(message: String, position: Option[Int])

  /** Names the query used that are not in the tree, one message each. */
  case UnknownReferences(messages: List[String])

  /** Type-check failures from the bind phase, one message each. */
  case TypeCheckFailed(messages: List[String])

  /** A quantifier ranging over a type with no enumerable domain. */
  case NotQuantifiable(message: String)

object QueryProblem:
  /** The key naming which refusal a payload states. Read by this coproduct's
    * decoder and published in its schema, and stated here once so the two
    * cannot disagree. Each case's published value is its own name, which is
    * what the derived codec writes. */
  final val TagField = "kind"

  given codec: JsonCodec[QueryProblem] = DeriveJsonCodec.gen[QueryProblem]

  /** Declared rather than left to a holder's derivation, so the payload's
    * published description is the four cases rather than an unconstrained
    * object, and names the key that decides between them. */
  given schema: Schema[QueryProblem] =
    given Configuration = Configuration.default.withDiscriminator(TagField)
    Schema.derived[QueryProblem]

  /** The sentence for a refused query: the query pane shows it, and a server
    * log line records it. The engine's messages are written for the person who
    * typed the query, so they are carried as they are. */
  def describe(problem: QueryProblem): String = problem match
    case Unparseable(message, position) =>
      position.fold(message)(p => s"$message (at character $p)")
    case UnknownReferences(messages) =>
      s"The query names something that is not in this tree: ${messages.mkString("; ")}"
    case TypeCheckFailed(messages) =>
      s"The query does not type-check: ${messages.mkString("; ")}"
    case NotQuantifiable(message) =>
      message

  /** The refusal the caller can act on, when the engine's error is one.
    *
    * `None` for a model that does not satisfy its catalog and for a failure
    * after binding succeeded: those are faults in how this service wires the
    * knowledge base, so nothing about the query would fix them.
    *
    * A bind failure whose every detail is a quoted literal of a node-reference
    * sort names a node the tree does not hold, so it is `UnknownReferences`.
    * Any other bind detail is a type error and makes the whole failure
    * `TypeCheckFailed`. The engine raises `ValidationError` from the query's own
    * variable rules, so it is the caller's text being refused.
    *
    * {{{
    * fromQueryError(QueryError.ParseError("Expected '}'", "Q[>=]^{2/3 x", Some(14)))
    * // Some(Unparseable("Expected '}'", Some(14)))
    * }}}
    */
  def fromQueryError(err: QueryError): Option[QueryProblem] = err match
    case e: QueryError.ParseError                    => Some(Unparseable(e.message, e.position))
    case e: QueryError.ValidationError               => Some(Unparseable(e.message, None))
    case e: QueryError.UnknownConstantOrLiteralError => Some(UnknownReferences(List(e.message)))
    case e: QueryError.BindError                     =>
      val allNodeUnresolved =
        e.details.nonEmpty && e.details.forall {
          case BindErrorDetail.UnparseableConstant(_, sortName, _, _) =>
            FolQueryFailure.NodeReferenceSortNames.contains(sortName)
          case _ => false
        }
      Some(if allNodeUnresolved then UnknownReferences(e.messages) else TypeCheckFailed(e.messages))
    case e: QueryError.DomainNotFoundError           => Some(NotQuantifiable(notEnumerable(e.typeName, e.availableTypes)))
    case _: QueryError.ModelValidationError          => None
    case _: QueryError.EvaluationError               => None
    case _: QueryError.UnboundVariableError          => None

  /** The sentence for a quantifier over a type with no enumerable domain. */
  private def notEnumerable(typeName: String, availableTypes: Iterable[String]): String =
    s"Queries can only range over tree nodes (type 'Asset'). " +
      s"The type '$typeName' cannot be enumerated. " +
      s"Available quantifiable types: ${availableTypes.mkString(", ")}"
