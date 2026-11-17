package app.state

import zio.test.*
import com.raquo.laminar.api.L.*
import com.raquo.airstream.ownership.ManualOwner

import com.risquanter.register.domain.data.AbsoluteReplacement
import com.risquanter.register.domain.data.iron.{MitigationId, NodeId, ResidualProbability, ValidationMessages, ValidationUtil}
import com.risquanter.register.domain.errors.{ErrorLocation, ErrorSubject, FieldName, FieldProblem, ValueRange}
import com.risquanter.register.testutil.TestHelpers.lognormalShape

/** Tests for the override edit popup's form: it starts from the stored
  * figures, keeps the case it was opened on, refuses a likelihood outside the
  * residual range at its own input, and accumulates every part's refusals. */
object AbsoluteFiguresFormStateSpec extends ZIOSpecDefault:

  /** Observe a signal's current value under a throwaway owner. */
  private def current[A](signal: Signal[A]): A =
    given owner: ManualOwner = new ManualOwner
    var seen: Option[A] = None
    signal.foreach(a => seen = Some(a))
    owner.killSubscriptions()
    seen.get

  private val subject = ErrorSubject.Mitigation(MitigationId(NodeId.fromString("01HX9ABCDE0000000000000010").toOption.get.toSafeId))

  private def residual(p: Double): ResidualProbability =
    ValidationUtil.refineResidualProbability(p, ErrorLocation.Whole).toOption.get

  private val likelihood = AbsoluteReplacement.Likelihood(residual(0.02))
  private val severity   = AbsoluteReplacement.Severity(lognormalShape(1000L, 5000L))
  private val both       = AbsoluteReplacement.Both(residual(0.02), lognormalShape(1000L, 5000L))

  private def at(field: FieldName): ErrorLocation = ErrorLocation.Field(subject, field)

  /** The (location, problem) pairs a refused read reports. */
  private def refusals(result: zio.prelude.Validation[com.risquanter.register.domain.errors.ValidationError, ?]) =
    result.toEither.left.toOption.map(_.toList.map(e => (e.location, e.problem))).getOrElse(Nil)

  def spec = suite("AbsoluteFiguresFormState")(

    test("the fields start from the stored figures, on the scale they are entered on") {
      val form = new AbsoluteFiguresFormState(both, subject)
      assertTrue(
        form.probabilityVar.now() == "2",
        form.distributionModeVar.now() == DistributionMode.Lognormal,
        form.minLossVar.now() == "1000",
        form.maxLossVar.now() == "5000"
      )
    },

    test("toReplacement keeps the case the form was opened on, for each of the three") {
      assertTrue(
        new AbsoluteFiguresFormState(likelihood, subject).toReplacement.toOption.contains(likelihood),
        new AbsoluteFiguresFormState(severity, subject).toReplacement.toOption.contains(severity),
        new AbsoluteFiguresFormState(both, subject).toReplacement.toOption.contains(both)
      )
    },

    test("an edited likelihood is read on the 0–100 scale") {
      val form = new AbsoluteFiguresFormState(likelihood, subject)
      form.probabilityVar.set("5")
      assertTrue(form.toReplacement.toOption.contains(AbsoluteReplacement.Likelihood(residual(0.05))))
    },

    test("a likelihood outside the residual range is refused at its own input") {
      val above = new AbsoluteFiguresFormState(likelihood, subject)
      above.probabilityVar.set("150")
      val zero = new AbsoluteFiguresFormState(likelihood, subject)
      zero.probabilityVar.set("0")
      val outOfRange = List((at(FieldName.Probability), FieldProblem.OutOfRange(ValueRange.ResidualPercent)))
      assertTrue(
        refusals(above.toReplacement) == outOfRange,
        refusals(zero.toReplacement) == outOfRange,
        ValidationMessages.render(FieldProblem.OutOfRange(ValueRange.ResidualPercent)).contains("at most 100")
      )
    },

    test("under Both a refused likelihood and a refused shape are reported together") {
      val form = new AbsoluteFiguresFormState(both, subject)
      form.probabilityVar.set("")
      form.minLossVar.set("")
      assertTrue(refusals(form.toReplacement) == List(
        (at(FieldName.Probability), FieldProblem.Required),
        (at(FieldName.MinLoss), FieldProblem.Required)
      ))
    },

    test("a stored refusal shows under the input it names once the popup has applied") {
      val form = new AbsoluteFiguresFormState(both, subject)
      form.minLossVar.set("")
      form.triggerValidation()
      form.setSubmitErrors(form.toReplacement.toEither.left.toOption.toList.flatMap(_.toList))
      assertTrue(current(form.minLossError).nonEmpty, current(form.probabilityError).isEmpty)
    },

    test("a refusal the leaf form shows under the name or the parent reaches no input of the popup") {
      assertTrue(
        AbsoluteFiguresField.inputsFor(at(FieldName.Name)).isEmpty,
        AbsoluteFiguresField.inputsFor(at(FieldName.ParentName)).isEmpty,
        AbsoluteFiguresField.inputsFor(at(FieldName.LossRange)) ==
          Set(AbsoluteFiguresField.Shape(ShapeInput.MinLoss), AbsoluteFiguresField.Shape(ShapeInput.MaxLoss))
      )
    },

    test("the popup's input ids differ from the leaf form's") {
      val form = new AbsoluteFiguresFormState(severity, subject)
      assertTrue(form.inputIdOf(ShapeInput.MinLoss) != (new RiskLeafFormState).inputIdOf(ShapeInput.MinLoss))
    }
  )
