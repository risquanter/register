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

```
COVERAGE  tier=security  scope=the plan documents and the glossary the range 67debe49..e33c223c
          on main changed — source 1, named by the user ("on the plans scope"); the working
          tree was clean, so the range resolved the scope. Whole-document scope on every file.
read-in-full:
  docs/dev/plans/PLAN-LOSS-SHAPE-ALGEBRA.md, PLAN-LOSS-SHAPE-ALGEBRA-INVENTORY.md,
    PLAN-RISKTRANSFORM.md, PLAN-ERROR-WIRE-BOUNDARY.md, PLAN-PROVENANCE-ENDPOINT.md,
    PLAN-CONTROL-GROUPING.md, PLAN-MITIGATION-ROI.md, PLAN-VALIDATION-IDIOM.md,
    IMPLEMENTATION-PLAN.md
  docs/user/TERMINOLOGY.md
  docs/dev/reviews/REVIEW-main.md, docs/dev/TODO.md
  .claude/rules/security.md, .claude/skills/code-quality-review/SKILL.md,
    .claude/skills/supply-chain/SKILL.md, .claude/skills/adr-constraints/SKILL.md,
    CLAUDE.md, ~/.claude/CLAUDE.md
  docs/dev/decision-records/ADR-017, ADR-021, ADR-022, ADR-024, ADR-029, ADR-030, ADR-034,
    ADR-035, ADR-036, ADR-038, ADR-039
  modules/common/src/main/scala/com/risquanter/register/http/endpoints/WorkspaceAnalysisEndpoints.scala
  modules/common/src/main/scala/com/risquanter/register/domain/errors/MergeRefusal.scala
  modules/common/src/main/scala/com/risquanter/register/domain/errors/ErrorLocation.scala
  modules/common/src/main/scala/com/risquanter/register/http/requests/DistributionShapeRequest.scala
  modules/server/src/main/scala/com/risquanter/register/http/RequestSpanInterceptor.scala
  modules/server/src/main/scala/com/risquanter/register/http/sse/SSEController.scala (:50-92)
  modules/server/src/main/scala/com/risquanter/register/services/ScenarioMergeService.scala (:245-275)
  modules/server/src/main/scala/com/risquanter/register/mitigation/MitigationApplication.scala (:57-84)
  modules/server/src/main/scala/com/risquanter/register/Application.scala (:355-370)
  zio-json 0.8.0 ast.scala (:445-456, :524-530, :594-613) and ZIO 2.1.24 FiberRuntime.scala
    (:468-470, :686-690) and RuntimePlatformSpecific.scala (:37-44), from the coursier
    sources jars — third-party, no sibling checkout
discharged:
  1 Project checklist — 2 findings reported — the hard rule on WorkspaceId holds in every
    specified wire type (WireError.scala as specified in PLAN-ERROR-WIRE-BOUNDARY §3;
    ErrorLocation.scala:37-71 declares no BranchRef and no storage path); R1-R8 unchanged,
    no plan introduces a credential type
  2 Input boundaries and injection — 1 finding reported (MUST-FIX, PLAN-ERROR-WIRE-BOUNDARY
    §5.4 RequestBodyCodec: an untyped AST parse ahead of the typed decode); every other
    parser boundary the plans specify is one ADR-029 §3 already lists, and no plan builds a
    parser input by interpolation
  3 Authentication, authorization, tenancy — PASS — every specified endpoint binds
    `given Checked[Permission]` before the service call and resolves the tree through
    `workspaceStore.resolveTreeWorkspace(key, treeId)` (PLAN-PROVENANCE-ENDPOINT Phase 2;
    PLAN-RISKTRANSFORM §7.6.5, §7.6.7), and the selection names MitigationId values that are
    looked up inside the already-resolved tree (§7.6.5 requireKnownMitigations); the SSE
    revocation gap is reported as pre-existing against the shipped controller
    (SSEController.scala:58-62)
  4 Web API surface — 2 findings reported — the selection bounds are specified as decoder
    smart constructors, which is the ADR-017 §6 vehicle, and are not implemented
    (AnalysisRequests.scala absent; WorkspaceAnalysisEndpoints.scala:48 still
    `jsonBody[List[NodeId]]`, TODO item 40 open); `Schema.any` on the three request types
    in §7.6.5 contradicts §7.6.6 and ADR-038
  5 SPA output paths — PASS — no plan specifies an output path other than Laminar's typed
    interface; no plan persists anything in browser storage (grep of modules/app for
    localStorage/sessionStorage/indexedDB/cookie is empty)
  6 Distributed and asynchronous computation — 1 finding reported (NOTE, the uncached
    double resolution on the write path in PLAN-RISKTRANSFORM §7.6.8); the scope memo key
    carries (TreeId, BranchRef, CommitHash) inside a per-WorkspaceId instance (§8.13); the
    staged merge publishes under compare-and-set (§8.19.9)
  7 Secrets, credentials, errors, logging — PASS — the key crosses in the two minting
    responses only (WorkspaceResponse.scala:8, :18); the span name and the server log read one
    redacted definition (RequestSpanInterceptor.scala:89-94, Application.scala:361-364); the
    merge guard logs the repository's reason and sends the tree ids alone
    (ScenarioMergeService.scala:256-259, MergeRefusal.scala:36)
  8 Containers and pipeline — not applicable — no document in scope specifies a container,
    compose, entrypoint or workflow change
  9 Supply chain — not applicable — no document in scope specifies a dependency change;
    the one version change in the range is the PATCH bump in build.sbt
  10 Inline verification — the depth recursion and the memory amplification are read from
    zio-json's and ZIO's own sources and not executed; the single check is named in the
    finding
escalated:
  (none) — every item needing deeper analysis is reported here with its verdict; the
  items needing a ruling are in the decision guides
```

