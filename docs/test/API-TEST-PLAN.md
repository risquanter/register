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

---

## Irmin GraphQL Server Tests (Persistence Layer)

### Overview

Irmin is the versioned persistence layer for the Risk Register. It provides a Git-like data store with GraphQL API for operations.

**Service:** `irmin`  
**Port:** 9080 (host) → 8080 (container)  
**Endpoint:** `http://localhost:9080/graphql`  
**Schema:** `dev/irmin-schema.graphql`

### Starting Irmin

```bash
# Start with the persistence profile
docker compose --profile persistence up irmin -d

# Check status
docker compose ps irmin

# View logs
docker compose logs -f irmin
```

### Test Cases - Irmin GraphQL

#### IR-1: Health Check

**Purpose:** Verify Irmin GraphQL server is responding

**curl:**
```bash
curl -s -X POST http://localhost:9080/graphql \
  -H "Content-Type: application/json" \
  -d '{"query": "{ __typename }"}'
```

**Expected Response:**
```json
{
  "data": {
    "__typename": "query"
  }
}
```

**Success Criteria:**
- HTTP 200 OK
- Valid GraphQL response
- Response < 100ms

---

#### IR-2: Query Schema Introspection

**Purpose:** Verify GraphQL schema is accessible

**curl:**
```bash
curl -s -X POST http://localhost:9080/graphql \
  -H "Content-Type: application/json" \
  -d '{"query": "{ __schema { queryType { name } mutationType { name } } }"}'
```

**Expected Response:**
```json
{
  "data": {
    "__schema": {
      "queryType": {
        "name": "query"
      },
      "mutationType": {
        "name": "mutation"
      }
    }
  }
}
```

**Success Criteria:**
- Returns query and mutation types
- Schema matches `dev/irmin-schema.graphql`

---

#### IR-3: Query Empty Repository

**Purpose:** Verify initial state of Irmin store

**curl:**
```bash
curl -s -X POST http://localhost:9080/graphql \
  -H "Content-Type: application/json" \
  -d '{"query": "{ branches { name } main { name head { hash } } }"}'
```

**Expected Response:**
```json
{
  "data": {
    "branches": [],
    "main": {
      "name": "main",
      "head": null
    }
  }
}
```

**Success Criteria:**
- Empty branches array
- Main branch exists but has no commits (head is null)

---

#### IR-4: Write Data (Mutation)

**Purpose:** Test basic write operation

**curl:**
```bash
curl -s -X POST http://localhost:9080/graphql \
  -H "Content-Type: application/json" \
  -d '{
    "query": "mutation SetValue($path: Path!, $value: Value!, $info: InfoInput) { set(path: $path, value: $value, info: $info) { hash info { message author date } } }",
    "variables": {
      "path": "/risks/risk-001",
      "value": "{\"id\": \"risk-001\", \"title\": \"Data Breach Risk\", \"probability\": 0.15}",
      "info": {
        "message": "Add data breach risk",
        "author": "test-suite"
      }
    }
  }'
```

**Expected Response:**
```json
{
  "data": {
    "set": {
      "hash": "<40-char-hex-hash>",
      "info": {
        "message": "Add data breach risk",
        "author": "test-suite",
        "date": "<unix-timestamp>"
      }
    }
  }
}
```

**Success Criteria:**
- Returns commit hash (40 hex chars)
- Contains commit info with message, author, date
- Response < 200ms

---

#### IR-5: Read Data Back

**Purpose:** Verify written data can be retrieved

**curl:**
```bash
curl -s -X POST http://localhost:9080/graphql \
  -H "Content-Type: application/json" \
  -d '{"query": "{ main { tree { get(path: \"/risks/risk-001\") } } }"}'
```

**Expected Response:**
```json
{
  "data": {
    "main": {
      "tree": {
        "get": "{\"id\": \"risk-001\", \"title\": \"Data Breach Risk\", \"probability\": 0.15}"
      }
    }
  }
}
```

**Success Criteria:**
- Returns exact value that was written
- Response < 100ms

---

#### IR-6: Query Commit History

**Purpose:** Verify version tracking

**curl:**
```bash
curl -s -X POST http://localhost:9080/graphql \
  -H "Content-Type: application/json" \
  -d '{"query": "{ main { head { hash info { message author date } parents } } }"}'
```

