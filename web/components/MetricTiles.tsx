import { duration } from "@/lib/format";
import type { AnalyticsOverview } from "@/lib/types";

export function MetricTiles({ overview, openCount }: { overview: AnalyticsOverview | null; openCount: number }) {
  const tiles = [
    { label: "Open now", value: String(openCount), tone: openCount > 0 ? "text-critical" : "text-ok" },
    { label: "Incidents", value: overview ? String(overview.totalIncidents) : "—", tone: "text-paper" },
    {
      label: "MTTA",
      value: duration(overview?.meanTimeToAcknowledgeSeconds),
      tone: "text-paper",
      hint: "mean time to acknowledge",
    },
    {
      label: "MTTR",
      value: duration(overview?.meanTimeToResolveSeconds),
      tone: "text-paper",
      hint: "mean time to resolve",
    },
    {
      label: "p90 resolve",
      value: duration(overview?.resolutionPercentiles.p90Seconds),
      tone: "text-paper",
      hint: "the tail is where the pain is",
    },
  ];

  return (
    <div className="grid grid-cols-2 gap-3 md:grid-cols-5">
      {tiles.map((tile) => (
        <div key={tile.label} className="panel px-4 py-3">
          <p className="label">{tile.label}</p>
          <p className={`stat mt-1 ${tile.tone}`}>{tile.value}</p>
          {tile.hint && <p className="mt-0.5 text-[10px] text-muted/70">{tile.hint}</p>}
        </div>
      ))}
    </div>
  );
}
