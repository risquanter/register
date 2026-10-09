# PLAN-IRMIN-GRAPHQL-VARIABLES — send values beside the query, not inside it

**Plan code:** `IGV`. Decisions in this document are labelled `IGV-D-<n>`.

**Status:** implementation-grade. No open decisions. Not started.

**Scope:** one object and its single caller —
`modules/server/src/main/scala/com/risquanter/register/infra/irmin/IrminQueries.scala`,
`IrminClientLive`, and the request wrapper they share. No signature outside the
`com.risquanter.register.infra.irmin` package changes, so no service, repository
or controller is touched.

---

## 1. What this closes

### 1.1 The concept, in plain terms

A value placed inside a text that something else will parse has to be
distinguishable from that text's own punctuation. A GraphQL string literal is
delimited by the double quote, so a value containing a double quote would end
the literal early and the remainder would be read as query syntax. This is
called **embedding**, and it has exactly two remedies. Either forbid the
dangerous characters in the value's type, or rewrite the value reversibly on the
way in so none of its characters can be read as punctuation — **escaping**.

`IrminQueries` builds every GraphQL document as text and uses both remedies on
different values:

- Branch names, storage paths and commit hashes are Iron-refined types whose
  character classes exclude the double quote and the backslash. They are safe
  because no value of those types can carry the dangerous characters. Nothing at
  the point of use has to remember anything.
- The commit message, the commit author and a stored node's JSON blob are raw
  `String`. They are safe only because `IrminQueries.escapeGraphQLString` is
  called on each of them, at eleven call sites.

### 1.2 The defect

The second group's safety is restated at every call site rather than enforced
once. A new mutation that interpolates a message without calling the escaper
compiles, passes the unit tiers, and is an injection path, because unlike the
refined values these genuinely can contain a double quote. Nothing in a
signature prevents it and no compiler check catches it.

ADR-029 (the architecture decision record governing injection defence) §2 ranks
the remedies, and after this plan the Irmin boundary moves from its second tier
to its first:

> **Preferred — a parameterised, structured, or AST-level interface.** Values
> travel beside the text rather than inside it, so the interpreter never lexes
> them and no character they contain can become syntax. The typed Quill DSL,
> zio-json codecs, Laminar's `textContent`, and a GraphQL document's `variables`
> member are all this route.

AST stands for abstract syntax tree — the parsed form of a document, as opposed
to its text.

### 1.3 What a GraphQL variable is

A GraphQL request is an HTTP POST whose body is a JSON object. That object may
carry a `variables` member alongside `query`. When it does, the document
declares named parameters and refers to them instead of containing the values:

```
mutation SetValue($value: Value!, $message: String!, $author: String!) {
  set(path: "nodes/n", value: $value, info: { message: $message, author: $author }) { hash }
}
```

with the body:

```json
{"query": "mutation SetValue(...) {...}",
 "variables": {"value": "{\"name\":\"Server Outage\"}", "message": "Update tree", "author": "zio-client"}}
```

The server lexes and parses only `query`. The values in `variables` arrive
already parsed, because JSON parsing is what turned those bytes into strings,
and are then bound to the parameter nodes of the built query tree. A value is
never GraphQL source, so there is no literal for a double quote to end. This is
the same arrangement as a SQL prepared statement.

**The embedding disappears, so its escaper has no job.** That is why this plan
deletes `escapeGraphQLString` rather than improving it. An escaper can only be
deleted by removing the embedding it serves.

### 1.4 Evidence this works against our Irmin

Irmin is the content-addressed store behind every risk tree, reached only over
GraphQL. The image is built from source so the project can carry a local patch
to the `merge_with_branch` resolver, so general claims about GraphQL servers do
not settle how ours behaves. Twelve probes were run against a live
`local/irmin-prod:3.11-p1` on 2026-10-08. The results the design rests on:

