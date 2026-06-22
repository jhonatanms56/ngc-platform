# NewRelic Observability — Design Document
## Backlog 1: Automated Dashboard Generation via Claude Agent

---

## 1. Problem Statement

Teams spend significant time manually building and rebuilding New Relic dashboards to
surface business KPIs for their microservices. This work is:

- **Reactive** — dashboards are created after incidents expose gaps
- **Undiscoverable** — KPIs live in UI clicks, not in the repo
- **Not PR-reviewable** — no diff, no history, no peer review
- **Not agent-readable** — a Claude agent cannot propose improvements to a dashboard
  that only exists as NR UI state

The goal is to make observability a first-class engineering artifact: defined in code,
versioned in git, and improvable by an autonomous agent.

---

## 2. Solution Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  SOURCE OF TRUTH: The Repo                                                  │
│                                                                             │
│  interaction-api/                    terraform/newrelic/                    │
│  └── src/                           └── interaction-api-dashboard.tf        │
│      └── InteractionService.java         (dashboard-as-code)                │
│          │                               │                                  │
│          │  emits custom events          │  defines NRQL widgets            │
│          └──────────────┐               └──────────────┐                   │
└─────────────────────────┼───────────────────────────────┼───────────────────┘
                          │                               │
                          ▼                               ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  RUNTIME                                                                    │
│                                                                             │
│  interaction-api pod                   New Relic Platform                  │
│  ┌──────────────────────┐             ┌─────────────────────────────────┐  │
│  │  POST /v1/interactions│             │  Custom Events (Insights API)   │  │
│  │         │            │  ─────────► │  InteractionCreated             │  │
│  │  InteractionService  │             │  InteractionFetched              │  │
│  │         │            │             └──────────────┬──────────────────┘  │
│  │  NR Agent API        │                            │                     │
│  │  recordCustomEvent() │             ┌──────────────▼──────────────────┐  │
│  └──────────────────────┘             │  Dashboard (managed by TF)      │  │
│                                       │  ┌──────────────────────────┐   │  │
│  NR Java Agent (via Dockerfile)       │  │ Interactions/min by chan  │   │  │
│  ┌──────────────────────┐  ─────────► │  │ Total interactions        │   │  │
│  │  APM / JVM / Traces  │             │  │ p95 queue wait time       │   │  │
│  └──────────────────────┘             │  │ Resolution rate           │   │  │
│                                       │  │ API p99 response time     │   │  │
│  Micrometer NR Registry   ─────────► │  └──────────────────────────┘   │  │
│  (actuator metrics)                   └─────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Three NR Integration Layers

Each layer serves a distinct purpose and is configured independently:

| Layer | Where Configured | What It Sends | NR Event Type |
|-------|-----------------|---------------|---------------|
| **NR Java Agent** | `Dockerfile` — `JAVA_TOOL_OPTIONS=-javaagent` | APM spans, JVM heap/GC, distributed traces | `Transaction`, `TransactionError`, `Span` |
| **NR Agent API** | `InteractionService.java` — `recordCustomEvent()` | Business KPIs per interaction | `InteractionCreated`, `InteractionFetched` |
| **Micrometer NR Registry** | `build.gradle` — `micrometer-registry-new-relic` | Spring actuator + JVM metrics | `Metric` |

> **Why separate them?**
> The agent is an infrastructure concern — ops deploys it via the Dockerfile.
> The API calls are a code concern — developers own them alongside the business logic.
> Micrometer is a framework concern — Spring Boot auto-configures it.
> Each can be updated, swapped, or disabled without touching the other two.

---

## 4. Event → Dashboard Pipeline

```
Developer writes code
        │
        │  adds NewRelic.getAgent().getInsights()
        │  .recordCustomEvent("InteractionCreated", attrs)
        ▼
Service runs in K8s pod
        │
        │  NR Java Agent intercepts JVM
        │  custom event sent to NR Insights API
        ▼
New Relic stores event
  { eventType: "InteractionCreated",
    customerId: "...",
    channel:    "VOICE",
    agentId:    "...",
    queueWaitMs: 1200,
    timestamp:  ... }
        │
        │  NRQL in dashboard.tf queries this event
        ▼
Dashboard widget renders
  SELECT rate(count(*), 1 minute)
  FROM InteractionCreated
  FACET channel
  SINCE 1 hour ago TIMESERIES
```

The Terraform file is the contract between the events the code emits and the
widgets that visualise them. Any mismatch (wrong attribute name, missing event)
is surfaced immediately as a blank widget — visible, auditable, fixable.

---

## 5. Terraform Flow

```
terraform/newrelic/
└── interaction-api-dashboard.tf

        terraform init
              │
              ▼
        terraform plan    ←── diff against NR current state
              │
              ▼
        terraform apply   ───► newrelic_one_dashboard resource
                                    updated in NR
```

### State management (production)

In a real NGC environment the Terraform state lives in a remote backend (e.g. Azure
Blob Storage), so multiple engineers and the CI agent can plan/apply safely:

```hcl
terraform {
  backend "azurerm" {
    resource_group_name  = "ngc-tf-state"
    storage_account_name = "ngctfstate"
    container_name       = "tfstate"
    key                  = "newrelic/interaction-api.tfstate"
  }
}
```

---

## 6. Claude Agent Flow — Backlog 1

The agent loop runs in three phases. Each can be triggered manually by a developer
or scheduled to run autonomously (e.g. after each deploy or on a nightly cron).

