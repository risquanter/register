# Development Environment Setup

## Overview

This document describes how to set up and use the development environment for the Risk Register project.

## Prerequisites

- Docker and Docker Compose
- JDK 21 (for Scala development)
- sbt (Scala Build Tool)

## Services

### Irmin GraphQL Server (Persistence Layer)

Irmin is a versioned key-value store with Git-like semantics. It exposes a GraphQL API for data operations.

**Port:** 9080 (host) → 8080 (container)  
**Endpoint:** `http://localhost:9080/graphql`  
**GraphiQL UI:** `http://localhost:9080/graphql` (GET request in browser)  
**Schema:** `dev/irmin-schema.graphql`

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

**For detailed testing procedures, see:** [API Test Plan - Irmin Section](test/API-TEST-PLAN.md#irmin-graphql-server-tests-persistence-layer)

## Data Persistence

Irmin data is persisted in a Docker volume: `register_irmin-data`

```bash
# List volumes
docker volume ls | grep irmin

# Inspect volume
docker volume inspect register_irmin-data

# Remove volume (DESTROYS DATA)
docker volume rm register_irmin-data
```

## Development Workflow

### Running the Full Stack

```bash
# Start all services
docker compose --profile persistence up -d

# Check status
docker compose ps
```

### Running Tests

```bash
# Scala tests
sbt test

# With coverage
sbt coverage test coverageReport
```

### Rebuilding Containers

```bash
# Rebuild a specific service
docker compose build irmin

# Rebuild without cache
docker compose build --no-cache irmin

# Rebuild and restart
docker compose up --build irmin -d
```

## Troubleshooting

### Container Won't Start

```bash
# Check logs
docker compose logs irmin

# Check container status
docker compose ps irmin

# Inspect container
docker inspect irmin-graphql
```

### GraphQL Endpoint Not Responding

1. Verify container is running: `docker compose ps irmin`
2. Check if healthy: Look for `(healthy)` in status
3. Test locally: `curl http://localhost:9080/graphql`
4. Check port mapping: `docker port irmin-graphql`

**For detailed troubleshooting, see:** [API Test Plan - Irmin Troubleshooting](test/API-TEST-PLAN.md#irmin-troubleshooting)

## Architecture Notes

### Why Irmin?

- **Versioned data**: Every change is tracked like Git
- **Branching/merging**: Enables offline-first and conflict resolution
- **GraphQL API**: Clean, typed API for data operations
- **OCaml heritage**: Efficient, reliable implementation

**For detailed data model and architecture, see:** [API Test Plan - Irmin Data Model](test/API-TEST-PLAN.md#irmin-data-model)

## Related Documentation

- [ADR-012: Service Mesh Strategy](ADR-012.md)
- [Implementation Plan](IMPLEMENTATION-PLAN-PROPOSALS.md) - Phase 1.5 / Phase 2
- [OAuth2 Flow Architecture](OAUTH2-FLOW-ARCHITECTURE.md)
