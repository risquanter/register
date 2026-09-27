# Fix — delete `LossDistribution.flatten`

**Status:** presented for approval.
**Inventory:** `docs/dev/plans/FIX-DELETE-LOSSDISTRIBUTION-FLATTEN-INVENTORY.md` —
does not exist yet; created by `.claude/bin/approve-inventory` at approval time.
**Why a fix-note and not a plan:** this is a review-driven deletion of code no
caller uses. It changes no signature that anything calls, no data transferred over
the network, and no behaviour. The working-protocol skill gives that class of
change an inline before-and-after instead of a five-section plan document.
**Why it lands on its own, before the valuation transplant:** ruled 2026-09-15 and
again 2026-09-27. It is taken green by itself so that, if anything in the
transplant goes wrong afterwards, this deletion is already behind a commit of its
own and is not part of what has to be unpicked.

---

## Result

Three method declarations are deleted from
`modules/server/src/main/scala/com/risquanter/register/simulation/LossDistribution.scala`,
and the two test suites that exercise them are deleted from
`modules/server/src/test/scala/com/risquanter/register/simulation/LossDistributionSpec.scala`.
No other file changes. No behaviour changes, because nothing outside those two
test suites ever calls the method.

## Reasoning

`flatten` returns a node's whole subtree as one flat `Vector[LossDistribution]`.
It was written so that a caller holding a portfolio result could walk everything
beneath it.

Nothing calls it. Searching the whole repository for the name gives three
declarations and two test suites, and no other use. The fifteen other occurrences
of `.flatten` in the codebase are the standard collection and `Option` method on
unrelated values — in `RiskTreeServiceLive.scala:101`,
`ScenarioMergeService.scala:193`, `WorkspaceStorePostgres.scala:166`,
`Simulator.scala:158`, `RiskNode.scala:301` and `Mitigation.scala:172`. None of
them is called on a loss distribution.

It also has no future caller, because the two things it was for are now done
differently. Drill-down is a separate request for the child node rather than a
walk over a returned value. Reading a subtree's provenance takes its set of leaves
from `TreeIndex` and resolves each leaf under its own node identifier, which is
what `PLAN-PROVENANCE-ENDPOINT.md` specifies.

Keeping it would also cost something concrete in the change that follows. A method
that hands back the whole subtree makes the size of a returned value depend on the
size of the tree beneath it, and the valuation shape in
`PLAN-FBF-VALUATION-TRANSPLANT.md` is built so that a returned value is bounded by
its own node. Deleting `flatten` first means that plan does not have to carry the
deletion as well as the type change.

---

## The change

### `modules/server/src/main/scala/com/risquanter/register/simulation/LossDistribution.scala`

Three deletions, no additions.

**1. The abstract declaration on the sealed base class, at line 175–176.**

Before:

```scala
  /** All trial IDs with non-zero outcomes */
  def trialIds(): Set[TrialId] = trialOutcomes.trialIds

  /** Flatten hierarchy to vector of all distributions */
  def flatten: Vector[LossDistribution]
}
```

After:

```scala
  /** All trial IDs with non-zero outcomes */
  def trialIds(): Set[TrialId] = trialOutcomes.trialIds
}
```

**2. The implementation on `RiskResult`, at line 191.** The class body becomes
empty, so the braces go with it.

Before:

```scala
case class RiskResult private (
  override val nodeId: NodeId,
  override val trialOutcomes: TrialOutcomes,
  provenances: List[NodeProvenance] = Nil
) extends LossDistribution(nodeId, trialOutcomes) {

  override def flatten: Vector[LossDistribution] = Vector(this)
}
```

After:

```scala
case class RiskResult private (
  override val nodeId: NodeId,
  override val trialOutcomes: TrialOutcomes,
  provenances: List[NodeProvenance] = Nil
) extends LossDistribution(nodeId, trialOutcomes)
```

