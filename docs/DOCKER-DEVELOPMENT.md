# Docker & Development Guide

## Overview

This guide covers containerization, deployment, and development environment setup for the Risk Register project.

## Prerequisites

- Docker 20.10+ 
- Docker Compose 2.0+
- JDK 21 (for Scala development)
- sbt (Scala Build Tool)
- At least 4GB RAM available for Docker

---

## Quick Start

### Start All Services

```bash
# Start Risk Register server only
docker compose up -d

# Start with Irmin persistence layer
docker compose --profile persistence up -d

# View logs
docker compose logs -f

# Stop all services
docker compose down
```

### Check Status

```bash
# All services
docker compose ps

# Specific service
docker compose ps register-server
docker compose ps irmin
```

---

## Services

### Risk Register Server (Native Image)

**Image:** GraalVM native binary on distroless  
**Port:** 8080  
**Endpoints:**
- Health: `http://localhost:8080/health`
- API: `http://localhost:8080/api`
- Swagger: `http://localhost:8080/docs`

**Performance:**
- Startup: ~5-10ms
- Memory: ~50-80 MB
- Image size: ~118 MB

#### Build Options

```bash
# Build native image (production)
docker compose build register-server

# Build without cache
docker compose build --no-cache register-server

# Build and start
docker compose up --build register-server -d
```

#### Standalone Docker

```bash
# Build
docker build -t register-server:latest .

# Run
docker run -p 8080:8080 --name register-server register-server:latest
```

---

### Irmin GraphQL Server (Persistence Layer)

Irmin is a versioned key-value store with Git-like semantics. It provides content-addressable storage with full version history.

**Port:** 9080 (host) → 8080 (container)  
**Endpoint:** `http://localhost:9080/graphql`  
**GraphiQL UI:** `http://localhost:9080/graphql` (GET in browser)  
**Schema:** `dev/irmin-schema.graphql`

**Performance:**
- Startup: ~500ms
- Memory: ~100-150 MB
- Image size: ~650-700 MB (dev with OCaml toolchain)

#### Quick Start

```bash
# Start Irmin
docker compose --profile persistence up irmin -d

# Check status
docker compose ps irmin

# View logs
docker compose logs -f irmin

# Stop
docker compose stop irmin
```

#### Why Irmin?

- **Versioned data**: Every change tracked like Git
- **Branching/merging**: Offline-first with conflict resolution
- **GraphQL API**: Type-safe, introspectable queries
- **Content-addressable**: Immutable, cryptographically verified
- **Time-travel**: Query any historical state

