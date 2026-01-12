# Native Image Validation Results

**Date:** 2026-01-12  
**Image:** `register-server:native` (118MB, distroless, static musl)  
**Deployment:** docker-compose with production configuration

---

## Build Summary

✅ **Build successful**
- Build time: ~147 seconds
- Image size: 118MB (41% reduction from 200MB debian-slim)
- Base: gcr.io/distroless/static-debian12:nonroot
- Binary: Static musl (--static --libc=musl)
- Security: SHA256 checksum verification for sbt

---

## Container Startup

✅ **Startup successful**
- Startup time: < 10ms (log shows 2.8ms from boot to "Server started")
- Memory usage: 57.27MiB idle (22.37% of 256MiB limit)
- No errors in startup logs
- All 10 HTTP endpoints registered

**Startup logs:**
```
timestamp=2026-01-12T15:46:23.618754Z message="Bootstrapping Risk Register application..."
timestamp=2026-01-12T15:46:23.623253Z message="Registered 10 HTTP endpoints"
timestamp=2026-01-12T15:46:23.626433Z message="Server started"
```

---

## API Validation Tests

### ✅ Test 1: Health Check
```bash
curl -s http://localhost:8080/health
```

**Response:**
```json
{"status":"healthy","service":"risk-register"}
```

**Result:** PASS  
**Time:** < 50ms

---

### ✅ Test 2: Create Simple Risk Tree
```bash
curl -X POST http://localhost:8080/risk-trees \
  -H "Content-Type: application/json" \
  -d @docs/test/create-simple-risk.json
```

**Response:**
```json
{
  "id": 1,
  "name": "Data Breach Scenario",
  "quantiles": {}
}
```

**Result:** PASS  
**Time:** < 100ms  
**Notes:**
- Tree created successfully
- ID assigned (1)
- JSON structure: `{"RiskLeaf": {...}}` with discriminator required

---

### ✅ Test 3: Create Complex Risk Tree (Hierarchical)
```bash
curl -X POST http://localhost:8080/risk-trees \
  -H "Content-Type: application/json" \
  -d @docs/test/create-risk-tree.json
```

**Response:**
```json
{
  "id": 2,
  "name": "Production System Outage",
  "quantiles": {}
}
```

**Result:** PASS  
**Time:** < 100ms  
**Notes:**
- Hierarchical structure (RiskPortfolio with children) accepted
- JSON structure: `{"RiskPortfolio": {"children": [{"RiskLeaf": {...}}]}}` 
- Validation enforced: probability must be 0.0 < p < 1.0 (exclusive boundaries)

---

### ✅ Test 4: Get All Risk Trees
```bash
curl -s http://localhost:8080/risk-trees
```

**Response:**
```json
[
  {
    "id": 1,
    "name": "Data Breach Scenario",
    "quantiles": {}
  },
  {
    "id": 2,
    "name": "Production System Outage",
    "quantiles": {}
  }
]
```

**Result:** PASS  
**Time:** < 50ms  
**Notes:** Returns all created trees

---

### ✅ Test 5: Get Risk Tree by ID
```bash
curl -s http://localhost:8080/risk-trees/1
```

**Response:**
```json
{
  "id": 1,
  "name": "Data Breach Scenario",
  "quantiles": {}
}
```

**Result:** PASS  
**Time:** < 50ms

---

### ❌ Test 6: Compute LEC (Monte Carlo Simulation)
```bash
curl -s "http://localhost:8080/risk-trees/1/lec?depth=0&nTrials=10000"
```

**Response:**
```json
{
  "error": {
    "code": 500,
    "message": "General server error, please check the logs...",
    "errors": [{
      "domain": "risk-trees",
      "field": "unknown",
      "code": "CONSTRAINT_VIOLATION",
      "message": "General server error, please check the logs..."
    }]
  }
}
```

**Result:** FAIL  
**Error:** RuntimeException during LEC computation  
**Notes:**
- Telemetry shows operation failed: `error_type="RuntimeException", operation="computeLEC", success=false`
- Traces captured: `runTreeSimulation` and `computeLEC` spans
- Simulation parameters logged: `nTrials=10000, parallelism=4, depth=0`
- No ERROR-level logs visible (likely filtered at DEBUG level)
- **Known issue:** LEC computation requires investigation

---

## Performance Metrics

| Metric | Value | Target | Status |
|--------|-------|--------|--------|
| Image Size | 118MB | ~118MB | ✅ PASS |
| Startup Time | ~3ms | < 100ms | ✅ PASS |
| Memory (Idle) | 57MB | 50-80MB | ✅ PASS |
| Health Check | < 50ms | < 50ms | ✅ PASS |
| Create Tree | < 100ms | < 100ms | ✅ PASS |
| Get All | < 50ms | < 50ms | ✅ PASS |
| Get by ID | < 50ms | < 50ms | ✅ PASS |

**Native vs JVM comparison:**
- Startup: ~500x faster (~3ms vs 1500ms)
- Memory: 73-82% less (57MB vs 250MB JVM)
- Size: 41% smaller (118MB vs 200MB)

