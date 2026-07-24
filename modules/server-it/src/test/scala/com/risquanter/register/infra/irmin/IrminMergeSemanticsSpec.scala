package com.risquanter.register.infra.irmin

import zio.*
import zio.json.*
import zio.test.*
import sttp.client3.*
import sttp.client3.httpclient.zio.HttpClientZioBackend
import com.risquanter.register.configs.IrminConfig
import com.risquanter.register.infra.irmin.model.IrminPath
import com.risquanter.register.domain.errors.{IrminError, IrminMergeConflict}
import com.risquanter.register.domain.data.iron.{BranchRef, CommitHash}
import com.risquanter.register.testcontainers.IrminCompose

/**
  * Integration tests pinning the Irmin three-way merge semantics that the
  * scenario merge service (ADR-032) builds on. These are the load-bearing
  * facts, each pinned by one test:
  *
  *  1. Paths edited on only one side since the fork merge cleanly (per-path
  *     auto-resolution, ADR-007-appendix §4).
  *  2. The same path edited to DIFFERENT bytes on both sides is refused as
  *     `IrminMergeConflict` — the patched-image contract (regression gate
  *     for the local irmin-graphql patch) — leaving main untouched. A
  *     comparison test relates this to the unpatched upstream surface:
  *     `merge_with_commit` errors on the same conflict, so the patch matches
  *     the sibling mutation's contract. A further pin: `merge_with_branch`
  *     fast-forwards when main has not moved (`merge_with_commit`'s
  *     fast-forward ancestry is state-dependent and deliberately unpinned —
  *     docs/dev/NOTE-irmin-merge-with-commit-ancestry.md).
  *  3. The same path edited to IDENTICAL bytes on both sides merges cleanly —
  *     the assumption behind resolving conflicts as ordinary edits that bring
  *     the branches into byte agreement before a native merge.
  *  4. `getAtCommit` reads a path's value as of a given commit (merge-base
  *     reads for the conflict pre-check).
  *  5. A clean merge commit records both parent heads (true merge ancestry,
  *     needed by the history view).
  *
  * Run: sbt "serverIt/testOnly *IrminMergeSemanticsSpec"
  */
