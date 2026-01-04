import zio.*
import sttp.tapir.ztapir.*
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import zio.http.Server

object MinimalTapir extends ZIOAppDefault {
  val pingEndpoint = endpoint.get.in("ping").out(stringBody).zServerLogic[Any](_ => ZIO.succeed("pong"))
  val app = ZioHttpInterpreter().toHttp(pingEndpoint)
  override def run = Server.serve(app).provide(Server.default)
}
