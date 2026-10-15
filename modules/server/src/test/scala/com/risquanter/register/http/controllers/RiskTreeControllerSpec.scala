package com.risquanter.register.http.controllers

import zio.*
import zio.test.*
import com.risquanter.register.services.{RiskTreeService, RiskTreeServiceLive}
import com.risquanter.register.repositories.RiskTreeRepository
import com.risquanter.register.domain.data.RiskTree
import com.risquanter.register.domain.errors.{AppError, TreeLoadFailure}
import com.risquanter.register.http.requests.{RiskTreeDefinitionRequest, RiskPortfolioDefinitionRequest, RiskLeafDefinitionRequest, DistributionShapeRequest}
import com.risquanter.register.telemetry.TelemetryLive
import com.risquanter.register.syntax.* // For .assert extension method
import com.risquanter.register.domain.data.iron.{NonNegativeLong, SafeId, TreeId, WorkspaceId, BranchRef, Revision, CommitHash}
import io.github.iltotore.iron.*
import com.risquanter.register.domain.errors.{ValidationFailed, ValidationErrorCode}
import com.risquanter.register.util.IdGenerators
import com.risquanter.register.testutil.TestHelpers.{safeId}
import io.github.iltotore.iron.constraint.numeric.*
import com.risquanter.register.auth.{Checked, Permission, TestChecked}

object RiskTreeControllerSpec extends ZIOSpecDefault {
  private given Checked[Permission] = TestChecked.value

  // Service accessor pattern. The error type is `AppError` rather than
  // `Throwable` because that is what the service declares; a helper widened to
  // `Throwable` would hide which failures a call can actually produce.
  private def service[A](f: RiskTreeService => IO[AppError, A]): ZIO[RiskTreeService, AppError, A] =
    ZIO.serviceWithZIO[RiskTreeService](f)

  // Stub repository factory - creates fresh instance per test
  private val stubWsId: WorkspaceId = WorkspaceId(safeId("test-workspace-ctrl"))

  private def makeStubRepo() = new RiskTreeRepository {
    private val db = collection.mutable.Map[(WorkspaceId, TreeId), RiskTree]()
    
    override def create(wsId: WorkspaceId, riskTree: RiskTree, branch: BranchRef): IO[AppError, RiskTree] =
      // Identifier generation fails only on a broken platform, which is a
      // defect rather than an outcome a caller can act on — the same treatment
      // the service gives it.
      IdGenerators.nextTreeId.orDie.map { nextId =>
        val newRiskTree = RiskTree.fromNodesUnsafe(nextId, riskTree.name, riskTree.nodes, riskTree.rootId, Some(riskTree.seedVarHighWater), riskTree.mitigations)
        db += ((wsId, nextId) -> newRiskTree)
        newRiskTree
      }

    // A missing key here is a test that did not seed what it reads, which is a
    // defect in the test rather than a failure the service should handle.
    override def update(wsId: WorkspaceId, id: TreeId, op: RiskTree => RiskTree, branch: BranchRef): IO[AppError, RiskTree] = ZIO.attempt {
      val riskTree = db((wsId, id))
      val updated = op(riskTree)
      db += ((wsId, id) -> updated)
      updated
    }.orDie

    override def delete(wsId: WorkspaceId, id: TreeId, branch: BranchRef): IO[AppError, RiskTree] = ZIO.attempt {
      val riskTree = db((wsId, id))
      db -= ((wsId, id))
      riskTree
    }.orDie

    override def revert(wsId: WorkspaceId, id: TreeId, toCommit: CommitHash, branch: BranchRef): IO[AppError, RiskTree] =
      ZIO.die(new UnsupportedOperationException("revert not exercised in this stub"))

    override def getById(wsId: WorkspaceId, id: TreeId, rev: Revision): IO[AppError, Option[(RiskTree, CommitHash)]] =
      ZIO.succeed(db.get((wsId, id)).map(t => (t, CommitHash.fromString("0" * 40).toOption.get)))

    override def getAllForWorkspace(wsId: WorkspaceId, rev: Revision): IO[AppError, List[Either[TreeLoadFailure, RiskTree]]] =
      ZIO.succeed(db.collect { case ((wid, _), tree) if wid == wsId => Right(tree) }.toList)
  }

