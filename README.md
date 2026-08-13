# Sentinel — Incident Intelligence & Reliability Platform

Sentinel takes the raw noise a production system emits — alerts, metrics, error logs, deployment
events — and turns it into a small number of incidents that a human can actually work.

The problem it solves is the one every on-call engineer knows: a single failure in a payment
service fires eleven alerts across four teams, and the first fifteen minutes of the response are
spent working out that they are all the same thing. Sentinel does that correlation automatically,
links the deployment that probably caused it, and drafts an explanation.

```
alerts ─┐
metrics ├─► ingest ─► Kafka ─► correlation ─► incident ─► SSE ─► browser
logs   ─┤   (dedup,          (graph-aware   (commands,
deploys ┘   rate limit)       scoring)       escalation)
                                  │
                                  └─► insight (LLM root cause + postmortem)
```

---

## What it actually does

**Correlates instead of aggregating.** Two alerts belong to the same incident when they are close in
the service dependency graph, close in time, similar in severity, and share labels. Each factor is
scored independently and combined with tunable weights; the combined score is stored on the signal,
so every correlation decision can be explained after the fact rather than trusted blindly.

**Knows the topology.** The service graph is a first-class object. Correlation walks it with a
best-first search that maximises the product of edge criticalities, which means an alert on
`checkout-api` and one on `orders-postgres` correlate strongly if there is a critical path between
them, and not at all if there is not.

**Deduplicates properly.** Fingerprints are SHA-256 over the semantic content of a signal with
volatile labels stripped — pod name, instance id, trace id — and numbers and UUIDs normalised out of
log messages. A pod restart loop across forty pods is one signal, not forty.

**Correlates deployments.** Suspicion is scored from recency and graph proximity to the affected
service, weighted by whether the deploy succeeded, failed, or rolled back. It surfaces the top
suspects and explicitly does **not** trigger an automatic rollback — the platform's job is to put the
right information in front of a human quickly.

**Escalates.** Unacknowledged incidents climb their policy's ladder. The sweep uses
`FOR UPDATE SKIP LOCKED`, so every replica can run it concurrently, take a disjoint batch, and never
page the same person twice — no leader election, no distributed lock.

**Explains.** The insight service assembles the incident's evidence and asks a model for calibrated
hypotheses with likelihood scores and a next step for each. Output is advisory, labelled with its
confidence and the model that produced it, and nothing in the platform acts on it automatically.

**Streams.** Incident changes reach the browser over SSE within a few hundred milliseconds, fanned
out across replicas via Kafka.

---

## Quick start

Prerequisites: Docker Desktop (or Docker Engine with Compose v2), GNU Make, Python 3, and at least
8 GB of memory available to Docker. Java, Maven, Node, Postgres, Redis and Kafka do not need to be
installed on the host for the containerised quick start.

```bash
cd iip
make doctor             # verify Docker, Compose and the demo-script runtime
make up                 # postgres, redis, kafka, four services, web, prometheus, grafana
make seed               # fire a realistic cascading failure at the stack
open http://localhost:3000
```

On Linux use `xdg-open http://localhost:3000`, or open the URL manually. The first image build can
take several minutes. Wait until `docker compose ps` reports the services as running/healthy before
seeding. Sign in with the prefilled `sre@acme.io`, tenant `acme`, and the `COMMANDER` role. The seed
script obtains its own local JWT; no token or model key is required.

`make seed` ships a bad `payment-gateway` deploy, then degrades `checkout-api` and `edge-gateway`
behind it. You should get **one** incident spanning three services, with the deployment linked as the
top suspect — not three separate pages.

`make storm` does the opposite: hundreds of flapping signals that should collapse into a handful of
incidents, which is how you see deduplication and rate limiting working.

| Surface | URL |
| --- | --- |
| Web app | http://localhost:3000 |
| Grafana | http://localhost:3001 |
| Prometheus | http://localhost:9090 |
| Ingest API | http://localhost:8081 |
| Incident API + OpenAPI | http://localhost:8083/docs |

No API key is needed. `INSIGHT_MODE=demo` is the default: it returns a curated, deterministic
analysis through the real context, parsing, persistence and UI pipeline without making an external
request. The UI labels this output **Demo analysis**, and disables regeneration once it is stored so
public visitors cannot create model costs.

The available modes are:

| `INSIGHT_MODE` | Intended use | External cost |
| --- | --- | --- |
| `demo` | Public portfolio and local product demonstration | None |
| `stub` | Explicit failure/empty-provider testing | None |
| `anthropic` | Private live model evaluation | Usage-based |

### Enable real root-cause analysis

For private testing only, create a local environment file from the checked-in template, change
`INSIGHT_MODE` to `anthropic`, and add an Anthropic API key. Never commit the populated file; `.env`
is ignored by Git.

```bash
cp .env.example .env
# Edit .env: set INSIGHT_MODE=anthropic and ANTHROPIC_API_KEY, then recreate the service:
docker compose up -d --build --force-recreate insight-service
docker compose ps insight-service
```

