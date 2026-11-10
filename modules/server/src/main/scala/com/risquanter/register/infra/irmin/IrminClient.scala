package com.risquanter.register.infra.irmin

import zio.*
import com.risquanter.register.infra.irmin.model.*
import com.risquanter.register.domain.errors.Failure
import com.risquanter.register.domain.data.iron.{BranchRef, CommitHash, MergeStagingRef, PositiveInt, StoreBranch}

/**
  * ZIO service interface for Irmin GraphQL client.
  *
  * Provides reads and writes of Irmin contents at paths in Irmin's
  * content-addressed tree, plus branch operations; a scenario is an Irmin
  * branch. Every operation fails with a `Failure` (ADR-010): `Unanswered` at
  * the store when Irmin did not answer, `Rejected` at the store when its answer
  * holds content this service refuses. A refused compare-and-set and a refused
  * merge are answers rather than failures: they return `IrminWriteOutcome` and
  * `IrminMergeOutcome`, and the caller decides what each means for its own
  * request.
  *
  * Every read and write of tree data takes a `BranchRef`, which defaults to
  * main, except one overload of `set` that takes a merge's `MergeStagingRef`.
  * Irmin creates a branch implicitly on its first write.
  *
  * Every write carries a `CommitMessage`, rendered where the GraphQL document
  * is written, and a listing returns `IrminSegment`s. Stored contents travel as
  * `String` in both directions: their parse is the domain decoder that reads
  * them in the same step, and the merge compares them as bytes (ADR-032), so
  * no representation between the bytes and the decoded value is kept.
  *
  * Irmin's subscriptions (watch) are not used.
  */
