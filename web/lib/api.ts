"use client";

import { getToken } from "./auth";
import type {
  Analysis,
  AnalyticsOverview,
  IncidentDetail,
  IncidentSummary,
  Page,
  Topology,
} from "./types";

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
    readonly detail?: string,
  ) {
    super(message);
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const token = getToken();

  const response = await fetch(path, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...init?.headers,
    },
    cache: "no-store",
  });

  if (!response.ok) {
    // The API speaks problem+json; surface its detail rather than a bare status code, because
    // "incident is RESOLVED, it cannot be acknowledged" is an answer and "409" is not.
    const problem = await response.json().catch(() => null);
    throw new ApiError(
      response.status,
      problem?.title ?? `Request failed (${response.status})`,
      problem?.detail,
    );
  }

  return response.status === 204 ? (undefined as T) : ((await response.json()) as T);
}

export const api = {
  listIncidents: (params: Record<string, string | undefined>) => {
    const query = new URLSearchParams(
      Object.entries(params).filter(([, value]) => Boolean(value)) as [string, string][],
    );
    return request<Page<IncidentSummary>>(`/api/incidents?${query}`);
  },

  incident: (id: string) => request<IncidentDetail>(`/api/incidents/${id}`),

  acknowledge: (id: string, note?: string) =>
    request<IncidentSummary>(`/api/incidents/${id}/acknowledge`, {
      method: "POST",
      body: JSON.stringify({ note: note ?? null }),
    }),

  mitigate: (id: string) =>
    request<IncidentSummary>(`/api/incidents/${id}/mitigate`, { method: "POST" }),

  resolve: (id: string, resolutionSummary: string, resolutionCategory?: string) =>
    request<IncidentSummary>(`/api/incidents/${id}/resolve`, {
      method: "POST",
      body: JSON.stringify({ resolutionSummary, resolutionCategory: resolutionCategory ?? null }),
    }),

  comment: (id: string, message: string) =>
    request<IncidentSummary>(`/api/incidents/${id}/comments`, {
      method: "POST",
      body: JSON.stringify({ message }),
    }),

  analyse: (id: string, force = false) =>
    request<Analysis>(`/api/insight/${id}/analysis?force=${force}`, { method: "POST" }),

  postmortem: (id: string, force = false) =>
    request<{ headline: string; markdown: string; model: string; generatedAt: string }>(
      `/api/insight/${id}/postmortem?force=${force}`,
      { method: "POST" },
    ),

  analytics: (window: string) =>
    request<AnalyticsOverview>(`/api/analytics/overview?window=${window}`),

  topology: () => request<Topology>("/api/catalog/topology"),

  login: async (email: string, tenantId: string, role: string) => {
    const response = await fetch("/api/auth/token", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email, tenantId, role }),
    });
    if (!response.ok) throw new ApiError(response.status, "Could not issue a token");
    return (await response.json()) as { accessToken: string; role: string; tenantId: string };
  },
};
