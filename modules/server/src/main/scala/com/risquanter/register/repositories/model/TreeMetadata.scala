package com.risquanter.register.repositories.model

import java.time.Instant
import zio.json.*
import com.risquanter.register.domain.data.iron.{TreeId, NodeId, SafeName}
import com.risquanter.register.domain.data.RiskTree.{safeNameDecoder, safeNameEncoder}

/** Metadata persisted for each risk tree under risk-trees/{treeId}/meta. */
final case class TreeMetadata(
  id: TreeId,
  name: SafeName.SafeName,
  rootId: NodeId,
  schemaVersion: Int,
  createdAt: Instant,
  updatedAt: Instant
)

object TreeMetadata:
  private given JsonCodec[Instant] = JsonCodec[Long].transform(Instant.ofEpochMilli(_), _.toEpochMilli)
  given JsonCodec[TreeMetadata] = DeriveJsonCodec.gen[TreeMetadata]
