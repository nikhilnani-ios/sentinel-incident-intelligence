"use client";

import type { Analysis, AnalyticsOverview, IncidentDetail, IncidentStatus, Page, Topology } from "./types";

const STORAGE_KEY = "sentinel.showcase.incidents.v1";
export const showcaseEnabled = process.env.NEXT_PUBLIC_SHOWCASE_MODE === "true";

const analysis: Analysis = {
  headline: "Payment gateway deployment likely triggered checkout failures",
  summary:
    "Errors began on payment-gateway shortly after payment-gateway v2.4.1. Failures then propagated to checkout-api and edge-gateway in dependency order, making the recent deployment the strongest shared explanation.",
  confidence: 0.84,
  hypotheses: [
    {
      cause: "Regression introduced by payment-gateway v2.4.1",
      reasoning:
        "The deployment precedes detection, the first critical signals originate at payment-gateway, and upstream checkout failures follow one minute later.",
      likelihood: 0.84,
      nextStep: "Compare v2.4.1 with the prior release and roll back while watching payment error rate",
    },
    {
      cause: "Database connection-pool exhaustion",
      reasoning:
        "ConnectionPoolTimeout errors support resource saturation, but do not establish whether it is a cause or a symptom.",
      likelihood: 0.46,
      nextStep: "Check pool wait time and orders-postgres saturation around detection",
    },
  ],
  immediateActions: [
    "Roll back payment-gateway v2.4.1 or shift traffic to the previous healthy version",
    "Track payment 5xx rate and checkout success rate during recovery",
    "Inspect connection-pool saturation before closing the incident",
  ],
  model: "sentinel-demo-v1",
  cached: true,
  generatedAt: "2026-08-13T01:32:00Z",
};

function fixtures(): IncidentDetail[] {
  const detected = "2026-08-13T01:26:00Z";
  const signal = (
    id: string,
    type: string,
    serviceKey: string,
    severity: "CRITICAL" | "HIGH",
    summary: string,
    minute: number,
    score: number,
    occurrences = 1,
  ) => ({
    id,
    type,
    serviceKey,
    severity,
    summary,
    correlationScore: score,
    occurrences,
    firstSeenAt: `2026-08-13T01:${minute}:00Z`,
    lastSeenAt: `2026-08-13T01:${minute}:20Z`,
    labels: { env: "production", region: "us-east-2" },
    detail: {},
  });

  const open: IncidentDetail = {
    summary: {
      id: "demo-incident-1001",
      incidentKey: "INC-1001",
      title: "payment-gateway: HighErrorRate",
      status: "OPEN",
      severity: "CRITICAL",
      primaryServiceKey: "payment-gateway",
      affectedServiceKeys: ["payment-gateway", "checkout-api", "edge-gateway"],
      detectedAt: detected,
      acknowledgedAt: null,
      resolvedAt: null,
      escalationLevel: 1,
      signalCount: 9,
      timeToAcknowledgeSeconds: null,
      timeToResolveSeconds: null,
    },
    description: "Checkout failures correlated across the payment path.",
    acknowledgedBy: null,
    resolvedBy: null,
    escalationPolicyKey: "default-critical",
    signals: [
      signal("s1", "ALERT", "payment-gateway", "CRITICAL", "CRITICAL alert 'HighErrorRate' firing", 26, 1),
      signal("s2", "METRIC", "payment-gateway", "CRITICAL", "http_server_error_ratio=0.110 breached 0.020", 26, 0.89),
      signal("s3", "METRIC", "payment-gateway", "HIGH", "http_request_duration_p99=4.800s breached 1.000s", 26, 0.89),
      signal("s4", "LOG", "payment-gateway", "CRITICAL", "ConnectionPoolTimeout after 5000ms", 26, 0.84, 48),
      signal("s5", "ALERT", "checkout-api", "CRITICAL", "CRITICAL alert 'CheckoutFailureRate' firing", 27, 0.9),
      signal("s6", "ALERT", "checkout-api", "HIGH", "HIGH alert 'UpstreamLatency' firing", 27, 0.94),
      signal("s7", "LOG", "checkout-api", "HIGH", "PaymentGatewayException: upstream returned 503", 27, 0.8, 132),
      signal("s8", "ALERT", "edge-gateway", "HIGH", "HIGH alert 'ElevatedErrorRate' firing", 28, 0.9),
      signal("s9", "METRIC", "edge-gateway", "HIGH", "http_server_error_ratio=0.037 breached 0.030", 28, 0.82),
    ],
    timeline: [
      { id: "t0", kind: "DEPLOYMENT_LINKED", message: "payment-gateway v2.4.1 deployed 2 min before detection", actor: "deployment-correlator", occurredAt: "2026-08-13T01:24:00Z", metadata: {} },
      { id: "t1", kind: "DETECTED", message: "Incident opened from CRITICAL alert 'HighErrorRate'", actor: "correlation-engine", occurredAt: detected, metadata: {} },
      { id: "t2", kind: "SIGNAL_CORRELATED", message: "Checkout failures attached from checkout-api", actor: "correlation-engine", occurredAt: "2026-08-13T01:27:00Z", metadata: {} },
      { id: "t3", kind: "ESCALATED", message: "Escalated to primary-oncall after 5 min", actor: "escalation-policy", occurredAt: "2026-08-13T01:31:00Z", metadata: {} },
    ],
    suspectDeployments: [{
      deploymentId: "deploy-demo-1",
      serviceKey: "payment-gateway",
      version: "v2.4.1",
      commitSha: "fe3e74b2c0ffee1234567890",
      environment: "production",
      deployedBy: "ci-bot",
      changelogUrl: "https://github.com/acme/payment-gateway/compare/v2.4.0...v2.4.1",
      occurredAt: "2026-08-13T01:24:00Z",
      suspicionScore: 0.84,
      rationale: "Deployed 2 min before detection to an affected service",
    }],
    dependencyGraph: graph(),
    rootCauseAnalysis: {
      headline: analysis.headline,
      body: analysis.summary,
      confidence: analysis.confidence,
      model: analysis.model,
      hypotheses: analysis.hypotheses,
      generatedAt: analysis.generatedAt,
    },
  };

  const resolved = structuredClone(open);
  resolved.summary = {
    ...open.summary,
    id: "demo-incident-1000",
    incidentKey: "INC-1000",
    status: "RESOLVED",
    detectedAt: "2026-08-12T18:36:00Z",
    acknowledgedAt: "2026-08-12T18:43:00Z",
    resolvedAt: "2026-08-12T18:52:00Z",
    escalationLevel: 1,
    timeToAcknowledgeSeconds: 420,
    timeToResolveSeconds: 960,
  };
  resolved.acknowledgedBy = "sre@acme.io";
  resolved.resolvedBy = "commander@acme.io";
  return [open, resolved];
}

