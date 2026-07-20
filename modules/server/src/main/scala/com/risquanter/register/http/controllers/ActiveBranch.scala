package com.risquanter.register.http.controllers

import zio.*
import com.risquanter.register.domain.data.iron.{BranchRef, WorkspaceId, WorkspaceKeySecret}
import com.risquanter.register.domain.errors.{AppError, BranchNotInWorkspace}

/** Ownership check for the `X-Active-Branch` header (milestone-2b Phase B item 4b).
  *
  * `main` is a single, store-wide Irmin branch (`dev/irmin-schema.graphql:149`)
  * shared across every workspace, not workspace-local (2026-07-20 security
  * review). A caller-supplied branch is therefore scoping input, not a bare
  * lookup key — the same enumeration-oracle discipline that governs
  * `WorkspaceId` (security.instructions.md) applies here: a branch not owned
  * by the resolved workspace is rejected with the same opaque not-found
  * shape used everywhere else (A13), never a distinguishable "forbidden"
  * response, so probing another workspace's scenario names through this
  * header behaves identically to probing a nonexistent one.
  */
object ActiveBranch:
  def resolve(key: WorkspaceKeySecret, wsId: WorkspaceId, requested: Option[BranchRef]): IO[AppError, Option[BranchRef]] =
    requested match
      case None =>
        ZIO.succeed(None)
      case Some(branch) if BranchRef.belongsTo(branch, wsId) =>
        ZIO.succeed(Some(branch))
      case Some(branch) =>
        ZIO.fail(BranchNotInWorkspace(key, branch))
