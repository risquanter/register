package com.risquanter.register.services

import zio.test.*

/** Unit tests for the pure pieces of the merge pre-check: the three-way
  * byte-equality rule (ADR-032, storage relation) and conflict-path parsing.
  */
object MergeConflictRuleSpec extends ZIOSpecDefault:

  private val base     = Some("""{"id":"n1","name":"cyber","probability":0.4}""")
  private val renamed  = Some("""{"id":"n1","name":"cyber-2026","probability":0.4}""")
  private val reProbed = Some("""{"id":"n1","name":"cyber","probability":0.6}""")

  // Valid 26-char ULIDs for path parsing (SafeId's pinned format).
  private val treeId = "01J8ZQ3FKWP2X9M4V7RTBND6EA"
  private val nodeId = "01J8ZQ3FKWP2X9M4V7RTBND6EB"

  override def spec = suite("MergeConflictRuleSpec")(

    suite("MergeConflictRule.isConflict — three-way byte rule")(
      test("unchanged on both sides is clean") {
        assertTrue(!MergeConflictRule.isConflict(base, base, base))
      },
      test("changed on one side only is clean (the other side wins)") {
        assertTrue(
          !MergeConflictRule.isConflict(base, reProbed, base),
          !MergeConflictRule.isConflict(base, base, renamed)
        )
      },
      test("changed to different bytes on both sides conflicts — including rename vs probability edit (ADR-032)") {
        // The semantic diff (domain hashes) sees the rename as Identical and
        // would predict a clean merge here; the byte rule correctly conflicts.
        assertTrue(MergeConflictRule.isConflict(base, reProbed, renamed))
      },
      test("changed to identical bytes on both sides is clean — the resolution mechanism's foundation") {
        assertTrue(!MergeConflictRule.isConflict(base, reProbed, reProbed))
      },
      test("added on one side only is clean; added differently on both sides conflicts") {
        assertTrue(
          !MergeConflictRule.isConflict(None, base, None),
          !MergeConflictRule.isConflict(None, None, base),
          MergeConflictRule.isConflict(None, base, renamed),
          !MergeConflictRule.isConflict(None, base, base)
        )
      },
      test("deleted on one side while edited on the other conflicts; deleted on both is clean") {
        assertTrue(
          MergeConflictRule.isConflict(base, None, reProbed),
          MergeConflictRule.isConflict(base, reProbed, None),
          !MergeConflictRule.isConflict(base, None, None)
        )
      }
    ),

    suite("MergeConflictPath.fromRelativePath")(
      test("node path yields tree and node coordinates") {
        val parsed = MergeConflictPath.fromRelativePath(s"risk-trees/$treeId/nodes/$nodeId")
        assertTrue(
          parsed.path == s"risk-trees/$treeId/nodes/$nodeId",
          parsed.treeId.map(_.value).contains(treeId),
          parsed.nodeId.map(_.value).contains(nodeId)
        )
      },
      test("meta path yields the tree coordinate only") {
        val parsed = MergeConflictPath.fromRelativePath(s"risk-trees/$treeId/meta")
        assertTrue(
          parsed.treeId.map(_.value).contains(treeId),
          parsed.nodeId.isEmpty
        )
      },
      test("unrecognised shape keeps the raw path with no coordinates") {
        val parsed = MergeConflictPath.fromRelativePath("some/other/path")
        assertTrue(
          parsed.path == "some/other/path",
          parsed.treeId.isEmpty,
          parsed.nodeId.isEmpty
        )
      }
    )
  )
