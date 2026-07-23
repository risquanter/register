package app.state

import com.raquo.laminar.api.L.{*, given}
import org.scalajs.dom
import zio.json.*

import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js.Thenable.Implicits.*
import scala.util.{Failure, Success}

/** App-wide static configuration, fetched once from `/config.json` at
  * startup (nginx-templated per deployment from `REGISTER_REPOSITORY_TYPE`
  * — see `Dockerfile.frontend-prod`).
  *
  * Not a Tapir endpoint: `/config.json` is a static file the container
  * entrypoint writes before nginx starts, not a backend API call, so this
  * uses a plain `fetch` rather than the ZJS/BackendClient bridge.
  */
final class AppConfigState:

  /** Whether the backend supports scenarios (Irmin repository). Defaults to
    * `false` — the safe default while the fetch is in flight or if it fails,
    * matching Variant R (BranchBar surfaces stay hidden unless positively
    * confirmed available).
    */
  val scenariosEnabled: Var[Boolean] = Var(false)

  /** Deployed app version (APP_VERSION env, templated into `/config.json`
    * by the frontend container entrypoint). "dev" while the fetch is in
    * flight, if it fails, or when the field is absent (Vite dev server).
    */
  val appVersion: Var[String] = Var("dev")

  def refresh(): Unit =
    dom.fetch("/config.json").toFuture
      .flatMap(_.text().toFuture)
      .onComplete {
        case Success(body) =>
          body.fromJson[AppConfigState.ConfigPayload] match
            case Right(cfg) =>
              scenariosEnabled.set(cfg.scenariosEnabled)
              cfg.appVersion.foreach(appVersion.set)
            case Left(_) => ()
        case Failure(_) => ()
      }

object AppConfigState:
  private final case class ConfigPayload(scenariosEnabled: Boolean, appVersion: Option[String] = None)
  private object ConfigPayload:
    given JsonDecoder[ConfigPayload] = DeriveJsonDecoder.gen[ConfigPayload]
