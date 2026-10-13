package com.risquanter.register.http.errors

import zio.json.{DeriveJsonCodec, JsonCodec}

/** A feature a deployment can lack.
  *
  * The browser needs to know which feature is unavailable and nothing else; the
  * operator-facing reason the server holds belongs in the log (ADR-035 §2). One
  * case exists because scenarios are the only capability a deployment can be
  * configured without — an in-memory repository has no store to hold branches.
  */
enum Capability:
  case Scenarios

object Capability:
  given codec: JsonCodec[Capability] = DeriveJsonCodec.gen[Capability]