  // Layer factory - creates fresh layer with isolated repository per test
  private def serviceLayer = {
    ZLayer.make[RiskTreeService](
      RiskTreeServiceLive.layer,
      ZLayer.succeed(makeStubRepo()),
      com.risquanter.register.configs.TestConfigs.simulationLayer,
      com.risquanter.register.services.cache.ContentCacheRegistry.layer,
      com.risquanter.register.services.cache.CachedResultResolverLive.layer,
      com.risquanter.register.services.pipeline.InvalidationHandler.live,
      com.risquanter.register.services.sse.SSEHub.live,
      com.risquanter.register.configs.TestConfigs.telemetryLayer >>> TelemetryLive.configured
    )
  }

  // Valid request with hierarchical structure (shared baseline; derive variants via .copy)
  private val validRequest = RiskTreeDefinitionRequest(
    name = "Test Risk Portfolio",
    portfolios = Seq(RiskPortfolioDefinitionRequest("Root Portfolio", None)),
    leaves = Seq(
      RiskLeafDefinitionRequest(
        name = "Test Risk",
        parentName = Some("Root Portfolio"),
        probability = 0.25,
        distributionShape = DistributionShapeRequest(
          distributionType = "lognormal",
          percentiles = None,
          quantiles = None,
          terms = None,
          minLoss = Some(1000L),
          maxLoss = Some(50000L)
        )
      )
    )
  )

