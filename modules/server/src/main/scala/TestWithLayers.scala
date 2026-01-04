import zio.*
import sttp.tapir.ztapir.*
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import zio.http.Server
import com.risquanter.register.http.HttpApi
import com.risquanter.register.http.controllers.RiskTreeController
import com.risquanter.register.services.RiskTreeServiceLive
import com.risquanter.register.repositories.RiskTreeRepositoryInMemory

object TestWithLayers extends ZIOAppDefault {
  override def run = {
    val program = for {
      _         <- ZIO.logInfo("Getting endpoints...")
      endpoints <- HttpApi.endpointsZIO
      _         <- ZIO.logInfo(s"Got ${endpoints.length} endpoints")
      app        = ZioHttpInterpreter().toHttp(endpoints)
      _         <- ZIO.logInfo("Serving...")
      _         <- Server.serve(app)
    } yield ()

    program.provide(
      Server.default,
      RiskTreeRepositoryInMemory.layer,
      com.risquanter.register.services.SimulationExecutionService.live,
      RiskTreeServiceLive.layer,
      ZLayer.fromZIO(RiskTreeController.makeZIO)
    )
  }
}
