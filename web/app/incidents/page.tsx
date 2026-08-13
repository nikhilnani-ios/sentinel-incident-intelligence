"use client";

import { useCallback, useEffect, useState } from "react";
import { IncidentRow } from "@/components/IncidentRow";
import { Empty } from "@/components/ui/Empty";
import { Panel } from "@/components/ui/Panel";
import { api } from "@/lib/api";
import { useIncidentStream } from "@/lib/useIncidentStream";
import type { IncidentStatus, IncidentSummary, Severity } from "@/lib/types";

const STATUSES: (IncidentStatus | "ALL")[] = ["ALL", "OPEN", "ACKNOWLEDGED", "MITIGATED", "RESOLVED"];
const SEVERITIES: (Severity | "ALL")[] = ["ALL", "CRITICAL", "HIGH", "MEDIUM", "LOW"];

export default function IncidentsPage() {
  const [incidents, setIncidents] = useState<IncidentSummary[]>([]);
  const [total, setTotal] = useState(0);
  const [status, setStatus] = useState<IncidentStatus | "ALL">("ALL");
  const [severity, setSeverity] = useState<Severity | "ALL">("ALL");
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const page = await api.listIncidents({
        status: status === "ALL" ? undefined : status,
        severity: severity === "ALL" ? undefined : severity,
        size: "50",
      });
      setIncidents(page.content);
      setTotal(page.totalElements);
    } finally {
      setLoading(false);
    }
  }, [status, severity]);

  useEffect(() => {
    load().catch(() => setIncidents([]));
  }, [load]);

  useIncidentStream(() => {
    load().catch(() => undefined);
  });

  return (
    <div className="space-y-4">
      <div>
        <h1 className="font-display text-2xl tracking-tightest">Incidents</h1>
        <p className="text-sm text-muted">
          {total} total · filters apply server-side so the list stays correct as new incidents arrive.
        </p>
      </div>

      <div className="flex flex-wrap gap-4">
        <FilterGroup label="Status" options={STATUSES} value={status} onChange={setStatus} />
        <FilterGroup label="Severity" options={SEVERITIES} value={severity} onChange={setSeverity} />
      </div>

      <Panel flush>
        {loading && incidents.length === 0 ? (
          <Empty message="Loading" />
        ) : incidents.length === 0 ? (
          <Empty message="No incidents match those filters" />
        ) : (
          incidents.map((incident) => <IncidentRow key={incident.id} incident={incident} />)
        )}
      </Panel>
    </div>
  );
}

function FilterGroup<T extends string>({
  label,
  options,
  value,
  onChange,
}: {
  label: string;
  options: readonly T[];
  value: T;
  onChange: (value: T) => void;
}) {
  return (
    <div className="flex items-center gap-2">
      <span className="label">{label}</span>
      <div className="flex gap-1">
        {options.map((option) => (
          <button
            key={option}
            onClick={() => onChange(option)}
            className={`rounded-sm border px-2 py-1 font-mono text-[10px] uppercase tracking-[0.1em] transition-colors ${
              value === option
                ? "border-trace/50 bg-trace/10 text-trace"
                : "border-ink-700 text-muted hover:text-paper"
            }`}
          >
            {option}
          </button>
        ))}
      </div>
    </div>
  );
}
