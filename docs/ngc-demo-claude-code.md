# NGC Platform Demo — Claude Code Instructions

## Context

This is an interview demo project for **NGC**, built to showcase skills across the
exact NGC tech stack. Every file in this project maps to one or more items in the
NGC engineering backlog. Do not generate anything that isn't listed here — keep the
scope tight and intentional.

**Candidate stack:** Java 21 · Spring Boot 3.x · Gradle multi-project ·
Docker · Minikube · LocalStack · Apache Kafka · NewRelic · Terraform · GitHub Actions

---

## Project structure (already exists in IntelliJ)

```
ngc-platform/
├── .github/
├── docker/
├── gradle/
├── interaction-api/          ← Gradle subproject (already registered)
├── k8s/
├── terraform/
│   └── newrelic/
│       └── interaction-api-dashboard.tf   ← file exists, fill content
├── build.gradle              ← root, already created
├── settings.gradle           ← already includes interaction-api
├── gradlew / gradlew.bat
└── .gitignore
```

---

## What has already been generated (do not regenerate)

| File | Status |
|------|--------|
| `settings.gradle` | Done — includes `interaction-api` |
| `build.gradle` (root) | Done — Spring Boot 3.3.5, Java 21 toolchain |
| `interaction-api/build.gradle` | Done — web, jpa, actuator, kafka, newrelic-api, lombok |
| `interaction-api/src/main/java/com/ngc/interaction/InteractionApiApplication.java` | Done |
| `interaction-api/src/main/java/com/ngc/interaction/domain/Interaction.java` | Done |
| `interaction-api/src/main/java/com/ngc/interaction/dto/CreateInteractionRequest.java` | Done |
| `interaction-api/src/main/java/com/ngc/interaction/dto/InteractionMetrics.java` | Done |
| `interaction-api/src/main/resources/application.yml` | Done |

---

## Files to generate (in this order)

### Step 4 — Repository

**`interaction-api/src/main/java/com/ngc/interaction/repository/InteractionRepository.java`**

- Extends `JpaRepository<Interaction, String>`
- Custom query: count by status RESOLVED → used for resolutionRate in metrics
- Custom query: average queueWaitMs → used for avgQueueWaitMs in metrics
- Custom query: group by channel → `Map<String, Long>` for byChannel in metrics
  (use a `@Query` with `GROUP BY i.channel` returning `List<Object[]>` — caller maps it)

---

### Step 5 — Service

**`interaction-api/src/main/java/com/ngc/interaction/service/InteractionService.java`**

Dependencies injected:
- `InteractionRepository repository`
- `KafkaTemplate<String, Object> kafkaTemplate`
- `@Value("${app.kafka.topic.interactions}") String interactionsTopic`

Methods:

**`create(CreateInteractionRequest request) → Interaction`**
1. Build and save `Interaction` from request using `Interaction.builder()`
2. Emit NewRelic custom event `"InteractionCreated"` with attributes:
   - `customerId`, `channel` (as string), `agentId`, `queueWaitMs`
3. Publish Kafka message to `interactionsTopic` with key = `interaction.getId()`
   and value = a Map containing `{ id, customerId, channel, status, createdAt }`
4. Return saved interaction

**`findById(String id) → Interaction`**
1. Call `repository.findById(id)` — throw `InteractionNotFoundException` if empty
2. Emit NewRelic custom event `"InteractionFetched"` with attributes:
   - `interactionId`, `channel` (as string)
3. Return the interaction

**`getMetrics() → InteractionMetrics`**
1. `totalInteractions` = `repository.count()`
2. `byChannel` = call the group-by-channel query, convert `List<Object[]>` to `Map<String, Long>`
3. `avgQueueWaitMs` = call avg query (return 0.0 if null)
4. `resolvedCount` = call count-by-status(RESOLVED)
5. `resolutionRate` = totalInteractions > 0 ? resolvedCount / (double) totalInteractions : 0.0
6. Return `new InteractionMetrics(totalInteractions, byChannel, avgQueueWaitMs, resolvedCount, resolutionRate)`

---

### Step 5a — Custom exception

**`interaction-api/src/main/java/com/ngc/interaction/exception/InteractionNotFoundException.java`**

- Extends `RuntimeException`
- Constructor takes `String id`
- Message: `"Interaction not found: " + id`
- Annotate with `@ResponseStatus(HttpStatus.NOT_FOUND)`

---

### Step 5b — Global exception handler

