package com.risquanter.register.domain.data

import zio.test.*
import zio.json.{DecoderOps, EncoderOps}
import com.risquanter.register.domain.data.RiskLeaf
import zio.prelude.Validation

import com.risquanter.register.domain.errors.{
  ErrorLocation, ErrorSubject, FieldName, SubmittedCollection, ValidationError
}
import com.risquanter.register.testutil.TestHelpers.{metalogShape, idStr, lognormalShape, nodeId, unsafeGet}

/** What `RiskLeaf.create` refines, and nothing else.
  *
  * The loss shape arrives already gated, so every rule about percentiles,
  * quantiles, term counts and loss bounds is asserted in
  * [[DistributionShapeSpec]] against the type that holds it. What is left here
  * is the four values this constructor refines — identifier, name, occurrence
  * probability and seed identity — their accumulation, and the leaf's own wire
  * format.
  */
object RiskLeafSpec extends ZIOSpecDefault {

  /** The node a caller names when the leaf came from a submitted collection
    * rather than from the request body as a whole. */
  private val submittedLeaf: ErrorSubject =
    ErrorSubject.nodeAtIndex(SubmittedCollection.Leaves, 0)

  /** A shape whose own gate passes, so a refusal in these tests is always about
    * one of the four values `create` refines. */
  private val validShape: DistributionShape = lognormalShape(100L, 1000L)

  /** Every location the refusal reported, in the order the errors accumulated. */
  private def locationsOf[A](
    result: Validation[ValidationError, A]
  ): List[ErrorLocation] =
    result.toEither.swap.toOption.toList.flatMap(_.toList).map(_.location)

  private def create(
    id:          String,
    name:        String,
    probability: Double,
    seedVarId:   Long,
    shape:       DistributionShape = validShape,
    subject:     ErrorSubject      = ErrorSubject.Request
  ): Validation[ValidationError, RiskLeaf] =
    RiskLeaf.create(id, name, probability, shape, parentId = None, seedVarId, subject)

