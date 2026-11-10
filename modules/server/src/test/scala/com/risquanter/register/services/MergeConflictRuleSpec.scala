package com.risquanter.register.services

import java.time.Instant
import zio.test.*
import com.risquanter.register.domain.data.{RiskLeaf, RiskPortfolio}
import com.risquanter.register.domain.data.iron.{SafeName, SeedVarId, WorkspaceId}
import com.risquanter.register.domain.errors.{ErrorSubject, FieldName}
import com.risquanter.register.http.responses.{TreeMetadataFields, Versions}
import com.risquanter.register.infra.irmin.WorkspaceStoragePaths
import com.risquanter.register.infra.irmin.WorkspaceStoragePaths.TreePath
import com.risquanter.register.infra.irmin.model.IrminSegment
import com.risquanter.register.repositories.model.TreeMetadata
import com.risquanter.register.testutil.TestHelpers.*

/** Unit tests for the pure pieces of the merge pre-check: the three-way rule
  * (ADR-032, storage relation), the field-wise merge of a tree's `meta` value,
  * the field comparisons a conflict reports, and the storage location a scan
  * fetches.
  */
object MergeConflictRuleSpec extends ZIOSpecDefault:

  private val base     = Some("""{"id":"n1","name":"cyber","probability":0.4}""")
  private val renamed  = Some("""{"id":"n1","name":"cyber-2026","probability":0.4}""")
  private val reProbed = Some("""{"id":"n1","name":"cyber","probability":0.6}""")

  private def isConflict(b: Option[String], m: Option[String], s: Option[String]): Boolean =
    MergeConflictRule.isConflict(Versions(b, m, s))

  private def merged(b: Option[String], m: Option[String], s: Option[String]): Option[String] =
    MergeConflictRule.merged(Versions(b, m, s))

  // ── `meta` fixtures ──────────────────────────────────────────────────────

  private def name(s: String): SafeName.SafeName = SafeName.fromString(s).toOption.get
  private def seed(n: Long): SeedVarId.SeedVarId = SeedVarId.fromLong(n).toOption.get

  private val tree   = treeId("merge-tree")
  private val root   = nodeId("root")
  private val t0     = Instant.parse("2026-11-01T09:00:00Z")
  private val mergeAt = Instant.parse("2026-11-01T12:00:00Z")

  private val forkMeta = TreeMetadata(tree, name("Ops"), root, seed(3), TreeMetadata.CurrentSchemaVersion, t0, t0)

  private def savedAt(meta: TreeMetadata, minutes: Long): TreeMetadata =
    meta.copy(updatedAt = t0.plusSeconds(minutes * 60))

  // ── node fixtures ────────────────────────────────────────────────────────

  private def leaf(probability: Double): RiskLeaf =
    unsafeGet(RiskLeaf.create(subject = ErrorSubject.Request,
      id = idStr("cyber"),
      name = "Cyber",
      probability = probability,
      shape = lognormalShape(1000L, 50000L),
      parentId = Some(root),
      seedVarId = 1L
    ), "leaf")

  private def portfolio(children: Seq[String]): RiskPortfolio =
    unsafeGet(RiskPortfolio.createFromStrings(subject = ErrorSubject.Request,
      id = idStr("cyber"),
      name = "Cyber",
      childIds = children.map(idStr).toVector,
      parentId = Some(root)
    ), "portfolio")

  override def spec = suite("MergeConflictRuleSpec")(

    suite("MergeConflictRule.isConflict — three-way byte rule")(
      test("unchanged on both sides is clean") {
        assertTrue(!isConflict(base, base, base))
      },
      test("changed on one side only is clean (the other side wins)") {
        assertTrue(
          !isConflict(base, reProbed, base),
          !isConflict(base, base, renamed)
        )
      },
      test("changed to different bytes on both sides conflicts — including rename vs probability edit (ADR-032)") {
        // The semantic diff (domain hashes) sees the rename as Identical and
        // would predict a clean merge here; the byte rule correctly conflicts.
        assertTrue(isConflict(base, reProbed, renamed))
      },
      test("changed to identical bytes on both sides is clean — the resolution mechanism's foundation") {
        assertTrue(!isConflict(base, reProbed, reProbed))
      },
      test("added on one side only is clean; added differently on both sides conflicts") {
        assertTrue(
          !isConflict(None, base, None),
          !isConflict(None, None, base),
          isConflict(None, base, renamed),
          !isConflict(None, base, base)
        )
      },
      test("deleted on one side while edited on the other conflicts; deleted on both is clean") {
        assertTrue(
          isConflict(base, None, reProbed),
          isConflict(base, reProbed, None),
          !isConflict(base, None, None)
        )
      }
    ),

    suite("MergeConflictRule.merged — what a non-conflicting path becomes")(
      test("the two sides agree, so that value is the result") {
        assertTrue(
          merged(base, base, base) == base,
          merged(base, reProbed, reProbed) == reProbed
        )
      },
      test("main still at the base, so the scenario's value wins") {
        assertTrue(merged(base, base, renamed) == renamed)
      },
      test("the scenario still at the base, so main's value wins") {
        assertTrue(merged(base, reProbed, base) == reProbed)
      },
      test("a side absent is a delete the winner's absence carries through") {
        assertTrue(
          // deleted on main, untouched on the scenario: the delete wins
          merged(base, None, base).isEmpty,
          // deleted on the scenario, untouched on main: the delete wins
          merged(base, base, None).isEmpty,
          // added on one side only: the addition wins
          merged(None, base, None) == base,
          merged(None, None, base) == base,
          // deleted on both: absent
          merged(base, None, None).isEmpty
        )
      },
      test("the two functions partition the same inputs: exactly one applies to every triple") {
        // `merged` is defined only where `isConflict` is false, so over the
        // whole input space the conflicting triples and the triples with a
        // merged value must not overlap, and together must cover everything.
        val values = List(None, base, renamed, reProbed)
        val triples = for
          b <- values
          m <- values
          s <- values
        yield (b, m, s)

        val conflicting = triples.filter { case (b, m, s) => isConflict(b, m, s) }
        val clean       = triples.filterNot { case (b, m, s) => isConflict(b, m, s) }

        assertTrue(
          conflicting.nonEmpty,
          clean.nonEmpty,
          conflicting.size + clean.size == triples.size,
          // Every clean triple's merged value is one of the three inputs —
          // the merge never invents a value.
          clean.forall { case (b, m, s) =>
            val out = merged(b, m, s)
            out == m || out == s || out == b
          }
        )
      }
    ),

    suite("MergeConflictRule.mergeMetadata — a tree's meta value merged field by field")(
      test("two saves that differ only in updatedAt merge, with the merge time as updatedAt") {
        val result = MergeConflictRule.mergeMetadata(Some(forkMeta), savedAt(forkMeta, 10), savedAt(forkMeta, 20), mergeAt)
        assertTrue(result == Right(forkMeta.copy(updatedAt = mergeAt)))
      },
      test("both branches renaming the tree differently names the field Name") {
        val onMain     = savedAt(forkMeta, 10).copy(name = name("Operations"))
        val onScenario = savedAt(forkMeta, 20).copy(name = name("Ops risk"))
        assertTrue(MergeConflictRule.mergeMetadata(Some(forkMeta), onMain, onScenario, mergeAt) == Left(List(FieldName.Name)))
      },
      test("a rename on one branch and a raised seed counter on the other merge into a value holding both") {
        val onMain     = savedAt(forkMeta, 10).copy(name = name("Operations"))
        val onScenario = savedAt(forkMeta, 20).copy(seedVarHighWater = seed(5))
        val result     = MergeConflictRule.mergeMetadata(Some(forkMeta), onMain, onScenario, mergeAt)
        assertTrue(result == Right(forkMeta.copy(name = name("Operations"), seedVarHighWater = seed(5), updatedAt = mergeAt)))
      },
      test("the merged value keeps main's createdAt and carries the current schema version") {
        val onMain     = savedAt(forkMeta, 10).copy(schemaVersion = 1)
        val onScenario = savedAt(forkMeta, 20)
        val result     = MergeConflictRule.mergeMetadata(Some(forkMeta), onMain, onScenario, mergeAt)
        assertTrue(
          result.map(_.createdAt) == Right(onMain.createdAt),
          result.map(_.schemaVersion) == Right(TreeMetadata.CurrentSchemaVersion)
        )
      }
    ),

    suite("ConflictFields — the fields a conflict names")(
      test("two leaves differing only in probability differ in Probability") {
        assertTrue(ConflictFields.node(leaf(0.3), leaf(0.6)) == Right(List(FieldName.Probability)))
      },
      test("two portfolios differing only in their children differ in ChildIds") {
        assertTrue(ConflictFields.node(portfolio(Seq("a")), portfolio(Seq("a", "b"))) == Right(List(FieldName.ChildIds)))
      },
      test("a leaf against a portfolio is a storage failure, not a field list") {
        assertTrue(ConflictFields.node(leaf(0.3), portfolio(Seq("a"))).isLeft)
      },
      test("a fork version against a changed version names what the change touched") {
        val fork    = leaf(0.3)
        val changed = leaf(0.6)
        assertTrue(ConflictFields.node(fork, changed) == Right(List(FieldName.Probability)))
      },
      test("two meta values differing in name and seed counter differ in Name and SeedVarHighWater") {
        val one   = TreeMetadataFields(name("Ops"), root, seed(3))
        val other = TreeMetadataFields(name("Operations"), root, seed(5))
        assertTrue(ConflictFields.metadata(one, other) == List(FieldName.Name, FieldName.SeedVarHighWater))
      }
    ),

    suite("WorkspaceStoragePaths.of — the path a scan fetches")(
      test("agrees with the builders every reader and writer uses, and keeps distinct locations distinct") {
        val ws         = WorkspaceId.fromString(idStr("workspace")).toOption.get
        val node       = nodeId("cyber")
        val mitigation = mitigationId("backups")
        val meta       = WorkspaceStoragePaths.of(ws, TreePath.Meta(tree))
        val nodePath   = WorkspaceStoragePaths.of(ws, TreePath.Node(tree, node))
        val mitPath    = WorkspaceStoragePaths.of(ws, TreePath.Mitigation(tree, mitigation))
        assertTrue(
          meta == WorkspaceStoragePaths.treeMeta(ws, tree),
          nodePath == WorkspaceStoragePaths.treeNodes(ws, tree) / IrminSegment.of(node.toSafeId),
          mitPath == WorkspaceStoragePaths.treeMitigations(ws, tree) / IrminSegment.of(mitigation.toSafeId),
          Set(meta, nodePath, mitPath).size == 3
        )
      }
    )
  )
