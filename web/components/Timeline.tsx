import { clock } from "@/lib/format";
import type { TimelineEntry } from "@/lib/types";

const KIND_COLOR: Record<string, string> = {
  DETECTED: "bg-critical",
  SIGNAL_CORRELATED: "bg-ink-600",
  SEVERITY_CHANGED: "bg-high",
  DEPLOYMENT_LINKED: "bg-ok",
  ACKNOWLEDGED: "bg-high",
  ESCALATED: "bg-critical",
  COMMENT: "bg-trace",
  ANALYSIS_GENERATED: "bg-trace",
  MITIGATED: "bg-low",
  RESOLVED: "bg-ok",
};

export function Timeline({ entries }: { entries: TimelineEntry[] }) {
  return (
    <ol className="relative space-y-0">
      <span className="absolute left-[5px] top-2 bottom-2 w-px bg-ink-700" aria-hidden />
      {entries.map((entry) => (
        <li key={entry.id} className="relative flex gap-3 py-2 pl-5">
          <span
            className={`absolute left-0 top-3.5 h-[11px] w-[11px] rounded-full border-2 border-ink-900 ${
              KIND_COLOR[entry.kind] ?? "bg-ink-600"
            }`}
            aria-hidden
          />
          <time className="w-16 shrink-0 font-mono text-[11px] text-muted tabular">{clock(entry.occurredAt)}</time>
          <div className="min-w-0 flex-1">
            <p className="text-sm text-paper">{entry.message}</p>
            <p className="font-mono text-[10px] uppercase tracking-[0.1em] text-muted">
              {entry.kind.replace(/_/g, " ")} · {entry.actor}
            </p>
          </div>
        </li>
      ))}
    </ol>
  );
}
