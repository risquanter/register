package com.risquanter.register.http

import zio.*
import zio.test.*
import sttp.client3.*
import sttp.client3.ziojson.*

import zio.json.*

import com.risquanter.register.domain.errors.ValidationErrorCode
import com.risquanter.register.http.errors.WireError
import com.risquanter.register.http.HttpTestHarness.HarnessConfig
import com.risquanter.register.http.requests.{
  RiskTreeDefinitionRequest, RiskPortfolioDefinitionRequest,
  RiskLeafDefinitionRequest, ExpertShapeRequest, LognormalShapeRequest
}
import com.risquanter.register.http.responses.WorkspaceBootstrapResponse
import com.risquanter.register.http.support.SttpClientFixture

/** A lognormal leaf's confidence-interval bounds and an expert leaf's quantile
  * loss amounts both feed a logarithm in the distribution fit, so each must be
  * strictly positive. Both rules are enforced by the request decoder, which
  * means a tree carrying a zero is rejected with a 400 before any handler runs
  * and nothing is stored.
  *
  * The rules live in `DistributionShape.Lognormal.fromPrimitives`, in
  * `QuantileAnchors.fromPairedSequences`, and in the `PositiveLong` and
  * `PositiveDouble` refinements each calls, so these cases prove the chain
  * holds end to end: the JSON body
  * reaches the decoder, the decoder refines, and the refinement failure becomes
  * the HTTP error the client sees.
  */
object LognormalBoundPositivityItSpec extends ZIOSpecDefault:

  private val harness = HarnessConfig()

  private def lognormalLeaf(min: Long, max: Long): RiskLeafDefinitionRequest =
    RiskLeafDefinitionRequest(
      name = "Cyber",
      parentName = Some("Root"),
      probability = 0.25,
      distributionShape = LognormalShapeRequest(minLoss = min, maxLoss = max)
    )

  private def expertLeaf(quantiles: Vector[Double]): RiskLeafDefinitionRequest =
    RiskLeafDefinitionRequest(
      name = "Outage",
      parentName = Some("Root"),
      probability = 0.25,
      distributionShape = ExpertShapeRequest(
        percentiles = Vector(0.05, 0.50, 0.95),
        quantiles = quantiles,
        terms = Some(3)
      )
    )

  private def treeWith(leaf: RiskLeafDefinitionRequest): RiskTreeDefinitionRequest =
    RiskTreeDefinitionRequest(
      name = "Bound Positivity Tree",
      portfolios = Seq(RiskPortfolioDefinitionRequest("Root", None)),
      leaves = Seq(leaf)
    )

  private def withServer[A](f: SttpClientFixture.Client => Task[A]): Task[A] =
    ZIO.scoped {
      ZLayer.makeSome[Scope, SttpClientFixture.Client](
        HttpTestHarness.inMemoryServer(harness),
        SttpClientFixture.layer
      ).build.map(_.get[SttpClientFixture.Client]).flatMap(f)
    }

  /** POST a tree definition and return the status code with the raw body. */
  private def postTree(
    client: SttpClientFixture.Client,
    req: RiskTreeDefinitionRequest
  ): Task[(Int, String)] =
    basicRequest.header("X-Branch", "main")
      .post(uri"${client.baseUrl}/workspaces")
      .body(req)
      .response(asStringAlways)
      .send(client.backend)
      .map(resp => (resp.code.code, resp.body))

  /** The validation codes a rejected request's body carries.
    *
    * The body is decoded as `WireError.RequestRejected` rather than as the
    * enclosing type: each status maps to exactly one wire case, so a 400 from
    * the decoder is that case and nothing else. A body that does not decode as
    * it is a failure of this helper, which is what makes the status-to-case
    * relation part of what these tests check.
    *
    * The request creates a workspace, so no tree exists to name yet and the
    * rejection is the request-level one. Content refused inside a known tree
    * answers `WireError.ValidationRejected` at 422 instead.
    */
  private def rangeErrorCodes(rawBody: String): Task[List[ValidationErrorCode]] =
    ZIO.fromEither(
      rawBody.fromJson[WireError.RequestRejected]
        .map(_.errors.map(_.code))
        .left.map(err => s"Failed to decode a validation rejection: $err\nBody was: $rawBody")
    ).mapError(new RuntimeException(_))

  override def spec =
    suite("LognormalBoundPositivityItSpec")(

      test("a lognormal leaf with minLoss 0 is rejected with 400 INVALID_RANGE") {
        withServer { client =>
          for
            (code, body) <- postTree(client, treeWith(lognormalLeaf(0L, 50000L)))
            codes        <- rangeErrorCodes(body)
          yield assertTrue(
            code == 400,
            codes.contains(ValidationErrorCode.INVALID_RANGE)
          )
        }
      },

      test("a lognormal leaf with maxLoss 0 is rejected with 400") {
        withServer { client =>
          for
            (code, body) <- postTree(client, treeWith(lognormalLeaf(1000L, 0L)))
            codes        <- rangeErrorCodes(body)
          yield assertTrue(
            code == 400,
            codes.contains(ValidationErrorCode.INVALID_RANGE)
          )
        }
      },

      test("an expert leaf with a zero quantile is rejected with 400 INVALID_RANGE") {
        withServer { client =>
          for
            (code, body) <- postTree(client, treeWith(expertLeaf(Vector(0.0, 5000.0, 25000.0))))
            codes        <- rangeErrorCodes(body)
          yield assertTrue(
            code == 400,
            codes.contains(ValidationErrorCode.INVALID_RANGE)
          )
        }
      },

      test("the smallest positive bounds are accepted, so the rejection is specific to zero") {
        withServer { client =>
          basicRequest.header("X-Branch", "main")
            .post(uri"${client.baseUrl}/workspaces")
            .body(treeWith(lognormalLeaf(1L, 2L)))
            .response(asJson[WorkspaceBootstrapResponse])
            .send(client.backend)
            .map(resp => assertTrue(resp.code.code == 200))
        }
      }

    ) @@ TestAspect.withLiveClock @@ TestAspect.sequential
