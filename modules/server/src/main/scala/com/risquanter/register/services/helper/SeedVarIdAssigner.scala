package com.risquanter.register.services.helper

import com.risquanter.register.domain.data.iron.{SafeName, SeedVarId}
import com.risquanter.register.domain.errors.{ValidationError, ValidationErrorCode}

/** Boundary-time seedVarId assignment (PLAN-SEED-IDENTITY §5.1).
  *
  * Pure: `(leaf names, already-fixed IDs, high-water) → (complete ID map, new high-water)`.
  * Leaves absent from `fixed` receive consecutive IDs in sorted-name order,
  * starting above `max(highWater, fixed IDs)` — deterministic across recreations
  * of the same tree, never dependent on `Map` iteration order, and never
  * colliding with a caller-provided ID. `fixed` carries IDs that must not
  * change: carried-over assignments on update (immutability, §5.3) and
  * caller-provided IDs (wired in via the DTOs).
  *
  * The returned high-water is `max(highWater, every ID in the tree)` — it never
  * decreases, so a deleted leaf's stream ID is never re-issued by auto-assignment.
  */
object SeedVarIdAssigner:

  def assign(
    leafNames: Seq[SafeName.SafeName],
    fixed: Map[SafeName.SafeName, SeedVarId.SeedVarId],
    highWater: Long
  ): Either[ValidationError, (Map[SafeName.SafeName, SeedVarId.SeedVarId], SeedVarId.SeedVarId)] = {
    val unassigned = leafNames.filterNot(fixed.contains).sortBy(_.value)
    // Allocation starts above every ID already spoken for — the watermark AND any
    // fixed (provided) ID above it — so a caller-provided ID can never collide with
    // auto-assignment (§5.1: a provided ID consumes the assignment space above it).
    val base = (highWater +: fixed.values.map(_.value).toSeq).max
    for {
      assigned <- allocateConsecutive(unassigned, base)
      all       = fixed ++ assigned
      newHighWaterValue = (highWater +: all.values.map(_.value).toSeq).max
      newHighWater <- SeedVarId.fromLong(newHighWaterValue).left.map(_ => exhausted(newHighWaterValue))
    } yield (all, newHighWater)
  }

  private def allocateConsecutive(
    names: Seq[SafeName.SafeName],
    highWater: Long
  ): Either[ValidationError, Map[SafeName.SafeName, SeedVarId.SeedVarId]] = {
    val allocated = names.zipWithIndex.map { case (name, idx) =>
      val value = highWater + 1 + idx
      SeedVarId.fromLong(value).map(name -> _).left.map(_ => exhausted(value))
    }
    allocated.collectFirst { case Left(err) => err } match {
      case Some(err) => Left(err)
      case None      => Right(allocated.collect { case Right(pair) => pair }.toMap)
    }
  }

  private def exhausted(value: Long): ValidationError =
    ValidationError(
      field = "request.seedVarIds",
      code = ValidationErrorCode.CONSTRAINT_VIOLATION,
      message = s"seedVarId assignment space exhausted: next value $value exceeds the valid range"
    )
