package com.risquanter.register.http.requests

import zio.test.*
import zio.json.*
import com.risquanter.register.domain.data.CurvePalette
import com.risquanter.register.domain.data.iron.NodeId

object LECChartRequestSpec extends ZIOSpecDefault:

  // Test node IDs (valid ULIDs)
  private val nodeId1 = NodeId.fromString("01HX9ABCDEFGHIJKLMNOPQRST0").toOption.get
  private val nodeId2 = NodeId.fromString("01HXA1234567890ABCDEFGHIJK").toOption.get

  def spec = suite("LECChartRequest")(
    test("encode → decode round-trip") {
      val req = LECChartRequest(List(
        LECChartCurveEntry(nodeId1, CurvePalette.Green),
        LECChartCurveEntry(nodeId2, CurvePalette.Aqua)
      ))
      val json = req.toJson
      val decoded = json.fromJson[LECChartRequest]
      assertTrue(decoded == Right(req))
    },
    test("empty curves list accepted at codec level") {
      val req = LECChartRequest(List.empty)
      val json = req.toJson
      val decoded = json.fromJson[LECChartRequest]
      assertTrue(decoded == Right(req))
    },
    test("JSON structure matches expected wire format") {
      val req = LECChartRequest(List(
        LECChartCurveEntry(nodeId1, CurvePalette.Green)
      ))
      val json = req.toJson
      val parsed = json.fromJson[zio.json.ast.Json]
      assertTrue(
        parsed.isRight,
        json.contains("\"curves\""),
        json.contains("\"nodeId\""),
        json.contains("\"palette\""),
        json.contains("\"green\"")
      )
    },
    test("decodes palette case-insensitively") {
      val json = s"""{"curves":[{"nodeId":"${nodeId1.value}","palette":"GREEN"}]}"""
      val decoded = json.fromJson[LECChartRequest]
      assertTrue(
        decoded.isRight,
        decoded.toOption.get.curves.head.palette == CurvePalette.Green
      )
    }
  )