| Probe | Result |
|---|---|
| A read with the branch name as a variable | Same response as the interpolated form |
| A required variable declared but not supplied | Rejected: `Argument 'name' of type 'BranchName!' expected on field 'branch', found null` |
| `set` with every argument a variable | Committed |
| `set` with a value carrying `"`, `\` and a newline, **unescaped**, via variables | Committed, and read back byte-exact |
| An input-object literal with variable fields — `info: {message: $message, author: $author}` | Accepted |
| `test_and_set_branch` with variables | Returned `true` |
| `merge_with_branch` with variables | Committed |
| `set_tree` with one variable per entry value | Committed; values with `"` and `\` read back byte-exact |
| `set_tree` with the whole `tree` list as a single variable | Committed |

Introspection also fixed the declared types the document must name:
`set_tree`'s `tree` argument is `NON_NULL<LIST<NON_NULL<INPUT_OBJECT TreeItem>>>`,
its `path` is `NON_NULL<SCALAR Path>`, its `info` is `INPUT_OBJECT InfoInput`,
and its `branch` is `SCALAR BranchName`.

The second row matters for the new failure mode this plan introduces: a document
that declares a variable the code forgets to supply fails loudly, naming the
argument, rather than writing a null into the store.

---

## 2. Decisions ruled in this plan

### IGV-D-1 — the builders return `GraphQLRequest`, and no new type is introduced

A builder must now produce a document together with the values that travel
beside it. `GraphQLRequest` in
`modules/server/src/main/scala/com/risquanter/register/infra/irmin/model/IrminResponses.scala`
is already exactly that pair, and it is already what `IrminClientLive.executeQuery`
posts. A second type pairing the same two things would duplicate it, so the
builders return `GraphQLRequest` directly and `executeQuery` takes one instead of
building it.

### IGV-D-2 — only free text becomes a variable; Iron-refined values stay interpolated

Three reasons, in order of weight.

A refined value's guarantee is a compile-time property of its type: no value of
`BranchRefStr` can contain a double quote, and no test or deployment is needed to
know that. Moving such a value into `variables` replaces that with a run-time
guarantee of the same property, which is weaker.

The `test` and `set` arguments of `test_and_set_branch` are nullable, and
`test: null` is a distinct instruction meaning the branch must not currently
exist — it is how `createBranchAt` expresses its precondition. An explicit JSON
null was probed and works; an unsupplied nullable variable is a different thing
under the GraphQL specification and was not probed. Keeping these two
interpolated avoids the question entirely, and they are `CommitHash`, so they are
already safe by type.

`getHistory` interpolates its `n: PositiveInt` as an unquoted number. It is not
inside a string literal, so there is no embedding and nothing to move.

The free-text values are therefore exactly: the `value` of `setValue`, each
entry's `value` in `setTree`, and the `message` and `author` of `setValue`,
`setTree`, `removeValue` and `mergeWithBranch`.

### IGV-D-3 — `setTree` passes the whole `tree` list as one variable

The alternative is one variable per entry, with generated names `$v0`, `$v1` and
so on. A risk tree may hold up to 10 000 nodes — the ceiling recorded in the
resource-limit table of ADR-017 §6 — and `setTree` writes the whole subtree in
one commit, so that form would put up to 10 000 variable declarations into a
single document and grow the request with the tree. Passing the list as one
variable keeps the document a fixed size regardless of how many entries it
carries.

Two consequences follow and both are accepted.

The variables member can no longer be `Map[String, String]`, because a list of
input objects is not a string. It becomes `Map[String, Json]`, using
`zio.json.ast.Json`, which the project already depends on.

Each entry's `path` travels inside the list variable rather than staying
interpolated, which is a narrow exception to IGV-D-2. It is forced: the path and
the value are fields of the same `TreeItem` object, and the object is the
variable. `IrminPath` is refined, so nothing becomes less safe — only the
mechanism changes for that one value.

### IGV-D-4 — a generated GraphQL client is refused (user ruling, 2026-10-08)

