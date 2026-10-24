package com.risquanter.register.http.requests

import zio.json.{JsonCodec, DeriveJsonCodec, jsonDiscriminator}
import zio.prelude.Validation
import sttp.tapir.Schema
import sttp.tapir.generic.Configuration

import com.risquanter.register.domain.data.DistributionShape
import com.risquanter.register.domain.errors.{ErrorSubject, ValidationError}

/** A submitted loss distribution shape, in the two modes an author can declare.
  *
  * One case per mode, so a decoded submission holds the fields of exactly one
  * mode: a metalog value has nowhere to put a lognormal bound, and a lognormal
  * value has nowhere to put anchors. A mode stated without its fields is
  * refused, because each case's fields are required.
  *
  * The derived decoder ignores keys the named case does not declare, so a body
  * naming one mode and also carrying the other's fields decodes to the named
  * mode alone and the surplus values are discarded rather than refused.
  *
  * Carries primitives rather than refined types, and `toShape` is the gate.
  * The gate takes the subject because the same body appears inside a collection
  * of submitted leaves, and a refusal has to name which one it is about.
  *
  * Percentile values are in domain scale 0–1. The browser form's 0–100 display
  * values are divided by 100 before being serialised into this type.
  */
@jsonDiscriminator(DistributionShape.TagField)
sealed trait DistributionShapeRequest:
  /** Refine and gate this submission into a domain shape, reporting every
    * refusal against `subject`. */
  def toShape(subject: ErrorSubject): Validation[ValidationError, DistributionShape]

object DistributionShapeRequest:
  /** Metalog: the author's anchor points as two parallel sequences, and
    * optionally the number of metalog terms to fit. An absent term count means
    * the fit derives one from the anchors. */
  final case class Metalog(
    percentiles: Vector[Double],
    quantiles:   Vector[Double],
    terms:       Option[Int]
  ) extends DistributionShapeRequest:
    def toShape(subject: ErrorSubject): Validation[ValidationError, DistributionShape] =
      DistributionShape.Metalog.fromPrimitives(percentiles, quantiles, terms, subject)

  /** Lognormal: the two bounds of a 90% confidence interval. The confidence
    * level is not submitted — it is fixed by the method the fit uses. */
  final case class Lognormal(
    minLoss: Long,
    maxLoss: Long
  ) extends DistributionShapeRequest:
    def toShape(subject: ErrorSubject): Validation[ValidationError, DistributionShape] =
      DistributionShape.Lognormal.fromPrimitives(minLoss, maxLoss, subject)

  /** The submitted form of a shape, and the inverse of `toShape`. The browser
    * holds a draft as a domain value and has to put it on the wire, so the
    * projection lives beside the gate rather than on the domain type — the
    * request layer knows the domain and not the other way round. */
  def fromShape(shape: DistributionShape): DistributionShapeRequest = shape match
    case DistributionShape.Metalog(anchors, terms) =>
      Metalog(anchors.percentiles, anchors.losses, terms.map(t => t: Int))
    case DistributionShape.Lognormal(bounds) =>
      Lognormal(bounds.min, bounds.max)

  given codec: JsonCodec[DistributionShapeRequest] = DeriveJsonCodec.gen[DistributionShapeRequest]

  given schema: Schema[DistributionShapeRequest] =
    import sttp.tapir.generic.auto.*
    given Configuration = Configuration.default.withDiscriminator(DistributionShape.TagField)
    Schema.derived[DistributionShapeRequest]
