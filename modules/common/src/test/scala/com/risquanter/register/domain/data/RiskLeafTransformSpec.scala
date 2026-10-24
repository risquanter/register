package com.risquanter.register.domain.data

import zio.test.*
import zio.prelude.Validation
import io.github.iltotore.iron.refineUnsafe
import com.risquanter.register.domain.data.iron.{RetentionFactor, ValidationUtil}
import com.risquanter.register.domain.errors.{ErrorLocation, ErrorSubject, FieldName, ValidationError, ValidationErrorCode}
import com.risquanter.register.testutil.TestHelpers.{
  metalogShape, lognormalShape, nodeId, residualProbability, retentionFactor
}

/**
 * The leaf-stage arrow: `LeafParams => Validation[ValidationError, LeafParams]`.
 *
 * Three things are checked. The monoid laws the arrow owes — identity on both
 * sides, associativity, and that an absolute replacement absorbs what precedes
 * it. The per-mode reading of severity scaling, which is the one component with
 * arithmetic of its own. And closure: whatever the factors, the output is a
 * shape that passed its own gate, so no downstream caller has to re-check it.
 */
object RiskLeafTransformSpec extends ZIOSpecDefault {

  private val subject: ErrorSubject = ErrorSubject.Request
  private val at: ErrorLocation     = ErrorLocation.Field(subject, FieldName.OverrideParams)

  /** The stamp and anchor an absolute replacement carries. Their values are
    * irrelevant to the arrow — it reads only the replacement — but the type
    * requires them, which is the rule that used to be a runtime refusal. */
  private val anchor = nodeId("root-pf")
  private val stamp  = com.risquanter.register.domain.data.iron.ContentHash.fromString("b" * 64).toOption.get

  private def lognormalParams(prob: Double = 0.4, min: Long = 1000L, max: Long = 100000L): LeafParams =
    LeafParams(prob.refineUnsafe, lognormalShape(min, max))

  private def metalogParams(prob: Double = 0.4): LeafParams =
    LeafParams(prob.refineUnsafe, metalogShape(Vector(0.1, 0.5, 0.9), Vector(1000.0, 5000.0, 20000.0), Some(3)))

  private def run(spec: LeafStageSpec, params: LeafParams): Validation[ValidationError, LeafParams] =
    LeafStageSpec.applyTo(spec, params, subject)

  private def ok(spec: LeafStageSpec, params: LeafParams): LeafParams =
    run(spec, params).toEither.toOption.get

  /** The identity arrow: a relative statement that changes neither component. */
  private val keep: LeafStageSpec =
    MitigationSpec.LeafRelative(RelativeLikelihood.Keep, RelativeSeverity.Keep)

  private def scaleLikelihood(f: Double): LeafStageSpec =
    MitigationSpec.LeafRelative(RelativeLikelihood.Scale(retentionFactor(f)), RelativeSeverity.Keep)

  private def scaleSeverity(f: Double): LeafStageSpec =
    MitigationSpec.LeafRelative(RelativeLikelihood.Keep, RelativeSeverity.ScaleSeverity(retentionFactor(f)))

  private def replaceLikelihood(p: Double): LeafStageSpec =
    MitigationSpec.LeafAbsolute(AbsoluteReplacement.Likelihood(residualProbability(p)), anchor, stamp)

  private def replaceSeverity(shape: DistributionShape): LeafStageSpec =
    MitigationSpec.LeafAbsolute(AbsoluteReplacement.Severity(shape), anchor, stamp)