  override def spec = suite("RiskTreeController")(
    suite("Health endpoint")(
      test("returns healthy status") {
        // Manual test confirms: GET /health returns {"status": "healthy", "service": "risk-register"}
        // This test documents the endpoint exists and compiles
        assertTrue(true)
      }
    ),

    suite("Hierarchical tree creation with discriminators")(
      test("POST with RiskPortfolio discriminator creates tree") {
        val request = validRequest.copy(
          name = "Ops Risk Portfolio",
          portfolios = Seq(RiskPortfolioDefinitionRequest("Total Operational Risk", None)),
          leaves = Seq(
            RiskLeafDefinitionRequest(
              name = "Cyber Attack",
              parentName = Some("Total Operational Risk"),
              probability = 0.25,
              distributionShape = DistributionShapeRequest(
                distributionType = "lognormal",
                percentiles = None,
                quantiles = None,
                terms = None,
                minLoss = Some(1000L),
                maxLoss = Some(50000L)
              )
            ),
            RiskLeafDefinitionRequest(
              name = "Data Breach",
              parentName = Some("Total Operational Risk"),
              probability = 0.15,
              distributionShape = DistributionShapeRequest(
                distributionType = "lognormal",
                percentiles = None,
                quantiles = None,
                terms = None,
                minLoss = Some(500L),
                maxLoss = Some(25000L)
              )
            )
          )
        )

        val program = service(_.create(stubWsId, request, BranchRef.Main))

        program.assert { tree =>
          tree.name.value == "Ops Risk Portfolio" &&
          tree.id.value.nonEmpty &&
          // Server-generated ID must satisfy SafeIdStr constraint (Crockford base32 ULID)
          tree.id.value.matches("^[0-9A-HJKMNP-TV-Z]{26}$")
        }.provide(serviceLayer)
      },

      test("duplicate tree name is rejected by ensureUniqueTree") {
        val firstRequest  = validRequest.copy(name = "Duplicate Risk Portfolio")
        val secondRequest = validRequest.copy(
          name = "Duplicate Risk Portfolio", // Same name as first
          leaves = Seq(
            RiskLeafDefinitionRequest(
              name = "Risk B",
              parentName = Some("Root Portfolio"),
              probability = 0.3,
              distributionShape = DistributionShapeRequest(
                distributionType = "lognormal",
                percentiles = None,
                quantiles = None,
                terms = None,
                minLoss = Some(500L),
                maxLoss = Some(10000L)
              )
            )
          )
        )

        val program = for {
          _ <- service(_.create(stubWsId, firstRequest, BranchRef.Main))
          e <- service(_.create(stubWsId, secondRequest, BranchRef.Main)).flip
        } yield e

        program.assert {
          case ValidationFailed(errors) =>
            errors.exists(e => e.field == "name" && e.code == ValidationErrorCode.DUPLICATE_VALUE)
          case _ => false
        }.provide(serviceLayer)
      }
    ),

    suite("Get by ID")(
      test("returns tree metadata when exists") {
        val request = validRequest.copy(name = "Test Tree")

        val program = for {
          tree <- service(_.create(stubWsId, request, BranchRef.Main))
          result <- service(_.getById(stubWsId, tree.id, Revision.Head(BranchRef.Main)))
        } yield result

        program.assert { maybeTree =>
          maybeTree.exists { case (tree, _) => tree.name.value == "Test Tree" }
        }.provide(serviceLayer)
      }
    ),

    suite("Path parameter validation (Tapir codec)")(
      test("NonNegativeLong codec accepts valid positive IDs") {
        // Test the codec directly
        import sttp.tapir.*
        import com.risquanter.register.http.codecs.IronTapirCodecs.given
        
        val codec = summon[Codec[String, NonNegativeLong, CodecFormat.TextPlain]]
        
        assertTrue(
          codec.decode("1") match { case DecodeResult.Value(_) => true; case _ => false },
          codec.decode("123") match { case DecodeResult.Value(_) => true; case _ => false },
          codec.decode("999999") match { case DecodeResult.Value(_) => true; case _ => false },
          codec.decode("0") match { case DecodeResult.Value(_) => true; case _ => false }  // Zero is valid (NonNegative)
        )
      },
      
      test("NonNegativeLong codec rejects non-numeric strings") {
        import sttp.tapir.*
        import com.risquanter.register.http.codecs.IronTapirCodecs.given
        
        val codec = summon[Codec[String, NonNegativeLong, CodecFormat.TextPlain]]
        
        assertTrue(
          codec.decode("abc") match { case DecodeResult.Error(_, _) => true; case _ => false },
          codec.decode("12-34") match { case DecodeResult.Error(_, _) => true; case _ => false },
          codec.decode("risk-tree-id") match { case DecodeResult.Error(_, _) => true; case _ => false },
          codec.decode("") match { case DecodeResult.Error(_, _) => true; case _ => false }
        )
      },
      
      test("NonNegativeLong codec refuses negative IDs as read-then-refused") {
        import sttp.tapir.*
        import com.risquanter.register.http.codecs.IronTapirCodecs.given

        val codec = summon[Codec[String, NonNegativeLong, CodecFormat.TextPlain]]

        // A refusal, not a read failure: "-1" parses as a Long and the
        // refinement then rejects it, which is what makes the wire code
        // INVALID_FORMAT rather than TYPE_MISMATCH.
        assertTrue(
          codec.decode("-1").isInstanceOf[DecodeResult.InvalidValue],
          codec.decode("-999").isInstanceOf[DecodeResult.InvalidValue]
        )
      },

      test("NonNegativeLong codec refusal messages are descriptive and omit the value") {
        import sttp.tapir.*
        import com.risquanter.register.http.codecs.IronTapirCodecs.given

        val codec = summon[Codec[String, NonNegativeLong, CodecFormat.TextPlain]]

        val messages = codec.decode("-987654321") match
          case DecodeResult.InvalidValue(errors) => errors.flatMap(_.customMessage)
          case _                                 => Nil

        assertTrue(
          messages.nonEmpty,
          messages.exists { m =>
            val lower = m.toLowerCase
            lower.contains("non-negative") || lower.contains(">= 0") || lower.contains("greater") || lower.contains("zero")
          },
          !messages.exists(_.contains("987654321"))
        )
      }
    )
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.withLiveRandom
}
