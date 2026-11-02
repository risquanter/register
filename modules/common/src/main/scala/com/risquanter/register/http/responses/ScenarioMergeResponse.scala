package com.risquanter.register.http.responses

import sttp.tapir.Schema
import sttp.tapir.generic.Configuration
import zio.json.{DeriveJsonCodec, JsonCodec, jsonDiscriminator}
import com.risquanter.register.domain.data.{Mitigation, RiskNode}
import com.risquanter.register.domain.data.RiskTree.{safeNameEncoder, safeNameDecoder}
import com.risquanter.register.domain.data.iron.{CommitHash, MitigationId, NodeId, SafeName, SeedVarId, TreeId}
import com.risquanter.register.domain.errors.FieldName

/** The fields of a tree's `meta` value that a merge compares. The save time is
  * not among them: every save rewrites it, so two branches that both saved a
  * tree would always differ there. */
final case class TreeMetadataFields(
  name: SafeName.SafeName,
  rootId: NodeId,
  seedVarHighWater: SeedVarId.SeedVarId
)

object TreeMetadataFields:
  given codec: JsonCodec[TreeMetadataFields] = DeriveJsonCodec.gen[TreeMetadataFields]
  given schema: Schema[TreeMetadataFields] =
    import com.risquanter.register.http.codecs.IronTapirCodecs.given
    Schema.derived[TreeMetadataFields]

/** One node, one mitigation or one tree's `meta` fields at the three points a
  * merge compares: `base` at the fork, then on each branch. `None` means it did
  * not exist at that point. The same
  * triple is what `MergeConflictRule` decides over. */
final case class Versions[A](base: Option[A], onMain: Option[A], onScenario: Option[A])

object Versions:
  given codec[A: JsonCodec]: JsonCodec[Versions[A]] = DeriveJsonCodec.gen[Versions[A]]
  given schema[A: Schema]: Schema[Versions[A]] = Schema.derived[Versions[A]]

  extension [A](versions: Versions[A])
    /** The same function applied at the fork and on both branches; an absent
      * version stays absent. A functor: `map(identity)` is the same value, and
      * `map(f).map(g)` equals `map(f andThen g)`. */
    def map[B](f: A => B): Versions[B] =
      Versions(versions.base.map(f), versions.onMain.map(f), versions.onScenario.map(f))

    /** `map` for a function that can fail: the first failure, or every
      * version converted. With a function that never fails it equals `map`.
      *
      * {{{
      * Versions(Some("1"), None, Some("x")).traverse(_.toIntOption.toRight("bad")) // Left("bad")
      * }}}
      */
    def traverse[E, B](f: A => Either[E, B]): Either[E, Versions[B]] =
      def one(version: Option[A]): Either[E, Option[B]] =
        version match
          case Some(a) => f(a).map(Some(_))
          case None    => Right(None)
      for
        base       <- one(versions.base)
        onMain     <- one(versions.onMain)
        onScenario <- one(versions.onScenario)
      yield Versions(base, onMain, onScenario)

/** A tree, named for the user by its name at the fork and on each branch.
  * `None` means the tree does not exist at that point. */
final case class TreeLabel(id: TreeId, names: Versions[SafeName.SafeName])

object TreeLabel:
  /** `Versions` derives over a codec, and `SafeName` provides its encoder and
    * decoder separately. */
  private given JsonCodec[SafeName.SafeName] = JsonCodec(safeNameEncoder, safeNameDecoder)
  given codec: JsonCodec[TreeLabel] = DeriveJsonCodec.gen[TreeLabel]
  given schema: Schema[TreeLabel] =
    import com.risquanter.register.http.codecs.IronTapirCodecs.given
    Schema.derived[TreeLabel]

/** The Irmin contents at one path, which both branches changed since the fork
  * into versions that differ. The tree, node or mitigation's name on each
  * branch is read from `versions`. `fields` lists the fields that differ
  * between main and the scenario. When one branch deleted the contents, it
  * lists the fields the other branch changed since the
  * fork.
  *
  * Every entry is workspace-relative and carries no `WorkspaceId` (ADR-036).
  */
@jsonDiscriminator(MergeConflictEntry.TagField)
enum MergeConflictEntry:
  case TreeMetadataConflict(
    treeId: TreeId,
    versions: Versions[TreeMetadataFields],
    fields: List[FieldName]
  )
  case NodeConflict(
    tree: TreeLabel,
    nodeId: NodeId,
    versions: Versions[RiskNode],
    fields: List[FieldName]
  )
  case MitigationConflict(
    tree: TreeLabel,
    mitigationId: MitigationId,
    versions: Versions[Mitigation],
    fields: List[FieldName]
  )

object MergeConflictEntry:
  /** The key naming which case a payload states. Read by this coproduct's
    * decoder and published in its schema, and stated here once so the two
    * cannot disagree. */
  final val TagField = "kind"
  given codec: JsonCodec[MergeConflictEntry] = DeriveJsonCodec.gen[MergeConflictEntry]
  given schema: Schema[MergeConflictEntry] =
    import sttp.tapir.generic.auto.*
    import com.risquanter.register.http.codecs.IronTapirCodecs.given
    given Configuration = Configuration.default.withDiscriminator(TagField)
    Schema.derived[MergeConflictEntry]

/** The outcome of a merge preview, as a closed set.
  *
  * Each case carries the values that belong to it, so there is no case whose
  * payload lives in a sibling field that may or may not be populated. A match on
  * this type is exhaustive, which is what lets the browser drop the wildcard arm
  * that renders an unrecognised status as an empty conflict list.
  */
@jsonDiscriminator(MergePreviewStatus.TagField)
enum MergePreviewStatus:
  /** The merge would apply with no intervention. */
  case Clean()

  /** Irmin contents both branches changed since the fork into versions that
    * differ. */
  case Conflicts(entries: List[MergeConflictEntry])

  /** The merged node set would repeat a name within a tree, listed per tree. */
  case DuplicateNames(perTree: Map[TreeId, List[SafeName.SafeName]])

  /** The scenario does not exist — a non-error outcome of a read-only preview,
    * mirroring `ScenarioDiffResponse`'s missing-tree statuses.
    */
  case ScenarioMissing()

object MergePreviewStatus:
  /** The key naming which outcome a response states. Read by this coproduct's
    * decoder and published in its schema, and stated here once so the two
    * cannot disagree. Each case's published value is its own name, which is
    * what the derived codec writes. */
  final val TagField = "kind"

  given codec: JsonCodec[MergePreviewStatus] = DeriveJsonCodec.gen[MergePreviewStatus]

  /** Declared rather than left to the holder's derivation, so the response's
    * published description is the four outcomes rather than an unconstrained
    * object, and names the key that decides between them. */
  given schema: Schema[MergePreviewStatus] =
    import sttp.tapir.generic.auto.*
    import com.risquanter.register.http.codecs.IronTapirCodecs.given
    given Configuration = Configuration.default.withDiscriminator(TagField)
    Schema.derived[MergePreviewStatus]

/** Response DTO for `GET /w/{key}/scenarios/{name}/merge-preview`. */
final case class MergePreviewResponse(status: MergePreviewStatus)

object MergePreviewResponse:
  given codec: JsonCodec[MergePreviewResponse] = DeriveJsonCodec.gen[MergePreviewResponse]

/** Response DTO for `POST /w/{key}/scenarios/{name}/merge`: main's new head
  * commit after the merge.
  */
final case class MergeScenarioResponse(mergeCommit: CommitHash)

object MergeScenarioResponse:
  given codec: JsonCodec[MergeScenarioResponse] = DeriveJsonCodec.gen[MergeScenarioResponse]
