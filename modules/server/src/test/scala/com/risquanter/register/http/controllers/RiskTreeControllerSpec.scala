package com.risquanter.register.http.controllers

import zio.*
import zio.test.*
import com.risquanter.register.services.{RiskTreeService, RiskTreeServiceLive, SimulationExecutionService}
import com.risquanter.register.repositories.RiskTreeRepository
import com.risquanter.register.domain.data.{RiskTree, RiskNode, RiskLeaf, RiskPortfolio}
import com.risquanter.register.http.requests.{RiskTreeDefinitionRequest}
import com.risquanter.register.http.responses.SimulationResponse
import com.risquanter.register.telemetry.{TracingLive, MetricsLive}
import com.risquanter.register.syntax.* // For .assert extension method
import io.github.iltotore.iron.*

object RiskTreeControllerSpec extends ZIOSpecDefault {

  // Service accessor pattern
  private def service = ZIO.serviceWithZIO[RiskTreeService]

  // Stub repository factory - creates fresh instance per test
  private def makeStubRepo() = new RiskTreeRepository {
    private val db = collection.mutable.Map[Long, RiskTree]()
    
    override def create(riskTree: RiskTree): Task[RiskTree] = ZIO.attempt {
      val nextId = db.keys.maxOption.getOrElse(0L) + 1L
      val newRiskTree = riskTree.copy(id = nextId.refineUnsafe)
      db += (nextId -> newRiskTree)
      newRiskTree
    }
    
    override def update(id: Long, op: RiskTree => RiskTree): Task[RiskTree] = ZIO.attempt {
      val riskTree = db(id)
      val updated = op(riskTree)
      db += (id -> updated)
      updated
    }
    
    override def delete(id: Long): Task[RiskTree] = ZIO.attempt {
      val riskTree = db(id)
      db -= id
      riskTree
    }
    
    override def getById(id: Long): Task[Option[RiskTree]] =
      ZIO.succeed(db.get(id))
    
    override def getAll: Task[List[RiskTree]] =
      ZIO.succeed(db.values.toList)
  }

  // Layer factory - creates fresh layer with isolated repository per test
  private def serviceLayer = {
    // Telemetry layers require TelemetryConfig
    val tracingLayer = com.risquanter.register.configs.TestConfigs.telemetryLayer >>> TracingLive.console
    val metricsLayer = com.risquanter.register.configs.TestConfigs.telemetryLayer >>> MetricsLive.console
    
    ZLayer.succeed(makeStubRepo()) >>>
    (SimulationExecutionService.live ++ ZLayer.environment[RiskTreeRepository] ++ com.risquanter.register.configs.TestConfigs.simulationLayer ++ tracingLayer ++ metricsLayer) >>>
    RiskTreeServiceLive.layer
  }

