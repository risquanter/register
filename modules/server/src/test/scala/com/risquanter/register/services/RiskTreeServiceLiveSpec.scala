package com.risquanter.register.services

import zio.*
import zio.test.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.*

import com.risquanter.register.http.requests.RiskTreeDefinitionRequest
import com.risquanter.register.domain.data.{RiskTree, RiskNode, RiskLeaf, RiskPortfolio}
import com.risquanter.register.domain.data.iron.{SafeId, SafeName, NonNegativeLong}
import com.risquanter.register.domain.tree.NodeId
import com.risquanter.register.repositories.RiskTreeRepository
import com.risquanter.register.domain.errors.{ValidationFailed, ValidationErrorCode, RepositoryFailure}
import com.risquanter.register.telemetry.{TracingLive, MetricsLive}
import com.risquanter.register.syntax.*
import com.risquanter.register.testutil.TestHelpers.safeId


object RiskTreeServiceLiveSpec extends ZIOSpecDefault {

  private val idStr: String => String = s => safeId(s).value

  // Concise service accessor pattern
  private val service = ZIO.serviceWithZIO[RiskTreeService]

  // Stub repository factory - creates fresh instance per test
  private def makeStubRepo = new RiskTreeRepository {
    private val db = collection.mutable.Map[NonNegativeLong, RiskTree]()
    
    override def create(riskTree: RiskTree): Task[RiskTree] = ZIO.attempt {
      val nextId: NonNegativeLong = (db.keys.map(k => (k: Long)).maxOption.getOrElse(0L) + 1L).refineUnsafe
      val newRiskTree = riskTree.copy(id = nextId)
      db += (nextId -> newRiskTree)
      newRiskTree
    }
    
    override def update(id: NonNegativeLong, op: RiskTree => RiskTree): Task[RiskTree] = ZIO.attempt {
      val riskTree = db(id)
      val updated = op(riskTree)
      db += (id -> updated)
      updated
    }
    
    override def delete(id: NonNegativeLong): Task[RiskTree] = ZIO.attempt {
      val riskTree = db(id)
      db -= id
      riskTree
    }
    
    override def getById(id: NonNegativeLong): Task[Option[RiskTree]] =
      ZIO.succeed(db.get(id))
    
    override def getAll: Task[List[Either[RepositoryFailure, RiskTree]]] =
      ZIO.succeed(db.values.toList.map(Right(_)))
  }
  
  private val stubRepoLayer = ZLayer.fromFunction(() => makeStubRepo)

  // Valid request with single leaf node (flat format)
  private val validLeafNode = RiskLeaf.create(
    id = idStr("test-risk"),
    name = "Test Risk",
    distributionType = "lognormal",
    probability = 0.75,
    minLoss = Some(1000L),
    maxLoss = Some(50000L),
    parentId = None
  ).toEither.fold(errs => throw new AssertionError(s"Invalid test data: validRequest: ${errs.map(_.message).mkString("; ")}"), identity)
  