trait IrminClient:

  /**
    * Get a value at the specified path. The value is the stored bytes as
    * text: its parse into a domain value is the decoder the caller runs on it
    * in the same step, so no representation between the two is kept here.
    *
    * @param path Path to the value (e.g., "risks/cyber")
    * @param branch Branch to read from (defaults to main)
    * @return Some(value) if exists, None if path not found
    */
  def get(path: IrminPath, branch: BranchRef = BranchRef.Main): IO[Failure, Option[String]]

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
  def set(path: IrminPath, value: String, message: CommitMessage, branch: BranchRef = BranchRef.Main): IO[Failure, IrminCommit]

  /** Set a value on a merge's staging branch. The one write that accepts the
    * staging type: every other write takes `BranchRef`, so tree data cannot be
    * written to a staging branch through them (ADR-036 §2). */
  def set(path: IrminPath, value: String, message: CommitMessage, staging: MergeStagingRef): IO[Failure, IrminCommit]

  /**
    * Replace an entire subtree in ONE commit.
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
  def setTree(path: IrminPath, entries: List[IrminTreeEntry], message: CommitMessage, branch: BranchRef = BranchRef.Main): IO[Failure, IrminCommit]

  /**
    * Remove a value at the specified path.
    *
    * @param path Path to remove
    * @param message Commit message describing the removal
    * @param branch Branch to write to (defaults to main)
    * @return Commit metadata from the remove operation
    */
  def remove(path: IrminPath, message: CommitMessage, branch: BranchRef = BranchRef.Main): IO[Failure, IrminCommit]

  /**
    * List all branches in the store. Every name is refined where it arrives
    * to the kind of branch this service names (ADR-039): main, a scenario
    * branch or a merge's staging branch. A name that is none of those is
    * content the store returned and this service refuses.
    *
    * @return The branches the store holds
    */
  def branches: IO[Failure, List[StoreBranch]]

  /**
    * Get info about the main branch including head commit.
    *
    * @return Branch info with head commit, or None if branch doesn't exist
    */
  def mainBranch: IO[Failure, Option[IrminBranch]]

  /**
    * Get info about a named branch including head commit.
    *
    * @return Branch info with head commit, or None if branch doesn't exist
    */
  def getBranch(branch: StoreBranch): IO[Failure, Option[IrminBranch]]

  /** Merge one commit into a branch (Irmin `merge_with_commit`).
    *
    * Fast-forwards when `into`'s head is the common ancestor of the two;
    * otherwise writes a merge commit whose parents are both heads. Requires the
    * patched Irmin image: a conflicting merge returns `Conflicted` and leaves
    * `into`'s head untouched.
    *
    * The patch (`local/irmin-prod:3.11-p2`, containers/builders/patches/)
    * prefixes the resolver's conflict error with "merge conflict: ", which is
    * what tells a conflict apart from the mutation's other GraphQL errors.
    *
    * @return `Merged` with the new commit, or `Conflicted` with Irmin's reason
    *         when the commits conflict and no commit was created
    */
  def mergeCommit(from: CommitHash, into: StoreBranch, message: CommitMessage): IO[Failure, IrminMergeOutcome]

  /**
    * Revert a branch to a previous commit.
    *
    * @param commit Target commit hash
    * @param branch Branch to revert
    * @return The new head commit
    */
  def revert(commit: CommitHash, branch: BranchRef): IO[Failure, IrminCommit]

  /**
    * Create a branch at a specific commit via compare-and-set. Unlike a bare
    * first write, which starts the branch empty rather than as a fork, this
    * makes the new branch's content `at`'s content, a true fork.
    *
    * @param branch Branch to create
    * @param at Commit the new branch should point at (e.g. main's head)
    * @return `Applied`, or `Refused` with the existing head when the branch
    *         already has one; the compare-and-set itself rejects name
    *         collisions.
    */
  def createBranchAt(branch: StoreBranch, at: CommitHash): IO[Failure, IrminWriteOutcome]

  /**
    * Delete a branch via compare-and-set. Only removes the branch pointer:
    * commits remain reachable by hash.
    *
    * @param branch Branch to delete
    * @param currentHead Expected current head
    * @return `Applied`, or `Refused` with the actual head when it is not
    *         `currentHead` (concurrent modification); the wrong state is never
    *         deleted.
    */
  def deleteBranch(branch: StoreBranch, currentHead: CommitHash): IO[Failure, IrminWriteOutcome]

  /**
    * Move a branch's pointer to `to`, but only while its head is still
    * `expectedHead` (Irmin `test_and_set_branch` with both sides present).
    *
    * The compare-and-set is what makes the move safe in either direction: a
    * write that landed on the branch since `expectedHead` was observed makes
    * the move fail rather than discard that write. Commits left off the branch
    * stay reachable by hash.
    *
    * @param branch Branch whose pointer to move
    * @param expectedHead The head the caller last observed
    * @param to Commit the branch should point at
    * @return `Applied`, or `Refused` with the actual head when it is no longer
    *         `expectedHead`; nothing was moved and nothing was discarded.
    */
  def moveBranchTo(branch: BranchRef, expectedHead: CommitHash, to: CommitHash): IO[Failure, IrminWriteOutcome]

  /**
    * Find a commit by hash.
    *
    * @return Commit metadata, or None if unknown
    */
  def getCommit(hash: CommitHash): IO[Failure, Option[IrminCommit]]

  /**
    * Get a value at a path as of a specific commit — the store's state at
    * that commit, independent of any branch head. Used to read merge-base
    * (LCA) values for the merge-conflict pre-check (ADR-032: storage-level
    * byte equality against the merge base).
    *
    * @param commit Commit whose tree to read
    * @param path Path to the value
    * @return Some(value) if the path exists at that commit; None if the path
    *         is absent there or the commit is unknown
    */
  def getAtCommit(commit: CommitHash, path: IrminPath): IO[Failure, Option[String]]

  /**
    * List immediate child paths under a prefix as of a specific commit — the
    * commit-pinned counterpart of [[list]]. Used by the read-consistency path
    * (resolve the branch head once, then read every constituent path pinned to
    * that commit) so a commit landing mid-load cannot produce a torn read.
    *
    * @param commit Commit whose tree to list
    * @param path Path prefix to list
    * @return The child names one level below the prefix; empty if the prefix
    *         is absent at that commit or the commit is unknown (oracle constancy)
    */
  def listAtCommit(commit: CommitHash, path: IrminPath): IO[Failure, List[IrminSegment]]

  /**
    * Commit history touching a path (Irmin `last_modified`).
    *
    * @param path Path whose history to read
    * @param n Maximum number of commits to return
    * @param branch Branch to read from (defaults to main)
    * @return Commits, most recent first
    */
  def getHistory(path: IrminPath, n: PositiveInt, branch: BranchRef = BranchRef.Main): IO[Failure, List[IrminCommit]]

  /**
    * Lowest common ancestor(s) of a branch head and a commit — the merge
    * base.
    *
    * @param branch Branch whose head is one side
    * @param commit The other side's commit hash
    */
  def lca(branch: BranchRef, commit: CommitHash): IO[Failure, List[IrminCommit]]

  /**
    * Startup readiness probe: succeeds iff the Irmin service is reachable and
    * responding. Otherwise it fails with an `Unanswered` that carries the real
    * cause.
    *
    * @see ADR-031 — startup dependency readiness gate
    */
  def healthCheck: IO[Failure, Unit]

  /**
    * List immediate child paths under the given prefix.
    *
    * @param prefix Path prefix to list (e.g., "risk-trees" or "risk-trees/1/nodes")
    * @param branch Branch to read from (defaults to main)
    * @return The child names one level below the prefix
    */
  def list(prefix: IrminPath, branch: BranchRef = BranchRef.Main): IO[Failure, List[IrminSegment]]

