package com.risquanter.register.util

import com.bilalfazlani.zioUlid.{ULIDGen, ULID}
import com.risquanter.register.domain.data.iron.SafeId
import zio.{Runtime, Unsafe, ZIO}

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
  def nextId: zio.Task[SafeId.SafeId] =
    ULIDGen.nextULID
      .provideLayer(ULIDGen.live)
      .flatMap { ulid =>
        ZIO.fromEither(SafeId.fromString(ulid.toString)).mapError { errs =>
          new RuntimeException(s"Generated ULID failed SafeId validation: ${errs.map(_.message).mkString("; ")}")
        }
      }
}