**`interaction-api/src/main/java/com/ngc/interaction/exception/GlobalExceptionHandler.java`**

- Annotate with `@RestControllerAdvice`
- Handle `InteractionNotFoundException` → 404 with body `{ "errorCode": "INTERACTION_NOT_FOUND", "message": "...", "timestamp": "..." }`
- Handle `MethodArgumentNotValidException` → 400 with body `{ "errorCode": "VALIDATION_ERROR", "errors": [...field errors...], "timestamp": "..." }`
- Handle generic `Exception` → 500 with body `{ "errorCode": "INTERNAL_ERROR", "message": "An unexpected error occurred", "timestamp": "..." }`

**Note on error messages (Backlog 5):** Every error response must include:
- `errorCode` — a machine-readable constant (e.g. `INTERACTION_NOT_FOUND`)
- `message` — human-readable, includes relevant IDs or field names
- `timestamp` — ISO-8601 `Instant.now().toString()`

This structured format is what the backlog 5 agent reads from NewRelic logs to
identify gaps and generate investigation-ready error prompts.

---

### Step 6 — Controller

**`interaction-api/src/main/java/com/ngc/interaction/controller/InteractionController.java`**

- Annotate with `@RestController`, `@RequestMapping("/v1/interactions")`
- Inject `InteractionService service`

Endpoints:

```
POST   /v1/interactions
  @RequestBody @Valid CreateInteractionRequest
  → 201 Created, body = saved Interaction

GET    /v1/interactions/{id}
  @PathVariable String id
  → 200 OK, body = Interaction
  → 404 if not found (handled by GlobalExceptionHandler)

GET    /v1/interactions/metrics
  → 200 OK, body = InteractionMetrics
```

---

### Step 7 — Unit tests

**`interaction-api/src/test/java/com/ngc/interaction/controller/InteractionControllerTest.java`**

Use `@WebMvcTest(InteractionController.class)` with `@MockBean InteractionService`.

Test cases:
1. `POST /v1/interactions` with valid body → assert 201, response has `id` field
2. `POST /v1/interactions` with missing `customerId` → assert 400
3. `POST /v1/interactions` with missing `channel` → assert 400
4. `GET /v1/interactions/{id}` with valid id → assert 200
5. `GET /v1/interactions/{id}` with unknown id → assert 404, body has `errorCode = INTERACTION_NOT_FOUND`
6. `GET /v1/interactions/metrics` → assert 200, body has `totalInteractions` field

**`interaction-api/src/test/java/com/ngc/interaction/service/InteractionServiceTest.java`**

Use `@ExtendWith(MockitoExtension.class)`.

Test cases:
1. `create()` saves interaction and emits Kafka message
2. `create()` records NewRelic event (verify `NewRelic.recordCustomEvent` is called — use a static mock)
3. `findById()` throws `InteractionNotFoundException` when id not found
4. `getMetrics()` returns correct `resolutionRate` (e.g. 2 resolved out of 4 total = 0.5)

---

### Step 8 — Dockerfile

**`interaction-api/Dockerfile`**

Multi-stage build:

```dockerfile
# Stage 1 — build
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace
COPY gradlew .
COPY gradle gradle
COPY build.gradle settings.gradle ./
COPY interaction-api/build.gradle interaction-api/
COPY interaction-api/src interaction-api/src
RUN ./gradlew :interaction-api:bootJar --no-daemon

# Stage 2 — runtime + NewRelic agent
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# NewRelic Java agent — downloaded at build time, not committed to git
ADD https://download.newrelic.com/newrelic/java-agent/newrelic-agent/current/newrelic-java.zip /tmp/newrelic-java.zip
RUN unzip /tmp/newrelic-java.zip -d /opt && rm /tmp/newrelic-java.zip

COPY --from=build /workspace/interaction-api/build/libs/interaction-api.jar app.jar

ENV JAVA_TOOL_OPTIONS="-javaagent:/opt/newrelic/newrelic.jar"
ENV NEW_RELIC_APP_NAME="interaction-api"
ENV NEW_RELIC_LOG_LEVEL="info"
# NEW_RELIC_LICENSE_KEY injected at runtime via K8s secret / docker-compose env

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
```

---

### Step 9 — docker-compose (LocalStack + service)

**`docker/docker-compose.yml`**

Services:
1. **localstack** — image `localstack/localstack:3.4`, ports `4566:4566`,
   env `SERVICES=kafka,s3,sqs`, volume `./localstack-data:/var/lib/localstack`
