package app.state

import scala.scalajs.js

import zio.test.*

import com.risquanter.register.domain.data.iron.NodeId

object ChartHoverBridgeSpec extends ZIOSpecDefault:

  // Use a ULID with no I/L/O characters (Crockford base32 normalizes them)
  private val validUlid = "01HX9ABCDEFGH1JK1MN0PQRST0"
  private val validNodeId = NodeId.fromString(validUlid).toOption.get

  def spec = suite("ChartHoverBridgeSpec")(
    suite("parseHoverSignal")(
      test("returns Some(nodeId) for valid selection store value") {
        val value: js.Dynamic = js.Array(
          js.Dynamic.literal(
            "fields" -> js.Array(js.Dynamic.literal("field" -> "curveId", "type" -> "E")),
            "values" -> js.Array(validUlid)
          )
        ).asInstanceOf[js.Dynamic]
        val result = ChartHoverBridge.parseHoverSignal(value)
        assertTrue(result == Some(validNodeId))
      },
      test("returns None for empty selection store") {
        val value: js.Dynamic = js.Array().asInstanceOf[js.Dynamic]
        val result = ChartHoverBridge.parseHoverSignal(value)
        assertTrue(result.isEmpty)
      },
      test("returns None for malformed input") {
        val value: js.Dynamic = js.Dynamic.literal("unexpected" -> true)
        val result = ChartHoverBridge.parseHoverSignal(value)
        assertTrue(result.isEmpty)
      },
      test("returns None for null/undefined") {
        val value: js.Dynamic = js.undefined.asInstanceOf[js.Dynamic]
        val result = ChartHoverBridge.parseHoverSignal(value)
        assertTrue(result.isEmpty)
      },
      test("returns None for invalid ULID string in values") {
        val value: js.Dynamic = js.Array(
          js.Dynamic.literal(
            "fields" -> js.Array(js.Dynamic.literal("field" -> "curveId", "type" -> "E")),
            "values" -> js.Array("not-a-valid-ulid")
          )
        ).asInstanceOf[js.Dynamic]
        val result = ChartHoverBridge.parseHoverSignal(value)
        assertTrue(result.isEmpty)
      }
    ),
    suite("buildSelectionStore")(
      test("produces store with fields and values for Some(nodeId)") {
        val store = ChartHoverBridge.buildSelectionStore(Some(validNodeId))
        assertTrue(store.length == 1) &&
        assertTrue(store(0).asInstanceOf[js.Dynamic].values.asInstanceOf[js.Array[String]](0) == validUlid)
      },
      test("produces empty array for None") {
        val store = ChartHoverBridge.buildSelectionStore(None)
        assertTrue(store.length == 0)
      },
      test("round-trips through parseHoverSignal") {
        val store = ChartHoverBridge.buildSelectionStore(Some(validNodeId))
        val parsed = ChartHoverBridge.parseHoverSignal(store.asInstanceOf[js.Dynamic])
        assertTrue(parsed == Some(validNodeId))
      }
    )
  )
