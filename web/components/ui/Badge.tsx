import clsx from "clsx";
import { severityClass, statusClass } from "@/lib/format";
import type { IncidentStatus, Severity } from "@/lib/types";

export function SeverityBadge({ severity }: { severity: Severity }) {
  return (
    <span
      className={clsx(
        "inline-flex items-center rounded-sm border px-1.5 py-0.5 font-mono text-[10px] uppercase tracking-[0.12em]",
        severityClass[severity],
      )}
    >
      {severity}
    </span>
  );
}

export function StatusBadge({ status }: { status: IncidentStatus }) {
  return (
    <span
      className={clsx(
        "inline-flex items-center rounded-sm border px-1.5 py-0.5 font-mono text-[10px] uppercase tracking-[0.12em]",
        statusClass[status] ?? "text-muted border-ink-600",
      )}
    >
      {status}
    </span>
  );
}
