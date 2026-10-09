# File inventory — PLAN-WORKSPACE-SECURITY-FIXES.md

The approval hook reads this file to decide whether a gated edit is
permitted. A gated edit is allowed only when the edited file appears on a
bullet line below, written as a full repo-relative path. Text that is not a
bullet line authorizes nothing.

Only the user writes this file, through `.claude/bin/approve-inventory`.

- modules/server/src/main/scala/com/risquanter/register/services/workspace/RateLimiter.scala
- modules/server/src/main/scala/com/risquanter/register/services/workspace/WorkspaceStore.scala
- modules/server/src/main/scala/com/risquanter/register/services/workspace/WorkspaceStoreLive.scala
- modules/server/src/main/scala/com/risquanter/register/services/workspace/WorkspaceStorePostgres.scala
- modules/server/src/main/scala/com/risquanter/register/http/controllers/WorkspaceLifecycleController.scala
- modules/server/src/main/scala/com/risquanter/register/configs/WorkspaceConfig.scala
- modules/server/src/main/resources/application.conf
- modules/common/src/main/scala/com/risquanter/register/domain/errors/AppError.scala
- modules/common/src/main/scala/com/risquanter/register/domain/errors/ErrorResponse.scala
- modules/common/src/main/scala/com/risquanter/register/domain/data/iron/ValidationMessages.scala
- modules/server/src/test/scala/com/risquanter/register/services/workspace/RateLimiterSpec.scala
- modules/server/src/test/scala/com/risquanter/register/services/workspace/WorkspaceStoreSpec.scala
- modules/server/src/test/scala/com/risquanter/register/services/workspace/WorkspaceStorePostgresSpec.scala
- modules/server/src/test/scala/com/risquanter/register/http/controllers/WorkspaceLifecycleControllerSpec.scala
- modules/server/src/test/scala/com/risquanter/register/configs/TestConfigs.scala
- modules/common/src/test/scala/com/risquanter/register/domain/errors/ErrorResponseSpec.scala
