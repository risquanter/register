package app.state

import zio.test.*

import com.risquanter.register.domain.data.Distribution
import com.risquanter.register.domain.data.iron.{SafeName, IronConstants, OccurrenceProbability}
import io.github.iltotore.iron.*

object FormModeSpec extends ZIOSpecDefault:

  private val leafName      = SafeName.fromString("Cyber Risk").toOption.get
  private val otherLeafName = SafeName.fromString("Market Risk").toOption.get
  private val portName      = SafeName.fromString("Operational Risk").toOption.get
  private val otherPortName = SafeName.fromString("IT Risk").toOption.get

  private val lognormalDistribution = Distribution(
    distributionType = IronConstants.Lognormal,
    minLoss          = Some(1000L),
    maxLoss          = Some(50000L),
    percentiles      = None,
    quantiles        = None,
    terms            = None
  )
  private val savedProbability: OccurrenceProbability = 0.3

  private val savedLeaf = LeafDraft(leafName, None, lognormalDistribution, savedProbability)
  private val savedPortfolio = PortfolioDraft(portName, None)

  private val savedLeafSnapshot: FieldSnapshot.LeafFields = FormMode.leafDraftToFieldSnapshot(savedLeaf)
  private val savedPortfolioSnapshot: FieldSnapshot.PortfolioFields = FormMode.portfolioDraftToFieldSnapshot(savedPortfolio)

  private val emptyLeafSnapshot: FieldSnapshot.LeafFields = FieldSnapshot.LeafFields(
    name        = "",
    parent      = None,
    probability = "",
    mode        = DistributionMode.Lognormal,
    percentiles = "",
    quantiles   = "",
    terms       = "",
    minLoss     = "",
    maxLoss     = ""
  )
  private val emptyPortfolioSnapshot: FieldSnapshot.PortfolioFields = FieldSnapshot.PortfolioFields("", None)

  def spec = suite("FormModeSpec")(

    suite("currentTarget")(
      test("Blank has no target") {
        assertTrue(FormMode.Blank.currentTarget.isEmpty)
      },
      test("Locked/Editing/Templating all expose their target") {
        val target = FormTarget.Leaf(leafName)
        assertTrue(
          FormMode.Locked(target).currentTarget.contains(target),
          FormMode.Editing(target).currentTarget.contains(target),
          FormMode.Templating(target).currentTarget.contains(target)
        )
      }
    ),

    suite("FormTarget.name")(
      test("extracts the wrapped name for both Leaf and Portfolio") {
        assertTrue(
          FormTarget.Leaf(leafName).name == leafName,
          FormTarget.Portfolio(portName).name == portName
        )
      }
    ),

    suite("leafDraftToFieldSnapshot / portfolioDraftToFieldSnapshot")(
      test("round-trips a saved leaf's raw display values") {
        assertTrue(
          savedLeafSnapshot.name == "Cyber Risk",
          savedLeafSnapshot.mode == DistributionMode.Lognormal,
          savedLeafSnapshot.minLoss == "1000",
          savedLeafSnapshot.maxLoss == "50000",
          savedLeafSnapshot.percentiles == "",
          savedLeafSnapshot.quantiles == "",
          savedLeafSnapshot.terms == ""
        )
      },
      test("round-trips a saved portfolio's raw display values") {
        assertTrue(
          savedPortfolioSnapshot.name == "Operational Risk",
          savedPortfolioSnapshot.parent.isEmpty
        )
      }
    ),

    suite("isFormDirty — Blank")(
      test("false when every field is empty") {
        assertTrue(!FormMode.isFormDirty(FormMode.Blank, emptyLeafSnapshot, Nil, Nil))
      },
      test("true once any leaf field is non-empty") {
        assertTrue(FormMode.isFormDirty(FormMode.Blank, emptyLeafSnapshot.copy(name = "x"), Nil, Nil))
      },
      test("true once the portfolio name is non-empty") {
        assertTrue(FormMode.isFormDirty(FormMode.Blank, emptyPortfolioSnapshot.copy(name = "x"), Nil, Nil))
      },
      test("false when parent matches the auto-selected dropdown default and every other field is empty") {
        // portfolios = [savedPortfolio] means root is already taken, so
        // FormInputs.parentSelect's own auto-correct would force parent to
        // savedPortfolio's name ("Operational Risk") — matching that forced
        // default alone must not count as user content.
        assertTrue(!FormMode.isFormDirty(FormMode.Blank, emptyLeafSnapshot.copy(parent = Some("Operational Risk")), Nil, List(savedPortfolio))) &&
        assertTrue(!FormMode.isFormDirty(FormMode.Blank, emptyPortfolioSnapshot.copy(parent = Some("Operational Risk")), Nil, List(savedPortfolio)))
      },
      test("true when parent is deliberately set to something other than the auto-selected default") {
        assertTrue(FormMode.isFormDirty(FormMode.Blank, emptyLeafSnapshot.copy(parent = Some("Some Other Portfolio")), Nil, List(savedPortfolio))) &&
        assertTrue(FormMode.isFormDirty(FormMode.Blank, emptyPortfolioSnapshot.copy(parent = Some("Some Other Portfolio")), Nil, List(savedPortfolio)))
      }
    ),

    suite("isFormDirty — Locked")(
      test("always false, even if the passed-in snapshot looks different from the saved node") {
        val target = FormTarget.Leaf(leafName)
        assertTrue(!FormMode.isFormDirty(FormMode.Locked(target), emptyLeafSnapshot, List(savedLeaf), Nil))
      }
    ),

    suite("isFormDirty — Editing")(
      test("false when the current snapshot matches the saved leaf exactly") {
        val target = FormTarget.Leaf(leafName)
        assertTrue(!FormMode.isFormDirty(FormMode.Editing(target), savedLeafSnapshot, List(savedLeaf), Nil))
      },
      test("true when a field has been changed since the saved leaf") {
        val target = FormTarget.Leaf(leafName)
        val edited = savedLeafSnapshot.copy(probability = "99")
        assertTrue(FormMode.isFormDirty(FormMode.Editing(target), edited, List(savedLeaf), Nil))
      },
      test("false when the current snapshot matches the saved portfolio exactly") {
        val target = FormTarget.Portfolio(portName)
        assertTrue(!FormMode.isFormDirty(FormMode.Editing(target), savedPortfolioSnapshot, Nil, List(savedPortfolio)))
      },
      test("true when the portfolio's parent has been changed") {
        val target = FormTarget.Portfolio(portName)
        val edited = savedPortfolioSnapshot.copy(parent = Some("Other Root"))
        assertTrue(FormMode.isFormDirty(FormMode.Editing(target), edited, Nil, List(savedPortfolio)))
      },
      test("false when the target no longer exists in the saved list (defensive default)") {
        val target = FormTarget.Leaf(otherLeafName)
        assertTrue(!FormMode.isFormDirty(FormMode.Editing(target), emptyLeafSnapshot, List(savedLeaf), Nil))
      }
    ),

    suite("isFormDirty — Templating")(
      test("false when the template's fields still match the source node") {
        val source = FormTarget.Leaf(leafName)
        assertTrue(!FormMode.isFormDirty(FormMode.Templating(source), savedLeafSnapshot, List(savedLeaf), Nil))
      },
      test("true once the templated draft diverges from the source node") {
        val source = FormTarget.Leaf(leafName)
        val edited = savedLeafSnapshot.copy(name = "Cyber Risk 2")
        assertTrue(FormMode.isFormDirty(FormMode.Templating(source), edited, List(savedLeaf), Nil))
      },
      test("false when templating a root portfolio and the parent field is only the forced auto-correction, not a real edit") {
        // savedPortfolio is the tree's only (root) portfolio. Templating it
        // copies its own parent (None), but FormInputs.parentSelect then
        // forces the dropdown to the only available option — the source's
        // own name — since a clone can't also be root. That forced value
        // alone must not register as dirty before the user types anything.
        val source = FormTarget.Portfolio(portName)
        val autoCorrected = savedPortfolioSnapshot.copy(parent = Some(portName.value))
        assertTrue(!FormMode.isFormDirty(FormMode.Templating(source), autoCorrected, Nil, List(savedPortfolio)))
      },
      test("true when templating a root portfolio and the parent is deliberately changed to something else") {
        val source = FormTarget.Portfolio(portName)
        val edited = savedPortfolioSnapshot.copy(parent = Some("Some Other Portfolio"))
        assertTrue(FormMode.isFormDirty(FormMode.Templating(source), edited, Nil, List(savedPortfolio)))
      }
    ),

    suite("isFormDirty — mismatched target/snapshot kind")(
      test("a Leaf target compared against a PortfolioFields snapshot is false, not a crash") {
        val target = FormTarget.Leaf(leafName)
        assertTrue(!FormMode.isFormDirty(FormMode.Editing(target), savedPortfolioSnapshot, List(savedLeaf), Nil))
      },
      test("a Portfolio target compared against a LeafFields snapshot is false, not a crash") {
        val target = FormTarget.Portfolio(portName)
        assertTrue(!FormMode.isFormDirty(FormMode.Editing(target), savedLeafSnapshot, Nil, List(savedPortfolio)))
      }
    )
  )
