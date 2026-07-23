package com.risquanter.register.http.controllers

import zio.*
import com.risquanter.register.domain.data.iron.{BranchChoice, BranchRef, WorkspaceId}

/** Resolves the `X-Active-Branch` header (milestone-2b Phase B item 4b) into
  * the branch to operate on.
  *
  * The header carries a `ScenarioName`, never a raw branch string — the
  * server composes the actual Irmin branch reference from the caller's own
  * server-resolved `WorkspaceId` and the supplied name (`BranchRef.scenario`).
  * The client never supplies, and this method never reads, any
  * workspace-identifying value, so the composed branch always belongs to the
  * caller's own workspace by construction: there is nothing here to reject
  * as "wrong workspace" (2026-07-20/21 security review) — that failure mode
  * is structurally inexpressible, not just checked and rejected. A named
  * scenario that doesn't exist is a distinct, ordinary not-found condition,
  * already handled by the underlying branch lookup returning `None`.
  */
object ActiveBranch:
  /** Total since the BranchChoice consolidation (TODO item 22): the boundary
    * already normalized the header into the single internal spelling, and
    * every request targets a definite branch — main included — so the result
    * is a definite `BranchRef`, never an `Option` whose absence could be
    * confused with "main".
    */
  def resolve(wsId: WorkspaceId, requested: BranchChoice): UIO[BranchRef] =
    requested match
      case BranchChoice.Main => ZIO.succeed(BranchRef.Main)
      case BranchChoice.Scenario(name) =>
        BranchRef.scenario(wsId, name) match
          case Right(branch) => ZIO.succeed(branch)
          case Left(errors) =>
            ZIO.die(new IllegalStateException(
              s"composed branch for workspace ${wsId.value} + scenario '${name.value}' failed BranchRef validation: $errors — " +
              "unreachable given a valid WorkspaceId + ScenarioName"
            ))
