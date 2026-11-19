package com.risquanter.register.http.requests

import zio.test.*
import zio.prelude.Validation
import com.risquanter.register.testutil.TestHelpers.{
  lognormalShape, mitigationId, residualProbability, retentionFactor, safeId, unsafeGet
}
import com.risquanter.register.http.requests.RiskTreeRequests.*
import com.risquanter.register.domain.data.{
  AbsoluteReplacement, DistributionShape, Mitigation, MitigationPrecedence, MitigationSpec, MitigationTarget,
  RelativeLikelihood, RelativeSeverity, RiskLeaf, RiskNode, RiskPortfolio, RiskTree, TargetingPredicate
}
import com.risquanter.register.domain.data.iron.{ContentHash, MitigationId, NodeId, NonNegativeInt, SafeId, SafeName, ValidationMessages}
import com.risquanter.register.domain.errors.{
  BoundedCollection, ErrorLocation, ErrorSubject, FieldName, FieldProblem, SubmittedMitigations, ValidationError, ValidationErrorCode
}
import com.risquanter.register.domain.tree.TreeIndex

object RiskTreeRequestsSpec extends ZIOSpecDefault {

  /** Assert that a `Validation` failed and at least one error message contains every given substring. */
  private def assertFailsWith(result: Validation[ValidationError, ?])(substrings: String*) =
    result match
      case Validation.Failure(_, errors) =>
        val messages = errors.map(e => ValidationMessages.render(e.problem))
        substrings.map(sub => assertTrue(messages.exists(_.contains(sub)))).reduce(_ && _)
      case Validation.Success(_, _) =>
        assertTrue(false).label(s"expected failure containing: ${substrings.mkString(", ")}")

  private def validLeafDef(name: String, parent: Option[String]) =
    RiskLeafDefinitionRequest(
      name              = name,
      parentName        = parent,
      probability       = 0.8,
      distributionShape = DistributionShapeRequest.Lognormal(minLoss = 1000L, maxLoss = 5000L),
      seedVarId         = None
    )

  // Update request helpers use raw ULID string literals for `id` fields because
  // the DTO layer (RiskPortfolioUpdateRequest, RiskLeafUpdateRequest) accepts pre-validation
  // strings. Iron refinement happens inside resolveUpdate, which is the code under test.
  // Using TestHelpers.safeId here would bypass the validation path we're testing.
  private def validLeafUpdate(id: String, name: String, parent: Option[String]) =
    RiskLeafUpdateRequest(
      id                = id,
      name              = name,
      parentName        = parent,
      probability       = 0.8,
      distributionShape = DistributionShapeRequest.Lognormal(minLoss = 1000L, maxLoss = 5000L),
      seedVarId         = None
    )

  // The identifiers the update requests below name: the root portfolio and its
  // one child.
  private val rootIdStr  = "01H0R8Z3F5J2N4R8Z3F5J2N4R8"
  private val childIdStr = "01H0R8Z3F5J2N4R8Z3F5J2N4R9"

  private def nodeIdOf(raw: String): NodeId = NodeId(SafeId.fromString(raw).toOption.get)

  /** The stored tree an update resolves against: the root portfolio and one
    * child, stored as a leaf or as a portfolio. A stored portfolio holds at
    * least one child, so the child portfolio holds a leaf of its own. */
  private def stored(child: NodeKind): TreeIndex =
    val grandchildIdStr = "01H0R8Z3F5J2N4R8Z3F5J2N4RB"
    def leafNode(id: String, name: String, parent: String, seed: Long): RiskNode =
      unsafeGet(RiskLeaf.create(
        id = id, name = name, probability = 0.8,
        shape = lognormalShape(1000L, 5000L), parentId = Some(nodeIdOf(parent)), seedVarId = seed,
        subject = ErrorSubject.Request
      ), "leaf")
    val root: RiskNode = unsafeGet(RiskPortfolio.createFromStrings(rootIdStr, "Root", Vector(childIdStr), None, ErrorSubject.Request), "root")
    val below: Seq[RiskNode] = child match
      case NodeKind.Leaf =>
        Seq(leafNode(childIdStr, "Leaf1", rootIdStr, 1L))
      case NodeKind.Portfolio =>
        Seq(
          unsafeGet(RiskPortfolio.createFromStrings(childIdStr, "Child", Vector(grandchildIdStr), Some(nodeIdOf(rootIdStr)), ErrorSubject.Request), "child"),
          leafNode(grandchildIdStr, "Grandchild", childIdStr, 1L)
        )
    TreeIndex.fromNodesUnsafe((root +: below).map(n => n.id -> n).toMap)

  /** The errors of a failed `Validation`, or none for a success. */
  private def errorsOf(result: Validation[ValidationError, ?]): List[ValidationError] =
    result match
      case Validation.Failure(_, errors) => errors.toList
      case Validation.Success(_, _)      => Nil

