package app.state

import zio.test.*
import zio.prelude.Validation

import com.risquanter.register.domain.data.iron.NodeId
import com.risquanter.register.domain.errors.{ErrorLocation, ErrorSubject, FieldName}

/** Each refusal the portfolio form submits names its own field, under the
  * subject the form was opened with. */
object PortfolioFormStateSpec extends ZIOSpecDefault:

  private val editedSubject: ErrorSubject =
    ErrorSubject.ExistingNode(NodeId.fromString("01HX9ABCDE0000000000000002").toOption.get)

  /** A portfolio form that would submit cleanly, opened on an existing node. */
  private def validForm(): PortfolioFormState =
    val st = new PortfolioFormState
    st.subjectVar.set(editedSubject)
    st.nameVar.set("Operational Risk")
    st.parentVar.set(ParentSelection.Root)
    st

  private def refusalLocations(st: PortfolioFormState): Set[ErrorLocation] =
    st.toDraft match
      case Validation.Failure(_, es) => es.toList.map(_.location).toSet
      case Validation.Success(_, _)  => Set.empty

  private def at(field: FieldName): ErrorLocation = ErrorLocation.Field(editedSubject, field)

  def spec = suite("PortfolioFormState")(
    test("the valid form submits cleanly") {
      assertTrue(refusalLocations(validForm()).isEmpty)
    },
    test("name") {
      val st = validForm()
      st.nameVar.set("")
      assertTrue(refusalLocations(st) == Set(at(FieldName.Name)))
    },
    test("an unset parent") {
      val st = validForm()
      st.parentVar.set(ParentSelection.Unset)
      assertTrue(refusalLocations(st) == Set(at(FieldName.ParentName)))
    },
    test("a parent name the refinement refuses") {
      val st = validForm()
      st.parentVar.set(ParentSelection.Under(""))
      assertTrue(refusalLocations(st) == Set(at(FieldName.ParentName)))
    }
  )