2. **interaction-api** — build from `../interaction-api`, port `8080:8080`,
   depends_on localstack, env vars:
   - `KAFKA_BOOTSTRAP_SERVERS=localstack:4566`
   - `KAFKA_TOPIC_INTERACTIONS=interaction-events`
   - `NEW_RELIC_LICENSE_KEY=${NEW_RELIC_LICENSE_KEY}`
   - `SPRING_PROFILES_ACTIVE=local`

Also include an **init container / healthcheck command** that uses the AWS CLI
to create the Kafka topic `interaction-events` on LocalStack after it starts.
Use a `localstack-init` service with image `amazon/aws-cli` that runs:
```bash
aws --endpoint-url=http://localstack:4566 kafka create-cluster ... 
```
Or simply use `awslocal` in a shell script at `docker/init-kafka.sh`.

---

### Step 10 — K8s ConfigMap

**`k8s/interaction-api/configmap.yaml`**

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: interaction-api-config
  namespace: ngc-dev
data:
  SPRING_PROFILES_ACTIVE: "k8s"
  KAFKA_BOOTSTRAP_SERVERS: "kafka-service:9092"
  KAFKA_TOPIC_INTERACTIONS: "interaction-events"
  NEW_RELIC_APP_NAME: "interaction-api"
```

---

### Step 11 — K8s Deployment

**`k8s/interaction-api/deployment.yaml`**

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: interaction-api
  namespace: ngc-dev
  labels:
    app: interaction-api
    version: "0.1.0"
spec:
  replicas: 1       # 1 for Minikube, 2 for AKS (override via Kustomize)
  selector:
    matchLabels:
      app: interaction-api
  template:
    metadata:
      labels:
        app: interaction-api
    spec:
      containers:
        - name: interaction-api
          image: interaction-api:latest
          imagePullPolicy: IfNotPresent   # for Minikube local image
          ports:
            - containerPort: 8080
          envFrom:
            - configMapRef:
                name: interaction-api-config
          env:
            - name: NEW_RELIC_LICENSE_KEY
              valueFrom:
                secretKeyRef:
                  name: newrelic-secret
                  key: license-key
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8080
            initialDelaySeconds: 30
            periodSeconds: 10
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 8080
            initialDelaySeconds: 15
            periodSeconds: 5
          resources:
            requests:
              memory: "256Mi"
              cpu: "250m"
            limits:
              memory: "512Mi"
              cpu: "500m"
```

---

### Step 12 — K8s Service

**`k8s/interaction-api/service.yaml`**

```yaml
apiVersion: v1
kind: Service
metadata:
  name: interaction-api
  namespace: ngc-dev
spec:
  selector:
    app: interaction-api
  ports:
    - protocol: TCP
      port: 80
      targetPort: 8080
  type: ClusterIP
```

---

### Step 13 — Namespace + deploy script

**`k8s/namespace.yaml`**
```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: ngc-dev
```

**`scripts/deploy-local.sh`**

```bash
#!/usr/bin/env bash
set -euo pipefail

echo "→ Starting Minikube..."
minikube start --driver=docker --memory=4g --cpus=2

echo "→ Applying namespace..."
kubectl apply -f k8s/namespace.yaml

echo "→ Building Docker image inside Minikube..."
eval $(minikube docker-env)
docker build -t interaction-api:latest -f interaction-api/Dockerfile .

echo "→ Applying K8s manifests..."
kubectl apply -f k8s/interaction-api/configmap.yaml
kubectl apply -f k8s/interaction-api/deployment.yaml
kubectl apply -f k8s/interaction-api/service.yaml

echo "→ Waiting for rollout..."
kubectl rollout status deployment/interaction-api -n ngc-dev

echo "→ Forwarding port 8080..."
kubectl port-forward svc/interaction-api 8080:80 -n ngc-dev &

echo "✓ Service available at http://localhost:8080"
echo "  POST /v1/interactions"
echo "  GET  /v1/interactions/{id}"
echo "  GET  /v1/interactions/metrics"
```

---

### Step 14 — AWE test file (Backlog 3)

**`interaction-api/awe-tests.yaml`**