```
COVERAGE  tier=security  scope=the source files PLAN-LOSS-SHAPE-ALGEBRA touched — the union of
          `git diff 4320b698..HEAD --name-only -- modules/` and the bullet lines of
          PLAN-LOSS-SHAPE-ALGEBRA-INVENTORY.md — with the routine entry's first escalation
          item (the twelve derived discriminator tags) as the focus; source 1, named by the
          user. Whole-file scope on every file. The inventory names
          modules/common/src/main/scala/com/risquanter/register/domain/data/Distribution.scala,
          which does not exist.
read-in-full:
  .claude/rules/security.md, .claude/skills/code-quality-review/SKILL.md,
    .claude/skills/supply-chain/SKILL.md, .claude/skills/adr-constraints/SKILL.md,
    CLAUDE.md, ~/.claude/CLAUDE.md, docs/dev/TODO.md (headings; items 15, 40-43, 46, 60, 62)
  docs/dev/decision-records/ADR-001.md, ADR-001-appendix.md, ADR-010.md, ADR-017-tree-api-design.md,
    ADR-017-NOTES.md, ADR-022, ADR-029, ADR-032, ADR-034, ADR-035, ADR-036, ADR-037, ADR-038, ADR-039
  docs/dev/reviews/REVIEW-main.md; docs/dev/plans/PLAN-RISKTRANSFORM.md (:2814-2838);
    docs/dev/plans/PLAN-ERROR-WIRE-BOUNDARY.md (:2671, :5347)
  modules/common/src/main/scala/com/risquanter/register/domain/data/Mitigation.scala,
    RiskLeafTransform.scala, ResultTransformSpec.scala, DistributionShape.scala,
    LeafSimContent.scala, Provenance.scala, RiskNode.scala, RiskTree.scala
  modules/common/src/main/scala/com/risquanter/register/domain/data/iron/OpaqueTypes.scala,
    ValidationMessages.scala, ValidationUtil.scala
  modules/common/src/main/scala/com/risquanter/register/domain/errors/ErrorLocation.scala,
    MergeRefusal.scala, ValidationErrorCode.scala
  modules/common/src/main/scala/com/risquanter/register/http/codecs/IronTapirCodecs.scala
  modules/common/src/main/scala/com/risquanter/register/http/errors/ConflictKind.scala,
    QueryProblem.scala, RejectionDetail.scala
  modules/common/src/main/scala/com/risquanter/register/http/requests/ScenarioRequests.scala,
    DistributionShapeRequest.scala, DistributionPreviewResponse.scala, RiskTreeRequests.scala,
    RiskTreeUpdateRequest.scala, RiskTreeDefinitionRequest.scala
  modules/common/src/main/scala/com/risquanter/register/http/endpoints/DistributionPreviewEndpoints.scala
  modules/common/src/main/scala/com/risquanter/register/http/responses/ScenarioMergeResponse.scala
  modules/server/src/main/scala/com/risquanter/register/mitigation/RiskResultTransform.scala,
    MitigationApplication.scala, ResultTransformInterpreter.scala
  modules/server/src/main/scala/com/risquanter/register/services/DistributionPreviewService.scala,
    RiskTreeServiceLive.scala, ScenarioServiceLive.scala,
    ScenarioMergeService.scala (:268-330), TreeHistoryService.scala (grep)
  modules/server/src/main/scala/com/risquanter/register/services/cache/MitigationStaleness.scala,
    MitigationScopeResolverLive.scala, CachedResultResolverLive.scala, ContentHashIndex.scala
  modules/server/src/main/scala/com/risquanter/register/services/helper/Simulator.scala
  modules/server/src/main/scala/com/risquanter/register/services/pipeline/InvalidationHandler.scala
  modules/server/src/main/scala/com/risquanter/register/simulation/MetalogDistribution.scala
  modules/server/src/main/scala/com/risquanter/register/repositories/RiskTreeRepositoryIrmin.scala
  modules/server/src/main/scala/com/risquanter/register/http/WireDecodeFailureHandler.scala
  modules/server/src/main/scala/com/risquanter/register/http/controllers/DistributionPreviewController.scala,
    ScenarioController.scala (:47-49), WorkspaceAnalysisController.scala (grep)
  modules/app/src/main/scala/app/chart/DistributionSpecBuilder.scala, components/TreeNodeRow.scala,
    state/DistributionMode.scala, state/FormMode.scala, state/RiskLeafFormState.scala,
    state/TreeBuilderState.scala, state/DistributionChartState.scala,
    views/RiskLeafFormView.scala, views/TreePreview.scala, views/DistributionChartView.scala,
    views/TreeDetailView.scala
  modules/common/src/test/scala/com/risquanter/register/http/PublishedDiscriminatorSpec.scala,
    domain/data/MitigationEntitySpec.scala (:150-220), domain/data/LeafSimContentSpec.scala (:20-60)
  build.sbt (diff: version only); the remaining test files in the diff as a grep for
    realistic secrets and for removed assertions
  zio-json 0.8.0 macros.scala (:585-704, the disambiguator and missing-hint refusals) from the
    coursier sources jar — third-party, no sibling checkout
discharged:
  1 Project checklist — PASS — WorkspaceId has no codec and no schema (OpaqueTypes.scala:951-953),
    so no derived schema in scope can publish it; ErrorLocation.scala:37-44 declares no BranchRef
    case; MergeConflictEntry.path is workspace-relative (ScenarioMergeResponse.scala:9-15);
    R1-R8 unchanged at OpaqueTypes.scala:849-869; no new credential type
  2 Input boundaries and injection — PASS — every discriminated coproduct decodes through a
    per-case refining codec (ResultTransformSpec.scala:68-144, Mitigation.scala:201-208 and
    :306-315, DistributionShape.scala:315-325 and :348-358, OpaqueTypes.scala:154-162 and
    :714-716); an unknown or absent tag is refused by zio-json (macros.scala:585, :650); the
    only parser inputs in scope are the targeting predicate, re-run through
    TargetingPredicate.create on decode (Mitigation.scala:23-24), and the Irmin documents,
    whose interpolated values are refined (ADR-029 §3)
  3 Authentication, authorization, tenancy — 2 findings reported (SHOULD-FIX pre-existing:
    RiskTreeService.probOfExceedance and getLECCurvesMulti carry no `using Checked[Permission]`
    while the five mutating methods do, RiskTreeServiceLive.scala:441 and :462; NOTE
    pre-existing: ScenarioSource.AtCommit forks at any commit in the shared store,
    ScenarioServiceLive.scala:83-94); no request body in scope carries a Mitigation
    (endpoint grep; RiskTreeServiceLive.scala:374-380 carries stored mitigations over), so no
    mitigation arm is caller-selectable; LeafAbsolute.anchor resolves only inside the loaded
    tree (MitigationStaleness.scala:26-27)
  4 Web API surface — PASS — a body decode failure answers "body could not be read" and never
    the zio-json text (WireDecodeFailureHandler.scala:134-135, :156-159); every bound a request
    carries is in a type or a gate (QuantileAnchors 2..20 at DistributionShape.scala:61-62,
    MetalogTermCount 2..20, pipeline 1..10 at Mitigation.scala:168-172, 1 000 mitigations and
    10 000 nodes at RiskTree.scala:106/:169); the unbounded List[NodeId] body is TODO 40
  5 SPA output paths — PASS — every render site is Laminar's typed interface; the one
    non-text sink is `styleAttr` built from HexColor, whose refinement is
    `^#[0-9a-fA-F]{6}$` (TreeDetailView.scala:83, :318; OpaqueTypes.scala:1011), which is
    ADR-029 §2's approved alternative; no browser storage (grep of modules/app empty)
  6 Distributed and asynchronous computation — PASS — the cache key's only input is
    LeafSimContent's own bytes (ContentHashIndex.scala:50-51), the mode spelling is declared
    there by hand (LeafSimContent.scala:61-72) and pinned (LeafSimContentSpec.scala:32, :46,
    pass); leaf-stage mitigations are baked into the hashed tree
    (CachedResultResolverLive.scala:69-73) and the cache is per workspace (:72); the scope memo
    serves an entry only on an exact revision match (MitigationScopeResolverLive.scala:38-39)
  7 Secrets, credentials, errors, logging — PASS — the fitter's own text is logged and never
    sent (Simulator.scala:121-130, :195-203); a stored-value refusal is a typed
    RepositoryFailure (RiskTreeRepositoryIrmin.scala:160) projected to InternalFault; the
    WorkspaceId in those reasons stays server-side (ADR-036 §3); no realistic secret in any
    touched fixture (grep empty)
  8 Containers and pipeline — not applicable — no file in scope is a container, compose,
    entrypoint or workflow definition
  9 Supply chain — not applicable — the only build.sbt hunk is the version bump
    0.10.59 → 0.11.4; no dependency changed
  10 Inline verification — the escalated item settled by executing
    `sbt commonJVM/testOnly *PublishedDiscriminatorSpec *MitigationEntitySpec *LeafSimContentSpec
    *ResultTransformSpecSpec` (pass): the published mode values are Lognormal/Metalog
    (PublishedDiscriminatorSpec.scala:97-99), so the complex tier's MUST-FIX on the private raw
    case names is closed at DistributionShape.scala:243-258; the other tiers were not re-run