object IrminClient:
  // Accessor methods for ZIO service pattern

  def get(path: IrminPath, branch: BranchRef = BranchRef.Main): ZIO[IrminClient, Failure, Option[String]] =
    ZIO.serviceWithZIO[IrminClient](_.get(path, branch))

  def set(path: IrminPath, value: String, message: CommitMessage, branch: BranchRef = BranchRef.Main): ZIO[IrminClient, Failure, IrminCommit] =
    ZIO.serviceWithZIO[IrminClient](_.set(path, value, message, branch))

  def setTree(path: IrminPath, entries: List[IrminTreeEntry], message: CommitMessage, branch: BranchRef = BranchRef.Main): ZIO[IrminClient, Failure, IrminCommit] =
    ZIO.serviceWithZIO[IrminClient](_.setTree(path, entries, message, branch))

  def remove(path: IrminPath, message: CommitMessage, branch: BranchRef = BranchRef.Main): ZIO[IrminClient, Failure, IrminCommit] =
    ZIO.serviceWithZIO[IrminClient](_.remove(path, message, branch))

  def branches: ZIO[IrminClient, Failure, List[StoreBranch]] =
    ZIO.serviceWithZIO[IrminClient](_.branches)

  def mainBranch: ZIO[IrminClient, Failure, Option[IrminBranch]] =
    ZIO.serviceWithZIO[IrminClient](_.mainBranch)

  def getBranch(branch: StoreBranch): ZIO[IrminClient, Failure, Option[IrminBranch]] =
    ZIO.serviceWithZIO[IrminClient](_.getBranch(branch))

  def mergeCommit(from: CommitHash, into: StoreBranch, message: CommitMessage): ZIO[IrminClient, Failure, IrminMergeOutcome] =
    ZIO.serviceWithZIO[IrminClient](_.mergeCommit(from, into, message))

  def revert(commit: CommitHash, branch: BranchRef): ZIO[IrminClient, Failure, IrminCommit] =
    ZIO.serviceWithZIO[IrminClient](_.revert(commit, branch))

  def createBranchAt(branch: StoreBranch, at: CommitHash): ZIO[IrminClient, Failure, IrminWriteOutcome] =
    ZIO.serviceWithZIO[IrminClient](_.createBranchAt(branch, at))

  def deleteBranch(branch: StoreBranch, currentHead: CommitHash): ZIO[IrminClient, Failure, IrminWriteOutcome] =
    ZIO.serviceWithZIO[IrminClient](_.deleteBranch(branch, currentHead))

  def moveBranchTo(branch: BranchRef, expectedHead: CommitHash, to: CommitHash): ZIO[IrminClient, Failure, IrminWriteOutcome] =
    ZIO.serviceWithZIO[IrminClient](_.moveBranchTo(branch, expectedHead, to))

  def getCommit(hash: CommitHash): ZIO[IrminClient, Failure, Option[IrminCommit]] =
    ZIO.serviceWithZIO[IrminClient](_.getCommit(hash))

  def getAtCommit(commit: CommitHash, path: IrminPath): ZIO[IrminClient, Failure, Option[String]] =
    ZIO.serviceWithZIO[IrminClient](_.getAtCommit(commit, path))

  def listAtCommit(commit: CommitHash, path: IrminPath): ZIO[IrminClient, Failure, List[IrminSegment]] =
    ZIO.serviceWithZIO[IrminClient](_.listAtCommit(commit, path))

  def getHistory(path: IrminPath, n: PositiveInt, branch: BranchRef = BranchRef.Main): ZIO[IrminClient, Failure, List[IrminCommit]] =
    ZIO.serviceWithZIO[IrminClient](_.getHistory(path, n, branch))

  def lca(branch: BranchRef, commit: CommitHash): ZIO[IrminClient, Failure, List[IrminCommit]] =
    ZIO.serviceWithZIO[IrminClient](_.lca(branch, commit))

  def healthCheck: ZIO[IrminClient, Failure, Unit] =
    ZIO.serviceWithZIO[IrminClient](_.healthCheck)

  def list(prefix: IrminPath, branch: BranchRef = BranchRef.Main): ZIO[IrminClient, Failure, List[IrminSegment]] =
    ZIO.serviceWithZIO[IrminClient](_.list(prefix, branch))
