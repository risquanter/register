package com.risquanter.register.http.errors

import sttp.tapir.Schema
import sttp.tapir.generic.Configuration
import zio.json.{DeriveJsonCodec, JsonCodec, jsonDiscriminator}
import com.risquanter.register.domain.data.iron.BranchChoice
import com.risquanter.register.http.codecs.IronTapirCodecs.given

/** Which absence a `404` reports.
  *
  * Every absence answers `404`, and the browser selects a wire case by status
  * alone, so every absence shares one wire case and this enumeration
  * discriminates between them. The two tree cases are reached only after the
  * workspace key has resolved and the workspace has been found to hold the
  * tree, so neither answers for another workspace's tree. The four store cases
  * describe content a peer returned; they are logged and answer as an internal
  * fault, so no client is sent one.
  */
@jsonDiscriminator(NotFoundKind.TagField)
enum NotFoundKind:
  /** No such resource, or one the caller may not see. One case for both, so
    * neither is an existence oracle (ADR-035 §3). */
  case Unknown()

  /** A tree the workspace holds, absent at the head of this branch. */
  case TreeNotOnBranch(branch: BranchChoice)

  /** A tree the workspace holds, absent at the commit the read named. The
    * store reads a commit it does not hold the same way, so this discloses
    * nothing about which commits exist. */
  case TreeNotAtCommit()

  /** A store listing named a value and the store does not hold it. */
  case StoredValueAbsent()

  /** A tree has nodes under it and no metadata. */
  case MetadataAbsent()

  /** A tree has metadata and no nodes. */
  case NodesAbsent()

  /** The branch exists and has no head commit. */
  case BranchHeadAbsent()

object NotFoundKind:
  /** The key naming which absence a payload states. Read by this coproduct's
    * decoder and published in its schema, and stated here once so the two
    * cannot disagree. Each case's published value is its own name, which is
    * what the derived codec writes. */
  final val TagField = "kind"

  given codec: JsonCodec[NotFoundKind] = DeriveJsonCodec.gen[NotFoundKind]

  /** Declared rather than left to a holder's derivation, so the payload's
    * published description is the cases rather than an unconstrained
    * object, and names the key that decides between them. */
  given schema: Schema[NotFoundKind] =
    given Configuration = Configuration.default.withDiscriminator(TagField)
    Schema.derived[NotFoundKind]

  /** The server's own sentence for a log line. Never reaches a client. */
  def describe(kind: NotFoundKind): String = kind match
    case Unknown()               => "no such resource"
    case TreeNotOnBranch(branch) => s"the tree is absent at the head of ${branchName(branch)}"
    case TreeNotAtCommit()       => "the tree is absent at the commit read"
    case StoredValueAbsent()     => "the store listed a value it does not hold"
    case MetadataAbsent()        => "the tree has nodes and no metadata"
    case NodesAbsent()           => "the tree has metadata and no nodes"
    case BranchHeadAbsent()      => "the branch has no head commit"

  private def branchName(branch: BranchChoice): String = branch match
    case BranchChoice.Main           => "main"
    case BranchChoice.Scenario(name) => s"scenario ${name.value}"