A client library with schema-driven code generation would produce the documents
structurally and is the textbook form of ADR-029 §2's preferred tier. It is
refused for this codebase. The current solution grew organically, it works
against our patched Irmin instance, and it is not a considerable maintenance
burden given that it is established and tested.

---

## 3. Exact signatures

### 3.1 The request wrapper — widened

In `modules/server/src/main/scala/com/risquanter/register/infra/irmin/model/IrminResponses.scala`,
with `import zio.json.ast.Json` added:

```scala
/** GraphQL request body: the document, and the values bound to its declared
  * variables. A value carried here is never lexed as GraphQL source, so it
  * needs no escaping for the document's syntax (ADR-029 §2, preferred tier).
  */
final case class GraphQLRequest(
    query: String,
    variables: Option[Map[String, Json]] = None
)

object GraphQLRequest:
  given JsonCodec[GraphQLRequest] = DeriveJsonCodec.gen[GraphQLRequest]
```

### 3.2 Every builder's signature

Parameter lists are unchanged throughout. Only the return type changes, from
`String` to `GraphQLRequest`, so no call site's arguments move.

```scala
object IrminQueries:
  def getValue(path: IrminPath, branch: BranchRef = BranchRef.Main): GraphQLRequest
  val listBranches: GraphQLRequest
  def listTree(path: IrminPath, branch: BranchRef = BranchRef.Main): GraphQLRequest
  def setValue(path: IrminPath, value: String, message: String, author: String, branch: BranchRef = BranchRef.Main): GraphQLRequest
  def setTree(path: IrminPath, entries: List[IrminTreeEntry], message: String, author: String, branch: BranchRef = BranchRef.Main): GraphQLRequest
  def removeValue(path: IrminPath, message: String, author: String, branch: BranchRef = BranchRef.Main): GraphQLRequest
  def getBranchInfo(branch: StoreBranch = BranchRef.Main): GraphQLRequest
  val getMainBranch: GraphQLRequest
  def mergeWithBranch(from: BranchRef, into: StoreBranch, message: String, author: String): GraphQLRequest
  def revert(commitHash: CommitHash, branch: BranchRef): GraphQLRequest
  def testAndSetBranch(branch: StoreBranch, test: Option[CommitHash], set: Option[CommitHash]): GraphQLRequest
  def getValueAtCommit(commitHash: CommitHash, path: IrminPath): GraphQLRequest
  def listTreeAtCommit(commitHash: CommitHash, path: IrminPath): GraphQLRequest
  def getCommit(commitHash: CommitHash): GraphQLRequest
  def getHistory(path: IrminPath, n: PositiveInt, branch: BranchRef = BranchRef.Main): GraphQLRequest
  def lca(branch: BranchRef, commitHash: CommitHash): GraphQLRequest
```

The two private fragment helpers are unchanged and keep their `String` return,
because both produce document structure rather than a value:

```scala
  private def branchSelector(branch: StoreBranch): String
  private def branchArg(branch: StoreBranch): String
```

`escapeGraphQLString` is deleted. It has no caller after this change.

### 3.3 The four bodies that gain variables

**A note for the implementer.** Inside a Scala `s"""…"""` interpolator a literal
dollar sign is written `$$`. The code below is what goes in the file; the
rendered document carries single dollar signs.

```scala
  def setValue(path: IrminPath, value: String, message: String, author: String, branch: BranchRef = BranchRef.Main): GraphQLRequest =
    GraphQLRequest(
      query = s"""
      |mutation SetValue($$value: Value!, $$message: String!, $$author: String!) {
      |  set(
      |    ${branchArg(branch)}path: "${path.value}",
      |    value: $$value,
      |    info: { message: $$message, author: $$author }
      |  ) {
      |    hash
      |    key
      |    parents
      |    info { date author message }
      |  }
      |}
      """.stripMargin.trim,
      variables = Some(Map(
        "value"   -> Json.Str(value),
        "message" -> Json.Str(message),
        "author"  -> Json.Str(author)
      ))
    )
```

