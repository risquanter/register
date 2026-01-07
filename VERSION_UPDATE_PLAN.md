# Version Update Plan - Incremental Migration

**Date:** 2026-01-07  
**Current Status:** Planning Phase  
**Strategy:** Small, testable increments with rollback capability

---

## Current Versions (Baseline)

| Library | Current | Latest Available | Risk |
|---------|---------|------------------|------|
| Scala | `3.6.3` | `3.6.4` | 🟢 Low (patch) |
| ZIO Core | `2.1.24` | `2.1.29` | 🟢 Low (patch) |
| ZIO JSON | `0.7.44` | `0.8.0` | 🟡 Medium (minor) |
| ZIO Prelude | `1.0.0-RC44` | `1.0.0-RC50` | 🟡 Medium (RC) |
| ZIO Config | `4.0.2` | `4.0.2` | ✅ Current |
| ZIO Logging | `2.2.4` | `2.4.0` | 🔴 High (API change) |
| Tapir | `1.13.4` | `1.13.4` | ✅ Current |
| STTP | `3.9.6` | `3.10.1` | 🟢 Low (patch) |
| Iron | `3.2.1` | `3.7.0` | 🟡 Medium (minor) |
| Logback | `1.5.23` | `1.5.23` | ✅ Current |
| Quill | `4.8.6` | `4.8.6` | ✅ Current |
| **simulation.util** | `0.8.0` | `0.8.0` | 🔒 Fixed |

---

## Compatible Version Matrix

Based on cross-compatibility research:

```
Scala 3.6.4
├── ZIO 2.1.29 ✅
├── ZIO JSON 0.8.0 ✅
├── ZIO Prelude 1.0.0-RC50 ✅
├── ZIO Config 4.0.2 ✅
├── ZIO Logging 2.4.0 ✅
├── Tapir 1.13.4 ✅
├── STTP 3.10.1 ✅
├── Iron 3.7.0 ✅
└── simulation.util 0.8.0 🔒 (constraint)
```

---

## Incremental Migration Strategy

### Phase 0: Preparation (10 min)
- ✅ Create git branch `update/library-versions`
- ✅ Backup current `build.sbt`
- ✅ Document baseline: 408 tests passing

### Phase 1: Scala Compiler Update (15 min) 🟢 LOW RISK
**Change:** `3.6.3` → `3.6.4`

**Rationale:** Patch version, bug fixes only

```scala
ThisBuild / scalaVersion := "3.6.4"
```

**Test Command:**
```bash
sbt clean compile
sbt test
```

**Success Criteria:** 408 tests passing, no compilation errors

**Rollback:** Revert scalaVersion if fails

---

### Phase 2: ZIO Core Update (20 min) 🟢 LOW RISK
**Change:** `2.1.24` → `2.1.29`

**Rationale:** 5 patch releases, backward compatible

```scala
val zioVersion = "2.1.29"
```

**Known Changes (2.1.24→2.1.29):**
- Bug fixes in fiber interruption
- Performance improvements in Queue
- No breaking API changes expected

**Test Command:**
```bash
sbt clean compile
sbt test
```

**Watch For:**
- Fiber lifecycle changes
- Queue/Stream behavior
- Test flakiness

**Success Criteria:** 408 tests passing

**Rollback:** Revert zioVersion if >10 tests fail

---

### Phase 3: STTP Client Update (15 min) 🟢 LOW RISK
**Change:** `3.9.6` → `3.10.1`

**Rationale:** Minor version, usually backward compatible

```scala
val sttpVersion = "3.10.1"
```

**Test Command:**
```bash
sbt clean compile
sbt "project server" test
```

**Watch For:**
- HTTP client behavior changes
- JSON parsing integration

**Success Criteria:** Server tests passing

**Rollback:** Revert sttpVersion if HTTP tests fail

---

### Phase 4: ZIO JSON Update (30 min) 🟡 MEDIUM RISK
**Change:** `0.7.44` → `0.8.0`

