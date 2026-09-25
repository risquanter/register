# Plan: read a tree's nodes and mitigations in one Irmin call instead of one per entry

**Status:** Ruled, awaiting approval as the session's governing plan.
**Date:** 2026-09-13.
**ADR reference:** ADR-004a (Irmin as the storage backend, single writer);
ADR-033 (exception boundaries — the new client method raises `IrminError` like
every other method on the trait).

No wire change, no DTO change, no domain change. One method is added to the
Irmin client trait and two private repository methods change their internals.
Everything above the repository sees identical results.

---

## Objective

Loading one risk tree from Irmin currently issues one request per node and one
per mitigation, sequentially.

`RiskTreeRepositoryIrmin.readNodesAt` lists the child names under the tree's
`nodes/` prefix, then fetches each child individually:

```scala
private def readNodesAt(prefix: IrminPath, at: CommitHash): Task[Seq[RiskNode]] =
  for
    childNames <- handleIrmin(irmin.listAtCommit(at, prefix))
    nodes      <- ZIO.foreach(childNames) { child =>
                    val fullPath = IrminPath.unsafeFrom(s"${prefix.value}/${child.value}")
                    handleIrmin(irmin.getAtCommit(at, fullPath)).flatMap {
                      case Some(json) => decodeNode(child, json)
                      case None       => ZIO.fail(RepositoryFailure(s"Missing node value at ${fullPath.value}"))
                    }
                  }
  yield nodes
```

`readMitigationsAt` has the same shape.

Two properties of that code make it slow, and it is worth separating them
because only one is the headline.

**It issues N+1 requests.** One request discovers the names, then N more fetch
the values. This shape is common enough to have a name — the N+1 query problem —
and it appears whenever a list operation is followed by a per-item fetch.

**The N requests are sequential, not concurrent.** `ZIO.foreach` runs its
effects one after another; the concurrent form is `ZIO.foreachPar`. So the N
fetches do not overlap: the total time is N times one round trip, not one round
trip plus scheduling.

For a 500-node tree with a handful of mitigations, one `getById` therefore makes
roughly 503 sequential HTTP calls to Irmin. Every one pays connection handling,
GraphQL parsing and response encoding for a single JSON blob.

Irmin's GraphQL schema offers `list_contents_recursively`, which returns every
path and value beneath a prefix in **one** response. It was verified working
against the `local/irmin-prod:3.11-p1` image. Using it turns the whole read into
one request for the nodes and one for the mitigations.

### Why this is the right fix rather than making the fetches concurrent

Switching `ZIO.foreach` to `ZIO.foreachPar` would also be faster, and it is
worth saying why it is not the answer. It keeps 500 requests and makes them
simultaneous, which moves the cost from latency to load: 500 concurrent
connections against a single-writer store, with the fan-out bounded by nothing.
The recursive read removes the requests instead of overlapping them. One request
cannot overload anything.

### Relationship to Irmin capacity

This is the whole of the Irmin throughput work. Irmin is not replicated and will
not be: `irmin-pack` is a single-writer on-disk format, and its replication is
Git `clone`/`push`/`pull`, which is asynchronous and manual with no leader
election or consensus. A read replica would serve stale data, which is wrong for
a collaborative editor. The read volume is ours to reduce, and this is how.

---

## Exact signatures

### New method on `IrminClient`

Added to the trait in
`modules/server/src/main/scala/com/risquanter/register/infra/irmin/IrminClient.scala`.
No existing method changes.

```scala
  /** Every path and value beneath `path` in the tree at `commit`, in one
    * request. Paths are returned relative to `path`. An absent prefix yields an
    * empty list, matching `listAtCommit`. */
  def listContentsRecursivelyAtCommit(
    commit: CommitHash,
    path:   IrminPath
  ): IO[IrminError, List[(IrminPath, String)]]
```

The companion object gains the matching accessor, following the shape every
other method on this trait already uses:

```scala
  def listContentsRecursivelyAtCommit(
    commit: CommitHash,
    path:   IrminPath
  ): ZIO[IrminClient, IrminError, List[(IrminPath, String)]] =
    ZIO.serviceWithZIO[IrminClient](_.listContentsRecursivelyAtCommit(commit, path))
```

