# File inventory — PLAN-WORKSPACE-CACHE-RELEASE.md

The approval hook reads this file to decide whether a gated edit is
permitted. A gated edit is allowed only when the edited file appears on a
bullet line below, written as a full repo-relative path. Text that is not a
bullet line authorizes nothing.

Only the user writes this file, through `.claude/bin/approve-inventory`.


- `modules/server/src/main/scala/com/risquanter/register/services/cache/WorkspaceCacheRelease.scala` — new — trait, layer, live implementation
- `modules/server/src/main/scala/com/risquanter/register/services/cache/ContentCacheRegistry.scala` — `release` on trait, companion and live implementation; scaladoc correction
- `modules/server/src/main/scala/com/risquanter/register/services/cache/MitigationScopeResolverRegistry.scala` — `release` on trait, companion and live implementation
- `modules/server/src/main/scala/com/risquanter/register/services/CascadeDelete.scala` — two parameters, the release call, scaladoc
- `modules/server/src/main/scala/com/risquanter/register/http/controllers/WorkspaceLifecycleController.scala` — constructor field, layer requirement, two call sites
- `modules/server/src/main/scala/com/risquanter/register/services/workspace/WorkspaceReaper.scala` — layer requirement, one call site
- `modules/server/src/main/scala/com/risquanter/register/Application.scala` — `WorkspaceCacheRelease.layer` in the graph; two layer requirements updated
- `modules/server/src/test/scala/com/risquanter/register/services/CascadeTestStubs.scala` — a `WorkspaceCacheRelease` stub recording its calls
- `modules/server/src/test/scala/com/risquanter/register/services/cache/WorkspaceCacheReleaseSpec.scala` — new
- `modules/server/src/test/scala/com/risquanter/register/services/cache/CachedResultResolverSpec.scala` — registry release tests
- `modules/server/src/test/scala/com/risquanter/register/services/cache/MitigationScopeResolverSpec.scala` — registry release tests
- `modules/server/src/test/scala/com/risquanter/register/http/controllers/WorkspaceLifecycleControllerCascadeSpec.scala` — both teardown paths release
- `modules/server/src/test/scala/com/risquanter/register/services/workspace/WorkspaceReaperSpec.scala` — the reaper releases on expiry
- `modules/server-it/src/test/scala/com/risquanter/register/http/HttpTestHarness.scala` — provide the new layer
- `modules/server-it/src/test/scala/com/risquanter/register/http/support/StubHttpTestHarness.scala` — provide the new layer

Any other spec constructing `WorkspaceLifecycleController` or `WorkspaceReaper`
directly gains the new dependency. A new constructor parameter is a compile
error at every construction site, so the compiler enumerates them; sites found
that way are added to this inventory before being edited.
