# File inventory — PLAN-SIMULATION-CONCURRENCY-BOUNDS.md

The approval hook reads this file to decide whether a gated edit is
permitted. A gated edit is allowed only when the edited file appears on a
bullet line below, written as a full repo-relative path. Text that is not a
bullet line authorizes nothing.

Only the user writes this file, through `.claude/bin/approve-inventory`.


Production sources:

- `modules/server/src/main/scala/com/risquanter/register/services/cache/LeafSimulationLimiter.scala` (new)
- `modules/server/src/main/scala/com/risquanter/register/services/cache/CachedResultResolverLive.scala`
- `modules/server/src/main/scala/com/risquanter/register/Application.scala`
- `modules/server/src/main/resources/application.conf`
- `modules/common/src/main/scala/com/risquanter/register/configs/SimulationConfig.scala`

Test sources (every `SimulationConfig` construction site plus the new spec):

- `modules/server/src/test/scala/com/risquanter/register/services/cache/LeafSimulationLimiterSpec.scala` (new)
- `modules/server/src/test/scala/com/risquanter/register/services/cache/CachedResultResolverSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/configs/TestConfigs.scala`
- `modules/server/src/test/scala/com/risquanter/register/services/helper/SimulatorSpec.scala`
- `modules/common/src/test/scala/com/risquanter/register/domain/PreludeOrdUsageSpec.scala`
- `modules/common/src/test/scala/com/risquanter/register/testutil/ConfigTestLoader.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/http/HttpTestHarness.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/http/support/StubHttpTestHarness.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/http/support/DemoSpecSupport.scala`

Configuration and documentation:

- `docker-compose.yml`
- `docs/user/DOCKER-DEVELOPMENT.md`
- `docs/dev/TODO.md`
- `docs/dev/plans/IMPLEMENTATION-PLAN.md`
- `build.sbt`, `.env`, `.env.irmin` (version bump on landing)