escalated:
  (none)
```

```
COVERAGE  tier=routine  scope=commits 141300d1 and 4acbfc9f (git diff 141300d1~1 4acbfc9f
          -- modules/ docs/dev/decision-records/), named by the user (File scope source 1);
          plan documents excluded as targets
read-in-full:
  modules/app/src/main/scala/app/state/FormState.scala
  modules/app/src/main/scala/app/state/ParentSelection.scala
  modules/app/src/main/scala/app/state/PortfolioFormState.scala
  modules/app/src/main/scala/app/state/RiskLeafFormState.scala
  modules/app/src/main/scala/app/state/TreeBuilderState.scala
  modules/app/src/main/scala/app/views/PortfolioFormView.scala
  modules/app/src/main/scala/app/views/RiskLeafFormView.scala
  modules/app/src/test/scala/app/state/FormModeSpec.scala
  modules/app/src/test/scala/app/state/PortfolioFormStateSpec.scala
  modules/app/src/test/scala/app/state/RiskLeafFormStateSpec.scala
  modules/app/src/test/scala/app/views/TreePreviewSpec.scala
  modules/common/src/main/scala/com/risquanter/register/domain/data/iron/ValidationMessages.scala
  docs/dev/decision-records/ADR-001.md
  docs/dev/decision-records/ADR-019-frontend-component-architecture.md
