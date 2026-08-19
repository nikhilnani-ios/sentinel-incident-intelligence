"use client";

import { useMemo } from "react";
import type { GraphNode } from "@/lib/types";

/**
 * The slice of the service graph around an incident.
 *
 * <p>Laid out by dependency depth rather than by a force simulation: callers on the left, their
 * dependencies to the right. A force layout looks impressive in a screenshot and is useless when you
 * need to answer "what is downstream of the thing that broke" in two seconds — the answer should
 * always be "to the right".
 */
export function DependencyGraph({ nodes }: { nodes: GraphNode[] }) {
  const layout = useMemo(() => {
    const byKey = new Map(nodes.map((node) => [node.serviceKey, node]));

    // Depth = longest path from a node with no visible caller, computed iteratively so a cyclic
    // topology (which exists in real systems) terminates instead of recursing forever.
    const depth = new Map<string, number>(nodes.map((node) => [node.serviceKey, 0]));
    for (let pass = 0; pass < nodes.length; pass += 1) {
      let changed = false;
      for (const node of nodes) {
        for (const target of node.dependsOn) {
          if (!byKey.has(target)) continue;
          const candidate = (depth.get(node.serviceKey) ?? 0) + 1;
          if (candidate > (depth.get(target) ?? 0)) {
            depth.set(target, candidate);
            changed = true;
          }
        }
      }
      if (!changed) break;
    }

    const columns = new Map<number, GraphNode[]>();
    for (const node of nodes) {
      const level = depth.get(node.serviceKey) ?? 0;
      columns.set(level, [...(columns.get(level) ?? []), node]);
    }

    const columnWidth = 190;
    const rowHeight = 62;
    const positions = new Map<string, { x: number; y: number }>();

    [...columns.entries()]
      .sort(([a], [b]) => a - b)
      .forEach(([level, columnNodes]) => {
        columnNodes.forEach((node, index) => {
          positions.set(node.serviceKey, {
            x: 20 + level * columnWidth,
            y: 24 + index * rowHeight,
          });
        });
      });

    const width = 40 + Math.max(columns.size, 1) * columnWidth;
    const height =
      48 + Math.max(...[...columns.values()].map((column) => column.length), 1) * rowHeight;

    return { positions, width, height };
  }, [nodes]);

  if (nodes.length === 0) {
    return <p className="py-8 text-center font-mono text-xs text-muted">No topology recorded</p>;
  }

  const nodeWidth = 148;
  const nodeHeight = 38;

  return (
    <div className="max-w-full overflow-x-auto pb-2">
    <svg
      viewBox={`0 0 ${layout.width} ${layout.height}`}
      className="min-w-[520px] w-full"
      role="img"
      aria-label="Service dependency graph"
    >
      <defs>
        <marker id="arrow" viewBox="0 0 8 8" refX="7" refY="4" markerWidth="6" markerHeight="6" orient="auto">
          <path d="M 0 0 L 8 4 L 0 8 z" fill="#2A3347" />
        </marker>
      </defs>

      {nodes.flatMap((node) =>
        node.dependsOn.map((target) => {
          const from = layout.positions.get(node.serviceKey);
          const to = layout.positions.get(target);
          if (!from || !to) return null;

          const x1 = from.x + nodeWidth;
          const y1 = from.y + nodeHeight / 2;
          const x2 = to.x;
          const y2 = to.y + nodeHeight / 2;
          const midX = (x1 + x2) / 2;

          return (
            <path
              key={`${node.serviceKey}->${target}`}
              d={`M ${x1} ${y1} C ${midX} ${y1}, ${midX} ${y2}, ${x2} ${y2}`}
              fill="none"
              stroke="#2A3347"
              strokeWidth="1"
              markerEnd="url(#arrow)"
            />
          );
        }),
      )}

      {nodes.map((node) => {
        const position = layout.positions.get(node.serviceKey);
        if (!position) return null;

        const stroke = node.isPrimary ? "#F2545B" : node.affected ? "#F2A65A" : "#2A3347";
        const fill = node.isPrimary ? "rgba(242,84,91,0.12)" : node.affected ? "rgba(242,166,90,0.08)" : "#131823";

        return (
          <g key={node.serviceKey}>
            <rect
              x={position.x}
              y={position.y}
              width={nodeWidth}
              height={nodeHeight}
              rx="3"
              fill={fill}
              stroke={stroke}
              strokeWidth={node.isPrimary ? 1.5 : 1}
            />
            <text
              x={position.x + 10}
              y={position.y + 16}
              className="fill-paper"
              fontSize="11"
              fontFamily="IBM Plex Mono"
            >
              {node.serviceKey.length > 18 ? `${node.serviceKey.slice(0, 17)}…` : node.serviceKey}
            </text>
            <text
              x={position.x + 10}
              y={position.y + 29}
              className="fill-muted"
              fontSize="8.5"
              fontFamily="IBM Plex Mono"
            >
              {node.tier.replace("_", " ")}
            </text>
            {node.isPrimary && <circle cx={position.x + nodeWidth - 12} cy={position.y + 12} r="3" fill="#F2545B" />}
          </g>
        );
      })}
    </svg>
    </div>
  );
}
