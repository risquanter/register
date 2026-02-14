package com.risquanter.register.services

import zio.*
import zio.test.*
import io.github.iltotore.iron.*

import com.risquanter.register.http.requests.{RiskTreeDefinitionRequest, RiskPortfolioDefinitionRequest, RiskLeafDefinitionRequest}
import com.risquanter.register.domain.data.{RiskTree, RiskNode, RiskLeaf, RiskPortfolio}
import com.risquanter.register.domain.data.iron.{SafeId, SafeName, NonNegativeLong, NodeId, TreeId}
import com.risquanter.register.repositories.RiskTreeRepository
import com.risquanter.register.domain.errors.{ValidationFailed, ValidationErrorCode, RepositoryFailure}
import com.risquanter.register.telemetry.{TracingLive, MetricsLive}
import com.risquanter.register.syntax.*
import com.risquanter.register.testutil.TestHelpers.{safeId, nodeId, treeId}
import com.risquanter.register.util.IdGenerators


object RiskTreeServiceLiveSpec extends ZIOSpecDefault {

  // Concise service accessor pattern
  private def service[A](f: RiskTreeService => ZIO[Any, Throwable, A]): ZIO[RiskTreeService, Throwable, A] =
    ZIO.serviceWithZIO[RiskTreeService](f)

  // Stub repository factory - creates fresh instance per test
  private def makeStubRepo = new RiskTreeRepository {
    private val db = collection.mutable.Map[TreeId, RiskTree]()
    
    override def create(riskTree: RiskTree): Task[RiskTree] = ZIO.succeed {
      db += (riskTree.id -> riskTree)
      riskTree
    }
    
    override def update(id: TreeId, op: RiskTree => RiskTree): Task[RiskTree] = ZIO.attempt {
      val riskTree = db(id)
      val updated = op(riskTree)
      db += (id -> updated)
      updated
    }
    
    override def delete(id: TreeId): Task[RiskTree] = ZIO.attempt {
      val riskTree = db(id)
      db -= id
      riskTree
    }
    
    override def getById(id: TreeId): Task[Option[RiskTree]] =
      ZIO.succeed(db.get(id))
    
    override def getAll: Task[List[Either[RepositoryFailure, RiskTree]]] =
      ZIO.succeed(db.values.toList.map(Right(_)))
  }
  
  private val stubRepoLayer = ZLayer.fromFunction(() => makeStubRepo)

