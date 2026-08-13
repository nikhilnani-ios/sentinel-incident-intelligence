import type { Severity } from "./types";

export function duration(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined) return "—";
  if (seconds < 60) return `${Math.round(seconds)}s`;
  if (seconds < 3600) return `${Math.round(seconds / 60)}m`;
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.round((seconds % 3600) / 60);
  return minutes === 0 ? `${hours}h` : `${hours}h ${minutes}m`;
}

/** Elapsed time reads better than a timestamp when you are asking "how long has this been on fire". */
export function since(iso: string, now = Date.now()): string {
  return duration((now - new Date(iso).getTime()) / 1000);
}

export function clock(iso: string): string {
  return new Date(iso).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" });
}

export function dayAndClock(iso: string): string {
  return new Date(iso).toLocaleString([], {
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

export const severityColor: Record<Severity, string> = {
  CRITICAL: "#F2545B",
  HIGH: "#F2A65A",
  MEDIUM: "#D9C55A",
  LOW: "#6E9BF7",
  INFO: "#8A93A6",
};

export const severityClass: Record<Severity, string> = {
  CRITICAL: "text-critical border-critical/40 bg-critical/10",
  HIGH: "text-high border-high/40 bg-high/10",
  MEDIUM: "text-medium border-medium/40 bg-medium/10",
  LOW: "text-low border-low/40 bg-low/10",
  INFO: "text-muted border-muted/40 bg-muted/10",
};

export const statusClass: Record<string, string> = {
  OPEN: "text-critical border-critical/40",
  ACKNOWLEDGED: "text-high border-high/40",
  MITIGATED: "text-low border-low/40",
  RESOLVED: "text-ok border-ok/40",
};
