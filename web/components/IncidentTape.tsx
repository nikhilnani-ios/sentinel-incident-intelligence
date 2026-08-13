"use client";

import { useMemo, useState } from "react";
import { clock, severityColor } from "@/lib/format";
import type { IncidentSignal, SuspectDeployment, TimelineEntry } from "@/lib/types";

interface Props {
  detectedAt: string;
  resolvedAt: string | null;
  signals: IncidentSignal[];
  deployments: SuspectDeployment[];
  timeline: TimelineEntry[];
}

const HEIGHT = 148;
const PADDING = { left: 16, right: 16, top: 22, bottom: 26 };
const LANE_TOP = PADDING.top;
const LANE_HEIGHT = HEIGHT - PADDING.top - PADDING.bottom;

/**
 * The strip-chart "incident tape".
 *
 * <p>This is the one view that answers the question every responder asks first: what happened, in
 * what order, and did a deployment land just before it? A table of timestamps technically contains
 * the same information, but reading causality out of a table means holding six timestamps in your
 * head. Here the deployment flag either sits to the left of the first tick or it does not.
 *
 * <p>The window deliberately starts fifteen minutes before detection. Signals that arrived before
 * the incident was opened are exactly the ones that explain it, and cropping to detection time
 * would hide them.
 */
export function IncidentTape({ detectedAt, resolvedAt, signals, deployments, timeline }: Props) {
  const [hovered, setHovered] = useState<{ x: number; label: string; sub: string } | null>(null);

  const window = useMemo(() => {
    const detected = new Date(detectedAt).getTime();
    const end = resolvedAt ? new Date(resolvedAt).getTime() : Date.now();

    const earliestSignal = signals.reduce(
      (min, signal) => Math.min(min, new Date(signal.firstSeenAt).getTime()),
      detected,
    );
    const earliestDeploy = deployments.reduce(
      (min, deployment) => Math.min(min, new Date(deployment.occurredAt).getTime()),
      detected,
    );

    const start = Math.min(earliestSignal, earliestDeploy, detected - 15 * 60_000);
    // A zero-width window would divide by zero; a one-minute floor also keeps very fast incidents
    // from rendering as a single stacked column.
    return { start, end: Math.max(end, start + 60_000) };
  }, [detectedAt, resolvedAt, signals, deployments]);

  const width = 1000;
  const innerWidth = width - PADDING.left - PADDING.right;
  const scale = (iso: string) => {
    const ratio = (new Date(iso).getTime() - window.start) / (window.end - window.start);
    return PADDING.left + Math.min(Math.max(ratio, 0), 1) * innerWidth;
  };

  const gridTicks = useMemo(() => {
    const count = 6;
    return Array.from({ length: count + 1 }, (_, index) => {
      const at = window.start + ((window.end - window.start) * index) / count;
      return { x: PADDING.left + (innerWidth * index) / count, label: clock(new Date(at).toISOString()) };
    });
  }, [window, innerWidth]);

  const detectedX = scale(detectedAt);
  const isLive = !resolvedAt;

  return (
    <div className="relative">
      <svg viewBox={`0 0 ${width} ${HEIGHT}`} className="w-full" role="img" aria-label="Incident signal tape">
        <defs>
          <linearGradient id="tape-bg" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="#131823" />
            <stop offset="100%" stopColor="#0B0E14" />
          </linearGradient>
          <linearGradient id="pre-incident" x1="0" y1="0" x2="1" y2="0">
            <stop offset="0%" stopColor="#6E9BF7" stopOpacity="0" />
            <stop offset="100%" stopColor="#6E9BF7" stopOpacity="0.08" />
          </linearGradient>
        </defs>

        <rect
          x={PADDING.left}
          y={LANE_TOP}
          width={innerWidth}
          height={LANE_HEIGHT}
          fill="url(#tape-bg)"
          stroke="#1D2433"
        />

        {/* The lead-up window, shaded to make "before detection" visually distinct. */}
        <rect
          x={PADDING.left}
          y={LANE_TOP}
          width={Math.max(detectedX - PADDING.left, 0)}
          height={LANE_HEIGHT}
          fill="url(#pre-incident)"
        />

        {gridTicks.map((tick) => (
          <g key={tick.x}>
            <line
              x1={tick.x}
              y1={LANE_TOP}
              x2={tick.x}
              y2={LANE_TOP + LANE_HEIGHT}
              stroke="#1D2433"
              strokeDasharray="2 4"
            />
            <text x={tick.x} y={HEIGHT - 8} textAnchor="middle" className="fill-muted" fontSize="9" fontFamily="IBM Plex Mono">
              {tick.label}
            </text>
          </g>
        ))}

        {/* Detection marker. */}
        <line x1={detectedX} y1={LANE_TOP - 6} x2={detectedX} y2={LANE_TOP + LANE_HEIGHT} stroke="#F2545B" strokeWidth="1.5" />
        <text x={detectedX + 4} y={LANE_TOP - 10} className="fill-critical" fontSize="9" fontFamily="IBM Plex Mono">
          DETECTED
        </text>

        {/* Signals as tick marks. Height encodes repeat count so a flapping alert reads as a block. */}
        {signals.map((signal) => {
          const x = scale(signal.firstSeenAt);
          const intensity = Math.min(signal.occurrences, 20) / 20;
          const tickHeight = 14 + intensity * (LANE_HEIGHT - 34);
          const y = LANE_TOP + LANE_HEIGHT - 10 - tickHeight;

          return (
            <rect
              key={signal.id}
              x={x - 1.5}
              y={y}
              width={3}
              height={tickHeight}
              fill={severityColor[signal.severity]}
              opacity={0.55 + signal.correlationScore * 0.45}
              onMouseEnter={() =>
                setHovered({
                  x,
                  label: `${signal.serviceKey} · ${signal.summary}`,
                  sub: `${signal.severity} · ${signal.occurrences}× · score ${signal.correlationScore.toFixed(2)}`,
                })
              }
              onMouseLeave={() => setHovered(null)}
            />
          );
        })}

        {/* Deployments as notched flags, drawn above the signals so they read as a separate class. */}
        {deployments.map((deployment) => {
          const x = scale(deployment.occurredAt);
          return (
            <g
              key={deployment.deploymentId}
              onMouseEnter={() =>
                setHovered({
                  x,
                  label: `${deployment.serviceKey} ${deployment.version}`,
                  sub: `suspicion ${(deployment.suspicionScore * 100).toFixed(0)}% · ${deployment.rationale}`,
                })
              }
              onMouseLeave={() => setHovered(null)}
            >
              <line x1={x} y1={LANE_TOP + 2} x2={x} y2={LANE_TOP + LANE_HEIGHT} stroke="#4CC38A" strokeWidth="1" opacity="0.5" />
              <path
                d={`M ${x} ${LANE_TOP + 2} L ${x + 34} ${LANE_TOP + 2} L ${x + 28} ${LANE_TOP + 9} L ${x + 34} ${LANE_TOP + 16} L ${x} ${LANE_TOP + 16} Z`}
                fill="#4CC38A"
                opacity={0.25 + deployment.suspicionScore * 0.6}
                stroke="#4CC38A"
                strokeWidth="0.75"
              />
              <text x={x + 3} y={LANE_TOP + 12} className="fill-paper" fontSize="8" fontFamily="IBM Plex Mono">
                DEPLOY
              </text>
            </g>
          );
        })}

        {/* Human actions from the timeline, as small carets under the lane. */}
        {timeline
          .filter((entry) => ["ACKNOWLEDGED", "MITIGATED", "RESOLVED", "ESCALATED"].includes(entry.kind))
          .map((entry) => {
            const x = scale(entry.occurredAt);
            return (
              <g
                key={entry.id}
                onMouseEnter={() => setHovered({ x, label: entry.kind, sub: entry.message })}
                onMouseLeave={() => setHovered(null)}
              >
                <path
                  d={`M ${x - 4} ${LANE_TOP + LANE_HEIGHT} L ${x} ${LANE_TOP + LANE_HEIGHT - 6} L ${x + 4} ${LANE_TOP + LANE_HEIGHT} Z`}
                  fill={entry.kind === "RESOLVED" ? "#4CC38A" : "#E6EAF2"}
                />
              </g>
            );
          })}

        {/* The live needle. Only drawn while the incident is open — a resolved one has an end, not a now. */}
        {isLive && (
          <g>
            <line
              x1={PADDING.left + innerWidth}
              y1={LANE_TOP - 4}
              x2={PADDING.left + innerWidth}
              y2={LANE_TOP + LANE_HEIGHT + 4}
              stroke="#E6EAF2"
              strokeWidth="1"
            />
            <circle cx={PADDING.left + innerWidth} cy={LANE_TOP - 4} r="2.5" fill="#E6EAF2" />
          </g>
        )}
      </svg>

      {hovered && (
        <div
          className="pointer-events-none absolute -top-2 z-10 max-w-xs -translate-x-1/2 rounded-sm border border-ink-600 bg-ink-900 px-2 py-1.5 shadow-lg"
          style={{ left: `${(hovered.x / width) * 100}%` }}
        >
          <p className="font-mono text-[10px] text-paper">{hovered.label}</p>
          <p className="font-mono text-[10px] text-muted">{hovered.sub}</p>
        </div>
      )}
    </div>
  );
}
