# NGC Platform — High-Level Design

## 1. Purpose

This document describes the architecture of the `ngc-platform` demo project. The project
serves two goals simultaneously:

1. **Prove NGC backlog readiness** — every file maps to one or more of the five backlog
   items (NewRelic Observability, KTLO Remediation, AWE Delegation, Service Deployment,
   Error Traceability).
2. **Show Claude Code as an engineering accelerator** — the entire platform was scaffolded,
   coded, tested, containerised, and configured via Claude Code following the NGC tech stack.

---

## 2. System Overview

```
┌─────────────────────────────────────────────────────────────────────────┐
│                            Developer / CI                               │
│                                                                         │
│   curl / Postman          GitHub Actions             deploy-local.sh    │
│        │                  CI ──► Deploy                    │            │
└────────┼──────────────────────────┼────────────────────────┼────────────┘
         │                          │                        │
         ▼                          ▼                        ▼
┌──────────────────────────────────────────────────────────────────────┐
│                        Kubernetes (AKS / Minikube)                   │
│                                                                      │
│  ┌─────────────────────────────────────────────────────────────┐     │
│  │  namespace: ngc-dev                                         │     │
│  │                                                             │     │
│  │  ┌──────────────────────────────────────────────────────┐   │     │
│  │  │  interaction-api  (Spring Boot 3 · Java 21)          │   │     │
│  │  │                                                      │   │     │
│  │  │  POST /v1/interactions  ──► InteractionController    │   │     │
│  │  │  GET  /v1/interactions/{id}      │                   │   │     │
│  │  │  GET  /v1/interactions/metrics   │                   │   │     │
│  │  │                                  ▼                   │   │     │
│  │  │                          InteractionService          │   │     │
│  │  │                         /        │        \          │   │     │
│  │  │                        /         │         \         │   │     │
│  │  │              Repository     Kafka         NewRelic   │   │     │
│  │  │              (JPA/H2)     Template        Agent API  │   │     │
│  │  └──────────┬──────────────────┬─────────────────┬─────┘   │     │
│  │             │                  │                 │          │     │
│  └─────────────┼──────────────────┼─────────────────┼──────────┘     │
│                │                  │                 │                 │
└────────────────┼──────────────────┼─────────────────┼─────────────────┘
                 │                  │                 │
                 ▼                  ▼                 ▼
          ┌──────────┐     ┌──────────────┐   ┌─────────────────┐
          │ H2 (local)│     │ Apache Kafka │   │  New Relic      │
          │ RDS (prod)│     │ (LocalStack  │   │  ┌───────────┐  │
          └──────────┘     │  local / AKS │   │  │ Custom    │  │
                           │  prod)       │   │  │ Events    │  │
                           └──────────────┘   │  │ APM / JVM │  │
                                              │  │ Dashboards│  │
                                              │  └───────────┘  │
                                              │  ▲              │
                                              │  │ Terraform    │
                                              └──┼──────────────┘
                                                 │
                                        terraform/newrelic/
                                        interaction-api-dashboard.tf
```

---

## 3. Components

### 3.1 interaction-api (Core Service)

| Layer | Class | Responsibility |
|-------|-------|---------------|
| Controller | `InteractionController` | HTTP routing, request validation, status codes |
| Service | `InteractionService` | Business logic, NR events, Kafka publish |
| Repository | `InteractionRepository` | JPA queries — avg wait, channel breakdown, resolution count |
| Domain | `Interaction` | JPA entity with Channel and Status enums |
| DTOs | `CreateInteractionRequest`, `InteractionMetrics` | Java 21 records — immutable, no Lombok |
| Exceptions | `InteractionNotFoundException`, `GlobalExceptionHandler` | Structured error responses for Backlog 5 |

**Key data flows:**

```
POST /v1/interactions
  → validate request (@Valid)
  → save to DB (Interaction entity)
  → emit NewRelic custom event "InteractionCreated" { customerId, channel, agentId, queueWaitMs }
  → publish Kafka message to "interaction-events" topic { id, customerId, channel, status, createdAt }
  → return 201 + saved entity

GET /v1/interactions/metrics
  → count() total
  → GROUP BY channel → byChannel map
  → AVG(queueWaitMs) → avgQueueWaitMs
  → count(status=RESOLVED) → resolutionRate
  → return InteractionMetrics record
```

### 3.2 Observability — New Relic (Backlog 1)

Two integration points, intentionally separated:

| Integration | Mechanism | Purpose |
|-------------|-----------|---------|
| **NR Java Agent** (runtime) | `JAVA_TOOL_OPTIONS=-javaagent:/opt/newrelic/newrelic.jar` in Dockerfile | APM, JVM metrics, distributed tracing, log correlation |
| **NR Agent API** (code) | `NewRelic.getAgent().getInsights().recordCustomEvent(...)` in `InteractionService` | Business KPI custom events: `InteractionCreated`, `InteractionFetched` |
| **Micrometer NR registry** (build) | `micrometer-registry-new-relic` in build.gradle | JVM / Spring actuator metrics → NR Metric API |
| **Terraform** (infra) | `terraform/newrelic/interaction-api-dashboard.tf` | Dashboard-as-code; a Backlog 1 agent auto-generates PRs against this file |

**Dashboard widgets backed by NRQL:**

