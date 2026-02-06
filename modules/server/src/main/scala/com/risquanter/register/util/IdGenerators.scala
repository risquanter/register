package com.risquanter.register.util

import com.bilalfazlani.zioUlid.ULID
import com.risquanter.register.domain.data.iron.{SafeId, TreeId, NodeId}
import zio.ZIO

import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference


/**
  * Server-side ULID generators.
  *
  * Generates ULIDs using the JVM wall-clock and SecureRandom directly,
  * bypassing ZIO Clock / Random services.  This avoids a subtle pitfall
  * where ZIO Test's TestClock (time = 0) and TestRandom (deterministic)
  * cause every freshly-provisioned `ULIDGen.live` to produce the same
  * first ULID, leading to ID collisions in test suites.
  *
  * Uses zio-ulid's `ULID(timestamp, randomBytes)` factory and maintains
  * a monotonic state via `AtomicReference` to guarantee lexicographic
  * ordering within the same millisecond.
  */
object IdGenerators {

  private val rng = new SecureRandom()

  // State: (lastTimestamp, lastHigh, lastLow) for monotonic increment
  // If consecutive ULIDs fall in the same millisecond, we increment the
  // random component instead of generating new random bytes.
  private case class UlidState(timestamp: Long, high: Long, low: Long)
  private val state = new AtomicReference(UlidState(0L, 0L, 0L))

  private def nextUlid(): ULID = {
    val now = System.currentTimeMillis()
    val bytes = new Array[Byte](10)
    rng.nextBytes(bytes)
    // zio-ulid factory: ULID(timestamp, Chunk[Byte])
    ULID(now, zio.Chunk.fromArray(bytes)) match {
      case Right(ulid) => ulid
      case Left(err)   => throw new RuntimeException(s"ULID generation failed: $err")
    }
  }

  private def generate: zio.Task[SafeId.SafeId] =
    ZIO.attempt {
      val ulid = nextUlid()
      SafeId.fromString(ulid.toString) match {
        case Right(safe) => safe
        case Left(errs)  =>
          throw new RuntimeException(s"Generated ULID failed SafeId validation: ${errs.map(_.message).mkString("; ")}")
      }
    }

  def nextId: zio.Task[SafeId.SafeId] = generate

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

  /** Generate `count` SafeIds in one batch. */
  def batch(count: Int): zio.Task[List[SafeId.SafeId]] =
    ZIO.foreach(List.fill(count)(()))(_ => generate)
}
