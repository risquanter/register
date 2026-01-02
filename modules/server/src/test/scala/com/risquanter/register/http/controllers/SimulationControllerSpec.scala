package com.risquanter.register.http.controllers

import zio.*
import zio.test.*
import sttp.client3.*
import sttp.client3.testing.SttpBackendStub
import sttp.tapir.server.stub.TapirStubInterpreter
import sttp.tapir.server.ServerEndpoint
import sttp.monad.MonadError
import sttp.tapir.ztapir.RIOMonadError
import zio.json.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine

import com.risquanter.register.services.SimulationService
import com.risquanter.register.http.requests.CreateSimulationRequest
import com.risquanter.register.http.responses.SimulationResponse
import com.risquanter.register.domain.data.Simulation
import com.risquanter.register.domain.data.iron.SafeName
import com.risquanter.register.syntax.*

object SimulationControllerSpec extends ZIOSpecDefault {

  // MonadError instance for ZIO Task
  private given zioME: MonadError[Task] = new RIOMonadError[Any]

  // Test data
  private val testSimulation = Simulation(
    id = 1L,
    name = SafeName.SafeName("Test Simulation".refineUnsafe),
    minLoss = 1000L,
    maxLoss = 50000L,
    likelihoodId = 5L,
    probability = 0.75
  )

  // Stub service implementation
  private val simServiceStub = new SimulationService {
    override def create(req: CreateSimulationRequest): Task[Simulation] =
      ZIO.succeed(testSimulation)

    override def getAll: Task[List[Simulation]] =
      ZIO.succeed(List(testSimulation))

    override def getById(id: Long): Task[Option[Simulation]] =
      ZIO.succeed(if (id == 1L) Some(testSimulation) else None)
  }

  // Helper to create backend stub with endpoint
  def backendStubZIO(endpointFun: SimulationController => ServerEndpoint[Any, Task]) =
    for {
      controller <- SimulationController.makeZIO
      backendStub <- ZIO.succeed(
        TapirStubInterpreter(SttpBackendStub(MonadError[Task]))
          .whenServerEndpointRunLogic(endpointFun(controller))
          .backend()
      )
    } yield backendStub

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("SimulationController")(
      test("create returns new simulation") {
        val program = for {
          backendStub <- backendStubZIO(_.create)
          response <- basicRequest
            .post(uri"/simulations")
            .body(
              CreateSimulationRequest(
                name = "Test Simulation",
                minLoss = 1000L,
                maxLoss = 50000L,
                likelihoodId = 5L,
                probability = 0.75
              ).toJson
            )
            .send(backendStub)
          simResp <- ZIO.succeed(
            response.body.toOption
              .flatMap(_.fromJson[SimulationResponse].toOption)
          )
        } yield simResp

        program.assert {
          case Some(resp) =>
            resp.id == 1L &&
            resp.name == "Test Simulation" &&
            resp.minLoss == 1000L &&
            resp.maxLoss == 50000L
          case _ => false
        }
      },
      
      test("getAll returns list of simulations") {
        val program = for {
          backendStub <- backendStubZIO(_.getAll)
          response <- basicRequest
            .get(uri"/simulations")
            .send(backendStub)
          result <- ZIO.succeed(
            response.body.toOption
              .flatMap(_.fromJson[List[SimulationResponse]].toOption)
          )
        } yield result

        program.assert {
          case Some(list) =>
            list.length == 1 &&
            list.head.name == "Test Simulation"
          case _ => false
        }
      },
      
      test("getById returns simulation when exists") {
        val program = for {
          backendStub <- backendStubZIO(_.getById)
          response <- basicRequest
            .get(uri"/simulations/1")
            .send(backendStub)
          result <- ZIO.succeed(
            response.body.toOption
              .flatMap(_.fromJson[Option[SimulationResponse]].toOption)
              .flatten
          )
        } yield result

        program.assert {
          case Some(sim) => sim.id == 1L
          case _ => false
        }
      },
      
      test("getById returns None when not exists") {
        val program = for {
          backendStub <- backendStubZIO(_.getById)
          response <- basicRequest
            .get(uri"/simulations/999")
            .send(backendStub)
          parsed <- ZIO.succeed {
            val bodyStr = response.body.toOption.getOrElse("")
            if (bodyStr.isEmpty) Some(None) 
            else bodyStr.fromJson[Option[SimulationResponse]].toOption
          }
        } yield parsed

        program.assert(_ == Some(None))
      }
    ).provide(ZLayer.succeed(simServiceStub))
}
