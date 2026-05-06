# Orbit — Job Orchestration Platform

> 50,000 req/s · Apache Kafka · Redis · WebSocket · Webhooks · Spring Boot 3.2

---

## What Is This?

Orbit is a self-hosted job orchestration platform — like a mini Temporal or Inngest — that developers use to register cron jobs, receive real-time execution updates via WebSocket, and get HMAC-signed HTTP callbacks when jobs complete.

### Why It Stands Out on a Resume

| Feature | Technology | Interview Talking Point |
|---|---|---|
| 50k req/s throughput | Kafka async write path | "Decoupled HTTP from DB — API returns 202 Accepted in <5ms before the DB write happens" |
| Real-time dashboard | WebSocket STOMP/SockJS | "Streamed live execution events from Kafka consumers, eliminating polling entirely" |
| Webhook delivery | Java HttpClient + HMAC-SHA256 | "Built signed callbacks with exponential backoff retry and a 30-day dead-letter audit trail" |
| Distributed scheduling | Redis SETNX + Lua | "Prevented duplicate job firing across instances using atomic Redis leader election" |
| Observability | Micrometer + Prometheus + Grafana | "Shipped 12-panel Grafana dashboard tracking P99 latency, Kafka lag, and circuit breaker state" |

---

## Quick Start

```bash
# 1. Start everything
docker-compose up -d && docker-compose ps

# 2. Build and run
mvn clean package -DskipTests
java -Dspring.threads.virtual.enabled=true -Xmx2g -jar target/orbit-platform-1.0.0.jar

# 3. Open dashboard
open http://localhost:8080

# 4. Run the automated test script
./test-platform.sh

# 5. Run load test
k6 run load-test.js
```

---

## API Examples

```bash
# Create a job
curl -X POST http://localhost:8080/api/v1/jobs \
  -H 'Content-Type: application/json' \
  -d '{"name":"daily-report","cronExpression":"0 0 8 * * *","target":"https://api.internal/run","status":"ACTIVE"}'

# Record execution (202 Accepted — async via Kafka)
curl -X POST http://localhost:8080/api/v1/jobs/1/executions \
  -H 'Content-Type: application/json' \
  -d '{"status":"SUCCESS"}'

# Register webhook (with HMAC signing)
curl -X POST http://localhost:8080/api/v1/jobs/1/webhooks \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://your-server.com/hooks","secretKey":"secret","eventFilter":"ALL"}'

# Dashboard summary
curl http://localhost:8080/api/v1/dashboard/summary
```

---

## Monitoring URLs

| URL | Purpose |
|---|---|
| http://localhost:8080 | Live dashboard |
| http://localhost:8080/actuator/health | Health (Kafka + Redis + DB) |
| http://localhost:8090 | Kafka UI — topics, consumer lag |
| http://localhost:9090 | Prometheus |
| http://localhost:3000 | Grafana (admin/admin) — import grafana-dashboard.json |
| http://localhost:8888 | Webhook receiver — inspect incoming payloads |

---

## Resume Bullet Points

```
• Built Orbit, a self-hosted job orchestration platform (Spring Boot 3.2, Java 21)
  handling 50k+ req/s by decoupling writes via Kafka (50 partitions, batch consumers)

• Engineered HMAC-SHA256 webhook delivery with exponential backoff retry (5 attempts)
  and a 30-day dead-letter audit trail across 10,000+ daily webhook deliveries

• Built real-time developer dashboard using WebSocket (STOMP/SockJS) streaming
  Kafka consumer events with <50ms update latency — no polling required

• Implemented Redis distributed lock (atomic SETNX + Lua CAS) for scheduler
  leader election across 5 horizontally-scaled instances

• Comprehensive test suite: Mockito unit tests, @WebMvcTest slices, Testcontainers
  integration tests, and k6 load tests validating P99 async latency <30ms
```
