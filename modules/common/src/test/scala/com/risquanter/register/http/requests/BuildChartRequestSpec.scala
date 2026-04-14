package com.risquanter.register.http.requests

import zio.test.*
import com.risquanter.register.domain.data.CurvePalette
import com.risquanter.register.domain.data.iron.NodeId
import com.risquanter.register.testutil.TestHelpers

object BuildChartRequestSpec extends ZIOSpecDefault:

  private def nid(s: String): NodeId = TestHelpers.nodeId(s)

  private val n1 = nid("01HX9ABCDEFGHIJKLMNOPQR001")
  private val n2 = nid("01HX9ABCDEFGHIJKLMNOPQR002")
  private val n3 = nid("01HX9ABCDEFGHIJKLMNOPQR003")
  private val n4 = nid("01HX9ABCDEFGHIJKLMNOPQR004")

  private def palettesOf(req: LECChartRequest): Map[NodeId, CurvePalette] =
    req.curves.map(e => e.nodeId -> e.palette).toMap

  def spec = suite("LECChartRequest.build")(
    test("disjoint sets — no overlap, correct palette tags") {
      val req = LECChartRequest.build(querySet = Set(n1, n2), userSet = Set(n3))
      val pm  = palettesOf(req)
      assertTrue(
        pm(n1) == CurvePalette.Green,
        pm(n2) == CurvePalette.Green,
        pm(n3) == CurvePalette.Aqua,
        req.curves.size == 3
      )
    },
    test("partial overlap — overlap nodes get Purple, remainder correct") {
      val req = LECChartRequest.build(querySet = Set(n1, n2), userSet = Set(n2, n3))
      val pm  = palettesOf(req)
      assertTrue(
        pm(n1) == CurvePalette.Green,
        pm(n2) == CurvePalette.Purple,
        pm(n3) == CurvePalette.Aqua,
        req.curves.size == 3
      )
    },
    test("full overlap — all Purple") {
      val req = LECChartRequest.build(querySet = Set(n1, n2), userSet = Set(n1, n2))
      val pm  = palettesOf(req)
      assertTrue(
        pm(n1) == CurvePalette.Purple,
        pm(n2) == CurvePalette.Purple,
        req.curves.size == 2
      )
    },
    test("empty query set — only Aqua entries") {
      val req = LECChartRequest.build(querySet = Set.empty, userSet = Set(n1, n2))
      val pm  = palettesOf(req)
      assertTrue(
        pm(n1) == CurvePalette.Aqua,
        pm(n2) == CurvePalette.Aqua,
        req.curves.size == 2
      )
    },
    test("empty user set — only Green entries") {
      val req = LECChartRequest.build(querySet = Set(n1, n2), userSet = Set.empty)
      val pm  = palettesOf(req)
      assertTrue(
        pm(n1) == CurvePalette.Green,
        pm(n2) == CurvePalette.Green,
        req.curves.size == 2
      )
    },
    test("both empty — empty entries list") {
      val req = LECChartRequest.build(querySet = Set.empty, userSet = Set.empty)
      assertTrue(req.curves.isEmpty)
    },
    test("each node appears exactly once") {
      val req = LECChartRequest.build(querySet = Set(n1, n2, n3), userSet = Set(n2, n3, n4))
      assertTrue(
        req.curves.map(_.nodeId).toSet == Set(n1, n2, n3, n4),
        req.curves.size == 4
      )
    }
  )