```
InteractionCreated events  →  rate(count(*), 1 min) FACET channel   [line chart]
                           →  count(*)                               [billboard]
                           →  percentile(queueWaitMs, 95)            [p95 wait]
                           →  percentage(WHERE status='RESOLVED')    [resolution rate]
Transaction events         →  percentile(duration*1000, 99)          [API p99]
```

### 3.3 Local Development Stack (Backlog 4)

```
docker/docker-compose.yml
  ├── localstack          (Kafka + S3 + SQS emulation via LocalStack 3.4)
  ├── localstack-init     (aws-cli container — creates "interaction-events" topic)
  └── interaction-api     (built from Dockerfile, depends_on localstack)

scripts/deploy-local.sh   →  Minikube start → build image → kubectl apply → port-forward
```

Environment variables are injected at runtime — no secrets in image or repo.

### 3.4 Kubernetes Manifests

```
k8s/
├── namespace.yaml              → ngc-dev namespace
└── interaction-api/
    ├── configmap.yaml          → SPRING_PROFILES_ACTIVE, KAFKA_*, NEW_RELIC_APP_NAME
    ├── deployment.yaml         → 1 replica (Minikube) / 2 replicas (AKS via Kustomize)
    │                             liveness  → /actuator/health/liveness
    │                             readiness → /actuator/health/readiness
    └── service.yaml            → ClusterIP, port 80 → 8080
```

`NEW_RELIC_LICENSE_KEY` is injected from a K8s Secret (`newrelic-secret`) — never in a ConfigMap.

### 3.5 CI/CD — GitHub Actions (Backlogs 2 & 4)

```
.github/workflows/
├── ci.yml        (push to main/develop, PR to main)
│   ├── ./gradlew :interaction-api:build     ← compile + unit tests
│   ├── SonarQube scan                       ← Backlog 2: code quality gate
│   ├── Veracode SAST scan                   ← Backlog 2: security gate
│   └── docker build                         ← verify image builds
│
└── deploy.yml    (workflow_dispatch → environment: dev | staging | prod)
    ├── az login + AKS context               ← Backlog 4: direct-from-repo deploy
    ├── docker build + push to ACR
    ├── kubectl set image + rollout status
    └── ngc-org/awe-action@v2                ← Backlog 3: run AWE tests post-deploy
```

### 3.6 AWE Test Delegation (Backlog 3)

Each repo owns its AWE test contract in `awe-tests.yaml`. The `deploy.yml` pipeline
invokes AWE with that file against the target environment after every deploy.

```
interaction-api/awe-tests.yaml
  ├── create-interaction   → POST 201, body has $.id, latency < 500ms
  ├── metrics-healthy      → GET metrics 200, totalInteractions ≥ 0
  └── unknown-id-404       → GET unknown id 404, $.errorCode = INTERACTION_NOT_FOUND
```

### 3.7 Error Traceability (Backlog 5)

Every error response from `GlobalExceptionHandler` follows a fixed schema:

```json
{
  "errorCode":  "INTERACTION_NOT_FOUND",   ← machine-readable constant
  "message":    "Interaction not found: abc-123",
  "timestamp":  "2026-06-22T14:30:00.000Z"
}
```

These land in New Relic Logs correlated with a `traceId`. A Backlog 5 agent can query:

```sql
SELECT message, traceId FROM Log
WHERE errorCode IS NOT NULL
SINCE 24 hours ago
```

…and generate investigation-ready prompts for any error pattern it finds.

---

## 4. Backlog Coverage Map

| Backlog Item | Evidence in This Repo |
|---|---|
| 1 — NewRelic Observability | `InteractionService` emits `InteractionCreated` custom events. `interaction-api-dashboard.tf` is the Terraform a Backlog 1 agent would PR against. |
| 2 — KTLO Remediation | `ci.yml` gates every PR on SonarQube + Veracode. Dependabot config (`.github/dependabot.yml`) closes the dependency-update loop. |
| 3 — AWE Delegation | `awe-tests.yaml` in the service repo is the per-repo DSL. `deploy.yml` invokes `ngc-org/awe-action@v2` post-deploy. |
| 4 — Service Deployment | `deploy-local.sh` is the dev CLI. `deploy.yml` is the GHA pipeline. K8s manifests are the deployment contract, all living in this repo. |
| 5 — Error Traceability | `GlobalExceptionHandler` emits structured `{ errorCode, message, timestamp }` on every error path, NR-log-queryable by a Backlog 5 agent. |

---

## 5. Environment Progression

```
Local (dev machine)
  └── docker-compose.yml  →  LocalStack (Kafka/S3/SQS)  +  interaction-api

Minikube (integration)
  └── deploy-local.sh     →  k8s manifests  →  ngc-dev namespace

AKS (dev / staging / prod)
  └── deploy.yml          →  ACR image push  →  kubectl set image  →  AWE smoke tests
```

---

## 6. Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| Java 21 records for DTOs | Immutable, no Lombok, deserializes cleanly with `@RequestBody @Valid` |
| H2 local / real DB on AKS | Same `application.yml`; ConfigMap drives the datasource URL per environment |
| NR agent in Dockerfile, NR API in build.gradle | Agent = infrastructure concern; API = code concern — deliberate separation |
| `awe-tests.yaml` per repo | Migration from centralised AWE ownership to team-owned test contracts |
| Structured `errorCode` in every error | Machine-readable field enables autonomous Backlog 5 investigation agents |
| `NEW_RELIC_LICENSE_KEY` from K8s Secret only | Never in ConfigMap, never in image — injected at pod startup |