**Expected Response:**
```json
{
  "data": {
    "main": {
      "head": {
        "hash": "<40-char-hex-hash>",
        "info": {
          "message": "Add data breach risk",
          "author": "test-suite",
          "date": "<unix-timestamp>"
        },
        "parents": []
      }
    }
  }
}
```

**Success Criteria:**
- Returns commit with hash and metadata
- Parents array (empty for first commit)
- Response < 100ms

---

#### IR-7: Write Multiple Values

**Purpose:** Test hierarchical data structure

**curl:**
```bash
curl -s -X POST http://localhost:9080/graphql \
  -H "Content-Type: application/json" \
  -d '{
    "query": "mutation { set(path: \"/risks/risk-002\", value: \"{\\\"id\\\": \\\"risk-002\\\", \\\"title\\\": \\\"System Outage\\\"}\", info: {message: \"Add outage risk\", author: \"test-suite\"}) { hash } }"
  }'
```

**Then list contents:**
```bash
curl -s -X POST http://localhost:9080/graphql \
  -H "Content-Type: application/json" \
  -d '{"query": "{ main { tree { list { ... on Contents { path value } } } } }"}'
```

**Expected Response:**
```json
{
  "data": {
    "main": {
      "tree": {
        "list": [
          {
            "path": "/risks/risk-001",
            "value": "<json-value>"
          },
          {
            "path": "/risks/risk-002",
            "value": "<json-value>"
          }
        ]
      }
    }
  }
}
```

**Success Criteria:**
- Both values stored under `/risks/` path
- Can list tree contents
- Response < 200ms

---

#### IR-8: Update Existing Value

**Purpose:** Test value modification

**curl:**
```bash
curl -s -X POST http://localhost:9080/graphql \
  -H "Content-Type: application/json" \
  -d '{
    "query": "mutation { set(path: \"/risks/risk-001\", value: \"{\\\"id\\\": \\\"risk-001\\\", \\\"title\\\": \\\"Data Breach Risk (Updated)\\\", \\\"probability\\\": 0.25}\", info: {message: \"Update probability\", author: \"test-suite\"}) { hash } }"
  }'
```

**Then verify history:**
```bash
curl -s -X POST http://localhost:9080/graphql \
  -H "Content-Type: application/json" \
  -d '{"query": "{ main { head { parents } } }"}'
```

**Success Criteria:**
- New commit created
- Parents array contains previous commit hash
- Updated value retrievable
- Response < 200ms

---

#### IR-9: Remove Value

**Purpose:** Test deletion operation

**curl:**
```bash
curl -s -X POST http://localhost:9080/graphql \
  -H "Content-Type: application/json" \
  -d '{
    "query": "mutation { remove(path: \"/risks/risk-002\", info: {message: \"Remove risk-002\", author: \"test-suite\"}) { hash } }"
  }'
```

**Then verify removal:**
```bash
curl -s -X POST http://localhost:9080/graphql \
  -H "Content-Type: application/json" \
  -d '{"query": "{ main { tree { get(path: \"/risks/risk-002\") } } }"}'
```

**Expected Response:**
```json
{
  "data": {
    "main": {
      "tree": {
        "get": null
      }
    }
  }
}
```

**Success Criteria:**
- Remove returns commit hash
- Deleted path returns null
- Response < 200ms

---

#### IR-10: Commit by Hash Lookup

**Purpose:** Verify time-travel queries

**curl:**
```bash
# First get a commit hash
HASH=$(curl -s -X POST http://localhost:9080/graphql \
  -H "Content-Type: application/json" \
  -d '{"query": "{ main { head { hash } } }"}' | jq -r '.data.main.head.hash')

# Then query that specific commit
curl -s -X POST http://localhost:9080/graphql \
  -H "Content-Type: application/json" \
  -d "{\"query\": \"{ commit(hash: \\\"$HASH\\\") { hash info { message } tree { path } } }\"}"
```

**Success Criteria:**
- Can retrieve any historical commit by hash
- Returns commit metadata and tree state
- Response < 150ms

---

### Irmin Automated Test Script

