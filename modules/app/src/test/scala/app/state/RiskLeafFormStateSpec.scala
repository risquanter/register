package app.state

import zio.test.*
import zio.prelude.Validation
import com.raquo.laminar.api.L.*
import com.raquo.airstream.ownership.ManualOwner

import com.risquanter.register.domain.data.iron.NodeId
import com.risquanter.register.domain.errors.{ErrorLocation, ErrorSubject, FieldName}

object RiskLeafFormStateSpec extends ZIOSpecDefault:

  /** Observe a signal's current value under a throwaway owner. */
  private def current[A](signal: Signal[A]): A =
    given owner: ManualOwner = new ManualOwner
    var seen: Option[A] = None
    signal.foreach(a => seen = Some(a))
    owner.killSubscriptions()
    seen.get

  private val editedSubject: ErrorSubject =
    ErrorSubject.ExistingNode(NodeId.fromString("01HX9ABCDE0000000000000001").toOption.get)

  /** A leaf form that would submit cleanly in lognormal mode, opened on an
    * existing node so a refusal's subject is distinguishable from the
    * default. Each test breaks one field. */
  private def validLognormalForm(): RiskLeafFormState =
    val st = new RiskLeafFormState
    st.subjectVar.set(editedSubject)
    st.nameVar.set("Server outage")
    st.parentVar.set(ParentSelection.Root)
    st.probabilityVar.set("10")
    st.distributionModeVar.set(DistributionMode.Lognormal)
    st.minLossVar.set("1000")
    st.maxLossVar.set("50000")
    st

  /** The same, in metalog mode. */
  private def validMetalogForm(): RiskLeafFormState =
    val st = validLognormalForm()
    st.distributionModeVar.set(DistributionMode.Metalog)
    st.percentilesVar.set("10, 50, 90")
    st.quantilesVar.set("1000, 5000, 25000")
    st

  /** The locations of every refusal a submission reports. */
  private def refusalLocations(st: RiskLeafFormState): Set[ErrorLocation] =
    st.toDraft match
      case Validation.Failure(_, es) => es.toList.map(_.location).toSet
      case Validation.Success(_, _)  => Set.empty

  private def at(field: FieldName): ErrorLocation = ErrorLocation.Field(editedSubject, field)

  /** The sentences a submission reports at one field. */
  private def submittedAt(st: RiskLeafFormState, field: FieldName): List[String] =
    st.toDraft match
      case Validation.Failure(_, es) => es.toList.filter(_.location == at(field)).map(_.message)
      case Validation.Success(_, _)  => Nil

  def spec = suite("RiskLeafFormState")(

    suite("a submitted refusal names its own field and the form's subject")(

      test("the valid forms submit cleanly") {
        assertTrue(
          refusalLocations(validLognormalForm()).isEmpty,
          refusalLocations(validMetalogForm()).isEmpty
        )
      },

      test("name") {
        val st = validLognormalForm()
        st.nameVar.set("")
        assertTrue(refusalLocations(st) == Set(at(FieldName.Name)))
      },

      test("parent") {
        val st = validLognormalForm()
        st.parentVar.set(ParentSelection.Unset)
        assertTrue(refusalLocations(st) == Set(at(FieldName.ParentName)))
      },

      test("probability that is not a number") {
        val st = validLognormalForm()
        st.probabilityVar.set("abc")
        assertTrue(refusalLocations(st) == Set(at(FieldName.Probability)))
      },

      test("probability refused after parsing and rescaling keeps the field it was read from") {
        // "150" is parsed, divided by 100 and only then refused against the
        // closed interval, so the location crosses two transformations.
        val st = validLognormalForm()
        st.probabilityVar.set("150")
        assertTrue(refusalLocations(st) == Set(at(FieldName.Probability)))
      },

      test("both lognormal bounds, reported together") {
        val st = validLognormalForm()
        st.minLossVar.set("")
        st.maxLossVar.set("")
        assertTrue(refusalLocations(st) == Set(at(FieldName.MinLoss), at(FieldName.MaxLoss)))
      },

      test("an unordered loss range names the pair") {
        val st = validLognormalForm()
        st.minLossVar.set("50000")
        st.maxLossVar.set("1000")
        assertTrue(refusalLocations(st) == Set(at(FieldName.LossRange)))
      },

      test("percentiles that do not increase") {
        val st = validMetalogForm()
        st.percentilesVar.set("90, 50, 10")
        assertTrue(refusalLocations(st) == Set(at(FieldName.Percentiles)))
      },

      test("unequal percentile and quantile counts name the pairing") {
        val st = validMetalogForm()
        st.quantilesVar.set("1000, 5000")
        assertTrue(refusalLocations(st) == Set(at(FieldName.PercentileQuantilePairing)))
      },

      test("a term count outside the fitter's range") {
        val st = validMetalogForm()
        st.termsVar.set("1")
        assertTrue(refusalLocations(st) == Set(at(FieldName.Terms)))
      },

      test("a new form names the draft node") {
        val st = validLognormalForm()
        st.subjectVar.set(ErrorSubject.DraftNode)
        st.nameVar.set("")
        assertTrue(refusalLocations(st) == Set(ErrorLocation.Field(ErrorSubject.DraftNode, FieldName.Name)))
      }

    ),

    suite("the live check and the submit refuse an input alike")(

      test("probability out of range, blank, and not a number give one sentence on both paths") {
        val cases = List("150", "", "abc").map { entered =>
          val st = validLognormalForm()
          st.probabilityVar.set(entered)
          st.markTouched(RiskLeafField.Probability)
          (current(st.probabilityError).toList, submittedAt(st, FieldName.Probability))
        }
        assertTrue(cases.forall((live, submitted) => live.nonEmpty && live == submitted))
      },

      test("a loss bound that is not a whole number gives one sentence on both paths") {
        val st = validLognormalForm()
        st.minLossVar.set("abc")
        st.markTouched(RiskLeafField.MinLoss)
        val live = current(st.minLossError).toList
        assertTrue(live.nonEmpty, live == submittedAt(st, FieldName.MinLoss))
      },

      test("probability accepts both ends of the closed interval") {
        val atZero = validLognormalForm()
        atZero.probabilityVar.set("0")
        val atHundred = validLognormalForm()
        atHundred.probabilityVar.set("100")
        assertTrue(
          refusalLocations(atZero).isEmpty,
          refusalLocations(atHundred).isEmpty,
          current(atZero.probabilityError).isEmpty,
          current(atHundred.probabilityError).isEmpty
        )
      },

      test("the term count is judged against the anchors that parse") {
        // "abc" is not an anchor, so two anchors remain and three terms is too many.
        val st = validMetalogForm()
        st.percentilesVar.set("10, abc, 90")
        st.quantilesVar.set("1000, 25000")
        st.termsVar.set("3")
        st.markTouched(RiskLeafField.Terms)
        assertTrue(
          current(st.termsError).isDefined,
          refusalLocations(st).contains(at(FieldName.Terms))
        )
      }

    ),

    suite("draftSignal")(

      test("builds the shape the submit builds") {
        val st = validMetalogForm()
        assertTrue(current(st.draftSignal) == st.currentShapeValidation().toOption)
      },

      test("is empty while the fields do not form a shape") {
        val st = validLognormalForm()
        st.maxLossVar.set("")
        assertTrue(current(st.draftSignal).isEmpty)
      }

    ),

    suite("lognormal bound positivity")(

      test("a minLoss of 0 shows the positivity error") {
        val st = new RiskLeafFormState
        st.distributionModeVar.set(DistributionMode.Lognormal)
        st.minLossVar.set("0")
        st.markTouched(RiskLeafField.MinLoss)
        assertTrue(current(st.minLossError).contains("Value must be greater than zero"))
      },

      test("a maxLoss of 0 shows the positivity error") {
        val st = new RiskLeafFormState
        st.distributionModeVar.set(DistributionMode.Lognormal)
        st.maxLossVar.set("0")
        st.markTouched(RiskLeafField.MaxLoss)
        assertTrue(current(st.maxLossError).contains("Value must be greater than zero"))
      },

      test("a minLoss of 1 is accepted") {
        val st = new RiskLeafFormState
        st.distributionModeVar.set(DistributionMode.Lognormal)
        st.minLossVar.set("1")
        st.markTouched(RiskLeafField.MinLoss)
        assertTrue(current(st.minLossError).isEmpty)
      },

      test("a zero metalog quantile shows the positivity error") {
        val st = new RiskLeafFormState
        st.distributionModeVar.set(DistributionMode.Metalog)
        st.quantilesVar.set("0, 5000, 20000")
        st.markTouched(RiskLeafField.Quantiles)
        assertTrue(
          current(st.quantilesError)
            .exists(_.contains("Quantile loss amounts must be greater than zero"))
        )
      }

    ),

    suite("pctToDomain")(

      test("50.0 → 0.5") {
        assertTrue(RiskLeafFormState.pctToDomain(50.0) == 0.5)
      },

      test("0.0 → 0.0 (boundary)") {
        assertTrue(RiskLeafFormState.pctToDomain(0.0) == 0.0)
      },

      test("100.0 → 1.0 (boundary)") {
        assertTrue(RiskLeafFormState.pctToDomain(100.0) == 1.0)
      },

      test("round-trip: pctToDomain(domainToDisplayPct(p, 2).toDouble) ≈ p for p = 0.2") {
        val displayed = RiskLeafFormState.domainToDisplayPct(0.2, 2)
        val roundTripped = RiskLeafFormState.pctToDomain(displayed.toDouble)
        assertTrue(math.abs(roundTripped - 0.2) < 1e-9)
      }

    ),

    suite("domainToDisplayPct")(

      test("0 dp: eliminates IEEE 754 noise — 0.1 * 100 = 10, not 10.000000000000001") {
        assertTrue(RiskLeafFormState.domainToDisplayPct(0.1, 0) == "10")
      },

      test("0 dp: 0.5 → \"50\"") {
        assertTrue(RiskLeafFormState.domainToDisplayPct(0.5, 0) == "50")
      },

      test("0 dp: 0.9 → \"90\"") {
        assertTrue(RiskLeafFormState.domainToDisplayPct(0.9, 0) == "90")
      },

      test("2 dp: 0.2 → \"20\" (trailing zeros stripped)") {
        assertTrue(RiskLeafFormState.domainToDisplayPct(0.2, 2) == "20")
      },

      test("2 dp: 0.205 → \"20.5\" (trailing zero after significant digit stripped)") {
        assertTrue(RiskLeafFormState.domainToDisplayPct(0.205, 2) == "20.5")
      },

      test("2 dp: 0.4012 → \"40.12\" (meaningful 2 dp preserved)") {
        assertTrue(RiskLeafFormState.domainToDisplayPct(0.4012, 2) == "40.12")
      },

      test("0 dp: boundary 0.0 → \"0\"") {
        assertTrue(RiskLeafFormState.domainToDisplayPct(0.0, 0) == "0")
      },

      test("0 dp: boundary 1.0 → \"100\"") {
        assertTrue(RiskLeafFormState.domainToDisplayPct(1.0, 0) == "100")
      }

    )

  )