  // Valid request with single leaf node
  private val validRequest = RiskTreeDefinitionRequest(
    name = "Test Risk Tree",
    portfolios = Seq.empty,
    leaves = Seq(
      RiskLeafDefinitionRequest(
        name = "Test Risk",
        parentName = None,
        distributionType = "lognormal",
        probability = 0.75,
        minLoss = Some(1000L),
        maxLoss = Some(50000L),
        percentiles = None,
        quantiles = None
      )
    )
  )

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("RiskTreeServiceLive")(
      test("create validates and persists risk tree config") {
        val program = service(_.create(validRequest))

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
              distributionType = "lognormal",
              probability = 0.25,
              minLoss = Some(1000L),
              maxLoss = Some(50000L),
              percentiles = None,
              quantiles = None
            ),
            RiskLeafDefinitionRequest(
              name = "Fraud",
              parentName = Some("Operational Risk"),
              distributionType = "lognormal",
              probability = 0.15,
              minLoss = Some(500L),
              maxLoss = Some(10000L),
              percentiles = None,
              quantiles = None
            )
          )
        )

        val program = service(_.create(hierarchicalRequest))

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
        val program = service(_.create(validRequest))

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
        val program = service(_.create(invalidRequest)).flip

        program.assert {
          case ValidationFailed(errors) => errors.exists(e => e.field.toLowerCase.contains("name"))
          case _                        => false
        }
      },

      test("create fails with invalid probability") {
        val invalidRequest = validRequest.copy(
          leaves = Seq(validRequest.leaves.head.copy(probability = 1.5))
        )
        val program = service(_.create(invalidRequest)).flip

        program.assert {
          case ValidationFailed(errors) => errors.exists(_.field.contains("probability"))
          case _ => false
        }
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
        val program = service(_.getById(treeId("nonexistent")))

        program.assert(_ == None)
      },

      // ========================================
      // New Query APIs (ADR-015)
      // ========================================

      test("getLECCurve returns curve for leaf node") {
        val program = for {
          tree <- service(_.create(validRequest))
          // RiskTree now has flat nodes with index already built
          rootId = tree.rootId
          
          // Call new getLECCurve API
          response <- service(_.getLECCurve(tree.id, rootId))
        } yield (response, rootId)

        program.assert { case (response, rootId) =>
          response.id == rootId.value.toString &&
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
          portfolios = Seq(RiskPortfolioDefinitionRequest("Portfolio", None)),
          leaves = Seq(
            RiskLeafDefinitionRequest(
              name = "Child A",
              parentName = Some("Portfolio"),
              distributionType = "lognormal",
              probability = 0.3,
              minLoss = Some(1000L),
              maxLoss = Some(20000L),
              percentiles = None,
              quantiles = None
            ),
            RiskLeafDefinitionRequest(
              name = "Child B",
              parentName = Some("Portfolio"),
              distributionType = "lognormal",
              probability = 0.2,
              minLoss = Some(500L),
              maxLoss = Some(10000L),
              percentiles = None,
              quantiles = None
            )
          )
        )

        val program = for {
          tree <- service(_.create(hierarchicalRequest))
          rootId = tree.rootId
          response <- service(_.getLECCurve(tree.id, rootId))
        } yield (tree, response)

        program.assert { case (tree, response) =>
          val childIds = tree.index.children.getOrElse(tree.rootId, Nil).map(_.value.toString).toSet
          response.id == tree.rootId.value.toString &&
            response.childIds.exists(_.toSet == childIds)
        }
      },

      test("getLECCurve fails for nonexistent node") {
        val program = for {
          tree <- service(_.create(validRequest))
          invalidId = nodeId("nonexistent")
          result <- service(_.getLECCurve(tree.id, invalidId)).flip
        } yield result

        program.assert {
          case ValidationFailed(errors) => errors.exists(e => e.field == "nodeId")
          case _ => false
        }
      },

      test("probOfExceedance returns probability for given threshold") {
        val program = for {
          tree <- service(_.create(validRequest))
          rootId = tree.rootId
          
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
          rootId = tree.rootId
          
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
          rootId = tree.rootId
          response <- service(_.getLECCurve(tree.id, rootId, includeProvenance = true))
        } yield (response, rootId)

        program.assert { case (response, rootId) =>
          response.provenances.nonEmpty &&
            response.provenances.exists(_.riskId.value.toString == rootId.value.toString)
        }
      },

      test("getLECCurve with includeProvenance=false returns empty provenances") {
        val program = for {
          tree <- service(_.create(validRequest))
          rootId = tree.rootId
          response <- service(_.getLECCurve(tree.id, rootId, includeProvenance = false))
        } yield response

        program.assert { response =>
          response.provenances.isEmpty
        }
      },

      test("getLECCurve defaults to no provenance") {
        val program = for {
          tree <- service(_.create(validRequest))
          rootId = tree.rootId
          response <- service(_.getLECCurve(tree.id, rootId))  // No includeProvenance arg
        } yield response

        program.assert { response =>
          response.provenances.isEmpty  // Default is false
        }
      },

      test("getLECCurvesMulti returns curves for multiple nodes") {
        val hierarchicalRequest = RiskTreeDefinitionRequest(
          name = "Multi-Node Test",
          portfolios = Seq(RiskPortfolioDefinitionRequest("Multi Root", None)),
          leaves = Seq(
            RiskLeafDefinitionRequest(
              name = "Leaf 1",
              parentName = Some("Multi Root"),
              distributionType = "lognormal",
              probability = 0.3,
              minLoss = Some(1000L),
              maxLoss = Some(30000L),
              percentiles = None,
              quantiles = None
            ),
            RiskLeafDefinitionRequest(
              name = "Leaf 2",
              parentName = Some("Multi Root"),
              distributionType = "lognormal",
              probability = 0.2,
              minLoss = Some(2000L),
              maxLoss = Some(40000L),
              percentiles = None,
              quantiles = None
            )
          )
        )

        val program = for {
          tree <- service(_.create(hierarchicalRequest))
          idsByName = tree.index.nodes.map((nid, n) => n.name -> nid).toMap
          leaf1Id = idsByName("Leaf 1")
          leaf2Id = idsByName("Leaf 2")
          curves <- service(_.getLECCurvesMulti(tree.id, Set(leaf1Id, leaf2Id)))
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
              distributionType = "lognormal",
              probability = 0.3,
              minLoss = Some(5000L),
              maxLoss = Some(15000L),
              percentiles = None,
              quantiles = None
            ),
            RiskLeafDefinitionRequest(
              name = "Node B",
              parentName = Some("Shared Root"),
              distributionType = "lognormal",
              probability = 0.2,
              minLoss = Some(10000L),
              maxLoss = Some(50000L),
              percentiles = None,
              quantiles = None
            )
          )
        )

        val program = for {
          tree <- service(_.create(hierarchicalRequest))
          idsByName = tree.index.nodes.map((nid, n) => n.name -> nid).toMap
          nodeAId = idsByName("Node A")
          nodeBId = idsByName("Node B")
          
          curves <- service(_.getLECCurvesMulti(tree.id, Set(nodeAId, nodeBId)))
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
    ) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.withLiveRandom
}