```bash
#!/bin/bash
set -e

BASE_URL="http://localhost:9080/graphql"
PASS=0
FAIL=0

echo "=== Irmin GraphQL Server Test Suite ==="
echo

# Helper function
test_graphql() {
    local test_name="$1"
    local query="$2"
    local expected_key="$3"
    
    echo "Test: $test_name"
    RESPONSE=$(curl -s -w "\n%{http_code}" \
        -X POST "$BASE_URL" \
        -H "Content-Type: application/json" \
        -d "{\"query\": \"$query\"}")
    
    HTTP_CODE=$(echo "$RESPONSE" | tail -n1)
    BODY=$(echo "$RESPONSE" | head -n-1)
    
    if [ "$HTTP_CODE" = "200" ]; then
        if [ -n "$expected_key" ]; then
            HAS_KEY=$(echo "$BODY" | jq "has(\"$expected_key\")")
            if [ "$HAS_KEY" = "true" ]; then
                echo "✅ PASS"
                ((PASS++))
            else
                echo "❌ FAIL (Missing key: $expected_key)"
                echo "$BODY"
                ((FAIL++))
            fi
        else
            echo "✅ PASS"
            ((PASS++))
        fi
    else
        echo "❌ FAIL (HTTP $HTTP_CODE)"
        echo "$BODY"
        ((FAIL++))
    fi
    echo
}

# IR-1: Health Check
test_graphql "IR-1: Health Check" "{ __typename }" "data"

# IR-2: Schema Introspection
test_graphql "IR-2: Schema Introspection" \
    "{ __schema { queryType { name } } }" "data"

# IR-3: Query Empty Repo
test_graphql "IR-3: Query Empty Repository" \
    "{ main { name } }" "data"

# IR-4: Write Data
test_graphql "IR-4: Write Data" \
    "mutation { set(path: \\\"/test/value\\\", value: \\\"test-data\\\", info: {message: \\\"test\\\", author: \\\"test-suite\\\"}) { hash } }" "data"

# IR-5: Read Data Back
test_graphql "IR-5: Read Data Back" \
    "{ main { tree { get(path: \\\"/test/value\\\") } } }" "data"

# Summary
echo "==================================="
echo "Irmin Tests: $PASS passed, $FAIL failed"
echo "==================================="

if [ $FAIL -gt 0 ]; then
    exit 1
fi
```

**Save as:** `docs/test/run-irmin-tests.sh`

---

### Irmin Performance Benchmarks

```bash
# Container startup time
docker compose logs irmin | grep -i "started"
# Expected: < 500ms

# Memory usage
docker stats irmin-graphql --no-stream
# Expected: ~100-150 MB RAM

# Image size
docker images register-irmin
# Expected: ~650-700 MB (includes OCaml toolchain for dev)

# Write operation latency
time curl -s -X POST http://localhost:9080/graphql \
  -H "Content-Type: application/json" \
  -d '{"query": "mutation { set(path: \"/perf-test\", value: \"data\", info: {message: \"test\", author: \"bench\"}) { hash } }"}'
# Expected: < 200ms

# Read operation latency
time curl -s -X POST http://localhost:9080/graphql \
  -H "Content-Type: application/json" \
  -d '{"query": "{ main { tree { get(path: \"/perf-test\") } } }"}'
# Expected: < 100ms
```

---

### Irmin Troubleshooting

**Container won't start:**
```bash
docker compose logs irmin
docker compose ps irmin
```

**GraphQL not responding:**
```bash
# Check if port is accessible
curl -v http://localhost:9080/graphql

# Check inside container
docker exec irmin-graphql sh -c 'wget -O - http://127.0.0.1:8080/graphql'
```

**Health check failing:**
```bash
# View health status
docker inspect irmin-graphql --format='{{.State.Health.Status}}'

# View health check logs
docker inspect irmin-graphql --format='{{range .State.Health.Log}}{{.Output}}{{end}}'
```

**Data not persisting:**
```bash
# Check volume
docker volume inspect register_irmin-data

# Verify mount
docker inspect irmin-graphql --format='{{json .Mounts}}' | jq .
```

---

### Irmin Data Model

Irmin organizes data as a content-addressable tree:

```
/
├── risks/
│   ├── risk-001  → JSON value (versioned)
│   ├── risk-002  → JSON value (versioned)
│   └── metadata  → JSON value (versioned)
├── assessments/
│   └── ...
└── controls/
    └── ...
```

**Each commit contains:**
- Hash (SHA-1, content-addressable)
- Parent commit(s)
- Author & message
- Timestamp
- Tree snapshot

**Benefits:**
- Full version history (time-travel queries)
- Branch/merge support for conflict resolution
- Immutable data structure
- GraphQL API for type-safe queries
