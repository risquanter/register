package com.risquanter.register.infra.irmin

import com.risquanter.register.domain.data.iron.{BranchRef, CommitHash, PositiveInt}
import com.risquanter.register.infra.irmin.model.{IrminPath, IrminTreeEntry}

/**
  * GraphQL query and mutation strings for Irmin operations.
  *
  * These are raw GraphQL strings that will be sent via HTTP POST.
  * Variables are interpolated directly (Irmin's GraphQL doesn't require parameterized queries
  * for simple string values, and this keeps the implementation simple).
  *
  * Branch parameterization (milestone 2b, DD-1): read queries select the
  * branch via a GraphQL alias — `main: branch(name: "x")` — so branch reads
  * return byte-identical response shapes to main reads and every response
  * model works unchanged. Mutations pass Irmin's optional `branch` argument.
  */
object IrminQueries:

  /** Selector for read queries: `main`, or the named branch aliased AS `main`
    * so response decoding is branch-agnostic. The definite `BranchRef` maps
    * onto Irmin's optional wire argument here — the only place.
    */
  private def branchSelector(branch: BranchRef): String =
    if branch == BranchRef.Main then "main"
    else s"""main: branch(name: "${branch.toBranchRef}")"""

  /** Branch argument fragment for mutations (empty for main). */
  private def branchArg(branch: BranchRef): String =
    if branch == BranchRef.Main then ""
    else s"""branch: "${branch.toBranchRef}", """

  /**
    * Query to get a value at a path.
    *
    * Returns null if path doesn't exist.
    *
    * @param path Path to query (e.g., "risks/cyber")
    * @param branch Branch to read from (Main = Irmin's default branch)
    */
  def getValue(path: IrminPath, branch: BranchRef = BranchRef.Main): String =
    s"""
    |{
    |  ${branchSelector(branch)} {
    |    tree {
    |      get(path: "${path.value}")
    |    }
    |  }
    |}
    """.stripMargin.trim

  /**
    * Query to list all branches.
    */
  val listBranches: String =
    """
    |{
    |  branches {
    |    name
    |  }
    |}
    """.stripMargin.trim

  /**
    * Query to list contents at a path in the tree.
    *
    * Returns all direct children of the specified path.
    * Uses fragment to handle union type (Contents | Tree).
    *
    * @param path Path to list (empty string for root)
    * @param branch Branch to read from (Main = Irmin's default branch)
    */
  def listTree(path: IrminPath, branch: BranchRef = BranchRef.Main): String =
    val pathPart = if path.value.isEmpty then "" else s"""(path: "${path.value}")"""
    s"""
    |{
    |  ${branchSelector(branch)} {
    |    tree {
    |      get_tree$pathPart {
    |        list {
    |          ... on Contents {
    |            path
    |            hash
    |            value
    |          }
    |          ... on Tree {
    |            path
    |            hash
    |          }
    |        }
    |      }
    |    }
    |  }
    |}
    """.stripMargin.trim

  /**
    * Mutation to set a value at a path.
    *
    * @param path Path to set
    * @param value JSON value to store
    * @param message Commit message
    * @param author Commit author
    * @param branch Branch to write to (Main = Irmin's default; Irmin creates a named branch on first write)
    */
  def setValue(path: IrminPath, value: String, message: String, author: String, branch: BranchRef = BranchRef.Main): String =
    // Escape special characters in value for GraphQL string
    val escapedValue = escapeGraphQLString(value)
    val escapedMessage = escapeGraphQLString(message)
    val escapedAuthor = escapeGraphQLString(author)
    s"""
    |mutation {
    |  set(
    |    ${branchArg(branch)}path: "${path.value}",
    |    value: "$escapedValue",
    |    info: {
    |      message: "$escapedMessage",
    |      author: "$escapedAuthor"
    |    }
    |  ) {
    |    hash
    |    key
    |    parents
    |    info {
    |      date
    |      author
    |      message
    |    }
    |  }
    |}
    """.stripMargin.trim

  /**
    * Mutation to replace an entire subtree in one commit (DD-7).
    *
    * Irmin's `set_tree` has subtree-replace semantics: keys under `path` that
    * are absent from `tree` are deleted; an empty `tree` removes the whole
    * subtree with no empty directory left behind. Entry paths are relative
    * to `path`.
    *
    * @param path Subtree root to replace
    * @param entries Full desired content of the subtree (relative paths)
    * @param message Commit message
    * @param author Commit author
    * @param branch Branch to write to (Main = Irmin's default; Irmin creates a named branch on first write)
    */
  def setTree(path: IrminPath, entries: List[IrminTreeEntry], message: String, author: String, branch: BranchRef = BranchRef.Main): String =
    val escapedMessage = escapeGraphQLString(message)
    val escapedAuthor = escapeGraphQLString(author)
    val items = entries
      .map(e => s"""{path: "${e.path.value}", value: "${escapeGraphQLString(e.value)}"}""")
      .mkString(", ")
    s"""
    |mutation {
    |  set_tree(
    |    ${branchArg(branch)}path: "${path.value}",
    |    tree: [$items],
    |    info: {
    |      message: "$escapedMessage",
    |      author: "$escapedAuthor"
    |    }
    |  ) {
    |    hash
    |    key
    |    parents
    |    info {
    |      date
    |      author
    |      message
    |    }
    |  }
    |}
    """.stripMargin.trim

  /**
    * Mutation to remove a value at a path.
    *
    * @param path Path to remove
    * @param message Commit message
    * @param author Commit author
    * @param branch Branch to write to (Main = Irmin's default branch)
    */
  def removeValue(path: IrminPath, message: String, author: String, branch: BranchRef = BranchRef.Main): String =
    val escapedMessage = escapeGraphQLString(message)
    val escapedAuthor = escapeGraphQLString(author)
    s"""
    |mutation {
    |  remove(
    |    ${branchArg(branch)}path: "${path.value}",
    |    info: {
    |      message: "$escapedMessage",
    |      author: "$escapedAuthor"
    |    }
    |  ) {
    |    hash
    |    key
    |    parents
    |    info {
    |      date
    |      author
    |      message
    |    }
    |  }
    |}
    """.stripMargin.trim

  /**
    * Query to get branch info including head commit.
    *
    * @param branch Branch to inspect (Main = Irmin's default). Branch queries alias the
    *               result as `main` so the response shape is branch-agnostic.
    */
  def getBranchInfo(branch: BranchRef = BranchRef.Main): String =
    s"""
    |{
    |  ${branchSelector(branch)} {
    |    name
    |    head {
    |      hash
    |      key
    |      parents
    |      info {
    |        date
    |        author
    |        message
    |      }
    |    }
    |  }
    |}
    """.stripMargin.trim

  /**
    * Query to get the main branch info including head commit.
    */
  val getMainBranch: String = getBranchInfo(BranchRef.Main)

  /**
    * Mutation to merge one branch into another (Phase D groundwork).
    *
    * @param from Source branch
    * @param into Target branch (Main = Irmin's default branch)
    */
  def mergeWithBranch(from: BranchRef, into: BranchRef, message: String, author: String): String =
    val escapedMessage = escapeGraphQLString(message)
    val escapedAuthor = escapeGraphQLString(author)
    s"""
    |mutation {
    |  merge_with_branch(
    |    ${branchArg(into)}from: "${from.toBranchRef}",
    |    info: {
    |      message: "$escapedMessage",
    |      author: "$escapedAuthor"
    |    }
    |  ) {
    |    hash
    |    key
    |    parents
    |    info {
    |      date
    |      author
    |      message
    |    }
    |  }
    |}
    """.stripMargin.trim

  /**
    * Mutation to revert a branch to a previous commit (Phase E groundwork).
    *
    * @param commitHash Target commit
    * @param branch Branch to revert (Main = Irmin's default branch)
    */
  def revert(commitHash: CommitHash, branch: BranchRef): String =
    s"""
    |mutation {
    |  revert(
    |    ${branchArg(branch)}commit: "${commitHash.value}"
    |  ) {
    |    hash
    |    key
    |    parents
    |    info {
    |      date
    |      author
    |      message
    |    }
    |  }
    |}
    """.stripMargin.trim

  /**
    * CAS mutation on a branch head (Phase B: DD-5 scenario create/delete;
    * A9 fact 2). `test` is the expected current head (`None` = branch must
    * not currently exist); `set` is the desired new head (`None` = delete
    * the branch). Returns a raw Boolean: `true` on success, `false` if the
    * branch's actual head didn't match `test` — a stale/collision result,
    * not a GraphQL error.
    *
    * @param branch Branch to create/delete
    * @param test Expected current head (None = branch must not exist)
    * @param set Desired new head (None = delete the branch)
    */
  def testAndSetBranch(branch: BranchRef, test: Option[CommitHash], set: Option[CommitHash]): String =
    val testArg = test.fold("test: null")(h => s"""test: "${h.value}"""")
    val setArg = set.fold("set: null")(h => s"""set: "${h.value}"""")
    s"""
    |mutation {
    |  test_and_set_branch(branch: "${branch.toBranchRef}", $testArg, $setArg)
    |}
    """.stripMargin.trim

  /**
    * Query for a value at a path as of a specific commit (Irmin
    * `commit(hash:).tree`). Reads the store's state at that commit — used to
    * read merge-base values for the merge-conflict pre-check (ADR-032:
    * storage-level byte equality against the LCA).
    *
    * @param commitHash Commit whose tree to read
    * @param path Path to query
    */
  def getValueAtCommit(commitHash: CommitHash, path: IrminPath): String =
    s"""
    |{
    |  commit(hash: "${commitHash.value}") {
    |    tree {
    |      get(path: "${path.value}")
    |    }
    |  }
    |}
    """.stripMargin.trim

  /**
    * Query to find a commit by hash.
    */
  def getCommit(commitHash: CommitHash): String =
    s"""
    |{
    |  commit(hash: "${commitHash.value}") {
    |    hash
    |    key
    |    parents
    |    info {
    |      date
    |      author
    |      message
    |    }
    |  }
    |}
    """.stripMargin.trim

  /**
    * Query for the commit history touching a path (Irmin `last_modified`).
    *
    * @param path Path whose history to read
    * @param n Max commits to return
    * @param branch Branch to read from (Main = Irmin's default branch)
    */
  def getHistory(path: IrminPath, n: PositiveInt, branch: BranchRef = BranchRef.Main): String =
    s"""
    |{
    |  ${branchSelector(branch)} {
    |    last_modified(path: "${path.value}", n: $n) {
    |      hash
    |      key
    |      parents
    |      info {
    |        date
    |        author
    |        message
    |      }
    |    }
    |  }
    |}
    """.stripMargin.trim

  /**
    * Query for the lowest common ancestors of a branch head and a commit
    * (merge-base; Phase D groundwork).
    *
    * @param branch Branch whose head is one side (Main = Irmin's default branch)
    * @param commitHash The other side's commit hash
    */
  def lca(branch: BranchRef, commitHash: CommitHash): String =
    s"""
    |{
    |  ${branchSelector(branch)} {
    |    lcas(commit: "${commitHash.value}") {
    |      hash
    |      key
    |      parents
    |      info {
    |        date
    |        author
    |        message
    |      }
    |    }
    |  }
    |}
    """.stripMargin.trim

  /**
    * Escape special characters for GraphQL string literals.
    */
  private def escapeGraphQLString(s: String): String =
    s.replace("\\", "\\\\")
     .replace("\"", "\\\"")
     .replace("\n", "\\n")
     .replace("\r", "\\r")
     .replace("\t", "\\t")
