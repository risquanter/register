package com.risquanter.register.telemetry

import zio.*
import zio.test.*
import zio.test.Assertion.*

object RequestContextSpec extends ZIOSpecDefault {

  def spec = suite("RequestContext")(
    
    test("generate creates context with requestId") {
      for {
        ctx <- RequestContext.generate
      } yield assertTrue(
        ctx.requestId.nonEmpty,
        ctx.traceId.isEmpty,
        ctx.spanId.isEmpty,
        ctx.userId.isEmpty
      )
    },
    
    test("set and get context") {
      for {
        ctx <- RequestContext.generate
        _   <- RequestContext.set(ctx)
        retrieved <- RequestContext.get
      } yield assertTrue(
        retrieved.contains(ctx)
      )
    },
    
    test("withContext propagates to child fibers") {
      val testCtx = RequestContext(requestId = "test-123")
      
      for {
        result <- RequestContext.withContext(testCtx) {
          for {
            ctx1 <- RequestContext.get
            // Spawn child fiber
            ctx2 <- ZIO.succeed(42).fork.flatMap(_.join).zipRight(RequestContext.get)
          } yield (ctx1, ctx2)
        }
        (parentCtx, childCtx) = result
      } yield assertTrue(
        parentCtx.contains(testCtx),
        childCtx.contains(testCtx)
      )
    },
    
    test("withGenerated creates and propagates context") {
      RequestContext.withGenerated {
        for {
          ctx <- RequestContext.get
        } yield assertTrue(
          ctx.isDefined,
          ctx.get.requestId.nonEmpty
        )
      }
    },
    
    test("context isolation between concurrent effects") {
      val ctx1 = RequestContext(requestId = "request-1")
      val ctx2 = RequestContext(requestId = "request-2")
      
      for {
        results <- ZIO.collectAllPar(List(
          RequestContext.withContext(ctx1)(RequestContext.get),
          RequestContext.withContext(ctx2)(RequestContext.get)
        ))
      } yield assertTrue(
        results(0).contains(ctx1),
        results(1).contains(ctx2),
        results(0) != results(1)
      )
    },
    
    test("context cleanup after scope") {
      val testCtx = RequestContext(requestId = "scoped-123")
      
      for {
        // Set context in scope
        _ <- RequestContext.withContext(testCtx)(ZIO.unit)
        // Context should be cleared after scope
        afterScope <- RequestContext.get
      } yield assertTrue(
        afterScope.isEmpty
      )
    }
  ).provide(RequestContext.layer)  // All tests require RequestContextRef
}