  def spec = suite("RiskLeaf Smart Constructor")(

    suite("Valid RiskLeaf Creation")(
      test("creates a leaf whose shape is a metalog fit") {
        val result = create(
          id = idStr("cyber-attack"), name = "Cyber Attack", probability = 0.25, seedVarId = 1L,
          shape = metalogShape(Vector(0.05, 0.5, 0.95), Vector(1000.0, 5000.0, 25000.0), None)
        )
        assertTrue(result.isSuccess)
      },
      test("creates a leaf whose shape is a lognormal") {
        val result = create(
          id = idStr("data-breach"), name = "Data Breach", probability = 0.15, seedVarId = 2L,
          shape = lognormalShape(1000L, 50000L)
        )
        assertTrue(result.isSuccess)
      },
      test("accepts a ULID-format id") {
        assertTrue(create(idStr("abc"), "ABC Risk", 0.5, 3L).isSuccess)
      },
      test("accepts another ULID-format id") {
        assertTrue(create(idStr("long-id"), "Long ID Risk", 0.5, 4L).isSuccess)
      },
      test("accepts an id derived from a label with hyphens and underscores") {
        assertTrue(create(idStr("ops_risk-2024"), "Operations Risk", 0.5, 5L).isSuccess)
      },
      test("accepts a leaf with a parent") {
        val result = RiskLeaf.create(
          idStr("child-leaf"), "Child Leaf", 0.5, validShape,
          parentId = Some(nodeId("root-pf")), seedVarId = 6L
        )
        assertTrue(result.map(_.parentId).toOption.contains(Some(nodeId("root-pf"))))
      }
    ),

    suite("Invalid ID Validation")(
      test("rejects an empty id") {
        assertTrue(create("", "Valid Name", 0.5, 7L).isFailure)
      },
      test("rejects a non-ULID id (too short)") {
        assertTrue(create("ab", "Valid Name", 0.5, 8L).isFailure)
      },
      test("rejects a non-ULID id (too long)") {
        assertTrue(create("a" * 31, "Valid Name", 0.5, 9L).isFailure)
      },
      test("rejects a non-ULID id (spaces)") {
        assertTrue(create("cyber attack", "Valid Name", 0.5, 10L).isFailure)
      },
      test("rejects a non-ULID id (special characters)") {
        assertTrue(create("cyber@attack!", "Valid Name", 0.5, 11L).isFailure)
      }
    ),

    suite("Invalid Name Validation")(
      test("rejects an empty name") {
        assertTrue(create(idStr("valid-id"), "", 0.5, 12L).isFailure)
      },
      test("rejects a blank name") {
        assertTrue(create(idStr("valid-id"), "   ", 0.5, 13L).isFailure)
      },
      test("rejects a name longer than fifty characters") {
        assertTrue(create(idStr("valid-id"), "a" * 51, 0.5, 14L).isFailure)
      }
    ),

    suite("Invalid Probability Validation")(
      test("accepts a probability of zero — an event that never occurs") {
        assertTrue(create(idStr("valid-id"), "Valid Name", 0.0, 15L).isSuccess)
      },
      test("accepts a probability of one — an event that always occurs") {
        assertTrue(create(idStr("valid-id"), "Valid Name", 1.0, 16L).isSuccess)
      },
      test("rejects a negative probability") {
        assertTrue(create(idStr("valid-id"), "Valid Name", -0.1, 17L).isFailure)
      },
      test("rejects a probability above one") {
        assertTrue(create(idStr("valid-id"), "Valid Name", 1.5, 18L).isFailure)
      }
    ),

    suite("Tapir schema matches the JSON wire names")(
      // The zio-json codec and the Tapir schema are derived independently: the
      // codec encodes through RiskLeafRaw, the schema reads this case class's
      // own field names. @jsonField renames two fields for zio-json only, so
      // the schema has to carry the same rename or the generated OpenAPI
      // describes properties that are not on the wire.
      test("the derived schema names id and name, not safeId and safeName") {
        import sttp.tapir.Schema
        import sttp.tapir.SchemaType
        import sttp.tapir.generic.auto.*
        import com.risquanter.register.http.codecs.IronTapirCodecs.given

        def namesOf(s: Schema[?]): List[String] = s.schemaType match
          case p: SchemaType.SProduct[?] => p.fields.map(_.name.encodedName)
          case _                         => Nil

        val leafNames      = namesOf(Schema.derived[RiskLeaf])
        val portfolioNames = namesOf(Schema.derived[RiskPortfolio])

        assertTrue(
          leafNames.contains("id"),
          leafNames.contains("name"),
          !leafNames.contains("safeId"),
          !leafNames.contains("safeName"),
          portfolioNames.contains("id"),
          portfolioNames.contains("name"),
          !portfolioNames.contains("safeId"),
          !portfolioNames.contains("safeName")
        )
      }
    ),

    suite("Error Accumulation")(
      test("every refusable field fails together rather than short-circuiting") {
        val result = create(id = "x", name = "", probability = 2.0, seedVarId = 0L)
        val locations = locationsOf(result)
        assertTrue(
          locations.contains(ErrorLocation.requestField(FieldName.Id)),
          locations.contains(ErrorLocation.requestField(FieldName.Name)),
          locations.contains(ErrorLocation.requestField(FieldName.Probability)),
          locations.contains(ErrorLocation.requestField(FieldName.SeedVarId))
        )
      },
      test("the accumulated messages name each refused field") {
        val result = create(id = "x", name = "", probability = 2.0, seedVarId = 19L)
        result.toEither match
          case Left(errors) =>
            val errorStr = errors.mkString("; ")
            assertTrue(
              errorStr.contains("id") || errorStr.contains("ID") || errorStr.contains("3"),
              errorStr.toLowerCase.contains("name") || errorStr.contains("blank"),
              errorStr.toLowerCase.contains("prob") || errorStr.contains("1.0")
            )
          case Right(_) => assertTrue(false).label("Expected failure but got Success")
      },
      test("a valid shape contributes no error to the accumulation") {
        val result = create(id = "x", name = "Valid Name", probability = 0.5, seedVarId = 20L)
        assertTrue(locationsOf(result) == List(ErrorLocation.requestField(FieldName.Id)))
      }
    ),

    suite("Successful Construction Properties")(
      test("the constructed leaf carries the id it was given") {
        val result = create(idStr("test-risk"), "Test Risk", 0.5, 35L)
        assertTrue(result.map(_.id).toOption.contains(nodeId("test-risk")))
      },
      test("the constructed leaf carries the name it was given") {
        val result = create(idStr("test-risk"), "Test Risk Name", 0.5, 36L)
        assertTrue(result.map(_.name).toOption.contains("Test Risk Name"))
      },
      test("the constructed leaf carries the shape it was given, anchors intact") {
        val shape  = metalogShape(Vector(0.05, 0.5, 0.95), Vector(1000.0, 5000.0, 25000.0), None)
        val result = create(idStr("test-risk"), "Test Risk", 0.5, 37L, shape = shape)
        assertTrue(result.map(_.shape).toOption.contains(shape))
      },
      test("the constructed leaf carries a stated term count") {
        val shape  = metalogShape(Vector(0.05, 0.5, 0.95), Vector(1000.0, 5000.0, 25000.0), Some(2))
        val result = create(idStr("terms-stated"), "Terms Stated", 0.5, 45L, shape = shape)
        result.toOption.get.shape match
          case DistributionShape.Metalog(_, terms) => assertTrue(terms.map(t => t: Int) == Some(2))
          case other => assertTrue(false).label(s"expected a metalog shape, got $other")
      },
      test("the constructed leaf carries no term count when none was stated") {
        val shape  = metalogShape(Vector(0.05, 0.5, 0.95), Vector(1000.0, 5000.0, 25000.0), None)
        val result = create(idStr("terms-absent"), "Terms Absent", 0.5, 46L, shape = shape)
        result.toOption.get.shape match
          case DistributionShape.Metalog(_, terms) => assertTrue(terms.isEmpty)
          case other => assertTrue(false).label(s"expected a metalog shape, got $other")
      }
    ),

    suite("Error Location Context in Errors")(
      test("an invalid id reports at the id field") {
        val result = create("x", "Test", 0.5, 38L)
        assertTrue(
          result.isFailure,
          locationsOf(result).contains(ErrorLocation.requestField(FieldName.Id))
        )
      },
      test("an invalid name reports at the name field") {
        val result = create(idStr("test-id"), "", 0.5, 39L)
        assertTrue(
          result.isFailure,
          locationsOf(result).contains(ErrorLocation.requestField(FieldName.Name))
        )
      },
      test("an out-of-range probability reports at the probability field") {
        val result = create(idStr("test-id"), "Test", 1.5, 40L)
        assertTrue(
          result.isFailure,
          locationsOf(result).contains(ErrorLocation.requestField(FieldName.Probability))
        )
      },
      test("a caller-supplied subject propagates to errors") {
        val result = create("x", "Test", 0.5, 44L, subject = submittedLeaf)
        assertTrue(
          result.isFailure,
          locationsOf(result).contains(ErrorLocation.Field(submittedLeaf, FieldName.Id))
        )
      }
    ),

    suite("SeedVarId Validation")(
      test("rejects a seedVarId of zero, reporting at the seedVarId field") {
        val result = create(idStr("seed-zero"), "Seed Zero", 0.5, 0L)
        assertTrue(
          result.isFailure,
          locationsOf(result).contains(ErrorLocation.requestField(FieldName.SeedVarId))
        )
      },
      test("rejects a seedVarId of fifty million — the stream-doubling budget") {
        assertTrue(create(idStr("seed-cap"), "Seed Cap", 0.5, 50000000L).isFailure)
      },
      test("accepts the range bounds one and 49 999 999") {
        val lo = create(idStr("seed-lo"), "Seed Lo", 0.5, 1L)
        val hi = create(idStr("seed-hi"), "Seed Hi", 0.5, 49999999L)
        assertTrue(lo.isSuccess, hi.isSuccess)
      },
      test("a seedVarId error accumulates with other field errors") {
        val result    = create("x", "Seed Accum", 0.5, 0L)
        val locations = locationsOf(result)
        assertTrue(
          locations.contains(ErrorLocation.requestField(FieldName.Id)),
          locations.contains(ErrorLocation.requestField(FieldName.SeedVarId))
        )
      },
      test("the constructed leaf carries the seedVarId it was given") {
        val result = create(idStr("seed-preserved"), "Seed Preserved", 0.5, 77L)
        assertTrue(
          result.isSuccess,
          result.toOption.get.seedVarId.value == 77L
        )
      }
    ),

    suite("SeedVarId JSON")(
      test("the encoder writes seedVarId and the decoder round-trips it") {
        val leaf    = unsafeGet(create(idStr("seed-json"), "Seed JSON", 0.5, 42L), "leaf")
        val json    = leaf.toJson
        val decoded = json.fromJson[RiskLeaf]
        assertTrue(
          json.contains("\"seedVarId\":42"),
          decoded.map(_.seedVarId.value) == Right(42L)
        )
      },
      test("the decoder rejects leaf JSON with no seedVarId") {
        val leaf     = unsafeGet(create(idStr("seed-missing"), "Seed Missing", 0.5, 42L), "leaf")
        val json     = leaf.toJson
        val tampered = json.replace(",\"seedVarId\":42", "")
        assertTrue(json != tampered, tampered.fromJson[RiskLeaf].isLeft)
      },
      test("the decoder rejects an out-of-range seedVarId in JSON") {
        val leaf     = unsafeGet(create(idStr("seed-oor"), "Seed OOR", 0.5, 42L), "leaf")
        val tampered = leaf.toJson.replace("\"seedVarId\":42", "\"seedVarId\":0")
        val decoded  = tampered.fromJson[RiskLeaf]
        assertTrue(
          decoded.isLeft,
          decoded.swap.toOption.get.contains("seedVarId")
        )
      }
    ),

    suite("the leaf's wire format carries the shape as one nested field")(
      test("a lognormal leaf round-trips") {
        val leaf = unsafeGet(create(idStr("wire-lognormal"), "Wire Lognormal", 0.3, 50L), "leaf")
        assertTrue(leaf.toJson.fromJson[RiskLeaf] == Right(leaf))
      },
      test("a metalog leaf round-trips") {
        val shape = metalogShape(Vector(0.1, 0.5, 0.9), Vector(1000.0, 5000.0, 25000.0), Some(3))
        val leaf  = unsafeGet(create(idStr("wire-metalog"), "Wire Metalog", 0.3, 51L, shape = shape), "leaf")
        assertTrue(leaf.toJson.fromJson[RiskLeaf] == Right(leaf))
      },
      // A round trip passes under any consistent rename, because the encoder
      // and decoder move together. These two compare against a literal, so a
      // change to a field name or to a shape's tag fails an assertion instead
      // of silently changing what every stored leaf looks like. The shape's tag
      // is its case name, which is what makes a rename reach these bytes.
      test("a lognormal leaf's stored bytes are pinned") {
        val leaf = unsafeGet(
          create("01HZZZZZZZZZZZZZZZZZZZZZZ7", "Pinned Lognormal", 0.3, 50L), "leaf")
        assertTrue(leaf.toJson ==
          """{"id":"01HZZZZZZZZZZZZZZZZZZZZZZ7","name":"Pinned Lognormal","probability":0.3,""" +
          """"shape":{"distributionType":"Lognormal","minLoss":100,"maxLoss":1000},"seedVarId":50}""")
      },
      test("a metalog leaf's stored bytes are pinned") {
        val shape = metalogShape(Vector(0.1, 0.5, 0.9), Vector(1000.0, 5000.0, 25000.0), Some(3))
        val leaf  = unsafeGet(
          create("01HZZZZZZZZZZZZZZZZZZZZZZ8", "Pinned Metalog", 0.3, 51L, shape = shape), "leaf")
        assertTrue(leaf.toJson ==
          """{"id":"01HZZZZZZZZZZZZZZZZZZZZZZ8","name":"Pinned Metalog","probability":0.3,""" +
          """"shape":{"distributionType":"Metalog","percentiles":[0.1,0.5,0.9],""" +
          """"quantiles":[1000.0,5000.0,25000.0],"terms":3},"seedVarId":51}""")
      },
      test("a leaf whose shape breaks its own rule is refused on the wire") {
        val leaf     = unsafeGet(create(idStr("wire-bad"), "Wire Bad", 0.3, 52L), "leaf")
        val tampered = leaf.toJson.replace("\"minLoss\":100", "\"minLoss\":5000")
        assertTrue(leaf.toJson != tampered, tampered.fromJson[RiskLeaf].isLeft)
      }
    )
  )
}