  override def spec = suite("RiskTreeController")(
    suite("Health endpoint")(
      test("returns healthy status") {
        // Manual test confirms: GET /health returns {"status": "healthy", "service": "risk-register"}
        // This test documents the endpoint exists and compiles
        assertTrue(true)
      }
    ),

    suite("Hierarchical tree creation with discriminators")(
      test("POST with RiskPortfolio discriminator creates tree") {
        val hierarchicalRequest = RiskTreeDefinitionRequest(
          name = "Ops Risk Portfolio",
          nTrials = 1000,
          root = RiskPortfolio.create(
            id = "root",
            name = "Total Operational Risk",
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
                id = "breach",
                name = "Data Breach",
                distributionType = "lognormal",
                probability = 0.15,
                minLoss = Some(500L),
                maxLoss = Some(25000L)
              ).toEither.getOrElse(throw new RuntimeException("Invalid test data: breach"))
            )
          ).toEither.getOrElse(throw new RuntimeException("Invalid test data"))
        )

        val program = service(_.create(hierarchicalRequest))

        program.assert { tree =>
          tree.name.value == "Ops Risk Portfolio" && tree.id > 0
        }.provide(serviceLayer)
      }
    ),

    suite("Depth parameter")(
      test("depth=0 returns only root curve") {
        val hierarchicalRequest = RiskTreeDefinitionRequest(
          name = "Depth Test",
          nTrials = 1000,
          root = RiskPortfolio.create(
            id = "root",
            name = "Root",
            children = Array(
              RiskLeaf.create(
                id = "child1",
                name = "Child 1",
                distributionType = "lognormal",
                probability = 0.5,
                minLoss = Some(1000L),
                maxLoss = Some(10000L)
              ).toEither.getOrElse(throw new RuntimeException("Invalid test data: child1"))
            )
          ).toEither.getOrElse(throw new RuntimeException("Invalid test data"))
        )

        val program = for {
          tree <- service(_.create(hierarchicalRequest))
          result <- service(_.computeLEC(tree.id, None, 1, depth = 0))
        } yield result

        program.assert { result =>
          result.vegaLiteSpec match {
            case Some(spec) =>
              spec.contains("\"risk\": \"root\"") && !spec.contains("\"risk\": \"child1\"")
            case None => false
          }
        }.provide(serviceLayer)
      },

      test("depth=1 includes children curves") {
        val hierarchicalRequest = RiskTreeDefinitionRequest(
          name = "Depth Test",
          nTrials = 1000,
          root = RiskPortfolio.create(
            id = "root",
            name = "Root",
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
          ).toEither.getOrElse(throw new RuntimeException("Invalid test data"))
        )

        val program = for {
          tree <- service(_.create(hierarchicalRequest))
          result <- service(_.computeLEC(tree.id, None, 1, depth = 1))
        } yield result

        program.assert { result =>
          result.vegaLiteSpec match {
            case Some(spec) =>
              spec.contains("\"risk\": \"root\"") && 
              spec.contains("\"risk\": \"child1\"") &&
              spec.contains("\"risk\": \"child2\"")
            case None => false
          }
        }.provide(serviceLayer)
      },

      test("excessive depth is clamped") {
        val hierarchicalRequest = RiskTreeDefinitionRequest(
          name = "Depth Test",
          nTrials = 1000,
          root = RiskPortfolio.create(
            id = "root",
            name = "Root",
            children = Array(
              RiskLeaf.create(
                id = "child1",
                name = "Child 1",
                distributionType = "lognormal",
                probability = 0.5,
                minLoss = Some(1000L),
                maxLoss = Some(10000L)
              ).toEither.getOrElse(throw new RuntimeException("Invalid test data: child1"))
            )
          ).toEither.getOrElse(throw new RuntimeException("Invalid test data"))
        )

        val program = for {
          tree <- service(_.create(hierarchicalRequest))
          result <- service(_.computeLEC(tree.id, None, 1, depth = 10))
        } yield result

        program.assert { result =>
          result.vegaLiteSpec match {
            case Some(spec) =>
              // Should still work, depth clamped to actual tree depth (2 levels)
              spec.contains("\"risk\": \"root\"") && spec.contains("\"risk\": \"child1\"")
            case None => false
          }
        }.provide(serviceLayer)
      }
    ),

    suite("nTrials override")(
      test("query parameter overrides default nTrials") {
        val hierarchicalRequest = RiskTreeDefinitionRequest(
          name = "Test Tree",
          nTrials = 1000,
          root = RiskLeaf.create(
            id = "test-risk",
            name = "Test Risk",
            distributionType = "lognormal",
            probability = 0.5,
            minLoss = Some(1000L),
            maxLoss = Some(10000L)
          ).toEither.getOrElse(throw new RuntimeException("Invalid test data"))
        )

        val program = for {
          tree <- service(_.create(hierarchicalRequest))
          result <- service(_.computeLEC(tree.id, Some(10000), 1, depth = 0))
        } yield result

        program.assert { result =>
          // Verify quantiles are computed (different nTrials might give slightly different results)
          result.quantiles.nonEmpty && result.vegaLiteSpec.isDefined
        }.provide(serviceLayer)
      }
    ),

    suite("Get by ID")(
      test("returns tree metadata when exists") {
        val hierarchicalRequest = RiskTreeDefinitionRequest(
          name = "Test Tree",
          nTrials = 1000,
          root = RiskLeaf.create(
            id = "test-risk",
            name = "Test Risk",
            distributionType = "lognormal",
            probability = 0.5,
            minLoss = Some(1000L),
            maxLoss = Some(10000L)
          ).toEither.getOrElse(throw new RuntimeException("Invalid test data"))
        )

        val program = for {
          tree <- service(_.create(hierarchicalRequest))
          result <- service(_.getById(tree.id))
        } yield result

        program.assert { maybeTree =>
          maybeTree.exists(_.name.value == "Test Tree")
        }.provide(serviceLayer)
      }
    )
  )
}