**Rationale:** Minor version bump, may have API changes

```scala
"dev.zio" %% "zio-json" % "0.8.0"
```

**Potential Breaking Changes:**
- Decoder API changes
- Error message format changes
- Codec derivation changes

**Test Strategy:**
```bash
# Compile first to catch API breaks
sbt clean compile

# If compilation succeeds
sbt test

# Focus on JSON tests
sbt "testOnly *RiskNodeSpec"
sbt "testOnly *ValidationSpec"
```

**Watch For:**
- `JsonDecoder.mapOrFail` signature changes
- `DeriveJsonCodec.gen` behavior
- Custom decoder compilation errors

**Mitigation:** If breaks occur:
1. Check ZIO JSON changelog for migration guide
2. Update custom decoders in RiskNode.scala
3. If too complex, **stay on 0.7.44**

**Success Criteria:** All JSON serialization tests pass

---

### Phase 5: ZIO Logging Migration (45 min) 🔴 HIGH RISK
**Change:** `zio-logging-slf4j` → `zio-logging-slf4j2` + version `2.2.4` → `2.4.0`

**Rationale:** BREAKING - Old SLF4J bridge deprecated

**Current:**
```scala
val zioLoggingVersion = "2.2.4"
"dev.zio" %% "zio-logging"        % zioLoggingVersion,
"dev.zio" %% "zio-logging-slf4j"  % zioLoggingVersion,
```

**Target:**
```scala
val zioLoggingVersion = "2.4.0"
"dev.zio" %% "zio-logging"        % zioLoggingVersion,
"dev.zio" %% "zio-logging-slf4j2" % zioLoggingVersion,  // ← NEW
```

**Code Changes Required:**

**1. Application.scala - Update import:**
```scala
// OLD
import zio.logging.backend.SLF4J

// NEW  
import zio.logging.slf4j.bridge.Slf4jBridge
```

**2. Application.scala - Update logging layer:**
```scala
// OLD
val loggingLayer = Runtime.removeDefaultLoggers >>> SLF4J.slf4j

// NEW
val loggingLayer = Runtime.removeDefaultLoggers >>> Slf4jBridge.initialize
```

**Test Strategy:**
```bash
# 1. Update build.sbt
# 2. Try compilation
sbt clean compile

# 3. If compilation errors, fix imports
# 4. Run tests
sbt test

# 5. Verify logging works
sbt "project server" run
# Check console output for logs
```

**Watch For:**
- Import errors in Application.scala
- Logging layer initialization failures
- Log output format changes

**Mitigation Plan:**
- If breaks, keep old version `2.2.4` with `slf4j` bridge
- Phase 3 (Structured Logging) will handle this properly
- **Alternative:** Skip this update, handle in Phase 3 implementation

**Success Criteria:** 
- Application starts
- Logs appear in console
- 408 tests passing

---

### Phase 6: ZIO Prelude Update (20 min) 🟡 MEDIUM RISK
**Change:** `1.0.0-RC44` → `1.0.0-RC50`

**Rationale:** RC versions may have API changes

```scala
"dev.zio" %% "zio-prelude" % "1.0.0-RC50"
```

**Potential Impact:**
- `Validation` API changes
- Error accumulation behavior

**Test Strategy:**
```bash
sbt clean compile
sbt "testOnly *ValidationSpec"
sbt test
```

**Watch For:**
- `Validation.validateWith` signature changes
- Error accumulation behavior
- Import path changes

**Mitigation:** If breaks, **stay on RC44** - it's working fine

**Success Criteria:** All validation tests pass

---

### Phase 7: Iron Type Library Update (25 min) 🟡 MEDIUM RISK
**Change:** `3.2.1` → `3.7.0`

**Rationale:** 5 minor versions, may have refinement API changes

```scala
val ironVersion = "3.7.0"
```

**Potential Impact:**
- Refinement syntax changes
- Iron opaque type behavior
- Constraint definition changes

