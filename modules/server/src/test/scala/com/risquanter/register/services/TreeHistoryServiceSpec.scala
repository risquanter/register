package com.risquanter.register.services

import zio.*
import zio.test.*
import java.time.Instant
import com.risquanter.register.http.responses.HistoryOperation

/** Pure tests for `TreeHistoryServiceLive`'s commit-message → operation
  * mapping and timestamp normalization (the parts that don't need Irmin). */
object TreeHistoryServiceSpec extends ZIOSpecDefault:

  private val ws = "01j8zq3fkwp2x9m4v7rtbnd6ea"
  private val tree = "01j8zq3fkwp2x9m4v7rtbnd6eb"

  def spec = suite("TreeHistoryServiceLive")(

    suite("parseOperation")(
      test("recognizes the tree-mutation message suffixes") {
        assertTrue(
          TreeHistoryServiceLive.parseOperation(s"workspace:$ws:risk-tree:$tree:create") == HistoryOperation.Create,
          TreeHistoryServiceLive.parseOperation(s"workspace:$ws:risk-tree:$tree:update") == HistoryOperation.Update,
          TreeHistoryServiceLive.parseOperation(s"workspace:$ws:risk-tree:$tree:delete") == HistoryOperation.Delete,
          TreeHistoryServiceLive.parseOperation(s"workspace:$ws:risk-tree:$tree:revert") == HistoryOperation.Revert
        )
      },
      test("recognizes a merge-scenario message") {
        assertTrue(
          TreeHistoryServiceLive.parseOperation(s"workspace:$ws:merge-scenario:cyber") == HistoryOperation.Merge
        )
      },
      test("maps anything unrecognized to Other") {
        assertTrue(
          TreeHistoryServiceLive.parseOperation("some external commit") == HistoryOperation.Other,
          TreeHistoryServiceLive.parseOperation("") == HistoryOperation.Other
        )
      }
    ),

    suite("toIso")(
      test("normalizes an epoch-seconds string to ISO-8601") {
        assertTrue(TreeHistoryServiceLive.toIso("0") == Instant.ofEpochSecond(0).toString)
      },
      test("passes a non-numeric date through unchanged") {
        assertTrue(TreeHistoryServiceLive.toIso("2026-07-27T00:00:00Z") == "2026-07-27T00:00:00Z")
      }
    )
  )
