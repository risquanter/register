package com.risquanter.register.http.responses

import zio.json.{DeriveJsonCodec, JsonCodec}
import sttp.tapir.Schema

import com.risquanter.register.domain.data.{RiskTree, ScopeOutcome}
import com.risquanter.register.domain.data.iron.MitigationId
import com.risquanter.register.http.codecs.IronTapirCodecs.given

/** One mitigation beside its resolution against the tree version this read
  * returned. `Resolved` with an empty scope is a predicate that bound correctly
  * and matched nothing — a different situation from `Failed`, and the interface
  * says so differently.
  */
final case class MitigationScopeView(mitigationId: MitigationId, outcome: ScopeOutcome)

object MitigationScopeView:
  given codec: JsonCodec[MitigationScopeView] = DeriveJsonCodec.gen[MitigationScopeView]
  given schema: Schema[MitigationScopeView]   = Schema.derived[MitigationScopeView]

/** The structure read: the tree, each mitigation's resolved scope for this
  * version, and the overrides whose stored base stamp no longer matches the
  * leaf they were authored against.
  *
  * The scopes travel beside the tree rather than inside it. `RiskTree` is the
  * persisted content type — what a PUT carries and what the store holds — so a
  * server-computed, per-version resolution placed inside it would persist
  * derived mitigation state and would claim a version-independence it does not
  * have.
  */
final case class TreeStructureResponse(
  tree: RiskTree,
  mitigationScopes: List[MitigationScopeView],
  staleMitigationIds: Set[MitigationId]
)

object TreeStructureResponse:
  given codec: JsonCodec[TreeStructureResponse] = DeriveJsonCodec.gen[TreeStructureResponse]
  given schema: Schema[TreeStructureResponse]   = Schema.derived[TreeStructureResponse]
