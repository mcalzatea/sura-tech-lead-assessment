# Spec 09 — Docker Compose & Infrastructure Config

## Context
`docker-compose.yml` at repo root wiring all services and infrastructure for the demo.
All config files for Prometheus, Jaeger, OTel Collector, and Grafana provisioning.

**Depends on**: spec-07 and spec-08 (both apps have `Dockerfile`s).

---

## 1. Services overview

| Service | Image | Port (host) | Purpose |
|---|---|---|---|
| `demo-service` | built from `./demo-service` | 8080 | Business endpoint |
| `flaky-upstream` | built from `./flaky-upstream` | 8081 | Simulated dependency |
| `redis` | `redis:7-alpine` | 6379 | Idempotency key store |
| `otel-collector` | `otel/opentelemetry-collector-contrib:0.100.0` | 4318 (OTLP HTTP) | Trace/metrics ingestion |
| `jaeger` | `jaegertracing/all-in-one:1.57` | 16686 (UI), 4317 (OTLP gRPC) | Trace UI |
| `prometheus` | `prom/prometheus:v2.52.0` | 9090 | Metrics scrape + storage |
| `grafana` | `grafana/grafana:10.4.2` | 3000 | Dashboards |

---

## 2. `docker-compose.yml`

```yaml
version: "3.9"

networks:
  demo-net:
    driver: bridge

services:

  redis:
    image: redis:7-alpine
    networks: [demo-net]
    ports: ["6379:6379"]
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 3s
      retries: 5

  jaeger:
    image: jaegertracing/all-in-one:1.57
    networks: [demo-net]
    ports:
      - "16686:16686"   # Jaeger UI
      - "4317:4317"     # OTLP gRPC (for otel-collector → jaeger)
    environment:
      - COLLECTOR_OTLP_ENABLED=true

  otel-collector:
    image: otel/opentelemetry-collector-contrib:0.100.0
    networks: [demo-net]
    ports:
      - "4318:4318"     # OTLP HTTP (apps → collector)
    volumes:
      - ./otel-collector/config.yaml:/etc/otelcol-contrib/config.yaml
    depends_on: [jaeger]

  prometheus:
    image: prom/prometheus:v2.52.0
    networks: [demo-net]
    ports: ["9090:9090"]
    volumes:
      - ./prometheus/prometheus.yml:/etc/prometheus/prometheus.yml
    depends_on: [demo-service]

  grafana:
    image: grafana/grafana:10.4.2
    networks: [demo-net]
    ports: ["3000:3000"]
    environment:
      - GF_SECURITY_ADMIN_PASSWORD=admin
    volumes:
      - ./grafana-provisioning/datasources:/etc/grafana/provisioning/datasources
      - ./grafana-provisioning/dashboards:/etc/grafana/provisioning/dashboards
    depends_on: [prometheus, jaeger]

  flaky-upstream:
    build:
      context: .
      dockerfile: flaky-upstream/Dockerfile
    networks: [demo-net]
    ports: ["8081:8081"]
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8081/actuator/health"]
      interval: 10s
      timeout: 5s
      retries: 5

  demo-service:
    build:
      context: .
      dockerfile: demo-service/Dockerfile
    networks: [demo-net]
    ports: ["8080:8080"]
    environment:
      - REDIS_HOST=redis
      - FLAKY_UPSTREAM_URL=http://flaky-upstream:8081
      - OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318
    depends_on:
      redis:
        condition: service_healthy
      flaky-upstream:
        condition: service_healthy
      otel-collector:
        condition: service_started
```

---

## 3. Dockerfiles

### `demo-service/Dockerfile`

```dockerfile
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app
COPY build/libs/demo-service-*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### `flaky-upstream/Dockerfile`

```dockerfile
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app
COPY build/libs/flaky-upstream-*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
```

Build note: both images expect the fat-jar to be pre-built via `./gradlew clean build`
before `docker compose up --build`. The Dockerfiles do NOT run Gradle (no internet
access needed inside Docker).

---

## 4. OTel Collector config

File: `otel-collector/config.yaml`

```yaml
receivers:
  otlp:
    protocols:
      http:
        endpoint: 0.0.0.0:4318

processors:
  batch:
    timeout: 1s

exporters:
  otlp/jaeger:
    endpoint: jaeger:4317
    tls:
      insecure: true

service:
  pipelines:
    traces:
      receivers: [otlp]
      processors: [batch]
      exporters: [otlp/jaeger]
```

---

## 5. Prometheus config

File: `prometheus/prometheus.yml`

```yaml
global:
  scrape_interval: 15s

scrape_configs:
  - job_name: demo-service
    static_configs:
      - targets: ["demo-service:8080"]
    metrics_path: /actuator/prometheus

  - job_name: flaky-upstream
    static_configs:
      - targets: ["flaky-upstream:8081"]
    metrics_path: /actuator/prometheus
```

---

## 6. Grafana provisioning

### `grafana-provisioning/datasources/prometheus.yaml`

```yaml
apiVersion: 1
datasources:
  - name: Prometheus
    type: prometheus
    url: http://prometheus:9090
    isDefault: true
```

### `grafana-provisioning/datasources/jaeger.yaml`

```yaml
apiVersion: 1
datasources:
  - name: Jaeger
    type: jaeger
    url: http://jaeger:16686
```

### `grafana-provisioning/dashboards/dashboard.yaml`

```yaml
apiVersion: 1
providers:
  - name: integration-framework
    folder: Integration Framework
    type: file
    options:
      path: /etc/grafana/provisioning/dashboards
```

### `grafana-provisioning/dashboards/integration-framework.json`

Grafana dashboard JSON with panels (generate via Grafana UI export or hand-craft):

| Panel | Query |
|---|---|
| Call rate by outcome | `rate(integration_client_calls_total[1m])` |
| Error rate % | `rate(integration_client_calls_total{outcome="error"}[1m]) / rate(integration_client_calls_total[1m]) * 100` |
| Retry rate | `rate(integration_client_retries_total[1m])` |
| Circuit breaker state | `integration_client_cb_state` |
| P95 latency (ms) | `histogram_quantile(0.95, rate(integration_client_duration_seconds_bucket[1m])) * 1000` |

---

## 7. Final directory layout

```
/
├── settings.gradle.kts
├── build.gradle.kts
├── docker-compose.yml
├── framework/
├── demo-service/
│   ├── Dockerfile
│   └── src/...
├── flaky-upstream/
│   ├── Dockerfile
│   └── src/...
├── otel-collector/
│   └── config.yaml
├── prometheus/
│   └── prometheus.yml
├── grafana-provisioning/
│   ├── datasources/
│   │   ├── prometheus.yaml
│   │   └── jaeger.yaml
│   └── dashboards/
│       ├── dashboard.yaml
│       └── integration-framework.json
└── README.md
```

---

## Acceptance criteria

- `./gradlew clean build && docker compose up -d --build` completes without errors.
- All 7 containers reach healthy/running state within 60 s.
- `curl http://localhost:8080/health` returns HTTP 200.
- `curl http://localhost:8081/actuator/health` returns HTTP 200.
- `curl http://localhost:8081/admin/mode` returns current mode JSON.
- Jaeger UI at `http://localhost:16686` shows traces for `demo-service` service.
- Grafana at `http://localhost:3000` (admin/admin) shows the provisioned dashboard with data
  after at least one call to `POST /orders`.
- Prometheus at `http://localhost:9090` scrapes `integration_client_calls_total` metric.
- Redis container responds to `redis-cli ping` → `PONG`.
