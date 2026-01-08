package com.risquanter.register.services

import zio.*
import com.risquanter.register.configs.SimulationConfig

/**
 * Simulation concurrency control using ZIO Semaphore.
 * 
 * Provides backpressure for compute-intensive Monte Carlo simulations.
 * Limits concurrent simulation executions to prevent resource exhaustion.
 * 
 * Design:
 * - Trait-based for testability (can mock in tests)
 * - ZLayer lifecycle ensures single semaphore instance
 * - FIFO fairness for permit acquisition
 * - Bracket semantics guarantee permit release
 * 
 * Thread-safety: ZIO Semaphore is fully concurrent and non-blocking.
 */
trait SimulationSemaphore {
  
  /**
   * Execute effect while holding a simulation permit.
   * 
   * Semantics: acquire permit → run effect → release permit (guaranteed via bracket)
   * 
   * If no permits are available, the fiber suspends (non-blocking) until
   * a permit becomes available. Permits are released in FIFO order.
   * 
   * @tparam R Environment type
   * @tparam E Error type  
   * @tparam A Result type
   * @param effect Effect to run while holding permit
   * @return Effect that acquires permit, runs, then releases
   */
  def withPermit[R, E, A](effect: ZIO[R, E, A]): ZIO[R, E, A]
  
  /**
   * Current number of available permits.
   * 
   * Useful for monitoring and health checks.
   * Note: This is a point-in-time snapshot; value may change immediately after reading.
   */
  def available: UIO[Long]
}

object SimulationSemaphore {
  
  /**
   * Live implementation backed by ZIO Semaphore.
   * 
   * The underlying Semaphore is created during layer construction
   * and shared across all usages within the application.
   */
  private final class Live(semaphore: Semaphore) extends SimulationSemaphore {
    
    override def withPermit[R, E, A](effect: ZIO[R, E, A]): ZIO[R, E, A] =
      semaphore.withPermit(effect)
    
    override def available: UIO[Long] = 
      semaphore.available
  }
  
  /**
   * ZLayer that creates SimulationSemaphore from SimulationConfig.
   * 
   * Permit count is determined by `config.maxConcurrentSimulations`.
   * Semaphore is created once at layer construction time.
   */
  val layer: ZLayer[SimulationConfig, Nothing, SimulationSemaphore] =
    ZLayer {
      for {
        config    <- ZIO.service[SimulationConfig]
        semaphore <- Semaphore.make(config.maxConcurrentSimulations.toLong)
      } yield new Live(semaphore): SimulationSemaphore
    }
  
  /**
   * Test layer with configurable permits.
   * 
   * Useful for:
   * - Testing backpressure behavior with limited permits
   * - Testing concurrent access with controlled permit count
   * - Performance testing with different concurrency limits
   * 
   * @param permits Number of permits for the test semaphore
   */
  def test(permits: Long): ULayer[SimulationSemaphore] =
    ZLayer.fromZIO(Semaphore.make(permits).map(new Live(_)))
  
  // Accessor methods for ZIO service pattern
  
  /**
   * Execute effect while holding a simulation permit.
   * Accessor for use in ZIO for-comprehensions.
   */
  def withPermit[R, E, A](effect: ZIO[R, E, A]): ZIO[R & SimulationSemaphore, E, A] =
    ZIO.serviceWithZIO[SimulationSemaphore](_.withPermit(effect))
  
  /**
   * Get current available permits.
   * Accessor for use in ZIO for-comprehensions.
   */
  def available: URIO[SimulationSemaphore, Long] =
    ZIO.serviceWithZIO[SimulationSemaphore](_.available)
}
