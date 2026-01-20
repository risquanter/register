package com.risquanter.register.services

import zio.*
import zio.test.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.*

import com.risquanter.register.http.requests.RiskTreeDefinitionRequest
import com.risquanter.register.domain.data.{RiskTree, RiskNode, RiskLeaf, RiskPortfolio}
import com.risquanter.register.domain.data.iron.{SafeName, PositiveInt, NonNegativeInt, NonNegativeLong, IronConstants}
import IronConstants.{One, Zero, NNOne, NNTen}
import com.risquanter.register.repositories.RiskTreeRepository
import com.risquanter.register.domain.errors.{ValidationFailed, ValidationErrorCode}
import com.risquanter.register.telemetry.{TracingLive, MetricsLive}
import com.risquanter.register.syntax.*


object RiskTreeServiceLiveSpec extends ZIOSpecDefault {

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
    
    override def getAll: Task[List[RiskTree]] =
      ZIO.succeed(db.values.toList)
  }
  
  private val stubRepoLayer = ZLayer.fromFunction(() => makeStubRepo)

  // Valid request with hierarchical structure
  private val validRequest = RiskTreeDefinitionRequest(
    name = "Test Risk Tree",
    nTrials = 1000,
    root = RiskLeaf.create(
      id = "test-risk",
      name = "Test Risk",
      distributionType = "lognormal",
      probability = 0.75,
      minLoss = Some(1000L),
      maxLoss = Some(50000L)
    ).toEither.getOrElse(throw new RuntimeException("Invalid test data: validRequest"))
  )

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("RiskTreeServiceLive")(
      test("create validates and persists risk tree config") {
        val program = service(_.create(validRequest))

        program.assert { result =>
          result.id > 0 &&
            result.name == SafeName.SafeName("Test Risk Tree".refineUnsafe) &&
            result.nTrials == 1000 &&
            result.root.isInstanceOf[RiskLeaf]
        }
      },

      test("computeLEC executes simulation and returns LEC data") {
        val program = for {
          created <- service(_.create(validRequest))
          lec <- service(_.computeLEC(created.id, None, One, Zero, includeProvenance = false))
        } yield (created, lec)

        program.assert { case (created, lec) =>
          lec.riskTree.id == created.id &&
            lec.riskTree.name == created.name &&
            lec.quantiles.nonEmpty
        }
      },

      test("create accepts hierarchical RiskNode structure") {
        val hierarchicalRequest = RiskTreeDefinitionRequest(
          name = "Hierarchical Tree",
          nTrials = 1000,
          root = RiskPortfolio.create(
            id = "ops-risk",
            name = "Operational Risk",
            children = Array(
              RiskLeaf.create(
                id = "cyber",
                name = "Cyber Attack",
                distributionType = "lognormal",
                probability = 0.25,
                minLoss = Some(1000L),
                maxLoss = Some(50000L)
              ).toEither.getOrElse(throw new RuntimeException("Invalid test data: cyber")),
              RiskLeaf.create(
                id = "fraud",
                name = "Fraud",
                distributionType = "lognormal",
                probability = 0.15,
                minLoss = Some(500L),
                maxLoss = Some(10000L)
              ).toEither.getOrElse(throw new RuntimeException("Invalid test data: fraud"))
            )
          ).toEither.getOrElse(throw new RuntimeException("Invalid test data: portfolio"))
        )
        
        val program = service(_.create(hierarchicalRequest))

        program.assert { result =>
          result.id > 0 &&
            result.root.isInstanceOf[RiskPortfolio] &&
            result.root.asInstanceOf[RiskPortfolio].children.length == 2
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
          id = "test-risk",
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

      test("computeLEC with depth=0 returns only root") {
        val hierarchicalRequest = RiskTreeDefinitionRequest(
          name = "Depth Test Tree",
          nTrials = 1000,
          root = RiskPortfolio.create(
            id = "root",
            name = "Root Portfolio",
            children = Array(
              RiskLeaf.create(
                id = "child1",
                name = "Child 1",
                distributionType = "lognormal",
                probability = 0.5,
                minLoss = Some(1000L),
                maxLoss = Some(10000L)
              ).toEither.getOrElse(throw new RuntimeException("Invalid test data: child1")),
              RiskLeaf.create(
                id = "child2",
                name = "Child 2",
                distributionType = "lognormal",
                probability = 0.6,
                minLoss = Some(2000L),
                maxLoss = Some(15000L)
              ).toEither.getOrElse(throw new RuntimeException("Invalid test data: child2"))
            )
          ).toEither.getOrElse(throw new RuntimeException("Invalid test data: root portfolio"))
        )

        val program = for {
          tree <- service(_.create(hierarchicalRequest))
          result <- service(_.computeLEC(tree.id, None, One, Zero, includeProvenance = false))
        } yield result

        program.assert { result =>
          result.vegaLiteSpec match {
            case Some(spec) =>
              spec.contains("\"risk\": \"root\"") &&
                !spec.contains("\"risk\": \"child1\"") &&
                !spec.contains("\"risk\": \"child2\"")
            case None => false
          }
        }
      },

      test("computeLEC with depth=1 includes childIds for navigation") {
        // Post ADR-004a/005 redesign: LEC response is flat with childIds
        // Children's curves are fetched separately via node-specific endpoints
        val hierarchicalRequest = RiskTreeDefinitionRequest(
          name = "Depth 1 Test Tree",
          nTrials = 1000,
          root = RiskPortfolio.create(
            id = "root",
            name = "Root Portfolio",
            children = Array(
              RiskLeaf.create(
                id = "child1",
                name = "Child 1",
                distributionType = "lognormal",
                probability = 0.5,
                minLoss = Some(1000L),
                maxLoss = Some(10000L)
              ).toEither.getOrElse(throw new RuntimeException("Invalid test data: child1")),
              RiskLeaf.create(
                id = "child2",
                name = "Child 2",
                distributionType = "lognormal",
                probability = 0.6,
                minLoss = Some(2000L),
                maxLoss = Some(15000L)
              ).toEither.getOrElse(throw new RuntimeException("Invalid test data: child2"))
            )
          ).toEither.getOrElse(throw new RuntimeException("Invalid test data: root portfolio"))
        )

        val program = for {
          tree <- service(_.create(hierarchicalRequest))
          result <- service(_.computeLEC(tree.id, None, One, NNOne, includeProvenance = false))
        } yield result

        program.assert { result =>
          // Verify flat structure with childIds
          result.lecCurve match {
            case Some(lec) =>
              lec.id == "root" &&
                lec.childIds.contains(List("child1", "child2"))
            case None => false
          }
        }
      },

      test("computeLEC rejects depth exceeding maximum") {
        val program = for {
          tree <- service(_.create(validRequest))
          result <- service(_.computeLEC(tree.id, None, One, 99.refineUnsafe[GreaterEqual[0]], includeProvenance = false).flip)
        } yield result

        program.assert { error =>
          error match {
            case ValidationFailed(errors) =>
              errors.exists(e => e.field == "depth" && e.code == ValidationErrorCode.INVALID_RANGE)
            case _ => false
          }
        }
      },

      // Note: depth=-1 test removed - NonNegativeInt type prevents negative values at compile time
      // This is the desired behavior per ADR-001: "correct by construction"

      test("computeLEC generates valid Vega-Lite spec") {
        val program = for {
          tree <- service(_.create(validRequest))
          result <- service(_.computeLEC(tree.id, None, One, Zero, includeProvenance = false))
        } yield result

        program.assert { result =>
          result.vegaLiteSpec match {
            case Some(spec) =>
              spec.contains("\"$schema\"") &&
                spec.contains("vega-lite") &&
                spec.contains("\"encoding\"") &&
                spec.contains("\"mark\"") &&
                spec.contains("\"data\"") &&
                spec.contains("\"loss\"") &&
                spec.contains("\"exceedance\"")
            case None => false
          }
        }
      },

      // ========================================
      // New Query APIs (ADR-015)
      // ========================================

      test("getLECCurve returns curve for leaf node") {
        val program = for {
          tree <- service(_.create(validRequest))
          // Build TreeIndex from created tree
          index = com.risquanter.register.domain.tree.TreeIndex.fromTree(tree.root)
          rootId = com.risquanter.register.domain.data.iron.SafeId.SafeId("test-risk".refineUnsafe)
          
          // Call new getLECCurve API
          response <- service(_.getLECCurve(rootId))
        } yield response

        program.assert { response =>
          response.id == "test-risk" &&
            response.name == "Test Risk" &&
            response.curve.nonEmpty &&
            response.quantiles.nonEmpty &&
            response.quantiles.contains("p50") &&
            response.quantiles.contains("p90") &&
            response.childIds.isEmpty  // Leaf has no children
        }
      },

      test("getLECCurve returns curve with childIds for portfolio node") {
        val hierarchicalRequest = RiskTreeDefinitionRequest(
          name = "Portfolio Test",
          nTrials = 1000,
          root = RiskPortfolio.create(
            id = "portfolio-root",
            name = "Portfolio",
            children = Array(
              RiskLeaf.create(
                id = "child-a",
                name = "Child A",
                distributionType = "lognormal",
                probability = 0.3,
                minLoss = Some(1000L),
                maxLoss = Some(20000L)
              ).toEither.getOrElse(throw new RuntimeException("Invalid test data")),
              RiskLeaf.create(
                id = "child-b",
                name = "Child B",
                distributionType = "lognormal",
                probability = 0.2,
                minLoss = Some(500L),
                maxLoss = Some(10000L)
              ).toEither.getOrElse(throw new RuntimeException("Invalid test data"))
            )
          ).toEither.getOrElse(throw new RuntimeException("Invalid test data"))
        )

        val program = for {
          tree <- service(_.create(hierarchicalRequest))
          rootId = com.risquanter.register.domain.data.iron.SafeId.SafeId("portfolio-root".refineUnsafe)
          response <- service(_.getLECCurve(rootId))
        } yield response

        program.assert { response =>
          response.id == "portfolio-root" &&
            response.childIds.isDefined &&
            response.childIds.get.toSet == Set("child-a", "child-b")
        }
      },

      test("getLECCurve fails for nonexistent node") {
        val program = for {
          _ <- service(_.create(validRequest))
          invalidId = com.risquanter.register.domain.data.iron.SafeId.SafeId("nonexistent".refineUnsafe)
          result <- service(_.getLECCurve(invalidId).flip)
        } yield result

        program.assert {
          case ValidationFailed(errors) => errors.exists(e => e.field == "nodeId")
          case _ => false
        }
      },

      test("probOfExceedance returns probability for given threshold") {
        val program = for {
          tree <- service(_.create(validRequest))
          rootId = com.risquanter.register.domain.data.iron.SafeId.SafeId("test-risk".refineUnsafe)
          
          // Test at multiple thresholds
          prob1 <- service(_.probOfExceedance(rootId, 1000L))   // Low threshold
          prob2 <- service(_.probOfExceedance(rootId, 25000L))  // Mid threshold
          prob3 <- service(_.probOfExceedance(rootId, 50000L))  // High threshold
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
          rootId = com.risquanter.register.domain.data.iron.SafeId.SafeId("test-risk".refineUnsafe)
          
          // Call twice with same threshold
          prob1 <- service(_.probOfExceedance(rootId, 10000L))
          prob2 <- service(_.probOfExceedance(rootId, 10000L))
        } yield (prob1, prob2)

        program.assert { case (prob1, prob2) =>
          prob1 == prob2  // Should be identical (cached result)
        }
      },

      test("getLECCurvesMulti returns curves for multiple nodes") {
        val hierarchicalRequest = RiskTreeDefinitionRequest(
          name = "Multi-Node Test",
          nTrials = 1000,
          root = RiskPortfolio.create(
            id = "multi-root",
            name = "Multi Root",
            children = Array(
              RiskLeaf.create(
                id = "leaf-1",
                name = "Leaf 1",
                distributionType = "lognormal",
                probability = 0.3,
                minLoss = Some(1000L),
                maxLoss = Some(30000L)
              ).toEither.getOrElse(throw new RuntimeException("Invalid test data")),
              RiskLeaf.create(
                id = "leaf-2",
                name = "Leaf 2",
                distributionType = "lognormal",
                probability = 0.2,
                minLoss = Some(2000L),
                maxLoss = Some(40000L)
              ).toEither.getOrElse(throw new RuntimeException("Invalid test data"))
            )
          ).toEither.getOrElse(throw new RuntimeException("Invalid test data"))
        )

        val program = for {
          tree <- service(_.create(hierarchicalRequest))
          leaf1Id = com.risquanter.register.domain.data.iron.SafeId.SafeId("leaf-1".refineUnsafe)
          leaf2Id = com.risquanter.register.domain.data.iron.SafeId.SafeId("leaf-2".refineUnsafe)
          
          curves <- service(_.getLECCurvesMulti(Set(leaf1Id, leaf2Id)))
        } yield curves

        program.assert { curves =>
          curves.size == 2 &&
            curves.contains(com.risquanter.register.domain.data.iron.SafeId.SafeId("leaf-1".refineUnsafe)) &&
            curves.contains(com.risquanter.register.domain.data.iron.SafeId.SafeId("leaf-2".refineUnsafe)) &&
            curves.values.forall(_.nonEmpty)  // All curves have points
        }
      },

      test("getLECCurvesMulti uses shared tick domain") {
        val hierarchicalRequest = RiskTreeDefinitionRequest(
          name = "Shared Domain Test",
          nTrials = 1000,
          root = RiskPortfolio.create(
            id = "shared-root",
            name = "Shared Root",
            children = Array(
              RiskLeaf.create(
                id = "node-a",
                name = "Node A",
                distributionType = "lognormal",
                probability = 0.3,
                minLoss = Some(5000L),
                maxLoss = Some(15000L)
              ).toEither.getOrElse(throw new RuntimeException("Invalid test data")),
              RiskLeaf.create(
                id = "node-b",
                name = "Node B",
                distributionType = "lognormal",
                probability = 0.2,
                minLoss = Some(10000L),
                maxLoss = Some(50000L)
              ).toEither.getOrElse(throw new RuntimeException("Invalid test data"))
            )
          ).toEither.getOrElse(throw new RuntimeException("Invalid test data"))
        )

        val program = for {
          tree <- service(_.create(hierarchicalRequest))
          nodeAId = com.risquanter.register.domain.data.iron.SafeId.SafeId("node-a".refineUnsafe)
          nodeBId = com.risquanter.register.domain.data.iron.SafeId.SafeId("node-b".refineUnsafe)
          
          curves <- service(_.getLECCurvesMulti(Set(nodeAId, nodeBId)))
        } yield curves

        program.assert { curves =>
          val curveA = curves(com.risquanter.register.domain.data.iron.SafeId.SafeId("node-a".refineUnsafe))
          val curveB = curves(com.risquanter.register.domain.data.iron.SafeId.SafeId("node-b".refineUnsafe))
          
          // Both curves should have same number of points (shared tick domain)
          curveA.size == curveB.size &&
            // Loss values should be identical (shared ticks)
            curveA.map(_.loss) == curveB.map(_.loss)
        }
      },

      test("getLECCurvesMulti rejects empty set") {
        for {
          exit <- service(_.getLECCurvesMulti(Set.empty)).exit
        } yield assertTrue(
          exit.isFailure
        )
      }
    ).provide(
      RiskTreeServiceLive.layer,
      SimulationExecutionService.live,
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
