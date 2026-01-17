package com.risquanter.register.infra.irmin.model

import _root_.zio.json.*

/**
  * Irmin commit information - metadata for each write operation.
  *
  * Corresponds to the GraphQL `Info` type in Irmin's schema:
  * ```graphql
  * type Info {
  *   date: String!
  *   author: String!
  *   message: String!
  * }
  * ```
  *
  * @param date ISO 8601 timestamp of the commit
  * @param author Author identifier (e.g., "zio-client", "user@example.com")
  * @param message Commit message describing the change
  */
final case class IrminInfo(
    date: String,
    author: String,
    message: String
)

object IrminInfo:
  given JsonCodec[IrminInfo] = DeriveJsonCodec.gen[IrminInfo]

/**
  * Input for commit info when making mutations.
  *
  * Corresponds to the GraphQL `InfoInput` type:
  * ```graphql
  * input InfoInput {
  *   parents: [CommitKey!]
  *   allow_empty: Boolean
  *   retries: Int
  *   message: String
  *   author: String
  * }
  * ```
  *
  * @param message Commit message (optional, defaults to "zio-client")
  * @param author Author identifier (optional, defaults to "zio-client")
  */
final case class IrminInfoInput(
    message: Option[String] = None,
    author: Option[String] = None
)

object IrminInfoInput:
  given JsonCodec[IrminInfoInput] = DeriveJsonCodec.gen[IrminInfoInput]
  
  /** Default info for automated operations */
  val default: IrminInfoInput = IrminInfoInput(
    message = Some("zio-client operation"),
    author = Some("zio-client")
  )
  
  /** Create info with custom message */
  def withMessage(msg: String): IrminInfoInput = IrminInfoInput(
    message = Some(msg),
    author = Some("zio-client")
  )

/**
  * Irmin commit - represents a point-in-time snapshot.
  *
  * Corresponds to the GraphQL `Commit` type:
  * ```graphql
  * type Commit {
  *   tree: Tree!
  *   parents: [CommitKey!]!
  *   info: Info!
  *   hash: Hash!
  *   key: CommitKey!
  * }
  * ```
  *
  * @param hash Content-addressed hash of the commit
  * @param key Commit key (may differ from hash in packed format)
  * @param parents Parent commit keys (empty for initial commit)
  * @param info Commit metadata
  */
final case class IrminCommit(
    hash: String,
    key: String,
    parents: List[String],
    info: IrminInfo
)

object IrminCommit:
  given JsonCodec[IrminCommit] = DeriveJsonCodec.gen[IrminCommit]

/**
  * Irmin branch - named reference to a commit.
  *
  * Corresponds to the GraphQL `Branch` type:
  * ```graphql
  * type Branch {
  *   name: BranchName!
  *   head: Commit
  *   tree: Tree!
  * }
  * ```
  *
  * @param name Branch name (e.g., "main", "feature/x")
  * @param head Current head commit (None if branch is empty)
  */
final case class IrminBranch(
    name: String,
    head: Option[IrminCommit]
)

object IrminBranch:
  given JsonCodec[IrminBranch] = DeriveJsonCodec.gen[IrminBranch]

/**
  * Irmin contents - a value stored at a path.
  *
  * Corresponds to the GraphQL `Contents` type:
  * ```graphql
  * type Contents {
  *   path: Path!
  *   metadata: Metadata!
  *   value: Value!
  *   hash: Hash!
  * }
  * ```
  *
  * @param path Path to the content
  * @param value Stored value (JSON string)
  * @param hash Content hash
  */
final case class IrminContents(
    path: String,
    value: String,
    hash: String
)

object IrminContents:
  given JsonCodec[IrminContents] = DeriveJsonCodec.gen[IrminContents]
