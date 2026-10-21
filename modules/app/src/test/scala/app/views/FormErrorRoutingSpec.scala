package app.views

import zio.test.*
import zio.prelude.Validation

import com.raquo.laminar.api.L.Var

import app.state.{DistributionMode, ParentSelection, PortfolioField, RiskLeafField, RiskLeafFormState}
import com.risquanter.register.domain.data.iron.NodeId
import com.risquanter.register.domain.errors.{ErrorLocation, ErrorSubject, FieldName, RequestHeaderName}

/** Which input each form shows a refusal under.
  *
  * One mapping per form, and between them they decide where every submit-time
  * error lands: under an input, or in the submit banner when the location names
  * nothing the form renders.
  */
object FormErrorRoutingSpec extends ZIOSpecDefault:

  private def draft(field: FieldName): ErrorLocation =
    ErrorLocation.Field(ErrorSubject.DraftNode, field)

  def spec = suite("form error routing")(

    suite("PortfolioFormView.inputFor")(
      test("a name refusal goes under the name input") {
        assertTrue(PortfolioFormView.inputFor(draft(FieldName.Name)) == Some(PortfolioField.Name))
      },
      test("a parent refusal goes under the parent input") {
        assertTrue(PortfolioFormView.inputFor(draft(FieldName.ParentName)) == Some(PortfolioField.Parent))
      },
      test("a refusal about the whole draft goes to the banner") {
        assertTrue(PortfolioFormView.inputFor(ErrorLocation.Whole) == None)
      },
      test("a field this form does not render goes to the banner") {
        assertTrue(PortfolioFormView.inputFor(draft(FieldName.Percentiles)) == None)
      },
      test("the subject does not change the routing") {
        // A topology rule reports at the draft node; a server rejection of the
        // same field reports at the request. Both belong under the same input.
        assertTrue(
          PortfolioFormView.inputFor(ErrorLocation.requestField(FieldName.Name)) == Some(PortfolioField.Name)
        )
      }
    ),

    suite("RiskLeafFormView.inputFor")(
      test("the name, parent and probability inputs") {
        assertTrue(RiskLeafFormView.inputFor(draft(FieldName.Name)) == Some(RiskLeafField.Name)) &&
        assertTrue(RiskLeafFormView.inputFor(draft(FieldName.ParentName)) == Some(RiskLeafField.Parent)) &&
        assertTrue(RiskLeafFormView.inputFor(draft(FieldName.Probability)) == Some(RiskLeafField.Probability))
      },
      test("the five distribution inputs") {
        assertTrue(RiskLeafFormView.inputFor(draft(FieldName.Percentiles)) == Some(RiskLeafField.Percentiles)) &&
        assertTrue(RiskLeafFormView.inputFor(draft(FieldName.Quantiles)) == Some(RiskLeafField.Quantiles)) &&
        assertTrue(RiskLeafFormView.inputFor(draft(FieldName.MinLoss)) == Some(RiskLeafField.MinLoss)) &&
        assertTrue(RiskLeafFormView.inputFor(draft(FieldName.MaxLoss)) == Some(RiskLeafField.MaxLoss)) &&
        assertTrue(RiskLeafFormView.inputFor(draft(FieldName.Terms)) == Some(RiskLeafField.Terms))
      },
      test("an element failure shares its collection's input") {
        // The form edits all percentiles as one comma-separated value, so the
        // third percentile being out of range lights the same input as the
        // collection not being strictly increasing.
        assertTrue(
          RiskLeafFormView.inputFor(draft(FieldName.percentileAt(2))) == Some(RiskLeafField.Percentiles)
        ) &&
        assertTrue(
          RiskLeafFormView.inputFor(draft(FieldName.quantileAt(2))) == Some(RiskLeafField.Quantiles)
        )
      },
      test("a mode-level mismatch goes to the banner") {
        // "Expert mode requires percentiles and quantiles" names the
        // distribution rather than one of its inputs.
        assertTrue(RiskLeafFormView.inputFor(draft(FieldName.DistributionType)) == None)
      },
      test("a refusal about the whole draft goes to the banner") {
        assertTrue(RiskLeafFormView.inputFor(ErrorLocation.Whole) == None)
      },
      test("a header refusal goes to the banner") {
        assertTrue(RiskLeafFormView.inputFor(ErrorLocation.Header(RequestHeaderName.Branch)) == None)
      },
      test("an existing node's field routes by field, not by subject") {
        val nodeId = NodeId.fromString("01HX9ABCDE0000000000000001").toOption.get
        assertTrue(
          RiskLeafFormView.inputFor(ErrorLocation.nodeField(nodeId, FieldName.MinLoss)) == Some(RiskLeafField.MinLoss)
        )
      }
    ),

    /** Two refusals from two different checks, routed together.
      *
      * The parent is the one input with no reactive error of its own, so it is
      * the field that would surface in the banner if the leaf form still sent
      * its distribution refusals through a router that did not know it.
      */
    suite("a leaf submitted with two bad fields")(
      test("shows both under their own inputs and leaves the banner empty") {
        val state = new RiskLeafFormState
        state.nameVar.set("Server outage")
        state.probabilityVar.set("10")
        state.parentVar.set(ParentSelection.Unset)
        state.distributionModeVar.set(DistributionMode.Expert)
        // Descending, so the strictly-increasing rule refuses the collection.
        state.percentilesVar.set("90, 50, 10")
        state.quantilesVar.set("1000, 5000, 25000")

        val errors = state.toDraft match
          case Validation.Failure(_, es) => es.toList
          case Validation.Success(_, _)  => Nil

        val banner: Var[Option[String]] = Var(None)
        FormSubmitUtil.routeErrors(state, errors, banner, RiskLeafFormView.inputFor)

        assertTrue(
          errors.map(_.location).toSet == Set(
            ErrorLocation.Field(ErrorSubject.DraftNode, FieldName.ParentName),
            ErrorLocation.Field(ErrorSubject.DraftNode, FieldName.Percentiles)
          ),
          banner.now().isEmpty
        )
      }
    )
  )
