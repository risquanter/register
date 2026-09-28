# File inventory — PLAN-FBF-VALUATION-TRANSPLANT.md

The approval hook reads this file to decide whether a gated edit is
permitted. A gated edit is allowed only when the edited file appears on a
bullet line below, written as a full repo-relative path. Text that is not a
bullet line authorizes nothing.

Only the user writes this file, through `.claude/bin/approve-inventory`.

- modules/server/src/main/scala/com/risquanter/register/simulation/LossDistribution.scala
- modules/server/src/main/scala/com/risquanter/register/services/cache/NodeLosses.scala
- modules/server/src/main/scala/com/risquanter/register/mitigation/MitigationApplication.scala
- modules/server/src/main/scala/com/risquanter/register/mitigation/RiskResultTransform.scala
- modules/server/src/main/scala/com/risquanter/register/services/cache/CachedResultResolver.scala
- modules/server/src/main/scala/com/risquanter/register/services/cache/CachedResultResolverLive.scala
- modules/server/src/main/scala/com/risquanter/register/services/helper/Simulator.scala
- modules/server/src/main/scala/com/risquanter/register/simulation/LECGenerator.scala
- modules/common/src/main/scala/com/risquanter/register/domain/data/Provenance.scala
- modules/common/src/main/scala/com/risquanter/register/domain/data/LEC.scala
- modules/common/src/main/scala/com/risquanter/register/domain/data/Mitigation.scala
- modules/common/src/main/scala/com/risquanter/register/domain/data/iron/ValidationMessages.scala
- build.sbt
