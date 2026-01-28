package com.risquanter.register.util

import com.bilalfazlani.zioUlid.{ULIDGen, ULID}
import com.risquanter.register.domain.data.iron.SafeId
import zio.{Clock, Random, ZIO, ZLayer}

/**
  * Server-side ULID generators.
  * - Uses zio-ulid `nextULID` to obtain canonical ULIDs.
  * - Wraps them in `SafeId` for downstream domain use.
  */
object IdGenerators {

  /**
    * Effectful SafeId generator using zio-ulid.
    * Returns a Task so callers can lift failures into the existing error pipeline.
    */
  // Build ULIDGen with live Clock/Random to avoid TestClock/TestRandom reuse in specs
  private val liveIdEnv =
    (ZLayer.succeed(Clock.ClockLive) ++ ZLayer.succeed(Random.RandomLive)) >>>
      ULIDGen.live

  // Core generator that relies on an injected ULIDGen (Clock/Random provided by layer wiring)
  private val generate: ZIO[ULIDGen, Throwable, SafeId.SafeId] =
    for {
      ulid <- ULIDGen.nextULID
      safe <- ZIO.fromEither(SafeId.fromString(ulid.toString)).mapError { errs =>
        new RuntimeException(s"Generated ULID failed SafeId validation: ${errs.map(_.message).mkString("; ")}")
      }
    } yield safe

  def nextId: zio.Task[SafeId.SafeId] =
    // Provide layer once per call (single ULID)
    generate.provideSomeLayer(liveIdEnv)

  /** Generate `count` SafeIds using a single ULIDGen instance (avoids repeated seeding under tests). */
  def batch(count: Int): zio.Task[List[SafeId.SafeId]] =
    ZIO.foreach(List.fill(count)(()))(_ => generate)
      .provideSomeLayer(liveIdEnv)
}