discharged:
  1 plan conformance — 1 finding (tree-name live check still Signal[Option[String]],
    TreeBuilderState.scala:128-129) — echoes matched at FormState.scala:27,132-143,
    ParentSelection.scala:51-58, RiskLeafFormState.scala:177-188,457-495,522-528,
    TreeBuilderState.scala:16,23,217,233,487-488; inventory checked against
    PLAN-ERROR-WIRE-BOUNDARY-INVENTORY.md (ADR-001.md not on it)
  2 mechanical bug scan — 1 finding (terms live/submit sentences diverge,
    RiskLeafFormState.scala:274-285 vs DistributionShape.scala:279,299)
  3 convention conformance — 3 findings (ADR-019 GOOD example, RiskLeafFormView.scala:57-75
    .now() in a signal map, RiskLeafFormView.scala:414 "80% CI"); if/else->match converted at
    RiskLeafFormView.scala and PortfolioFormView.scala Templating arms
  4 test presence — PASS after pinning tests added to TreeBuilderStateSpec.scala (subject from
    populate*Form, tree-name refusal location); app/test, commonJVM/test, server/test,
    serverIt/test pass
  5 drive-by changes — 1 finding (ADR-001.md example rewrite)
  6 boyscout pass — performed: stale and history comments in RiskLeafFormState.scala,
    TreeBuilderState.scala, RiskLeafFormView.scala, PortfolioFormView.scala; two matches
  7 terminology freshness — PASS — no new name in the diff names a concept that has a
    heading in docs/user/TERMINOLOGY.md; §1.1 "a number between 0 and 1" and §1.4 "one term
    per anchor" agree with RiskLeafFormState.scala:24,61
