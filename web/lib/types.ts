export type Severity = "CRITICAL" | "HIGH" | "MEDIUM" | "LOW" | "INFO";
export type IncidentStatus = "OPEN" | "ACKNOWLEDGED" | "MITIGATED" | "RESOLVED";
export type Role = "VIEWER" | "RESPONDER" | "COMMANDER" | "ADMIN";

export interface IncidentSummary {
  id: string;
  incidentKey: string;
  title: string;
  status: IncidentStatus;
  severity: Severity;
  primaryServiceKey: string;
  affectedServiceKeys: string[];
  detectedAt: string;
  acknowledgedAt: string | null;
  resolvedAt: string | null;
  escalationLevel: number;
  signalCount: number;
  timeToAcknowledgeSeconds: number | null;
  timeToResolveSeconds: number | null;
}

export interface IncidentSignal {
  id: string;
  type: string;
  serviceKey: string;
  severity: Severity;
  summary: string;
  correlationScore: number;
  occurrences: number;
  firstSeenAt: string;
  lastSeenAt: string;
  labels: Record<string, string>;
  detail: Record<string, unknown>;
}

export interface TimelineEntry {
  id: string;
  kind: string;
  message: string;
  actor: string;
  occurredAt: string;
  metadata: Record<string, unknown>;
}

export interface SuspectDeployment {
  deploymentId: string;
  serviceKey: string;
  version: string;
  commitSha: string | null;
  environment: string;
  deployedBy: string | null;
  changelogUrl: string | null;
  occurredAt: string;
  suspicionScore: number;
  rationale: string;
}

export interface GraphNode {
  serviceKey: string;
  displayName: string;
  tier: string;
  affected: boolean;
  isPrimary: boolean;
  impactWeight: number;
  dependsOn: string[];
}

export interface Hypothesis {
  cause: string;
  reasoning: string;
  likelihood: number;
  nextStep: string;
}

/** Returned by the insight service's analysis endpoints. */
export interface Analysis {
  headline: string;
  summary: string;
  confidence: number;
  hypotheses: Hypothesis[];
  immediateActions: string[];
  model: string;
  cached: boolean;
  generatedAt: string;
}

/**
 * The flattened form the incident detail response embeds. It carries the stored body and
 * hypotheses as they were persisted, without the immediate-actions list.
 */
export interface EmbeddedInsight {
  headline: string;
  body: string;
  confidence: number;
  model: string;
  hypotheses: Hypothesis[];
  generatedAt: string;
}

export interface IncidentDetail {
  summary: IncidentSummary;
  description: string | null;
  acknowledgedBy: string | null;
  resolvedBy: string | null;
  escalationPolicyKey: string | null;
  signals: IncidentSignal[];
  timeline: TimelineEntry[];
  suspectDeployments: SuspectDeployment[];
  dependencyGraph: GraphNode[];
  rootCauseAnalysis: EmbeddedInsight | null;
}

export interface Page<T> {
  content: T[];
  totalElements: number;
  number: number;
  size: number;
}

export interface AnalyticsBucket {
  label: string;
  incidentCount: number;
  meanTimeToAcknowledgeSeconds: number | null;
  meanTimeToResolveSeconds: number | null;
  criticalCount: number;
}

export interface AnalyticsOverview {
  window: string;
  from: string;
  to: string;
  totalIncidents: number;
  criticalIncidents: number;
  meanTimeToAcknowledgeSeconds: number | null;
  meanTimeToResolveSeconds: number | null;
  resolutionPercentiles: { p50Seconds: number | null; p90Seconds: number | null };
  overTime: AnalyticsBucket[];
  byService: AnalyticsBucket[];
}

export interface Topology {
  nodes: {
    serviceKey: string;
    displayName: string;
    tier: string;
    ownerTeam: string | null;
    runbookUrl: string | null;
    dependentCount: number;
  }[];
  edges: { source: string; target: string; kind: string; criticality: number }[];
}

export interface IncidentEvent {
  incidentId: string;
  tenantId: string;
  change: string;
  status: IncidentStatus;
  severity: Severity;
  title: string;
  primaryServiceKey: string;
  affectedServiceKeys: string[];
  actor: string;
  occurredAt: string;
}
