package com.risquanter.register.domain.data

import zio.json.{JsonEncoder, JsonDecoder}
import sttp.tapir.Schema

/** Named colour palette for LEC chart curves.
  *
  * Each variant maps to one of the 13-shade CSS curve palettes
  * defined in app.css. The server resolves the palette name to
  * concrete hex values when building the Vega-Lite spec.
  *
  * Serialized as lowercase string (e.g. "green", "aqua").
  */
enum CurvePalette:
  case Green   // --curve-green-*   (query results)
  case Aqua    // --curve-aqua-*    (manual selection)
  case Purple  // --curve-purple-*  (overlap: both sets)

object CurvePalette:
  given JsonEncoder[CurvePalette] =
    JsonEncoder[String].contramap(_.toString.toLowerCase)
  given JsonDecoder[CurvePalette] =
    JsonDecoder[String].mapOrFail { s =>
      CurvePalette.values.find(_.toString.equalsIgnoreCase(s))
        .toRight(s"Unknown curve palette: '$s'. Valid: ${CurvePalette.values.map(_.toString.toLowerCase).mkString(", ")}")
    }
  given Schema[CurvePalette] = Schema.string
