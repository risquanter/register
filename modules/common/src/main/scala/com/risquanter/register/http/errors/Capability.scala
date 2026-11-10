package com.risquanter.register.http.errors

import zio.json.{DeriveJsonCodec, JsonCodec}

/** A feature a deployment can lack.
  *
  * The browser needs to know which feature is unavailable and nothing else; the
  * operator-facing reason the server holds belongs in the log (ADR-035 §2). Both
  * cases are features an in-memory repository lacks, because it has no store to
  * hold branches or history.
  */
enum Capability:
  case Scenarios

  /** Reading a tree at a commit, which only a store with history supports. */
  case PinnedReads

object Capability:
  given codec: JsonCodec[Capability] = DeriveJsonCodec.gen[Capability]

  /** The server's own sentence for a log line. Never reaches a client. */
  def describe(capability: Capability): String = capability match
    case Scenarios   => "scenarios need a store with branches"
    case PinnedReads => "reads pinned to a commit need a store with history"