function graph() {
  return [
    { serviceKey: "edge-gateway", displayName: "Edge Gateway", tier: "TIER_1", affected: true, isPrimary: false, impactWeight: 0.9, dependsOn: ["checkout-api"] },
    { serviceKey: "checkout-api", displayName: "Checkout API", tier: "TIER_1", affected: true, isPrimary: false, impactWeight: 0.95, dependsOn: ["payment-gateway", "cart-service"] },
    { serviceKey: "payment-gateway", displayName: "Payment Gateway", tier: "TIER_1", affected: true, isPrimary: true, impactWeight: 1, dependsOn: ["orders-postgres", "stripe"] },
    { serviceKey: "cart-service", displayName: "Cart Service", tier: "TIER_1", affected: false, isPrimary: false, impactWeight: 0.4, dependsOn: [] },
    { serviceKey: "orders-postgres", displayName: "Orders Postgres", tier: "TIER_1", affected: false, isPrimary: false, impactWeight: 0.7, dependsOn: [] },
    { serviceKey: "stripe", displayName: "Stripe", tier: "TIER_1", affected: false, isPrimary: false, impactWeight: 0.6, dependsOn: [] },
  ];
}

function read(): IncidentDetail[] {
  const raw = window.localStorage.getItem(STORAGE_KEY);
  if (raw) return JSON.parse(raw) as IncidentDetail[];
  const value = fixtures();
  write(value);
  return value;
}

function write(value: IncidentDetail[]) {
  window.localStorage.setItem(STORAGE_KEY, JSON.stringify(value));
}

export function listShowcase(params: Record<string, string | undefined>): Page<IncidentDetail["summary"]> {
  const filtered = read()
    .map((item) => item.summary)
    .filter((item) => !params.status || item.status === params.status)
    .filter((item) => !params.severity || item.severity === params.severity);
  return { content: filtered, totalElements: filtered.length, number: 0, size: filtered.length };
}

