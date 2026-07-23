package com.risquanter.register.infra.irmin

import zio.*
import com.risquanter.register.infra.irmin.model.*
import com.risquanter.register.domain.errors.IrminError
import com.risquanter.register.domain.data.iron.{BranchRef, CommitHash, PositiveInt}

/**
  * ZIO service interface for Irmin GraphQL client.
  *
  * Provides CRUD operations for key-value storage in Irmin's content-addressed tree,
  * plus branch operations (milestone 2b: scenarios ride on Irmin branches).
  * All operations return typed IrminError on the error channel (ADR-010).
  * Repository/service boundaries map IrminError to existing domain errors.
  *
  * Branch parameterization (DD-1): every CRUD op takes an optional `branch`;
  * `None` reads/writes `main` — fully backward compatible. Irmin creates a
  * branch implicitly on its first write (there is no create-branch mutation).
  *
  * Note: Subscriptions (watch) are deferred to Phase 5, Task 0 (IMPLEMENTATION-PLAN-PROPOSALS.md).
  * Requires WebSocket client dep decision; implemented with TreeUpdatePipeline as consumer.
  */
trait IrminClient:

  /**
    * Get a value at the specified path.
    *
    * @param path Path to the value (e.g., "risks/cyber")
    * @param branch Branch to read from (defaults to main)
    * @return Some(value) if exists, None if path not found
    */
  def get(path: IrminPath, branch: BranchRef = BranchRef.Main): IO[IrminError, Option[String]]

  /**
    * Set a value at the specified path.
    *
    * Creates a new commit with the change.
    *
    * @param path Path to store the value
    * @param value JSON string value to store
    * @param message Commit message describing the change
    * @param branch Branch to write to (defaults to main; first write creates the branch)
    * @return Commit metadata from the write operation
    */
  def set(path: IrminPath, value: String, message: String, branch: BranchRef = BranchRef.Main): IO[IrminError, IrminCommit]

  /**
    * Replace an entire subtree in ONE commit (DD-7).
    *
    * Subtree-replace semantics: keys under `path` absent from `entries` are
    * deleted; an empty `entries` removes the whole subtree cleanly. Entry
    * paths are relative to `path`.
    *
    * @param path Subtree root to replace
    * @param entries Full desired content of the subtree (relative paths)
    * @param message Commit message describing the change
    * @param branch Branch to write to (defaults to main; first write creates the branch)
    * @return Commit metadata from the write operation
    */
  def setTree(path: IrminPath, entries: List[IrminTreeEntry], message: String, branch: BranchRef = BranchRef.Main): IO[IrminError, IrminCommit]

  /**
    * Remove a value at the specified path.
    *
    * @param path Path to remove
    * @param message Commit message describing the removal
    * @param branch Branch to write to (defaults to main)
    * @return Commit metadata from the remove operation
    */
  def remove(path: IrminPath, message: String, branch: BranchRef = BranchRef.Main): IO[IrminError, IrminCommit]

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
    * Get info about a named branch including head commit.
    *
    * @return Branch info with head commit, or None if branch doesn't exist
    */
  def getBranch(branch: BranchRef): IO[IrminError, Option[IrminBranch]]

  /**
    * Merge a branch into another (Irmin `merge_with_branch`; Phase D
    * groundwork). Merge conflicts surface as `IrminGraphQLError`; the
    * scenario-merge service maps them to the domain `MergeConflict` (DD-10).
    *
    * @param from Source branch
    * @param into Target branch
    * @param message Commit message for the merge commit
    * @return The merge commit
    */
  def mergeBranch(from: BranchRef, into: BranchRef, message: String): IO[IrminError, IrminCommit]

  /**
    * Revert a branch to a previous commit (Phase E groundwork).
    *
    * @param commit Target commit hash
    * @param branch Branch to revert
    * @return The new head commit
    */
  def revert(commit: CommitHash, branch: BranchRef): IO[IrminError, IrminCommit]

  /**
    * Create a branch at a specific commit via CAS (Phase B, DD-5, A9 fact
    * 2/3). Unlike a bare first write — which starts the branch EMPTY, not as
    * a fork (A9 fact 3) — this makes the new branch's content `at`'s
    * content, a true fork.
    *
    * @param branch Branch to create
    * @param at Commit the new branch should point at (e.g. main's head)
    * @return Unit on success
    * @see BranchAlreadyExists — fails with this typed error if the branch
    *      already has a head; the CAS itself rejects name collisions.
    */
  def createBranchAt(branch: BranchRef, at: CommitHash): IO[IrminError, Unit]

  /**
    * Delete a branch via CAS (Phase B, DD-5, A9 fact 2). Only removes the
    * branch pointer — commits remain reachable by hash (A9 fact 5).
    *
    * @param branch Branch to delete
    * @param currentHead Expected current head
    * @return Unit on success
    * @see BranchHeadStale — fails with this typed error if `currentHead`
    *      doesn't match the branch's actual head (concurrent modification);
    *      never silently deletes the wrong state.
    */
  def deleteBranch(branch: BranchRef, currentHead: CommitHash): IO[IrminError, Unit]

  /**
    * Find a commit by hash.
    *
    * @return Commit metadata, or None if unknown
    */
  def getCommit(hash: CommitHash): IO[IrminError, Option[IrminCommit]]

  /**
    * Commit history touching a path (Irmin `last_modified`; Phase E groundwork).
    *
    * @param path Path whose history to read
    * @param n Maximum number of commits to return
    * @param branch Branch to read from (defaults to main)
    * @return Commits, most recent first
    */
  def getHistory(path: IrminPath, n: PositiveInt, branch: BranchRef = BranchRef.Main): IO[IrminError, List[IrminCommit]]

  /**
    * Lowest common ancestor(s) of a branch head and a commit — the merge
    * base (Phase D groundwork).
    *
    * @param branch Branch whose head is one side
    * @param commit The other side's commit hash
    */
  def lca(branch: BranchRef, commit: CommitHash): IO[IrminError, List[IrminCommit]]

  /**
    * Startup readiness probe: succeeds iff the Irmin service is reachable and
    * responding; fails with the underlying IrminError (carrying the real cause)
    * otherwise.
    *
    * @see ADR-031 — startup dependency readiness gate
    */
  def healthCheck: IO[IrminError, Unit]

  /**
    * List immediate child paths under the given prefix.
    *
    * @param prefix Path prefix to list (e.g., "risk-trees" or "risk-trees/1/nodes")
    * @param branch Branch to read from (defaults to main)
    * @return Child paths relative to the prefix
    */
  def list(prefix: IrminPath, branch: BranchRef = BranchRef.Main): IO[IrminError, List[IrminPath]]

