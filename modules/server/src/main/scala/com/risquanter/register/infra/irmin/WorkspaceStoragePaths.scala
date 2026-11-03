package com.risquanter.register.infra.irmin

import io.github.iltotore.iron.*
import com.risquanter.register.domain.data.iron.{MitigationId, NodeId, TreeId, WorkspaceId}
import com.risquanter.register.infra.irmin.model.{IrminPath, IrminSegment}

/** Single owner of the workspace storage-path layout in Irmin (ADR-004a):
  *
  *   - Tree meta:        workspaces/{wsId}/risk-trees/{treeId}/meta
  *   - Tree nodes:       workspaces/{wsId}/risk-trees/{treeId}/nodes/{nodeId}
  *   - Tree mitigations: workspaces/{wsId}/risk-trees/{treeId}/mitigations/{mitigationId}
  *
  * Every server-side construction of a workspace storage path goes through
  * this object; the layout can change in exactly one place.
  */
private[register] object WorkspaceStoragePaths:

  /** Where one of a tree's Irmin contents is stored. */
  enum TreePath:
    case Meta(treeId: TreeId)
    case Node(treeId: TreeId, nodeId: NodeId)
    case Mitigation(treeId: TreeId, mitigationId: MitigationId)

    def treeId: TreeId

  /** The layout's fixed segments, each spelled once. */
  private val Workspaces: IrminSegment  = IrminSegment("workspaces")
  private val Trees: IrminSegment       = IrminSegment("risk-trees")
  private val MetaName: IrminSegment    = IrminSegment("meta")
  private val Nodes: IrminSegment       = IrminSegment("nodes")
  private val Mitigations: IrminSegment = IrminSegment("mitigations")

  def workspaceRoot(wsId: WorkspaceId): IrminPath =
    IrminPath.root / Workspaces / IrminSegment.of(wsId.toSafeId)

  def treesRoot(wsId: WorkspaceId): IrminPath =
    workspaceRoot(wsId) / Trees

  def treeRoot(wsId: WorkspaceId, treeId: TreeId): IrminPath =
    treesRoot(wsId) / IrminSegment.of(treeId.toSafeId)

  def treeMeta(wsId: WorkspaceId, treeId: TreeId): IrminPath =
    treeRoot(wsId, treeId) / MetaName

  def treeNodes(wsId: WorkspaceId, treeId: TreeId): IrminPath =
    treeRoot(wsId, treeId) / Nodes

  def treeMitigations(wsId: WorkspaceId, treeId: TreeId): IrminPath =
    treeRoot(wsId, treeId) / Mitigations

  /** The absolute path of `path`, built by the same functions every other
    * reader and writer uses.
    *
    * {{{
    * of(ws, TreePath.Node(t, n)) // workspaces/{ws}/risk-trees/{t}/nodes/{n}
    * }}}
    */
  def of(wsId: WorkspaceId, path: TreePath): IrminPath =
    path match
      case TreePath.Meta(treeId)                     => treeMeta(wsId, treeId)
      case TreePath.Node(treeId, nodeId)             => treeNodes(wsId, treeId) / IrminSegment.of(nodeId.toSafeId)
      case TreePath.Mitigation(treeId, mitigationId) => treeMitigations(wsId, treeId) / IrminSegment.of(mitigationId.toSafeId)

  /** The path of `path` relative to its tree's root, which is how a tree write
    * names its entries.
    *
    * {{{
    * withinTree(TreePath.Node(t, n)) // nodes/{n}
    * }}}
    */
  def withinTree(path: TreePath): IrminPath =
    path match
      case TreePath.Meta(_)                     => IrminPath.root / MetaName
      case TreePath.Node(_, nodeId)             => IrminPath.root / Nodes / IrminSegment.of(nodeId.toSafeId)
      case TreePath.Mitigation(_, mitigationId) => IrminPath.root / Mitigations / IrminSegment.of(mitigationId.toSafeId)
