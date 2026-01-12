# ============================================
# DEVELOPMENT IMAGE - NOT FOR PRODUCTION
# ============================================
# This image runs sbt at runtime for development/debugging.
# For production, use Dockerfile.native (GraalVM Native Image)
# ============================================

# Stage 1: Build the application using sbt
FROM eclipse-temurin:21-jdk-jammy AS builder

# Install sbt
RUN apt-get update && \
    apt-get install -y curl gnupg && \
    echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" | tee /etc/apt/sources.list.d/sbt.list && \
    echo "deb https://repo.scala-sbt.org/scalasbt/debian /" | tee /etc/apt/sources.list.d/sbt_old.list && \
    curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | apt-key add && \
    apt-get update && \
    apt-get install -y sbt && \
    rm -rf /var/lib/apt/lists/*

# Set working directory
WORKDIR /app

# Copy only build-essential files (see .dockerignore for exclusions)
COPY . .

# Compile and package application
RUN sbt server/compile

# Stage 2: Create runtime image
FROM eclipse-temurin:21-jdk-jammy

# Install necessary runtime dependencies including sbt
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl gnupg && \
    echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" | tee /etc/apt/sources.list.d/sbt.list && \
    curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | apt-key add && \
    apt-get update && \
    apt-get install -y sbt && \
    rm -rf /var/lib/apt/lists/*

# Create non-root user for security
RUN groupadd -r register && \
    useradd -r -g register -u 1001 -m -d /home/register register

# Set working directory
WORKDIR /app

# Copy entire compiled project from builder
COPY --from=builder /app /app

# Change ownership to non-root user
RUN chown -R register:register /app && \
    chown -R register:register /home/register

# Switch to non-root user
USER register

# Expose application port
EXPOSE 8080

# Set JVM options for container environment
ENV JAVA_OPTS="-Xms512m -Xmx2g -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

# Run the application using sbt with explicit main class
CMD ["sbt", "server/runMain com.risquanter.register.Application"]