The default model is the pinned `claude-sonnet-4-20250514` snapshot. Pinning makes repeated analyses
and incident audits reproducible; change `INSIGHT_MODEL` in `.env` only as an intentional model
upgrade. Open an incident and choose **Analyse** or **Regenerate** after the service is healthy. The
generated result records the model and token usage, while the deterministic stub remains available
when Anthropic mode is selected without a key. Return to `INSIGHT_MODE=demo` and remove the key before
publishing a portfolio deployment.

If analysis fails, inspect only the insight service first:

```bash
docker compose logs --tail=200 insight-service
curl -i http://localhost:8084/actuator/health
```

If the stack was started before a configuration change, run `make down && make up`. If database or
Kafka state is incompatible with the current code, `make reset && make up` recreates the local
volumes (and intentionally deletes local demo data). Use `make logs` for application logs and
`docker compose logs kafka postgres redis` for infrastructure startup failures.

---

## Free portfolio showcase

The repository also ships a frontend-only showcase for free personal hosting. It uses the same
pages and components as the live platform, with seeded browser-local data replacing network calls.
Visitors can filter incidents, inspect the dependency graph, run the incident workflow and view the
curated RCA/postmortem. Their changes stay in their own browser and no database, Kafka cluster,
secret or model API is involved.

Run it locally:

```bash
cd web
cp .env.showcase.example .env.local
npm ci
npm run dev
```

Deploy it from this monorepo on Vercel:

1. Import the GitHub repository.
2. Set **Root Directory** to `web`.
3. Keep the detected Next.js build settings.
4. Add `NEXT_PUBLIC_SHOWCASE_MODE=true` for Production, Preview and Development.
5. Deploy. Do not add database, JWT or model-provider secrets to this frontend project.

Vercel's Hobby plan is intended for personal, non-commercial projects. The full Docker Compose
platform remains the end-to-end development environment; the hosted showcase is deliberately a
safe, zero-cost presentation surface.

---

## Architecture

### Services

| Service | Port | Responsibility |
| --- | --- | --- |
| `ingest-service` | 8081 | Validation, idempotency, rate limiting, publish to Kafka |
| `correlation-service` | 8082 | Dedup, graph-aware scoring, incident creation, deployment linking |
| `incident-service` | 8083 | Commands, queries, RBAC, SSE fan-out, escalation, analytics |
| `insight-service` | 8084 | LLM root cause analysis and postmortem drafting |

Shared code lives in two library modules: `platform-common` (event contracts, Kafka config, security,
error handling) and `platform-domain` (JPA entities, repositories, Flyway migrations).

### Why these boundaries

The split follows failure domains and scaling profiles, not org-chart tidiness:

- **Ingest is the only internet-facing service** and the only one that must never lose data under
  load. It does no database work at all — validate, dedupe, publish, return `202`. It scales to 20
  replicas independently of everything else.
- **Correlation is CPU-bound and stateful-ish.** It holds a cached service graph and does the
  scoring. It is the one service where a slow query directly delays incident creation.
- **Incident is request/response and holds the long-lived SSE connections.** Its scaling driver is
  concurrent browsers, which has nothing to do with signal volume.
- **Insight can be completely down without affecting incident response.** That is the point of
  separating it: a model provider outage degrades one panel in the UI, not the pipeline.

### Deliberate trade-offs

Where a simpler choice was made, it was made on purpose:

**Shared schema between correlation and incident.** Both services read and write the same `incident`
tables. Splitting them into separate databases would be textbook microservices, and would mean
either distributed transactions or eventual consistency on a record two services mutate within
seconds of each other. Instead they share one bounded context and both deploy identical migrations,
so the schema cannot drift. Insight owns its own concerns and only reads.

**Publish-after-commit, not transactional outbox.** Incident events go to Kafka in a
`TransactionSynchronization` after commit. A crash in the window between commit and publish loses the
event — the incident is durable, the notification is not. A full outbox with a relay is the correct
answer at higher stakes; it is documented in `IncidentEventPublisher` rather than silently omitted.

**SSE over WebSockets.** Traffic is strictly server-to-client. SSE gives automatic browser
reconnection, plain HTTP semantics through load balancers, and no protocol upgrade to configure.

**Polling escalation, not per-incident timers.** A million in-flight timers is a memory leak that
also does not survive a restart. A query against a partial index is cheap and stateless.

**Token in the query string for SSE.** `EventSource` cannot set headers. The tokens are short-lived
and tenant-scoped; the alternative (a cookie) drags CSRF back into a stateless API. This is a real
trade-off, noted in `StreamController` and here rather than buried.

**Correlation is tuned, not learned.** The scoring weights are configuration, not a model. A
tunable function whose output can be explained line by line beats a classifier nobody can debug at
3am — and there is no labelled incident data to train on anyway.

### Reliability mechanics

- **Retry and DLQ.** Consumers use `DefaultErrorHandler` with exponential backoff (1s → 30s, five
  attempts) and a `DeadLetterPublishingRecoverer`. Deserialization and validation failures are
  classified non-retryable and go straight to the DLQ — retrying a malformed message five times just
  delays the inevitable.
