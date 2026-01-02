package com.risquanter.register.services

import zio.*
import com.risquanter.register.http.requests.CreateSimulationRequest
import com.risquanter.register.domain.data.Simulation
import com.risquanter.register.domain.data.iron.ValidationUtil
import com.risquanter.register.domain.errors.ValidationFailed
import com.risquanter.register.repositories.SimulationRepository
import io.github.iltotore.iron.autoRefine


/** Live implementation of SimulationService
  * Handles validation and delegates to repository layer
  */
class SimulationServiceLive private (repo: SimulationRepository) extends SimulationService {
  
  override def create(req: CreateSimulationRequest): Task[Simulation] =
    for {
      validatedSimulation <- validateAndBuild(req)
      created <- repo.create(validatedSimulation)
    } yield created

  override def getAll: Task[List[Simulation]] =
    repo.getAll

  override def getById(id: Long): Task[Option[Simulation]] =
    repo.getById(id)

  private def validateAndBuild(req: CreateSimulationRequest): Task[Simulation] = {
    val validation = for {
      name <- ValidationUtil.refineName(req.name)
      minLoss <- ValidationUtil.refineNonNegativeLong(req.minLoss, "minLoss")
      maxLoss <- ValidationUtil.refineNonNegativeLong(req.maxLoss, "maxLoss")
      likelihoodId <- ValidationUtil.refineNonNegativeLong(req.likelihoodId, "likelihoodId")
      probability <- ValidationUtil.refineProbability(req.probability)
    } yield Simulation(
      id = 0L, // Will be assigned by repository
      name = name,
      minLoss = minLoss,
      maxLoss = maxLoss,
      likelihoodId = likelihoodId,
      probability = probability
    )

    validation match {
      case Left(errors) => ZIO.fail(ValidationFailed(errors))
      case Right(sim) => ZIO.succeed(sim)
    }
  }
}

object SimulationServiceLive {
  val layer: ZLayer[SimulationRepository, Nothing, SimulationService] = ZLayer {
    for {
      repo <- ZIO.service[SimulationRepository]
    } yield new SimulationServiceLive(repo)
  }
}
