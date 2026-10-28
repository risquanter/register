# File inventory — PLAN-CACHE-REGISTRY-RENAME.md

The approval hook reads this file to decide whether a gated edit is
permitted. A gated edit is allowed only when the edited file appears on a
bullet line below, written as a full repo-relative path. Text that is not a
bullet line authorizes nothing.

Only the user writes this file, through `.claude/bin/approve-inventory`.


Every path is repo-relative and complete, as the approval hook requires.

### Source — renamed files

- `modules/server/src/main/scala/com/risquanter/register/services/cache/CacheScope.scala` — renamed to `ContentCacheRegistry.scala`; 7 occurrences
- `modules/server/src/main/scala/com/risquanter/register/services/cache/ScopeResolverScope.scala` — renamed to `MitigationScopeResolverRegistry.scala`; 8 occurrences

### Source — call sites

- `modules/server/src/main/scala/com/risquanter/register/Application.scala` — 3 + 2
- `modules/server/src/main/scala/com/risquanter/register/services/QueryServiceLive.scala` — 5
- `modules/server/src/main/scala/com/risquanter/register/services/cache/CachedResultResolverLive.scala` — 5
- `modules/server/src/main/scala/com/risquanter/register/services/cache/ContentCache.scala` — 1
- `modules/server/src/main/scala/com/risquanter/register/services/cache/MitigationScopeResolver.scala` — 1 + 1
- `modules/server/src/main/scala/com/risquanter/register/services/cache/MitigationScopeResolverLive.scala` — 1, plus the scaladoc replacement above

### Unit tests — `server`

- `modules/server/src/test/scala/com/risquanter/register/services/cache/MitigationScopeResolverSpec.scala` — 1, plus 3 `resolverFor` call sites
- `modules/server/src/test/scala/com/risquanter/register/services/cache/CachedResultResolverSpec.scala` — 12, plus 8 `cacheFor` call sites
- `modules/server/src/test/scala/com/risquanter/register/services/cache/CacheTransparencySpec.scala` — 7, including an `override def cacheFor` in a stub
- `modules/server/src/test/scala/com/risquanter/register/domain/data/ProvenanceSpec.scala` — 4
- `modules/server/src/test/scala/com/risquanter/register/http/controllers/RiskTreeControllerSpec.scala` — 1
- `modules/server/src/test/scala/com/risquanter/register/http/controllers/RouteSecurityRegressionSpec.scala` — 2
- `modules/server/src/test/scala/com/risquanter/register/http/controllers/WorkspaceLifecycleControllerSpec.scala` — 2
- `modules/server/src/test/scala/com/risquanter/register/services/AggregateFreshnessAfterLeafMoveSpec.scala` — 1
- `modules/server/src/test/scala/com/risquanter/register/services/RiskTreeServiceLiveSpec.scala` — 1
- `modules/server/src/test/scala/com/risquanter/register/services/SeedStabilitySpec.scala` — 1

### Integration tests — `serverIt`

- `modules/server-it/src/test/scala/com/risquanter/register/http/HttpTestHarness.scala` — 2 + 2
- `modules/server-it/src/test/scala/com/risquanter/register/http/support/StubHttpTestHarness.scala` — 2 + 2
- `modules/server-it/src/test/scala/com/risquanter/register/http/SeedReproducibilityItSpec.scala` — 2
- modules/server/src/main/scala/com/risquanter/register/services/cache/EvictionStrategy.scala
- modules/server/src/main/scala/com/risquanter/register/services/cache/ContentCacheRegistry.scala
- modules/server/src/main/scala/com/risquanter/register/services/cache/MitigationScopeResolverRegistry.scala
- build.sbt
- .env
- .env.irmin
