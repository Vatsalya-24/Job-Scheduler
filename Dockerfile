# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /build

# Copy pom and download dependencies first (layer cache)
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 \
    mvn dependency:resolve --no-transfer-progress -f pom.xml || true

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn clean package -DskipTests --no-transfer-progress

# Extract layers for efficient layer caching
RUN java -Djarmode=layertools \
    -jar target/orbit-platform-*.jar extract --destination extracted

# ── Stage 2: Runtime ────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# Non-root user for security
RUN addgroup -S orbit && adduser -S orbit -G orbit
USER orbit

# Copy extracted layers (changes least → most frequently)
COPY --from=builder /build/extracted/dependencies/          ./
COPY --from=builder /build/extracted/spring-boot-loader/    ./
COPY --from=builder /build/extracted/snapshot-dependencies/ ./
COPY --from=builder /build/extracted/application/           ./

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1

EXPOSE 8080

# Virtual threads + tuned GC
ENTRYPOINT ["java", \
    "-Dspring.threads.virtual.enabled=true", \
    "-XX:+UseG1GC", \
    "-XX:MaxGCPauseMillis=200", \
    "-XX:+HeapDumpOnOutOfMemoryError", \
    "-XX:HeapDumpPath=/tmp/heapdump.hprof", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "org.springframework.boot.loader.launch.JarLauncher"]
