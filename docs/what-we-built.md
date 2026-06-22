# What We Built — NGC Platform Demo

## TL;DR

A production-grade microservice (`interaction-api`) that emits business telemetry
to New Relic via three independent integration layers, with its observability
dashboard defined as Terraform code in the same repo — and a Claude agent that
automatically keeps the dashboard in sync with the code.

---

## The Problem We Solved

| Pain point | Without this system | With this system |
|---|---|---|
| Dashboard creation | Manual clicks in NR UI after incidents | Code in git, applied via `terraform apply` |
| Dashboard discoverability | Hidden in UI, no history, no diff | PR-reviewable, git history, diffable |
| New KPI coverage | Developer adds event → nobody builds widget | Claude agent detects gap → opens PR automatically |
| Observability ownership | Ops/SRE reactive | Developer-owned, per-repo, first-class artifact |

---

## System Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│  REPO — Single source of truth                                      │
│                                                                     │
│  interaction-api/                  terraform/newrelic/              │
│  └── InteractionService.java       └── interaction-api-dashboard.tf │
│      │  recordCustomEvent()            │  widget_billboard           │
│      │  "InteractionCreated"           │  widget_line                │
│      │  { customerId, channel,         │  NRQL queries               │
│      │    agentId, queueWaitMs }       │                             │
└──────┼─────────────────────────────────┼─────────────────────────────┘
       │                                 │
       ▼                                 ▼
┌─────────────────────────────────────────────────────────────────────┐
│  RUNTIME                                                            │
│                                                                     │
│  interaction-api (Spring Boot 3 / Java 21)                          │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │                                                             │    │
│  │  POST /v1/interactions                                      │    │
│  │  GET  /v1/interactions/{id}          ──► H2 / RDS           │    │
│  │  GET  /v1/interactions/metrics                              │    │
│  │                                                             │    │
│  │  Layer 1: NR Java Agent (-javaagent)                        │    │
│  │  └── APM spans, JVM heap/GC, distributed traces             │    │
│  │                                                             │    │
│  │  Layer 2: NR Agent API (recordCustomEvent)                  │    │
│  │  └── InteractionCreated { customerId, channel,              │    │
│  │                           agentId, queueWaitMs }            │    │
│  │  └── InteractionFetched { interactionId, channel }          │    │
│  │                                                             │    │
│  │  Layer 3: Micrometer NR Registry                            │    │
│  │  └── Spring actuator + JVM metrics → NR Metric API          │    │
│  │                                                             │    │
│  └─────────────────────────────────────────────────────────────┘    │
│                      │                                              │
└──────────────────────┼──────────────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────────────────┐
│  NEW RELIC                                                          │
│                                                                     │
│  NRDB (custom events)          APM                                  │
│  ┌──────────────────────┐      ┌──────────────────────────────┐     │
│  │ InteractionCreated   │      │ Transaction (auto-traced)     │     │
│  │ InteractionFetched   │      │ TransactionError              │     │
│  └──────────┬───────────┘      │ JVM heap / GC / threads       │     │
│             │                  └──────────────────────────────┘     │
│             │  NRQL at render time                                  │
│             ▼                                                       │
│  Dashboard — "Interaction API — Business KPIs"                      │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │  Total interactions (billboard)                              │   │
│  │  Interactions/min by channel (line — FACET channel)          │   │
│  │  p95 queue wait time (line — percentile)                     │   │
│  │  Avg wait by channel (bar — FACET channel)                   │   │
│  │  Resolution rate (billboard — percentage)                    │   │
│  │  API p99 response time (line — Transaction data)             │   │
│  └──────────────────────────────────────────────────────────────┘   │
│                    ▲                                                │
│                    │  terraform apply                               │
└────────────────────┼────────────────────────────────────────────────┘
                     │
          terraform/newrelic/interaction-api-dashboard.tf
```

---

## The Three New Relic Integration Layers

Each layer is independently configured and owned by a different concern:

| Layer | Where configured | What it sends | NR event type | Owner |
|---|---|---|---|---|
| **NR Java Agent** | `Dockerfile` — `-javaagent` | APM spans, JVM heap/GC, distributed traces | `Transaction`, `Span`, `TransactionError` | Ops / Dockerfile |
| **NR Agent API** | `InteractionService.java` — `recordCustomEvent()` | Business KPIs per interaction | `InteractionCreated`, `InteractionFetched` | Developer |
| **Micrometer** | `build.gradle` — `micrometer-registry-new-relic` | Spring actuator + JVM metrics | `Metric` | Framework (auto) |

> **Why separate them?** Each can be updated, swapped, or disabled without
> touching the other two. The agent is an infrastructure concern. The API calls
> are a business logic concern. Micrometer is a framework concern.

---

## CI/CD Pipeline

```
Developer pushes code
        │
        ├── opens PR
        ▼
ci.yml (runs on every PR)
        ├── ./gradlew :interaction-api:build    ← compile + unit tests
        ├── SonarQube scan                      ← code quality gate  (Backlog 2)
        ├── Veracode SAST scan                  ← security gate      (Backlog 2)
        └── docker build                        ← image builds cleanly
        │
        │  PR reviewed + merged to main
        ▼