export function showcaseIncident(id: string) {
  const result = read().find((item) => item.summary.id === id);
  if (!result) throw new Error("Showcase incident not found");
  return result;
}

export function transitionShowcase(id: string, status: IncidentStatus, note?: string) {
  const incidents = read();
  const incident = incidents.find((item) => item.summary.id === id);
  if (!incident) throw new Error("Showcase incident not found");
  const now = new Date().toISOString();
  incident.summary.status = status;
  if (status === "ACKNOWLEDGED") {
    incident.summary.acknowledgedAt = now;
    incident.acknowledgedBy = "portfolio-visitor";
  }
  if (status === "RESOLVED") {
    incident.summary.resolvedAt = now;
    incident.resolvedBy = "portfolio-visitor";
  }
  incident.timeline.push({ id: `demo-${Date.now()}`, kind: status, message: note || `Incident marked ${status.toLowerCase()}`, actor: "portfolio-visitor", occurredAt: now, metadata: {} });
  write(incidents);
  return incident.summary;
}

export function showcaseAnalysis() { return analysis; }

export function showcasePostmortem() {
  return `## Summary\nA payment-gateway regression propagated through checkout-api to edge-gateway.\n\n## Customer impact\nCustomers experienced failed or delayed checkout attempts.\n\n## Timeline\n- v2.4.1 deployed shortly before detection.\n- Payment and checkout alerts correlated into one incident.\n- The suspect change was rolled back.\n\n## Contributing factors\n- The release reached production before checkout-path regression detection.\n\n## What went well\n- Dependency-aware correlation grouped nine signals into one incident.\n\n## What could have gone better\n- A payment canary could have reduced recovery time.\n\n## Action items\n- Payments: add a checkout success-rate canary.\n- Platform: automate rollback on exhausted canary error budgets.`;
}

export function showcaseAnalytics(windowName: string): AnalyticsOverview {
  const incidents = read();
  const resolved = incidents.filter((item) => item.summary.status === "RESOLVED");
  return {
    window: windowName, from: "2026-08-06T00:00:00Z", to: "2026-08-13T23:59:59Z",
    totalIncidents: incidents.length, criticalIncidents: incidents.length,
    meanTimeToAcknowledgeSeconds: 420, meanTimeToResolveSeconds: 960,
    resolutionPercentiles: { p50Seconds: 960, p90Seconds: 960 },
    overTime: [
      { label: "2026-08-12", incidentCount: 1, criticalCount: 1, meanTimeToAcknowledgeSeconds: 420, meanTimeToResolveSeconds: 960 },
      { label: "2026-08-13", incidentCount: incidents.length - 1, criticalCount: incidents.length - 1, meanTimeToAcknowledgeSeconds: resolved.length ? 420 : null, meanTimeToResolveSeconds: resolved.length ? 960 : null },
    ],
    byService: [{ label: "payment-gateway", incidentCount: incidents.length, criticalCount: incidents.length, meanTimeToAcknowledgeSeconds: 420, meanTimeToResolveSeconds: 960 }],
  };
}

export const showcaseTopology: Topology = {
  nodes: [
    ["edge-gateway", "Edge Gateway", "TIER_1", "platform"], ["checkout-api", "Checkout API", "TIER_1", "payments"], ["payment-gateway", "Payment Gateway", "TIER_1", "payments"], ["cart-service", "Cart Service", "TIER_1", "commerce"], ["orders-postgres", "Orders Postgres", "TIER_1", "platform"], ["stripe", "Stripe (external)", "TIER_1", "payments"], ["inventory-api", "Inventory API", "TIER_2", "commerce"], ["search-api", "Search API", "TIER_2", "discovery"], ["notification-api", "Notification API", "TIER_3", "growth"],
  ].map(([serviceKey, displayName, tier, ownerTeam]) => ({ serviceKey, displayName, tier, ownerTeam, runbookUrl: null, dependentCount: 1 })),
  edges: [
    { source: "edge-gateway", target: "checkout-api", kind: "SYNC", criticality: 0.95 },
    { source: "checkout-api", target: "payment-gateway", kind: "SYNC", criticality: 0.95 },
    { source: "checkout-api", target: "cart-service", kind: "SYNC", criticality: 0.9 },
    { source: "payment-gateway", target: "orders-postgres", kind: "DATASTORE", criticality: 0.95 },
    { source: "payment-gateway", target: "stripe", kind: "SYNC", criticality: 0.9 },
  ],
};
