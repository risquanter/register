
package com.risquanter.register.services.sse

import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.stream.*

import com.risquanter.register.http.sse.SSEEvent
import com.risquanter.register.domain.data.iron.TreeId
import com.risquanter.register.testutil.TestHelpers.*

object SSEHubSpec extends ZIOSpecDefault {

  // Helper to create TreeId for tests
  private def tid(label: String): TreeId = treeId(label)

  /** 
   * Wait for subscriber count to reach expected value.
   * Uses polling with exponential backoff to avoid flaky tests.
   */
  private def awaitSubscribers(hub: SSEHub, tree: TreeId, expected: Int): ZIO[Any, Nothing, Unit] =
    hub.subscriberCount(tree).repeatUntil(_ >= expected).unit

  def spec = suite("SSEHubSpec")(
    test("subscribe returns stream") {
      for
        hub    <- ZIO.service[SSEHub]
        stream <- hub.subscribe(tid("tree-1"))
      yield assertTrue(stream != null)
    },

    test("subscriberCount is zero until stream is consumed (lazy subscription semantics)") {
      // Documents the lazy subscription model: count reflects ACTIVE consumers,
      // not just callers of subscribe(). This ensures subscriberCount accurately
      // predicts how many consumers will receive a published event.
      for
        hub         <- ZIO.service[SSEHub]
        countBefore <- hub.subscriberCount(tid("tree-500"))
        stream      <- hub.subscribe(tid("tree-500"))
        countAfter  <- hub.subscriberCount(tid("tree-500"))  // Still 0 - stream not consumed yet
        queue       <- Queue.unbounded[SSEEvent]
        fiber       <- stream.foreach(queue.offer).fork
        _           <- awaitSubscribers(hub, tid("tree-500"), 1)  // Now count is 1
        countActive <- hub.subscriberCount(tid("tree-500"))
        _           <- fiber.interrupt
      yield assertTrue(
        countBefore == 0,
        countAfter == 0,   // Key assertion: subscribe() alone doesn't increment count
        countActive == 1   // Only incremented when stream is being consumed
      )
    } @@ TestAspect.withLiveClock,

    test("publish with no subscribers returns 0") {
      for
        hub   <- ZIO.service[SSEHub]
        event  = SSEEvent.ConnectionStatus("test", None)
        count <- hub.publish(tid("tree-999"), event)
      yield assertTrue(count == 0)
    },

    test("subscriber receives published event") {
      for
        hub    <- ZIO.service[SSEHub]
        stream <- hub.subscribe(tid("tree-1"))
        event   = SSEEvent.LECUpdated("node-1", tid("tree-1"), Map("p50" -> 1000.0))
        // Use Queue to collect results
        queue  <- Queue.unbounded[SSEEvent]
        // Start consuming in background
        fiber  <- stream.foreach(queue.offer).fork
        // Wait for subscriber to be registered (avoids flaky timing)
        _      <- awaitSubscribers(hub, tid("tree-1"), 1)
        _      <- hub.publish(tid("tree-1"), event)
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
        stream1  <- hub.subscribe(tid("tree-2"))
        stream2  <- hub.subscribe(tid("tree-2"))
        event     = SSEEvent.NodeChanged("node-2", tid("tree-2"), "updated")
        queue1   <- Queue.unbounded[SSEEvent]
        queue2   <- Queue.unbounded[SSEEvent]
        fiber1   <- stream1.foreach(queue1.offer).fork
        fiber2   <- stream2.foreach(queue2.offer).fork
        _        <- awaitSubscribers(hub, tid("tree-2"), 2)
        _        <- hub.publish(tid("tree-2"), event)
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
        count0 <- hub.subscriberCount(tid("tree-3"))
        stream <- hub.subscribe(tid("tree-3"))
        queue  <- Queue.unbounded[SSEEvent]
        fiber  <- stream.foreach(queue.offer).fork
        _      <- awaitSubscribers(hub, tid("tree-3"), 1)
        count1 <- hub.subscriberCount(tid("tree-3"))
        _      <- fiber.interrupt
      yield assertTrue(
        count0 == 0,
        count1 >= 1
      )
    } @@ TestAspect.withLiveClock,

    test("events for different trees are isolated") {
      for
        hub      <- ZIO.service[SSEHub]
        stream1  <- hub.subscribe(tid("tree-10"))
        stream2  <- hub.subscribe(tid("tree-20"))
        event1    = SSEEvent.LECUpdated("node-10", tid("tree-10"), Map.empty)
        event2    = SSEEvent.LECUpdated("node-20", tid("tree-20"), Map.empty)
        queue1   <- Queue.unbounded[SSEEvent]
        queue2   <- Queue.unbounded[SSEEvent]
        fiber1   <- stream1.foreach(queue1.offer).fork
        fiber2   <- stream2.foreach(queue2.offer).fork
        _        <- awaitSubscribers(hub, tid("tree-10"), 1) *> awaitSubscribers(hub, tid("tree-20"), 1)
        _        <- hub.publish(tid("tree-10"), event1)
        _        <- hub.publish(tid("tree-20"), event2)
        result1  <- queue1.take.timeout(5.seconds)
        result2  <- queue2.take.timeout(5.seconds)
        _        <- fiber1.interrupt *> fiber2.interrupt
      yield assertTrue(
        result1.get.asInstanceOf[SSEEvent.LECUpdated].treeId == tid("tree-10"),
        result2.get.asInstanceOf[SSEEvent.LECUpdated].treeId == tid("tree-20")
      )
    } @@ TestAspect.withLiveClock,

    test("broadcastAll reaches all trees") {
      for
        hub      <- ZIO.service[SSEHub]
        stream1  <- hub.subscribe(tid("tree-100"))
        stream2  <- hub.subscribe(tid("tree-200"))
        event     = SSEEvent.ConnectionStatus("broadcast", Some("system message"))
        queue1   <- Queue.unbounded[SSEEvent]
        queue2   <- Queue.unbounded[SSEEvent]
        fiber1   <- stream1.foreach(queue1.offer).fork
        fiber2   <- stream2.foreach(queue2.offer).fork
        _        <- awaitSubscribers(hub, tid("tree-100"), 1) *> awaitSubscribers(hub, tid("tree-200"), 1)
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