  /** Whether `errors` holds one with `code` at `location`. */
  private def refusedAt(errors: List[ValidationError], code: ValidationErrorCode, location: ErrorLocation): Boolean =
    errors.exists(e => FieldProblem.code(e.problem) == code && e.location == location)

  private val storedStamp: ContentHash = ContentHash.fromString("a" * 64).toOption.get

  /** A stored absolute statement on the stored leaf, as `stored(NodeKind.Leaf)` holds it. */
  private val storedAbsolute: Mitigation =
    unsafeGet(Mitigation.create(
      mitigationId("floor"),
      SafeName.fromString("Floor").toOption.get,
      MitigationTarget.Predicate(TargetingPredicate.ofNode(nodeIdOf(childIdStr))),
      MitigationSpec.LeafAbsolute(
        AbsoluteReplacement.Likelihood(residualProbability(0.05)), nodeIdOf(childIdStr), storedStamp),
      MitigationPrecedence.default
    ), "storedAbsolute")

  /** An update keeping the stored root and leaf, with the given mitigation buckets. */
  private def keepNodes(
    mitigations: Seq[MitigationUpdateRequest],
    newMitigations: Seq[MitigationDefinitionRequest]
  ): RiskTreeUpdateRequest =
    RiskTreeUpdateRequest(
      name = "Tree",
      portfolios = Seq(RiskPortfolioUpdateRequest(id = rootIdStr, name = "Root", parentName = None)),
      leaves = Seq(validLeafUpdate(childIdStr, "Leaf1", Some("Root"))),
      newPortfolios = Seq.empty,
      newLeaves = Seq.empty,
      mitigations = mitigations,
      newMitigations = newMitigations
    )

  /** A new absolute statement anchored on `anchor`. */
  private def newAbsolute(anchor: String): MitigationDefinitionRequest =
    MitigationDefinitionRequest(
      name = "Floor",
      target = None,
      spec = MitigationSpecRequest.LeafAbsolute(
        AbsoluteReplacementRequest.Likelihood(0.05), anchor, BaseStampChoice.KeepIfUnchanged),
      precedence = MitigationPrecedence.default
    )

  private val atFirstNew: ErrorSubject = ErrorSubject.mitigationAtIndex(SubmittedMitigations.NewMitigations, 0)

  /** A new mitigation refused on its own, by its blank name and its anchor. */
  private val blankNamed: MitigationDefinitionRequest = newAbsolute("not-a-leaf").copy(name = "")

