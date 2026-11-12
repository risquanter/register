package com.risquanter.register.infra.irmin.model

import zio.NonEmptyChunk
import zio.test.*

import com.risquanter.register.domain.errors.{ErrorLocation, FieldProblem, ValidationError, ValueFormat}

/** `IrminSegment.from`: a name the store listed is a segment, or the one
  * refusal that says it is not. */
object IrminSegmentSpec extends ZIOSpecDefault:

  private val refusal: NonEmptyChunk[ValidationError] =
    NonEmptyChunk(ValidationError(ErrorLocation.Whole, FieldProblem.WrongFormat(ValueFormat.StoreSegment)))

  def spec = suite("IrminSegment.from")(
    test("a name of segment characters is a segment holding that name") {
      assertTrue(IrminSegment.from("01ARZ3NDEKTSV4RRFFQ69G5FAV").map(_.value) == Right("01ARZ3NDEKTSV4RRFFQ69G5FAV"))
    },
    test("a name holding a separator is refused as a store segment, and the name is not in the refusal") {
      assertTrue(IrminSegment.from("risk-trees/extra") == Left(refusal))
    },
    test("an empty name is refused") {
      assertTrue(IrminSegment.from("") == Left(refusal))
    }
  )