**For detailed testing, see:** [Testing Guide - Irmin Section](test/TESTING.md#irmin-graphql-server-tests)

---

## Configuration

### Environment Variables

Configure via `docker-compose.yml` or `.env` file:

| Variable | Default | Description |
|----------|---------|-------------|
| `REGISTER_SERVER_HOST` | `0.0.0.0` | Server bind address |
| `REGISTER_SERVER_PORT` | `8080` | Server port |
| `REGISTER_DEFAULT_NTRIALS` | `10000` | Default simulation trials |
| `REGISTER_MAX_TREE_DEPTH` | `5` | Maximum risk tree depth |
| `REGISTER_PARALLELISM` | `8` | Parallel processing threads |
| `REGISTER_MAX_CONCURRENT_SIMULATIONS` | `4` | Max concurrent simulations |
| `REGISTER_MAX_NTRIALS` | `1000000` | Maximum trials per simulation |
| `REGISTER_MAX_PARALLELISM` | `16` | Maximum parallelism |
| `REGISTER_CORS_ORIGINS` | See config | Allowed CORS origins |
| `OTEL_SERVICE_NAME` | `risk-register` | OpenTelemetry service name |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4317` | OTLP endpoint |

### Custom Configuration

Edit `docker-compose.yml`:

```yaml
services:
  register-server:
    environment:
      REGISTER_DEFAULT_NTRIALS: "50000"
      REGISTER_PARALLELISM: "16"
```

Or use `.env` file:

```bash
# Create .env file
cat > .env <<EOF
REGISTER_DEFAULT_NTRIALS=50000
OTEL_EXPORTER_OTLP_ENDPOINT=https://prod-otlp:4317
EOF

# Start with env file
docker compose --env-file .env up -d
```

---

## Data Persistence

### Irmin Volume

Irmin data persists in Docker volume: `register_irmin-data`

```bash
# List volumes
docker volume ls | grep irmin

# Inspect volume
docker volume inspect register_irmin-data

# Backup volume
docker run --rm -v register_irmin-data:/data -v $(pwd):/backup \
  alpine tar czf /backup/irmin-backup.tar.gz -C /data .

# Restore volume
docker run --rm -v register_irmin-data:/data -v $(pwd):/backup \
  alpine tar xzf /backup/irmin-backup.tar.gz -C /data

# Remove volume (DESTROYS DATA)
docker volume rm register_irmin-data
```

---

## Development Workflow

### Local Development

```bash
# Start full stack
docker compose --profile persistence up -d

# Check status
docker compose ps

# View combined logs
docker compose logs -f

# Restart specific service
docker compose restart register-server
```

### Running Tests

```bash
# Scala unit tests
sbt test

# With coverage
sbt coverage test coverageReport

# Run test suite against containers
./docs/test/run-tests.sh
```

### Rebuilding

```bash
# Rebuild specific service
docker compose build register-server

# Rebuild without cache
docker compose build --no-cache

# Rebuild and restart
docker compose up --build -d
```

---

## Docker Images

### Multi-Stage Build (Register Server)

1. **Builder Stage**: `sbtscala/scala-sbt` - compiles Scala code
2. **GraalVM Stage**: `ghcr.io/graalvm/native-image-community` - builds native binary
3. **Runtime Stage**: `gcr.io/distroless/static-debian12:nonroot` - minimal runtime

**Benefits:**
- Static binary (no libc dependencies)
- Non-root user for security
- No shell or package manager
- Minimal attack surface (~2 MB base + binary)

### Multi-Stage Build (Irmin Server)

1. **Builder Stage**: `ocaml/opam:alpine-ocaml-5.2` - compiles OCaml packages
2. **Runtime Stage**: `alpine:3.21` - runs Irmin server

**Future Migration:**
- Structured for distroless runtime
- Will reduce from ~700 MB to ~100-150 MB
- Static linking with musl libc prepared

---

## Observability (Optional)

### OpenTelemetry Collector

```bash
# Start with observability stack
docker compose --profile observability up -d

# Access Prometheus metrics
curl http://localhost:8889/metrics

# View OTLP traces
# Configure your observability backend endpoint
```

### Configure External OTLP

```yaml
environment:
  OTEL_EXPORTER_OTLP_ENDPOINT: "https://your-platform:4317"
```

---

## Production Deployment

### Resource Limits

```yaml
services:
  register-server:
    deploy:
      resources:
        limits:
          cpus: '4'
          memory: 4G
        reservations:
          cpus: '2'
          memory: 2G
```

### Logging

```yaml
services:
  register-server:
    logging:
      driver: "json-file"
      options:
        max-size: "10m"
        max-file: "3"
```

### Security Scanning

```bash
# Scan for vulnerabilities
docker scan register-server:latest

# Or use Trivy
trivy image register-server:latest
```

---

## Troubleshooting

### Container Won't Start

```bash
# Check logs
docker compose logs register-server
docker compose logs irmin

# Check if port is in use
lsof -i :8080
lsof -i :9080

# Rebuild without cache
docker compose build --no-cache

# Check container status
docker compose ps
```

### Memory Issues

Increase JVM heap for JVM-based services:

```yaml
environment:
  JAVA_OPTS: "-Xms1g -Xmx4g -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"
```

For native image, memory is managed automatically.

### Irmin GraphQL Not Responding

```bash
# Check container health
docker compose ps irmin

# Test endpoint
curl -X POST http://localhost:9080/graphql \
  -H "Content-Type: application/json" \
  -d '{"query": "{ __typename }"}'

# Check inside container
docker exec irmin-graphql sh -c 'wget -O - http://127.0.0.1:8080/graphql'

# View health logs
docker inspect irmin-graphql --format='{{range .State.Health.Log}}{{.Output}}{{end}}'
```

### Build Failures

```bash
# Clean build artifacts
sbt clean

# Remove Docker build cache
docker builder prune -a

# Rebuild from scratch
docker compose build --no-cache
```

### Network Issues

```bash
# Inspect network
docker network inspect register-network

# Check container connectivity
docker exec register-server ping irmin-graphql

# Recreate network
docker compose down
docker compose up -d
```

---

## Health Checks

### Register Server

```bash
# HTTP health endpoint
curl http://localhost:8080/health

# Docker health status
docker inspect --format='{{.State.Health.Status}}' register-server
```

**Expected Response:**
```json
{
  "status": "healthy",
  "service": "risk-register"
}
```

### Irmin Server

```bash
# GraphQL health check
curl -s -X POST http://localhost:9080/graphql \
  -H "Content-Type: application/json" \
  -d '{"query": "{ __typename }"}'

# Docker health status
docker inspect --format='{{.State.Health.Status}}' irmin-graphql
```

---

## Performance Benchmarks

### Register Server (Native Image)

| Metric | Native Image | JVM | Improvement |
|--------|--------------|-----|-------------|
| Startup Time | 5-10ms | 3-5s | 500x faster |
| Memory (idle) | 50-80 MB | 200-300 MB | 75% reduction |
| Image Size | 118 MB | 200 MB | 41% smaller |
| Cold Response | 10-20ms | 50-100ms | 3-5x faster |

### Irmin Server

| Metric | Value |
|--------|-------|
| Startup Time | ~500ms |
| Memory (idle) | 100-150 MB |
| Write Latency | < 200ms |
| Read Latency | < 100ms |
| Image Size | ~650 MB (dev) |

---

## Security Notes

### Distroless Benefits (Register Server)

- ✅ No shell (`/bin/sh` not present)
- ✅ No package manager
- ✅ Minimal attack surface (~2 MB base)
- ✅ Non-root user (`nonroot:nonroot`)
- ✅ Static binary (no dynamic libraries)

### Verify Security

```bash
# Try to exec into distroless container (will fail - no shell)
docker exec -it register-server /bin/sh
# Expected: exec failed: no such file or directory

# Check running user
docker exec register-server id 2>/dev/null || echo "Cannot exec (distroless)"

# Scan for CVEs
trivy image register-server:latest
```

---

## Related Documentation

- [Testing Guide](test/TESTING.md) - Comprehensive test procedures
- [ADR-012: Service Mesh Strategy](ADR-012.md)
- [Implementation Plan](IMPLEMENTATION-PLAN-PROPOSALS.md)
- [OAuth2 Flow Architecture](OAUTH2-FLOW-ARCHITECTURE.md)

---

## Quick Reference

```bash
# Start everything
docker compose --profile persistence up -d

# View logs
docker compose logs -f

# Check health
curl http://localhost:8080/health
curl -X POST http://localhost:9080/graphql -H "Content-Type: application/json" -d '{"query": "{ __typename }"}'

# Run tests
./docs/test/run-tests.sh

# Stop everything
docker compose down

# Remove all data
docker compose down -v
```