  def spec = suite("RiskTreeRequests")(
    test("resolveCreate fails when a leaf's shape breaks its own rule") {
      val req = RiskTreeDefinitionRequest(
        name = "Tree",
        portfolios = Seq(RiskPortfolioDefinitionRequest(name = "Root", parentName = None)),
        leaves = Seq(
          RiskLeafDefinitionRequest(
            name              = "Leaf",
            parentName        = Some("Root"),
            probability       = 0.8,
            // The bounds are the wrong way round, which `LossRange` refuses.
            distributionShape = DistributionShapeRequest.Lognormal(minLoss = 5000L, maxLoss = 1000L),
            seedVarId         = None
          )
        ),
        newMitigations = Nil
      )

      val result = resolveCreate(req, () => safeId("generated"))
      assertFailsWith(result)("must be less than maximum loss")
    },

    test("resolveCreate returns validated distributions for leaves") {
      val req = RiskTreeDefinitionRequest(
        name = "Tree",
        portfolios = Seq(RiskPortfolioDefinitionRequest(name = "Root", parentName = None)),
        leaves = Seq(validLeafDef("Leaf", Some("Root"))),
        newMitigations = Nil
      )

      val result = resolveCreate(req, () => safeId("generated"))

      result match {
        case Validation.Success(_, resolved) =>
          val bounds = resolved.leaves.values.head.shape match
            case DistributionShape.Lognormal(b) => Some(((b.min: Long), (b.max: Long)))
            case _                              => None
          assertTrue(
            resolved.nodes.values.count(_.kind == NodeKind.Leaf) == 1,
            bounds == Some((1000L, 5000L))
          )
        case Validation.Failure(_, errors) =>
          assertTrue(false).label(s"resolveCreate should succeed but failed: ${errors.map(ValidationError.describe).mkString(", ")}")
      }
    },

    test("resolveCreate fails when names are duplicated across nodes") {
      val req = RiskTreeDefinitionRequest(
        name = "Tree",
        portfolios = Seq(RiskPortfolioDefinitionRequest(name = "Root", parentName = None)),
        leaves = Seq(validLeafDef("Root", Some("Root"))), // duplicate name with portfolio
        newMitigations = Nil
      )

      val result = resolveCreate(req, () => safeId("generated"))

      assertFailsWith(result)("duplicate node name")
    },

    test("resolveCreate fails when leaf parent points to a leaf") {
      val req = RiskTreeDefinitionRequest(
        name = "Tree",
        portfolios = Seq(RiskPortfolioDefinitionRequest(name = "Root", parentName = None)),
        leaves = Seq(
          validLeafDef("Leaf1", Some("Root")),
          validLeafDef("Leaf2", Some("Leaf1")) // parent is a leaf
        ),
        newMitigations = Nil
      )

      val result = resolveCreate(req, () => safeId("generated"))

      assertFailsWith(result)("refers to a leaf")
    },

    test("resolveCreate fails when leaf parent is missing") {
      val req = RiskTreeDefinitionRequest(
        name = "Tree",
        portfolios = Seq(RiskPortfolioDefinitionRequest(name = "Root", parentName = None)),
        leaves = Seq(validLeafDef("Leaf", Some("Missing"))),
        newMitigations = Nil
      )

      val result = resolveCreate(req, () => safeId("generated"))

      assertFailsWith(result)("not found in portfolios")
    },

    test("resolveCreate fails when a portfolio parent is not one of the submitted portfolios") {
      // Every leaf here names a valid parent, so requirePortfolioParents is the
      // only guard that can produce this message.
      val req = RiskTreeDefinitionRequest(
        name = "Tree",
        portfolios = Seq(
          RiskPortfolioDefinitionRequest(name = "Root", parentName = None),
          RiskPortfolioDefinitionRequest(name = "Child", parentName = Some("Missing"))
        ),
        leaves = Seq(validLeafDef("Leaf", Some("Root"))),
        newMitigations = Nil
      )

      val result = resolveCreate(req, () => safeId("generated"))

      assertFailsWith(result)("not found in portfolios")
    },

    test("resolveCreate fails when multiple roots are provided") {
      val req = RiskTreeDefinitionRequest(
        name = "Tree",
        portfolios = Seq(
          RiskPortfolioDefinitionRequest(name = "Root", parentName = None),
          RiskPortfolioDefinitionRequest(name = "AnotherRoot", parentName = None)
        ),
        leaves = Seq.empty,
        newMitigations = Nil
      )

      val result = resolveCreate(req, () => safeId("generated"))

      assertFailsWith(result)("Multiple roots")
    },

    test("resolveCreate fails when a cycle exists") {
      val req = RiskTreeDefinitionRequest(
        name = "Tree",
        portfolios = Seq(
          RiskPortfolioDefinitionRequest(name = "Root", parentName = None),
          RiskPortfolioDefinitionRequest(name = "A", parentName = Some("B")),
          RiskPortfolioDefinitionRequest(name = "B", parentName = Some("A"))
        ),
        leaves = Seq.empty,
        newMitigations = Nil
      )

      val result = resolveCreate(req, () => safeId("generated"))

      assertFailsWith(result)("Cycle detected")
    },

    test("resolveCreate fails when a portfolio is left empty") {
      val req = RiskTreeDefinitionRequest(
        name = "Tree",
        portfolios = Seq(
          RiskPortfolioDefinitionRequest(name = "Root", parentName = None),
          RiskPortfolioDefinitionRequest(name = "Child", parentName = Some("Root"))
        ),
        leaves = Seq.empty,
        newMitigations = Nil
      )

      val result = resolveCreate(req, () => safeId("generated"))

      assertFailsWith(result)("Every portfolio must have at least one child")
    },

    test("resolveCreate fails naming the childless portfolio") {
      // Root ← {P1 ← L1, P2 (empty)} – error should mention P2
      val req = RiskTreeDefinitionRequest(
        name = "Tree",
        portfolios = Seq(
          RiskPortfolioDefinitionRequest(name = "Root", parentName = None),
          RiskPortfolioDefinitionRequest(name = "P1", parentName = Some("Root")),
          RiskPortfolioDefinitionRequest(name = "P2", parentName = Some("Root"))
        ),
        leaves = Seq(validLeafDef("L1", Some("P1"))),
        newMitigations = Nil
      )

      val result = resolveCreate(req, () => safeId("generated"))

      assertFailsWith(result)("P2", "Every portfolio must have at least one child")
    },

    test("resolveCreate succeeds when all portfolios have children") {
      val req = RiskTreeDefinitionRequest(
        name = "Tree",
        portfolios = Seq(
          RiskPortfolioDefinitionRequest(name = "Root", parentName = None),
          RiskPortfolioDefinitionRequest(name = "P1", parentName = Some("Root")),
          RiskPortfolioDefinitionRequest(name = "P2", parentName = Some("Root"))
        ),
        leaves = Seq(validLeafDef("L1", Some("P1")), validLeafDef("L2", Some("P2"))),
        newMitigations = Nil
      )

      val result = resolveCreate(req, () => safeId("generated"))

      assertTrue(result.isSuccess)
    },

    test("resolveUpdate fails when existing portfolio becomes childless") {
      // Root (existing) with no children → should fail
      val req = RiskTreeUpdateRequest(
        name = "Tree",
        portfolios = Seq(
          RiskPortfolioUpdateRequest(id = rootIdStr, name = "Root", parentName = None),
          RiskPortfolioUpdateRequest(id = childIdStr, name = "Child", parentName = Some("Root"))
        ),
        leaves = Seq.empty,
        newPortfolios = Seq.empty,
        newLeaves = Seq.empty,
        mitigations = Nil,
        newMitigations = Nil
      )

      val result = resolveUpdate(req, stored(NodeKind.Portfolio), Nil, () => safeId("generated"))

      assertFailsWith(result)("Every portfolio must have at least one child")
    },

    test("resolveUpdate fails when names conflict between existing and new nodes") {
      val req = RiskTreeUpdateRequest(
        name = "Tree",
        portfolios = Seq(RiskPortfolioUpdateRequest(id = rootIdStr, name = "Root", parentName = None)),
        leaves = Seq.empty,
        newPortfolios = Seq.empty,
        newLeaves = Seq(validLeafDef("Root", Some("Root"))), // duplicates existing portfolio name
        mitigations = Nil,
        newMitigations = Nil
      )

      val result = resolveUpdate(req, stored(NodeKind.Leaf), Nil, () => safeId("generated"))

      assertFailsWith(result)("duplicate node name")
    },

    test("resolveUpdate fails when new leaf parent is a leaf") {
      val req = RiskTreeUpdateRequest(
        name = "Tree",
        portfolios = Seq(RiskPortfolioUpdateRequest(id = rootIdStr, name = "Root", parentName = None)),
        leaves = Seq(validLeafUpdate(childIdStr, "Leaf1", Some("Root"))),
        newPortfolios = Seq.empty,
        newLeaves = Seq(validLeafDef("Leaf2", Some("Leaf1"))), // parent is a leaf
        mitigations = Nil,
        newMitigations = Nil
      )

      val result = resolveUpdate(req, stored(NodeKind.Leaf), Nil, () => safeId("generated"))

      assertFailsWith(result)("refers to a leaf")
    },

    test("resolveCreate fails when a node name collides with a reserved FOL symbol") {
      val req = RiskTreeDefinitionRequest(
        name = "Tree",
        portfolios = Seq(RiskPortfolioDefinitionRequest(name = "Root", parentName = None)),
        leaves = Seq(validLeafDef("leaf", Some("Root"))), // "leaf" is a reserved predicate
        newMitigations = Nil
      )

      val result = resolveCreate(req, () => safeId("generated"))

      assertFailsWith(result)("reserved query symbol")
    },

    test("resolveUpdate fails when an existing node name collides with a reserved FOL symbol") {
      val req = RiskTreeUpdateRequest(
        name = "Tree",
        portfolios = Seq(RiskPortfolioUpdateRequest(id = rootIdStr, name = "Root", parentName = None)),
        leaves = Seq(validLeafUpdate(childIdStr, "p95", Some("Root"))), // "p95" is a reserved function
        newPortfolios = Seq.empty,
        newLeaves = Seq.empty,
        mitigations = Nil,
        newMitigations = Nil
      )

      val result = resolveUpdate(req, stored(NodeKind.Leaf), Nil, () => safeId("generated"))

      assertFailsWith(result)("reserved query symbol")
    },

    // ── Seed identity boundary rules ──

    test("resolveCreate carries provided seedVarIds keyed by refined leaf name") {
      val req = RiskTreeDefinitionRequest(
        name = "Tree",
        portfolios = Seq(RiskPortfolioDefinitionRequest(name = "Root", parentName = None)),
        leaves = Seq(
          validLeafDef("L1", Some("Root")).copy(seedVarId = Some(7L)),
          validLeafDef("L2", Some("Root")) // no ID — server assigns
        ),
        newMitigations = Nil
      )

      val result = resolveCreate(req, () => safeId("generated"))

      result match
        case Validation.Success(_, resolved) =>
          assertTrue(
            resolved.providedSeedVarIds.map { case (n, s) => n.value -> s.value } == Map("L1" -> 7L)
          )
        case Validation.Failure(_, errors) =>
          assertTrue(false).label(errors.map(ValidationError.describe).mkString("; "))
    },

    test("resolveCreate rejects an out-of-range provided seedVarId") {
      val req = RiskTreeDefinitionRequest(
        name = "Tree",
        portfolios = Seq(RiskPortfolioDefinitionRequest(name = "Root", parentName = None)),
        leaves = Seq(validLeafDef("L1", Some("Root")).copy(seedVarId = Some(50000000L))),
        newMitigations = Nil
      )

      assertFailsWith(resolveCreate(req, () => safeId("generated")))("seedVarId must be between 1 and 49999999")
    },

    test("resolveCreate rejects duplicate provided seedVarIds within the tree") {
      val req = RiskTreeDefinitionRequest(
        name = "Tree",
        portfolios = Seq(RiskPortfolioDefinitionRequest(name = "Root", parentName = None)),
        leaves = Seq(
          validLeafDef("L1", Some("Root")).copy(seedVarId = Some(5L)),
          validLeafDef("L2", Some("Root")).copy(seedVarId = Some(5L))
        ),
        newMitigations = Nil
      )

      assertFailsWith(resolveCreate(req, () => safeId("generated")))("seedVarId 5 is used by multiple nodes", "L1", "L2")
    },

    test("resolveCreate accumulates seed range errors with independent field errors") {
      val req = RiskTreeDefinitionRequest(
        name = "Tree",
        portfolios = Seq(RiskPortfolioDefinitionRequest(name = "Root", parentName = None)),
        leaves = Seq(
          validLeafDef("L1", Some("Root")).copy(seedVarId = Some(0L), probability = 1.5)
        ),
        newMitigations = Nil
      )

      assertFailsWith(resolveCreate(req, () => safeId("generated")))(
        "seedVarId must be between 1 and 49999999",
        "probability"
      )
    },

    test("resolveUpdate rejects seedVarId on an existing leaf, whose seedVarId is immutable") {
      val req = RiskTreeUpdateRequest(
        name = "Tree",
        portfolios = Seq(RiskPortfolioUpdateRequest(id = rootIdStr, name = "Root", parentName = None)),
        leaves = Seq(validLeafUpdate(childIdStr, "Leaf1", Some("Root")).copy(seedVarId = Some(3L))),
        newPortfolios = Seq.empty,
        newLeaves = Seq.empty,
        mitigations = Nil,
        newMitigations = Nil
      )

      assertFailsWith(resolveUpdate(req, stored(NodeKind.Leaf), Nil, () => safeId("generated")))("seedVarId is immutable once assigned")
    },

    test("resolveUpdate carries provided seedVarIds for new leaves only") {
      val req = RiskTreeUpdateRequest(
        name = "Tree",
        portfolios = Seq(RiskPortfolioUpdateRequest(id = rootIdStr, name = "Root", parentName = None)),
        leaves = Seq(validLeafUpdate(childIdStr, "Leaf1", Some("Root"))),
        newPortfolios = Seq.empty,
        newLeaves = Seq(validLeafDef("Leaf2", Some("Root")).copy(seedVarId = Some(9L))),
        mitigations = Nil,
        newMitigations = Nil
      )

      val result = resolveUpdate(req, stored(NodeKind.Leaf), Nil, () => safeId("generated"))

      result match
        case Validation.Success(_, resolved) =>
          assertTrue(
            resolved.providedSeedVarIds.map { case (n, s) => n.value -> s.value } == Map("Leaf2" -> 9L)
          )
        case Validation.Failure(_, errors) =>
          assertTrue(false).label(errors.map(ValidationError.describe).mkString("; "))
    },

    test("resolveUpdate rejects duplicate provided seedVarIds among new leaves") {
      val req = RiskTreeUpdateRequest(
        name = "Tree",
        portfolios = Seq(RiskPortfolioUpdateRequest(id = rootIdStr, name = "Root", parentName = None)),
        leaves = Seq.empty,
        newPortfolios = Seq.empty,
        newLeaves = Seq(
          validLeafDef("N1", Some("Root")).copy(seedVarId = Some(4L)),
          validLeafDef("N2", Some("Root")).copy(seedVarId = Some(4L))
        ),
        mitigations = Nil,
        newMitigations = Nil
      )

      assertFailsWith(resolveUpdate(req, stored(NodeKind.Leaf), Nil, () => safeId("generated")))("seedVarId 4 is used by multiple nodes", "N1", "N2")
    },

    test("resolveUpdate preserves existing node ids verbatim (time-travel identity precondition)") {
      // The whole point of the existing buckets: a loaded node's id must survive
      // resolution unchanged so its Irmin path is rewritten in place, not churned.
      val req = RiskTreeUpdateRequest(
        name = "Tree",
        portfolios = Seq(RiskPortfolioUpdateRequest(id = rootIdStr, name = "Root", parentName = None)),
        leaves = Seq(validLeafUpdate(childIdStr, "Leaf1", Some("Root"))),
        newPortfolios = Seq.empty,
        newLeaves = Seq.empty,
        mitigations = Nil,
        newMitigations = Nil
      )

      // The id generator would mint a *different* id; existing nodes must not call it.
      val result = resolveUpdate(req, stored(NodeKind.Leaf), Nil, () => safeId("REGENERATED-MUST-NOT-BE-USED"))

      result match
        case Validation.Success(_, resolved) =>
          val rootNode = resolved.existing.values.find(_.name.value == "Root")
          val leafNode = resolved.existing.values.find(_.name.value == "Leaf1")
          assertTrue(
            rootNode.exists(_.id.value.toString == rootIdStr),
            leafNode.exists(_.id.value.toString == childIdStr)
          )
        case Validation.Failure(_, errors) =>
          assertTrue(false).label(errors.map(ValidationError.describe).mkString("; "))
    },

    // ── an update names only the stored tree's nodes ──

    test("resolveUpdate refuses a leaves entry whose id the stored tree does not hold, at that entry's Id") {
      val unknown = "01H0R8Z3F5J2N4R8Z3F5J2N4RA"
      val req = RiskTreeUpdateRequest(
        name = "Tree",
        portfolios = Seq(RiskPortfolioUpdateRequest(id = rootIdStr, name = "Root", parentName = None)),
        leaves = Seq(validLeafUpdate(unknown, "Leaf1", Some("Root"))),
        newPortfolios = Seq.empty,
        newLeaves = Seq.empty,
        mitigations = Nil,
        newMitigations = Nil
      )
      val errors = errorsOf(resolveUpdate(req, stored(NodeKind.Leaf), Nil, () => safeId("generated")))
      assertTrue(errors.exists(e =>
        FieldProblem.code(e.problem) == ValidationErrorCode.MISSING_REFERENCE &&
          e.location == ErrorLocation.Field(ErrorSubject.ExistingNode(nodeIdOf(unknown)), FieldName.Id)
      ))
    },

    test("resolveUpdate refuses a leaves entry naming a stored portfolio, and a portfolios entry naming a stored leaf") {
      val leafAsPortfolio = RiskTreeUpdateRequest(
        name = "Tree",
        portfolios = Seq(
          RiskPortfolioUpdateRequest(id = rootIdStr, name = "Root", parentName = None),
          RiskPortfolioUpdateRequest(id = childIdStr, name = "Leaf1", parentName = Some("Root"))
        ),
        leaves = Seq.empty,
        newPortfolios = Seq.empty,
        newLeaves = Seq(validLeafDef("Leaf2", Some("Leaf1"))),
        mitigations = Nil,
        newMitigations = Nil
      )
      val portfolioAsLeaf = RiskTreeUpdateRequest(
        name = "Tree",
        portfolios = Seq(RiskPortfolioUpdateRequest(id = rootIdStr, name = "Root", parentName = None)),
        leaves = Seq(validLeafUpdate(childIdStr, "Child", Some("Root"))),
        newPortfolios = Seq.empty,
        newLeaves = Seq.empty,
        mitigations = Nil,
        newMitigations = Nil
      )
      val atChildId = ErrorLocation.Field(ErrorSubject.ExistingNode(nodeIdOf(childIdStr)), FieldName.Id)
      assertTrue(
        errorsOf(resolveUpdate(leafAsPortfolio, stored(NodeKind.Leaf), Nil, () => safeId("generated")))
          .exists(e => FieldProblem.code(e.problem) == ValidationErrorCode.INVALID_NODE_TYPE && e.location == atChildId),
        errorsOf(resolveUpdate(portfolioAsLeaf, stored(NodeKind.Portfolio), Nil, () => safeId("generated")))
          .exists(e => FieldProblem.code(e.problem) == ValidationErrorCode.INVALID_NODE_TYPE && e.location == atChildId)
      )
    },

    test("resolveUpdate reports a stored-node refusal and an invalid probability together") {
      val req = RiskTreeUpdateRequest(
        name = "Tree",
        portfolios = Seq(RiskPortfolioUpdateRequest(id = rootIdStr, name = "Root", parentName = None)),
        leaves = Seq(validLeafUpdate(childIdStr, "Child", Some("Root")).copy(probability = 1.5)),
        newPortfolios = Seq.empty,
        newLeaves = Seq.empty,
        mitigations = Nil,
        newMitigations = Nil
      )
      val errors = errorsOf(resolveUpdate(req, stored(NodeKind.Portfolio), Nil, () => safeId("generated")))
      assertTrue(
        errors.exists(e => FieldProblem.code(e.problem) == ValidationErrorCode.INVALID_NODE_TYPE),
        errors.exists(_.location match
          case ErrorLocation.Field(_, FieldName.Probability) => true
          case _                                            => false)
      )
    },

    test("resolveUpdate reports an id that does not refine once, as INVALID_FORMAT, and not as a stored-node refusal") {
      val req = RiskTreeUpdateRequest(
        name = "Tree",
        portfolios = Seq(RiskPortfolioUpdateRequest(id = rootIdStr, name = "Root", parentName = None)),
        leaves = Seq(validLeafUpdate("not-an-id", "Leaf1", Some("Root"))),
        newPortfolios = Seq.empty,
        newLeaves = Seq.empty,
        mitigations = Nil,
        newMitigations = Nil
      )
      val errors = errorsOf(resolveUpdate(req, stored(NodeKind.Leaf), Nil, () => safeId("generated")))
      assertTrue(
        errors.count(e => FieldProblem.code(e.problem) == ValidationErrorCode.INVALID_FORMAT) == 1,
        !errors.exists(e =>
          FieldProblem.code(e.problem) == ValidationErrorCode.MISSING_REFERENCE ||
            FieldProblem.code(e.problem) == ValidationErrorCode.INVALID_NODE_TYPE)
      )
    },

    test("resolveUpdate refuses a stored id listed twice, once, at that id") {
      val req = RiskTreeUpdateRequest(
        name = "Tree",
        portfolios = Seq(RiskPortfolioUpdateRequest(id = rootIdStr, name = "Root", parentName = None)),
        leaves = Seq(
          validLeafUpdate(childIdStr, "Leaf1", Some("Root")),
          validLeafUpdate(childIdStr, "Leaf2", Some("Root"))
        ),
        newPortfolios = Seq.empty,
        newLeaves = Seq.empty,
        mitigations = Nil,
        newMitigations = Nil
      )
      val errors = errorsOf(resolveUpdate(req, stored(NodeKind.Leaf), Nil, () => safeId("generated")))
      val atChildId = ErrorLocation.Field(ErrorSubject.ExistingNode(nodeIdOf(childIdStr)), FieldName.Id)
      assertTrue(
        errors.count(e => FieldProblem.code(e.problem) == ValidationErrorCode.DUPLICATE_VALUE && e.location == atChildId) == 1
      )
    },

    // ── mitigations travel in the tree request ──

    test("resolveCreate resolves a new relative mitigation with a minted id") {
      val req = RiskTreeDefinitionRequest(
        name = "Tree",
        portfolios = Seq(RiskPortfolioDefinitionRequest(name = "Root", parentName = None)),
        leaves = Seq(validLeafDef("Leaf", Some("Root"))),
        newMitigations = Seq(MitigationDefinitionRequest(
          name = "MFA",
          target = Some("leaf(x)"),
          spec = MitigationSpecRequest.LeafRelative(RelativeLikelihoodRequest.Scale(0.3), RelativeSeverityRequest.Keep),
          precedence = MitigationPrecedence.default
        ))
      )
      val minted = safeId("minted")
      resolveCreate(req, () => minted) match
        case Validation.Success(_, resolved) =>
          assertTrue(
            resolved.mitigations.map(_.id) == Seq(MitigationId(minted)),
            resolved.mitigations.map(_.spec) == Seq(ResolvedSpec.Complete(
              MitigationSpec.LeafRelative(RelativeLikelihood.Scale(retentionFactor(0.3)), RelativeSeverity.Keep)))
          )
        case Validation.Failure(_, errors) =>
          assertTrue(false).label(errors.map(ValidationError.describe).mkString("; "))
    },

    test("resolveCreate refuses an absolute statement at its anchor, because a new tree stores no leaf") {
      val req = RiskTreeDefinitionRequest(
        name = "Tree",
        portfolios = Seq(RiskPortfolioDefinitionRequest(name = "Root", parentName = None)),
        leaves = Seq(validLeafDef("Leaf", Some("Root"))),
        newMitigations = Seq(newAbsolute(childIdStr))
      )
      val errors = errorsOf(resolveCreate(req, () => safeId("generated")))
      assertTrue(errors.contains(ValidationError(ErrorLocation.Field(atFirstNew, FieldName.Anchor), FieldProblem.AnchorNotInTree)))
    },

    test("resolveUpdate refuses an absolute statement whose anchor is not stored, or is a stored portfolio, at its anchor") {
      val unknown = "01H0R8Z3F5J2N4R8Z3F5J2N4RA"
      val atAnchor = ErrorLocation.Field(atFirstNew, FieldName.Anchor)
      assertTrue(
        errorsOf(resolveUpdate(keepNodes(Nil, Seq(newAbsolute(unknown))), stored(NodeKind.Leaf), Nil, () => safeId("generated")))
          .contains(ValidationError(atAnchor, FieldProblem.AnchorNotInTree)),
        errorsOf(resolveUpdate(keepNodes(Nil, Seq(newAbsolute(rootIdStr))), stored(NodeKind.Leaf), Nil, () => safeId("generated")))
          .contains(ValidationError(atAnchor, FieldProblem.AnchorIsPortfolio))
      )
    },

    test("resolveUpdate refuses a kept mitigation the stored tree does not hold, at that mitigation's Id") {
      val req = keepNodes(Seq(MitigationUpdateRequest.fromMitigation(storedAbsolute)), Nil)
      val errors = errorsOf(resolveUpdate(req, stored(NodeKind.Leaf), Nil, () => safeId("generated")))
      assertTrue(refusedAt(
        errors, ValidationErrorCode.MISSING_REFERENCE, ErrorLocation.Field(ErrorSubject.Mitigation(storedAbsolute.id), FieldName.Id)))
    },

    test("resolveUpdate refuses a stored mitigation listed twice, once, at its Id") {
      val entry = MitigationUpdateRequest.fromMitigation(storedAbsolute)
      val errors = errorsOf(resolveUpdate(keepNodes(Seq(entry, entry), Nil), stored(NodeKind.Leaf), Seq(storedAbsolute), () => safeId("generated")))
      val atId = ErrorLocation.Field(ErrorSubject.Mitigation(storedAbsolute.id), FieldName.Id)
      assertTrue(errors.count(e => FieldProblem.code(e.problem) == ValidationErrorCode.DUPLICATE_VALUE && e.location == atId) == 1)
    },

    test("a stored absolute statement resubmitted by fromMitigation keeps its stamp") {
      val req = keepNodes(Seq(MitigationUpdateRequest.fromMitigation(storedAbsolute)), Nil)
      resolveUpdate(req, stored(NodeKind.Leaf), Seq(storedAbsolute), () => safeId("generated")) match
        case Validation.Success(_, resolved) =>
          assertTrue(resolved.mitigations.map(_.spec) == Seq(ResolvedSpec.Complete(storedAbsolute.spec)))
        case Validation.Failure(_, errors) =>
          assertTrue(false).label(errors.map(ValidationError.describe).mkString("; "))
    },

    test("a kept absolute statement is left for the service to stamp when it asks to restamp or its replacement changed") {
      val kept = MitigationUpdateRequest.fromMitigation(storedAbsolute)
      val restamp = kept.copy(spec = MitigationSpecRequest.LeafAbsolute(
        AbsoluteReplacementRequest.Likelihood(0.05), childIdStr, BaseStampChoice.Restamp))
      val changed = kept.copy(spec = MitigationSpecRequest.LeafAbsolute(
        AbsoluteReplacementRequest.Likelihood(0.04), childIdStr, BaseStampChoice.KeepIfUnchanged))
      def specsOf(entry: MitigationUpdateRequest): Seq[ResolvedSpec] =
        resolveUpdate(keepNodes(Seq(entry), Nil), stored(NodeKind.Leaf), Seq(storedAbsolute), () => safeId("generated")) match
          case Validation.Success(_, resolved) => resolved.mitigations.map(_.spec)
          case Validation.Failure(_, _)        => Nil
      assertTrue(
        specsOf(restamp) == Seq(ResolvedSpec.Unstamped(
          AbsoluteReplacement.Likelihood(residualProbability(0.05)), nodeIdOf(childIdStr))),
        specsOf(changed) == Seq(ResolvedSpec.Unstamped(
          AbsoluteReplacement.Likelihood(residualProbability(0.04)), nodeIdOf(childIdStr)))
      )
    },

    test("a create above the mitigation ceiling is refused by its count alone, before any mitigation is refined") {
      val req = RiskTreeDefinitionRequest(
        name = "Tree",
        portfolios = Seq(RiskPortfolioDefinitionRequest("Root", None)),
        leaves = Seq(validLeafDef("Leaf1", Some("Root"))),
        newMitigations = Seq.fill(RiskTree.MaxMitigations + 1)(blankNamed)
      )
      assertTrue(errorsOf(resolveCreate(req, () => safeId("generated"))) == List(ValidationError(
        ErrorLocation.requestField(FieldName.Mitigations),
        FieldProblem.TooMany(BoundedCollection.Mitigations, NonNegativeInt.count(RiskTree.MaxMitigations + 1), RiskTree.MaxMitigations)
      )))
    },

    test("a create at the mitigation ceiling is refined, so each mitigation's own refusal is reported") {
      val req = RiskTreeDefinitionRequest(
        name = "Tree",
        portfolios = Seq(RiskPortfolioDefinitionRequest("Root", None)),
        leaves = Seq(validLeafDef("Leaf1", Some("Root"))),
        newMitigations = Seq.fill(RiskTree.MaxMitigations)(blankNamed)
      )
      val errors = errorsOf(resolveCreate(req, () => safeId("generated")))
      assertTrue(
        errors.nonEmpty,
        errors.forall(_.problem match
          case FieldProblem.TooMany(_, _, _) => false
          case _                             => true)
      )
    },

    test("a create above the node ceiling is refused by its count alone, before any node is refined") {
      val req = RiskTreeDefinitionRequest(
        name = "",
        portfolios = Seq(RiskPortfolioDefinitionRequest("Root", None)),
        leaves = (1 to RiskTree.MaxNodes).map(i => validLeafDef(s"L$i", Some("Root"))),
        newMitigations = Seq.empty
      )
      assertTrue(errorsOf(resolveCreate(req, () => safeId("generated"))) == List(ValidationError(
        ErrorLocation.Whole,
        FieldProblem.TooMany(BoundedCollection.Nodes, NonNegativeInt.count(RiskTree.MaxNodes + 1), RiskTree.MaxNodes)
      )))
    },

    test("an update counts kept and new mitigations together against the ceiling") {
      val kept = Seq.fill(RiskTree.MaxMitigations / 2 + 1)(
        MitigationUpdateRequest(id = "", name = "", target = None, spec = blankNamed.spec, precedence = MitigationPrecedence.default))
      val req = keepNodes(kept, Seq.fill(RiskTree.MaxMitigations / 2)(blankNamed))
      assertTrue(errorsOf(resolveUpdate(req, stored(NodeKind.Leaf), Nil, () => safeId("generated"))) == List(ValidationError(
        ErrorLocation.requestField(FieldName.Mitigations),
        FieldProblem.TooMany(BoundedCollection.Mitigations, NonNegativeInt.count(RiskTree.MaxMitigations + 1), RiskTree.MaxMitigations)
      )))
    }
  )
}
