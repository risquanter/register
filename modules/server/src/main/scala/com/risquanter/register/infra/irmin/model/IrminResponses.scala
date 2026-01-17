package com.risquanter.register.infra.irmin.model

import _root_.zio.json.*

/**
  * GraphQL response wrapper types for Irmin queries.
  *
  * These match the structure returned by Irmin's GraphQL endpoint.
  */

// ============================================================================
// Query Response Types
// ============================================================================

/**
  * Response for `main { tree { get(path) } }` query.
  */
final case class GetValueResponse(
    data: Option[GetValueData],
    errors: Option[List[GraphQLError]]
)

final case class GetValueData(
    main: Option[MainBranchData]
)

final case class MainBranchData(
    tree: TreeData
)

final case class TreeData(
    get: Option[String]
)

object GetValueResponse:
  given JsonCodec[TreeData] = DeriveJsonCodec.gen[TreeData]
  given JsonCodec[MainBranchData] = DeriveJsonCodec.gen[MainBranchData]
  given JsonCodec[GetValueData] = DeriveJsonCodec.gen[GetValueData]
  given JsonCodec[GetValueResponse] = DeriveJsonCodec.gen[GetValueResponse]

/**
  * Response for `main { tree { list } }` query.
  */
final case class ListTreeResponse(
    data: Option[ListTreeData],
    errors: Option[List[GraphQLError]]
)

final case class ListTreeData(
    main: Option[ListTreeMainData]
)

final case class ListTreeMainData(
    tree: ListTreeTreeData
)

final case class ListTreeTreeData(
    list: List[TreeNode]
)

/**
  * Node in tree listing - can be Contents or Tree.
  * We use a simplified representation since GraphQL unions are complex.
  */
final case class TreeNode(
    path: String,
    hash: Option[String],
    value: Option[String]  // Present only for Contents
)

object ListTreeResponse:
  given JsonCodec[TreeNode] = DeriveJsonCodec.gen[TreeNode]
  given JsonCodec[ListTreeTreeData] = DeriveJsonCodec.gen[ListTreeTreeData]
  given JsonCodec[ListTreeMainData] = DeriveJsonCodec.gen[ListTreeMainData]
  given JsonCodec[ListTreeData] = DeriveJsonCodec.gen[ListTreeData]
  given JsonCodec[ListTreeResponse] = DeriveJsonCodec.gen[ListTreeResponse]

/**
  * Response for `branches` query.
  */
final case class BranchesResponse(
    data: Option[BranchesData],
    errors: Option[List[GraphQLError]]
)

final case class BranchesData(
    branches: List[BranchInfo]
)

final case class BranchInfo(
    name: String
)

object BranchesResponse:
  given JsonCodec[BranchInfo] = DeriveJsonCodec.gen[BranchInfo]
  given JsonCodec[BranchesData] = DeriveJsonCodec.gen[BranchesData]
  given JsonCodec[BranchesResponse] = DeriveJsonCodec.gen[BranchesResponse]

// ============================================================================
// Mutation Response Types
// ============================================================================

/**
  * Response for `set(path, value, info)` mutation.
  */
final case class SetValueResponse(
    data: Option[SetValueData],
    errors: Option[List[GraphQLError]]
)

final case class SetValueData(
    set: Option[CommitData]
)

final case class CommitData(
    hash: String,
    key: String,
    info: InfoData
)

final case class InfoData(
    date: String,
    author: String,
    message: String
)

object SetValueResponse:
  given JsonCodec[InfoData] = DeriveJsonCodec.gen[InfoData]
  given JsonCodec[CommitData] = DeriveJsonCodec.gen[CommitData]
  given JsonCodec[SetValueData] = DeriveJsonCodec.gen[SetValueData]
  given JsonCodec[SetValueResponse] = DeriveJsonCodec.gen[SetValueResponse]

/**
  * Response for `remove(path, info)` mutation.
  */
final case class RemoveValueResponse(
    data: Option[RemoveValueData],
    errors: Option[List[GraphQLError]]
)

final case class RemoveValueData(
    remove: Option[CommitData]
)

object RemoveValueResponse:
  import SetValueResponse.given  // Reuse CommitData and InfoData codecs
  given JsonCodec[RemoveValueData] = DeriveJsonCodec.gen[RemoveValueData]
  given JsonCodec[RemoveValueResponse] = DeriveJsonCodec.gen[RemoveValueResponse]

// ============================================================================
// Common Types
// ============================================================================

/**
  * GraphQL error from Irmin.
  */
final case class GraphQLError(
    message: String,
    locations: Option[List[ErrorLocation]],
    path: Option[List[String]]
)

final case class ErrorLocation(
    line: Int,
    column: Int
)

object GraphQLError:
  given JsonCodec[ErrorLocation] = DeriveJsonCodec.gen[ErrorLocation]
  given JsonCodec[GraphQLError] = DeriveJsonCodec.gen[GraphQLError]

/**
  * GraphQL request body.
  */
final case class GraphQLRequest(
    query: String,
    variables: Option[Map[String, String]] = None
)

object GraphQLRequest:
  given JsonCodec[GraphQLRequest] = DeriveJsonCodec.gen[GraphQLRequest]
