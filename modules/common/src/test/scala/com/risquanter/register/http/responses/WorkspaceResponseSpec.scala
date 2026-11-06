package com.risquanter.register.http.responses

import zio.test.*
import zio.json.*
import sttp.tapir.DecodeResult
import io.github.iltotore.iron.*
import com.risquanter.register.domain.data.iron.{NonNegativeInt, WorkspaceKeySecret}

/** The location a bootstrap answers with, and the evict count. */
object WorkspaceResponseSpec extends ZIOSpecDefault {

  private val key: WorkspaceKeySecret =
    WorkspaceKeySecret.fromString("abcdefghijklmnopqrstuv").toOption.get

  def spec = suite("WorkspaceResponse")(
    suite("WorkspaceLocation")(
      test("renders as the workspace segment and the key") {
        assertTrue(WorkspaceLocation.codec.encode(WorkspaceLocation(key)) == WorkspaceLocation.Prefix + key.reveal)
      },
      test("parse undoes render") {
        val location = WorkspaceLocation(key)
        assertTrue(WorkspaceLocation.codec.decode(WorkspaceLocation.codec.encode(location)) == DecodeResult.Value(location))
      },
      test("a reference without the workspace segment is refused") {
        val decoded = WorkspaceLocation.codec.decode(key.reveal)
        assertTrue(decoded match
          case DecodeResult.Mismatch(_, _) => true
          case _                           => false)
      },
      test("prints without its key") {
        assertTrue(!WorkspaceLocation(key).toString.contains(key.reveal))
      }
    ),
    test("the evict count round-trips as an object") {
      val response = EvictExpiredResponse(NonNegativeInt.count(3))
      assertTrue(
        response.toJson == "{\"evicted\":3}",
        response.toJson.fromJson[EvictExpiredResponse] == Right(response)
      )
    }
  )
}
