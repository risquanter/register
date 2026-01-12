# API Test Plan - Native Image Validation

## Purpose

Validate the GraalVM native image production deployment by:
1. Testing all API endpoints work correctly
2. Verifying simulation computations produce valid results
3. Confirming native binary performance (startup, memory, response time)
4. Ensuring no regressions from JVM version

## Test Environment

**Image:** `register-server:native` (118MB, distroless, static musl binary)
**Deployment:** docker-compose with production configuration
**Base URL:** `http://localhost:8080`

## Prerequisites

```bash
# Clean environment
docker-compose down -v
docker system prune -f

# Build and start native image
docker-compose up -d --build

# Wait for startup (~9ms)
sleep 2
```

## Test Cases

### 1. Health Check

**Purpose:** Verify service is running and responding

**curl:**
```bash
curl -X GET http://localhost:8080/health
```

**httpie:**
```bash
http GET http://localhost:8080/health
```

**Expected Response:**
```json
{
  "status": "healthy",
  "service": "risk-register"
}
```

**Success Criteria:**
- HTTP 200 OK
- Response < 50ms
- JSON structure matches

---

### 2. Create Risk Tree (Complex Hierarchy)

**Purpose:** Test POST endpoint with nested risk tree structure

**curl:**
```bash
curl -X POST http://localhost:8080/api/risk-trees \
  -H "Content-Type: application/json" \
  -d @docs/test/create-risk-tree.json
```

**httpie:**
```bash
http POST http://localhost:8080/api/risk-trees < docs/test/create-risk-tree.json
```

**Expected Response:**
```json
{
  "id": 0,
  "name": "Production System Outage",
  "nTrials": 50000,
  "root": {
    "name": "Total Impact",
    "probability": 0.15,
    ...
  },
  "createdAt": "2026-01-12T15:30:00Z"
}
```

**Success Criteria:**
- HTTP 200 OK
- Returns assigned ID (0 for first tree)
- Structure preserved
- Response < 100ms

---

### 3. Create Simple Risk Tree

**Purpose:** Test single-node (leaf) risk tree

**curl:**
```bash
curl -X POST http://localhost:8080/api/risk-trees \
  -H "Content-Type: application/json" \
  -d @docs/test/create-simple-risk.json
```

**httpie:**
```bash
http POST http://localhost:8080/api/risk-trees < docs/test/create-simple-risk.json
```

**Expected Response:**
```json
{
  "id": 1,
  "name": "Data Breach Scenario",
  ...
}
```

**Success Criteria:**
- HTTP 200 OK
- Returns ID 1
- Response < 100ms

---

### 4. Get All Risk Trees

**Purpose:** Verify list endpoint returns all created trees

**curl:**
```bash
curl -X GET http://localhost:8080/api/risk-trees
```

**httpie:**
```bash
http GET http://localhost:8080/api/risk-trees
```

**Expected Response:**
```json
[
  {
    "id": 0,
    "name": "Production System Outage",
    ...
  },
  {
    "id": 1,
    "name": "Data Breach Scenario",
    ...
  }
]
```

**Success Criteria:**
- HTTP 200 OK
- Returns array with 2 trees
- Response < 50ms

---

### 5. Get Risk Tree by ID

**Purpose:** Test single tree retrieval

**curl:**
```bash
curl -X GET http://localhost:8080/api/risk-trees/0
```

**httpie:**
```bash
http GET http://localhost:8080/api/risk-trees/0
```

**Expected Response:**
```json
{
  "id": 0,
  "name": "Production System Outage",
  ...
}
```

**Success Criteria:**
- HTTP 200 OK
- Returns correct tree
- Response < 50ms

---

### 6. Compute LEC (Loss Exceedance Curve) - Root Only

**Purpose:** Test Monte Carlo simulation at root level

**curl:**
```bash
curl -X GET "http://localhost:8080/api/risk-trees/0/lec?depth=0&nTrials=10000"
```

**httpie:**
```bash
http GET http://localhost:8080/api/risk-trees/0/lec depth==0 nTrials==10000
```

**Expected Response:**
```json
{
  "id": 0,
  "name": "Production System Outage",
  "lec": {
    "quantiles": {
      "p50": 75000.5,
      "p90": 350000.2,
      "p95": 450000.8,
      "p99": 490000.1
    },
    "vegaLiteSpec": {
      "$schema": "https://vega.github.io/schema/vega-lite/v5.json",
      ...
    }
  }
}
```

**Success Criteria:**
- HTTP 200 OK
- Contains `lec.quantiles` with p50/p90/p95/p99
- Contains `lec.vegaLiteSpec` (valid Vega-Lite JSON)
- Quantiles are sorted: p50 < p90 < p95 < p99
- Response < 2000ms (10,000 trials)

---

### 7. Compute LEC with Full Hierarchy

**Purpose:** Test simulation with depth=1 (includes children)

**curl:**
```bash
curl -X GET "http://localhost:8080/api/risk-trees/0/lec?depth=1&nTrials=25000&parallelism=4"
```

