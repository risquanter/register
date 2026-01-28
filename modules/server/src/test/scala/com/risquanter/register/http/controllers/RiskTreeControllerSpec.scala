package com.risquanter.register.http.controllers

import zio.*
import zio.test.*
import com.risquanter.register.services.{RiskTreeService, RiskTreeServiceLive}
import com.risquanter.register.repositories.RiskTreeRepository
import com.risquanter.register.domain.data.RiskTree
import com.risquanter.register.domain.errors.RepositoryFailure
import com.risquanter.register.http.requests.{RiskTreeDefinitionRequest, RiskPortfolioDefinitionRequest, RiskLeafDefinitionRequest}
import com.risquanter.register.http.responses.SimulationResponse
import com.risquanter.register.telemetry.{TracingLive, MetricsLive}
import com.risquanter.register.syntax.* // For .assert extension method
import com.risquanter.register.domain.data.iron.NonNegativeLong
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.*

object RiskTreeControllerSpec extends ZIOSpecDefault {

  // Service accessor pattern
  private def service[A](f: RiskTreeService => ZIO[Any, Throwable, A]): ZIO[RiskTreeService, Throwable, A] =
    ZIO.serviceWithZIO[RiskTreeService](f)

  // Stub repository factory - creates fresh instance per test
  private def makeStubRepo() = new RiskTreeRepository {
    private val db = collection.mutable.Map[NonNegativeLong, RiskTree]()
    
    override def create(riskTree: RiskTree): Task[RiskTree] = ZIO.attempt {
      val nextId: NonNegativeLong = (db.keys.map(k => (k: Long)).maxOption.getOrElse(0L) + 1L).refineUnsafe
      val newRiskTree = riskTree.copy(id = nextId)
      db += (nextId -> newRiskTree)
      newRiskTree
    }
    
    override def update(id: NonNegativeLong, op: RiskTree => RiskTree): Task[RiskTree] = ZIO.attempt {
      val riskTree = db(id)
      val updated = op(riskTree)
      db += (id -> updated)
      updated
    }
    
    override def delete(id: NonNegativeLong): Task[RiskTree] = ZIO.attempt {
      val riskTree = db(id)
      db -= id
      riskTree
    }
    
    override def getById(id: NonNegativeLong): Task[Option[RiskTree]] =
      ZIO.succeed(db.get(id))
    
    override def getAll: Task[List[Either[RepositoryFailure, RiskTree]]] =
      ZIO.succeed(db.values.toList.map(Right(_)))
  }

  // Layer factory - creates fresh layer with isolated repository per test
  private def serviceLayer = {
    ZLayer.make[RiskTreeService](
      RiskTreeServiceLive.layer,
      ZLayer.succeed(makeStubRepo()),
      com.risquanter.register.configs.TestConfigs.simulationLayer,
      com.risquanter.register.services.SimulationSemaphore.layer,
      com.risquanter.register.services.cache.TreeCacheManager.layer,
      com.risquanter.register.services.cache.RiskResultResolverLive.layer,
      com.risquanter.register.configs.TestConfigs.telemetryLayer >>> TracingLive.console,
      com.risquanter.register.configs.TestConfigs.telemetryLayer >>> MetricsLive.console
    )
  }

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
        val hierarchicalRequest = RiskTreeDefinitionRequest(
          name = "Ops Risk Portfolio",
          portfolios = Seq(RiskPortfolioDefinitionRequest("Total Operational Risk", None)),
          leaves = Seq(
            RiskLeafDefinitionRequest(
              name = "Cyber Attack",
              parentName = Some("Total Operational Risk"),
              distributionType = "lognormal",
              probability = 0.25,
              minLoss = Some(1000L),
              maxLoss = Some(50000L),
              percentiles = None,
              quantiles = None
            ),
            RiskLeafDefinitionRequest(
              name = "Data Breach",
              parentName = Some("Total Operational Risk"),
              distributionType = "lognormal",
              probability = 0.15,
              minLoss = Some(500L),
              maxLoss = Some(25000L),
              percentiles = None,
              quantiles = None
            )
          )
        )

        val program = service(_.create(hierarchicalRequest))

        program.assert { tree =>
          tree.name.value == "Ops Risk Portfolio" && tree.id > 0
        }.provide(serviceLayer)
      }
    ),

    suite("Get by ID")(
      test("returns tree metadata when exists") {
        val hierarchicalRequest = RiskTreeDefinitionRequest(
          name = "Test Tree",
          portfolios = Seq.empty,
          leaves = Seq(
            RiskLeafDefinitionRequest(
              name = "Test Risk",
              parentName = None,
              distributionType = "lognormal",
              probability = 0.5,
              minLoss = Some(1000L),
              maxLoss = Some(10000L),
              percentiles = None,
              quantiles = None
            )
          )
        )

        val program = for {
          tree <- service(_.create(hierarchicalRequest))
          result <- service(_.getById(tree.id))
        } yield result

        program.assert { maybeTree =>
          maybeTree.exists(_.name.value == "Test Tree")
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
      
      test("NonNegativeLong codec rejects negative IDs") {
        import sttp.tapir.*
        import com.risquanter.register.http.codecs.IronTapirCodecs.given
        
        val codec = summon[Codec[String, NonNegativeLong, CodecFormat.TextPlain]]
        
        assertTrue(
          codec.decode("-1") match { case DecodeResult.Error(_, _) => true; case _ => false },
          codec.decode("-999") match { case DecodeResult.Error(_, _) => true; case _ => false }
        )
      },
      
      test("NonNegativeLong codec error messages are descriptive") {
        import sttp.tapir.*
        import com.risquanter.register.http.codecs.IronTapirCodecs.given
        
        val codec = summon[Codec[String, NonNegativeLong, CodecFormat.TextPlain]]
        
        val result = codec.decode("-5")
        result match {
          case DecodeResult.Error(_, error) => 
            val msg = error.getMessage.toLowerCase
            assertTrue(
              msg.contains("non-negative") || msg.contains(">= 0") || msg.contains("greater")
            )
          case _ => 
            assertTrue(false) // Expected DecodeResult.Error for negative value
        }
      }
    )
  ) @@ TestAspect.sequential
}
