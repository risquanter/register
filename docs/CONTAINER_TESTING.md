# Container Testing Guide

This document provides testing procedures for the Risk Register containerized application.

## Table of Contents
- [Native Image Container Testing](#native-image-container-testing)
- [Expected Outputs](#expected-outputs)
- [Verification Steps](#verification-steps)

---

## Native Image Container Testing

### 1. Start the Container

```bash
docker run --rm -d --name register-distroless-test -p 8080:8080 register-distroless-static:test
```

**Expected Output:**
```
<container-id>
```

**What it does:**
- Starts the distroless native image container in detached mode
- Maps port 8080 (host) → 8080 (container)
- Container auto-removes on stop (`--rm`)
- Names the container `register-distroless-test`

---

### 2. Check Health Endpoint

```bash
sleep 2 && curl -s http://localhost:8080/health
```

**Expected Output:**
```json
{"status":"healthy","service":"risk-register"}
```

**What it does:**
- Waits 2 seconds for container startup
- Queries the health check endpoint
- Returns JSON health status

---

### 3. View Startup Logs

```bash
docker logs register-distroless-test 2>&1 | head -20
```

**Expected Output:**
```
timestamp=2026-01-12T14:37:23.208103Z level=INFO thread=#zio-fiber-1017009078 message="Bootstrapping Risk Register application..." location=com.risquanter.register.Application.program file=Application.scala line=81
timestamp=2026-01-12T14:37:23.208281Z level=INFO thread=#zio-fiber-1017009078 message="Server config: host=0.0.0.0, port=8080" location=com.risquanter.register.Application.startServer file=Application.scala line=57
timestamp=2026-01-12T14:37:23.208300Z level=INFO thread=#zio-fiber-1017009078 message="CORS allowed origins: http://localhost:3000, http://localhost:5173" location=com.risquanter.register.Application.startServer file=Application.scala line=58
timestamp=2026-01-12T14:37:23.213549Z level=INFO thread=#zio-fiber-1017009078 message="Registered 10 HTTP endpoints" location=com.risquanter.register.Application.startServer file=Application.scala line=60
timestamp=2026-01-12T14:37:23.213994Z level=INFO thread=#zio-fiber-1017009078 message="Starting HTTP server on 0.0.0.0:8080..." location=com.risquanter.register.Application.startServer file=Application.scala line=76
timestamp=2026-01-12T14:37:23.214045Z level=INFO thread=#zio-fiber-1017009078 message="Starting the server..." location=com.risquanter.register.Application.startServer file=Application.scala line=77
timestamp=2026-01-12T14:37:23.217151Z level=INFO thread=#zio-fiber-1017009078 message="Server started" location=com.risquanter.register.Application.startServer file=Application.scala line=77
```

**Key Indicators:**
- ✅ Application bootstrapped successfully
- ✅ Server configuration loaded (0.0.0.0:8080)
- ✅ CORS origins configured
- ✅ 10 HTTP endpoints registered
- ✅ Server started (~5ms startup time for native binary)

---

### 4. Check Resource Usage

```bash
docker stats register-distroless-test --no-stream --format "table {{.Container}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}"
```

**Expected Output:**
```
CONTAINER                  CPU %     MEM USAGE / LIMIT     MEM %
register-distroless-test   0.03%     53.04MiB / 31.31GiB   0.17%
```

**Performance Characteristics:**
- **CPU Usage:** ~0.03% (idle)
- **Memory Usage:** ~53 MB (native binary overhead)
- **Memory %:** ~0.17% of system RAM

**Comparison with JVM:**
- JVM baseline: ~200-300 MB
- Native image: ~53 MB
- **Savings:** ~75-85% memory reduction

---

### 5. Check Image Size

```bash
docker images register-distroless-static:test --format "{{.Repository}}:{{.Tag}}\t{{.Size}}"
```

**Expected Output:**
```
register-distroless-static:test	118MB
```

**Image Size Breakdown:**
- Native binary: ~80-90 MB
- Distroless base: ~2 MB
- Application resources: ~10-20 MB
- **Total:** 118 MB

**Comparison:**
- Previous (debian:bookworm-slim): 200 MB
- Current (distroless/static): **118 MB**
- **Reduction:** 41% smaller

---

## Verification Steps

### Quick Health Check
```bash
# All-in-one verification
docker run --rm -d --name register-test -p 8080:8080 register-distroless-static:test && \
  sleep 2 && \
  curl -s http://localhost:8080/health && \
  docker stop register-test
```

**Expected:** Health check returns `{"status":"healthy","service":"risk-register"}`

### Full API Test
```bash
# Test Swagger UI endpoint
curl -I http://localhost:8080/docs

# Test API endpoints
curl -s http://localhost:8080/api/health
curl -s http://localhost:8080/api/risk-trees
```

**Expected:**
- `/docs` → 200 OK (Swagger UI)
- `/api/health` → 200 OK with health JSON
- `/api/risk-trees` → 200 OK with risk trees data or empty array

---

## Cleanup

### Stop Container
```bash
docker stop register-distroless-test
```

### Remove Image
```bash
docker rmi register-distroless-static:test
```

### Remove All Test Containers
```bash
docker ps -a --filter "name=register-" -q | xargs -r docker rm -f
```

---

## Troubleshooting

### Container Won't Start
```bash
# Check container logs
docker logs register-distroless-test

# Check if port is already in use
sudo lsof -i :8080

# Try with verbose output
docker run --rm --name register-test -p 8080:8080 register-distroless-static:test
```

### Health Check Fails
```bash
# Check if container is running
docker ps --filter "name=register-distroless-test"

# Check container internal logs
docker logs register-distroless-test --tail 50

# Test with localhost and 127.0.0.1
curl http://localhost:8080/health
curl http://127.0.0.1:8080/health
```

### High Memory Usage
```bash
# Monitor memory over time
docker stats register-distroless-test

# Check for memory leaks (run for 5 minutes)
watch -n 5 'docker stats register-distroless-test --no-stream'
```

---

## Performance Benchmarks

### Startup Time
- **JVM mode:** ~3-5 seconds
- **Native image:** ~5-10 milliseconds
- **Improvement:** ~500x faster startup

### Memory Footprint
- **JVM mode:** ~200-300 MB baseline
- **Native image:** ~53 MB baseline
- **Improvement:** ~75-85% memory reduction

### Image Size
- **JVM mode (debian:bookworm-slim):** 200 MB
- **Native image (distroless/static):** 118 MB
- **Improvement:** 41% size reduction

---

## Security Notes

### Distroless Benefits
- ✅ No shell (`/bin/sh` not present)
- ✅ No package manager
- ✅ Minimal attack surface (~2 MB base)
- ✅ Non-root user (`nonroot:nonroot`)
- ✅ Static binary (no dynamic library dependencies)

### Verify Security
```bash
# Try to exec into container (should fail - no shell)
docker exec -it register-distroless-test /bin/sh
# Expected: OCI runtime exec failed: exec failed: unable to start container process: exec: "/bin/sh": stat /bin/sh: no such file or directory

# Check running user
docker exec register-distroless-test id 2>/dev/null || echo "Cannot exec - distroless (expected)"

# Scan for vulnerabilities
docker scan register-distroless-static:test
# or
trivy image register-distroless-static:test
```
