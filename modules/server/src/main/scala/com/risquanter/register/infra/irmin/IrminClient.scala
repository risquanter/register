package com.risquanter.register.infra.irmin

import zio.*
import com.risquanter.register.infra.irmin.model.*
import com.risquanter.register.domain.errors.IrminError

/**
  * ZIO service interface for Irmin GraphQL client.
  *
  * Provides CRUD operations for key-value storage in Irmin's content-addressed tree.
  * All operations return typed IrminError on the error channel (ADR-010).
  * Repository/service boundaries map IrminError to existing domain errors.
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
  def get(path: IrminPath): IO[IrminError, Option[String]]

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
  def set(path: IrminPath, value: String, message: String): IO[IrminError, IrminCommit]

  /**
    * Remove a value at the specified path.
    *
    * @param path Path to remove
    * @param message Commit message describing the removal
    * @return Commit metadata from the remove operation
    */
  def remove(path: IrminPath, message: String): IO[IrminError, IrminCommit]

  /**
    * List all branches in the store.
    *
    * @return List of branch names
    */
  def branches: IO[IrminError, List[String]]

  /**
    * Get info about the main branch including head commit.
    *
    * @return Branch info with head commit, or None if branch doesn't exist
    */
  def mainBranch: IO[IrminError, Option[IrminBranch]]

  /**
    * Check if the Irmin service is reachable.
    *
    * Useful for health checks.
    *
    * @return true if service responds, false otherwise
    */
  def healthCheck: IO[IrminError, Boolean]

  /**
    * List immediate child paths under the given prefix.
    *
    * @param prefix Path prefix to list (e.g., "risk-trees" or "risk-trees/1/nodes")
    * @return Child paths relative to the prefix
    */
  def list(prefix: IrminPath): IO[IrminError, List[IrminPath]]

object IrminClient:
  // Accessor methods for ZIO service pattern

  def get(path: IrminPath): ZIO[IrminClient, IrminError, Option[String]] =
    ZIO.serviceWithZIO[IrminClient](_.get(path))

  def set(path: IrminPath, value: String, message: String): ZIO[IrminClient, IrminError, IrminCommit] =
    ZIO.serviceWithZIO[IrminClient](_.set(path, value, message))

  def remove(path: IrminPath, message: String): ZIO[IrminClient, IrminError, IrminCommit] =
    ZIO.serviceWithZIO[IrminClient](_.remove(path, message))

  def branches: ZIO[IrminClient, IrminError, List[String]] =
    ZIO.serviceWithZIO[IrminClient](_.branches)

  def mainBranch: ZIO[IrminClient, IrminError, Option[IrminBranch]] =
    ZIO.serviceWithZIO[IrminClient](_.mainBranch)

  def healthCheck: ZIO[IrminClient, IrminError, Boolean] =
    ZIO.serviceWithZIO[IrminClient](_.healthCheck)

  def list(prefix: IrminPath): ZIO[IrminClient, IrminError, List[IrminPath]] =
    ZIO.serviceWithZIO[IrminClient](_.list(prefix))
