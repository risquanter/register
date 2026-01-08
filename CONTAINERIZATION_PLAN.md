# Containerization Plan for Risk Register Application

## Overview

This plan provides a step-by-step guide to containerize the Risk Register application, progressing from a basic JVM container to a hardened, optimized GraalVM native image.

**Simplified Phases (Testcontainers deferred):**
- Phase 1: Basic JVM Containerization
- Phase 2: Dockerfile Hardening
- Phase 3: GraalVM Native Image Preparation
- Phase 4: Native Image Docker Build
- Phase 5: Distroless Image (Optional - evaluate when needed)

---

## Phase 1: Basic JVM Containerization

**Goal:** Create a working Docker image using sbt-native-packager with standard JVM runtime.

### Step 1.1: Add sbt-native-packager Plugin

**File:** `project/plugins.sbt`

```scala
addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.10.4")
```

**Verification:**
- [ ] `sbt compile` succeeds
- [ ] Plugin available in sbt shell

### Step 1.2: Configure Docker Plugin

**File:** `build.sbt`

```scala
lazy val server = (project in file("modules/server"))
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(
    name := "register-server",
    // Docker configuration
    dockerBaseImage := "eclipse-temurin:21-jre-alpine",
    dockerExposedPorts := Seq(8080),
    Docker / packageName := "risk-register",
    Docker / version := version.value,
    Docker / maintainer := "risquanter",
    Compile / mainClass := Some("com.risquanter.register.Application"),
    // ... existing settings
  )
```

**Verification:**
- [ ] `sbt server/docker:publishLocal` creates image
- [ ] `docker images | grep risk-register` shows image
- [ ] `docker run -p 8080:8080 risk-register:0.1.0` starts application

### Step 1.3: Create docker-compose.yml for Local Development

**File:** `docker-compose.yml`

```yaml
version: '3.8'

services:
  server:
    image: risk-register:0.1.0
    ports:
      - "8080:8080"
    environment:
      - OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4317
    healthcheck:
      test: ["CMD", "wget", "-q", "--spider", "http://localhost:8080/health"]
      interval: 30s
      timeout: 10s
      retries: 3
```

**Verification:**
- [ ] `docker-compose up` starts the service
- [ ] API endpoints accessible at http://localhost:8080
- [ ] Health endpoint responds

---

## Phase 2: Dockerfile Hardening

**Goal:** Improve security, reduce image size, and follow best practices.

### Step 2.1: Create Custom Dockerfile

**File:** `docker/Dockerfile.jvm`

```dockerfile
FROM eclipse-temurin:21-jre-alpine AS base

# Security: Create non-root user
RUN addgroup -g 1001 -S appgroup && \
    adduser -u 1001 -S appuser -G appgroup

WORKDIR /app

# Copy application files (from sbt stage output)
COPY --chown=appuser:appgroup target/universal/stage/ .

# Security: Remove unnecessary files
RUN rm -rf /var/cache/apk/* && \
    chmod -R 550 /app && \
    chmod -R 770 /app/logs 2>/dev/null || true

USER appuser

HEALTHCHECK --interval=30s --timeout=10s --retries=3 \
    CMD wget -q --spider http://localhost:8080/health || exit 1

EXPOSE 8080

ENV JAVA_OPTS="-XX:+UseContainerSupport \
               -XX:MaxRAMPercentage=75.0 \
               -XX:+UseG1GC \
               -XX:+ExitOnOutOfMemoryError \
               -Djava.security.egd=file:/dev/./urandom"

ENTRYPOINT ["bin/register-server"]
```

### Step 2.2: Security Scanning

```bash
docker run --rm -v /var/run/docker.sock:/var/run/docker.sock \
  aquasec/trivy image risk-register:0.1.0
```

**Verification:**
- [ ] Image builds successfully
- [ ] Image size reduced (target: < 300MB)
- [ ] Non-root user verified: `docker run --rm risk-register:0.1.0 id`
- [ ] No critical vulnerabilities in scan

---

## Phase 3: GraalVM Native Image Preparation

**Goal:** Prepare codebase for GraalVM native image compilation.

### Step 3.1: Add GraalVM Native Image Plugin

**File:** `project/plugins.sbt`

```scala
addSbtPlugin("org.scalameta" % "sbt-native-image" % "0.3.4")
```

