# Docker Deployment Guide

## Overview

This guide covers containerizing and deploying the Risk Register service using Docker and Docker Compose.

## Prerequisites

- Docker 20.10+ 
- Docker Compose 2.0+
- At least 4GB RAM available for Docker

## Quick Start

### 1. Build and Run with Docker Compose

```bash
# Build and start the service
docker-compose up -d

# View logs
docker-compose logs -f register-api

# Stop the service
docker-compose down
```

### 2. Build Docker Image Only

```bash
# Build the image
docker build -t register-api:latest .

# Run the container
docker run -p 8080:8080 --name register-api register-api:latest
```

## Configuration

### Environment Variables

The service can be configured via environment variables:

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

Edit `docker-compose.yml` to override environment variables:

```yaml
services:
  register-api:
    environment:
      REGISTER_DEFAULT_NTRIALS: "50000"
      REGISTER_PARALLELISM: "16"
```

## Docker Images

### Multi-Stage Build

The Dockerfile uses a multi-stage build:

1. **Builder Stage**: Uses `sbtscala/scala-sbt` to compile and package the application
2. **Runtime Stage**: Uses `eclipse-temurin:21-jre-jammy` for a lightweight runtime

### Image Size Optimization

- Only runtime dependencies included in final image
- Non-root user for security
- Minimal base image (JRE only, no build tools)

## Observability (Optional)

### Enable OpenTelemetry Collector

```bash
# Start with observability stack
docker-compose --profile observability up -d

# Access Prometheus metrics
curl http://localhost:8889/metrics
```

### Configure Custom OTLP Endpoint

```bash
# Use external observability platform
docker-compose up -d \
  -e OTEL_EXPORTER_OTLP_ENDPOINT=https://your-otlp-endpoint:4317
```

## Health Checks

The container includes a health check endpoint:

```bash
# Check health status
curl http://localhost:8080/api/health

# Docker health status
docker inspect --format='{{.State.Health.Status}}' register-api
```

## Production Deployment

### Resource Limits

Add resource constraints in `docker-compose.yml`:

```yaml
services:
  register-api:
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

Configure logging driver:

```yaml
services:
  register-api:
    logging:
      driver: "json-file"
      options:
        max-size: "10m"
        max-file: "3"
```

### Secrets Management

Use Docker secrets or environment files:

```bash
# Create .env file
cat > .env <<EOF
REGISTER_DEFAULT_NTRIALS=50000
OTEL_EXPORTER_OTLP_ENDPOINT=https://prod-otlp:4317
EOF

# Start with env file
docker-compose --env-file .env up -d
```

## Troubleshooting

### Container Won't Start

```bash
# Check logs
docker-compose logs register-api

# Check if port is in use
lsof -i :8080

# Rebuild without cache
docker-compose build --no-cache
```

### Memory Issues

Increase JVM heap size:

```yaml
environment:
  JAVA_OPTS: "-Xms1g -Xmx4g -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"
```

### Build Failures

```bash
# Clean local build artifacts
sbt clean

# Rebuild Docker image
docker-compose build --no-cache
```

## Development Workflow

### Local Development with Docker

```bash
# Build and run
docker-compose up --build

# Hot reload (mount source code)
# Note: Requires additional configuration for sbt continuous compilation
```

### Testing Docker Build

```bash
# Build image
docker build -t register-api:test .

# Run with test config
docker run --rm -p 8080:8080 \
  -e REGISTER_DEFAULT_NTRIALS=1000 \
  register-api:test
```

## API Endpoints

Once running, access:

- **API Base**: http://localhost:8080/api
- **Health Check**: http://localhost:8080/api/health
- **Swagger UI**: http://localhost:8080/docs

## Next Steps

1. ✅ Build Docker image
2. ✅ Test locally with Docker Compose
3. Deploy to cloud platform (AWS ECS, GCP Cloud Run, Azure Container Instances)
4. Set up CI/CD pipeline for automated builds
5. Configure production monitoring and alerting
