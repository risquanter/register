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
- [SBT Unit & Integration Tests](#sbt-unit--integration-tests)
- [API Testing - Register Server](#api-testing---register-server)
- [Container Testing](#container-testing)
- [BATS Smoke & Integration Tests](#bats-smoke--integration-tests)
- [Irmin GraphQL Server Tests](#irmin-graphql-server-tests)
- [Performance Benchmarks](#performance-benchmarks)

---

## Prerequisites


# Clean environment
docker compose down -v
docker system prune -f

> **One-time setup:** `local/irmin:3.11` is not available from any public registry
> and must be built locally before the first run. This takes 15–40 minutes:
>
> ```bash
> docker build -f dev/Dockerfile.irmin -t local/irmin:3.11 dev/
> ```
>
> Re-run this only when `dev/Dockerfile.irmin` changes.

```bash

# Build and start services (uses pre-built local/irmin:3.11)
docker compose --profile persistence up -d --build

# Wait for startup
sleep 2
```

**Services:**
- Register Server: `http://localhost:8090`
- Irmin GraphQL: `http://localhost:9080/graphql`

---

## SBT Unit & Integration Tests

### Quick Reference

All commands filter output to show pass/fail counts, individual failures, and compilation errors.

```bash
# All tests (common + server + app + integration — requires Docker)
sbt 'commonJVM/test; server/test; app/test; serverIt/test' 2>&1 | grep -E 'tests passed|tests failed|FAILED|FAIL|error.*Tests|\[error\]|success|Executed in'

# Unit tests only (common + server, no integration)
sbt 'commonJVM/test; server/test' 2>&1 | grep -E 'tests passed|tests failed|FAILED|FAIL|error.*Tests|\[error\]|success|Executed in'

# Server tests only
sbt server/test 2>&1 | grep -E 'tests passed|tests failed|FAILED|FAIL|error.*Tests|\[error\]|success|Executed in'

# Integration tests only
sbt 'serverIt/test' 2>&1 | grep -E 'tests passed|tests failed|FAILED|Failed|PASS|\+.*test|\-.*test|error.*Tests|\[error\]|success|Executed in' | head -40
```

### Integration test infrastructure

`IrminCompose` uses `docker-compose.server-it.yml` (not the main `docker-compose.yml`)
to start each Irmin container with a **dynamic host port**. This means:
- Multiple specs run concurrently without port 9080 conflicts.
- Dev stack (`docker compose --profile persistence up`) is unaffected — it still uses port 9080.
- `IrminCompose` discovers the assigned port via `docker compose port irmin 8080` after startup.

### Docker Cleanup

When integration tests leave stale containers after a crash or Ctrl+C:

```bash
docker ps -a --filter name=register_it_ --format '{{.ID}}' | xargs -r docker rm -f
docker network ls --filter name=register_it_ --format '{{.ID}}' | xargs -r docker network rm
```

---

## API Testing - Register Server

### Test Environment

**Image:** `register-server:native` (118MB, distroless, static binary)  
**Base URL:** `http://localhost:8090`  
**Deployment:** Docker Compose with production configuration

### Health Check

**Purpose:** Verify service is running and responding

```bash
curl -X GET http://localhost:8090/health
```

**Expect:** HTTP 200 with `status=healthy`; latency < 50ms

---

## Container Testing

### Overview

Verify Docker container functionality, resource usage, and security posture.

### CT-1: Container Startup

**Purpose:** Verify container starts successfully

```bash
docker run --rm -d --name register-test -p 8090:8090 register-server:latest
```

**Expected Output:**
```
<container-id>
```

**Success Criteria:**
- Container starts without errors
- Port 8090 is mapped
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
timestamp=2026-01-17T... level=INFO message="Starting HTTP server on 0.0.0.0:8090..."
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
curl -s http://localhost:8090/health
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
8090/tcp -> 0.0.0.0:8090
8090/tcp -> [::]:8090
```

**Test Access:**
```bash
# From host
curl http://localhost:8090/health

# From another container
docker run --rm --network container:register-test alpine wget -qO- http://localhost:8090/health
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

## BATS Smoke & Integration Tests

### Overview

Three automated [BATS](https://github.com/bats-core/bats-core) suites validate Docker
images, compose topology, nginx routing, and end-to-end persistence **after**
container images are built. Tests run inside a purpose-built runner image
(`local/bats-runner:1.11`) that contains bash, bats-core 1.11, curl, jq, and
the Docker CLI + compose plugin.

| Suite | File | Services | Tests | Purpose |
|-------|------|----------|-------|---------|
| **A** | `tests/bats/suite-a-full-prod.bats` | server + frontend + irmin | 5 | E2E: data flows nginx → server → Irmin and is verifiable |
| **B** | `tests/bats/suite-b-irmin-prod.bats` | irmin (standalone) | 5 | Irmin image security & GraphQL round-trip |
| **C** | `tests/bats/suite-c-in-memory.bats` | server + frontend | 16 | In-memory mode — nginx routing & ADR-027 validation |

### Prerequisites

1. **Build production images** (from the project root):

```bash
# Server (GraalVM native)
docker build -f Dockerfile.native -t local/register-server:<version> .

# Frontend (nginx)
docker build -f containers/prod/Dockerfile.frontend-prod \
  -t local/frontend:<version> .

# Irmin builder + prod (needed for suites A and B)
docker build -f containers/prod/Dockerfile.irmin-builder \
  -t local/irmin-builder:3.11-p1 containers/prod/
docker build -f containers/prod/Dockerfile.irmin-prod \
  -t local/irmin-prod:3.11-p1 containers/prod/
```

2. **Build the BATS runner image**:

```bash
docker build -f containers/dev/Dockerfile.bats-runner \
  -t local/bats-runner:1.11 containers/dev/
```

3. **Docker socket access**: The runner needs access to the host Docker socket
   to manage compose services during tests.

### Running the Suites

All suites are invoked the same way — only the `.bats` file changes:

```bash
run_bats() {
  docker run --rm --userns=host --network host \
    --group-add "$(stat -c '%g' /var/run/docker.sock)" \
    -e HOME=/tmp \
    -v /var/run/docker.sock:/var/run/docker.sock \
    -v "$(dirname "$(pwd)")":"$(dirname "$(pwd)")" \
    -w "$(pwd)" \
    local/bats-runner:1.11 "$1"
}

run_bats tests/bats/suite-c-in-memory.bats   # Suite C — quickest, no Irmin dependency (start here)
run_bats tests/bats/suite-a-full-prod.bats   # Suite A — E2E with Irmin persistence (REGISTER_REPOSITORY_TYPE=irmin)
run_bats tests/bats/suite-b-irmin-prod.bats  # Suite B — standalone Irmin image validation
```

Why each flag — the runner drives the **host's** Docker daemon from inside a
container, which makes four things fragile (all four bite on a daemon running
with user-namespace remapping):

- `--userns=host` — a remapped daemon refuses `--network host` otherwise.
  No-op on daemons without remapping, so the command is portable.
- `--group-add $(stat -c '%g' /var/run/docker.sock)` — puts the container
  user in the socket's group; without it every Docker call is
  permission-denied.
- `-e HOME=/tmp` — the Docker CLI writes `$HOME/.docker`; the image's
  baked-in home is not writable once the ID mapping changes.
- Mount the repo's **parent** directory at its **identical host path** (not
  `/workspace`) — compose build contexts (`..` for the frontend/builder
  images) are resolved by the host daemon, so every path the runner passes
  must mean the same thing on the host. A `/workspace` mount fails with
  errors like `lstat /register: no such file or directory`.

### Which Suite to Run When

| Scenario | Suite(s) |
|----------|----------|
| Quick smoke test after code changes | C |
| Frontend/nginx config changes | C |
| Server ↔ Irmin integration changes | A |
| Irmin image or Dockerfile changes | B, then A |
| Full release validation | A + B + C |
| CI pipeline (fast gate) | C |
| CI pipeline (full gate) | A + B + C |

### Upgrading

When upgrading dependencies (alpine, bats-core, docker CLI, etc.):

1. Update pinned versions in `containers/dev/Dockerfile.bats-runner`
2. If bats-core version changes, update the SHA256 checksum in the Dockerfile
3. Rebuild the runner image: `docker build -f containers/dev/Dockerfile.bats-runner -t local/bats-runner:1.11 containers/dev/`
4. Re-run all three suites to validate

When adding new test cases, follow the naming convention `@test "X## — description"` where `X` is the suite letter and `##` is the zero-padded number.

### File Layout

```
tests/bats/
├── helpers/
│   └── setup.bash              # Shared helpers: ports, wait_for_url, create_workspace
├── suite-a-full-prod.bats      # Suite A — E2E: nginx → server → Irmin
├── suite-b-irmin-prod.bats     # Suite B — standalone irmin-prod
└── suite-c-in-memory.bats      # Suite C — in-memory mode

containers/dev/
└── Dockerfile.bats-runner      # BATS runner image definition
```

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
time curl -X GET http://localhost:8090/health
# Expected: < 50ms
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