- **Idempotency.** Producers are idempotent with `acks=all`. Ingest holds a Redis `SET NX` claim per
  event id over a six-hour window and releases it if publishing fails, so a retry after a failed
  publish is not swallowed as a duplicate.
- **Rate limiting.** A Redis Lua token bucket, evaluated atomically. It fails **open**: if Redis is
  unreachable, signals are accepted. Dropping incident data to protect a rate limiter is the wrong
  trade for this system.
- **Optimistic locking** on the incident aggregate, with `PESSIMISTIC_WRITE` on the correlation path
  where two signals can race to attach to the same incident.
- **Circuit breaker** around the model API. When analysis is degraded the correct behaviour is to
  fail fast, not to queue requests behind a provider outage during a live incident.
- **Content-hashed analysis.** Regeneration is skipped when the evidence has not changed, so
  refreshing an incident page costs nothing.

### Data model

`incident` is the aggregate root. `incident_signal` holds correlated signals with their scores,
`timeline_entry` is an append-only audit log, `incident_deployment` links suspects with their
rationale. Topology is `service_node` plus `service_dependency`. Schema is versioned with Flyway;
`V2` seeds a twelve-service demo topology so the platform is useful the moment it starts.

---

## Security

- Stateless JWT with a role hierarchy: `VIEWER → RESPONDER → COMMANDER → ADMIN`. Reading is open to
  viewers, acknowledging needs a responder, resolving and declaring duplicates need a commander,
  editing the catalog needs an admin.
- Every query is tenant-scoped at the repository level. An incident from another tenant returns 404,
  not 403 — the existence of the record is not disclosed.
- Containers run non-root with a read-only root filesystem and all capabilities dropped.
  NetworkPolicy is default-deny with explicit allows.
- The dev token endpoint (`/v1/auth/token`) is `@Profile({"local","demo"})` and simply does not exist
  in the production context.

**Known scope boundary:** the web app stores its token in `localStorage`, which is appropriate for a
dev auth endpoint issuing short-lived tokens and would be replaced by an httpOnly cookie and a real
identity provider in production.

---

## Observability

Every service exports OpenTelemetry traces and Prometheus metrics. The collector applies tail-based
sampling — every error and every request over a second is kept, the rest sampled at 10%, because
traces of fast successful requests are the ones nobody opens.

Two Grafana dashboards ship provisioned:

- **Pipeline health** — throughput, correlation outcome mix, consumer lag, DLQ depth, analysis
  latency and cache hit rate.
- **Reliability outcomes** — MTTA and MTTR at p50 and p95, escalations by level, incidents by
  severity.

Prometheus rules in `deploy/observability/alert-rules.yml` watch the platform itself and fire into
its own Alertmanager webhook. Sentinel raises incidents about Sentinel.

---

## Testing

```bash
make test        # services
make test-web    # typecheck and lint
```

Tests concentrate on the logic that is genuinely hard to get right and expensive to get wrong:

- `ServiceGraphTest` — traversal with cycles, depth limits, weak-edge pruning
- `CorrelationScorerTest` — adjacency correlation, time-decay half-life, label mismatch rejection
- `FingerprintsTest` — volatile-label stripping and message normalisation
- `EscalationServiceTest` — cumulative ladder delays, severity floors, exhausted policies
- `RcaResponseParserTest` — malformed model output degrading instead of throwing
- `SignalIngestServiceTest` — replay suppression and claim release on publish failure

Behaviour is asserted, not implementation. Every test name is a sentence describing a rule.

---

## Deployment

`deploy/k8s` is a kustomize base plus a production overlay: HPAs driven by CPU with an asymmetric
scale-up policy (signal volume arrives in bursts), PodDisruptionBudgets, topology spread across
zones, and a `preStop` sleep so the load balancer deregisters before the JVM starts shutting down.

No CPU limits are set — throttling a JVM under a traffic spike is exactly the wrong behaviour for a
service whose job is to keep up during an incident. Memory limits are set.

`deploy/terraform` provisions the AWS footprint: three-AZ VPC, Multi-AZ RDS Postgres with PITR,
ElastiCache Redis with automatic failover, MSK with `min.insync.replicas=2` and unclean leader
election disabled, and EKS. State is remote with DynamoDB locking.

CI runs formatting, tests, typecheck, a Trivy scan gated on fixable HIGH/CRITICAL findings, and
manifest validation before building images.

---

## Building from source

The build needs access to Maven Central and the npm registry:

```bash
cd services && mvn verify     # Java 21
cd web && npm ci && npm run build
```

---

## Repository layout

```
services/
  platform-common/       event contracts, Kafka config, security, errors
  platform-domain/       entities, repositories, Flyway migrations
  ingest-service/        validation, idempotency, rate limiting
  correlation-service/   graph, scoring, incident creation
  incident-service/      commands, queries, SSE, escalation, analytics
  insight-service/       LLM analysis and postmortems
web/                     Next.js 14 app router, TypeScript, Tailwind
deploy/
  k8s/                   kustomize base and production overlay
  terraform/             AWS: VPC, RDS, ElastiCache, MSK, EKS
  observability/         collector, Prometheus rules, Grafana dashboards
scripts/simulate.py      cascade and storm scenario generators
```
