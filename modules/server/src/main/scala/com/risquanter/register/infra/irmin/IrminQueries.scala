package com.risquanter.register.infra.irmin

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
    * so response decoding is branch-agnostic.
    */
  private def branchSelector(branch: Option[String]): String =
    branch.fold("main")(b => s"""main: branch(name: "$b")""")

  /** Branch argument fragment for mutations (empty for main). */
  private def branchArg(branch: Option[String]): String =
    branch.fold("")(b => s"""branch: "$b", """)

  /**
    * Query to get a value at a path.
    *
    * Returns null if path doesn't exist.
    *
    * @param path Path to query (e.g., "risks/cyber")
    * @param branch Branch to read from (None = main)
    */
  def getValue(path: IrminPath, branch: Option[String] = None): String =
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
    * @param branch Branch to read from (None = main)
    */
  def listTree(path: IrminPath, branch: Option[String] = None): String =
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
    * @param branch Branch to write to (None = main; Irmin creates the branch on first write)
    */
  def setValue(path: IrminPath, value: String, message: String, author: String, branch: Option[String] = None): String =
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
    * @param branch Branch to write to (None = main; Irmin creates the branch on first write)
    */
  def setTree(path: IrminPath, entries: List[IrminTreeEntry], message: String, author: String, branch: Option[String] = None): String =
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
    * @param branch Branch to write to (None = main)
    */
  def removeValue(path: IrminPath, message: String, author: String, branch: Option[String] = None): String =
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
    * @param branch Branch to inspect (None = main). Branch queries alias the
    *               result as `main` so the response shape is branch-agnostic.
    */
  def getBranchInfo(branch: Option[String] = None): String =
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
  val getMainBranch: String = getBranchInfo(None)

  /**
    * Mutation to merge one branch into another (Phase D groundwork).
    *
    * @param from Source branch name
    * @param into Target branch (None = main)
    */
  def mergeWithBranch(from: String, into: Option[String], message: String, author: String): String =
    val escapedMessage = escapeGraphQLString(message)
    val escapedAuthor = escapeGraphQLString(author)
    s"""
    |mutation {
    |  merge_with_branch(
    |    ${branchArg(into)}from: "$from",
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
    * @param commitHash Target commit (40-hex Irmin hash)
    * @param branch Branch to revert (None = main)
    */
  def revert(commitHash: String, branch: Option[String]): String =
    s"""
    |mutation {
    |  revert(
    |    ${branchArg(branch)}commit: "$commitHash"
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
    * Query to find a commit by hash.
    */
  def getCommit(commitHash: String): String =
    s"""
    |{
    |  commit(hash: "$commitHash") {
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
    * @param branch Branch to read from (None = main)
    */
  def getHistory(path: IrminPath, n: Int, branch: Option[String] = None): String =
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
    * @param branch Branch whose head is one side (None = main)
    * @param commitHash The other side's commit hash
    */
  def lca(branch: Option[String], commitHash: String): String =
    s"""
    |{
    |  ${branchSelector(branch)} {
    |    lcas(commit: "$commitHash") {
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
