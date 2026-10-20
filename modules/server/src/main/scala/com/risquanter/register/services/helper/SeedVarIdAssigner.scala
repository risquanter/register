package com.risquanter.register.services.helper

import com.risquanter.register.domain.data.iron.{SafeName, SeedVarId, ValidationMessages}
import com.risquanter.register.domain.errors.{ErrorLocation, FieldName, ValidationError, ValidationErrorCode}

/** Assigns a seedVarId to every leaf of a tree that does not already have one.
  *
  * A seedVarId is the number that fixes which stream of random draws a leaf uses
  * in simulation, so two leaves must never share one. This is a pure function:
  * leaf names, the seedVarIds already spoken for, and the tree's high-water mark
  * in; a seedVarId for every leaf plus a new high-water mark out.
  *
  * `fixed` holds the seedVarIds that must not move — those carried over from
  * leaves an update keeps, because a seedVarId is immutable once assigned, and
  * those the client supplied for new leaves. Every other leaf is sorted by name
  * and given consecutive numbers starting above both the high-water mark and the
  * highest value in `fixed`. Sorting by name rather than relying on map order is
  * what makes rebuilding the same tree allocate the same numbers, and starting
  * above `fixed` is what stops auto-assignment colliding with a supplied value.
  *
  * {{{
  * // leaves Fire, Flood and Server outage, the last supplying 42, high-water 0
  * assign(Seq("Fire", "Flood", "Server outage"), Map("Server outage" -> 42), 0)
  * // Map("Server outage" -> 42, "Fire" -> 43, "Flood" -> 44), high-water 44
  * }}}
  *
  * The returned high-water mark is the largest seedVarId in the tree and never
  * decreases, so deleting a leaf does not free its number for reuse — a later
  * leaf given that number would inherit the deleted risk's cached results.
  *
  * Two supplied values that collide with each other, or a supplied value equal to
  * a carried-over one, are not caught here: `fixed` is keyed by leaf name, so both
  * entries survive. `RiskTree.fromNodes` runs the distinctness check that refuses
  * them.
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
      location = ErrorLocation.requestField(FieldName.SeedVarId),
      code = ValidationErrorCode.CONSTRAINT_VIOLATION,
      message = ValidationMessages.seedVarIdAssignmentExhausted(value)
    )
