package com.risquanter.register.domain.data

import zio.prelude.*
import zio.json.{JsonCodec, JsonEncoder, JsonDecoder, DeriveJsonCodec}
import com.risquanter.register.domain.data.iron.{NonNegativeDouble, NonNegativeLong, ValidationMessages, ValidationUtil}
import com.risquanter.register.domain.errors.{ValidationError, ValidationErrorCode}

/**
 * Reified result-stage transform: pure data describing one atomic operation on
 * `TrialOutcomes` — comparable, hashable, serializable (unlike the opaque
 * function inside `RiskResultTransform`). `toTransform` is the single
 * interpreter from spec to executable transform.
 *
 * Wire format is discriminated by an `op` field; each case validates its own
 * `op` tag during decode, so decoding is unambiguous even where cases share
 * field shapes (e.g. `InsurancePolicy` carries a `deductible` like
 * `ApplyDeductible`).
 */
sealed trait ResultTransformSpec

object ResultTransformSpec {

  final case class ApplyDeductible(deductible: NonNegativeLong)     extends ResultTransformSpec
  final case class CapLosses(cap: NonNegativeLong)                  extends ResultTransformSpec
  final case class ScaleLosses(factor: NonNegativeDouble)           extends ResultTransformSpec
  final case class FilterBelowThreshold(threshold: NonNegativeLong) extends ResultTransformSpec

  /** Deductible-then-cap pair; cross-field rule `cap > deductible` (ADR-001). */
  final case class InsurancePolicy private (deductible: NonNegativeLong, cap: NonNegativeLong)
      extends ResultTransformSpec

  object InsurancePolicy {
    def create(
      deductible: NonNegativeLong,
      cap: NonNegativeLong
    ): Validation[ValidationError, InsurancePolicy] =
      if (cap > deductible) Validation.succeed(InsurancePolicy(deductible, cap))
      else
        Validation.fail(ValidationError(
          field = "insurancePolicy.cap",
          code = ValidationErrorCode.INVALID_COMBINATION,
          message = ValidationMessages.capMustExceedDeductible
        ))
  }

  /** Single interpreter: spec → executable transform. */
  def toTransform(spec: ResultTransformSpec): RiskResultTransform = spec match {
    case ApplyDeductible(d)       => RiskResultTransform.applyDeductible(d)
    case CapLosses(c)             => RiskResultTransform.capLosses(c)
    case ScaleLosses(f)           => RiskResultTransform.scaleLosses(f)
    case FilterBelowThreshold(t)  => RiskResultTransform.filterBelowThreshold(t)
    case InsurancePolicy(d, c)    =>
      RiskResultTransform.applyDeductible(d).andThen(RiskResultTransform.capLosses(c))
  }

  // Lawful: structural equality on scalar data (unlike the deleted
  // Equal[RiskTransform], whose function field compared by reference).
  given Equal[ResultTransformSpec] = Equal.default

  // ── Wire format: op-discriminated per-case Raw (DistributionParams precedent) ──

  private object Op {
    val applyDeductible      = "applyDeductible"
    val capLosses            = "capLosses"
    val scaleLosses          = "scaleLosses"
    val filterBelowThreshold = "filterBelowThreshold"
    val insurancePolicy      = "insurancePolicy"
  }

  private case class RawDeductible(op: String, deductible: Long)
  private object RawDeductible { given c: JsonCodec[RawDeductible] = DeriveJsonCodec.gen }
  private case class RawCap(op: String, cap: Long)
  private object RawCap { given c: JsonCodec[RawCap] = DeriveJsonCodec.gen }
  private case class RawScale(op: String, factor: Double)
  private object RawScale { given c: JsonCodec[RawScale] = DeriveJsonCodec.gen }
  private case class RawThreshold(op: String, threshold: Long)
  private object RawThreshold { given c: JsonCodec[RawThreshold] = DeriveJsonCodec.gen }
  private case class RawPolicy(op: String, deductible: Long, cap: Long)
  private object RawPolicy { given c: JsonCodec[RawPolicy] = DeriveJsonCodec.gen }

  private def requireOp[A](expected: String, actual: String)(a: => Either[String, A]): Either[String, A] =
    if (actual == expected) a else Left(s"expected op '$expected', found '$actual'")

  private def firstError(errors: List[ValidationError]): String =
    errors.map(_.message).mkString("; ")

  private val applyDeductibleCodec: JsonCodec[ApplyDeductible] = JsonCodec(
    JsonEncoder[RawDeductible].contramap(s => RawDeductible(Op.applyDeductible, s.deductible)),
    JsonDecoder[RawDeductible].mapOrFail { raw =>
      requireOp(Op.applyDeductible, raw.op) {
        ValidationUtil.refineNonNegativeLong(raw.deductible, "deductible")
          .map(ApplyDeductible(_)).left.map(firstError)
      }
    }
  )