escalated:
  modules/app/src/main/scala/app/state/RiskLeafFormState.scala:522-528 + RiskLeafFormView.scala
    Templating arm — subject now feeds draftSignal; populate sets ExistingNode then the view
    resets to DraftNode, so draftSignal re-emits into currentDraftVar (subscription ordering)
    → complex
  modules/app/src/main/scala/app/state/RiskLeafFormState.scala, FormState.scala:132-143 —
    refusal codes change (blank probability INVALID_FORMAT->REQUIRED_FIELD, unreadable bound
    REQUIRED_FIELD->TYPE_MISMATCH) and edit-path subject DraftNode->ExistingNode
    (changed error-path semantics) → complex
```

```
COVERAGE  tier=complex  scope=the routine tier's two escalated items for commits 141300d1
          and 4acbfc9f, plus commit 1f5943c0 (the fixes to the routine findings), named by
          the user (File scope source 1); plan documents read as the oracle only
read-in-full:
  modules/app/src/main/scala/app/state/FormState.scala
  modules/app/src/main/scala/app/state/Located.scala
  modules/app/src/main/scala/app/state/RiskLeafFormState.scala
  modules/app/src/main/scala/app/state/PortfolioFormState.scala
  modules/app/src/main/scala/app/state/TreeBuilderState.scala
  modules/app/src/main/scala/app/state/ParentSelection.scala
  modules/app/src/main/scala/app/state/FormMode.scala
  modules/app/src/main/scala/app/state/DistributionChartState.scala
  modules/app/src/main/scala/app/views/RiskLeafFormView.scala
  modules/app/src/main/scala/app/views/PortfolioFormView.scala
  modules/app/src/main/scala/app/views/FormSubmitUtil.scala
  modules/app/src/main/scala/app/components/FormInputs.scala
  modules/app/src/test/scala/app/state/RiskLeafFormStateSpec.scala
  modules/app/src/test/scala/app/views/FormErrorRoutingSpec.scala
  (in part) modules/app/src/main/scala/app/views/DistributionChartView.scala:25-124,
    modules/common/.../domain/data/DistributionShape.scala:56-360,
    modules/common/.../domain/data/iron/ValidationUtil.scala:318-338,
    modules/common/.../domain/errors/ErrorLocation.scala:1-80,127-141,219,
    modules/app/src/test/scala/app/state/TreeBuilderStateSpec.scala:1-130,600-660,
    PLAN-ERROR-WIRE-BOUNDARY.md §15.4, §15.5a-15.8, §13:5785-5848, EWB-D-25, EWB-D-38
