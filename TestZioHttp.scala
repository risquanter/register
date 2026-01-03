import zio.*
import zio.http.*

object TestZioHttp extends ZIOAppDefault {
  
  val app = Routes(
    Method.GET / "hello" -> handler(Response.text("Hello World!"))
  )
  
  val program = for {
    _ <- Console.printLine("Starting minimal ZIO HTTP server on port 8080...")
    _ <- Server.serve(app)
  } yield ()
  
  override def run = program.provide(Server.default)
}
