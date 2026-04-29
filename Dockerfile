FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder

WORKDIR /build

# Copy pom first — layer cache for dependencies
COPY pom.xml .

# Download dependencies (no cache mount — Render doesn't support BuildKit cache mounts)
RUN mvn dependency:go-offline --no-transfer-progress -f pom.xml || true

# Copy source and build
COPY src ./src
RUN mvn clean package -DskipTests --no-transfer-progress

# Extract Spring Boot layers for efficient Docker layer caching
RUN java -Djarmode=layertools \
    -jar target/orbit-platform-*.jar extract --destination extracted

# ── Stage 2: Runtime ────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime

WORKDIR /app

# Security: run as non-root
RUN addgroup -S orbit && adduser -S orbit -G orbit
USER orbit

# Copy extracted Spring Boot layers (least-to-most-frequently-changed order)
COPY --from=builder /build/extracted/dependencies/          ./
COPY --from=builder /build/extracted/spring-boot-loader/    ./
COPY --from=builder /build/extracted/snapshot-dependencies/ ./
COPY --from=builder /build/extracted/application/           ./

# Health check — Render uses this to know when the service is ready
HEALTHCHECK --interval=30s --timeout=10s --start-period=90s --retries=5 \
    CMD wget -qO- http://localhost:${PORT:-8080}/actuator/health \
        | grep -q '"status":"UP"' || exit 1

EXPOSE 8080

# FIX: Use ${PORT} env var — Render assigns a dynamic port via PORT env variable
ENTRYPOINT ["sh", "-c", \
    "java \
    -Dserver.port=${PORT:-8080} \
    -Dspring.threads.virtual.enabled=true \
    -XX:+UseG1GC \
    -XX:MaxGCPauseMillis=200 \
    -XX:MaxRAMPercentage=75.0 \
    -Djava.security.egd=file:/dev/./urandom \
    org.springframework.boot.loader.launch.JarLauncher"]