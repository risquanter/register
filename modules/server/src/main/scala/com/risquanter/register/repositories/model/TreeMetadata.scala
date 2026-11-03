package com.risquanter.register.repositories.model

import java.time.Instant
import zio.json.*
import com.risquanter.register.domain.data.iron.{TreeId, NodeId, SafeName, SeedVarId}
import com.risquanter.register.http.responses.TreeMetadataFields

/** Metadata persisted for each risk tree under risk-trees/{treeId}/meta. */
final case class TreeMetadata(
  id: TreeId,
  name: SafeName.SafeName,
  rootId: NodeId,
  seedVarHighWater: SeedVarId.SeedVarId,
  schemaVersion: Int,
  createdAt: Instant,
  updatedAt: Instant
):
  /** The fields a merge compares. `updatedAt` is not among them, because every
    * save rewrites it. */
  def toMergeFields: TreeMetadataFields = TreeMetadataFields(name, rootId, seedVarHighWater)

object TreeMetadata:
  /** The `meta` format this server writes. Both the repository and the merge
    * write the `meta` value, so the version lives with the type.
    *
    * Version 2: nodes carry `seedVarId` and `meta` carries `seedVarHighWater`.
    * There is no decode path for version 1: such a store is wiped rather than
    * migrated. */
  val CurrentSchemaVersion: Int = 2

  private given JsonCodec[Instant] = JsonCodec[Long].transform(Instant.ofEpochMilli(_), _.toEpochMilli)
  given JsonCodec[TreeMetadata] = DeriveJsonCodec.gen[TreeMetadata]