### Implementation in `IrminClientLive`

Follows the established three-step shape — build the query, execute it, extract
the result:

```scala
  override def listContentsRecursivelyAtCommit(
    commit: CommitHash,
    path:   IrminPath
  ): IO[IrminError, List[(IrminPath, String)]] =
    for
      _        <- ZIO.logDebug(s"Irmin LIST-RECURSIVE@${commit.value.take(12)}: ${path.value}")
      response <- executeQuery[CommitTreeContentsResponse](
                    IrminQueries.listContentsRecursivelyAtCommit(commit, path)
                  )
      entries  <- extractContentsAtCommit(path, response)
      _        <- ZIO.logDebug(s"Irmin LIST-RECURSIVE@commit result (${entries.size})")
    yield entries
```

### Query in `IrminQueries`

```scala
  /** Query returning every content path and value beneath `path` in one
    * response, for the tree at `commitHash`. */
  def listContentsRecursivelyAtCommit(commitHash: CommitHash, path: IrminPath): String
```

### Response model in `IrminResponses.scala`

New case classes `CommitTreeContentsResponse`, `CommitTreeContentsData`,
`CommitContentsTreeData` and `ContentsEntryData`, following the existing
`CommitTreeListResponse` / `CommitTreeListData` / `CommitListTreeData` chain
exactly, including the nullability conventions already documented there
(`commit` is null for an unknown hash; the tree accessor is null for an absent
path).

**Field names are confirmed against the live schema in step 1 below, before
they are written.** The introspection is a step of this plan, not an assumption
inside it.

### Repository changes

In
`modules/server/src/main/scala/com/risquanter/register/repositories/RiskTreeRepositoryIrmin.scala`,
both private read methods keep their signatures and change their bodies:

```scala
  private def readNodesAt(prefix: IrminPath, at: CommitHash): Task[Seq[RiskNode]] =
    handleIrmin(irmin.listContentsRecursivelyAtCommit(at, prefix))
      .flatMap(ZIO.foreach(_) { case (child, json) => decodeNode(child, json) })

  private def readMitigationsAt(prefix: IrminPath, at: CommitHash): Task[Seq[Mitigation]] =
    handleIrmin(irmin.listContentsRecursivelyAtCommit(at, prefix))
      .flatMap(ZIO.foreach(_) { case (child, json) => decodeMitigation(child, json) })
```

`ZIO.foreach` stays, and here it is correct: it now sequences pure decoding, not
network calls.

Two details that decide correctness:

**The "missing value" failure branch disappears, and that is a real change in
error behaviour worth stating.** Today a path can be listed and then fetched as
`None`, which raises `RepositoryFailure("Missing node value at …")`. A recursive
contents listing returns paths *with* their values, so the case cannot arise:
a path that has no content is not a content entry and is not returned. Nothing
silently swallows an error — the condition ceases to be representable.

**Paths must be relative to the prefix**, because `decodeNode` and
`decodeMitigation` use the child name in their error messages, and the node
identifier is recovered from it. The new client method's contract states that it
returns paths relative to `path`; if the raw schema returns absolute paths,
`extractContentsAtCommit` strips the prefix, in the same place
`extractListAtCommit` already normalizes paths today.

**Flat storage makes "recursively" exact rather than approximate.** `writeTree`
issues a single `set_tree` whose entries are the tree metadata plus one entry per
node under `nodes/` and one per mitigation under `mitigations/`. There is no
nesting beneath either prefix, so a recursive contents listing at `nodes/`
returns exactly the set the current list-then-fetch pair produces.

---

## File inventory

The file inventory lives in its own document, `PLAN-IRMIN-RECURSIVE-READ-INVENTORY.md`,
which the approval hook reads and only the user writes.

---

## Verification plan

The evidence this change must produce is ruled: correctness and
no-functional-regression from the existing suite plus a new equivalence test, a
**call-count assertion** as the committed regression guard, and a **one-off
timing measurement** recorded here rather than asserted in CI.

### Step 1 — introspect the schema and record the baseline, before any code change

Start the real image and confirm the exact field names and the shape of the
recursive contents query:

