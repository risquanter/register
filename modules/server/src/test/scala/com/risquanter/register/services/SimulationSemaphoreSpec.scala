package com.risquanter.register.services

import zio.*
import zio.test.*
import zio.test.Assertion.*
import com.risquanter.register.configs.{SimulationConfig, TestConfigs}

object SimulationSemaphoreSpec extends ZIOSpecDefault {

  def spec = suite("SimulationSemaphore")(
    
    suite("withPermit")(
      
      test("executes effect and returns result") {
        for {
          result <- SimulationSemaphore.withPermit(ZIO.succeed(42))
        } yield assertTrue(result == 42)
      }.provide(SimulationSemaphore.test(4)),
      
      test("limits concurrent executions to permit count") {
        for {
          // Track max concurrent executions
          currentRef    <- Ref.make(0)
          maxRef        <- Ref.make(0)
          latch         <- Promise.make[Nothing, Unit]
          
          // Define effect that tracks concurrency
          trackedEffect = for {
            current <- currentRef.updateAndGet(_ + 1)
            _       <- maxRef.update(max => math.max(max, current))
            _       <- latch.await  // Wait until test releases
            _       <- currentRef.update(_ - 1)
          } yield ()
          
          // Run 10 effects with only 2 permits (will block at 2)
          fibers <- ZIO.foreach(1 to 10)(_ => 
            SimulationSemaphore.withPermit(trackedEffect).fork
          )
          
          // Give fibers time to acquire permits and increment counter
          _ <- ZIO.yieldNow.repeatN(100)
          
          maxConcurrent <- maxRef.get
          
          // Release latch to complete effects
          _ <- latch.succeed(())
          _ <- ZIO.foreach(fibers)(_.join)
          
        } yield assertTrue(maxConcurrent <= 2)
      }.provide(SimulationSemaphore.test(2)),
      
      test("releases permit on success") {
        for {
          _         <- SimulationSemaphore.withPermit(ZIO.unit)
          available <- SimulationSemaphore.available
        } yield assertTrue(available == 4L)
      }.provide(SimulationSemaphore.test(4)),
      
      test("releases permit on failure") {
        for {
          result    <- SimulationSemaphore.withPermit(
                         ZIO.fail(new RuntimeException("boom"))
                       ).either
          available <- SimulationSemaphore.available
        } yield assertTrue(
          result.isLeft,
          available == 4L  // Permit released despite failure
        )
      }.provide(SimulationSemaphore.test(4)),
      
      test("releases permit on interruption") {
        for {
          started   <- Promise.make[Nothing, Unit]
          fiber     <- SimulationSemaphore.withPermit(
                         started.succeed(()) *> ZIO.never  // Signal start, then block
                       ).fork
          _         <- started.await  // Wait for permit to be acquired
          _         <- fiber.interrupt
          available <- SimulationSemaphore.available
        } yield assertTrue(available == 4L)  // Permit released after interrupt
      }.provide(SimulationSemaphore.test(4))
    ),
    
    suite("available")(
      
      test("returns initial permit count when no permits held") {
        for {
          available <- SimulationSemaphore.available
        } yield assertTrue(available == 4L)
      }.provide(SimulationSemaphore.test(4)),
      
      test("decrements when permit acquired") {
        for {
          latch       <- Promise.make[Nothing, Unit]
          readyLatch  <- Promise.make[Nothing, Unit]
          duringRef   <- Ref.make(0L)
          
          // Start effect that holds permit and signals when ready
          fiber       <- SimulationSemaphore.withPermit(
                           SimulationSemaphore.available.flatMap(duringRef.set) *>
                           readyLatch.succeed(()) *>
                           latch.await
                         ).fork
          
          // Wait until fiber has acquired permit and recorded value
          _ <- readyLatch.await
          
          during      <- duringRef.get
          
          // Release and wait
          _ <- latch.succeed(())
          _ <- fiber.join
          after <- SimulationSemaphore.available
          
        } yield assertTrue(
          during == 1L,  // One permit consumed while holding
          after == 2L    // Both permits available after release
        )
      }.provide(SimulationSemaphore.test(2))
    ),
    
    suite("layer construction")(
      
      test("creates semaphore from SimulationConfig") {
        for {
          available <- SimulationSemaphore.available
        } yield assertTrue(available == 4L)  // TestConfigs.simulation.maxConcurrentSimulations
      }.provide(
        SimulationSemaphore.layer,
        TestConfigs.simulationLayer
      )
    )
  ) @@ TestAspect.timeout(10.seconds)
}
