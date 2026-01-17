package com.risquanter.register.infra.irmin

import com.risquanter.register.infra.irmin.model.IrminPath

/**
  * GraphQL query and mutation strings for Irmin operations.
  *
  * These are raw GraphQL strings that will be sent via HTTP POST.
  * Variables are interpolated directly (Irmin's GraphQL doesn't require parameterized queries
  * for simple string values, and this keeps the implementation simple).
  */
object IrminQueries:

  /**
    * Query to get a value at a path from the main branch.
    *
    * Returns null if path doesn't exist.
    *
    * @param path Path to query (e.g., "risks/cyber")
    */
  def getValue(path: IrminPath): String =
    s"""
    |{
    |  main {
    |    tree {
    |      get(path: "${path.value}")
    |    }
    |  }
    |}
    """.stripMargin.trim

  /**
    * Query to get a value from a specific branch.
    *
    * @param branch Branch name
    * @param path Path to query
    */
  def getValueFromBranch(branch: String, path: IrminPath): String =
    s"""
    |{
    |  branch(name: "$branch") {
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
    */
  def listTree(path: IrminPath): String =
    val pathPart = if path.value.isEmpty then "" else s"""(path: "${path.value}")"""
    s"""
    |{
    |  main {
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
    */
  def setValue(path: IrminPath, value: String, message: String, author: String): String =
    // Escape special characters in value for GraphQL string
    val escapedValue = escapeGraphQLString(value)
    val escapedMessage = escapeGraphQLString(message)
    val escapedAuthor = escapeGraphQLString(author)
    s"""
    |mutation {
    |  set(
    |    path: "${path.value}",
    |    value: "$escapedValue",
    |    info: {
    |      message: "$escapedMessage",
    |      author: "$escapedAuthor"
    |    }
    |  ) {
    |    hash
    |    key
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
    */
  def removeValue(path: IrminPath, message: String, author: String): String =
    val escapedMessage = escapeGraphQLString(message)
    val escapedAuthor = escapeGraphQLString(author)
    s"""
    |mutation {
    |  remove(
    |    path: "${path.value}",
    |    info: {
    |      message: "$escapedMessage",
    |      author: "$escapedAuthor"
    |    }
    |  ) {
    |    hash
    |    key
    |    info {
    |      date
    |      author
    |      message
    |    }
    |  }
    |}
    """.stripMargin.trim

  /**
    * Query to get the main branch info including head commit.
    */
  val getMainBranch: String =
    """
    |{
    |  main {
    |    name
    |    head {
    |      hash
    |      key
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
