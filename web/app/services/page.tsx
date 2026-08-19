"use client";

import { useEffect, useMemo, useState } from "react";
import { Empty } from "@/components/ui/Empty";
import { Panel } from "@/components/ui/Panel";
import { api } from "@/lib/api";
import type { Topology } from "@/lib/types";

const TIER_LABEL: Record<string, string> = {
  TIER_1: "Tier 1 · customer facing",
  TIER_2: "Tier 2 · supporting",
  TIER_3: "Tier 3 · internal",
};

export default function ServicesPage() {
  const [topology, setTopology] = useState<Topology | null>(null);
  const [selected, setSelected] = useState<string | null>(null);

  useEffect(() => {
    api.topology().then(setTopology).catch(() => setTopology(null));
  }, []);

  const neighbours = useMemo(() => {
    if (!topology || !selected) return { upstream: [], downstream: [] };
    return {
      upstream: topology.edges.filter((edge) => edge.target === selected),
      downstream: topology.edges.filter((edge) => edge.source === selected),
    };
  }, [topology, selected]);

  if (!topology) return <Empty message="Loading catalog" />;

  const byTier = ["TIER_1", "TIER_2", "TIER_3"].map((tier) => ({
    tier,
    nodes: topology.nodes.filter((node) => node.tier === tier),
  }));

  return (
    <div className="space-y-4">
      <div>
        <h1 className="font-display text-2xl tracking-tightest">Service catalog</h1>
        <p className="text-sm text-muted">
          {topology.nodes.length} services, {topology.edges.length} dependencies. Correlation walks these
          edges to decide whether two alerts belong to the same incident.
        </p>
      </div>

      <div className="grid gap-4 lg:grid-cols-[1.4fr_1fr]">
        <div className="space-y-4">
          {byTier.map(
            (group) =>
              group.nodes.length > 0 && (
                <Panel key={group.tier} title={TIER_LABEL[group.tier]} flush>
                  <div className="grid grid-cols-1 gap-px bg-ink-700 sm:grid-cols-2 md:grid-cols-3">
                    {group.nodes.map((node) => (
                      <button
                        key={node.serviceKey}
                        onClick={() => setSelected(node.serviceKey)}
                        className={`bg-ink-800 px-3 py-2.5 text-left transition-colors hover:bg-ink-700 ${
                          selected === node.serviceKey ? "bg-trace/10" : ""
                        }`}
                      >
                        <p className="font-mono text-xs text-paper">{node.serviceKey}</p>
                        <p className="truncate text-[11px] text-muted">{node.displayName}</p>
                        <p className="mt-1 font-mono text-[10px] text-muted/70">
                          {node.dependentCount} dependents
                          {node.ownerTeam && ` · ${node.ownerTeam}`}
                        </p>
                      </button>
                    ))}
                  </div>
                </Panel>
              ),
          )}
        </div>

        <Panel title={selected ? `${selected} · connections` : "Select a service"}>
          {!selected ? (
            <Empty
              message="Nothing selected"
              hint="Pick a service to see what calls it and what it depends on."
            />
          ) : (
            <div className="space-y-5">
              <EdgeList
                heading="Called by"
                empty="Nothing calls this service"
                edges={neighbours.upstream.map((edge) => ({ key: edge.source, ...edge }))}
              />
              <EdgeList
                heading="Depends on"
                empty="No downstream dependencies"
                edges={neighbours.downstream.map((edge) => ({ key: edge.target, ...edge }))}
              />
            </div>
          )}
        </Panel>
      </div>
    </div>
  );
}

function EdgeList({
  heading,
  empty,
  edges,
}: {
  heading: string;
  empty: string;
  edges: { key: string; kind: string; criticality: number }[];
}) {
  return (
    <div>
      <p className="label mb-2">{heading}</p>
      {edges.length === 0 ? (
        <p className="text-xs text-muted">{empty}</p>
      ) : (
        <ul className="space-y-1.5">
          {edges.map((edge) => (
            <li key={`${heading}-${edge.key}`} className="grid grid-cols-[minmax(0,1fr)_auto] gap-x-3 gap-y-1 sm:flex sm:items-center">
              <span className="min-w-0 truncate font-mono text-xs text-paper sm:w-36 sm:shrink-0">{edge.key}</span>
              <span className="col-span-2 row-start-2 h-1 overflow-hidden rounded-full bg-ink-700 sm:order-none sm:col-span-1 sm:flex-1">
                <span
                  className="block h-full rounded-full bg-trace/70"
                  style={{ width: `${edge.criticality * 100}%` }}
                />
              </span>
              <span className="col-start-2 row-start-1 w-20 shrink-0 text-right font-mono text-[10px] uppercase tracking-[0.1em] text-muted">
                {edge.kind}
              </span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
