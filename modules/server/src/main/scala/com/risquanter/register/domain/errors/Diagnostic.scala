package com.risquanter.register.domain.errors

import java.time.Instant

import zio.prelude.NonEmptyList

import com.risquanter.register.auth.{Permission, ResourceRef}
import com.risquanter.register.domain.data.iron.{CommitHash, Revision, TreeId, UserId, ValidationMessages, WorkspaceId}
import com.risquanter.register.infra.irmin.model.IrminPath

/** One log-only entry on a failure. Each case fixes its log key and the type of
  * its value, so a site cannot log a value under a wrong key or log a value the
  * case does not admit (ADR-037). A confidential identifier may appear here,
  * because this reaches a server log and never the wire (ADR-036).
  *
  * `RawValue`, `PeerMessage` and `PeerBody` hold `String` because each is text a
  * peer produced that this service refused or did not produce, and a log line is
  * its only use (ADR-035 §3).
  */
enum Diagnostic:
  case WorkspaceRef(id: WorkspaceId)
  case TreeRef(id: TreeId)
  case RevisionRead(rev: Revision)
  case ExpiredAt(at: Instant)

  /** The head a compare-and-set expected. */
  case ExpectedHead(hash: CommitHash)

  /** The head the branch held; `None` when the branch has no head. */
  case ActualHead(hash: Option[CommitHash])

  case Principal(user: UserId.Authenticated, permission: Permission, resource: ResourceRef)
  case StoragePath(path: IrminPath)

  /** The segment, hash, interval or counter text that was refused. */
  case RawValue(text: String)

  /** An exception's, driver's or fitter's own text. */
  case PeerMessage(text: String)

  /** An HTTP body this service could not read. */
  case PeerBody(text: String)

  case GraphQlPath(path: List[String])

  /** The entries of the store refusal a conflict was built from: each refused
    * value's location and problem. */
  case StoreRefusal(entries: NonEmptyList[(ErrorLocation, FieldProblem)])

object Diagnostic:
  /** The log key and rendered value of one entry. Total over the enum. The keys
    * are a contract with the log pipeline, so each is declared here once and
    * none follows a case name. */
  def render(d: Diagnostic): List[(String, String)] = d match
    case WorkspaceRef(id)        => List("workspace_id" -> id.value)
    case TreeRef(id)             => List("tree_id" -> id.value)
    case RevisionRead(rev)       => List("revision" -> revision(rev))
    case ExpiredAt(at)           => List("expired_at" -> at.toString)
    case ExpectedHead(hash)      => List("expected_head" -> hash.value)
    case ActualHead(hash)        => List("actual_head" -> hash.fold("none")(_.value))
    case Principal(user, permission, resource) =>
      List(
        "user"          -> user.value,
        "permission"    -> permission.zedName,
        "resource_type" -> resource.resourceType.zedType,
        "resource_id"   -> resource.resourceId.value.toString
      )
    case StoragePath(path)       => List("storage_path" -> path.value)
    case RawValue(text)          => List("raw_value" -> text)
    case PeerMessage(text)       => List("peer_message" -> text)
    case PeerBody(text)          => List("peer_body" -> text)
    case GraphQlPath(path)       => List("graphql_path" -> path.mkString("."))
    case StoreRefusal(entries)   =>
      List("store_refusal" -> entries.toList.map((location, problem) =>
        s"[${ErrorLocation.describe(location)}] ${ValidationMessages.render(problem)}").mkString("; "))

  private def revision(rev: Revision): String = rev match
    case Revision.Head(branch) => s"head of ${branch.toBranchRef}"
    case Revision.At(commit)   => commit.value
