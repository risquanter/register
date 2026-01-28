# Testing Guide - Risk Register

## Overview

This document provides comprehensive testing procedures for the Risk Register application, covering:

1. **API Testing** - REST endpoint validation
2. **Container Testing** - Docker image verification
3. **Irmin GraphQL Testing** - Persistence layer validation
4. **Performance Testing** - Benchmarks and metrics

---

## Table of Contents

- [Prerequisites](#prerequisites)
- [API Testing - Register Server](#api-testing---register-server)
- [Container Testing](#container-testing)
- [Irmin GraphQL Server Tests](#irmin-graphql-server-tests)
- [Performance Benchmarks](#performance-benchmarks)
- [Automated Test Scripts](#automated-test-scripts)

---

## Prerequisites

```bash
# Clean environment
docker compose down -v
docker system prune -f

# Build and start services
docker compose --profile persistence up -d --build

# Wait for startup
sleep 2
```

**Services:**
- Register Server: `http://localhost:8080`
- Irmin GraphQL: `http://localhost:9080/graphql`

---

## API Testing - Register Server

### Test Environment

**Image:** `register-server:native` (118MB, distroless, static binary)  
**Base URL:** `http://localhost:8080`  
**Deployment:** Docker Compose with production configuration

### Payload Fixtures (New API Shapes)

- [docs/test/create-risk-tree.json](docs/test/create-risk-tree.json): Complex tree with three portfolios and two leaves (name-based topology, no IDs)
- [docs/test/create-simple-risk.json](docs/test/create-simple-risk.json): Minimal tree with a single leaf under a root portfolio
- [docs/test/create-lognormal-risk.json](docs/test/create-lognormal-risk.json): Single lognormal leaf under a root portfolio
- [docs/test/update-risk-tree-template.json](docs/test/update-risk-tree-template.json): PUT template; replace `REPLACE_*` tokens with IDs from create response before sending

### Protocol Cheatsheet

- POST `/api/risk-trees` uses `portfolios` + `leaves` with `parentName` resolved by name; exactly one portfolio has `parentName = null` (root) and leaves must reference portfolios.
- PUT `/api/risk-trees/{treeId}` mixes existing nodes (with `id`) and new nodes (without). Topology still uses `parentName` by name. Nodes omitted from the request are deleted unless that would leave a portfolio empty.
- PATCH `/api/risk-trees/{treeId}/nodes/{nodeUlid}/distribution` updates only a leaf distribution. PATCH `/api/risk-trees/{treeId}/nodes/{nodeUlid}` renames any node.
- DELETE `/api/risk-trees/{treeId}/nodes/{nodeUlid}` rejects deleting the root and rejects deleting the only child of a portfolio; portfolios delete their entire subtree.
- All names must be unique per tree; parents must be portfolios; cycles are rejected.

### Test Cases (New API)

### 1. Health Check

**Purpose:** Verify service is running and responding

```bash
curl -X GET http://localhost:8080/health
```

**Expect:** HTTP 200 with `status=healthy`; latency < 50ms

---

### 2. Create Risk Tree (Complex)

**Purpose:** POST a multi-node tree using the new DTO shape

```bash
curl -i -X POST http://localhost:8080/api/risk-trees \
  -H "Content-Type: application/json" \
  -d @docs/test/create-risk-tree.json -o /tmp/create-complex.json
```

**Expect:** HTTP 201 Created, body includes `id`, `name`, and `nodes` with ULIDs and `parentId`. Verify one root (`parentId = null`) and no duplicate names.

---

### 3. Create Simple Risk Tree

**Purpose:** POST a minimal tree (one leaf under a root portfolio)

```bash
curl -i -X POST http://localhost:8080/api/risk-trees \
  -H "Content-Type: application/json" \
  -d @docs/test/create-simple-risk.json -o /tmp/create-simple.json
```

**Expect:** HTTP 201 Created; nodes array contains a single portfolio root and one leaf with matching `parentId`.

---

### 4. List Risk Trees

**Purpose:** Verify list endpoint returns created trees

```bash
curl -s http://localhost:8080/api/risk-trees | jq '.'
```

**Expect:** HTTP 200; contains both tree IDs created above; latency < 50ms.

---

### 5. Get Risk Tree by ID

**Purpose:** Fetch the complex tree created in Test 2

```bash
COMPLEX_ID=$(jq -r '.id' /tmp/create-complex.json)
curl -s http://localhost:8080/api/risk-trees/$COMPLEX_ID | jq '.'
```

**Expect:** HTTP 200; response echoes portfolios/leaves with ULIDs and parent relationships.

---

### 6. Update Risk Tree (PUT)

**Purpose:** Exercise mixed existing/new nodes, renames, and topology changes

```bash
ROOT_ID=$(jq -r '.nodes[] | select(.parentId==null) | .id' /tmp/create-complex.json)
APPS_ID=$(jq -r '.nodes[] | select(.name=="Applications") | .id' /tmp/create-complex.json)
REVENUE_ID=$(jq -r '.nodes[] | select(.name=="Revenue Loss") | .id' /tmp/create-complex.json)
sed "s/REPLACE_ROOT_ID/$ROOT_ID/g; s/REPLACE_APPS_ID/$APPS_ID/g; s/REPLACE_REVENUE_ID/$REVENUE_ID/g" \
  docs/test/update-risk-tree-template.json > /tmp/update-risk-tree.json
curl -i -X PUT http://localhost:8080/api/risk-trees/$COMPLEX_ID \
  -H "Content-Type: application/json" \
  -d @/tmp/update-risk-tree.json -o /tmp/update-response.json
```

**Expect:** HTTP 200; updated names/parents applied; new portfolio and leaf created; omitted nodes removed only if parent is not left empty.

---

### 7. Patch Leaf Distribution

**Purpose:** Verify targeted distribution update without full PUT

```bash
curl -i -X PATCH http://localhost:8080/api/risk-trees/$COMPLEX_ID/nodes/$REVENUE_ID/distribution \
  -H "Content-Type: application/json" \
  -d '{"distributionType":"lognormal","probability":0.9,"minLoss":45000,"maxLoss":350000}'
```

**Expect:** HTTP 200; updated leaf distribution; parent and siblings unchanged.

---

### 8. Rename Node

**Purpose:** Rename a node using PATCH

```bash
curl -i -X PATCH http://localhost:8080/api/risk-trees/$COMPLEX_ID/nodes/$APPS_ID \
  -H "Content-Type: application/json" \
  -d '{"name":"Applications and Services"}'
```

**Expect:** HTTP 200; name is unique and reflected in subsequent GETs.

---

### 9. Delete Node

**Purpose:** Delete a non-root leaf while honoring the only-child guard

```bash
curl -i -X DELETE http://localhost:8080/api/risk-trees/$COMPLEX_ID/nodes/$REVENUE_ID -o /tmp/delete-leaf.json
```

**Expect:** HTTP 200; leaf removed; portfolio still has at least one child; root unchanged.

---

### 10. Delete Tree

**Purpose:** Full cleanup of the complex tree

```bash
curl -i -X DELETE http://localhost:8080/api/risk-trees/$COMPLEX_ID
```

**Expect:** HTTP 204 No Content; subsequent GET by id returns 200 with `null` body.

---

### 11. LEC (Loss Exceedance Curve)

**Purpose:** Validate simulation endpoints on the simple tree

```bash
SIMPLE_ID=$(jq -r '.id' /tmp/create-simple.json)
curl -s "http://localhost:8080/api/risk-trees/$SIMPLE_ID/lec?depth=0&nTrials=5000" | jq '.'
```

**Expect:** HTTP 200; `lec.quantiles` present and ordered; response < 2000ms for 5k trials.

---

### 12. Validation Errors (Structural)

- Missing parent reference: POST with `parentName` not present → HTTP 400 `MISSING_REFERENCE`
- Duplicate names: POST with repeated name across portfolios/leaves → HTTP 400 `AMBIGUOUS_REFERENCE`
- Leaf as parent: POST leaf referenced as parent → HTTP 400 `LEAF_AS_PARENT`
- Multiple or zero roots: POST where 0 or >1 portfolios have `parentName = null` → HTTP 400
- Invalid distribution: PATCH/POST with invalid parameters (e.g., missing `minLoss` for lognormal) → HTTP 400

These tests confirm invariants match ADR-017 (unique names, single root, parents are portfolios, no cycles, valid distributions).

---

## Container Testing

### Overview

Verify Docker container functionality, resource usage, and security posture.

### CT-1: Container Startup

**Purpose:** Verify container starts successfully

```bash
docker run --rm -d --name register-test -p 8080:8080 register-server:latest
```

**Expected Output:**
```
<container-id>
```

**Success Criteria:**
- Container starts without errors
- Port 8080 is mapped
- Container reaches healthy state within 10s

---

### CT-2: Startup Logs

**Purpose:** Verify application initialization

```bash
docker logs register-test 2>&1 | head -20
```

**Expected Output:**
```
timestamp=2026-01-17T... level=INFO message="Bootstrapping Risk Register application..."
timestamp=2026-01-17T... level=INFO message="Server config: host=0.0.0.0, port=8080"
timestamp=2026-01-17T... level=INFO message="CORS allowed origins: ..."
timestamp=2026-01-17T... level=INFO message="Registered 10 HTTP endpoints"
timestamp=2026-01-17T... level=INFO message="Starting HTTP server on 0.0.0.0:8080..."
timestamp=2026-01-17T... level=INFO message="Server started"
```

**Success Criteria:**
- ✅ Application bootstrapped
- ✅ Server configuration loaded
- ✅ Endpoints registered (10 endpoints)
- ✅ Server started (< 100ms for native)

---

### CT-3: Resource Usage

**Purpose:** Verify memory and CPU efficiency

```bash
docker stats register-test --no-stream --format "table {{.Container}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}"
```

**Expected Output:**
```
CONTAINER       CPU %    MEM USAGE / LIMIT      MEM %
register-test   0.03%    50-80MiB / 31GiB      0.15-0.25%
```

**Success Criteria:**
- **CPU:** ~0.03% (idle)
- **Memory:** 50-80 MB (native binary)
- **Comparison:** 75-85% less memory than JVM (~250MB)

---

### CT-4: Image Size

**Purpose:** Verify minimal image footprint

```bash
docker images register-server:latest --format "{{.Repository}}:{{.Tag}}\t{{.Size}}"
```

**Expected Output:**
```
register-server:latest    118MB
```

**Success Criteria:**
- **Total:** ~118 MB
- **Breakdown:**
  - Native binary: 80-90 MB
  - Distroless base: ~2 MB
  - Resources: 10-20 MB

---

### CT-5: Security Verification

**Purpose:** Verify distroless security posture

**Test 5a: No Shell Access**

```bash
docker exec -it register-test /bin/sh
```

**Expected:** `exec failed: no such file or directory` (distroless has no shell)

**Test 5b: Non-Root User**

```bash
docker inspect register-test --format='{{.Config.User}}'
```

**Expected:** `nonroot` or `65532:65532`

**Test 5c: Vulnerability Scan**

```bash
trivy image register-server:latest
```

**Expected:** No HIGH or CRITICAL CVEs

**Success Criteria:**
- ✅ No shell access
- ✅ Runs as non-root
- ✅ Minimal attack surface
- ✅ No dynamic libraries
- ✅ Clean vulnerability scan

---

### CT-6: Health Check Mechanism

**Purpose:** Verify Docker health check works

```bash
# Wait for health check to run
sleep 30

# Check health status
docker inspect register-test --format='{{.State.Health.Status}}'
```

**Expected Output:**
```
healthy
```

**View Health Logs:**
```bash
docker inspect register-test --format='{{range .State.Health.Log}}{{.Output}}{{end}}'
```

**Success Criteria:**
- Health check passes
- Status transitions: starting → healthy
- Health endpoint responds correctly

---

### CT-7: Container Restart Behavior

**Purpose:** Verify container restarts gracefully

```bash
# Restart container
docker restart register-test

# Wait for restart
sleep 3

# Check health
curl -s http://localhost:8080/health
```

**Expected:**
- Container restarts successfully
- Health endpoint responds within 5s
- No data loss (in-memory state reset is expected)

---

### CT-8: Port Mapping

**Purpose:** Verify network configuration

```bash
docker port register-test
```

**Expected Output:**
```
8080/tcp -> 0.0.0.0:8080
8080/tcp -> [::]:8080
```

**Test Access:**
```bash
# From host
curl http://localhost:8080/health

# From another container
docker run --rm --network container:register-test alpine wget -qO- http://localhost:8080/health
```

---

### CT-9: Log Output

**Purpose:** Verify structured logging

```bash
docker logs register-test 2>&1 | grep -E "level=(INFO|WARN|ERROR)"
```

**Success Criteria:**
- Logs are structured (timestamp, level, message)
- No ERROR or WARN during normal operation
- JSON or key-value format
- Proper log levels used

---

### CT-10: Cleanup

**Purpose:** Verify proper shutdown

```bash
# Stop container gracefully
docker stop register-test

# Check exit code
docker inspect register-test --format='{{.State.ExitCode}}'
```

**Expected Exit Code:** `0` (graceful shutdown)

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

## Automated Test Script (New API)

```bash
#!/bin/bash
set -e

BASE_URL="http://localhost:8080"
PASS=0
FAIL=0

echo "=== Risk Tree API Test Suite (New DTOs) ==="
echo

check() {
  local name="$1" http_code="$2" expected="$3"
  if [ "$http_code" = "$expected" ]; then
    echo "✅ PASS - $name"
    ((PASS++))
  else
    echo "❌ FAIL - $name (HTTP $http_code, expected $expected)"
    ((FAIL++))
  fi
}

# 1) Health
HTTP=$(curl -s -w "%{http_code}" -o /tmp/health.json "$BASE_URL/health")
check "Health" "$HTTP" "200"

# 2) Create complex tree
CREATE_COMPLEX=$(curl -s -w "\n%{http_code}" -o /tmp/create-complex.json \
  -X POST "$BASE_URL/api/risk-trees" \
  -H "Content-Type: application/json" \
  -d @docs/test/create-risk-tree.json)
HTTP_COMPLEX=$(echo "$CREATE_COMPLEX" | tail -n1)
check "Create complex tree" "$HTTP_COMPLEX" "201"
COMPLEX_ID=$(jq -r '.id' /tmp/create-complex.json)
ROOT_ID=$(jq -r '.nodes[] | select(.parentId==null) | .id' /tmp/create-complex.json)
APPS_ID=$(jq -r '.nodes[] | select(.name=="Applications") | .id' /tmp/create-complex.json)
REVENUE_ID=$(jq -r '.nodes[] | select(.name=="Revenue Loss") | .id' /tmp/create-complex.json)

# 3) Create simple tree
CREATE_SIMPLE=$(curl -s -w "\n%{http_code}" -o /tmp/create-simple.json \
  -X POST "$BASE_URL/api/risk-trees" \
  -H "Content-Type: application/json" \
  -d @docs/test/create-simple-risk.json)
HTTP_SIMPLE=$(echo "$CREATE_SIMPLE" | tail -n1)
check "Create simple tree" "$HTTP_SIMPLE" "201"
SIMPLE_ID=$(jq -r '.id' /tmp/create-simple.json)

# 4) List trees
LIST_HTTP=$(curl -s -w "%{http_code}" -o /tmp/list.json "$BASE_URL/api/risk-trees")
check "List trees" "$LIST_HTTP" "200"

# 5) GET by id
GET_HTTP=$(curl -s -w "%{http_code}" -o /tmp/get-complex.json "$BASE_URL/api/risk-trees/$COMPLEX_ID")
check "Get complex tree" "$GET_HTTP" "200"

# 6) PUT update (mixed existing/new nodes)
sed "s/REPLACE_ROOT_ID/$ROOT_ID/g; s/REPLACE_APPS_ID/$APPS_ID/g; s/REPLACE_REVENUE_ID/$REVENUE_ID/g" \
  docs/test/update-risk-tree-template.json > /tmp/update-risk-tree.json
PUT_HTTP=$(curl -s -w "%{http_code}" -o /tmp/put.json \
  -X PUT "$BASE_URL/api/risk-trees/$COMPLEX_ID" \
  -H "Content-Type: application/json" \
  -d @/tmp/update-risk-tree.json)
check "PUT update" "$PUT_HTTP" "200"

# 7) PATCH distribution
PATCH_DIST_HTTP=$(curl -s -w "%{http_code}" -o /tmp/patch-dist.json \
  -X PATCH "$BASE_URL/api/risk-trees/$COMPLEX_ID/nodes/$REVENUE_ID/distribution" \
  -H "Content-Type: application/json" \
  -d '{"distributionType":"lognormal","probability":0.9,"minLoss":45000,"maxLoss":350000}')
check "PATCH distribution" "$PATCH_DIST_HTTP" "200"

# 8) PATCH rename
PATCH_RENAME_HTTP=$(curl -s -w "%{http_code}" -o /tmp/patch-rename.json \
  -X PATCH "$BASE_URL/api/risk-trees/$COMPLEX_ID/nodes/$APPS_ID" \
  -H "Content-Type: application/json" \
  -d '{"name":"Applications and Services"}')
check "PATCH rename" "$PATCH_RENAME_HTTP" "200"

# 9) LEC (root only)
LEC_HTTP=$(curl -s -w "%{http_code}" -o /tmp/lec.json \
  "$BASE_URL/api/risk-trees/$SIMPLE_ID/lec?depth=0&nTrials=5000")
HAS_QUANTILES=$(jq 'has("lec") and .lec | has("quantiles")' /tmp/lec.json)
if [ "$LEC_HTTP" = "200" ] && [ "$HAS_QUANTILES" = "true" ]; then
  echo "✅ PASS - LEC root"
  ((PASS++))
else
  echo "❌ FAIL - LEC root (HTTP $LEC_HTTP)"
  ((FAIL++))
fi

# 10) Delete complex tree (cleanup)
DEL_HTTP=$(curl -s -w "%{http_code}" -o /tmp/delete.json \
  -X DELETE "$BASE_URL/api/risk-trees/$COMPLEX_ID")
check "Delete complex tree" "$DEL_HTTP" "204"

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
