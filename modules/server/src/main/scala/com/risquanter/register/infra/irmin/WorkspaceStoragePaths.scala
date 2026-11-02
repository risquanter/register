package com.risquanter.register.infra.irmin

import com.risquanter.register.domain.data.iron.{MitigationId, NodeId, TreeId, WorkspaceId}
import com.risquanter.register.infra.irmin.model.IrminPath

/** Single owner of the workspace storage-path layout in Irmin (ADR-004a):
  *
  *   - Tree meta:        workspaces/{wsId}/risk-trees/{treeId}/meta
  *   - Tree nodes:       workspaces/{wsId}/risk-trees/{treeId}/nodes/{nodeId}
  *   - Tree mitigations: workspaces/{wsId}/risk-trees/{treeId}/mitigations/{mitigationId}
  *
  * Every server-side construction of an absolute workspace storage path goes
  * through this object; the layout can change in exactly one place.
  */
private[register] object WorkspaceStoragePaths:

  /** Where one of a tree's Irmin contents is stored. */
  enum TreePath:
    case Meta(treeId: TreeId)
    case Node(treeId: TreeId, nodeId: NodeId)
    case Mitigation(treeId: TreeId, mitigationId: MitigationId)

    def treeId: TreeId

  def workspaceRoot(wsId: WorkspaceId): String =
    s"workspaces/${wsId.value}"

  def treesRoot(wsId: WorkspaceId): String =
    s"${workspaceRoot(wsId)}/risk-trees"

  def treeRoot(wsId: WorkspaceId, treeId: TreeId): String =
    s"${treesRoot(wsId)}/${treeId.value}"

  def treeMeta(wsId: WorkspaceId, treeId: TreeId): String =
    s"${treeRoot(wsId, treeId)}/meta"

  def treeNodes(wsId: WorkspaceId, treeId: TreeId): String =
    s"${treeRoot(wsId, treeId)}/nodes"

  def treeMitigations(wsId: WorkspaceId, treeId: TreeId): String =
    s"${treeRoot(wsId, treeId)}/mitigations"

  /** The absolute path of `path`, built by the same functions every other
    * reader and writer uses.
    *
    * {{{
    * of(ws, TreePath.Node(t, n)) // workspaces/{ws}/risk-trees/{t}/nodes/{n}
    * }}}
    */
  def of(wsId: WorkspaceId, path: TreePath): IrminPath =
    IrminPath.unsafeFrom(path match
      case TreePath.Meta(treeId)                     => treeMeta(wsId, treeId)
      case TreePath.Node(treeId, nodeId)             => s"${treeNodes(wsId, treeId)}/${nodeId.value}"
      case TreePath.Mitigation(treeId, mitigationId) => s"${treeMitigations(wsId, treeId)}/${mitigationId.value}"
    )
