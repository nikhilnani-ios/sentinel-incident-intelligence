"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { IncidentRow } from "@/components/IncidentRow";
import { IncidentTrendChart } from "@/components/IncidentTrendChart";
import { MetricTiles } from "@/components/MetricTiles";
import { Empty } from "@/components/ui/Empty";
import { Panel } from "@/components/ui/Panel";
import { api } from "@/lib/api";
import { duration } from "@/lib/format";
import { useIncidentStream } from "@/lib/useIncidentStream";
import type { AnalyticsOverview, IncidentSummary } from "@/lib/types";

const WINDOWS = ["LAST_24_HOURS", "LAST_7_DAYS", "LAST_30_DAYS"] as const;

export default function OverviewPage() {
  const [overview, setOverview] = useState<AnalyticsOverview | null>(null);
  const [open, setOpen] = useState<IncidentSummary[]>([]);
  const [window, setWindow] = useState<(typeof WINDOWS)[number]>("LAST_7_DAYS");
  const [flashed, setFlashed] = useState<Set<string>>(new Set());

  const loadOpenIncidents = useCallback(async () => {
    const page = await api.listIncidents({ status: "OPEN", size: "8" });
    setOpen(page.content);
  }, []);

  useEffect(() => {
    loadOpenIncidents().catch(() => setOpen([]));
  }, [loadOpenIncidents]);

  useEffect(() => {
    api.analytics(window).then(setOverview).catch(() => setOverview(null));
  }, [window]);

  // A live event means the list is stale. Refetching is simpler and more correct than patching the
  // local array — the server already knows the ordering rules, and this happens a few times a
  // minute at worst.
  useIncidentStream((event) => {
    setFlashed((previous) => new Set(previous).add(event.incidentId));
    loadOpenIncidents().catch(() => undefined);
    setTimeout(() => {
      setFlashed((previous) => {
        const next = new Set(previous);
        next.delete(event.incidentId);
        return next;
      });
    }, 4000);
  });

  const worstServices = (overview?.byService ?? []).slice(0, 6);

  return (
    <div className="space-y-4">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <h1 className="font-display text-2xl tracking-tightest">Reliability overview</h1>
          <p className="text-sm text-muted">Everything currently burning, and how the last few weeks have gone.</p>
        </div>
        <div className="flex max-w-full gap-1 overflow-x-auto pb-1 sm:pb-0">
          {WINDOWS.map((option) => (
            <button
              key={option}
              onClick={() => setWindow(option)}
              className={`shrink-0 rounded-sm border px-2.5 py-2 font-mono text-[10px] uppercase tracking-[0.12em] transition-colors ${
                window === option
                  ? "border-trace/50 bg-trace/10 text-trace"
                  : "border-ink-700 text-muted hover:text-paper"
              }`}
            >
              {option.replace("LAST_", "").replace("_", " ").toLowerCase()}
            </button>
          ))}
        </div>
      </div>

      <MetricTiles overview={overview} openCount={open.length} />

      <div className="grid gap-4 lg:grid-cols-[1.35fr_1fr]">
        <Panel
          title="Open incidents"
          flush
          actions={
            <Link href="/incidents" className="font-mono text-[10px] uppercase tracking-[0.12em] text-muted hover:text-paper">
              All incidents →
            </Link>
          }
        >
          {open.length === 0 ? (
            <Empty message="Nothing open" hint="Every incident is acknowledged or resolved." />
          ) : (
            open.map((incident) => (
              <IncidentRow key={incident.id} incident={incident} highlight={flashed.has(incident.id)} />
            ))
          )}
        </Panel>

        <Panel title="Frequency and time to resolve" className="min-w-0">
          {overview && overview.overTime.length > 0 ? (
            <IncidentTrendChart buckets={overview.overTime} />
          ) : (
            <Empty message="No data in this window" />
          )}
        </Panel>
      </div>

      <Panel title="Where the pages come from" flush>
        {worstServices.length === 0 ? (
          <Empty message="No incidents recorded in this window" />
        ) : (
          <div className="overflow-x-auto">
          <table className="min-w-[620px] w-full">
            <thead>
              <tr className="border-b border-ink-700">
                {["Service", "Incidents", "Critical incidents", "MTTA", "MTTR"].map((heading, index) => (
                  <th
                    key={heading}
                    className={`label px-4 py-2 font-normal ${index === 0 ? "text-left" : "text-right"}`}
                  >
                    {heading}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {worstServices.map((row) => (
                <tr key={row.label} className="border-b border-ink-700 last:border-b-0">
                  <td className="px-4 py-2.5 font-mono text-xs text-paper">{row.label}</td>
                  <td className="px-4 py-2.5 text-right font-mono text-xs text-paper tabular">{row.incidentCount}</td>
                  <td className="px-4 py-2.5 text-right font-mono text-xs tabular">
                    <span className={row.criticalCount > 0 ? "text-critical" : "text-muted"}>{row.criticalCount}</span>
                  </td>
                  <td className="px-4 py-2.5 text-right font-mono text-xs text-muted tabular">
                    {duration(row.meanTimeToAcknowledgeSeconds)}
                  </td>
                  <td className="px-4 py-2.5 text-right font-mono text-xs text-muted tabular">
                    {duration(row.meanTimeToResolveSeconds)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          </div>
        )}
      </Panel>
    </div>
  );
}
