package com.risquanter.register.services.helper

import zio.test.*
import com.risquanter.register.domain.data.iron.{SafeName, SeedVarId}

object SeedVarIdAssignerSpec extends ZIOSpecDefault:

  private def name(s: String): SafeName.SafeName =
    SafeName.fromString(s).toOption.get
  private def sid(v: Long): SeedVarId.SeedVarId =
    SeedVarId.fromLong(v).toOption.get

  def spec = suite("SeedVarIdAssigner (PLAN-SEED-IDENTITY §5.1)")(
    test("create shape: all leaves unassigned get 1..n in sorted-name order") {
      val names = Seq(name("Zeta"), name("Alpha"), name("Mid"))
      val Right((ids, hw)) = SeedVarIdAssigner.assign(names, Map.empty, highWater = 0L): @unchecked
      assertTrue(
        ids(name("Alpha")).value == 1L,
        ids(name("Mid")).value == 2L,
        ids(name("Zeta")).value == 3L,
        hw.value == 3L
      )
    },
    test("assignment is independent of input sequence order") {
      val a = SeedVarIdAssigner.assign(Seq(name("B"), name("A"), name("C")), Map.empty, 0L)
      val b = SeedVarIdAssigner.assign(Seq(name("C"), name("A"), name("B")), Map.empty, 0L)
      assertTrue(a == b)
    },
    test("fixed IDs are preserved untouched; only unassigned leaves are numbered") {
      val fixed = Map(name("Kept") -> sid(7L))
      val Right((ids, hw)) = SeedVarIdAssigner.assign(
        Seq(name("Kept"), name("New Leaf")), fixed, highWater = 7L
      ): @unchecked
      assertTrue(
        ids(name("Kept")).value == 7L,
        ids(name("New Leaf")).value == 8L,
        hw.value == 8L
      )
    },
    test("high-water, not scan-max: a freed ID is never re-issued") {
      // Tree once held IDs 1..5 (hw = 5); leaf with ID 3 was deleted.
      // A new leaf must get 6, not the freed 3.
      val survivors = Map(
        name("A") -> sid(1L), name("B") -> sid(2L),
        name("D") -> sid(4L), name("E") -> sid(5L)
      )
      val Right((ids, hw)) = SeedVarIdAssigner.assign(
        survivors.keys.toSeq :+ name("Fresh"), survivors, highWater = 5L
      ): @unchecked
      assertTrue(
        ids(name("Fresh")).value == 6L,
        hw.value == 6L
      )
    },
    test("returned high-water never decreases (no new leaves, hw above max)") {
      val fixed = Map(name("Only") -> sid(2L))
      val Right((_, hw)) = SeedVarIdAssigner.assign(Seq(name("Only")), fixed, highWater = 9L): @unchecked
      assertTrue(hw.value == 9L)
    },
    test("multiple new leaves are consecutive from highWater+1, sorted by name") {
      val fixed = Map(name("Old") -> sid(3L))
      val Right((ids, hw)) = SeedVarIdAssigner.assign(
        Seq(name("Old"), name("Zeta New"), name("Alpha New")), fixed, highWater = 3L
      ): @unchecked
      assertTrue(
        ids(name("Alpha New")).value == 4L,
        ids(name("Zeta New")).value == 5L,
        hw.value == 5L
      )
    },
    test("assignment space exhaustion yields a validation error, not a crash") {
      val result = SeedVarIdAssigner.assign(Seq(name("Overflow")), Map.empty, highWater = 49999999L)
      assertTrue(
        result.isLeft,
        result.swap.toOption.get.message.contains("exhausted")
      )
    },
    test("a provided ID above the watermark raises the allocation base (no collision with auto-assignment)") {
      // Fresh create: L1 provided 5; auto-assigned leaves start above it, never at 1..4
      // (§5.1: a provided ID consumes the assignment space above it).
      val fixed = Map(name("Provided") -> sid(5L))
      val Right((ids, hw)) = SeedVarIdAssigner.assign(
        Seq(name("Provided"), name("Auto B"), name("Auto A")), fixed, highWater = 0L
      ): @unchecked
      assertTrue(
        ids(name("Provided")).value == 5L,
        ids(name("Auto A")).value == 6L,
        ids(name("Auto B")).value == 7L,
        hw.value == 7L
      )
    },
    test("a provided ID below the watermark (deliberate resurrection) does not shift auto-assignment") {
      // Tree had IDs up to hw=10; caller resurrects freed ID 3 on a new leaf.
      // The other new leaf continues from the watermark, not from the resurrected ID.
      val fixed = Map(name("Resurrected") -> sid(3L))
      val Right((ids, hw)) = SeedVarIdAssigner.assign(
        Seq(name("Resurrected"), name("Fresh")), fixed, highWater = 10L
      ): @unchecked
      assertTrue(
        ids(name("Resurrected")).value == 3L,
        ids(name("Fresh")).value == 11L,
        hw.value == 11L
      )
    },
    test("a provided ID near the range cap exhausts the assignment space above it (documented §5.1 edge)") {
      val fixed = Map(name("Near Cap") -> sid(49999999L))
      val result = SeedVarIdAssigner.assign(Seq(name("Near Cap"), name("Auto")), fixed, highWater = 0L)
      assertTrue(
        result.isLeft,
        result.swap.toOption.get.message.contains("exhausted")
      )
    }
  )
