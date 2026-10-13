package com.risquanter.register.http.errors

import zio.json.{DeriveJsonCodec, JsonCodec, jsonDiscriminator}

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
@jsonDiscriminator("kind")
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
  given codec: JsonCodec[QueryProblem] = DeriveJsonCodec.gen[QueryProblem]
