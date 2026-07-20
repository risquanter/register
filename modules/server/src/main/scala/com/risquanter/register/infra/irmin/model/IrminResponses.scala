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
  get_tree: Option[ListTreeGetTree]
)

final case class ListTreeGetTree(
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
  given JsonCodec[ListTreeGetTree] = DeriveJsonCodec.gen[ListTreeGetTree]
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
    parents: Option[List[String]] = None,
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
  * Response for `set_tree(path, tree, info)` mutation (DD-7).
  */
final case class SetTreeResponse(
    data: Option[SetTreeData],
    errors: Option[List[GraphQLError]]
)

final case class SetTreeData(
    set_tree: Option[CommitData]
)

object SetTreeResponse:
  import SetValueResponse.given  // Reuse CommitData and InfoData codecs
  given JsonCodec[SetTreeData] = DeriveJsonCodec.gen[SetTreeData]
  given JsonCodec[SetTreeResponse] = DeriveJsonCodec.gen[SetTreeResponse]

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

/**
  * Response for `merge_with_branch(from, branch, info)` mutation.
  */
final case class MergeBranchResponse(
    data: Option[MergeBranchData],
    errors: Option[List[GraphQLError]]
)

final case class MergeBranchData(
    merge_with_branch: Option[CommitData]
)

object MergeBranchResponse:
  import SetValueResponse.given
  given JsonCodec[MergeBranchData] = DeriveJsonCodec.gen[MergeBranchData]
  given JsonCodec[MergeBranchResponse] = DeriveJsonCodec.gen[MergeBranchResponse]

/**
  * Response for `revert(commit, branch)` mutation.
  */
final case class RevertResponse(
    data: Option[RevertData],
    errors: Option[List[GraphQLError]]
)

final case class RevertData(
    revert: Option[CommitData]
)

object RevertResponse:
  import SetValueResponse.given
  given JsonCodec[RevertData] = DeriveJsonCodec.gen[RevertData]
  given JsonCodec[RevertResponse] = DeriveJsonCodec.gen[RevertResponse]

/**
  * Response for `test_and_set_branch(branch, test, set)` mutation (Phase B,
  * A9 fact 2). The mutation itself returns a raw Boolean, not a Commit.
  */
final case class TestAndSetBranchResponse(
    data: Option[TestAndSetBranchData],
    errors: Option[List[GraphQLError]]
)

final case class TestAndSetBranchData(
    test_and_set_branch: Option[Boolean]
)

object TestAndSetBranchResponse:
  given JsonCodec[TestAndSetBranchData] = DeriveJsonCodec.gen[TestAndSetBranchData]
  given JsonCodec[TestAndSetBranchResponse] = DeriveJsonCodec.gen[TestAndSetBranchResponse]

/**
  * Response for `commit(hash)` query.
  */
final case class CommitQueryResponse(
    data: Option[CommitQueryData],
    errors: Option[List[GraphQLError]]
)

final case class CommitQueryData(
    commit: Option[CommitData]
)

object CommitQueryResponse:
  import SetValueResponse.given
  given JsonCodec[CommitQueryData] = DeriveJsonCodec.gen[CommitQueryData]
  given JsonCodec[CommitQueryResponse] = DeriveJsonCodec.gen[CommitQueryResponse]

/**
  * Response for `{ main { last_modified(path, n) } }` query.
  * Branch reads alias the branch as `main` (IrminQueries.branchSelector).
  */
final case class HistoryResponse(
    data: Option[HistoryData],
    errors: Option[List[GraphQLError]]
)

final case class HistoryData(
    main: Option[HistoryBranchData]
)

final case class HistoryBranchData(
    last_modified: List[CommitData]
)

object HistoryResponse:
  import SetValueResponse.given
  given JsonCodec[HistoryBranchData] = DeriveJsonCodec.gen[HistoryBranchData]
  given JsonCodec[HistoryData] = DeriveJsonCodec.gen[HistoryData]
  given JsonCodec[HistoryResponse] = DeriveJsonCodec.gen[HistoryResponse]

/**
  * Response for `{ main { lcas(commit) } }` query.
  * Branch reads alias the branch as `main` (IrminQueries.branchSelector).
  */
final case class LcaResponse(
    data: Option[LcaData],
    errors: Option[List[GraphQLError]]
)

final case class LcaData(
    main: Option[LcaBranchData]
)

final case class LcaBranchData(
    lcas: List[CommitData]
)

object LcaResponse:
  import SetValueResponse.given
  given JsonCodec[LcaBranchData] = DeriveJsonCodec.gen[LcaBranchData]
  given JsonCodec[LcaData] = DeriveJsonCodec.gen[LcaData]
  given JsonCodec[LcaResponse] = DeriveJsonCodec.gen[LcaResponse]

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
