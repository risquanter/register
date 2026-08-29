# ADR-016: Configuration Management with ZIO Config

**Status:** Proposed  
**Date:** 2026-01-21  
**Tags:** configuration, zio-config, typesafe, testing

---

## Context

We currently define configuration case classes (e.g., `SimulationConfig`) and load them in the server module via `Configs.makeLayer`. Common/JVM tests, however, were instantiating configs inline (e.g., `SimulationConfig(...)` literals), which drifted from the canonical settings defined in `modules/server/src/main/resources/application.conf`. We want:
- A single source of truth for configuration values (the existing application.conf file).
- Idiomatic ZIO Config loading for production and tests, including in modules that do not automatically see server resources on the classpath.
- A documented pattern (mirroring the cheleb reference) for how to load and override configs.

## Decision

- Keep `modules/server/src/main/resources/application.conf` as the canonical configuration source for all environments.
- Provide a reusable test loader in `modules/common/src/test/scala/com/risquanter/register/testutil/ConfigTestLoader.scala` that reads from the canonical application.conf (via Typesafe config) so common-module tests no longer hardcode values.
- Maintain ZIO Config + magnolia derivation for configuration case classes. Tests load via `TypesafeConfigSource.fromTypesafeConfig(ConfigFactory.load())` (with fallback to the repository application.conf file when it is not on the classpath), ensuring values align with production defaults.
- Keep the server `Configs.makeLayer` helper as the production entry point for layering configuration, with the expectation that the service is launched with the canonical application.conf (and environment overrides) on the classpath.

## Consequences

- Tests use the same values as production by default; drift between test literals and runtime config is eliminated.
- Configuration changes now propagate automatically to specs that rely on `ConfigTestLoader`.
- There is a single authoritative location for simulation defaults (`register.simulation` in application.conf). Any future config additions should follow the same pattern.

## Implementation Notes

- Canonical file: `modules/server/src/main/resources/application.conf`.
- Test loader: `ConfigTestLoader.simulation` (common test scope) reads the canonical file via Typesafe config and ZIO Config descriptors.
- Production loader: `Configs.makeLayer[MyConfig]("register.<path>")` remains the entry point in the server module; it uses ZIO Config derivation and expects Typesafe-config provider on the classpath.
- Overrides: use environment variables as already defined in application.conf (e.g., `REGISTER_DEFAULT_NTRIALS`).

## Alternatives Considered

- Copying config snippets into each module's test resources (rejected—drifts from source of truth).
- Defining separate test-specific configs (rejected—adds another authority).

## Related Work

- `Configs.makeLayer` (server module) for production config loading.
- `ConfigTestLoader` (common tests) for canonical config access in non-server modules.
- `SimulationConfig` (common module) shared by production and tests.
