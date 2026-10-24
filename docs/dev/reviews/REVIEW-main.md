# Review coverage — branch `main`

```
COVERAGE  tier=routine  scope=git diff 09092a5e..HEAD (94 files, 5085 insertions)
read-in-full:
  docs/dev/plans/PLAN-LOSS-SHAPE-ALGEBRA.md
  docs/dev/plans/PLAN-LOSS-SHAPE-ALGEBRA-INVENTORY.md
  CLAUDE.md
  .claude/skills/code-quality-review/SKILL.md
  .claude/rules/scala-algebraic-design.md
  .claude/rules/scala-domain.md
  .claude/rules/scala-style.md
  .claude/rules/scala-test.md
  .claude/rules/laminar.md
  .claude/rules/security.md
  ~/.claude/skills/boyscout/SKILL.md
  docs/user/TERMINOLOGY.md (Core Concepts through Loss Distribution)
  modules/common/src/main/scala/com/risquanter/register/domain/data/DistributionShape.scala
  modules/common/src/main/scala/com/risquanter/register/domain/data/RiskNode.scala
  modules/common/src/main/scala/com/risquanter/register/domain/data/LeafSimContent.scala
  modules/common/src/main/scala/com/risquanter/register/domain/data/Mitigation.scala
  modules/common/src/main/scala/com/risquanter/register/domain/data/Provenance.scala
  modules/common/src/main/scala/com/risquanter/register/domain/data/RiskLeafTransform.scala
  modules/common/src/main/scala/com/risquanter/register/http/requests/DistributionShapeRequest.scala
  modules/common/src/main/scala/com/risquanter/register/http/requests/DistributionPreviewResponse.scala
  modules/common/src/main/scala/com/risquanter/register/domain/errors/ValidationErrorCode.scala
  modules/server/src/main/scala/com/risquanter/register/services/DistributionPreviewService.scala
  modules/server/src/main/scala/com/risquanter/register/services/helper/Simulator.scala (imports + createDistributionWithParams)
  modules/common/src/test/scala/com/risquanter/register/domain/data/RiskLeafSpec.scala (header + wire-format suite)
  modules/common/src/test/scala/com/risquanter/register/domain/data/MitigationEntitySpec.scala (lines 1-150)
  modules/common/src/test/scala/com/risquanter/register/testutil/TestHelpers.scala (diff)
  every remaining file in the diff, as diff hunks plus enclosing functions
discharged:
  1 Plan conformance — 5 findings reported — inventory matched at
    PLAN-LOSS-SHAPE-ALGEBRA-INVENTORY.md:10-66 (every gated path named; the 41
    files outside it are test sources reached by the standing authorization,
    §12); signatures matched verbatim at RiskNode.scala:96-147, :247-253,
    :279-290, :332-338, :405-411, DistributionShape.scala:28-35, :75-78,
    :119-123, :180-184, :217-230, :261-265, :283-288, Provenance.scala:49-61,
    DistributionPreviewResponse.scala:39-44
  2 Mechanical bug scan — PASS — no wrong-variable, inverted-condition,
    unhandled-branch, swallowed-exception, unrun-effect or falsy-zero defect
    found; the two accumulate-then-gate sites that could hold one are
    DistributionShape.scala:133-143 and :295-302
  3 Convention conformance — 2 findings reported — if/else-to-match applied:
    DistributionSpecBuilder.scala:141-164 and FormMode.scala:141-168 both
    became matches on the coproduct, and the only surviving `if`s
    (DistributionShape.scala:126, RiskLeafTransform.scala:149,
    InvalidationHandler.scala:216) are genuine logical conditions
  4 Test presence — 1 finding reported — 156 test names added against 144
    removed; every rule in the plan's §4.5 table has an assertion in
    DistributionShapeSpec.scala:56-320 and DistributionShapeRequestSpec.scala:63-211;
    the Kleisli laws at RiskLeafTransformSpec.scala:60-94; the ResultStage gate at
    MitigationEntitySpec.scala:84-108; portfolio content equality at
    RiskPortfolioSpec.scala:370; cache-key rekey at LeafSimContentSpec.scala:32,:46
  5 Drive-by changes — PASS — every hunk traces to a plan item; the three
    Laminar `{*, given}` → `*` import narrowings (TreeNodeRow.scala:3,
    TreeDetailView.scala:3, TreePreview.scala:3) are the scala-style
    implicit-scope rule applied to files the change already edited
  6 Boyscout pass, whole-file scope — 4 fixes performed, 2 findings reported —
    fixes at RiskNode.scala:15-29, RiskLeafTransform.scala:109-117,
    DistributionPreviewService.scala:8-10/:50-53/:79-82,
    DistributionPreviewServiceSpec.scala:73
  7 Terminology freshness (name level) — 1 finding reported — glossary hits
    checked at TERMINOLOGY.md:45 (Loss Shape), :85 (Loss Distribution), :171
    (Parameter-Stage Mitigation), :280 (Relative and Absolute Statements), :508
    (Product and Coproduct), :542 (How Parameter-Stage Mitigations Compose); all
    six are current against the code, and no terminology note is owed
escalated:
  modules/common/src/main/scala/com/risquanter/register/domain/data/RiskNode.scala:162-169 —
    the stored and wire leaf nests the shape under a `shape` key where the plan's
    §9 and §5.7 state the eleven flat primitive fields are kept, so the Irmin blob
    bytes and the tree response body both change (wire contracts; identity and
    equality machinery) → complex, security
  modules/common/src/main/scala/com/risquanter/register/domain/data/Mitigation.scala:56-146,
  :263-285 — the mitigation stage tags change from `leaf`/`result` to
    `leafRelative`/`leafAbsolute`/`result` and the stamp and anchor are renamed,
    where the plan's §6.6 states stored mitigations decode and re-encode unchanged
    (persistence format) → complex, security
  modules/server/src/main/scala/com/risquanter/register/services/pipeline/InvalidationHandler.scala:215-221 —
    `nodeContent(node) != nodeContent(node)` became `node != node`, so which nodes
    a write notifies now rests on derived case-class equality over every field
    rather than on encoded bytes (identity and equality machinery; changed
    notification semantics) → complex
  modules/common/src/main/scala/com/risquanter/register/domain/data/Mitigation.scala:195 —
    whether `DeriveJsonCodec.gen[MitigationSpec]` reaches the `@jsonHint`s on
    `LeafRelative`/`LeafAbsolute` through the intermediate sealed `LeafStageSpec`
    is a derivation-level fact this tier did not settle; `sbt commonJVM/test`
    settles it (MitigationEntitySpec.scala:137) → complex
  modules/common/src/main/scala/com/risquanter/register/domain/data/DistributionShape.scala:307-317,
  :340-350 — the per-case decoders hardcode `ErrorSubject.Request`, so a refusal
    on the store-read path names a request; whether that is right where
    `Simulator` builds its locations from `ErrorSubject.ExistingNode` is a
    changed error-path question → complex
  modules/common/src/main/scala/com/risquanter/register/domain/data/iron/OpaqueTypes.scala:139-146 —
    one `JsonEncoder`/`JsonDecoder` pair is declared for a refinement that
    `RetentionFactor` and `ResidualProbability` both spell, so the instance is
    shared by two domain concepts by structural coincidence → complex
```