  private val capLossesCodec: JsonCodec[CapLosses] = JsonCodec(
    JsonEncoder[RawCap].contramap(s => RawCap(Op.capLosses, s.cap)),
    JsonDecoder[RawCap].mapOrFail { raw =>
      requireOp(Op.capLosses, raw.op) {
        ValidationUtil.refineNonNegativeLong(raw.cap, "cap")
          .map(CapLosses(_)).left.map(firstError)
      }
    }
  )

  private val scaleLossesCodec: JsonCodec[ScaleLosses] = JsonCodec(
    JsonEncoder[RawScale].contramap(s => RawScale(Op.scaleLosses, s.factor)),
    JsonDecoder[RawScale].mapOrFail { raw =>
      requireOp(Op.scaleLosses, raw.op) {
        ValidationUtil.refineNonNegativeDouble(raw.factor, "factor")
          .map(ScaleLosses(_)).left.map(firstError)
      }
    }
  )

  private val filterBelowThresholdCodec: JsonCodec[FilterBelowThreshold] = JsonCodec(
    JsonEncoder[RawThreshold].contramap(s => RawThreshold(Op.filterBelowThreshold, s.threshold)),
    JsonDecoder[RawThreshold].mapOrFail { raw =>
      requireOp(Op.filterBelowThreshold, raw.op) {
        ValidationUtil.refineNonNegativeLong(raw.threshold, "threshold")
          .map(FilterBelowThreshold(_)).left.map(firstError)
      }
    }
  )

  private val insurancePolicyCodec: JsonCodec[InsurancePolicy] = JsonCodec(
    JsonEncoder[RawPolicy].contramap(s => RawPolicy(Op.insurancePolicy, s.deductible, s.cap)),
    JsonDecoder[RawPolicy].mapOrFail { raw =>
      requireOp(Op.insurancePolicy, raw.op) {
        (ValidationUtil.refineNonNegativeLong(raw.deductible, "deductible"),
         ValidationUtil.refineNonNegativeLong(raw.cap, "cap")) match {
          case (Right(d), Right(c)) => InsurancePolicy.create(d, c).toEither.left.map(e => firstError(e.toList))
          case (Left(e1), Left(e2)) => Left(firstError(e1 ++ e2))
          case (Left(e), _)         => Left(firstError(e))
          case (_, Left(e))         => Left(firstError(e))
        }
      }
    }
  )

  given codec: JsonCodec[ResultTransformSpec] = JsonCodec(
    new JsonEncoder[ResultTransformSpec] {
      override def unsafeEncode(a: ResultTransformSpec, indent: Option[Int], out: zio.json.internal.Write): Unit =
        a match {
          case s: ApplyDeductible      => applyDeductibleCodec.encoder.unsafeEncode(s, indent, out)
          case s: CapLosses            => capLossesCodec.encoder.unsafeEncode(s, indent, out)
          case s: ScaleLosses          => scaleLossesCodec.encoder.unsafeEncode(s, indent, out)
          case s: FilterBelowThreshold => filterBelowThresholdCodec.encoder.unsafeEncode(s, indent, out)
          case s: InsurancePolicy      => insurancePolicyCodec.encoder.unsafeEncode(s, indent, out)
        }
    },
    applyDeductibleCodec.decoder.widen[ResultTransformSpec] <>
      capLossesCodec.decoder.widen[ResultTransformSpec] <>
      scaleLossesCodec.decoder.widen[ResultTransformSpec] <>
      filterBelowThresholdCodec.decoder.widen[ResultTransformSpec] <>
      insurancePolicyCodec.decoder.widen[ResultTransformSpec]
  )
}

/**
 * Ordered list of result-stage operations: position is application order;
 * interpretation folds front-to-back with `andThen`; combining pipelines is
 * list concatenation (appends, never reorders). Flat by design — the
 * recursive alternative gave one sequence multiple representations and needed
 * special recursive serialization (PLAN-RISKTRANSFORM D1).
 */
final case class TransformPipeline(steps: List[ResultTransformSpec])

object TransformPipeline {

  val empty: TransformPipeline = TransformPipeline(Nil)

  /** Associative with `empty` as identity; deliberately NOT commutative (order matters). */
  given Identity[TransformPipeline] with {
    def identity: TransformPipeline = empty
    def combine(l: => TransformPipeline, r: => TransformPipeline): TransformPipeline =
      TransformPipeline(l.steps ++ r.steps)
  }

  given Equal[TransformPipeline] = Equal.default

  given JsonCodec[TransformPipeline] =
    JsonCodec[List[ResultTransformSpec]].transform(TransformPipeline(_), _.steps)

  /** Law (tested): toTransform(a <> b) behaves as toTransform(a) andThen toTransform(b). */
  def toTransform(p: TransformPipeline): RiskResultTransform =
    p.steps.foldLeft(RiskResultTransform.identityTransform)((acc, s) =>
      acc.andThen(ResultTransformSpec.toTransform(s))
    )
}
