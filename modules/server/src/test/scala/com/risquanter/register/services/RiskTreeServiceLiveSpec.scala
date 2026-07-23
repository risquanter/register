package com.risquanter.register.services

import zio.*
import zio.test.*
import io.github.iltotore.iron.*

import com.risquanter.register.http.requests.{RiskTreeDefinitionRequest, RiskPortfolioDefinitionRequest, RiskLeafDefinitionRequest, DistributionShapeRequest, RiskTreeUpdateRequest, RiskPortfolioUpdateRequest, RiskLeafUpdateRequest}
import com.risquanter.register.domain.data.{RiskTree, RiskNode, RiskLeaf, RiskPortfolio}
import com.risquanter.register.domain.data.iron.{SafeId, SafeName, NonNegativeLong, NodeId, TreeId, WorkspaceId, SeedEntityId, BranchRef}
import com.risquanter.register.repositories.RiskTreeRepository
import com.risquanter.register.domain.errors.{ValidationFailed, ValidationErrorCode, RepositoryFailure}
import com.risquanter.register.telemetry.{TracingLive, MetricsLive}
import com.risquanter.register.syntax.*
import com.risquanter.register.testutil.TestHelpers.{safeId, nodeId, treeId}
import com.risquanter.register.util.IdGenerators
import com.risquanter.register.auth.{Checked, Permission, TestChecked}


object RiskTreeServiceLiveSpec extends ZIOSpecDefault {
  // Service-level test: operates below the authorization layer.
  // TestChecked provides the Checked[Permission] proof required by protected service methods.
  private given Checked[Permission] = TestChecked.value

  // Concise service accessor pattern
  private def service[A](f: RiskTreeService => ZIO[Any, Throwable, A]): ZIO[RiskTreeService, Throwable, A] =
    ZIO.serviceWithZIO[RiskTreeService](f)

  // Stub repository factory - creates fresh instance per test
  private def makeStubRepo = new RiskTreeRepository {
    private val db = collection.mutable.Map[(WorkspaceId, TreeId), RiskTree]()
    
    override def create(wsId: WorkspaceId, riskTree: RiskTree, branch: BranchRef = BranchRef.Main): Task[RiskTree] = ZIO.succeed {
      db += ((wsId, riskTree.id) -> riskTree)
      riskTree
    }
    
    override def update(wsId: WorkspaceId, id: TreeId, op: RiskTree => RiskTree, branch: BranchRef = BranchRef.Main): Task[RiskTree] = ZIO.attempt {
      val riskTree = db((wsId, id))
      val updated = op(riskTree)
      db += ((wsId, id) -> updated)
      updated
    }
    
    override def delete(wsId: WorkspaceId, id: TreeId, branch: BranchRef = BranchRef.Main): Task[RiskTree] = ZIO.attempt {
      val riskTree = db((wsId, id))
      db -= ((wsId, id))
      riskTree
    }
    
    override def getById(wsId: WorkspaceId, id: TreeId, branch: BranchRef = BranchRef.Main): Task[Option[RiskTree]] =
      ZIO.succeed(db.get((wsId, id)))
    
    override def getAllForWorkspace(wsId: WorkspaceId, branch: BranchRef = BranchRef.Main): Task[List[Either[RepositoryFailure, RiskTree]]] =
      ZIO.succeed(db.collect { case ((wid, _), tree) if wid == wsId => Right(tree) }.toList)
  }
  
  private val stubWsId: WorkspaceId = WorkspaceId(safeId("test-workspace-live"))
  private val testEntity: SeedEntityId.SeedEntityId = SeedEntityId.fromLong(1L).toOption.get
  private val stubRepoLayer = ZLayer.fromFunction(() => makeStubRepo)

  // Valid request with single leaf node
  private val validRequest = RiskTreeDefinitionRequest(
    name = "Test Risk Tree",
    portfolios = Seq.empty,
    leaves = Seq(
      RiskLeafDefinitionRequest(
        name = "Test Risk",
        parentName = None,
        probability = 0.75,
        distributionShape = DistributionShapeRequest(
          distributionType = "lognormal",
          percentiles = None,
          quantiles = None,
          terms = None,
          minLoss = Some(1000L),
          maxLoss = Some(50000L)
        )
      )
    )
  )

  // ── Seed-identity test helpers ──────────────────────────────────────

  private def leafDef(name: String, parent: String): RiskLeafDefinitionRequest =
    RiskLeafDefinitionRequest(
      name = name,
      parentName = Some(parent),
      probability = 0.25,
      distributionShape = DistributionShapeRequest(
        distributionType = "lognormal",
        percentiles = None, quantiles = None, terms = None,
        minLoss = Some(1000L), maxLoss = Some(50000L)
      )
    )

