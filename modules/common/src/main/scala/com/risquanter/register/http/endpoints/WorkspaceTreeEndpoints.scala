package com.risquanter.register.http.endpoints

import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.json.zio.*
import sttp.tapir.generic.auto.schemaForCaseClass
import io.github.iltotore.iron.autoRefine

import com.risquanter.register.http.requests.{RiskTreeUpdateRequest, RevertTreeRequest}
import com.risquanter.register.http.responses.{TreeSummaryResponse, ChangedNodesResponse, TreeHistoryResponse}
import com.risquanter.register.domain.data.RiskTree
import com.risquanter.register.domain.data.iron.{CommitHash, BranchChoice, PositiveInt}
import com.risquanter.register.http.codecs.IronTapirCodecs.given

/** Workspace-scoped tree endpoints.
  *
  * All tree-specific operations are served exclusively under `/w/{key}/...`
  * to enforce workspace capability checks.
  *
  * Reads and writes carry a required `X-Branch` header naming the target
  * branch (`"main"` or a scenario name); absence is a 400. Reads additionally
  * accept an optional `at` commit pin for point-in-time access — absent = the
  * branch head. The header never carries a raw Irmin branch string: the
  * controller composes the actual branch from this name and the caller's own
  * resolved `WorkspaceId`, so a scenario name from another workspace can never
  * resolve to that workspace's branch — see `ActiveBranch.resolve`.
  *
  * A tree the workspace holds but the revision lacks answers `404` with
  * `NotFoundKind.TreeNotOnBranch` or `TreeNotAtCommit`, on every read here.
  */
trait WorkspaceTreeEndpoints extends BaseEndpoint:

  val getWorkspaceTreeByIdEndpoint =
    authedBaseEndpoint
      .tag("workspaces")
      .name("getWorkspaceTreeById")
      .description("Get tree summary (workspace-scoped)")
      .in(treeRoute)
      .get
      .in(branchHeader)
      .out(jsonBody[TreeSummaryResponse])

  val getWorkspaceTreeStructureEndpoint =
    authedBaseEndpoint
      .tag("workspaces")
      .name("getWorkspaceTreeStructure")
      .description("Get full tree structure (workspace-scoped); optional `at` commit pin for point-in-time reads")
      .in(treeRoute / "structure")
      .get
      .in(branchHeader)
      .in(query[Option[CommitHash]]("at").description("Commit pin for point-in-time read — absent = branch head."))
      .out(jsonBody[RiskTree])

  /** Content-hash changed-nodes comparison between two symmetric, explicit
    * revisions — no value-level comparison. Each side is a
    * `(branch, optional commit pin)`: `a`/`aAt` and `b`/`bAt`. No `X-Branch`
    * header — the two sides are the operands, given explicitly as query
    * parameters. Compare-to-current from history is `a = branch head`,
    * `b = same branch, bAt = <hash>`.
    */
  val getChangedNodesEndpoint =
    authedBaseEndpoint
      .tag("workspaces")
      .name("getChangedNodes")
      .description("Content-hash changed nodes of a tree between two revisions — no value-level comparison")
      .in(treeRoute / "changed-nodes")
      .get
      .in(query[BranchChoice]("a"))
      .in(query[Option[CommitHash]]("aAt"))
      .in(query[BranchChoice]("b"))
      .in(query[Option[CommitHash]]("bAt"))
      .out(jsonBody[ChangedNodesResponse])

  /** Per-tree commit history on a branch. Typed entries only — the raw
    * commit message (which embeds the WorkspaceId) never leaves the server.
    * `n` is refined by its codec, so the handler receives a positive count.
    */
  val getTreeHistoryEndpoint =
    authedBaseEndpoint
      .tag("workspaces")
      .name("getTreeHistory")
      .description("Per-tree commit history on a branch, oldest first")
      .in(treeRoute / "history")
      .get
      .in(branchHeader)
      .in(query[PositiveInt]("n").default(WorkspaceTreeEndpoints.DefaultHistoryLimit))
      .out(jsonBody[TreeHistoryResponse])

  /** Revert a tree to a chosen commit. A forward write: reads the tree
    * at `toCommit` and writes it as one new commit on the branch; no
    * precondition, last write wins; superseded edits remain in history.
    */
  val revertTreeEndpoint =
    authedBaseEndpoint
      .tag("workspaces")
      .name("revertTree")
      .description("Revert a tree to a chosen commit as one forward commit (per-tree)")
      .in(treeRoute / "revert")
      .post
      .in(branchHeader)
      .in(jsonBody[RevertTreeRequest])
      .out(jsonBody[TreeSummaryResponse])

  val updateWorkspaceTreeEndpoint =
    authedBaseEndpoint
      .tag("workspaces")
      .name("updateWorkspaceTree")
      .description("Full replacement update of tree (workspace-scoped)")
      .in(treeRoute)
      .put
      .in(jsonBody[RiskTreeUpdateRequest])
      .in(branchHeader)
      .out(jsonBody[TreeSummaryResponse])

  /** A delete answers `204` with no body: the tree is gone, so there is
    * nothing left to describe. */
  val deleteWorkspaceTreeEndpoint =
    authedBaseEndpoint
      .tag("workspaces")
      .name("deleteWorkspaceTree")
      .description("Delete a single tree from workspace")
      .in(treeRoute)
      .delete
      .in(branchHeader)
      .out(statusCode(StatusCode.NoContent))

object WorkspaceTreeEndpoints:
  /** How many history entries a read returns when the caller names no `n`.
    * Chosen by this project as one screen of history. */
  val DefaultHistoryLimit: PositiveInt = 50