  def spec = suite("LeafStageSpec.applyTo")(

    suite("the laws the arrow owes")(
      test("Keep/Keep is the identity on both components") {
        val params = lognormalParams()
        assertTrue(run(keep, params) == Validation.succeed(params))
      },
      test("Keep is a left and a right identity of composition") {
        val params = lognormalParams()
        val scale  = scaleLikelihood(0.5)
        val left   = run(keep, params).flatMap(p => run(scale, p))
        val right  = run(scale, params).flatMap(p => run(keep, p))
        assertTrue(left == right, left == run(scale, params))
      },
      test("composition is associative") {
        val params = lognormalParams()
        val a      = scaleLikelihood(0.5)
        val b      = scaleSeverity(0.5)
        val c      = scaleLikelihood(0.8)
        val leftFirst  = run(a, params).flatMap(p => run(b, p)).flatMap(p => run(c, p))
        val rightFirst = run(a, params).flatMap(p => run(b, p).flatMap(q => run(c, q)))
        assertTrue(leftFirst == rightFirst)
      },
      test("an absolute replacement absorbs what precedes it") {
        val params  = lognormalParams(prob = 0.4)
        val replace = replaceLikelihood(0.05)
        val after   = run(scaleLikelihood(0.5), params).flatMap(p => run(replace, p))
        assertTrue(after == run(replace, params))
      },
      test("an absolute replacement does not absorb what follows it") {
        val params  = lognormalParams(prob = 0.4)
        val replace = replaceLikelihood(0.05)
        val before  = run(replace, params).flatMap(p => run(scaleLikelihood(0.5), p))
        assertTrue(before != run(replace, params))
      }
    ),

    suite("closure — the output always passed a shape gate")(
      test("property: every factor pair yields a valid result for both modes") {
        val factors: Gen[Any, RetentionFactor] = Gen.double(0.1, 1.0).map(retentionFactor)
        check(factors, factors) { (lf, df) =>
          val both = MitigationSpec.LeafRelative(
            RelativeLikelihood.Scale(lf), RelativeSeverity.ScaleSeverity(df))
          assertTrue(
            run(both, lognormalParams()).toEither.isRight,
            run(both, metalogParams()).toEither.isRight
          )
        }
      }
    ),

    suite("the two fractions are distinct types")(
      // Both refine one predicate, Double in (0, 1], so nothing but the wrapper
      // keeps a factor out of a parameter that wants a probability. The
      // assertion is on compilation rather than on a value, because a type
      // confusion that compiles produces a wrong figure and no failure.
      test("a retention factor does not satisfy a residual probability") {
        typeCheck(
          "AbsoluteReplacement.Likelihood(retentionFactor(0.5))"
        ).map(result => assertTrue(result.isLeft))
      },
      test("a residual probability does not satisfy a retention factor") {
        typeCheck(
          "RelativeLikelihood.Scale(residualProbability(0.5))"
        ).map(result => assertTrue(result.isLeft))
      },
      test("each satisfies its own parameter") {
        typeCheck(
          "AbsoluteReplacement.Likelihood(residualProbability(0.5))"
        ).zipWith(typeCheck("RelativeLikelihood.Scale(retentionFactor(0.5))"))(
          (likelihood, scale) => assertTrue(likelihood.isRight, scale.isRight)
        )
      }
    ),

    suite("likelihood component")(
      test("Scale multiplies the probability") {
        val out = ok(scaleLikelihood(0.5), lognormalParams(prob = 0.4))
        assertTrue(math.abs((out.probability: Double) - 0.2) < 1e-9)
      },
      test("a factor above one is refused at the boundary, so no clamp is needed") {
        assertTrue(ValidationUtil.refineRetentionFactor(4.0, at).isLeft)
      },
      test("a replacement cannot state a probability of zero — no mitigation eliminates a risk") {
        assertTrue(
          ValidationUtil.refineResidualProbability(0.0, at).isLeft,
          ValidationUtil.refineResidualProbability(0.05, at).isRight
        )
      },
      test("an absolute replacement replaces the probability and leaves the shape") {
        val params = lognormalParams(prob = 0.4)
        val out    = ok(replaceLikelihood(0.05), params)
        assertTrue((out.probability: Double) == 0.05, out.shape == params.shape)
      }
    ),

    suite("severity component — ScaleSeverity")(
      test("lognormal: both bounds scale") {
        val out = ok(scaleSeverity(0.5), lognormalParams(min = 1000L, max = 100000L))
        out.shape match
          case DistributionShape.Lognormal(bounds) =>
            assertTrue((bounds.min: Long) == 500L, (bounds.max: Long) == 50000L)
          case other => assertTrue(false).label(s"expected a lognormal shape, got $other")
      },
      test("lognormal: outward rounding keeps adjacent bounds apart") {
        // Round-to-nearest sent 50.5 and 51.005 both to 51, collapsing the
        // interval and failing the strict min-below-max rule.
        val out = ok(scaleSeverity(0.505), lognormalParams(min = 100L, max = 101L))
        out.shape match
          case DistributionShape.Lognormal(bounds) =>
            assertTrue((bounds.min: Long) == 50L, (bounds.max: Long) == 52L)
          case other => assertTrue(false).label(s"expected a lognormal shape, got $other")
      },
      test("lognormal: a sub-unit lower bound is refused, naming the smallest workable factor") {
        val result = run(scaleSeverity(0.0005), lognormalParams(min = 1000L, max = 100000L))
        val errors = result.toEither.swap.toOption.toList.flatMap(_.toChunk.toList)
        assertTrue(
          errors.exists(_.code == ValidationErrorCode.INVALID_LOGNORMAL_PARAMS),
          errors.exists(_.message.contains("1.000e-03"))
        )
      },
      test("metalog: anchor losses scale and percentiles do not") {
        val out = ok(scaleSeverity(0.5), metalogParams())
        out.shape match
          case DistributionShape.Metalog(anchors, _) =>
            assertTrue(
              anchors.losses == Vector(500.0, 2500.0, 10000.0),
              anchors.percentiles == Vector(0.1, 0.5, 0.9)
            )
          case other => assertTrue(false).label(s"expected a metalog shape, got $other")
      },
      test("metalog: a stated term count survives scaling") {
        val out = ok(scaleSeverity(0.5), metalogParams())
        out.shape match
          case DistributionShape.Metalog(_, terms) => assertTrue(terms.map(t => t: Int) == Some(3))
          case other => assertTrue(false).label(s"expected a metalog shape, got $other")
      }
    ),

    suite("bound arithmetic")(
      test("scaleSeverityBounds floors the lower bound and ceils the upper") {
        assertTrue(
          RiskLeafTransform.scaleSeverityBounds(100L, 101L, 0.505) == ((50L, 52L)),
          RiskLeafTransform.scaleSeverityBounds(1000L, 100000L, 0.5) == ((500L, 50000L)),
          RiskLeafTransform.scaleSeverityBounds(7L, 9L, 1.0) == ((7L, 9L))
        )
      },
      test("lowerBoundRepresentable holds at one whole unit and fails below it") {
        assertTrue(
          RiskLeafTransform.lowerBoundRepresentable(1L),
          !RiskLeafTransform.lowerBoundRepresentable(0L)
        )
      },
      test("minRepresentableFactor is the reciprocal of the lower bound") {
        assertTrue(
          RiskLeafTransform.minRepresentableFactor(1000L) == 0.001,
          RiskLeafTransform.minRepresentableFactor(40000L) == 0.000025
        )
      }
    ),

    suite("severity component — absolute replacement")(
      test("a replacement switches representation wholesale") {
        val replacement = metalogShape(Vector(0.1, 0.9), Vector(500.0, 8000.0), Some(2))
        val out         = ok(replaceSeverity(replacement), lognormalParams())
        assertTrue(out.shape == replacement, out.shape.isInstanceOf[DistributionShape.Metalog])
      },
      test("a replacement leaves the probability untouched") {
        val replacement = metalogShape(Vector(0.1, 0.9), Vector(500.0, 8000.0), Some(2))
        val params      = lognormalParams(prob = 0.4)
        val out         = ok(replaceSeverity(replacement), params)
        assertTrue(out.probability == params.probability)
      },
      test("a replacement that states both components replaces both") {
        val replacement = metalogShape(Vector(0.1, 0.9), Vector(500.0, 8000.0), Some(2))
        val both: LeafStageSpec = MitigationSpec.LeafAbsolute(
          AbsoluteReplacement.Both(residualProbability(0.05), replacement), anchor, stamp)
        val out = ok(both, lognormalParams(prob = 0.4))
        assertTrue((out.probability: Double) == 0.05, out.shape == replacement)
      }
    )
  )
}