object IrminMergeSemanticsSpec extends ZIOSpecDefault:

  private val testLayer: ZLayer[Any, Throwable, IrminClient & IrminConfig] =
    IrminCompose.irminConfigLayer >+> IrminClientLive.layer

  private def uniquePath(base: String): ZIO[Any, Nothing, IrminPath] =
    ZIO.clockWith(_.instant).map { instant =>
      IrminPath.unsafeFrom(s"test/merge/$base/${instant.toEpochMilli}")
    }

  /** Write an initial value on main, fork a fresh branch at that commit.
    * Returns the branch and the fork-point commit (the merge base).
    */
  private def forkAt(path: IrminPath, baseValue: String): ZIO[IrminClient, IrminError, (BranchRef, CommitHash)] =
    for
      branch   <- freshBranch
      baseHead <- IrminClient.set(path, baseValue, "base write")
      base      = commitHash(baseHead.hash)
      _        <- IrminClient.createBranchAt(branch, base)
    yield (branch, base)

  override def spec = suite("IrminMergeSemanticsSpec")(

    test("disjoint edits since the fork merge cleanly; merge commit has both parents") {
      for
        pathA        <- uniquePath("disjoint-a")
        pathB        <- uniquePath("disjoint-b")
        (branch, _)  <- forkAt(pathA, "\"a0\"")
        _            <- IrminClient.set(pathB, "\"b0\"", "base write b")
        branchHead   <- IrminClient.set(pathA, "\"a-scenario\"", "scenario edits a", branch)
        mainHead     <- IrminClient.set(pathB, "\"b-main\"", "main edits b")
        mergeCommit  <- IrminClient.mergeBranch(branch, BranchRef.Main, "merge disjoint")
        a            <- IrminClient.get(pathA)
        b            <- IrminClient.get(pathB)
      yield assertTrue(
        a.contains("\"a-scenario\""),
        b.contains("\"b-main\""),
        mergeCommit.parents.toSet == Set(branchHead.hash, mainHead.hash)
      )
    },

    test("a conflicting merge fails typed (IrminMergeConflict) and leaves main untouched") {
      // Regression gate for the local irmin-graphql patch
      // (containers/builders/patches/): upstream's `merge_with_branch`
      // resolver silently swallows a conflict (no error, unchanged head);
      // the patched image reports it as a "merge conflict: "-prefixed
      // GraphQL error, which IrminClientLive raises as IrminMergeConflict.
      // This test failing with a successful merge result means the running
      // image is unpatched or the patch no longer applies — do not weaken
      // it; rebuild the image (VERSION-UPGRADE-PROTOCOL, opam section).
      for
        path        <- uniquePath("conflict")
        (branch, _) <- forkAt(path, "\"v0\"")
        _           <- IrminClient.set(path, "\"scenario-value\"", "scenario edit", branch)
        mainHead    <- IrminClient.set(path, "\"main-value\"", "main edit")
        result      <- IrminClient.mergeBranch(branch, BranchRef.Main, "merge conflicting").either
        headAfter   <- IrminClient.mainBranch.map(_.flatMap(_.head))
        onMain      <- IrminClient.get(path)
      yield assertTrue(
        result.left.exists(_.isInstanceOf[IrminMergeConflict]),
        headAfter.map(_.hash).contains(mainHead.hash),
        onMain.contains("\"main-value\"")
      )
    },

    test("comparison: unpatched-style merge_with_commit surfaces the same conflict as an error") {
      // The upstream API already surfaces conflicts on the sibling mutation
      // `merge_with_commit` — the patch brings `merge_with_branch` up to the
      // same contract. Pinned so the patch's behaviour has an in-tree
      // upstream reference point.
      for
        path        <- uniquePath("conflict-mwc")
        (branch, _) <- forkAt(path, "\"v0\"")
        scenHead    <- IrminClient.set(path, "\"scenario-value\"", "scenario edit", branch)
        mainHead    <- IrminClient.set(path, "\"main-value\"", "main edit")
        result      <- mergeWithCommitRaw(commitHash(scenHead.hash), "merge conflicting").either
        headAfter   <- IrminClient.mainBranch.map(_.flatMap(_.head))
      yield assertTrue(
        result.isLeft,
        headAfter.map(_.hash).contains(mainHead.hash)
      )
    },

    test("patched merge_with_branch fast-forwards when main has not moved") {
      // The fast-forward property of the mutation production code calls:
      // with main unmoved since the fork, the merged head IS the scenario
      // head — ancestry intact, no synthetic commit. merge_with_commit's
      // behaviour in this same case is state-dependent (sometimes a commit
      // that drops the scenario parent) and deliberately NOT pinned:
      // docs/dev/NOTE-irmin-merge-with-commit-ancestry.md.
      for
        pathA        <- uniquePath("ff-branch")
        (branchA, _) <- forkAt(pathA, "\"v0\"")
        headA        <- IrminClient.set(pathA, "\"scenario-only\"", "scenario edit", branchA)
        ffCommit     <- IrminClient.mergeBranch(branchA, BranchRef.Main, "merge ff")
      yield assertTrue(ffCommit.hash == headA.hash)
    },

    test("same path edited to identical bytes on both sides merges cleanly") {
      for
        path        <- uniquePath("equal-bytes")
        (branch, _) <- forkAt(path, "\"v0\"")
        _           <- IrminClient.set(path, "\"agreed\"", "scenario edit", branch)
        _           <- IrminClient.set(path, "\"agreed\"", "main edit to same bytes")
        _           <- IrminClient.mergeBranch(branch, BranchRef.Main, "merge equal bytes")
        onMain      <- IrminClient.get(path)
      yield assertTrue(onMain.contains("\"agreed\""))
    },

    test("getAtCommit reads the value as of the given commit; absent path is None") {
      for
        path         <- uniquePath("at-commit")
        otherPath    <- uniquePath("at-commit-absent")
        (branch, base) <- forkAt(path, "\"base-value\"")
        _            <- IrminClient.set(path, "\"changed-later\"", "later edit", branch)
        atBase       <- IrminClient.getAtCommit(base, path)
        absent       <- IrminClient.getAtCommit(base, otherPath)
        current      <- IrminClient.get(path, branch)
      yield assertTrue(
        atBase.contains("\"base-value\""),
        absent.isEmpty,
        current.contains("\"changed-later\"")
      )
    },

    test("lca of main and the scenario head is the fork-point commit") {
      for
        path           <- uniquePath("lca-forkpoint")
        (branch, base) <- forkAt(path, "\"v0\"")
        branchHead     <- IrminClient.set(path, "\"diverged\"", "scenario edit", branch)
        _              <- IrminClient.set(path, "\"main-moved\"", "main edit")
        lcas           <- IrminClient.lca(BranchRef.Main, commitHash(branchHead.hash))
      yield assertTrue(lcas.map(_.hash) == List(base.value))
    }

  ).provideLayerShared(testLayer) @@ TestAspect.sequential @@ TestAspect.withLiveClock

  // ---- raw merge_with_commit (comparison tests only) ----
  // Deliberately NOT an IrminClient method: production code never merges by
  // commit (the fast-forward test above pins why). Speaks GraphQL directly.

  // `parents` arrives as plain hash strings even when queried as a
  // sub-selection — Irmin's GraphQL commit type serialises parents directly.
  private final case class RawCommit(hash: String, parents: List[String])
  private final case class MwcData(merge_with_commit: Option[RawCommit])
  private final case class GqlError(message: String)
  private final case class MwcResponse(data: Option[MwcData], errors: Option[List[GqlError]])
  private given JsonDecoder[RawCommit]   = DeriveJsonDecoder.gen
  private given JsonDecoder[MwcData]     = DeriveJsonDecoder.gen
  private given JsonDecoder[GqlError]    = DeriveJsonDecoder.gen
  private given JsonDecoder[MwcResponse] = DeriveJsonDecoder.gen
  private final case class GraphQLRequest(query: String)
  private given JsonEncoder[GraphQLRequest] = DeriveJsonEncoder.gen

  private def mergeWithCommitRaw(from: CommitHash, message: String): ZIO[IrminConfig, Throwable, RawCommit] =
    ZIO.serviceWithZIO[IrminConfig] { config =>
      val mutation =
        s"""mutation { merge_with_commit(branch: "main", from: "${from.value}", info: {message: "$message"}) { hash parents { hash } } }"""
      ZIO.scoped {
        HttpClientZioBackend.scoped().flatMap { backend =>
          basicRequest
            .post(uri"${config.graphqlUrl}")
            .header("Content-Type", "application/json")
            .body(GraphQLRequest(mutation).toJson)
            .response(asStringAlways)
            .send(backend)
        }
      }.flatMap { resp =>
        ZIO.fromEither(resp.body.fromJson[MwcResponse].left.map(e => new RuntimeException(s"unparseable response: $e")))
      }.flatMap { r =>
        r.data.flatMap(_.merge_with_commit) match
          case Some(c) => ZIO.succeed(c)
          case None =>
            ZIO.fail(new RuntimeException(
              r.errors.toList.flatten.map(_.message).mkString("; ")))
      }
    }

  // ---- helpers (same conventions as IrminClientIntegrationSpec) ----

  private def freshBranch: ZIO[Any, Nothing, BranchRef] =
    Random.nextIntBetween(0, 1000000).flatMap { salt =>
      ZIO.clockWith(_.instant).map(i => unsafeBranch(s"scenarios.merge.t${i.toEpochMilli}x$salt"))
    }

  private def unsafeBranch(s: String): BranchRef =
    BranchRef.fromString(s).fold(e => throw new IllegalArgumentException(e.mkString(";")), identity)

  private def commitHash(s: String): CommitHash =
    CommitHash.fromString(s).fold(e => throw new IllegalArgumentException(e.mkString(";")), identity)
