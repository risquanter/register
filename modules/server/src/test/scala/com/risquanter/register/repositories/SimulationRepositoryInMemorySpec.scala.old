package com.risquanter.register.repositories

import zio.*
import zio.test.*
import io.github.iltotore.iron.*

import com.risquanter.register.domain.data.Simulation
import com.risquanter.register.domain.data.iron.SafeName
import com.risquanter.register.syntax.*

object SimulationRepositoryInMemorySpec extends ZIOSpecDefault {

  private val repo = ZIO.serviceWithZIO[SimulationRepository]

  private val testSimulation = Simulation(
    id = 0L,
    name = SafeName.SafeName("Test Simulation".refineUnsafe),
    minLoss = 1000L,
    maxLoss = 50000L,
    likelihoodId = 5L,
    probability = 0.75
  )

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("SimulationRepositoryInMemory")(
      test("create assigns ID and persists simulation") {
        val program = repo(_.create(testSimulation))

        program.assert { created =>
          created.id > 0 &&
            created.name == testSimulation.name &&
            created.minLoss == testSimulation.minLoss
        }
      },

      test("create assigns sequential IDs") {
        val program = for {
          sim1 <- repo(_.create(testSimulation))
          sim2 <- repo(_.create(testSimulation))
        } yield (sim1.id, sim2.id)

        program.assert { case (id1, id2) =>
          id2 == id1 + 1
        }
      },

      test("getById returns simulation when exists") {
        val program = for {
          created <- repo(_.create(testSimulation))
          found   <- repo(_.getById(created.id))
        } yield (created, found)

        program.assert {
          case (created, Some(found)) => found.id == created.id
          case _                      => false
        }
      },

      test("getById returns None when not exists") {
        val program = repo(_.getById(999L))

        program.assert(_ == None)
      },

      test("getAll returns all simulations") {
        val program = for {
          sim1 <- repo(_.create(testSimulation))
          sim2 <- repo(_.create(testSimulation.copy(name = SafeName.SafeName("Second".refineUnsafe))))
          all  <- repo(_.getAll)
        } yield (sim1, sim2, all)

        program.assert { case (sim1, sim2, all) =>
          all.length == 2 &&
            all.map(_.id).toSet == Set(sim1.id, sim2.id)
        }
      },

      test("update modifies existing simulation") {
        val program = for {
          created <- repo(_.create(testSimulation))
          updated <- repo(_.update(
            created.id,
            _.copy(name = SafeName.SafeName("Updated Name".refineUnsafe))
          ))
          found <- repo(_.getById(created.id))
        } yield (updated, found)

        program.assert {
          case (updated, Some(found)) =>
            found.name == SafeName.SafeName("Updated Name".refineUnsafe) &&
              found.id == updated.id
          case _ => false
        }
      },

      test("update fails when simulation not found") {
        val program = repo(
          _.update(999L, _.copy(minLoss = 2000L))
        ).flip

        program.assert(_.isInstanceOf[NoSuchElementException])
      },

      test("delete removes simulation") {
        val program = for {
          created <- repo(_.create(testSimulation))
          deleted <- repo(_.delete(created.id))
          found   <- repo(_.getById(created.id))
        } yield (created.id, deleted.id, found)

        program.assert {
          case (createdId, deletedId, None) => deletedId == createdId
          case _                             => false
        }
      },

      test("delete fails when simulation not found") {
        val program = repo(_.delete(999L)).flip

        program.assert(_.isInstanceOf[NoSuchElementException])
      }
    ).provide(
      SimulationRepositoryInMemory.layer
    ) @@ TestAspect.sequential
}
