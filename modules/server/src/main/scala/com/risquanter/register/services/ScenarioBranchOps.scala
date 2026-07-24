package com.risquanter.register.services

import zio.*
import com.risquanter.register.domain.data.iron.{WorkspaceId, ScenarioName, BranchRef, CommitHash}

/** Branch/commit refinement helpers shared by the scenario services
  * (`ScenarioServiceLive`, `ScenarioMergeServiceLive`). Both operate on
  * already-validated `WorkspaceId`/`ScenarioName` inputs (validate once at the
  * boundary), so a failure here is an invariant violation (`ZIO.die`), not a
  * domain error.
  */
private[services] object ScenarioBranchOps:

  /** Compose the scenario's Irmin branch reference (DD-5/DD-11 shape:
    * `scenarios.<workspaceId-lowercased-ulid>.<name-slug>`).
    */
  def scenarioBranch(wsId: WorkspaceId, name: ScenarioName.ScenarioName): UIO[BranchRef] =
    BranchRef.scenario(wsId, name) match
      case Right(branch) => ZIO.succeed(branch)
      case Left(errors) =>
        ZIO.die(new IllegalStateException(
          s"composed branch for workspace ${wsId.value} + scenario '${name.value}' failed BranchRef validation: $errors — " +
          "unreachable given a valid WorkspaceId + ScenarioName"
        ))

  /** Refine a commit hash returned by Irmin; Irmin emitting a hash outside
    * `CommitHash`'s pinned format is an invariant violation.
    */
  def refineCommitHash(raw: String): UIO[CommitHash] =
    CommitHash.fromString(raw) match
      case Right(hash) => ZIO.succeed(hash)
      case Left(errors) =>
        ZIO.die(new IllegalStateException(
          s"Irmin returned commit hash '$raw' not matching CommitHash's pinned format: $errors"
        ))
