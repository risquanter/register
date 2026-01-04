import zio.*
import zio.http.*
import sttp.tapir.{endpoint as tapirEndpoint, *}
import sttp.tapir.server.ziohttp.ZioHttpInterpreter

object TestTapirZioHttp extends ZIOAppDefault {
  
  val helloEndpoint = tapirEndpoint.get
    .in("hello")
    .out(stringBody)
    .serverLogicSuccess[Task](_ => ZIO.succeed("Hello from Tapir!"))
  
  val program = for {
    _ <- Console.printLine("Starting Tapir ZIO HTTP server on port 8080...")
    routes = ZioHttpInterpreter().toHttp(List(helloEndpoint))
    _ <- Server.serve(routes)
  } yield ()
  
  override def run = program.provide(Server.default)
}
