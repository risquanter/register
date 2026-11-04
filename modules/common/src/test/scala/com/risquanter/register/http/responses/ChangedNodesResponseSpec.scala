package com.risquanter.register.http.responses

import zio.json.*
import zio.json.ast.{Json, JsonCursor}
import zio.test.*

import com.risquanter.register.testutil.TestHelpers.*

/** The changed-nodes outcome's codec: each outcome round-trips, and the tag
  * field is the one the companion declares. */
object ChangedNodesResponseSpec extends ZIOSpecDefault:

  private val every: List[ChangedNodesStatus] = List(
    ChangedNodesStatus.Ok(List(
      NodeChangeEntry(nodeId("cyber"), NodeChangeStatus.Changed),
      NodeChangeEntry(nodeId("root"), NodeChangeStatus.Identical),
      NodeChangeEntry(nodeId("new-leaf"), NodeChangeStatus.Added),
      NodeChangeEntry(nodeId("old-leaf"), NodeChangeStatus.Removed)
    )),
    ChangedNodesStatus.MissingOnA(),
    ChangedNodesStatus.MissingOnB(),
    ChangedNodesStatus.MissingOnBoth()
  )

  private def tagOf(status: ChangedNodesStatus): Option[String] =
    status.toJson.fromJson[Json].toOption
      .flatMap(_.get(JsonCursor.field(ChangedNodesStatus.TagField)).toOption)
      .collect { case Json.Str(value) => value }

  def spec = suite("ChangedNodesResponse")(
    test("every outcome round-trips through the derived codec") {
      assertTrue(every.forall(s => ChangedNodesResponse(s).toJson.fromJson[ChangedNodesResponse] == Right(ChangedNodesResponse(s))))
    },
    test("the tag field is the declared key and carries the case name") {
      assertTrue(
        tagOf(ChangedNodesStatus.Ok(Nil)) == Some("Ok"),
        tagOf(ChangedNodesStatus.MissingOnBoth()) == Some("MissingOnBoth")
      )
    },
    test("only Identical does not differ") {
      assertTrue(
        !NodeChangeStatus.Identical.differs,
        NodeChangeStatus.Changed.differs,
        NodeChangeStatus.Added.differs,
        NodeChangeStatus.Removed.differs
      )
    }
  )