```scala
  def setTree(path: IrminPath, entries: List[IrminTreeEntry], message: String, author: String, branch: BranchRef = BranchRef.Main): GraphQLRequest =
    GraphQLRequest(
      query = s"""
      |mutation SetTree($$tree: [TreeItem!]!, $$message: String!, $$author: String!) {
      |  set_tree(
      |    ${branchArg(branch)}path: "${path.value}",
      |    tree: $$tree,
      |    info: { message: $$message, author: $$author }
      |  ) {
      |    hash
      |    key
      |    parents
      |    info { date author message }
      |  }
      |}
      """.stripMargin.trim,
      variables = Some(Map(
        "tree"    -> Json.Arr(entries.map(e =>
                       Json.Obj("path" -> Json.Str(e.path.value), "value" -> Json.Str(e.value))
                     )*),
        "message" -> Json.Str(message),
        "author"  -> Json.Str(author)
      ))
    )
```

```scala
  def removeValue(path: IrminPath, message: String, author: String, branch: BranchRef = BranchRef.Main): GraphQLRequest =
    GraphQLRequest(
      query = s"""
      |mutation RemoveValue($$message: String!, $$author: String!) {
      |  remove(
      |    ${branchArg(branch)}path: "${path.value}",
      |    info: { message: $$message, author: $$author }
      |  ) {
      |    hash
      |    key
      |    parents
      |    info { date author message }
      |  }
      |}
      """.stripMargin.trim,
      variables = Some(Map("message" -> Json.Str(message), "author" -> Json.Str(author)))
    )
```

```scala
  def mergeWithBranch(from: BranchRef, into: StoreBranch, message: String, author: String): GraphQLRequest =
    GraphQLRequest(
      query = s"""
      |mutation MergeWithBranch($$message: String!, $$author: String!) {
      |  merge_with_branch(
      |    ${branchArg(into)}from: "${from.toBranchRef}",
      |    info: { message: $$message, author: $$author }
      |  ) {
      |    hash
      |    key
      |    parents
      |    info { date author message }
      |  }
      |}
      """.stripMargin.trim,
      variables = Some(Map("message" -> Json.Str(message), "author" -> Json.Str(author)))
    )
```

### 3.4 The eleven builders with no free text

Each keeps its document text exactly as it stands today and wraps it. `getValue`
is the pattern for all eleven:

```scala
  def getValue(path: IrminPath, branch: BranchRef = BranchRef.Main): GraphQLRequest =
    GraphQLRequest(s"""
    |{
    |  ${branchSelector(branch)} {
    |    tree {
    |      get(path: "${path.value}")
    |    }
    |  }
    |}
    """.stripMargin.trim)
```

The other ten are `listBranches`, `listTree`, `getBranchInfo`, `getMainBranch`,
`revert`, `testAndSetBranch`, `getValueAtCommit`, `listTreeAtCommit`,
`getCommit`, `getHistory` and `lca`.

### 3.5 The caller

In `IrminClientLive`:

```scala
  private def executeQuery[R: JsonDecoder](request: GraphQLRequest): IO[IrminError, R] =
    val requestJson = request.toJson
    val uri = Uri.unsafeParse(config.graphqlUrl)
    basicRequest
      .post(uri)
      .contentType("application/json")
      .body(requestJson)
      .response(asJson[R])
      .readTimeout(config.timeout.asScala)
      .send(backend)
      .flatMap { response => /* unchanged */ }
      .mapError(mapNetworkError)
```

The line `val request = GraphQLRequest(query)` is removed, because the argument
now is the request. The eighteen call sites keep their arguments and their
`executeQuery[ResponseType](…)` shape unchanged; four of them currently bind a
`query` value first and that binding's type changes from `String` to
`GraphQLRequest`.

