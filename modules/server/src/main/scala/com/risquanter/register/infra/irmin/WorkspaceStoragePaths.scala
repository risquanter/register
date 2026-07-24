package com.risquanter.register.infra.irmin

import com.risquanter.register.domain.data.iron.{TreeId, WorkspaceId}

/** Single owner of the workspace storage-path layout in Irmin (ADR-004a):
  *
  *   - Tree meta:  workspaces/{wsId}/risk-trees/{treeId}/meta
  *   - Tree nodes: workspaces/{wsId}/risk-trees/{treeId}/nodes/{nodeId}
  *
  * Every server-side construction of an absolute workspace storage path goes
  * through this object; the layout can change in exactly one place.
  */
private[register] object WorkspaceStoragePaths:

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
