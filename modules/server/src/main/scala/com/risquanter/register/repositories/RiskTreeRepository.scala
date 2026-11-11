package com.risquanter.register.repositories

import zio.*
import zio.prelude.NonEmptyList
import com.risquanter.register.domain.data.RiskTree
import com.risquanter.register.domain.errors.{
  Core, Diagnostic, ErrorLocation, Failure, RequestHeaderName, RequestQueryParam, TrustZone, ValidationError
}
import com.risquanter.register.domain.data.iron.{TreeId, WorkspaceId, BranchChoice, BranchRef, CommitHash, Revision}
import com.risquanter.register.http.errors.NotFoundKind

/** Repository for RiskTree persistence operations.
  *
  * Every method takes an explicit `wsId: WorkspaceId` as its first parameter
  * so that workspace scoping is visible and compile-time enforced at every call site.
  *
  * Revision model: writes target a `branch: BranchRef`; reads take a
  * `Revision` — `Head(branch)` resolves the branch head once, `At(commit)`
  * pins a specific commit for point-in-time access. There is no default: every
  * call names its target explicitly. Only the Irmin backend supports commit
  * pins and non-main branches; the in-memory backend rejects both with a typed
  * failure rather than silently serving main-branch/head data.
  */
trait RiskTreeRepository {
  def create(wsId: WorkspaceId, riskTree: RiskTree, branch: BranchRef): IO[Failure, RiskTree]
  def update(wsId: WorkspaceId, id: TreeId, op: RiskTree => RiskTree, branch: BranchRef): IO[Failure, RiskTree]
  def delete(wsId: WorkspaceId, id: TreeId, branch: BranchRef): IO[Failure, RiskTree]

  /** Revert a tree to `toCommit` as one forward `set_tree` commit.
    * Reads the tree state at `toCommit` and writes it forward with a `:revert`
    * message; no precondition. Absent target (commit or path) → NotFound. */
  def revert(wsId: WorkspaceId, id: TreeId, toCommit: CommitHash, branch: BranchRef): IO[Failure, RiskTree]

  /** Loads a tree and reports the concrete commit it was read at. The
    * `CommitHash` is the Irmin commit the read resolved to — a whole-tree,
    * byte-level revision, never a domain content hash — and is what scope
    * resolution memoizes on. One read names the head it resolved, so there is
    * no second call and no resolve-then-reload race. */
  def getById(wsId: WorkspaceId, id: TreeId, rev: Revision): IO[Failure, Option[(RiskTree, CommitHash)]]
  /** Every tree in the workspace at `rev`. When the store holds content it
    * refuses for one or more trees, the listing fails with one refusal
    * accumulating every refused tree's entries, each tree named in its
    * diagnostics. Any other failure fails the listing as it is. */
  def getAllForWorkspace(wsId: WorkspaceId, rev: Revision): IO[Failure, List[RiskTree]]
}

object RiskTreeRepository:
  /** Fails with the caller's NotFound for a tree its workspace holds and the
    * revision read lacks. A branch is named as the client-facing choice, which
    * only the site holding `wsId` can derive. A branch that is not one of
    * `wsId`'s branches dies: every branch passed here was composed for `wsId`,
    * so that case cannot hold (ADR-033 §5).
    *
    * {{{
    * treeAbsent(wsId, treeId, Revision.Head(BranchRef.Main))
    * // fails with NotFound at Client: (Header(Branch), TreeNotOnBranch(Main))
    * }}}
    */
  def treeAbsent(wsId: WorkspaceId, treeId: TreeId, rev: Revision): IO[Failure, Nothing] =
    val diagnostics = List(Diagnostic.WorkspaceRef(wsId), Diagnostic.TreeRef(treeId), Diagnostic.RevisionRead(rev))
    def notFound(location: ErrorLocation, kind: NotFoundKind): IO[Failure, Nothing] =
      ZIO.fail(Failure.NotFound(Core(TrustZone.Client, NonEmptyList(location -> kind), diagnostics)))
    rev match
      case Revision.Head(branch) =>
        BranchChoice.fromBranchRef(wsId, branch) match
          case Right(choice) => notFound(ErrorLocation.Header(RequestHeaderName.Branch), NotFoundKind.TreeNotOnBranch(choice))
          case Left(errors)  => ZIO.die(new IllegalStateException(
            s"branch ${branch.toBranchRef} is not a branch of workspace ${wsId.value}: " +
            errors.map(ValidationError.describe).mkString("; ")))
      case Revision.At(_) =>
        notFound(ErrorLocation.QueryParameter(RequestQueryParam.At), NotFoundKind.TreeNotAtCommit())