```
COVERAGE  tier=complex  scope=git diff 09092a5e..HEAD, the six escalation items of the routine entry above
read-in-full:
  docs/dev/plans/PLAN-LOSS-SHAPE-ALGEBRA.md
  docs/dev/plans/PLAN-LOSS-SHAPE-ALGEBRA-INVENTORY.md
  docs/dev/decision-records/ADR-001.md, ADR-002.md, ADR-003.md, ADR-010.md, ADR-014.md,
    ADR-018-nominal-wrappers.md, ADR-032-content-equality-relations.md,
    ADR-035-error-leakage-prevention.md, ADR-037-in-code-constants.md,
    ADR-038-response-wire-shapes.md, ADR-039-trust-boundary-ingress-validation.md
  .claude/skills/adr-constraints/SKILL.md (Layer 3), .claude/skills/code-quality-review/SKILL.md,
    .claude/rules/scala-algebraic-design.md, scala-domain.md, scala-style.md, scala-test.md, security.md
  docs/user/TERMINOLOGY.md:42-120, :150-320, :505-600
  modules/common/src/main/scala/com/risquanter/register/domain/data/DistributionShape.scala
  modules/common/src/main/scala/com/risquanter/register/domain/data/RiskNode.scala
  modules/common/src/main/scala/com/risquanter/register/domain/data/Mitigation.scala
  modules/common/src/main/scala/com/risquanter/register/domain/data/LeafSimContent.scala
  modules/common/src/main/scala/com/risquanter/register/domain/data/Provenance.scala
  modules/common/src/main/scala/com/risquanter/register/domain/data/RiskLeafTransform.scala
  modules/common/src/main/scala/com/risquanter/register/domain/data/iron/OpaqueTypes.scala
  modules/common/src/main/scala/com/risquanter/register/http/requests/DistributionShapeRequest.scala
  modules/common/src/main/scala/com/risquanter/register/http/requests/DistributionPreviewResponse.scala
  modules/common/src/main/scala/com/risquanter/register/http/codecs/IronTapirCodecs.scala
  modules/server/src/main/scala/com/risquanter/register/services/pipeline/InvalidationHandler.scala
  modules/server/src/main/scala/com/risquanter/register/services/helper/Simulator.scala
  modules/server/src/main/scala/com/risquanter/register/services/DistributionPreviewService.scala
  modules/server/src/main/scala/com/risquanter/register/services/cache/MitigationStaleness.scala
  modules/server/src/main/scala/com/risquanter/register/mitigation/MitigationApplication.scala
  modules/server/src/main/scala/com/risquanter/register/services/cache/ContentHashIndex.scala (:1-60)
  modules/server/src/main/scala/com/risquanter/register/repositories/RiskTreeRepositoryIrmin.scala (:270-300)
  the diff hunks of ValidationUtil.scala, ValidationMessages.scala, ResultTransformSpec.scala,
    ValidationErrorCode.scala, RiskTreeServiceLive.scala, CachedResultResolverLive.scala,
    MitigationScopeResolverLive.scala, InvalidationHandlerSpec.scala
discharged:
  1 Adversarial line scan — 1 finding reported (NOTE, DistributionShape.scala:311-316/:344-349,
    location flattened out of the decode-failure string); every other hunk withstood
    input, state and platform variation at the enclosing function
  2 Removed-behaviour audit — PASS — the JSON-bytes comparison of the old
    `nodeContent` is re-established by derived equality over RiskLeaf.scala fields
    (RiskNode.scala:65-74) and RiskPortfolio fields (:242-249), each a value or an
    immutable sequence (DistributionShape.scala:22, :28, :171, :217, :225); the two
    relations differ only on an occurrence probability of -0.0 against 0.0, where the
    new relation reports no change and the simulated figures are also unchanged;
    every flat shape field the old leaf carried is carried by the nested shape
    (DistributionShape.scala:238-244 is the stored form)
  3 Cross-module tracing — PASS — the shape gate is reached on every decode path:
    store read at RiskTreeRepositoryIrmin.scala:293 → RiskNode.scala:175 →
    DistributionShape.scala:352 → :311 / :344; request path at
    DistributionShapeRequest.scala:43-44, :53-54 (with the real subject); mitigation
    severity at RiskLeafTransform.scala:98, :103 → DistributionShape.scala:352; the
    browser form at RiskLeafFormState.scala:400, :409; the cache key at
    LeafSimContent.scala:66-70 reads the same case; the hardcoded
    `ErrorSubject.Request` at DistributionShape.scala:312/:345 is dropped at :315/:348
    before any caller can read it, so it is unobservable
  4 State and interleaving — PASS — the diff touches no `Ref`, fiber or transaction
    boundary; `handleMutation` (InvalidationHandler.scala:137) is a pure function of
    two immutable snapshots and publishes once
  5 Invariant reconstruction — PASS — anchor monotonicity and count are held behind
    the private constructor at DistributionShape.scala:28, :75-100 (`copy` is private
    with it); Kleisli identity and associativity are tested at
    RiskLeafTransformSpec.scala:60-94 and pass; absorption holds per component
    (Mitigation.scala:98-107); cache-key stability pinned at LeafSimContentSpec.scala:32, :46;
    `Expert.create` keeps the term-count-at-most-anchor-count relation (DistributionShape.scala:266-273)
  6 Design altitude — 2 findings reported (NOTE, DistributionShape.scala:205-226 mode
    member beside the derived discriminator; NOTE pre-existing, OpaqueTypes.scala:128/:137
    two aliases of one Iron type); every surviving `if` is a genuine condition
  7 Terminology freshness (meaning level) — PASS — the glossary's "How Parameter-Stage
    Mitigations Compose" (TERMINOLOGY.md:542-596) already states the per-component
    pass-through the code performs; the stale text was the plan's §6.6 algebra paragraph,
    swept; no terminology note owed
  8 Inline verification — the fourth escalation item settled by `sbt commonJVM/test`
    (MitigationEntitySpec "all three spec cases round-trip", "every component case
    round-trips": pass); `sbt 'commonJVM/test; server/test'` pass after this review's
    edits; `app/test` and `serverIt/test` not run by this tier
escalated:
  (none new) — the routine entry's first and second items already name the security tier
```

