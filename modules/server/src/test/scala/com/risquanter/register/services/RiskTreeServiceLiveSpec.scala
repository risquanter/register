package com.risquanter.register.services

import zio.*
import zio.test.*
import io.github.iltotore.iron.*

import com.risquanter.register.http.requests.{CreateSimulationRequest}
import com.risquanter.register.domain.data.{RiskTree, RiskNode, RiskLeaf, RiskPortfolio}
import com.risquanter.register.domain.data.iron.SafeName
import com.risquanter.register.repositories.RiskTreeRepository
import com.risquanter.register.domain.errors.ValidationFailed
import com.risquanter.register.syntax.*


object RiskTreeServiceLiveSpec extends ZIOSpecDefault {

  // Concise service accessor pattern
  private val service = ZIO.serviceWithZIO[RiskTreeService]

  // Stub repository factory - creates fresh instance per test
  private def makeStubRepo = new RiskTreeRepository {
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
  
  private val stubRepoLayer = ZLayer.fromFunction(() => makeStubRepo)

  // Valid request with hierarchical structure
  private val validRequest = CreateSimulationRequest(
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
          lec <- service(_.computeLEC(created.id, None, 1))
        } yield (created, lec)

        program.assert { case (created, lec) =>
          lec.riskTree.id == created.id &&
            lec.riskTree.name == created.name &&
            lec.quantiles.nonEmpty
        }
      },

      test("create accepts hierarchical RiskNode structure") {
        val hierarchicalRequest = CreateSimulationRequest(
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
        // create() returns Validation failure, which won't compile into CreateSimulationRequest
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
        val program = service(_.getById(999L))

        program.assert(_ == None)
      },

      test("computeLEC with depth=0 returns only root") {
        val hierarchicalRequest = CreateSimulationRequest(
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
          result <- service(_.computeLEC(tree.id, None, 1, depth = 0))
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

      test("computeLEC with depth=1 includes children") {
        val hierarchicalRequest = CreateSimulationRequest(
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
        }
      },

      test("computeLEC clamps depth to maximum of 5") {
        val program = for {
          tree <- service(_.create(validRequest))
          result <- service(_.computeLEC(tree.id, None, 1, depth = 99))
        } yield result

        program.assert { result =>
          // Should not fail, depth should be clamped to 5
          result.vegaLiteSpec.nonEmpty &&
            result.quantiles.nonEmpty
        }
      },

      test("computeLEC rejects negative depth") {
        val program = for {
          tree <- service(_.create(validRequest))
          result <- service(_.computeLEC(tree.id, None, 1, depth = -1).flip)
        } yield result

        program.assert {
          case ValidationFailed(errors) => errors.exists(e => e.field.toLowerCase.contains("depth") || e.message.toLowerCase.contains("depth"))
          case _                        => false
        }
      },

      test("computeLEC generates valid Vega-Lite spec") {
        val program = for {
          tree <- service(_.create(validRequest))
          result <- service(_.computeLEC(tree.id, None, 1, depth = 0))
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
      }
    ).provide(
      RiskTreeServiceLive.layer,
      SimulationExecutionService.live,
      stubRepoLayer,
      com.risquanter.register.configs.TestConfigs.simulationLayer
    ) @@ TestAspect.sequential
}
