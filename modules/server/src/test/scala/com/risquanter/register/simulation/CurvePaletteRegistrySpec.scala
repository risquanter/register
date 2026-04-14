package com.risquanter.register.simulation

import zio.test.*

import com.risquanter.register.domain.data.CurvePalette

object CurvePaletteRegistrySpec extends ZIOSpecDefault:

  def spec = suite("CurvePaletteRegistrySpec")(
    test("every CurvePalette variant has an entry in the registry") {
      val allPalettes = CurvePalette.values.toSet
      val registeredPalettes = CurvePaletteRegistry.shades.keySet
      assertTrue(registeredPalettes == allPalettes)
    },
    test("each palette has exactly 13 shades") {
      val counts = CurvePaletteRegistry.shades.view.mapValues(_.size).toMap
      assertTrue(
        counts(CurvePalette.Green) == 13,
        counts(CurvePalette.Aqua) == 13,
        counts(CurvePalette.Purple) == 13
      )
    },
    test("Green shade 0 is darkest (#03170b)") {
      val first = CurvePaletteRegistry.shades(CurvePalette.Green).head.value
      assertTrue(first == "#03170b")
    },
    test("Green shade 12 is lightest (#f1fdf5)") {
      val last = CurvePaletteRegistry.shades(CurvePalette.Green).last.value
      assertTrue(last == "#f1fdf5")
    },
    test("Aqua shade 0 is darkest (#00121a)") {
      val first = CurvePaletteRegistry.shades(CurvePalette.Aqua).head.value
      assertTrue(first == "#00121a")
    },
    test("Purple shade 0 is darkest (#11011e)") {
      val first = CurvePaletteRegistry.shades(CurvePalette.Purple).head.value
      assertTrue(first == "#11011e")
    },
    test("all shades are valid 7-character hex strings") {
      val allShades = CurvePaletteRegistry.shades.values.flatten
      val allValid = allShades.forall { h =>
        val s = h.value
        s.length == 7 && s.startsWith("#") && s.drop(1).forall("0123456789abcdef".contains(_))
      }
      assertTrue(allValid)
    },
    test("no duplicate shades within the same palette") {
      val noDupes = CurvePaletteRegistry.shades.forall { case (_, shades) =>
        shades.map(_.value).distinct.size == shades.size
      }
      assertTrue(noDupes)
    }
  )
