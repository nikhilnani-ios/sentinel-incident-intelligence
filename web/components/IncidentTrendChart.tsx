"use client";

import type { AnalyticsBucket } from "@/lib/types";

export function IncidentTrendChart({ buckets }: { buckets: AnalyticsBucket[] }) {
  const width = 640;
  const height = 220;
  const left = 42;
  const right = 42;
  const top = 18;
  const bottom = 34;
  const chartWidth = width - left - right;
  const chartHeight = height - top - bottom;

  const maxCount = Math.max(1, ...buckets.map((bucket) => bucket.incidentCount));
  const maxMttr = Math.max(
    1,
    ...buckets.map((bucket) => (bucket.meanTimeToResolveSeconds ?? 0) / 60),
  );

  const slotWidth = chartWidth / Math.max(1, buckets.length);
  const barWidth = Math.min(34, slotWidth * 0.28);

  const points = buckets
    .map((bucket, index) => {
      if (bucket.meanTimeToResolveSeconds == null) return null;

      const x = left + slotWidth * index + slotWidth / 2;
      const minutes = bucket.meanTimeToResolveSeconds / 60;
      const y = top + chartHeight - (minutes / maxMttr) * chartHeight;

      return `${x},${y}`;
    })
    .filter(Boolean)
    .join(" ");

  return (
    <div className="w-full">
      <svg
        viewBox={`0 0 ${width} ${height}`}
        className="h-[220px] w-full"
        role="img"
        aria-label="Incident frequency and mean time to resolve"
      >
        {[0, 0.5, 1].map((ratio) => {
          const y = top + chartHeight * ratio;
          return (
            <line
              key={ratio}
              x1={left}
              x2={width - right}
              y1={y}
              y2={y}
              stroke="#1D2433"
              strokeWidth="1"
            />
          );
        })}

        <text x={left - 8} y={top + 4} textAnchor="end" className="fill-muted font-mono text-[9px]">
          {maxCount}
        </text>
        <text
          x={width - right + 8}
          y={top + 4}
          textAnchor="start"
          className="fill-trace font-mono text-[9px]"
        >
          {Math.round(maxMttr)}m
        </text>

        {buckets.map((bucket, index) => {
          const center = left + slotWidth * index + slotWidth / 2;
          const incidentHeight = (bucket.incidentCount / maxCount) * chartHeight;
          const criticalHeight = (bucket.criticalCount / maxCount) * chartHeight;

          return (
            <g key={`${bucket.label}-${index}`}>
              <rect
                x={center - barWidth - 2}
                y={top + chartHeight - incidentHeight}
                width={barWidth}
                height={incidentHeight}
                rx="2"
                fill="#2A3347"
              />
              <rect
                x={center + 2}
                y={top + chartHeight - criticalHeight}
                width={barWidth}
                height={criticalHeight}
                rx="2"
                fill="#F2545B"
              />
              <text
                x={center}
                y={height - 12}
                textAnchor="middle"
                className="fill-muted font-mono text-[9px]"
              >
                {bucket.label}
              </text>
            </g>
          );
        })}

        {points && (
          <polyline
            points={points}
            fill="none"
            stroke="#6E9BF7"
            strokeWidth="2"
            strokeLinejoin="round"
            strokeLinecap="round"
          />
        )}

        {buckets.map((bucket, index) => {
          if (bucket.meanTimeToResolveSeconds == null) return null;

          const x = left + slotWidth * index + slotWidth / 2;
          const minutes = bucket.meanTimeToResolveSeconds / 60;
          const y = top + chartHeight - (minutes / maxMttr) * chartHeight;

          return <circle key={`mttr-${index}`} cx={x} cy={y} r="3" fill="#6E9BF7" />;
        })}
      </svg>

      <div className="mt-2 flex gap-5 font-mono text-[9px] uppercase tracking-[0.1em] text-muted">
        <span><i className="mr-1.5 inline-block h-2 w-2 bg-ink-600" />Incidents</span>
        <span><i className="mr-1.5 inline-block h-2 w-2 bg-critical" />Critical</span>
        <span><i className="mr-1.5 inline-block h-0.5 w-3 bg-trace align-middle" />MTTR</span>
      </div>
    </div>
  );
}