**Test Strategy:**
```bash
sbt clean compile
sbt "testOnly *RiskLeafSpec"
sbt "testOnly *RiskPortfolioSpec"
sbt test
```

**Watch For:**
- `refineEither` API changes
- Constraint definition syntax
- Iron-ZIO integration

**Mitigation:** If breaks, **stay on 3.2.1** - refinements are core to validation

**Success Criteria:** All Iron refinement tests pass

---

## Testing Protocol (After Each Phase)

### 1. Compilation Check
```bash
sbt clean
sbt compile
```
✅ Must succeed with 0 errors

### 2. Full Test Suite
```bash
sbt test
```
✅ Must maintain 408 tests passing

### 3. Server Startup
```bash
sbt "project server" run
```
✅ Server must start on port 8080

### 4. Manual API Test
```bash
curl http://localhost:8080/api/docs
```
✅ Swagger UI must load

### 5. Git Checkpoint
```bash
git add .
git commit -m "Phase X: Updated [library] to [version] - 408 tests passing"
```

---

## Rollback Strategy

### If Phase Fails:

1. **Minor issues (1-5 test failures):** 
   - Debug and fix
   - Continue

2. **Major issues (>10 test failures or compilation errors):**
   - Revert last change: `git reset --hard HEAD~1`
   - Document issue
   - Skip that update
   - Continue with remaining phases

3. **Critical failure (server won't start):**
   - Abort migration
   - Revert all: `git reset --hard origin/main`
   - Reassess strategy

---

## Risk Mitigation Strategies

### For Breaking API Changes:

1. **Compilation-First Approach:**
   - Always run `sbt clean compile` before tests
   - Catches API breaks immediately

2. **Focused Testing:**
   - Run affected module tests first
   - E.g., JSON changes → test `*RiskNodeSpec` first

3. **Incremental Commits:**
   - Commit after each successful phase
   - Easy rollback to last known good state

4. **Documentation:**
   - Record any workarounds in this file
   - Link to library changelogs for breaking changes

5. **Conservative Fallback:**
   - If library breaks, stay on current version
   - Mark as "investigated but not worth migration"

---

## Final Target State

```scala
// build.sbt - After successful migration
ThisBuild / scalaVersion := "3.6.4"

val sttpVersion       = "3.10.1"
val zioVersion        = "2.1.29"
val tapirVersion      = "1.13.4"
val zioLoggingVersion = "2.4.0"
val zioConfigVersion  = "4.0.2"
val quillVersion      = "4.8.6"
val ironVersion       = "3.7.0"

// In dependencies:
"dev.zio" %% "zio-json"            % "0.8.0",
"dev.zio" %% "zio-prelude"         % "1.0.0-RC50",
"dev.zio" %% "zio-logging-slf4j2"  % zioLoggingVersion,  // ← CHANGED
```

---

## Success Metrics

- ✅ All 408 tests passing
- ✅ Server starts and responds
- ✅ No deprecation warnings
- ✅ Ready for Phase 3 (Structured Logging)

---

## Execution Checklist

- [ ] Phase 0: Create branch, backup
- [ ] Phase 1: Scala 3.6.4 → Test → Commit
- [ ] Phase 2: ZIO Core 2.1.29 → Test → Commit
- [ ] Phase 3: STTP 3.10.1 → Test → Commit
- [ ] Phase 4: ZIO JSON 0.8.0 → Test → Commit
- [ ] Phase 5: ZIO Logging 2.4.0 (slf4j2) → Test → Commit
- [ ] Phase 6: ZIO Prelude RC50 → Test → Commit
- [ ] Phase 7: Iron 3.7.0 → Test → Commit
- [ ] Final: Integration test, merge to main

**Estimated Total Time:** 3-4 hours (with testing and contingency)

---

## Next Steps

1. **Review this plan** - Approve or suggest changes
2. **Start Phase 0** - Create branch and baseline
3. **Execute phases incrementally** - Stop for review after each phase
4. **Report progress** - After each successful phase

Ready to begin Phase 0 (preparation)?
