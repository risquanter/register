package com.risquanter.register.http

import zio.test.*
import sttp.tapir.{Schema, SchemaType}

import com.risquanter.register.domain.data.{
  AbsoluteReplacement, DistributionParams, DistributionShape, MitigationSpec,
  RelativeLikelihood, RelativeSeverity, ResultTransformSpec
}
import com.risquanter.register.domain.errors.{
  ErrorLocation, ErrorSubject, FieldName, MergeRefusal, RequestHeaderName,
  SubmittedCollection
}
import com.risquanter.register.http.errors.{ConflictKind, QueryProblem, RejectionDetail}
import com.risquanter.register.http.requests.{DistributionShapeRequest, ScenarioSourceDto}
import com.risquanter.register.http.responses.MergePreviewStatus

/**
  * Every coproduct the API publishes as a set of alternatives names the key that
  * decides which alternative it is.
  *
  * The decoder never guesses: each of these types is serialised with a tag field
  * and read back by matching on it. A schema that lists the alternatives without
  * naming that field describes a different contract — one where a reader has to
  * infer the case from which fields are present — so a generated client built
  * from the document can send a body the server refuses.
  *
  * The assertion is on the schema rather than on a round trip, because the codec
  * and the schema are produced by different machinery. A round trip passes while
  * the document is silent, which is exactly the state this pins against.
  *
  * A type published as a plain string rather than as alternatives is not in
  * question and reports nothing here.
  *
  * No automatic derivation is imported, so each entry resolves the schema its
  * own companion declares. A type whose companion stopped declaring one would
  * fail to compile here rather than fall back to a derivation that names no
  * key.
  */
object PublishedDiscriminatorSpec extends ZIOSpecDefault {

  private final case class Published(name: String, schema: Schema[?])

  private inline def entry[A](name: String)(using schema: Schema[A]): Published =
    Published(name, schema)

  /** Every coproduct reachable from an endpoint's request or response body. */
  private val published: List[Published] = List(
    entry[DistributionShapeRequest]("DistributionShapeRequest"),
    entry[DistributionShape]("DistributionShape"),
    entry[DistributionParams]("DistributionParams"),
    entry[MitigationSpec]("MitigationSpec"),
    entry[RelativeLikelihood]("RelativeLikelihood"),
    entry[RelativeSeverity]("RelativeSeverity"),
    entry[AbsoluteReplacement]("AbsoluteReplacement"),
    entry[ResultTransformSpec]("ResultTransformSpec"),
    entry[SubmittedCollection]("SubmittedCollection"),
    entry[ErrorSubject]("ErrorSubject"),
    entry[FieldName]("FieldName"),
    entry[ErrorLocation]("ErrorLocation"),
    entry[RequestHeaderName]("RequestHeaderName"),
    entry[ConflictKind]("ConflictKind"),
    entry[MergeRefusal]("MergeRefusal"),
    entry[RejectionDetail]("RejectionDetail"),
    entry[QueryProblem]("QueryProblem"),
    entry[ScenarioSourceDto]("ScenarioSourceDto"),
    entry[MergePreviewStatus]("MergePreviewStatus")
  )

  /** A published type is an offender when its schema offers alternatives and
    * names no key to choose between them. */
  private def namesNoKey(p: Published): Boolean = p.schema.schemaType match
    case c: SchemaType.SCoproduct[?] => c.discriminator.isEmpty
    case _                           => false

  /** The values a published coproduct offers for its key, sorted. Empty where
    * the schema names no key at all. */
  private def publishedValues(name: String): List[String] =
    published.find(_.name == name).toList.flatMap(_.schema.schemaType match
      case c: SchemaType.SCoproduct[?] => c.discriminator.toList.flatMap(_.mapping.keys).sorted
      case _                           => Nil
    )

  def spec = suite("PublishedDiscriminatorSpec")(

    test("every published coproduct names its discriminator") {
      val offenders = published.filter(namesNoKey).map(_.name).sorted
      assertTrue(offenders == List.empty[String])
    },

    /** Every other entry's values are its own case names, which the derivation
      * reads off the type the decoder matches on. The loss distribution is the
      * one whose schema derives from a different type — a private coproduct
      * holding the wire's two parallel sequences — so its values come from that
      * type's case names and can part from the decoder's without failing
      * anything else here. */
    test("the loss distribution publishes its two mode names") {
      assertTrue(publishedValues("DistributionShape") == List("Lognormal", "Metalog"))
    }
  )
}