```yaml
version: "1"
service: interaction-api
description: "Smoke and integration tests for customer interaction API"

env:
  base_url: "${AWE_BASE_URL}"

tests:
  - id: create-interaction
    name: "POST /v1/interactions returns 201"
    type: http
    request:
      method: POST
      path: /v1/interactions
      headers:
        Content-Type: application/json
      body:
        customerId: "awe-smoke-001"
        channel: VOICE
        queueWaitMs: 1200
    assert:
      - status: 201
      - body: "$.id" exists
      - body: "$.status" equals("CREATED")
      - latency: lt(500ms)
    cleanup:
      delete: "$.id"

  - id: metrics-healthy
    name: "GET /v1/interactions/metrics returns valid KPIs"
    type: http
    request:
      method: GET
      path: /v1/interactions/metrics
    assert:
      - status: 200
      - body: "$.totalInteractions" gte(0)
      - body: "$.resolutionRate" gte(0)

  - id: unknown-id-404
    name: "Unknown ID returns 404 with typed error code"
    type: http
    request:
      method: GET
      path: /v1/interactions/does-not-exist
    assert:
      - status: 404
      - body: "$.errorCode" equals("INTERACTION_NOT_FOUND")
```

---

### Step 15 — Terraform NewRelic dashboard (Backlog 1)

**`terraform/newrelic/interaction-api-dashboard.tf`**

```hcl
terraform {
  required_providers {
    newrelic = {
      source  = "newrelic/newrelic"
      version = "~> 3.0"
    }
  }
}

variable "nr_account_id" {
  description = "NewRelic account ID"
  type        = number
}

resource "newrelic_one_dashboard" "interaction_api" {
  name        = "Interaction API — Business KPIs"
  permissions = "public_read_only"

  page {
    name = "Overview"

    # --- Interactions per minute by channel (line chart) ---
    widget_line {
      title  = "Interactions per minute by channel"
      row    = 1
      column = 1
      width  = 8
      height = 3

      nrql_query {
        account_id = var.nr_account_id
        query      = <<-NRQL
          SELECT rate(count(*), 1 minute)
          FROM InteractionCreated
          FACET channel
          SINCE 1 hour ago
          TIMESERIES
        NRQL
      }
    }

    # --- Total interactions (billboard) ---
    widget_billboard {
      title  = "Total interactions (1 hour)"
      row    = 1
      column = 9
      width  = 4
      height = 3

      nrql_query {
        account_id = var.nr_account_id
        query      = "SELECT count(*) AS 'interactions' FROM InteractionCreated SINCE 1 hour ago"
      }
    }

    # --- p95 queue wait time (line chart) ---
    widget_line {
      title  = "p95 queue wait time (ms)"
      row    = 4
      column = 1
      width  = 6
      height = 3

      nrql_query {
        account_id = var.nr_account_id
        query      = <<-NRQL
          SELECT percentile(queueWaitMs, 95) AS 'p95 wait'
          FROM InteractionCreated
          SINCE 1 hour ago
          TIMESERIES
        NRQL
      }
    }

    # --- Resolution rate (billboard) ---
    widget_billboard {
      title  = "Resolution rate"
      row    = 4
      column = 7
      width  = 3
      height = 3

      nrql_query {
        account_id = var.nr_account_id
        query      = <<-NRQL
          SELECT percentage(count(*), WHERE status = 'RESOLVED') AS 'resolution rate'
          FROM InteractionCreated
          SINCE 1 hour ago
        NRQL
      }
    }

    # --- p99 API latency (APM Transaction data) ---
    widget_line {
      title  = "API p99 response time (ms)"
      row    = 4
      column = 10
      width  = 3
      height = 3

      nrql_query {
        account_id = var.nr_account_id
        query      = <<-NRQL
          SELECT percentile(duration * 1000, 99) AS 'p99 ms'
          FROM Transaction
          WHERE appName = 'interaction-api'
          SINCE 1 hour ago
          TIMESERIES
        NRQL
      }
    }
  }
}
```

---

### Step 16 — GitHub Actions CI (Backlog 2)

**`.github/workflows/ci.yml`**