  private val validRequest = RiskTreeDefinitionRequest(
    name = "Test Risk Tree",
    nodes = Seq(validLeafNode),
    rootId = idStr("test-risk")
  )

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("RiskTreeServiceLive")(
      test("create validates and persists risk tree config") {
        val program = service(_.create(validRequest))

        program.assert { result =>
          result.id > 0 &&
            result.name == SafeName.SafeName("Test Risk Tree".refineUnsafe) &&
            result.root.isInstanceOf[RiskLeaf]
        }
      },

      test("create accepts hierarchical RiskNode structure") {
        // Create nodes with flat structure (childIds + parentId)
        val cyberLeaf = RiskLeaf.unsafeApply(
          id = idStr("cyber"),
          name = "Cyber Attack",
          distributionType = "lognormal",
          probability = 0.25,
          minLoss = Some(1000L),
          maxLoss = Some(50000L),
          parentId = Some(safeId("ops-risk"))
        )
        val fraudLeaf = RiskLeaf.unsafeApply(
          id = idStr("fraud"),
          name = "Fraud",
          distributionType = "lognormal",
          probability = 0.15,
          minLoss = Some(500L),
          maxLoss = Some(10000L),
          parentId = Some(safeId("ops-risk"))
        )
        val portfolioNode = RiskPortfolio.unsafeFromStrings(
          id = idStr("ops-risk"),
          name = "Operational Risk",
          childIds = Array(idStr("cyber"), idStr("fraud")),
          parentId = None
        )
        
        val hierarchicalRequest = RiskTreeDefinitionRequest(
          name = "Hierarchical Tree",
          nodes = Seq(portfolioNode, cyberLeaf, fraudLeaf),
          rootId = idStr("ops-risk")
        )
        
        val program = service(_.create(hierarchicalRequest))

        program.assert { result =>
          result.id > 0 &&
            result.root.isInstanceOf[RiskPortfolio] &&
            result.root.asInstanceOf[RiskPortfolio].childIds.length == 2
        }
      },

      test("create fails with invalid name") {
        val invalidRequest = validRequest.copy(name = "")
        val program = service(_.create(invalidRequest).flip)

        program.assert {
          case ValidationFailed(errors) => errors.exists(e => e.field.toLowerCase.contains("name"))
          case _                        => false
        }
      },

      test("create fails with invalid probability") {
        val invalidRoot = RiskLeaf.create(
          id = idStr("test-risk"),
          name = "Test Risk",
          distributionType = "lognormal",
          probability = 1.5,  // Invalid: > 1.0
          minLoss = Some(1000L),
          maxLoss = Some(50000L)
        )
        // create() returns Validation failure, which won't compile into RiskTreeDefinitionRequest
        // So we test this differently - verify the validation fails
        assertTrue(invalidRoot.toEither.isLeft)
      },

      test("getAll returns all risk trees") {
        val program = for {
          tree1 <- service(_.create(validRequest))
          tree2 <- service(_.create(validRequest.copy(name = "Second Risk Tree")))
          all  <- service(_.getAll)
        } yield (tree1, tree2, all)

        program.assert { case (tree1, tree2, all) =>
          all.length == 2 &&
            all.map(_.id).toSet == Set(tree1.id, tree2.id)
        }
      },

      test("getById returns risk tree when exists") {
        val program = for {
          created <- service(_.create(validRequest))
          found   <- service(_.getById(created.id))
        } yield (created, found)

        program.assert {
          case (created, Some(found)) =>
            found.id == created.id &&
              found.name == created.name
          case _ => false
        }
      },

      test("getById returns None when not exists") {
        val program = service(_.getById(999L.refineUnsafe))

        program.assert(_ == None)
      },

      // ========================================
      // New Query APIs (ADR-015)
      // ========================================

      test("getLECCurve returns curve for leaf node") {
        val program = for {
          tree <- service(_.create(validRequest))
          // RiskTree now has flat nodes with index already built
          rootId = safeId("test-risk")
          
          // Call new getLECCurve API
          response <- service(_.getLECCurve(tree.id, rootId))
        } yield response

        program.assert { response =>
          response.id == idStr("test-risk") &&
            response.name == "Test Risk" &&
            response.curve.nonEmpty &&
            response.quantiles.nonEmpty &&
            response.quantiles.contains("p50") &&
            response.quantiles.contains("p90") &&
            response.childIds.isEmpty  // Leaf has no children
        }
      },

      test("getLECCurve returns curve with childIds for portfolio node") {
        // Create nodes with flat structure (childIds + parentId)
        val childA = RiskLeaf.unsafeApply(
          id = idStr("child-a"),
          name = "Child A",
          distributionType = "lognormal",
          probability = 0.3,
          minLoss = Some(1000L),
          maxLoss = Some(20000L),
          parentId = Some(safeId("portfolio-root"))
        )
        val childB = RiskLeaf.unsafeApply(
          id = idStr("child-b"),
          name = "Child B",
          distributionType = "lognormal",
          probability = 0.2,
          minLoss = Some(500L),
          maxLoss = Some(10000L),
          parentId = Some(safeId("portfolio-root"))
        )
        val portfolioRoot = RiskPortfolio.unsafeFromStrings(
          id = idStr("portfolio-root"),
          name = "Portfolio",
          childIds = Array(idStr("child-a"), idStr("child-b")),
          parentId = None
        )
        
        val hierarchicalRequest = RiskTreeDefinitionRequest(
          name = "Portfolio Test",
          nodes = Seq(portfolioRoot, childA, childB),
          rootId = idStr("portfolio-root")
        )

        val program = for {
          tree <- service(_.create(hierarchicalRequest))
          rootId = safeId("portfolio-root")
          response <- service(_.getLECCurve(tree.id, rootId))
        } yield response

        program.assert { response =>
          response.id == idStr("portfolio-root") &&
            response.childIds.isDefined &&
            response.childIds.get.toSet == Set(idStr("child-a"), idStr("child-b"))
        }
      },

      test("getLECCurve fails for nonexistent node") {
        val program = for {
          tree <- service(_.create(validRequest))
          invalidId = safeId("nonexistent")
          result <- service(_.getLECCurve(tree.id, invalidId).flip)
        } yield result

        program.assert {
          case ValidationFailed(errors) => errors.exists(e => e.field == "nodeId")
          case _ => false
        }
      },

      test("probOfExceedance returns probability for given threshold") {
        val program = for {
          tree <- service(_.create(validRequest))
          rootId = safeId("test-risk")
          
          // Test at multiple thresholds
          prob1 <- service(_.probOfExceedance(tree.id, rootId, 1000L))   // Low threshold
          prob2 <- service(_.probOfExceedance(tree.id, rootId, 25000L))  // Mid threshold
          prob3 <- service(_.probOfExceedance(tree.id, rootId, 50000L))  // High threshold
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
          tree <- service(_.create(validRequest))
          rootId = safeId("test-risk")
          
          // Call twice with same threshold
          prob1 <- service(_.probOfExceedance(tree.id, rootId, 10000L))
          prob2 <- service(_.probOfExceedance(tree.id, rootId, 10000L))
        } yield (prob1, prob2)

        program.assert { case (prob1, prob2) =>
          prob1 == prob2  // Should be identical (cached result)
        }
      },

      // ========================================
      // Provenance Filtering (Service Layer)
      // ========================================

      // Service layer filters provenance based on includeProvenance flag.
      // Resolver always captures provenance (for cache consistency),
      // service layer omits it from response when not requested.
      test("getLECCurve with includeProvenance=true returns provenances") {
        val program = for {
          tree <- service(_.create(validRequest))
          rootId = safeId("test-risk")
          response <- service(_.getLECCurve(tree.id, rootId, includeProvenance = true))
        } yield response

        program.assert { response =>
          response.provenances.nonEmpty &&
            response.provenances.exists(_.riskId.value.toString == idStr("test-risk"))
        }
      },

      test("getLECCurve with includeProvenance=false returns empty provenances") {
        val program = for {
          tree <- service(_.create(validRequest))
          rootId = safeId("test-risk")
          response <- service(_.getLECCurve(tree.id, rootId, includeProvenance = false))
        } yield response

        program.assert { response =>
          response.provenances.isEmpty
        }
      },

      test("getLECCurve defaults to no provenance") {
        val program = for {
          tree <- service(_.create(validRequest))
          rootId = safeId("test-risk")
          response <- service(_.getLECCurve(tree.id, rootId))  // No includeProvenance arg
        } yield response

        program.assert { response =>
          response.provenances.isEmpty  // Default is false
        }
      },

      test("getLECCurvesMulti returns curves for multiple nodes") {
        // Create nodes with flat structure
        val leaf1 = RiskLeaf.unsafeApply(
          id = idStr("leaf-1"),
          name = "Leaf 1",
          distributionType = "lognormal",
          probability = 0.3,
          minLoss = Some(1000L),
          maxLoss = Some(30000L),
          parentId = Some(safeId("multi-root"))
        )
        val leaf2 = RiskLeaf.unsafeApply(
          id = idStr("leaf-2"),
          name = "Leaf 2",
          distributionType = "lognormal",
          probability = 0.2,
          minLoss = Some(2000L),
          maxLoss = Some(40000L),
          parentId = Some(safeId("multi-root"))
        )
        val multiRoot = RiskPortfolio.unsafeFromStrings(
          id = idStr("multi-root"),
          name = "Multi Root",
          childIds = Array(idStr("leaf-1"), idStr("leaf-2")),
          parentId = None
        )
        
        val hierarchicalRequest = RiskTreeDefinitionRequest(
          name = "Multi-Node Test",
          nodes = Seq(multiRoot, leaf1, leaf2),
          rootId = idStr("multi-root")
        )

        val program = for {
          tree <- service(_.create(hierarchicalRequest))
          leaf1Id = safeId("leaf-1")
          leaf2Id = safeId("leaf-2")
          
          curves <- service(_.getLECCurvesMulti(tree.id, Set(leaf1Id, leaf2Id)))
        } yield curves

        program.assert { curves =>
          curves.size == 2 &&
            curves.contains(safeId("leaf-1")) &&
            curves.contains(safeId("leaf-2")) &&
            curves.values.forall(_.nonEmpty)  // All curves have points
        }
      },

      test("getLECCurvesMulti uses shared tick domain") {
        // Create nodes with flat structure
        val nodeA = RiskLeaf.unsafeApply(
          id = idStr("node-a"),
          name = "Node A",
          distributionType = "lognormal",
          probability = 0.3,
          minLoss = Some(5000L),
          maxLoss = Some(15000L),
          parentId = Some(safeId("shared-root"))
        )
        val nodeB = RiskLeaf.unsafeApply(
          id = idStr("node-b"),
          name = "Node B",
          distributionType = "lognormal",
          probability = 0.2,
          minLoss = Some(10000L),
          maxLoss = Some(50000L),
          parentId = Some(safeId("shared-root"))
        )
        val sharedRoot = RiskPortfolio.unsafeFromStrings(
          id = idStr("shared-root"),
          name = "Shared Root",
          childIds = Array(idStr("node-a"), idStr("node-b")),
          parentId = None
        )
        
        val hierarchicalRequest = RiskTreeDefinitionRequest(
          name = "Shared Domain Test",
          nodes = Seq(sharedRoot, nodeA, nodeB),
          rootId = idStr("shared-root")
        )

        val program = for {
          tree <- service(_.create(hierarchicalRequest))
          nodeAId = safeId("node-a")
          nodeBId = safeId("node-b")
          
          curves <- service(_.getLECCurvesMulti(tree.id, Set(nodeAId, nodeBId)))
        } yield curves

        program.assert { curves =>
          val curveA = curves(safeId("node-a"))
          val curveB = curves(safeId("node-b"))
          
          // Both curves should have same number of points (shared tick domain)
          curveA.size == curveB.size &&
            // Loss values should be identical (shared ticks)
            curveA.map(_.loss) == curveB.map(_.loss)
        }
      },

      test("getLECCurvesMulti rejects empty set") {
        for {
          tree <- service(_.create(validRequest))
          exit <- service(_.getLECCurvesMulti(tree.id, Set.empty)).exit
        } yield assertTrue(
          exit.isFailure
        )
      }
    ).provide(
      RiskTreeServiceLive.layer,
      stubRepoLayer,
      com.risquanter.register.configs.TestConfigs.simulationLayer,
      com.risquanter.register.services.cache.RiskResultResolverLive.layer,
      com.risquanter.register.services.cache.TreeCacheManager.layer,
      // Concurrency control (uses SimulationConfig)
      com.risquanter.register.configs.TestConfigs.simulationLayer >>> com.risquanter.register.services.SimulationSemaphore.layer,
      // Telemetry layers require TelemetryConfig
      com.risquanter.register.configs.TestConfigs.telemetryLayer >>> TracingLive.console,
      com.risquanter.register.configs.TestConfigs.telemetryLayer >>> MetricsLive.console
    ) @@ TestAspect.sequential
}