  /** Two-leaf tree under "Seed Root": "Cyber Attack" and "Fraud". */
  private def seedTreeRequest(treeName: String): RiskTreeDefinitionRequest =
    RiskTreeDefinitionRequest(
      name = treeName,
      portfolios = Seq(RiskPortfolioDefinitionRequest("Seed Root", None)),
      leaves = Seq(leafDef("Cyber Attack", "Seed Root"), leafDef("Fraud", "Seed Root"))
    )

  private def seedsByName(tree: RiskTree): Map[String, Long] =
    tree.nodes.collect { case l: RiskLeaf => l.name.value -> l.seedVarId.value }.toMap

  private def leafIdByName(tree: RiskTree, name: String): String =
    tree.nodes.collectFirst { case l: RiskLeaf if l.name.value == name => l.id.value }.get

  private def portfolioUpd(tree: RiskTree, name: String): RiskPortfolioUpdateRequest =
    val id = tree.nodes.collectFirst { case p: RiskPortfolio if p.name.value == name => p.id.value }.get
    RiskPortfolioUpdateRequest(id = id, name = name, parentName = None)

  private def leafUpd(id: String, name: String, parent: String): RiskLeafUpdateRequest =
    RiskLeafUpdateRequest(
      id = id,
      name = name,
      parentName = Some(parent),
      probability = 0.25,
      distributionShape = DistributionShapeRequest(
        distributionType = "lognormal",
        percentiles = None, quantiles = None, terms = None,
        minLoss = Some(1000L), maxLoss = Some(50000L)
      )
    )

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("RiskTreeServiceLive")(
      test("create validates and persists risk tree config") {
        val program = service(_.create(stubWsId, validRequest))

        program.assert { result =>
          result.id.value.nonEmpty &&
            result.name == SafeName.SafeName("Test Risk Tree".refineUnsafe) &&
            result.root.isInstanceOf[RiskLeaf]
        }
      },

      test("create accepts hierarchical RiskNode structure") {
        val hierarchicalRequest = RiskTreeDefinitionRequest(
          name = "Hierarchical Tree",
          portfolios = Seq(
            RiskPortfolioDefinitionRequest(name = "Operational Risk", parentName = None)
          ),
          leaves = Seq(
            RiskLeafDefinitionRequest(
              name = "Cyber Attack",
              parentName = Some("Operational Risk"),
              probability = 0.25,
              distributionShape = DistributionShapeRequest(
                distributionType = "lognormal",
                percentiles = None,
                quantiles = None,
                terms = None,
                minLoss = Some(1000L),
                maxLoss = Some(50000L)
              )
            ),
            RiskLeafDefinitionRequest(
              name = "Fraud",
              parentName = Some("Operational Risk"),
              probability = 0.15,
              distributionShape = DistributionShapeRequest(
                distributionType = "lognormal",
                percentiles = None,
                quantiles = None,
                terms = None,
                minLoss = Some(500L),
                maxLoss = Some(10000L)
              )
            )
          )
        )

        val program = service(_.create(stubWsId, hierarchicalRequest))

        program.assert { result =>
          val root = result.root.asInstanceOf[RiskPortfolio]
          result.id.value.nonEmpty &&
            root.childIds.length == 2 &&
            result.index.descendants(result.rootId).size == 3
        }
      },

      test("root NodeId is present in tree nodes (ADR-018 nominal identity)") {
        // Verifies that RiskNode.id (NodeId) matches rootId (NodeId) via
        // structural equality — the same invariant guarded by ensureRootPresent
        val program = service(_.create(stubWsId, validRequest))

        program.assert { tree =>
          val rootId = tree.rootId
          // NodeId-to-NodeId lookup must find the root
          tree.nodes.exists(_.id == rootId) &&
            // The index (keyed by NodeId) must also resolve it
            tree.index.nodes.contains(rootId) &&
            // root accessor (index lookup by NodeId) must succeed
            tree.root.id == rootId
        }
      },

      test("create fails with invalid name") {
        val invalidRequest = validRequest.copy(name = "")
        val program = service(_.create(stubWsId, invalidRequest)).flip

        program.assert {
          case ValidationFailed(errors) => errors.exists(e => e.field.toLowerCase.contains("name"))
          case _                        => false
        }
      },

      test("create fails with invalid probability") {
        val invalidRequest = validRequest.copy(
          leaves = Seq(validRequest.leaves.head.copy(probability = 1.5))
        )
        val program = service(_.create(stubWsId, invalidRequest)).flip

        program.assert {
          case ValidationFailed(errors) => errors.exists(_.field.contains("probability"))
          case _ => false
        }
      },

      test("getById returns risk tree when exists") {
        val program = for {
          created <- service(_.create(stubWsId, validRequest))
          found   <- service(_.getById(stubWsId, created.id))
        } yield (created, found)

        program.assert {
          case (created, Some(found)) =>
            found.id == created.id &&
              found.name == created.name
          case _ => false
        }
      },

      test("getById returns None when not exists") {
        val program = service(_.getById(stubWsId, treeId("nonexistent")))

        program.assert(_ == None)
      },

      // ========================================
      // New Query APIs (ADR-015)
      // ========================================
      test("probOfExceedance returns probability for given threshold") {
        val program = for {
          tree <- service(_.create(stubWsId, validRequest))
          rootId = tree.rootId
          
          // Test at multiple thresholds
          prob1 <- service(_.probOfExceedance(stubWsId, tree.id, rootId, 1000L, testEntity))   // Low threshold
          prob2 <- service(_.probOfExceedance(stubWsId, tree.id, rootId, 25000L, testEntity))  // Mid threshold
          prob3 <- service(_.probOfExceedance(stubWsId, tree.id, rootId, 50000L, testEntity))  // High threshold
        } yield (prob1, prob2, prob3)

        program.assert { case (prob1, prob2, prob3) =>
          // Probabilities should be in range [0, 1]
          prob1 >= 0 && prob1 <= 1 &&
            prob2 >= 0 && prob2 <= 1 &&
            prob3 >= 0 && prob3 <= 1 &&
            // Higher thresholds should have lower exceedance probability
            prob1 >= prob2 && prob2 >= prob3
        }
      },

      test("probOfExceedance returns deterministic results") {
        val program = for {
          tree <- service(_.create(stubWsId, validRequest))
          rootId = tree.rootId
          
          // Call twice with same threshold
          prob1 <- service(_.probOfExceedance(stubWsId, tree.id, rootId, 10000L, testEntity))
          prob2 <- service(_.probOfExceedance(stubWsId, tree.id, rootId, 10000L, testEntity))
        } yield (prob1, prob2)

        program.assert { case (prob1, prob2) =>
          prob1 == prob2  // Should be identical (cached result)
        }
      },

      // ========================================
      // Provenance Filtering (Service Layer)
      // ========================================

      test("getLECCurvesMulti returns curves for multiple nodes") {
        val hierarchicalRequest = RiskTreeDefinitionRequest(
          name = "Multi-Node Test",
          portfolios = Seq(RiskPortfolioDefinitionRequest("Multi Root", None)),
          leaves = Seq(
            RiskLeafDefinitionRequest(
              name = "Leaf 1",
              parentName = Some("Multi Root"),
              probability = 0.3,
              distributionShape = DistributionShapeRequest(
                distributionType = "lognormal",
                percentiles = None,
                quantiles = None,
                terms = None,
                minLoss = Some(1000L),
                maxLoss = Some(30000L)
              )
            ),
            RiskLeafDefinitionRequest(
              name = "Leaf 2",
              parentName = Some("Multi Root"),
              probability = 0.2,
              distributionShape = DistributionShapeRequest(
                distributionType = "lognormal",
                percentiles = None,
                quantiles = None,
                terms = None,
                minLoss = Some(2000L),
                maxLoss = Some(40000L)
              )
            )
          )
        )

        val program = for {
          tree <- service(_.create(stubWsId, hierarchicalRequest))
          idsByName = tree.index.nodes.map((nid, n) => n.name.value.toString -> nid).toMap
          leaf1Id = idsByName("Leaf 1")
          leaf2Id = idsByName("Leaf 2")
          curves <- service(_.getLECCurvesMulti(stubWsId, tree.id, Set(leaf1Id, leaf2Id), testEntity))
        } yield curves

        program.assert { curves =>
          curves.size == 2 &&
            curves.values.forall(_.curve.nonEmpty)
        }
      },

      test("getLECCurvesMulti uses shared tick domain") {
        val hierarchicalRequest = RiskTreeDefinitionRequest(
          name = "Shared Tick Test",
          portfolios = Seq(RiskPortfolioDefinitionRequest("Shared Root", None)),
          leaves = Seq(
            RiskLeafDefinitionRequest(
              name = "Node A",
              parentName = Some("Shared Root"),
              probability = 0.3,
              distributionShape = DistributionShapeRequest(
                distributionType = "lognormal",
                percentiles = None,
                quantiles = None,
                terms = None,
                minLoss = Some(5000L),
                maxLoss = Some(15000L)
              )
            ),
            RiskLeafDefinitionRequest(
              name = "Node B",
              parentName = Some("Shared Root"),
              probability = 0.2,
              distributionShape = DistributionShapeRequest(
                distributionType = "lognormal",
                percentiles = None,
                quantiles = None,
                terms = None,
                minLoss = Some(10000L),
                maxLoss = Some(50000L)
              )
            )
          )
        )

        val program = for {
          tree <- service(_.create(stubWsId, hierarchicalRequest))
          idsByName = tree.index.nodes.map((nid, n) => n.name.value.toString -> nid).toMap
          nodeAId = idsByName("Node A")
          nodeBId = idsByName("Node B")
          
          curves <- service(_.getLECCurvesMulti(stubWsId, tree.id, Set(nodeAId, nodeBId), testEntity))
        } yield (curves, nodeAId, nodeBId)

        program.assert { case (curves, nodeAId, nodeBId) =>
          val curveA = curves(nodeAId).curve
          val curveB = curves(nodeBId).curve
          
          // Both curves should have same number of points (shared tick domain)
          curveA.size == curveB.size &&
            // Loss values should be identical (shared ticks)
            curveA.map(_.loss) == curveB.map(_.loss)
        }
      },

      test("getLECCurvesMulti rejects empty set") {
        for {
          tree <- service(_.create(stubWsId, validRequest))
          exit <- service(_.getLECCurvesMulti(stubWsId, tree.id, Set.empty, testEntity)).exit
        } yield assertTrue(
          exit.isFailure
        )
      },

      // ========================================
      // Seed identity assignment (PLAN-SEED-IDENTITY §5)
      // ========================================

      test("create assigns seedVarIds 1..n in sorted-name order and sets the watermark") {
        val request = RiskTreeDefinitionRequest(
          name = "Seed Order Tree",
          portfolios = Seq(RiskPortfolioDefinitionRequest("Seed Root", None)),
          leaves = Seq(
            leafDef("Zeta Risk", "Seed Root"),
            leafDef("Alpha Risk", "Seed Root"),
            leafDef("Mid Risk", "Seed Root")
          )
        )
        val program = service(_.create(stubWsId, request))
        program.assert { tree =>
          val seeds = seedsByName(tree)
          seeds == Map("Alpha Risk" -> 1L, "Mid Risk" -> 2L, "Zeta Risk" -> 3L) &&
            tree.seedVarHighWater.value == 3L
        }
      },

      test("recreating the same tree yields the same seedVarIds (item 12 core property)") {
        val request = seedTreeRequest("Recreate A")
        val program = for {
          first  <- service(_.create(stubWsId, request))
          second <- service(_.create(stubWsId, seedTreeRequest("Recreate B")))
        } yield (first, second)
        program.assert { case (first, second) =>
          seedsByName(first) == seedsByName(second)
        }
      },

      test("update preserves surviving leaves' seedVarIds, including across a rename") {
        val program = for {
          created <- service(_.create(stubWsId, seedTreeRequest("Rename Tree")))
          leafId   = leafIdByName(created, "Cyber Attack")
          updated <- service(_.update(stubWsId, created.id, RiskTreeUpdateRequest(
            name = "Rename Tree",
            portfolios = Seq(portfolioUpd(created, "Seed Root")),
            leaves = Seq(
              leafUpd(leafId, "Cyber Attack Renamed", "Seed Root"),
              leafUpd(leafIdByName(created, "Fraud"), "Fraud", "Seed Root")
            ),
            newPortfolios = Seq.empty,
            newLeaves = Seq.empty
          )))
        } yield (created, updated)
        program.assert { case (created, updated) =>
          val before = seedsByName(created)
          val after  = seedsByName(updated)
          after("Cyber Attack Renamed") == before("Cyber Attack") &&
            after("Fraud") == before("Fraud") &&
            updated.seedVarHighWater == created.seedVarHighWater
        }
      },

      test("create with provided seedVarIds: fixed IDs kept, auto-assignment continues above them") {
        val request = RiskTreeDefinitionRequest(
          name = "Provided Seed Tree",
          portfolios = Seq(RiskPortfolioDefinitionRequest("Seed Root", None)),
          leaves = Seq(
            leafDef("Pinned Risk", "Seed Root").copy(seedVarId = Some(5L)),
            leafDef("Auto Beta", "Seed Root"),
            leafDef("Auto Alpha", "Seed Root")
          )
        )
        val program = service(_.create(stubWsId, request))
        program.assert { tree =>
          seedsByName(tree) == Map("Pinned Risk" -> 5L, "Auto Alpha" -> 6L, "Auto Beta" -> 7L) &&
            tree.seedVarHighWater.value == 7L
        }
      },

      test("update: a provided seedVarId clashing with a surviving leaf's ID is rejected (§5.1)") {
        val program = for {
          created <- service(_.create(stubWsId, seedTreeRequest("Clash Tree")))
          clashId  = seedsByName(created)("Cyber Attack")
          exit <- service(_.update(stubWsId, created.id, RiskTreeUpdateRequest(
            name = "Clash Tree",
            portfolios = Seq(portfolioUpd(created, "Seed Root")),
            leaves = Seq(
              leafUpd(leafIdByName(created, "Cyber Attack"), "Cyber Attack", "Seed Root"),
              leafUpd(leafIdByName(created, "Fraud"), "Fraud", "Seed Root")
            ),
            newPortfolios = Seq.empty,
            newLeaves = Seq(leafDef("Usurper", "Seed Root").copy(seedVarId = Some(clashId)))
          ))).exit
        } yield exit
        program.assert {
          case Exit.Failure(cause) =>
            cause.failureOption.exists {
              case ValidationFailed(errors) =>
                errors.exists(e =>
                  e.code == ValidationErrorCode.DUPLICATE_VALUE &&
                  e.message.contains("used by multiple nodes"))
              case _ => false
            }
          case Exit.Success(_) => false
        }
      },

      test("update: a provided seedVarId may deliberately resurrect a freed ID (§5.1)") {
        val program = for {
          created <- service(_.create(stubWsId, seedTreeRequest("Resurrect Tree")))
          freedId  = seedsByName(created)("Cyber Attack")
          // Delete "Cyber Attack", then re-add a leaf explicitly claiming its freed ID.
          after <- service(_.update(stubWsId, created.id, RiskTreeUpdateRequest(
            name = "Resurrect Tree",
            portfolios = Seq(portfolioUpd(created, "Seed Root")),
            leaves = Seq(leafUpd(leafIdByName(created, "Fraud"), "Fraud", "Seed Root")),
            newPortfolios = Seq.empty,
            newLeaves = Seq(leafDef("Cyber Attack Reborn", "Seed Root").copy(seedVarId = Some(freedId)))
          )))
        } yield (freedId, created, after)
        program.assert { case (freedId, created, after) =>
          seedsByName(after)("Cyber Attack Reborn") == freedId &&
            after.seedVarHighWater == created.seedVarHighWater
        }
      },

      test("update: a new leaf gets highWater+1; a deleted leaf's ID is never reused") {
        val program = for {
          created <- service(_.create(stubWsId, seedTreeRequest("HighWater Tree")))
          // Delete "Cyber Attack" (drop it), add "New Risk"
          afterDelete <- service(_.update(stubWsId, created.id, RiskTreeUpdateRequest(
            name = "HighWater Tree",
            portfolios = Seq(portfolioUpd(created, "Seed Root")),
            leaves = Seq(leafUpd(leafIdByName(created, "Fraud"), "Fraud", "Seed Root")),
            newPortfolios = Seq.empty,
            newLeaves = Seq(leafDef("New Risk", "Seed Root"))
          )))
        } yield (created, afterDelete)
        program.assert { case (created, after) =>
          val freedId = seedsByName(created)("Cyber Attack")
          val newId   = seedsByName(after)("New Risk")
          newId == created.seedVarHighWater.value + 1 &&
            newId != freedId &&
            after.seedVarHighWater.value == newId
        }
      }
    ).provide(
      RiskTreeServiceLive.layer,
      stubRepoLayer,
      com.risquanter.register.configs.TestConfigs.simulationLayer,
      com.risquanter.register.services.cache.RiskResultResolverLive.layer,
      com.risquanter.register.services.cache.CacheScope.layer,
      com.risquanter.register.services.pipeline.InvalidationHandler.live,
      com.risquanter.register.services.sse.SSEHub.live,
      // Concurrency control (uses SimulationConfig)
      com.risquanter.register.configs.TestConfigs.simulationLayer >>> com.risquanter.register.services.SimulationSemaphore.layer,
      // Telemetry layers require TelemetryConfig
      com.risquanter.register.configs.TestConfigs.telemetryLayer >>> TracingLive.console,
      com.risquanter.register.configs.TestConfigs.telemetryLayer >>> MetricsLive.console
    ) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.withLiveRandom
}