### Step 3.2: Create Native Image Configuration

**File:** `modules/server/src/main/resources/META-INF/native-image/reflect-config.json`

```json
[
  {
    "name": "com.risquanter.register.configs.ServerConfig",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true,
    "allDeclaredFields": true
  },
  {
    "name": "com.risquanter.register.configs.TelemetryConfig",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true,
    "allDeclaredFields": true
  },
  {
    "name": "com.risquanter.register.configs.SimulationConfig",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true,
    "allDeclaredFields": true
  }
]
```

### Step 3.3: Configure Native Image Build

**File:** `build.sbt`

```scala
lazy val server = (project in file("modules/server"))
  .enablePlugins(JavaAppPackaging, DockerPlugin, NativeImagePlugin)
  .settings(
    nativeImageOptions ++= Seq(
      "--no-fallback",
      "--enable-http",
      "--enable-https",
      "-H:+ReportExceptionStackTraces",
      "--initialize-at-build-time=scala,zio,com.risquanter",
      "--initialize-at-run-time=io.netty"
    ),
    nativeImageJvm := "graalvm-community",
    nativeImageVersion := "21"
  )
```

**Verification:**
- [ ] `sbt server/nativeImage` completes (5-15 minutes)
- [ ] Native binary runs successfully
- [ ] All endpoints work

---

## Phase 4: GraalVM Native Image Docker Build

**Goal:** Create Docker image with GraalVM native binary.

### Step 4.1: Multi-Stage Dockerfile for Native Image

**File:** `docker/Dockerfile.native`

```dockerfile
FROM ghcr.io/graalvm/native-image-community:21 AS builder

WORKDIR /build

RUN curl -fL https://github.com/sbt/sbt/releases/download/v1.10.7/sbt-1.10.7.tgz | tar xz -C /usr/local
ENV PATH="/usr/local/sbt/bin:${PATH}"

COPY build.sbt .
COPY project/ project/
COPY modules/ modules/

RUN sbt server/nativeImage

FROM alpine:3.20

RUN addgroup -g 1001 -S appgroup && \
    adduser -u 1001 -S appuser -G appgroup

WORKDIR /app

COPY --from=builder --chown=appuser:appgroup \
    /build/modules/server/target/native-image/register-server .

USER appuser

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --retries=3 \
    CMD wget -q --spider http://localhost:8080/health || exit 1

ENTRYPOINT ["./register-server"]
```

**Verification:**
- [ ] Native Dockerfile builds successfully
- [ ] Image size significantly reduced (target: < 100MB)
- [ ] Application starts in < 1 second
- [ ] Memory usage reduced

---

## Phase 5: Distroless Image (Optional)

**Status:** DEFERRED

**Rationale:** 
- No persistence layer yet to benefit from minimal attack surface
- Future database choice uncertain
- JVM image with hardening provides sufficient security for current needs
- Revisit when deploying to production with sensitive data

**Trigger to reconsider:**
- Adding PostgreSQL or other database
- Deploying to regulated environment
- Security audit requirement

---

## Phase Summary: Image Size Progression

| Phase | Base Image | Est. Size | Security |
|-------|-----------|-----------|----------|
| 1 | eclipse-temurin:21-jre-alpine | ~300MB | Basic |
| 2 | eclipse-temurin:21-jre-alpine (hardened) | ~280MB | Improved |
| 4 | alpine:3.20 + native binary | ~80MB | Good |
| 5 | gcr.io/distroless/static | ~30MB | Excellent |

---

## Appendix A: Testcontainers (Future)

**When to add:** When you have external dependencies to test against.

**Trigger points:**
- Add PostgreSQL persistence → `testcontainers-scala-postgresql`
- Add Redis caching → `testcontainers-scala-redis`
- Add Kafka messaging → `testcontainers-scala-kafka`

**Pattern from BCG project:**
```scala
trait RepositorySpec {
  val dataSourceLayer = ZLayer {
    ZIO.acquireRelease(ZIO.attempt(createContainer()))(
      container => ZIO.attempt(container.stop()).ignoreLogged
    )
  }
}
```

---

## Ready to Start?

Begin with **Phase 1, Step 1.1**: Add sbt-native-packager plugin.

Let me know when you're ready to proceed.