---

## 4. ADR alignment

| ADR | Bearing | Status after this plan |
|---|---|---|
| ADR-029, input injection defence | Owns the rule this plan satisfies | Compliant, and the boundary moves from §2's approved alternative to its preferred tier. §3's table row is rewritten in this plan |
| ADR-001, correct by construction | Free text staying a raw `String` is its documented carve-out for commit messages and serialization payloads | Compliant, unchanged |
| ADR-004a, persistence and scenarios | One user action produces one commit, and GraphQL is the only Irmin channel with a single writer | Compliant, unchanged — no operation is split or merged |
| ADR-010 and ADR-031, error handling and readiness | `executeQuery`'s typed error channel and bounded timeout | Compliant, unchanged. `mapNetworkError` and `parseError` are untouched |
| ADR-020, supply chain | A generated client would add a dependency | No new dependency. `zio.json.ast.Json` is part of zio-json, already pinned. IGV-D-4 records the refusal |
| ADR-035, error messages | Irmin's rejection text reaches the log, not the client | Compliant, unchanged |

**The distillation is updated in this plan.** Plan Quality Gate item 3 requires
that a plan amending an ADR also updates the `adr-constraints` skill. Two edits,
applied to `.github/skills/adr-constraints/SKILL.md` first and then copied
byte-identically to `.claude/skills/adr-constraints/SKILL.md`:

- The constraint added on 2026-10-08 forbidding a non-Iron-derived value in an
  Irmin GraphQL document is reworded. After this plan free text is not
  interpolated at all, so the rule becomes that only document structure and
  Iron-refined values appear in the text, and every free-text value goes to
  `variables`.
- The ADR-029 row in the positive-invariants table gains the `variables` member
  as the named route for a value sent to Irmin.

---

## 5. Test changes

### 5.1 Rewritten — `IrminQueriesSpec`

`modules/server/src/test/scala/com/risquanter/register/infra/irmin/IrminQueriesSpec.scala`
currently pins the escaper's behaviour through `setValue`'s rendered document,
in seven cases. The escaper is deleted, so those cases are replaced rather than
removed. The property being pinned changes from "the escaper rewrites the
dangerous characters" to "the value is absent from the document and present in
the variables verbatim", which is a stronger statement about the same goal:

- For each of `setValue`, `setTree`, `removeValue` and `mergeWithBranch`: the
  rendered `query` contains the variable reference and does **not** contain the
  free-text value, asserted with a value carrying a double quote, a backslash
  and a newline.
- For the same four: `variables` carries each free-text value **unmodified** —
  equal to the input string, not an escaped form of it.
- Every variable the document declares is present as a key in `variables`, and
  every key in `variables` is declared by the document. One case per builder,
  because a mismatch is the new failure mode IGV-D-3's generated names removed
  but the fixed names can still produce.
- `setTree`'s `tree` variable is a `Json.Arr` whose length equals the entry
  count, with each element's `path` and `value` matching its entry.
- For the eleven builders with no free text: `variables` is `None`.

### 5.2 Extended — `IrminClientIntegrationSpec`

`modules/server-it/src/test/scala/com/risquanter/register/infra/irmin/IrminClientIntegrationSpec.scala`
already covers set and get round trips, commit author information, named
branches and the compare-and-set operations against a live Irmin. It gains the
case the probes were manual stand-ins for:

- A value containing a double quote, a backslash, a newline and a character
  below U+0020 is written through `set`, then read back equal to the input. The
  lower bound U+0020 is from the GraphQL specification, which admits no
  unescaped source character below it in a string literal.
- The same payload written through `setTree` as one of several entries, then read
  back equal, so the list-variable form of IGV-D-3 is covered against the real
  server and not only by probe.
- A commit message containing a double quote survives into the commit
  information the store returns.

### 5.3 Unchanged and load-bearing

