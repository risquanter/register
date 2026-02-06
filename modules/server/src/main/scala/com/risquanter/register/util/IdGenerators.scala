package com.risquanter.register.util

import com.bilalfazlani.zioUlid.{ULIDGen, ULID}
import com.risquanter.register.domain.data.iron.{SafeId, TreeId, NodeId}
import zio.{Clock, Random, ZIO, ZLayer}


/**
  * Server-side ULID generators.
  * - Uses zio-ulid `nextULID` to obtain canonical ULIDs.
  * - Wraps them in `SafeId` for downstream domain use.
  *
  * IMPORTANT: `ULIDGen.live` internally calls `ZIO.clockWith` / `ZIO.randomWith`,
  * which resolve to the **fiber's default services**, not to any layer-provided
  * Clock/Random instances.  In ZIO Test the fiber defaults are TestClock (time = 0)
  * and TestRandom (deterministic), so every freshly-provisioned `ULIDGen` emits
  * the same first ULID.  Test specs that exercise `IdGenerators` therefore need
  * `@@ TestAspect.withLiveClock @@ TestAspect.withLiveRandom` to obtain unique IDs.
  */
object IdGenerators {

  // Build ULIDGen with live Clock/Random layer wiring
  private val liveIdEnv =
    (ZLayer.succeed(Clock.ClockLive) ++ ZLayer.succeed(Random.RandomLive)) >>>
      ULIDGen.live

  // Core generator that relies on an injected ULIDGen
  private val generate: ZIO[ULIDGen, Throwable, SafeId.SafeId] =
    for {
      ulid <- ULIDGen.nextULID
      safe <- ZIO.fromEither(SafeId.fromString(ulid.toString)).mapError { errs =>
        new RuntimeException(s"Generated ULID failed SafeId validation: ${errs.map(_.message).mkString("; ")}")
      }
    } yield safe

  def nextId: zio.Task[SafeId.SafeId] =
    generate.provideSomeLayer(liveIdEnv)

  /** Effectful TreeId generator for server-assigned tree identifiers.
    * Delegates to nextId and wraps in TreeId case class.
    * @see ADR-018 for nominal wrapper pattern
    */
  def nextTreeId: zio.Task[TreeId] =
    nextId.map(TreeId(_))

  /** Effectful NodeId generator for server-assigned node identifiers.
    * Delegates to nextId and wraps in NodeId case class.
    * @see ADR-018 for nominal wrapper pattern
    */
  def nextNodeId: zio.Task[NodeId] =
    nextId.map(NodeId(_))

  /** Generate `count` SafeIds using a single ULIDGen instance (monotonic within batch). */
  def batch(count: Int): zio.Task[List[SafeId.SafeId]] =
    ZIO.foreach(List.fill(count)(()))(_ => generate)
      .provideSomeLayer(liveIdEnv)
}