**httpie:**
```bash
http GET http://localhost:8080/api/risk-trees/0/lec depth==1 nTrials==25000 parallelism==4
```

**Expected Response:**
```json
{
  "id": 0,
  "name": "Production System Outage",
  "lec": {
    "quantiles": { ... },
    "vegaLiteSpec": { ... }
  },
  "children": [
    {
      "name": "Revenue Loss",
      "lec": {
        "quantiles": { ... }
      }
    },
    {
      "name": "Recovery Costs",
      "lec": {
        "quantiles": { ... }
      }
    }
  ]
}
```

**Success Criteria:**
- HTTP 200 OK
- Root has LEC
- Children have LEC
- Response < 3000ms (25,000 trials with parallelism)

---

### 8. Compute LEC with Provenance

**Purpose:** Verify provenance metadata for reproducibility

**curl:**
```bash
curl -X GET "http://localhost:8080/api/risk-trees/1/lec?includeProvenance=true&nTrials=5000"
```

**httpie:**
```bash
http GET http://localhost:8080/api/risk-trees/1/lec includeProvenance==true nTrials==5000
```

**Expected Response:**
```json
{
  "id": 1,
  "name": "Data Breach Scenario",
  "lec": {
    "quantiles": { ... },
    "provenance": {
      "seed": 42,
      "nTrials": 5000,
      "parallelism": 8,
      "timestamp": "2026-01-12T15:35:00Z",
      "version": "0.1.0"
    }
  }
}
```

**Success Criteria:**
- HTTP 200 OK
- Contains `lec.provenance`
- Provenance has seed, nTrials, parallelism, timestamp
- Response < 1000ms

---

### 9. Invalid Request Handling

**Purpose:** Verify validation and error responses

**Test 9a: Invalid nTrials (must be > 0)**

**curl:**
```bash
curl -X GET "http://localhost:8080/api/risk-trees/0/lec?nTrials=0"
```

**Expected:** HTTP 400 Bad Request

**Test 9b: Invalid depth (max 5)**

**curl:**
```bash
curl -X GET "http://localhost:8080/api/risk-trees/0/lec?depth=10"
```

**Expected:** HTTP 400 Bad Request

**Test 9c: Non-existent tree**

**curl:**
```bash
curl -X GET http://localhost:8080/api/risk-trees/999
```

**Expected:** HTTP 200 with `null` body

**Success Criteria:**
- Validation errors return HTTP 400
- Error messages are descriptive
- Non-existent resources handled gracefully

---

## Performance Validation

### Native Image Metrics

```bash
# Container startup time
docker-compose logs register-server | grep "Server started"
# Expected: < 100ms total startup

# Memory usage
docker stats register-server --no-stream
# Expected: ~50-80 MB RAM usage

# Image size
docker images register-server:native
# Expected: ~118 MB
```

### Response Time Benchmarks

```bash
# Baseline (health check)
time curl -X GET http://localhost:8080/health
# Expected: < 50ms

# Simple simulation (5,000 trials)
time curl -X GET "http://localhost:8080/api/risk-trees/1/lec?nTrials=5000"
# Expected: < 1000ms

# Complex simulation (25,000 trials, depth=1)
time curl -X GET "http://localhost:8080/api/risk-trees/0/lec?depth=1&nTrials=25000"
# Expected: < 3000ms
```

---

## Automated Test Script