No existing assertion is weakened or deleted. `IrminMergeSemanticsSpec`,
`IrminRevertSemanticsSpec`, `RiskTreeRepositoryIrminSpec`,
`ScenarioMergeServiceItSpec` and `TreeRevertItSpec` all drive real operations
through `IrminClientLive` without naming a document, so they pass unchanged if
the migration is correct and fail if any operation's variables do not match its
declarations. They are the regression net for this plan.

---

## 6. Verification plan

Run in this order. Every tier must be green; results are reported pass or fail
only.

```bash
sbt commonJVM/test
sbt server/test
sbt app/test
```

Expected: each prints `[success]` with no `[error]` line.

Before the integration tier, clear leaked Docker state. This is a mandatory
pre-step, not crash recovery: `IrminCompose` creates a uniquely named
`register_it_<random>` stack per spec, and interrupted runs leave containers,
networks and volumes behind until Docker's address pool is exhausted.

```bash
docker ps -a --filter name=register_it_ -q | xargs -r docker rm -f; docker network ls --filter name=register_it_ -q | xargs -r docker network rm; docker volume ls --filter name=register_it_ -q | xargs -r docker volume rm; echo "--- remaining register_it_ containers/networks/volumes ---"; for k in "ps -a" "network ls" "volume ls"; do docker $k --filter name=register_it_ -q | wc -l; done
```

Expected: three zeros.

```bash
sbt serverIt/test
```

Expected: `[success]` with no `[error]` line.

Two structural checks confirm the migration is complete rather than partial.

```bash
grep -rn 'escapeGraphQLString' modules/
```

Expected: no output. A match means an escaper survived, which means a free-text
value is still embedded in a document.

```bash
grep -nE 'value: "|message: "|author: "' modules/server/src/main/scala/com/risquanter/register/infra/irmin/IrminQueries.scala
```

Expected: no output. A match means a free-text value is still inside a GraphQL
string literal.

A version bump applies when the work lands, because shipped code changes: PATCH
per step, and MINOR when this plan closes.

---

## 7. File inventory

The paths the enforcement hook matches live in
`docs/dev/plans/PLAN-IRMIN-GRAPHQL-VARIABLES-INVENTORY.md`, which does not exist
yet and which only the user creates. The plan's scope is:

Production:

- `modules/server/src/main/scala/com/risquanter/register/infra/irmin/IrminQueries.scala`
- `modules/server/src/main/scala/com/risquanter/register/infra/irmin/IrminClientLive.scala`
- `modules/server/src/main/scala/com/risquanter/register/infra/irmin/model/IrminResponses.scala`

Tests:

- `modules/server/src/test/scala/com/risquanter/register/infra/irmin/IrminQueriesSpec.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/infra/irmin/IrminClientIntegrationSpec.scala`

Build:

- `build.sbt` — the version bump only

Outside the hook's gate, so needing no inventory entry: ADR-029 and the two
mirrored copies of the constraints distillation.

`modules/server/src/test` is covered by the same-module test authorization
because the inventory lists files under `modules/server/src/main`.
`modules/server-it` has no `src/main`, so its one spec needs an explicit entry.

---

## 8. Open decisions

None. The four decisions this plan raised are ruled in §2.

---

## 9. What this plan does not do

`IrminQueries` keeps building its documents by assembling strings. What changes
is what is available to put in them: after this plan the text-building code
holds document structure and Iron-refined values only, and every free-text value
reaches the server through `variables`. That is enforcement by what is in scope
rather than by a type error, which is weaker than a compiler check and stronger
than a convention, and it is visible in a signature rather than requiring a
reader to notice a missing call.

The branch **selector** keeps one interpolation that is structure rather than a
value, and it could not move to `variables` even in principle: a read of a named
branch aliases it as `main` so that every response decodes identically, and the
choice between that aliased call and the bare `main` selector is a choice
between two document shapes. The branch name inside it is an ordinary argument
value and stays interpolated under IGV-D-2.