**3. The implementation on `RiskResultGroup`, at lines 251–252.** Same: the body
becomes empty.

Before:

```scala
final case class RiskResultGroup private (
  children: List[LossDistribution],
  override val nodeId: NodeId,
  override val trialOutcomes: TrialOutcomes
) extends LossDistribution(nodeId, trialOutcomes) {

  override def flatten: Vector[LossDistribution] =
    this +: children.toVector.sortBy(_.nodeId.value)
}
```

After:

```scala
final case class RiskResultGroup private (
  children: List[LossDistribution],
  override val nodeId: NodeId,
  override val trialOutcomes: TrialOutcomes
) extends LossDistribution(nodeId, trialOutcomes)
```

### `modules/server/src/test/scala/com/risquanter/register/simulation/LossDistributionSpec.scala`

Two deletions. Both test the deleted method and nothing else, so neither has a
property to keep. This is Decision Trigger #8 — removing a test assertion — and
this document is where it is presented for approval.

**1. `test("flatten returns hierarchy")`, lines 164–174**, inside the
`RiskResultGroup` suite:

```scala
      test("flatten returns hierarchy") {
        val r1    = withCfg(100) { RiskResult(nodeId("risk-001"), Map(1 -> 1000L), Nil) }
        val r2    = withCfg(100) { RiskResult(nodeId("risk-002"), Map(2 -> 2000L), Nil) }
        val group = withCfg(100) { RiskResultGroup.create(nodeId("TOTAL"), r1, r2).toEither.toOption.get }

        val flattened = group.flatten

        assertTrue(flattened.size == 3) &&
        assertTrue(flattened(0) == group) &&
        assertTrue(flattened.tail.toSet == Set(r1, r2))
      },
```

The test immediately above it already asserts `group.children == List(r1, r2)`, so
the fact that a group holds its children stays covered after this test is removed.

**2. The whole `suite("RiskResult - flatten")`, lines 207–214**, which contains one
test:

```scala
    suite("RiskResult - flatten")(
      test("single result flattens to itself") {
        val result    = withCfg(100) { RiskResult(nodeId("risk-001"), Map(1 -> 1000L), Nil) }
        val flattened = result.flatten

        assertTrue(flattened == Vector(result))
      }
    ),
```

The suite becomes empty once its only test is removed, so the suite is removed
too. The trailing comma before `suite("RiskResult - equality")` goes with it.

---

## Version

`ThisBuild / version` in `build.sbt` moves from `0.10.38` to `0.10.39`, and
`APP_VERSION` is mirrored into both `.env` and `.env.irmin`. Shipped code changes,
which is the PATCH condition; the bump is autonomous and is applied when this
lands. `build.sbt` is therefore in the inventory. The two environment files are
not, because the approval hook gates only `modules/**` and `build.sbt`.

---

## Verification

Every tier, green, before this is reported done. The leaked-state cleanup is a
mandatory step before the integration tier, not something run after a failure.

```bash
sbt compile
sbt 'commonJVM/test; server/test'
sbt app/test
docker ps -a --filter name=register_it_ -q | xargs -r docker rm -f; \
docker network ls --filter name=register_it_ -q | xargs -r docker network rm; \
docker volume ls --filter name=register_it_ -q | xargs -r docker volume rm
sbt "serverIt/test"
```

`sbt compile` carries most of the weight here. `LossDistribution` is a sealed
class, and an inexhaustive match on a sealed hierarchy is a compile error in this
build, so a remaining caller of `flatten` anywhere in `server`, `commonJVM` or
`app` fails the build rather than slipping through.

---

## Relationship to the valuation transplant

`PLAN-FBF-VALUATION-TRANSPLANT.md` replaces `LossDistribution`, `RiskResult` and
`RiskResultGroup` with a different set of types. This deletion lands and is taken
green first. That plan then starts from a file that no longer declares `flatten`,
so `flatten` appears nowhere in its scope.