```
┌──────────────────────────────────────────────────────────────────────────┐
│  PHASE 1 — Discover Business KPIs                                        │
│                                                                          │
│  Agent reads the service repo:                                           │
│  • InteractionService.java  → finds recordCustomEvent() calls            │
│  • CreateInteractionRequest → finds input fields (customerId, channel…)  │
│  • InteractionMetrics       → finds KPI fields (resolutionRate, avg…)    │
│  • awe-tests.yaml           → finds what the team already validates      │
│                                                                          │
│  Output: list of candidate KPIs with event names and attribute keys      │
└────────────────────────────────────┬─────────────────────────────────────┘
                                     │
                                     ▼
┌──────────────────────────────────────────────────────────────────────────┐
│  PHASE 2 — Evaluate Current Dashboard Coverage                           │
│                                                                          │
│  Agent reads terraform/newrelic/interaction-api-dashboard.tf:            │
│  • Parses existing nrql_query blocks                                     │
│  • Extracts which event types, attributes, and aggregations are covered  │
│  • Compares against Phase 1 KPI list                                     │
│                                                                          │
│  Output: gap analysis — KPIs emitted by code but not visible in NR       │
└────────────────────────────────────┬─────────────────────────────────────┘
                                     │
                                     ▼
┌──────────────────────────────────────────────────────────────────────────┐
│  PHASE 3 — Draft Terraform PR                                            │
│                                                                          │
│  Agent edits interaction-api-dashboard.tf:                               │
│  • Adds missing widget blocks for uncovered KPIs                         │
│  • Proposes NRQL queries grounded on the actual event attributes         │
│  • Opens a GitHub PR with:                                               │
│    - title: "observability: add [KPI name] dashboard coverage"           │
│    - body: gap analysis, proposed widgets, NRQL rationale                │
│                                                                          │
│  Human reviews and merges. terraform apply runs in CI.                   │
└──────────────────────────────────────────────────────────────────────────┘
```

### What makes this loop safe

- The agent never applies Terraform — it only opens a PR
- A human reviews the NRQL before it reaches New Relic
- `terraform plan` in CI shows the exact diff before merge
- Rollback is `git revert` + `terraform apply`

---

## 7. Developer CI/CD Integration

```
Developer pushes code change
          │
          ├─► adds new recordCustomEvent() call
          │   (e.g. "InteractionResolved" with resolutionTimeMs)
          │
          ▼
  ci.yml runs on PR
  ┌────────────────────────────────────────┐
  │ 1. ./gradlew :interaction-api:build    │  ← compile + unit tests
  │ 2. SonarQube scan                      │  ← code quality gate
  │ 3. Veracode SAST scan                  │  ← security gate
  │ 4. docker build                        │  ← image builds cleanly
  └────────────────────────────────────────┘
          │
          │  PR merged to main
          ▼
  deploy.yml runs (workflow_dispatch or auto)
  ┌────────────────────────────────────────┐
  │ 1. az login + AKS context              │
  │ 2. docker build + push to ACR          │
  │ 3. kubectl set image + rollout status  │
  │ 4. awe-action: run awe-tests.yaml      │  ← smoke tests against live env
  └────────────────────────────────────────┘
          │
          │  new event type live in NR
          ▼
  [TRIGGER] Claude Agent — Backlog 1
  ┌────────────────────────────────────────┐
  │ 1. Scan repo for new KPI signals       │
  │ 2. Compare to current dashboard.tf     │
  │ 3. Open PR with new widget blocks      │
  └────────────────────────────────────────┘
          │
          │  Terraform PR reviewed + merged
          ▼
  terraform apply (CI or manual)
  ┌────────────────────────────────────────┐
  │ terraform plan → NR provider API       │
  │ dashboard updated in New Relic UI      │
  └────────────────────────────────────────┘
```

### Full end-to-end timeline

```
t=0   Developer adds new business event to InteractionService
t=5m  CI passes (build + test + scan)
t=10m PR merged, deploy.yml runs
t=15m New pod live in AKS, event flowing to New Relic
t=20m Claude agent detects gap, opens Terraform PR
t=?   Human reviews and merges Terraform PR
t+2m  terraform apply → dashboard updated in New Relic
```

---

## 8. What a New KPI Addition Looks Like

**Developer change** — `InteractionService.java`:
```java
// new event when an interaction is resolved
nrAttributes.put("resolutionTimeMs", resolutionTimeMs);
NewRelic.getAgent().getInsights().recordCustomEvent("InteractionResolved", nrAttributes);
```

**Agent-generated Terraform PR** — `interaction-api-dashboard.tf`:
```hcl
widget_line {
  title  = "p95 resolution time (ms)"
  row    = 7
  column = 1
  width  = 6
  height = 3

  nrql_query {
    account_id = var.nr_account_id
    query      = <<-NRQL
      SELECT percentile(resolutionTimeMs, 95)
      FROM InteractionResolved
      SINCE 1 hour ago
      TIMESERIES
    NRQL
  }
}
```

The agent grounds the NRQL on the exact attribute names it read from the service code —
no guessing, no manual lookup.

---

## 9. Summary

| Concern | Owner | Artifact |
|---------|-------|----------|
| What events are emitted | Developer | `InteractionService.java` |
| What events are visualised | Agent + Human review | `interaction-api-dashboard.tf` |
| How the dashboard reaches NR | CI / ops | `terraform apply` |
| Whether the service is healthy post-deploy | AWE + CI | `awe-tests.yaml` + `deploy.yml` |
| Whether new KPI gaps exist | Claude Agent (Backlog 1) | PR against `dashboard.tf` |