discharged:
  1 adversarial line scan — 2 findings (terms-overflow TYPE_MISMATCH and the flatMap
    suppression, RiskLeafFormState.scala:274-279,484-491; live terms check counts
    percentiles where the gate counts pairs, RiskLeafFormState.scala:297 vs
    DistributionShape.scala:273-281)
  2 removed-behaviour audit — PASS — minMetalogTerms replaced by refineMetalogTermCount
    (RiskLeafFormState.scala:285); if/else→match on ParentRef exhaustive over its two cases
    (RiskTreeRequests.scala:29-32); currentSnapshot's combined values equal the .now()
    reads it replaced (RiskLeafFormView.scala:54-59); treeNameErrorRaw's message mapping
    kept (TreeBuilderState.scala:128-135)
  3 cross-module tracing — PASS — browser refusals never leave the browser; the only
    readers of a refusal are FormSubmitUtil.routeErrors (location + message,
    FormSubmitUtil.scala:28-35) and the two inputFor mappings, which match FieldName and
    discard the subject (RiskLeafFormView.scala:491-505, PortfolioFormView.scala:332-336);
    no consumer in modules/app/src/main reads ValidationError.code. Controls reached on every
    submit path: the refinement boundary toDraft (RiskLeafFormState.scala:523-529) from
    handleSubmit (RiskLeafFormView.scala:436) and handleUpdate (:460); the shape gate
    shapeFrom → DistributionShape.Metalog/Lognormal.fromPrimitives
    (RiskLeafFormState.scala:484-500) from toDraft and from draftSignal (:540-546);
    PortfolioFormState.toDraft (PortfolioFormState.scala:70-75) from
    PortfolioFormView.handleSubmit (:285) and handleUpdate (:305)
  4 state and interleaving model — PASS, 1 pre-existing note — the subject enters
    draftSignal four times and combineWith emits once per transaction; the emitted
    Option[DistributionShape] is independent of the subject (pinned,
    RiskLeafFormStateSpec "the subject does not change the shape it carries"); writes
    issued inside the Templating observer apply in issue order so DraftNode is the final
    subject (pinned, TreeBuilderStateSpec "a template populated from inside an observer
    ends on the draft node"); every emission during a populate lands in currentDraftVar and
    is collapsed by the 400 ms debounce (DistributionChartView.scala:74-83), so one fetch
    of the final fields; no stale fetch, no double fetch. Pre-existing: equal values
    re-emitted into currentDraftVar re-embed the chart and refetch after a submit
  5 invariant reconstruction — PASS — live and submit refuse the same input with the same
    sentence for probability, bounds and terms (pinned; the terms case now covers below
    range, above anchor count, unreadable); every refusal reaches its field's slot or the
    banner by location alone; pair locations reach the banner until plan step 4
    (§15.8 step 2 states this)
  6 design altitude — 2 notes (positional FieldSnapshot.LeafFields construction,
    RiskLeafFormView.scala:57-58; FormInputs.fromValue if-chain on named constants,
    FormInputs.scala:263-266, pre-existing)
  7 terminology freshness (meaning level) — PASS — §1.4 "90% confidence interval" and
    "stating none means one term per anchor" agree with RiskLeafFormView.scala:402 and
    RiskLeafFormState.scala:274-275; no denotation changed
  8 inline verification — app/test pass after the three pinning tests and one
    strengthened test; the Airstream re-emission premise behind the pre-existing note is
    unverified and named in the finding
escalated:
  (none)
```

```
COVERAGE  tier=routine  scope=the frontend commits after 4acbfc9f up to HEAD
          (1f5943c0, 00645acb, eb412b6a, a758940e, c0be2ece, 75227515, 10b5365a),
          df4b0f4c excluded as a server change — File scope source 1, named by the user
read-in-full:
  modules/app/src/main/scala/app/components/BranchBar.scala
  modules/app/src/main/scala/app/components/FailureNotice.scala
  modules/app/src/main/scala/app/components/FormInputs.scala
  modules/app/src/main/scala/app/components/MergeMessages.scala
  modules/app/src/main/scala/app/components/MergeModal.scala
  modules/app/src/main/scala/app/core/ZJS.scala
  modules/app/src/main/scala/app/state/FormState.scala
  modules/app/src/main/scala/app/state/PortfolioFormState.scala
  modules/app/src/main/scala/app/state/RiskLeafFormState.scala
  modules/app/src/main/scala/app/state/TreeBuilderState.scala
  modules/app/src/main/scala/app/views/BlockedMessages.scala
  modules/app/src/main/scala/app/views/ErrorBanner.scala
  modules/app/src/main/scala/app/views/ErrorMessages.scala
  modules/app/src/main/scala/app/views/FormSubmitUtil.scala (deleted; read at 10b5365a^)
  modules/app/src/main/scala/app/views/PortfolioFormView.scala
  modules/app/src/main/scala/app/views/RiskLeafFormView.scala
  modules/app/src/main/scala/app/views/TreeBuilderView.scala
  modules/app/styles/app.css (diff hunks and the rules they name)
  modules/common/src/main/scala/com/risquanter/register/domain/errors/ErrorLocation.scala (hunk)
  scanned by pattern, not line by line: AnalyzeView.scala, DistributionChartView.scala,
    LECChartView.scala, TreeDetailView.scala, TreeListView.scala, the three in-scope specs
discharged:
  1 plan conformance — PASS — SubmitRefusals, inputsFor, topologyError, draftEdits,
    blockable, FailureNotice match §15.5a and §15.8 step 5; every changed file is a
    bullet in PLAN-ERROR-WIRE-BOUNDARY-INVENTORY.md (tests by the same-module rule);
    §9 Phase 3a summary swept to EWB-D-43
  2 mechanical bug scan — 1 finding — RiskLeafFormView.scala:234-241 (no clearFor(Terms))
  3 convention conformance — 2 findings — RiskLeafFormView.scala:428,449 and
    PortfolioFormView.scala:281,300 (nested Validation match); FormState.scala:87 with
    RiskLeafFormState.scala:471 and PortfolioFormState.scala:84 (hasErrors/isValid no caller)
  4 test presence — 1 gap closed (ErrorMessagesSpec viewFailedLines); app/test pass,
    commonJVM/test pass
  5 drive-by changes — 1 note — app.css .error-banner border
  6 boyscout pass — performed; notes on MergeModal.scala:38,99,107 and Option.when sites
  7 terminology freshness (name level) — PASS — no glossary heading names a concept in
    the diff; SubmitRefusals.selected is a homonym of §4.5 Selection, not that concept
escalated:
  modules/app/src/main/scala/app/views/TreeBuilderView.scala:139 — draftEdits.sample(submitState)
    clears a Failed state and relies on onSuccess setting Success before startNewTree /
    selectTree write the draft (subscription ordering) → complex
  modules/app/src/main/scala/app/core/ZJS.scala:286 — submitInto notifies the observer by
    hand and withholds ValidationFailed from the banner (changed error-path semantics) → complex
  modules/app/src/main/scala/app/components/FailureNotice.scala:23,
    modules/app/src/main/scala/app/components/FormInputs.scala:230 — new Laminar render sites
    carrying server sentences and reasons (SPA output path) → security
```

```
COVERAGE  tier=complex  scope=the routine tier's escalation list for the frontend commits
          after 4acbfc9f (1f5943c0, 00645acb, eb412b6a, a758940e, c0be2ece, 75227515,
          10b5365a, and the follow-up 3ca530f2 on disk) — File scope source 1, named by
          the user
read-in-full:
  modules/app/src/main/scala/app/views/TreeBuilderView.scala
  modules/app/src/main/scala/app/core/ZJS.scala
  modules/app/src/main/scala/app/state/TreeBuilderState.scala
  modules/app/src/main/scala/app/state/TreeViewState.scala
  modules/app/src/main/scala/app/state/TreeLoadPolicy.scala
  modules/app/src/main/scala/app/state/SubmitState.scala
  modules/app/src/main/scala/app/state/GlobalError.scala
  modules/app/src/main/scala/app/state/FormState.scala
  modules/app/src/main/scala/app/state/WorkspaceState.scala
  modules/app/src/main/scala/app/views/DesignView.scala
  modules/app/src/main/scala/app/views/ErrorBanner.scala
  modules/app/src/main/scala/app/views/ErrorMessages.scala
  modules/app/src/main/scala/app/views/PortfolioFormView.scala
  modules/app/src/main/scala/app/views/RiskLeafFormView.scala
  modules/app/src/main/scala/app/views/BlockedMessages.scala
  modules/app/src/main/scala/app/components/FormInputs.scala
  modules/app/src/main/scala/app/components/FailureNotice.scala
  modules/app/src/main/scala/app/core/ViewFailure.scala
  modules/app/src/main/scala/app/Main.scala
  modules/app/src/main/scala/app/components/AppShell.scala (lines 24-77, the view switch)
  modules/app/src/test/scala/app/state/TreeBuilderStateSpec.scala (tail; two tests added)
  library, from the airstream 17.2.0 sources jar (no sibling checkout exists):
    core/Transaction.scala, state/Var.scala:26-36, state/SourceVar.scala,
    misc/StreamFromSignal.scala, combine/SampleCombineStreamN.scala,
    combine/MergeStream.scala, core/Signal.scala:86
  plan: PLAN-ERROR-WIRE-BOUNDARY.md §7.4, §9 Phase 3a, EWB-D-42, EWB-D-43, §15.5a, §15.8
  records: ADR-010, ADR-019, ADR-033, ADR-035
discharged:
  1 adversarial line scan — PASS on the two escalated items; 1 finding on the bootstrap
    path (TreeBuilderView.scala:107 with WorkspaceState.scala:131-140, `.runJs` notifies
    the observer, so a server field refusal on the first Create shows in the banner and
    on the builder's line)
  2 removed-behaviour audit — PASS — Failed(FormInvalid) replaced by treeNameError +
    topologyError, both gated by triggerValidation (TreeBuilderState.scala:133,142-149);
    the name-edit clear replaced by draftEdits (TreeBuilderState.scala:152-157) and no
    longer clears Submitting; forkProvided's automatic onError/onSuccess re-established
    by hand at ZJS.scala:290-295 for every failure GlobalError.from does not classify
    as ValidationFailed, and for every success; recordToConsole still runs for every
    failure (ZJS.scala:63)
  3 cross-module tracing — PASS — submitInto has two callers, both in
    TreeBuilderView.scala:95,104; the withholding reaches no other view. Error path
    reached on every submit: ZJS.scala:291-296 (Failed set, then the observer decision)
    from :95 and :104; TreeBuilderView.scala:107 (bootstrap failure → Failed) and :91
    (no workspace → Failed). Render site for every Failed: TreeBuilderView.scala:69-72 →
    :175-178 (FailureNotice.lines) via ErrorMessages.viewFailedLines (:117-125). Banner
    writer: Main.scala:101-106
  4 state and interleaving model — PASS on item 1 — Var.set opens its own transaction
    and a set issued inside an observer runs after the current transaction, depth-first
    (Transaction.scala pendingTransactions.add/putNextTransactionOnStack); a set issued
    from a ZIO callback runs to completion before the next statement; sample reads the
    sampled signal's tryNow() at the sampling emission (SampleCombineStreamN); changes
    emits nothing on first start (StreamFromSignal onStart, isFirstPull && changesOnly)
    and the view is never remounted (AppShell.scala:77 toggles display). Hence Success
    set at TreeBuilderView.scala:49 is what every draft write of startNewTree (:51) and
    of loadFromTree (via DesignView.scala:132 → :52) samples. Pinned in
    TreeBuilderStateSpec "draft edits". 1 pre-existing finding: draft writes accepted
    while a submit is in flight are overwritten by onSuccess (TreeBuilderView.scala:51,
    :55-58 with TreeLoadPolicy.scala:41-42)
  5 invariant reconstruction — PASS — submitState has one owner (TreeBuilderView) plus
    submitInto; every Failed renders; a failure the view renders is also sent to the
    banner except a field refusal (see finding 2, pre-existing and codebase-wide:
    loadInto ZJS.scala:187-193 does the same)
  6 design altitude — 1 note (submitInto re-implements forkProvided's notification
    instead of forkProvided taking a predicate, ZJS.scala:61,286-298); if/else pass —
    TreeLoadPolicy.scala:41-46 three-way if/else-if producing an enum (pre-existing)
  7 terminology freshness (meaning level) — PASS — no glossary heading denotes a
    submit, a refusal's display, or the banner; §8 trace handling unchanged
  8 inline verification — app/test pass with the two pinning tests added
escalated:
  (none beyond the routine tier's security item, which stands: FailureNotice.scala:23,
  FormInputs.scala:230)
```
