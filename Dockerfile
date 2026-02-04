# ==============================================================================
# USCIS Case Tracker gRPC Server - Multi-stage Dockerfile
# ==============================================================================
# Build: docker build -t uscis-case-tracker .
# Run:   docker run -p 50051:50051 uscis-case-tracker
#
# With environment overrides:
#   docker run -p 50051:50051 \
#     -e USCIS_CLIENT_ID=your-client-id \
#     -e USCIS_CLIENT_SECRET=your-secret \
#     -e USCIS_GRPC_LOG_LEVEL=DEBUG \
#     -v ~/.uscis-tracker:/home/uscis/.uscis-tracker \
#     uscis-case-tracker
# ==============================================================================

# ------------------------------------------------------------------------------
# Stage 1: Build stage - compile and create fat jar
# ------------------------------------------------------------------------------
FROM azul/zulu-openjdk:21 AS builder

# Install sbt
ARG SBT_VERSION=1.9.8
RUN apt-get update && \
    apt-get install -y curl && \
    curl -fsSL "https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz" | tar xz -C /opt && \
    ln -s /opt/sbt/bin/sbt /usr/local/bin/sbt && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /build

# Copy build configuration first (for better Docker layer caching)
# Changes to source won't invalidate dependency download cache
COPY build.sbt .
COPY project/build.properties project/
COPY project/plugins.sbt project/

# Download and cache dependencies
RUN sbt update

# Copy protobuf files and generate code
COPY src/main/protobuf/ src/main/protobuf/

# Copy remaining source files
COPY src/ src/

# Compile and create fat jar
RUN sbt clean compile assembly

# Verify the jar was created
RUN ls -la target/scala-2.13/uscis-case-tracker.jar

# ------------------------------------------------------------------------------
# Stage 2: Runtime stage - minimal JRE image
# ------------------------------------------------------------------------------
FROM azul/zulu-openjdk-alpine:21-jre

LABEL org.opencontainers.image.title="USCIS Case Tracker"
LABEL org.opencontainers.image.description="gRPC Server for tracking USCIS case statuses"
LABEL org.opencontainers.image.version="0.1.0"
LABEL org.opencontainers.image.source="https://github.com/your-org/uscis-case-tracker"

# Install wget for health checks (busybox wget is limited)
RUN apk add --no-cache wget

# Create non-root user for security
RUN addgroup -S uscis && adduser -S -G uscis -h /home/uscis uscis

WORKDIR /app

# Copy the fat jar from builder stage
COPY --from=builder /build/target/scala-2.13/uscis-case-tracker.jar /app/uscis-case-tracker.jar

# Create data directory with correct permissions
RUN mkdir -p /home/uscis/.uscis-tracker && \
    chown -R uscis:uscis /home/uscis/.uscis-tracker /app

# ------------------------------------------------------------------------------
# Environment Configuration
# ------------------------------------------------------------------------------
# gRPC Server
ENV GRPC_HOST=0.0.0.0
ENV GRPC_PORT=50051

# Logging
ENV USCIS_GRPC_LOG_LEVEL=INFO

# Data storage
ENV USCIS_DATA_DIR=/home/uscis/.uscis-tracker

# USCIS API (override these with secrets in production)
# ENV USCIS_CLIENT_ID=
# ENV USCIS_CLIENT_SECRET=
# ENV USCIS_API_BASE_URL=https://api.uscis.gov/case-status
ENV USCIS_SANDBOX_MODE=true

# HTTP Health check server port (for cloud platform health probes)
ENV HEALTH_PORT=8080

# JVM options for containers
# - UseContainerSupport: Respect container memory/CPU limits
# - MaxRAMPercentage: Use up to 75% of container memory for heap
# - UseG1GC: Better pause times for services
# - ExitOnOutOfMemoryError: Fail fast on OOM
# - Cats Effect tuning: Reduce tracing overhead in production
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -Dcats.effect.tracing.mode=none"

# Expose ports
# - 50051: gRPC API (HTTP/2)
# - 8080: HTTP health check (HTTP/1.1) for cloud platform probes
EXPOSE 50051 8080

# Health check via HTTP endpoint (works with all cloud platforms)
HEALTHCHECK --interval=30s --timeout=10s --start-period=20s --retries=3 \
    CMD wget -q --spider http://localhost:8080/health || exit 1

# Switch to non-root user
USER uscis

# Run the application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/uscis-case-tracker.jar"]