```yaml
name: CI

on:
  push:
    branches: [main, develop]
  pull_request:
    branches: [main]

jobs:
  build-and-test:
    runs-on: ubuntu-latest

    steps:
      - uses: actions/checkout@v4

      - name: Set up Java 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'

      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v3

      - name: Build and test
        run: ./gradlew :interaction-api:build --no-daemon

      - name: SonarQube scan
        env:
          SONAR_TOKEN: ${{ secrets.SONAR_TOKEN }}
          SONAR_HOST_URL: ${{ secrets.SONAR_HOST_URL }}
        run: |
          ./gradlew :interaction-api:sonar \
            -Dsonar.projectKey=interaction-api \
            -Dsonar.host.url=$SONAR_HOST_URL \
            -Dsonar.login=$SONAR_TOKEN \
            --no-daemon

      - name: Veracode scan
        uses: veracode/veracode-uploadandscan-action@v1
        with:
          appname: 'interaction-api'
          createprofile: true
          filepath: 'interaction-api/build/libs/interaction-api.jar'
          vid: ${{ secrets.VERACODE_API_ID }}
          vkey: ${{ secrets.VERACODE_API_KEY }}

      - name: Build Docker image
        run: |
          docker build \
            -t interaction-api:${{ github.sha }} \
            -f interaction-api/Dockerfile .
```

---

### Step 17 — GitHub Actions deploy (Backlog 4)

**`.github/workflows/deploy.yml`**

```yaml
name: Deploy

on:
  workflow_dispatch:
    inputs:
      environment:
        description: 'Target environment'
        type: choice
        options: [dev, staging, prod]
        required: true

jobs:
  deploy:
    runs-on: ubuntu-latest
    environment: ${{ inputs.environment }}

    steps:
      - uses: actions/checkout@v4

      - name: Azure login
        uses: azure/login@v1
        with:
          creds: ${{ secrets.AZURE_CREDENTIALS }}

      - name: Set AKS context
        uses: azure/aks-set-context@v3
        with:
          resource-group: ${{ vars.AKS_RESOURCE_GROUP }}
          cluster-name: ${{ vars.AKS_CLUSTER_NAME }}

      - name: Build and push image
        run: |
          docker build \
            -t ${{ vars.ACR_REGISTRY }}/interaction-api:${{ github.sha }} \
            -f interaction-api/Dockerfile .
          docker push ${{ vars.ACR_REGISTRY }}/interaction-api:${{ github.sha }}

      - name: Deploy to AKS
        run: |
          kubectl set image deployment/interaction-api \
            interaction-api=${{ vars.ACR_REGISTRY }}/interaction-api:${{ github.sha }} \
            --namespace=${{ inputs.environment }}
          kubectl rollout status deployment/interaction-api \
            --namespace=${{ inputs.environment }}
            --timeout=120s

      - name: Run AWE tests
        uses: ngc-org/awe-action@v2
        with:
          test-file: ./interaction-api/awe-tests.yaml
          environment: ${{ inputs.environment }}
          base-url: https://interaction-api.${{ inputs.environment }}.ngc.internal
          fail-on-error: true
        env:
          AWE_TOKEN: ${{ secrets.AWE_ENGINE_TOKEN }}
```

---

## Backlog mapping — interview talking points

| Backlog | What in this repo proves it |
|---------|----------------------------|
| 1 — NewRelic observability | `InteractionService` emits `InteractionCreated` custom events. `terraform/newrelic/interaction-api-dashboard.tf` is the Terraform a backlog-1 agent would auto-generate as a PR. |
| 2 — KTLO remediation | `.github/workflows/ci.yml` integrates SonarQube + Veracode. Adding Dependabot config to `.github/dependabot.yml` closes the loop. |
| 3 — AWE delegation | `interaction-api/awe-tests.yaml` shows the per-repo DSL pattern. The `ngc-org/awe-action@v2` step in `deploy.yml` is the GitHub Actions invocation. |
| 4 — Service deployment | `scripts/deploy-local.sh` is the dev CLI. `deploy.yml` is the GHA pipeline. The K8s manifests are the deployment contract. |
| 5 — Error traceability | `GlobalExceptionHandler` emits structured `{ errorCode, message, timestamp }` on every error. These land in NewRelic logs with a `traceId`, making errors directly investigable. |

---

## Key design decisions to mention in the interview

1. **Java 21 records for DTOs** — immutable, no Lombok, deserializes cleanly with `@RequestBody`
2. **H2 for local/Minikube, real DB for AKS** — same YAML, different ConfigMap values
3. **NR agent in Dockerfile, NR API in build.gradle** — agent = infrastructure concern, API = code concern, deliberate separation
4. **awe-tests.yaml in the repo root of each service** — the migration from centralized AWE to per-repo ownership
5. **Structured error codes** — machine-readable `errorCode` field enables backlog 5's agent to query NR logs and identify investigation gaps autonomously