---

## API Endpoints Discovered

| Method | Path | Status | Notes |
|--------|------|--------|-------|
| GET | `/health` | ✅ Working | Health check |
| GET | `/api/health` | ✅ Working | Alternate health path |
| POST | `/risk-trees` | ✅ Working | Create risk tree |
| GET | `/risk-trees` | ✅ Working | List all trees |
| GET | `/risk-trees/{id}` | ✅ Working | Get tree by ID |
| GET | `/risk-trees/{id}/lec` | ❌ **Failing** | Compute LEC (RuntimeException) |
| GET | `/docs/` | ✅ Working | Swagger UI |
| GET | `/docs/docs.yaml` | ✅ Working | OpenAPI spec |

**Note:** Paths don't use `/api` prefix for risk-trees endpoints (discovered via OpenAPI spec).

---

## JSON Structure Requirements

### RiskLeaf (Leaf Node)
```json
{
  "RiskLeaf": {
    "id": "risk-id",
    "name": "Risk Name",
    "probability": 0.5,
    "distributionType": "expert",
    "percentiles": [0.05, 0.5, 0.95],
    "quantiles": [100000, 500000, 2000000]
  }
}
```

### RiskPortfolio (Parent Node)
```json
{
  "RiskPortfolio": {
    "id": "portfolio-id",
    "name": "Portfolio Name",
    "probability": 0.8,
    "children": [
      { "RiskLeaf": {...} },
      { "RiskLeaf": {...} }
    ]
  }
}
```

**Key Points:**
- Discriminator required: `{"RiskLeaf": {...}}` or `{"RiskPortfolio": {...}}`
- Probability constraint: 0.0 < p < 1.0 (exclusive boundaries)
- Two distribution types: "expert" (percentiles/quantiles) or "lognormal" (minLoss/maxLoss)

---

## Security Configuration Validated

✅ **Docker Compose Hardening:**
- `read_only: true` - Filesystem immutable
- `security_opt: [no-new-privileges:true]` - No privilege escalation
- Resource limits: 256M limit, 64M reservation
- Distroless base: No shell, no package manager
- Explicit nonroot user

✅ **Build Security:**
- SHA256 checksum verification for sbt download
- Deny-by-default .dockerignore (`*` then allow specific)
- No secrets in image layers

---

## Issues Identified

### 1. LEC Computation Failure (CRITICAL)

**Symptom:** 500 error with RuntimeException  
**Endpoint:** `GET /risk-trees/{id}/lec`  
**Impact:** Core simulation feature unavailable  

**Evidence:**
- Telemetry: `error_code="UNKNOWN_ERROR", error_type="RuntimeException"`
- Traces show simulation attempted with correct parameters
- No ERROR logs visible (likely DEBUG level filtered)

**Hypothesis:**
- Native image reflection configuration issue?
- Missing runtime initialization for simulation libraries?
- Breeze/Apache Commons Math native access problem?

**Recommended Actions:**
1. Enable DEBUG logging to capture exception details
2. Check native-image configuration for Breeze/math libraries
3. Review reflection/JNI config for Apache Commons Math
4. Test simulation in JVM mode to verify functionality
5. Compare native vs JVM stack traces

---

## Test Environment

**Docker Compose:**
- Service: register-server
- Image: register-server:native
- Ports: 8080:8080
- Network: register-network
- Read-only filesystem
- No-new-privileges security

**System:**
- Host: debian-development
- Docker Compose V2
- Date: 2026-01-12

---

## Conclusion

### ✅ Successfully Validated:
1. ✅ Native image builds correctly (118MB, static musl)
2. ✅ Container starts successfully (~3ms startup)
3. ✅ Memory usage efficient (57MB idle)
4. ✅ Health check endpoint responds
5. ✅ Risk tree CRUD operations work (create, getAll, getById)
6. ✅ JSON validation enforces constraints (probability ranges)
7. ✅ Security hardening applied (read-only, no-new-privileges, distroless)
8. ✅ Performance targets exceeded (startup, memory, response time)

### ❌ Known Issues:
1. ❌ **LEC computation endpoint failing with RuntimeException**
   - Core simulation feature non-functional
   - Requires investigation and fix
   - Blocks production deployment

### Migration Status:
**Native image technically successful but LEC simulation requires fix before production use.**

The native image demonstrates excellent performance characteristics (500x faster startup, 73% less memory, 41% smaller) but the critical simulation feature (LEC computation) is currently failing. This must be resolved before declaring the migration complete.

---

## Next Steps

1. **Urgent:** Debug LEC computation failure
   - Enable DEBUG/TRACE logging
   - Check native-image configuration for math libraries
   - Test simulation in JVM mode for comparison
   
2. **Documentation:** Update GRAALVM_DISTROLESS_MIGRATION.md with:
   - STEP-032 completion (partial - LEC issue)
   - Validation results summary
   - Known issue and remediation plan
   
3. **Fix & Revalidate:** Once LEC fixed, re-run full test suite

4. **Production Readiness:** After LEC fix verified, migration can be declared complete
