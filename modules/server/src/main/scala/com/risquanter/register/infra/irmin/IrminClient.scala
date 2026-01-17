package com.risquanter.register.infra.irmin

import zio.*
import com.risquanter.register.infra.irmin.model.*

/**
  * ZIO service interface for Irmin GraphQL client.
  *
  * Provides CRUD operations for key-value storage in Irmin's content-addressed tree.
  * All operations return domain errors wrapped in SimulationError hierarchy.
  *
  * Error mapping:
  * - Connection refused / timeout → IrminUnavailable
  * - Request timeout → NetworkTimeout
  * - GraphQL errors → appropriate SimulationError subtype
  *
  * Note: Subscriptions (watch) are not implemented in this phase.
  * They will be added in Phase 4/5 when building the cache invalidation pipeline.
  */
trait IrminClient:

  /**
    * Get a value at the specified path from the main branch.
    *
    * @param path Path to the value (e.g., "risks/cyber")
    * @return Some(value) if exists, None if path not found
    */
  def get(path: IrminPath): Task[Option[String]]

  /**
    * Set a value at the specified path.
    *
    * Creates a new commit with the change.
    *
    * @param path Path to store the value
    * @param value JSON string value to store
    * @param message Commit message describing the change
    * @return Commit metadata from the write operation
    */
  def set(path: IrminPath, value: String, message: String): Task[IrminCommit]

  /**
    * Remove a value at the specified path.
    *
    * @param path Path to remove
    * @param message Commit message describing the removal
    * @return Commit metadata from the remove operation
    */
  def remove(path: IrminPath, message: String): Task[IrminCommit]

  /**
    * List all branches in the store.
    *
    * @return List of branch names
    */
  def branches: Task[List[String]]

  /**
    * Get info about the main branch including head commit.
    *
    * @return Branch info with head commit, or None if branch doesn't exist
    */
  def mainBranch: Task[Option[IrminBranch]]

  /**
    * Check if the Irmin service is reachable.
    *
    * Useful for health checks.
    *
    * @return true if service responds, false otherwise
    */
  def healthCheck: Task[Boolean]

object IrminClient:
  // Accessor methods for ZIO service pattern

  def get(path: IrminPath): ZIO[IrminClient, Throwable, Option[String]] =
    ZIO.serviceWithZIO[IrminClient](_.get(path))

  def set(path: IrminPath, value: String, message: String): ZIO[IrminClient, Throwable, IrminCommit] =
    ZIO.serviceWithZIO[IrminClient](_.set(path, value, message))

  def remove(path: IrminPath, message: String): ZIO[IrminClient, Throwable, IrminCommit] =
    ZIO.serviceWithZIO[IrminClient](_.remove(path, message))

  def branches: ZIO[IrminClient, Throwable, List[String]] =
    ZIO.serviceWithZIO[IrminClient](_.branches)

  def mainBranch: ZIO[IrminClient, Throwable, Option[IrminBranch]] =
    ZIO.serviceWithZIO[IrminClient](_.mainBranch)

  def healthCheck: ZIO[IrminClient, Throwable, Boolean] =
    ZIO.serviceWithZIO[IrminClient](_.healthCheck)