```bash
docker run -d --rm --name irmin-probe -p 19080:8080 local/irmin-prod:3.11-p1
curl -s -X POST http://localhost:19080/graphql -H 'Content-Type: application/json' \
  -d '{"query":"{ __type(name: \"Tree\") { fields { name type { name kind } } } }"}' | jq .
```

In the same session, seed trees of several sizes through the running server and
time `getById` on each, **against the current code**. Record the numbers in the
"Measured result" table below. This is the "before" half of the timing
measurement, and taking it first is what makes keeping two code paths
unnecessary.

Remove the probe container when done.

### Step 2 — the equivalence test (correctness)

An integration test seeds a tree with a known set of nodes and mitigations,
reads it back, and asserts the returned `RiskTree` equals the one the write
path was given — same nodes, same identifiers, same mitigations, same structure.
This is what proves the recursive read returns what the per-entry read returned.

### Step 3 — the call-count test (the committed regression guard)

`CountingIrminClient` wraps an `IrminClient`, increments a `Ref[Int]` on every
method, and delegates. Because the trait has around twenty methods, the wrapper
is twenty delegating one-liners plus the counter; it counts *every* call, so it
cannot be fooled by the read moving to a different method.

The test seeds two trees of different sizes — ten nodes and two hundred — reads
each, and asserts **the call count is the same for both**. That is the precise
statement of the property: the number of Irmin requests does not grow with the
number of nodes.

Asserting equality between two sizes rather than against a fixed number is
deliberate. A fixed expected count would need updating whenever an unrelated
part of the read path adds a lookup, which makes it the kind of test people
update without reading. Equality across sizes stays true under those changes and
false under exactly the regression it guards.

### Step 4 — the timing measurement (the "after" half)

Re-run step 1's timing script against the new code and complete the table.
The script is not committed as a test. A wall-clock assertion against a Docker
container fails for reasons unrelated to the code — machine load, cold
containers, a busy CI runner — and a threshold loose enough to avoid that is too
loose to catch a regression. The call-count test is the regression guard; this
is evidence that the change achieved what it was for.

### Measured result

Completed during implementation. Left empty rather than predicted.

| Tree size (nodes) | Irmin calls before | Irmin calls after | `getById` before | `getById` after |
|---|---|---|---|---|
| 10 | | | | |
| 200 | | | | |
| 500 | | | | |

### Step 5 — the full suite

```bash
sbt commonJVM/test
sbt server/test
sbt app/test
```

Then, after the mandatory leaked-network cleanup:

```bash
docker ps -a --filter name=register_it_ --format '{{.ID}}' | xargs -r docker rm -f; docker network ls --filter name=register_it_ --format '{{.ID}}' | xargs -r docker network rm; echo "--- remaining register_it_ networks ---"; docker network ls --filter name=register_it_ --format '{{.Name}}' | wc -l

sbt "serverIt/test"
```

All four tiers green is the acceptance condition. Report pass or fail only.

---

## ADR alignment

- **ADR-004a (Irmin, single writer).** Read-path only. No second writer, no
  change to the write path, no change to commit semantics. Conforms.
- **ADR-033 (exception boundaries).** The new method raises `IrminError` in the
  typed error channel like every other method on the trait; `handleIrmin`
  translates it at the repository boundary exactly as it does today. Conforms.
- **ADR-001 (validate once, at the boundary).** Decoding still happens in
  `decodeNode` and `decodeMitigation`; the change moves how the JSON arrives,
  not how it is validated. Conforms.
- **Decision Trigger review.** Trigger 4 (existing signatures) does not fire: no
  existing signature changes, one method is added. Trigger 5 (existing
  behaviour) fires on one point and it is written out above — the "missing
  value" failure branch becomes unrepresentable rather than unreachable. That is
  the deliberate, stated change, not an incidental one.

---

## Sequencing

Independent of every other open plan. It touches no file that
`PLAN-CACHE-REGISTRY-RENAME`, `PLAN-SIMULATION-CONCURRENCY-BOUNDS`,
`PLAN-NGINX-WORKSPACE-ROUTING` or `PLAN-TELEMETRY-EXPORT` touch, so it can land
in any order relative to them.

---

## Version bump

PATCH. Shipped code changes, no external API change.