```
COVERAGE  tier=routine  scope=git diff 4320b698..HEAD (the three §19 commits 519a0616,
          83cf840b, 145d7f24) together with the open working tree (git diff HEAD, 41
          modified tracked files) and the untracked docs/dev/reviews/REVIEW-main.md —
          source 1, named by the user. The two entries above cover 09092a5e..<an earlier
          HEAD>, so their discharges do not reach these three commits; every file was
          re-read.
read-in-full:
  docs/dev/plans/PLAN-LOSS-SHAPE-ALGEBRA.md (§16–§19 in full, §14 and §14.2 for the
    amendment list)
  docs/dev/plans/PLAN-LOSS-SHAPE-ALGEBRA-INVENTORY.md
  docs/dev/reviews/REVIEW-main.md
  CLAUDE.md, ~/.claude/CLAUDE.md
  .claude/rules/scala-algebraic-design.md, scala-domain.md, scala-style.md,
    scala-test.md, laminar.md, security.md
  .claude/skills/code-quality-review/SKILL.md, .claude/skills/adr-constraints/SKILL.md
    (Amending the corpus), .claude/skills/supply-chain/SKILL.md (bump scheme),
    .claude/skills/register-dev/SKILL.md (commands, cleanup),
    ~/.claude/skills/code-comment-style/SKILL.md
  docs/user/TERMINOLOGY.md
  docs/dev/decision-records/ADR-003.md, ADR-037-in-code-constants.md (diff hunks)
  modules/common/src/main/scala/com/risquanter/register/domain/data/ResultTransformSpec.scala
  modules/common/src/main/scala/com/risquanter/register/domain/data/DistributionShape.scala
  modules/common/src/main/scala/com/risquanter/register/domain/data/Mitigation.scala
  modules/common/src/main/scala/com/risquanter/register/domain/data/Provenance.scala
  modules/common/src/main/scala/com/risquanter/register/domain/data/RiskLeafTransform.scala
  modules/common/src/main/scala/com/risquanter/register/domain/data/LeafSimContent.scala
  modules/common/src/main/scala/com/risquanter/register/domain/data/iron/OpaqueTypes.scala
  modules/common/src/main/scala/com/risquanter/register/domain/errors/ErrorLocation.scala
  modules/common/src/main/scala/com/risquanter/register/domain/errors/MergeRefusal.scala
  modules/common/src/main/scala/com/risquanter/register/http/errors/ConflictKind.scala,
    QueryProblem.scala, RejectionDetail.scala
  modules/common/src/main/scala/com/risquanter/register/http/requests/DistributionShapeRequest.scala,
    ScenarioRequests.scala
  modules/common/src/main/scala/com/risquanter/register/http/responses/ScenarioMergeResponse.scala
  modules/common/src/test/scala/com/risquanter/register/http/PublishedDiscriminatorSpec.scala
  modules/common/src/test/scala/com/risquanter/register/testutil/TestHelpers.scala
  modules/app/src/main/scala/app/state/FormMode.scala
  every remaining file in both diffs, as diff hunks plus enclosing functions
discharged:
  1 Plan conformance — 1 finding reported — §19.1's four renamed cases match verbatim at
    DistributionShapeRequest.scala:39-43/:49-52 and Provenance.scala:51-55/:58-62; §19.2's
    discriminator is declared in 18 companions with the key named once per concept at
    DistributionShape.scala:218, Mitigation.scala:133, RiskLeafTransform.scala:148/:152,
    ResultTransformSpec.scala:34, ErrorLocation.scala:13, ConflictKind.scala:53,
    QueryProblem.scala:38, RejectionDetail.scala:32, ScenarioRequests.scala:23,
    ScenarioMergeResponse.scala:48; §19.3's apparatus is gone (no `requireOp` and no `Op`
    object anywhere under modules/); §19.4 is pinned at MitigationEntitySpec.scala:175-183;
    the only surviving @jsonHint pair is LeafSimContent.scala:64/:71, which §18.2 rules
    stays; every gated path in both diffs is a bullet line in the inventory (:10-76) or a
    same-module test source. The finding is §19's absent ADR-alignment item.
  2 Mechanical bug scan — PASS — the five per-case codecs at ResultTransformSpec.scala:61,
    :74, :86, :98, :126 each pair the right field with the right refinement
    (refineNonNegativeLong/Deductible, refineLossCap/Cap, refineRetentionFactor/Factor,
    refineNonNegativeLong/Threshold, both plus InsurancePolicy.create), and the two-sided
    accumulation at :128-135 has no swapped arm; ValidationUtil.scala:343/:357 build the
    wrapper their signatures promise; RiskResultTransform.scala:175-181 reads the fraction
    once and uses it in both the arithmetic and the overflow message
  3 Convention conformance — 2 findings reported, both pre-existing (boyscout) — every
    surviving `if` in the scope is a genuine logical condition
    (DistributionShape.scala:127, RiskResultTransform.scala:180/:203,
    InvalidationHandler.scala:124/:141, IronTapirCodecs.scala:246 and the
    RiskLeafFormState blank/range checks), and no case of a named coproduct is selected by
    equality; three comment-style violations were fixed rather than reported
    (FormMode.scala:18-41, DemoSimpleScriptSpec.scala:19-21, ScenarioControllerSpec.scala:130)
  4 Test presence — PASS — the stored-bytes pin at MitigationEntitySpec.scala:190-196 and
    the schema pin at PublishedDiscriminatorSpec.scala:74-80 are both present; the three
    restated assertions now test what they name — ResultTransformSpecSpec.scala:63/:66/:70
    reject on the refinement rather than on an unknown tag,
    MitigationEntitySpec.scala:166-174 tampers a tag the encoder actually writes, and
    ScenarioControllerSpec.scala:287 sends a decodable source so the 400 comes from the name
    rule; all four tiers pass (commonJVM/test, server/test, app/test, serverIt/test, the
    last after the mandatory register_it_ cleanup)
  5 Drive-by changes — 1 finding reported (NOTE) — every modules/ hunk traces to §16–§19;
    the non-code half of the working tree (CLAUDE.md,
    .claude/skills/code-quality-review/SKILL.md, .claude/skills/register-dev/SKILL.md and
    the deleted PLAN-REVIEW-TIER-PIPELINE.md) is the review-tier protocol workstream, a
    second theme
  6 Boyscout pass, whole-file scope — 13 fixes performed, 2 findings reported — unused
    imports removed in 10 files and comments brought to style in 3; the findings are
    ResultTransformSpec.scala:54 and the unit-fraction predicate restated at
    OpaqueTypes.scala:151/:156, ValidationUtil.scala:342/:357 and TestHelpers.scala:160
    while OpaqueTypes.scala:126 names the type
  7 Terminology freshness (name level) — 1 finding reported, note written — glossary hits
    checked at TERMINOLOGY.md:46 (Loss distribution — states Metalog and Lognormal
    correctly per LSA-D-8), :308 (The four result-stage operations — the finding), :334
    (Deductible, limit, and the three bands), :379 (Relative and absolute statements), :419
    (Precedence), :595 (Alternatives, not optional fields); the pipeline bound 1…10 agrees
    with Mitigation.scala:157/:161
escalated:
  modules/common/src/main/scala/com/risquanter/register/domain/data/Mitigation.scala:133,
  modules/common/src/main/scala/com/risquanter/register/domain/data/RiskLeafTransform.scala:21,
  :59, :90,
  modules/common/src/main/scala/com/risquanter/register/domain/data/ResultTransformSpec.scala:26,
  modules/common/src/main/scala/com/risquanter/register/http/requests/ScenarioRequests.scala:16 —
    twelve declared tag values were dropped, so every serialised spelling changes its
    initial letter and MitigationSpec's third value moves from `result` to `ResultStage`;
    two literal-byte pins stand behind the whole set (wire contracts; persistence shape)
    → complex, security
  modules/common/src/main/scala/com/risquanter/register/domain/data/LeafSimContent.scala:61-72 —
    the cache-key preimage declares `Metalog`/`Lognormal` by hand while DistributionShape
    derives the same two spellings, and the file's own comment states the agreement is a
    fact about today rather than a coupling (identity and equality machinery) → complex
  modules/common/src/main/scala/com/risquanter/register/domain/errors/MergeRefusal.scala:16 —
    MergeRefusal declares no schema of its own, and a probe of `Schema[ConflictKind]` showed
    it inherits `kind` from the `given Configuration` scoped inside ConflictKind.schema's
    body, so a coproduct's published key can come from whichever holder derives it
    (wire contracts) → complex
  modules/common/src/main/scala/com/risquanter/register/domain/data/ResultTransformSpec.scala:146,
  :153 — the parent codec is `DeriveJsonCodec.gen` over a trait whose five cases each supply
    their own `JsonCodec` built on a private `Raw`, while the schema derives from the trait
    itself; round trips pass, but the decode-failure path through a discriminated parent and
    the per-case agreement between the published field list and the encoded bytes were not
    traced (wire contracts) → complex
```

