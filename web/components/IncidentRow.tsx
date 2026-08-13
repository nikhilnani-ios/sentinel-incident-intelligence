import Link from "next/link";
import { SeverityBadge, StatusBadge } from "./ui/Badge";
import { since } from "@/lib/format";
import type { IncidentSummary } from "@/lib/types";

export function IncidentRow({ incident, highlight }: { incident: IncidentSummary; highlight?: boolean }) {
  const open = incident.status !== "RESOLVED";

  return (
    <Link
      href={`/incidents/${incident.id}`}
      className={`group grid grid-cols-[auto_1fr_auto] items-center gap-4 border-b border-ink-700 px-4 py-3 transition-colors last:border-b-0 hover:bg-ink-700/40 ${
        highlight ? "bg-trace/5" : ""
      }`}
    >
      <div className="flex w-24 flex-col gap-1">
        <SeverityBadge severity={incident.severity} />
        <span className="font-mono text-[10px] text-muted tabular">{incident.incidentKey}</span>
      </div>

      <div className="min-w-0">
        <p className="truncate text-sm text-paper group-hover:text-white">{incident.title}</p>
        <p className="mt-0.5 flex items-center gap-2 font-mono text-[10px] uppercase tracking-[0.1em] text-muted">
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

      <div className="flex items-center gap-4">
        <StatusBadge status={incident.status} />
        <span className="w-14 text-right font-mono text-[11px] text-muted tabular">
          {open ? since(incident.detectedAt) : "closed"}
        </span>
      </div>
    </Link>
  );
}
