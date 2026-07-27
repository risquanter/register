package com.risquanter.register.http.endpoints

import sttp.tapir.*
import sttp.tapir.json.zio.*
import sttp.tapir.generic.auto.*

import com.risquanter.register.http.requests.{RiskTreeUpdateRequest, RevertTreeRequest}
import com.risquanter.register.http.responses.{SimulationResponse, ChangedNodesResponse, TreeHistoryResponse}
import com.risquanter.register.domain.data.RiskTree
import com.risquanter.register.domain.data.iron.{WorkspaceKeySecret, TreeId, CommitHash, BranchChoice}
import com.risquanter.register.http.codecs.IronTapirCodecs.given

/** Workspace-scoped tree endpoints.
  *
  * All tree-specific operations are served exclusively under `/w/{key}/...`
  * to enforce workspace capability checks.
  *
  * Reads and writes carry a required `X-Branch` header (E7) naming the target
  * branch (`"main"` or a scenario name); absence is a 400. Reads additionally
  * accept an optional `at` commit pin for point-in-time access — absent = the
  * branch head. The header never carries a raw Irmin branch string: the
  * controller composes the actual branch from this name and the caller's own
  * resolved `WorkspaceId`, so a scenario name from another workspace can never
  * resolve to that workspace's branch — see `ActiveBranch.resolve`.
  */
trait WorkspaceTreeEndpoints extends BaseEndpoint:

  val getWorkspaceTreeByIdEndpoint =
    authedBaseEndpoint
      .tag("workspaces")
      .name("getWorkspaceTreeById")
      .description("Get tree summary (workspace-scoped)")
      .in("w" / path[WorkspaceKeySecret]("key") / "risk-trees" / path[TreeId]("treeId"))
      .get
      .in(branchHeader)
      .out(jsonBody[Option[SimulationResponse]])

  val getWorkspaceTreeStructureEndpoint =
    authedBaseEndpoint
      .tag("workspaces")
      .name("getWorkspaceTreeStructure")
      .description("Get full tree structure (workspace-scoped); optional `at` commit pin for point-in-time reads")
      .in("w" / path[WorkspaceKeySecret]("key") / "risk-trees" / path[TreeId]("treeId") / "structure")
      .get
      .in(branchHeader)
      .in(query[Option[CommitHash]]("at").description("Commit pin for point-in-time read — absent = branch head."))
      .out(jsonBody[Option[RiskTree]])

  /** Content-hash changed-nodes comparison (UC5) between two symmetric,
    * explicit revisions — no value-level comparison (DD-6). Each side is a
    * `(branch, optional commit pin)`: `a`/`aAt` and `b`/`bAt`. No `X-Branch`
    * header — the two sides are the operands, given explicitly as query
    * parameters. Compare-to-current from history is `a = branch head`,
    * `b = same branch, bAt = <hash>`.
    */
  val getChangedNodesEndpoint =
    authedBaseEndpoint
      .tag("workspaces")
      .name("getChangedNodes")
      .description("Content-hash changed nodes of a tree between two revisions (UC5) — no value-level comparison")
      .in("w" / path[WorkspaceKeySecret]("key") / "risk-trees" / path[TreeId]("treeId") / "changed-nodes")
      .get
      .in(query[BranchChoice]("a"))
      .in(query[Option[CommitHash]]("aAt"))
      .in(query[BranchChoice]("b"))
      .in(query[Option[CommitHash]]("bAt"))
      .out(jsonBody[ChangedNodesResponse])

  /** Per-tree commit history on a branch (E1). Typed entries only — the raw
    * commit message (which embeds the WorkspaceId) never leaves the server.
    */
  val getTreeHistoryEndpoint =
    authedBaseEndpoint
      .tag("workspaces")
      .name("getTreeHistory")
      .description("Per-tree commit history on a branch, oldest first")
      .in("w" / path[WorkspaceKeySecret]("key") / "risk-trees" / path[TreeId]("treeId") / "history")
      .get
      .in(branchHeader)
      .in(query[Int]("n").default(50).validate(Validator.min(1)))
      .out(jsonBody[TreeHistoryResponse])

  /** Revert a tree to a chosen commit (E3/E4). A forward write: reads the tree
    * at `toCommit` and writes it as one new commit on the branch; no
    * precondition, last write wins; superseded edits remain in history.
    */
  val revertTreeEndpoint =
    authedBaseEndpoint
      .tag("workspaces")
      .name("revertTree")
      .description("Revert a tree to a chosen commit as one forward commit (per-tree)")
      .in("w" / path[WorkspaceKeySecret]("key") / "risk-trees" / path[TreeId]("treeId") / "revert")
      .post
      .in(branchHeader)
      .in(jsonBody[RevertTreeRequest])
      .out(jsonBody[SimulationResponse])

  val updateWorkspaceTreeEndpoint =
    authedBaseEndpoint
      .tag("workspaces")
      .name("updateWorkspaceTree")
      .description("Full replacement update of tree (workspace-scoped)")
      .in("w" / path[WorkspaceKeySecret]("key") / "risk-trees" / path[TreeId]("treeId"))
      .put
      .in(jsonBody[RiskTreeUpdateRequest])
      .in(branchHeader)
      .out(jsonBody[SimulationResponse])

  val deleteWorkspaceTreeEndpoint =
    authedBaseEndpoint
      .tag("workspaces")
      .name("deleteWorkspaceTree")
      .description("Delete a single tree from workspace")
      .in("w" / path[WorkspaceKeySecret]("key") / "risk-trees" / path[TreeId]("treeId"))
      .delete
      .in(branchHeader)
      .out(jsonBody[SimulationResponse])