```
COVERAGE  tier=complex  scope=the four escalation items of the routine entry above, inside
          git diff 4320b698..HEAD plus the working tree — source 1, named by the invoking
          session. Whole-file scope on every file an item names.
read-in-full:
  docs/dev/plans/PLAN-LOSS-SHAPE-ALGEBRA.md (§16–§19), PLAN-LOSS-SHAPE-ALGEBRA-INVENTORY.md
  docs/dev/plans/PLAN-ERROR-WIRE-BOUNDARY.md (:1000-1040, :1625-1665, :3870-3925 — EWB-D-25)
  docs/dev/decision-records/ADR-001.md, ADR-010.md, ADR-014.md,
    ADR-032-content-equality-relations.md, ADR-035-error-leakage-prevention.md,
    ADR-037-in-code-constants.md, ADR-038-response-wire-shapes.md,
    ADR-039-trust-boundary-ingress-validation.md
  .claude/skills/adr-constraints/SKILL.md (Layer 3), .claude/skills/code-quality-review/SKILL.md,
    .claude/rules/scala-algebraic-design.md, scala-domain.md, scala-style.md, scala-test.md,
    security.md, CLAUDE.md, ~/.claude/CLAUDE.md
  docs/user/TERMINOLOGY.md (:40-60, :300-430, :590-620, headings)
  docs/dev/reviews/REVIEW-main.md
  modules/common/src/main/scala/com/risquanter/register/domain/data/Mitigation.scala
  modules/common/src/main/scala/com/risquanter/register/domain/data/ResultTransformSpec.scala
  modules/common/src/main/scala/com/risquanter/register/domain/data/RiskLeafTransform.scala
  modules/common/src/main/scala/com/risquanter/register/domain/data/LeafSimContent.scala
  modules/common/src/main/scala/com/risquanter/register/domain/data/DistributionShape.scala
  modules/common/src/main/scala/com/risquanter/register/domain/data/Provenance.scala
  modules/common/src/main/scala/com/risquanter/register/domain/data/LEC.scala
  modules/common/src/main/scala/com/risquanter/register/domain/data/RiskTree.scala (:1-120)
  modules/common/src/main/scala/com/risquanter/register/domain/data/RiskNode.scala (:200-240)
  modules/common/src/main/scala/com/risquanter/register/domain/errors/MergeRefusal.scala
  modules/common/src/main/scala/com/risquanter/register/http/errors/ConflictKind.scala
  modules/common/src/main/scala/com/risquanter/register/http/requests/ScenarioRequests.scala
  modules/common/src/main/scala/com/risquanter/register/http/requests/DistributionShapeRequest.scala
  modules/common/src/main/scala/com/risquanter/register/http/responses/SimulationResponse.scala
  modules/common/src/main/scala/com/risquanter/register/http/codecs/IronTapirCodecs.scala (:95-120, :240-320)
  modules/common/src/test/scala/com/risquanter/register/domain/data/MitigationEntitySpec.scala
  modules/common/src/test/scala/com/risquanter/register/domain/data/LeafSimContentSpec.scala
  modules/common/src/test/scala/com/risquanter/register/domain/data/ResultTransformSpecSpec.scala
  modules/common/src/test/scala/com/risquanter/register/http/PublishedDiscriminatorSpec.scala
  modules/server/src/main/scala/com/risquanter/register/http/WireDecodeFailureHandler.scala
  modules/server/src/main/scala/com/risquanter/register/http/HttpApi.scala
  modules/server/src/main/scala/com/risquanter/register/services/cache/ContentHashIndex.scala
  modules/server/src/main/scala/com/risquanter/register/repositories/RiskTreeRepositoryIrmin.scala (:265-310, mitigation read at :150-171)
  the rendered interface description of every endpoint in WorkspaceTreeEndpoints,
    ScenarioEndpoints, DistributionPreviewEndpoints, WorkspaceLifecycleEndpoints,
    WorkspaceAnalysisEndpoints and WorkspaceQueryEndpoints (OpenAPIDocsInterpreter, probe)
discharged:
  1 Adversarial line scan — 1 finding reported (MUST-FIX, DistributionShape.scala:360-363:
    the published discriminator values are the private raw case names LognormalRaw/MetalogRaw
    while the decoder at :354 reads Lognormal/Metalog; rendered at the ShapeRaw component,
    reached from RiskLeafRaw.shape and AbsoluteReplacement.Severity/Both.shape); every other
    changed line withstood input and platform variation at its enclosing function
  2 Removed-behaviour audit — PASS — each of the twelve deleted @jsonHint spellings is
    re-established by zio-json's derived tag at the case name (Mitigation.scala:201,
    RiskLeafTransform.scala:31/:67/:106, ResultTransformSpec.scala:149,
    ScenarioRequests.scala:28), pinned by literal at MitigationEntitySpec.scala:172/:179-181/:198
    for ResultStage, LeafRelative, LeafAbsolute, Scale and CapLosses; the other nine values
    are held by the shared codec alone (a rename moves both sides, no finding)
  3 Cross-module tracing — PASS — every published holder resolves the companion schema, shown
    by reference identity: Mitigation.schema's spec field is MitigationSpec.schema,
    RiskTree.schema's mitigations element is Mitigation.schema, ConflictKind.schema's refusal
    field is MergeRefusal.schema (the fourth escalation item is closed); the intermediate
    sealed trait LeafStageSpec is flattened by both derivations ("stage":"LeafStageSpec" is an
    invalid disambiguator; the schema lists three subtypes); the decode gate for a stored
    mitigation is RiskTreeRepositoryIrmin.scala:160 → Mitigation.scala:294 → per-case codecs
  4 State and interleaving — PASS — the items touch no Ref, fiber or transaction boundary
  5 Invariant reconstruction — 1 finding reported (SHOULD-FIX, pre-existing: four fields of
    the published Mitigation description disagree with the bytes the codec writes — id,
    target, precedence, ResultStage.pipeline); the cache-key preimage relation of ADR-032 §1
    holds: LeafSimContent.scala:61-72 is the only input to ContentHashIndex.hashOf
    (ContentHashIndex.scala:54-55), the wire spelling never enters it, and
    LeafSimContentSpec.scala:32/:46 pin both modes' bytes
  6 Design altitude — PASS — every if in scope is a comparison (DistributionShape.scala:127,
    ResultTransformSpec.scala:115); the per-case codec pattern matches its precedent
    MitigationSpec.ResultStage exactly
  7 Terminology freshness (meaning level) — PASS — TERMINOLOGY.md:46 (two modes), :308-336
    (five operations, pipeline bound 1–10 matching Mitigation.scala:157/:161), :383-421
    (relative and absolute), :599 (alternatives) all denote what the code holds; no note owed
  8 Inline verification — the derivation facts settled by probe (schema structure, OpenAPI
    rendering, decode-failure strings, tapir Configuration.toDiscriminatorValue and nested
    private cases as fix options); commonJVM/test, server/test, app/test and serverIt/test
    (after the register_it_ cleanup) all pass
escalated:
  (none new) — the routine entry's first item already names the security tier
```
