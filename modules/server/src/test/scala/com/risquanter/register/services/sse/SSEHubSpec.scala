package com.risquanter.register.services.sse

import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.stream.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.*

import com.risquanter.register.http.sse.SSEEvent
import com.risquanter.register.domain.data.iron.NonNegativeLong

object SSEHubSpec extends ZIOSpecDefault {

  // Helper to create NonNegativeLong for tests
  private def treeId(n: Long): NonNegativeLong = n.refineUnsafe[GreaterEqual[0L]]

  def spec = suite("SSEHubSpec")(
    test("subscribe returns stream") {
      for
        hub    <- ZIO.service[SSEHub]
        stream <- hub.subscribe(treeId(1L))
      yield assertTrue(stream != null)
    },

    test("publish with no subscribers returns 0") {
      for
        hub   <- ZIO.service[SSEHub]
        event  = SSEEvent.ConnectionStatus("test", None)
        count <- hub.publish(treeId(999L), event)
      yield assertTrue(count == 0)
    },

    test("subscriber receives published event") {
      for
        hub    <- ZIO.service[SSEHub]
        stream <- hub.subscribe(treeId(1L))
        event   = SSEEvent.LECUpdated("node-1", 1L, Map("p50" -> 1000.0))
        // Use Queue to collect results
        queue  <- Queue.unbounded[SSEEvent]
        // Start consuming in background
        fiber  <- stream.foreach(queue.offer).fork
        // Small delay with live clock to let fiber start
        _      <- Live.live(ZIO.sleep(100.millis))
        _      <- hub.publish(treeId(1L), event)
        result <- queue.take.timeout(5.seconds)
        _      <- fiber.interrupt
      yield assertTrue(
        result.isDefined,
        result.get.isInstanceOf[SSEEvent.LECUpdated]
      )
    } @@ TestAspect.withLiveClock,

    test("multiple subscribers receive same event") {
      for
        hub      <- ZIO.service[SSEHub]
        stream1  <- hub.subscribe(treeId(2L))
        stream2  <- hub.subscribe(treeId(2L))
        event     = SSEEvent.NodeChanged("node-2", 2L, "updated")
        queue1   <- Queue.unbounded[SSEEvent]
        queue2   <- Queue.unbounded[SSEEvent]
        fiber1   <- stream1.foreach(queue1.offer).fork
        fiber2   <- stream2.foreach(queue2.offer).fork
        _        <- Live.live(ZIO.sleep(100.millis))
        _        <- hub.publish(treeId(2L), event)
        result1  <- queue1.take.timeout(5.seconds)
        result2  <- queue2.take.timeout(5.seconds)
        _        <- fiber1.interrupt *> fiber2.interrupt
      yield assertTrue(
        result1.isDefined,
        result2.isDefined,
        result1 == result2
      )
    } @@ TestAspect.withLiveClock,

    test("subscriberCount reflects active subscribers") {
      for
        hub    <- ZIO.service[SSEHub]
        count0 <- hub.subscriberCount(treeId(3L))
        stream <- hub.subscribe(treeId(3L))
        queue  <- Queue.unbounded[SSEEvent]
        fiber  <- stream.foreach(queue.offer).fork
        _      <- Live.live(ZIO.sleep(100.millis))
        count1 <- hub.subscriberCount(treeId(3L))
        _      <- fiber.interrupt
      yield assertTrue(
        count0 == 0,
        count1 >= 1
      )
    } @@ TestAspect.withLiveClock,

    test("events for different trees are isolated") {
      for
        hub      <- ZIO.service[SSEHub]
        stream1  <- hub.subscribe(treeId(10L))
        stream2  <- hub.subscribe(treeId(20L))
        event1    = SSEEvent.LECUpdated("node-10", 10L, Map.empty)
        event2    = SSEEvent.LECUpdated("node-20", 20L, Map.empty)
        queue1   <- Queue.unbounded[SSEEvent]
        queue2   <- Queue.unbounded[SSEEvent]
        fiber1   <- stream1.foreach(queue1.offer).fork
        fiber2   <- stream2.foreach(queue2.offer).fork
        _        <- Live.live(ZIO.sleep(100.millis))
        _        <- hub.publish(treeId(10L), event1)
        _        <- hub.publish(treeId(20L), event2)
        result1  <- queue1.take.timeout(5.seconds)
        result2  <- queue2.take.timeout(5.seconds)
        _        <- fiber1.interrupt *> fiber2.interrupt
      yield assertTrue(
        result1.get.asInstanceOf[SSEEvent.LECUpdated].treeId == 10L,
        result2.get.asInstanceOf[SSEEvent.LECUpdated].treeId == 20L
      )
    } @@ TestAspect.withLiveClock,

    test("broadcastAll reaches all trees") {
      for
        hub      <- ZIO.service[SSEHub]
        stream1  <- hub.subscribe(treeId(100L))
        stream2  <- hub.subscribe(treeId(200L))
        event     = SSEEvent.ConnectionStatus("broadcast", Some("system message"))
        queue1   <- Queue.unbounded[SSEEvent]
        queue2   <- Queue.unbounded[SSEEvent]
        fiber1   <- stream1.foreach(queue1.offer).fork
        fiber2   <- stream2.foreach(queue2.offer).fork
        _        <- Live.live(ZIO.sleep(100.millis))
        total    <- hub.broadcastAll(event)
        result1  <- queue1.take.timeout(5.seconds)
        result2  <- queue2.take.timeout(5.seconds)
        _        <- fiber1.interrupt *> fiber2.interrupt
      yield assertTrue(
        total >= 2,
        result1.isDefined,
        result2.isDefined
      )
    } @@ TestAspect.withLiveClock
  ).provide(SSEHub.live) @@ TestAspect.sequential @@ TestAspect.timeout(30.seconds)
}