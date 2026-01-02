package com.risquanter.register.services

import zio.*
import zio.test.*
import io.github.iltotore.iron.*

import com.risquanter.register.http.requests.CreateSimulationRequest
import com.risquanter.register.domain.data.Simulation
import com.risquanter.register.domain.data.iron.SafeName
import com.risquanter.register.repositories.SimulationRepository
import com.risquanter.register.domain.errors.ValidationFailed
import com.risquanter.register.syntax.*


object SimulationServiceLiveSpec extends ZIOSpecDefault {

  // Concise service accessor pattern from BCG
  private val service = ZIO.serviceWithZIO[SimulationService]

  // Stub repository factory - creates fresh instance per test
  private def makeStubRepo = new SimulationRepository {
    private val db = collection.mutable.Map[Long, Simulation]()
    
    override def create(simulation: Simulation): Task[Simulation] = ZIO.attempt {
      val nextId = db.keys.maxOption.getOrElse(0L) + 1L
      val newSimulation = simulation.copy(id = nextId.refineUnsafe)
      db += (nextId -> newSimulation)
      newSimulation
    }
    
    override def update(id: Long, op: Simulation => Simulation): Task[Simulation] = ZIO.attempt {
      val simulation = db(id)
      val updated = op(simulation)
      db += (id -> updated)
      updated
    }
    
    override def delete(id: Long): Task[Simulation] = ZIO.attempt {
      val simulation = db(id)
      db -= id
      simulation
    }
    
    override def getById(id: Long): Task[Option[Simulation]] =
      ZIO.succeed(db.get(id))
    
    override def getAll: Task[List[Simulation]] =
      ZIO.succeed(db.values.toList)
  }
  
  private val stubRepoLayer = ZLayer.fromFunction(() => makeStubRepo)

  private val validRequest = CreateSimulationRequest(
    name = "Test Simulation",
    minLoss = 1000L,
    maxLoss = 50000L,
    likelihoodId = 5L,
    probability = 0.75
  )

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("SimulationServiceLive")(
      test("create validates and persists simulation") {
        val program = service(_.create(validRequest))

        program.assert { sim =>
          sim.id > 0 &&
            sim.name == SafeName.SafeName("Test Simulation".refineUnsafe) &&
            sim.minLoss == 1000L &&
            sim.maxLoss == 50000L &&
            sim.likelihoodId == 5L &&
            sim.probability == 0.75
        }
      },

      test("create fails with invalid name") {
        val invalidRequest = validRequest.copy(name = "")
        val program = service(_.create(invalidRequest).flip)

        program.assert {
          case ValidationFailed(errors) => errors.exists(e => e.toLowerCase.contains("name"))
          case _                        => false
        }
      },

      test("create fails with negative minLoss") {
        val invalidRequest = validRequest.copy(minLoss = -100L)
        val program = service(_.create(invalidRequest).flip)

        program.assert {
          case ValidationFailed(errors) => errors.exists(_.contains("minLoss"))
          case _                        => false
        }
      },

      test("create fails with invalid probability") {
        val invalidRequest = validRequest.copy(probability = 1.5)
        val program = service(_.create(invalidRequest).flip)

        program.assert {
          case ValidationFailed(errors) => errors.exists(_.contains("probRiskOccurance"))
          case _                        => false
        }
      },

      test("getAll returns all simulations") {
        val program = for {
          sim1 <- service(_.create(validRequest))
          sim2 <- service(_.create(validRequest.copy(name = "Second Simulation")))
          all  <- service(_.getAll)
        } yield (sim1, sim2, all)

        program.assert { case (sim1, sim2, all) =>
          all.length == 2 &&
            all.map(_.id).toSet == Set(sim1.id, sim2.id)
        }
      },

      test("getById returns simulation when exists") {
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
      }
    ).provide(
      SimulationServiceLive.layer,
      stubRepoLayer
    ) @@ TestAspect.sequential
}