object IrminClient:
  // Accessor methods for ZIO service pattern

  def get(path: IrminPath, branch: BranchRef = BranchRef.Main): ZIO[IrminClient, IrminError, Option[String]] =
    ZIO.serviceWithZIO[IrminClient](_.get(path, branch))

  def set(path: IrminPath, value: String, message: String, branch: BranchRef = BranchRef.Main): ZIO[IrminClient, IrminError, IrminCommit] =
    ZIO.serviceWithZIO[IrminClient](_.set(path, value, message, branch))

  def setTree(path: IrminPath, entries: List[IrminTreeEntry], message: String, branch: BranchRef = BranchRef.Main): ZIO[IrminClient, IrminError, IrminCommit] =
    ZIO.serviceWithZIO[IrminClient](_.setTree(path, entries, message, branch))

  def remove(path: IrminPath, message: String, branch: BranchRef = BranchRef.Main): ZIO[IrminClient, IrminError, IrminCommit] =
    ZIO.serviceWithZIO[IrminClient](_.remove(path, message, branch))

  def branches: ZIO[IrminClient, IrminError, List[String]] =
    ZIO.serviceWithZIO[IrminClient](_.branches)

  def mainBranch: ZIO[IrminClient, IrminError, Option[IrminBranch]] =
    ZIO.serviceWithZIO[IrminClient](_.mainBranch)

  def getBranch(branch: BranchRef): ZIO[IrminClient, IrminError, Option[IrminBranch]] =
    ZIO.serviceWithZIO[IrminClient](_.getBranch(branch))

  def mergeBranch(from: BranchRef, into: BranchRef, message: String): ZIO[IrminClient, IrminError, IrminCommit] =
    ZIO.serviceWithZIO[IrminClient](_.mergeBranch(from, into, message))

  def revert(commit: CommitHash, branch: BranchRef): ZIO[IrminClient, IrminError, IrminCommit] =
    ZIO.serviceWithZIO[IrminClient](_.revert(commit, branch))

  def createBranchAt(branch: BranchRef, at: CommitHash): ZIO[IrminClient, IrminError, Unit] =
    ZIO.serviceWithZIO[IrminClient](_.createBranchAt(branch, at))

  def deleteBranch(branch: BranchRef, currentHead: CommitHash): ZIO[IrminClient, IrminError, Unit] =
    ZIO.serviceWithZIO[IrminClient](_.deleteBranch(branch, currentHead))

  def getCommit(hash: CommitHash): ZIO[IrminClient, IrminError, Option[IrminCommit]] =
    ZIO.serviceWithZIO[IrminClient](_.getCommit(hash))

  def getHistory(path: IrminPath, n: PositiveInt, branch: BranchRef = BranchRef.Main): ZIO[IrminClient, IrminError, List[IrminCommit]] =
    ZIO.serviceWithZIO[IrminClient](_.getHistory(path, n, branch))

  def lca(branch: BranchRef, commit: CommitHash): ZIO[IrminClient, IrminError, List[IrminCommit]] =
    ZIO.serviceWithZIO[IrminClient](_.lca(branch, commit))

  def healthCheck: ZIO[IrminClient, IrminError, Unit] =
    ZIO.serviceWithZIO[IrminClient](_.healthCheck)

  def list(prefix: IrminPath, branch: BranchRef = BranchRef.Main): ZIO[IrminClient, IrminError, List[IrminPath]] =
    ZIO.serviceWithZIO[IrminClient](_.list(prefix, branch))