deploy.yml (workflow_dispatch → dev | staging | prod)
        ├── az login + AKS context
        ├── docker build + push to ACR
        ├── kubectl set image + rollout status   ← Backlog 4
        └── ngc-org/awe-action@v2               ← AWE smoke tests    (Backlog 3)
              awe-tests.yaml:
              ├── POST /v1/interactions → 201, $.id exists
              ├── GET  /v1/interactions/metrics → 200
              └── GET  /unknown-id → 404, $.errorCode = INTERACTION_NOT_FOUND
```

---

## Claude Agent — Backlog 1 (Automated Dashboard Generation)

This is the centerpiece. The agent runs after every deploy and keeps the
dashboard code in sync with the business events the service actually emits.

```
PHASE 1 — Discover business KPIs
        │
        │  Agent reads InteractionService.java
        │  finds: recordCustomEvent("InteractionCreated", { customerId,
        │                           channel, agentId, queueWaitMs })
        │  finds: recordCustomEvent("InteractionFetched", { interactionId,
        │                           channel })
        ▼
        candidate KPIs:
        - InteractionCreated.queueWaitMs   (numeric → percentile candidate)
        - InteractionCreated.channel       (string  → FACET candidate)
        - InteractionFetched.channel       (string  → FACET candidate)

PHASE 2 — Evaluate dashboard coverage
        │
        │  Agent reads interaction-api-dashboard.tf
        │  finds existing NRQL: FROM InteractionCreated (covered)
        │  gap detected: InteractionFetched not in any widget
        ▼
        gap report:
        - InteractionFetched → no widget exists

PHASE 3 — Open GitHub PR
        │
        │  Agent adds to interaction-api-dashboard.tf:
        │
        │  widget_line {
        │    title = "Fetch rate by channel"
        │    nrql_query {
        │      query = "SELECT rate(count(*), 1 minute)
        │               FROM InteractionFetched
        │               FACET channel
        │               SINCE 1 hour ago TIMESERIES"
        │    }
        │  }
        │
        └── opens GitHub draft PR:
              title: "observability: add InteractionFetched dashboard coverage"
              body:  gap analysis + proposed NRQL + rationale
```

### What makes this safe

- Agent **never runs `terraform apply`** — it only opens a PR
- Human reviews the NRQL before it reaches New Relic
- `terraform plan` in CI shows the exact diff before merge
- Rollback = `git revert` + `terraform apply`

---

## Event → Dashboard Pipeline (end to end)

```
Developer writes:
  nrAttributes.put("queueWaitMs", saved.getQueueWaitMs());
  recordCustomEvent("InteractionCreated", nrAttributes);
        │
        ▼
Service runs, NR Java Agent intercepts JVM
Custom event sent to NR Insights API:
  {
    eventType:   "InteractionCreated",
    customerId:  "cust-001",
    channel:     "VOICE",
    agentId:     "agent-42",
    queueWaitMs: 1500,
    timestamp:   ...
  }
        │
        ▼
Dashboard widget renders in real time:
  SELECT percentile(queueWaitMs, 95)
  FROM InteractionCreated
  SINCE 1 hour ago
  TIMESERIES
        │
        ▼
p95 line chart shows: 1500ms at T=now
```

The Terraform file is the **contract** between what the code emits and what
the dashboard visualises. Any mismatch (wrong attribute name, missing event)
shows up immediately as a blank widget — visible, auditable, fixable.

---

## Backlog Coverage Map

| Backlog | What in this repo proves it |
|---|---|
| **1 — NewRelic Observability** | `InteractionService` emits `InteractionCreated` custom events. `interaction-api-dashboard.tf` is dashboard-as-code. Claude agent auto-generates PR for new KPI gaps. |
| **2 — KTLO Remediation** | `ci.yml` gates every PR on SonarQube + Veracode. Dependabot closes the dependency-update loop. |
| **3 — AWE Delegation** | `awe-tests.yaml` is the per-repo test DSL. `deploy.yml` invokes `ngc-org/awe-action@v2` post-deploy. |
| **4 — Service Deployment** | `deploy.yml` is the GHA pipeline. K8s manifests in repo. `deploy-local.sh` for dev. |
| **5 — Error Traceability** | `GlobalExceptionHandler` emits `{ errorCode, message, timestamp }` on every error — NR-log-queryable by a Backlog 5 agent. |

---

## What Was Validated Hands-On

| Step | Validated |
|---|---|
| Service starts with NR Java Agent attached (`-javaagent`) | ✅ |
| `POST /v1/interactions` emits `InteractionCreated` event to NR | ✅ |
| `GET /v1/interactions/{id}` emits `InteractionFetched` event to NR | ✅ |
| NRQL queries return real data (`SELECT * FROM InteractionCreated`) | ✅ |
| `percentile()`, `rate()`, `FACET` aggregate live business data | ✅ |
| `terraform apply` creates NR dashboard from `.tf` file | ✅ |
| Dashboard widget shows live count from real events | ✅ |
