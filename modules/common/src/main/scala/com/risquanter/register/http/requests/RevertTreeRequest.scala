package com.risquanter.register.http.requests

import zio.json.{DeriveJsonCodec, JsonCodec}
import com.risquanter.register.domain.data.iron.CommitHash

/** Request body for `POST /w/{key}/risk-trees/{treeId}/revert`. Names the
  * commit whose tree state is written forward as one new revert commit (E3/E4;
  * per-tree granularity, E8). No precondition — last write wins.
  */
final case class RevertTreeRequest(toCommit: CommitHash)

object RevertTreeRequest:
  given codec: JsonCodec[RevertTreeRequest] = DeriveJsonCodec.gen[RevertTreeRequest]
