import Link from "next/link";
import { SeverityBadge, StatusBadge } from "./ui/Badge";
import { since } from "@/lib/format";
import type { IncidentSummary } from "@/lib/types";

export function IncidentRow({ incident, highlight }: { incident: IncidentSummary; highlight?: boolean }) {
  const open = incident.status !== "RESOLVED";

  return (
    <Link
      href={`/incidents/${incident.id}`}
      className={`group grid grid-cols-[auto_1fr] items-start gap-3 border-b border-ink-700 px-3 py-3 transition-colors last:border-b-0 hover:bg-ink-700/40 sm:grid-cols-[auto_1fr_auto] sm:items-center sm:gap-4 sm:px-4 ${
        highlight ? "bg-trace/5" : ""
      }`}
    >
      <div className="flex w-20 flex-col gap-1 sm:w-24">
        <SeverityBadge severity={incident.severity} />
        <span className="font-mono text-[10px] text-muted tabular">{incident.incidentKey}</span>
      </div>

      <div className="min-w-0">
        <p className="text-sm text-paper group-hover:text-white sm:truncate">{incident.title}</p>
        <p className="mt-1 flex flex-wrap items-center gap-x-2 gap-y-0.5 font-mono text-[10px] uppercase tracking-[0.1em] text-muted">
          <span>{incident.primaryServiceKey}</span>
          {incident.affectedServiceKeys.length > 1 && (
            <span className="text-muted/70">+{incident.affectedServiceKeys.length - 1} affected</span>
          )}
          <span>· {incident.signalCount} signals</span>
          {incident.escalationLevel > 0 && (
            <span className="text-critical">· escalated L{incident.escalationLevel}</span>
          )}
        </p>
      </div>

      <div className="col-span-2 flex items-center justify-between gap-4 sm:col-span-1 sm:justify-start">
        <StatusBadge status={incident.status} />
        <span className="w-14 text-right font-mono text-[11px] text-muted tabular">
          {open ? since(incident.detectedAt) : "closed"}
        </span>
      </div>
    </Link>
  );
}
