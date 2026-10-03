# File inventory — PLAN-LOGNORMAL-BOUND-POSITIVITY.md

The approval hook reads this file to decide whether a gated edit is
permitted. A gated edit is allowed only when the edited file appears on a
bullet line below, written as a full repo-relative path. Text that is not a
bullet line authorizes nothing.

Only the user writes this file, through `.claude/bin/approve-inventory`.

- modules/common/src/main/scala/com/risquanter/register/domain/data/iron/OpaqueTypes.scala
- modules/common/src/main/scala/com/risquanter/register/domain/data/iron/ValidationUtil.scala
- modules/common/src/main/scala/com/risquanter/register/domain/data/iron/ValidationMessages.scala
- modules/common/src/main/scala/com/risquanter/register/domain/data/RiskNode.scala
- modules/common/src/main/scala/com/risquanter/register/domain/data/Distribution.scala
- modules/common/src/main/scala/com/risquanter/register/domain/data/RiskLeafTransform.scala
- modules/common/src/main/scala/com/risquanter/register/domain/data/ResultTransformSpec.scala
- modules/common/src/main/scala/com/risquanter/register/domain/data/Provenance.scala
- modules/common/src/main/scala/com/risquanter/register/domain/data/LeafSimContent.scala
- modules/common/src/test/scala/com/risquanter/register/domain/data/iron/ValidationUtilSpec.scala
- modules/common/src/test/scala/com/risquanter/register/domain/data/RiskLeafSpec.scala
- modules/common/src/test/scala/com/risquanter/register/domain/data/RiskLeafTransformSpec.scala
- modules/common/src/test/scala/com/risquanter/register/domain/data/LeafSimContentSpec.scala
- modules/common/src/test/scala/com/risquanter/register/domain/data/DistributionSpec.scala
- modules/server/src/main/scala/com/risquanter/register/mitigation/RiskResultTransform.scala
- modules/server/src/test/scala/com/risquanter/register/domain/data/ProvenanceSpec.scala
- modules/server/src/test/scala/com/risquanter/register/mitigation/RiskResultTransformSpec.scala
- modules/server/src/test/scala/com/risquanter/register/simulation/LossDistributionSpec.scala
- modules/server/src/test/scala/com/risquanter/register/simulation/MetalogDistributionSpec.scala
- modules/app/src/main/scala/app/state/RiskLeafFormState.scala
- modules/app/src/main/scala/app/components/TreeNodeRow.scala
- modules/app/src/main/scala/app/views/TreePreview.scala
- modules/app/src/main/scala/app/views/TreeDetailView.scala
- modules/app/src/test/scala/app/state/RiskLeafFormStateSpec.scala
- build.sbt
- modules/server-it/src/test/scala/com/risquanter/register/http/LognormalBoundPositivityItSpec.scala
- modules/common/src/main/scala/com/risquanter/register/http/codecs/IronTapirCodecs.scala
- modules/server/src/main/scala/com/risquanter/register/simulation/LossDistribution.scala
- docs/dev/plans/PLAN-LOGNORMAL-BOUND-POSITIVITY.md
- modules/server/src/main/scala/com/risquanter/register/simulation/LossDistribution.scala
