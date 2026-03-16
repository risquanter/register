package com.risquanter.register.http.requests

import zio.json.{DeriveJsonCodec, JsonCodec}

/** Request body for vague-quantifier query evaluation.
  *
  * Contains the raw query string in the FOL vague-quantifier syntax.
  * Parsing and validation happen server-side in `QueryService`.
  *
  * @param query FOL query string, e.g. `Q[>=]^{2/3} x (leaf(x), >(p95(x), 5000000))`
  */
final case class QueryRequest(query: String)

object QueryRequest:
  given JsonCodec[QueryRequest] = DeriveJsonCodec.gen
