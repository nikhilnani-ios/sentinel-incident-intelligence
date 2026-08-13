# Architecture decisions

Short records of the choices that shaped the platform, and what each one costs. They are written
the way a reviewer would want to read them: the decision, the alternative, and why the alternative
was not taken.

---

## ADR-001 — Correlate on a weighted service graph rather than by label matching

**Decision.** Two signals correlate based on graph proximity (45%), time proximity (30%), label
overlap (15%) and severity affinity (10%). Graph traversal is best-first, maximising the product of
edge criticalities, with a depth cap and a weak-edge floor.

**Alternative considered.** Group by shared labels, which is what most alerting stacks do.

**Why not.** Label matching only correlates signals that already agree on vocabulary. The most
valuable correlation — a database alert and a checkout alert two hops apart — has no labels in
common. The graph is the thing that carries the causal relationship.

**Cost.** The graph must be maintained. A stale or missing edge means a missed correlation, which is
why catalog writes publish a Redis invalidation rather than waiting out a TTL.

---

## ADR-002 — Shared schema between correlation and incident services

**Decision.** Both services own the `incident` bounded context and deploy identical migrations.

**Alternative considered.** A database per service, with correlation publishing and incident
projecting.

**Why not.** They mutate the same aggregate within seconds of each other. Splitting the store means
either a distributed transaction or accepting that an operator can acknowledge an incident that the
correlation service does not yet believe exists. Neither is worth the isolation at this scale.

**Cost.** The services are coupled at the schema. Mitigated by both applying the same migrations, so
drift is structurally impossible rather than merely discouraged.

---

## ADR-003 — Publish incident events after commit, not through an outbox

**Decision.** Events are published in a `TransactionSynchronization` after the transaction commits.

**Alternative considered.** A transactional outbox table with a relay process.

**Why not.** The outbox is correct and it is more moving parts: a table, a relay, its own failure
modes and its own monitoring. The window this leaves open is between commit and publish, and what is
lost is a *notification* — the incident itself is durable, and the UI refetches on reconnect.

**Cost.** A crash in that window means a browser does not get a live nudge for one change. Documented
in `IncidentEventPublisher` as the natural upgrade path.

---

## ADR-004 — Fail open on rate limiting

**Decision.** If Redis is unreachable, the ingest service accepts signals rather than rejecting them.

**Why.** The rate limiter exists to protect the platform from a runaway producer. Losing incident
data during a Redis outage — which is itself likely to coincide with an incident — is a far worse
outcome than briefly accepting more load than intended.

**Cost.** A Redis outage plus a genuine flood is unprotected. Accepted knowingly; the DLQ and
consumer lag alerts cover the downstream effect.

---

## ADR-005 — LLM analysis is advisory and never on the write path

**Decision.** Analysis is requested explicitly, generated asynchronously, stored with a confidence
score and a model name, and rendered as a suggestion.

**Why.** Two failure modes to avoid. First, a slow provider must never delay incident creation —
hence never on the correlation path. Second, a confident-sounding wrong root cause during a live
outage sends responders down the wrong path, which is worse than no analysis at all. The system
prompt requires evidence for every claim and explicitly permits "unclear"; the UI shows the
confidence bar and labels weak evidence as such.

**Cost.** Responders have to click. That is the correct default for an operation that costs money and
whose output needs judgement applied to it.

---

## ADR-006 — Escalation by polling with `SKIP LOCKED`

**Decision.** A scheduled sweep claims a batch of unacknowledged incidents with
`SELECT ... FOR UPDATE SKIP LOCKED` and advances the ones that are due.

**Alternative considered.** A scheduled task per incident, or a delay queue.

**Why not.** Per-incident timers do not survive a restart and grow without bound. `SKIP LOCKED` lets
every replica sweep concurrently on disjoint batches with no coordination — no leader election, no
distributed lock, and no possibility of double-paging.

**Cost.** Escalation resolution is bounded by the poll interval: a 30-second poll means a five-minute
step fires somewhere between 5:00 and 5:30. Acceptable for paging humans.

---

## ADR-007 — Content-hash the analysis context

**Decision.** The evidence bundle is hashed, excluding anything that moves on its own (elapsed time,
wall clock). Regeneration is skipped when the hash is unchanged.

**Why.** Analysis is the only expensive operation in the platform, and an open incident's page gets
refreshed constantly. Without this, every refresh is a paid model call.

**Cost.** A subtle bug here silently serves stale analysis. Guarded by including signal counts,
timeline entry timestamps and deployment versions in the hash, so any real change invalidates it.