```bash
#!/bin/bash
set -e

BASE_URL="http://localhost:8080"
PASS=0
FAIL=0

echo "=== Native Image API Test Suite ==="
echo

# Test 1: Health Check
echo "Test 1: Health Check"
RESPONSE=$(curl -s -w "%{http_code}" -o /tmp/response.json $BASE_URL/health)
if [ "$RESPONSE" = "200" ]; then
    echo "✅ PASS"
    ((PASS++))
else
    echo "❌ FAIL (HTTP $RESPONSE)"
    ((FAIL++))
fi
echo

# Test 2: Create Complex Tree
echo "Test 2: Create Complex Risk Tree"
RESPONSE=$(curl -s -w "%{http_code}" -o /tmp/response.json \
    -X POST $BASE_URL/api/risk-trees \
    -H "Content-Type: application/json" \
    -d @docs/test/create-risk-tree.json)
if [ "$RESPONSE" = "200" ]; then
    echo "✅ PASS"
    ((PASS++))
else
    echo "❌ FAIL (HTTP $RESPONSE)"
    cat /tmp/response.json
    ((FAIL++))
fi
echo

# Test 3: Create Simple Tree
echo "Test 3: Create Simple Risk Tree"
RESPONSE=$(curl -s -w "%{http_code}" -o /tmp/response.json \
    -X POST $BASE_URL/api/risk-trees \
    -H "Content-Type: application/json" \
    -d @docs/test/create-simple-risk.json)
if [ "$RESPONSE" = "200" ]; then
    echo "✅ PASS"
    ((PASS++))
else
    echo "❌ FAIL (HTTP $RESPONSE)"
    ((FAIL++))
fi
echo

# Test 4: Get All Trees
echo "Test 4: Get All Risk Trees"
RESPONSE=$(curl -s -w "%{http_code}" -o /tmp/response.json $BASE_URL/api/risk-trees)
COUNT=$(jq '. | length' /tmp/response.json)
if [ "$RESPONSE" = "200" ] && [ "$COUNT" = "2" ]; then
    echo "✅ PASS (Found $COUNT trees)"
    ((PASS++))
else
    echo "❌ FAIL (HTTP $RESPONSE, Count: $COUNT)"
    ((FAIL++))
fi
echo

# Test 5: Get Tree by ID
echo "Test 5: Get Risk Tree by ID"
RESPONSE=$(curl -s -w "%{http_code}" -o /tmp/response.json $BASE_URL/api/risk-trees/0)
if [ "$RESPONSE" = "200" ]; then
    echo "✅ PASS"
    ((PASS++))
else
    echo "❌ FAIL (HTTP $RESPONSE)"
    ((FAIL++))
fi
echo

# Test 6: Compute LEC (Root Only)
echo "Test 6: Compute LEC (Root Only)"
START=$(date +%s%N)
RESPONSE=$(curl -s -w "%{http_code}" -o /tmp/response.json \
    "$BASE_URL/api/risk-trees/0/lec?depth=0&nTrials=10000")
END=$(date +%s%N)
DURATION=$(((END - START) / 1000000))
HAS_QUANTILES=$(jq 'has("lec") and .lec | has("quantiles")' /tmp/response.json)
if [ "$RESPONSE" = "200" ] && [ "$HAS_QUANTILES" = "true" ]; then
    echo "✅ PASS (${DURATION}ms)"
    ((PASS++))
else
    echo "❌ FAIL (HTTP $RESPONSE)"
    ((FAIL++))
fi
echo

# Test 7: Compute LEC (With Children)
echo "Test 7: Compute LEC (Full Hierarchy)"
START=$(date +%s%N)
RESPONSE=$(curl -s -w "%{http_code}" -o /tmp/response.json \
    "$BASE_URL/api/risk-trees/0/lec?depth=1&nTrials=25000&parallelism=4")
END=$(date +%s%N)
DURATION=$(((END - START) / 1000000))
HAS_CHILDREN_LEC=$(jq '.children[0] | has("lec")' /tmp/response.json)
if [ "$RESPONSE" = "200" ] && [ "$HAS_CHILDREN_LEC" = "true" ]; then
    echo "✅ PASS (${DURATION}ms)"
    ((PASS++))
else
    echo "❌ FAIL (HTTP $RESPONSE)"
    ((FAIL++))
fi
echo

# Test 8: Provenance
echo "Test 8: Compute LEC with Provenance"
RESPONSE=$(curl -s -w "%{http_code}" -o /tmp/response.json \
    "$BASE_URL/api/risk-trees/1/lec?includeProvenance=true&nTrials=5000")
HAS_PROVENANCE=$(jq '.lec | has("provenance")' /tmp/response.json)
if [ "$RESPONSE" = "200" ] && [ "$HAS_PROVENANCE" = "true" ]; then
    echo "✅ PASS"
    ((PASS++))
else
    echo "❌ FAIL (HTTP $RESPONSE)"
    ((FAIL++))
fi
echo

# Test 9: Error Handling
echo "Test 9: Invalid Request Handling"
RESPONSE=$(curl -s -w "%{http_code}" -o /tmp/response.json \
    "$BASE_URL/api/risk-trees/0/lec?nTrials=0")
if [ "$RESPONSE" = "400" ]; then
    echo "✅ PASS (Validation error caught)"
    ((PASS++))
else
    echo "❌ FAIL (Expected 400, got $RESPONSE)"
    ((FAIL++))
fi
echo

# Summary
echo "==================================="
echo "Results: $PASS passed, $FAIL failed"
echo "==================================="

if [ $FAIL -gt 0 ]; then
    exit 1
fi
```

**Save as:** `docs/test/run-api-tests.sh`

**Usage:**
```bash
chmod +x docs/test/run-api-tests.sh
./docs/test/run-api-tests.sh
```

---

## Cleanup

```bash
# Stop and remove containers
docker-compose down -v

# Remove test data
rm /tmp/response.json
```

---

## Success Criteria Summary

| Test | Criteria | Expected |
|------|----------|----------|
| Health Check | Response time | < 50ms |
| Create Tree | Response time | < 100ms |
| Get All | Response time | < 50ms |
| Get By ID | Response time | < 50ms |
| LEC (10k trials) | Response time | < 2000ms |
| LEC (25k trials) | Response time | < 3000ms |
| Container Startup | Total time | < 100ms |
| Memory Usage | Idle | 50-80 MB |
| Image Size | Total | ~118 MB |

---

## Notes

- All tests use in-memory storage (no database persistence)
- IDs start at 0 for first tree
- Response times may vary based on system load
- Native image shows ~500x faster startup vs JVM
- Memory usage 73-82% lower than JVM (~250MB)
