"use client";

import { useCallback, useEffect, useState } from "react";
import { useParams } from "next/navigation";
import { AnalysisPanel } from "@/components/AnalysisPanel";
import { DependencyGraph } from "@/components/DependencyGraph";
import { IncidentTape } from "@/components/IncidentTape";
import { Timeline } from "@/components/Timeline";
import { SeverityBadge, StatusBadge } from "@/components/ui/Badge";
import { Button } from "@/components/ui/Button";
import { Empty } from "@/components/ui/Empty";
import { Panel } from "@/components/ui/Panel";
import { api, ApiError } from "@/lib/api";
import { can } from "@/lib/auth";
import { clock, dayAndClock, duration, severityColor, since } from "@/lib/format";
import { useIncidentStream } from "@/lib/useIncidentStream";
import type { IncidentDetail } from "@/lib/types";

export default function IncidentDetailPage() {
  const { id } = useParams<{ id: string }>();
  const [incident, setIncident] = useState<IncidentDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [postmortem, setPostmortem] = useState<string | null>(null);
  const [dialog, setDialog] = useState<"acknowledge" | "resolve" | null>(null);
  const [actionNote, setActionNote] = useState("");

  const load = useCallback(async () => {
    try {
      setIncident(await api.incident(id));
    } catch (e) {
      setError(e instanceof ApiError ? (e.detail ?? e.message) : "Could not load incident");
    }
  }, [id]);

  useEffect(() => {
    load();
  }, [load]);

  useIncidentStream((event) => {
    if (event.incidentId === id) load();
  });

  async function act(action: () => Promise<unknown>) {
    setBusy(true);
    setError(null);
    try {
      await action();
      await load();
    } catch (e) {
      setError(e instanceof ApiError ? (e.detail ?? e.message) : "Action failed");
    } finally {
      setBusy(false);
    }
  }

  if (error && !incident) return <Empty message={error} />;
  if (!incident) return <Empty message="Loading incident" />;

  const summary = incident.summary;
  const open = summary.status !== "RESOLVED";

  return (
    <div className="space-y-4">
      <header className="flex flex-wrap items-start justify-between gap-4">
        <div className="min-w-0">
          <div className="flex items-center gap-2">
            <span className="font-mono text-xs text-muted">{summary.incidentKey}</span>
            <SeverityBadge severity={summary.severity} />
            <StatusBadge status={summary.status} />
            {summary.escalationLevel > 0 && (
              <span className="font-mono text-[10px] uppercase tracking-[0.12em] text-critical">
                escalated L{summary.escalationLevel}
              </span>
            )}
          </div>
          <h1 className="mt-1 break-words font-display text-xl tracking-tightest sm:text-2xl">{summary.title}</h1>
          <p className="mt-1 font-mono text-[11px] text-muted">
            {summary.primaryServiceKey} · detected {dayAndClock(summary.detectedAt)} ·{" "}
            {open ? `open ${since(summary.detectedAt)}` : `resolved in ${duration(
              (new Date(summary.resolvedAt!).getTime() - new Date(summary.detectedAt).getTime()) / 1000,
            )}`}
          </p>
        </div>

        <div className="flex flex-wrap gap-2">
          {summary.status === "OPEN" && can("RESPONDER") && (
            <Button
              variant="primary"
              loading={busy}
              onClick={() => {
                setActionNote("");
                setDialog("acknowledge");
              }}
            >
              Acknowledge
            </Button>
          )}
          {open && summary.status !== "MITIGATED" && can("RESPONDER") && (
            <Button loading={busy} onClick={() => act(() => api.mitigate(id))}>
              Mark mitigated
            </Button>
          )}
          {open && can("COMMANDER") && (
            <Button
              variant="danger"
              loading={busy}
              onClick={() => {
                setActionNote("");
                setDialog("resolve");
              }}
            >
              Resolve
            </Button>
          )}
          {!open && can("RESPONDER") && (
            <Button
              loading={busy}
              onClick={() =>
                act(async () => {
                  const draft = await api.postmortem(id);
                  setPostmortem(draft.markdown);
                })
              }
            >
              Draft postmortem
            </Button>
          )}
        </div>
      </header>

      {error && (
        <p className="border border-critical/40 bg-critical/10 px-3 py-2 text-sm text-critical">{error}</p>
      )}

      {dialog && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 px-4"
          role="dialog"
          aria-modal="true"
          aria-labelledby="incident-action-title"
          onMouseDown={(event) => {
            if (event.target === event.currentTarget && !busy) setDialog(null);
          }}
        >
          <div className="w-full max-w-lg border border-ink-600 bg-ink-900 shadow-2xl">
            <div className="border-b border-ink-700 px-5 py-4">
              <h2
                id="incident-action-title"
                className="font-mono text-xs uppercase tracking-[0.14em] text-paper"
              >
                {dialog === "acknowledge" ? "Acknowledge incident" : "Resolve incident"}
              </h2>
              <p className="mt-2 text-sm text-muted">
                {dialog === "acknowledge"
                  ? "Record what you are investigating or the action you are taking."
                  : "Describe what resolved the incident. This will be recorded in its timeline."}
              </p>
            </div>

            <form
              className="space-y-4 p-5"
              onSubmit={(event) => {
                event.preventDefault();
                const note = actionNote.trim();

                if (dialog === "resolve" && !note) return;

                const action =
                  dialog === "acknowledge"
                    ? () => api.acknowledge(id, note || undefined)
                    : () => api.resolve(id, note);

                setDialog(null);
                setActionNote("");
                void act(action);
              }}
            >
              <div>
                <label
                  htmlFor="incident-action-note"
                  className="mb-2 block font-mono text-[10px] uppercase tracking-[0.12em] text-muted"
                >
                  {dialog === "acknowledge" ? "Investigation note · optional" : "Resolution summary · required"}
                </label>
                <textarea
                  id="incident-action-note"
                  autoFocus
                  rows={5}
                  value={actionNote}
                  onChange={(event) => setActionNote(event.target.value)}
                  placeholder={
                    dialog === "acknowledge"
                      ? "Investigating payment-gateway errors after the latest deployment…"
                      : "Rolled back the deployment and confirmed error rates returned to normal…"
                  }
                  className="w-full resize-y border border-ink-600 bg-ink-950 px-3 py-3 font-mono text-sm text-paper outline-none placeholder:text-muted/50 focus:border-trace"
                />
              </div>

              <div className="flex flex-col-reverse gap-2 border-t border-ink-700 pt-4 sm:flex-row sm:justify-end">
                <Button type="button" disabled={busy} onClick={() => setDialog(null)}>
                  Cancel
                </Button>
                <Button
                  type="submit"
                  variant={dialog === "resolve" ? "danger" : "primary"}
                  loading={busy}
                  disabled={dialog === "resolve" && !actionNote.trim()}
                >
                  {dialog === "acknowledge" ? "Acknowledge incident" : "Resolve incident"}
                </Button>
              </div>
            </form>
          </div>
        </div>
      )}

      <Panel title="Signal tape" flush>
        <div className="p-4">
          <IncidentTape
            detectedAt={summary.detectedAt}
            resolvedAt={summary.resolvedAt}
            signals={incident.signals}
            deployments={incident.suspectDeployments}
            timeline={incident.timeline}
          />
          <div className="mt-3 flex flex-wrap gap-4 font-mono text-[10px] uppercase tracking-[0.1em] text-muted">
            <Legend color="#F2545B" label="critical signal" />
            <Legend color="#F2A65A" label="high" />
            <Legend color="#D9C55A" label="medium" />
            <Legend color="#4CC38A" label="deployment" />
            <span>bar height = repeat count</span>
          </div>
        </div>
      </Panel>

      <div className="grid gap-4 lg:grid-cols-[1fr_1fr]">
        <AnalysisPanel incidentId={id} initial={incident.rootCauseAnalysis} />

        <Panel title="Suspect deployments">
          {incident.suspectDeployments.length === 0 ? (
            <Empty
              message="No deployments correlated"
              hint="Nothing shipped to the affected services inside the lookback window."
            />
          ) : (
            <ul className="space-y-3">
              {incident.suspectDeployments.map((deployment) => (
                <li key={deployment.deploymentId} className="border-l-2 border-ok/40 pl-3">
                  <div className="flex items-baseline justify-between gap-3">
                    <p className="font-mono text-sm text-paper">
                      {deployment.serviceKey} <span className="text-ok">{deployment.version}</span>
                    </p>
                    <span className="shrink-0 font-mono text-[10px] text-muted tabular">
                      {(deployment.suspicionScore * 100).toFixed(0)}% suspicion
                    </span>
                  </div>
                  <p className="mt-0.5 text-xs text-muted">{deployment.rationale}</p>
                  <p className="mt-1 font-mono text-[10px] text-muted/70">
                    {deployment.commitSha?.slice(0, 8) ?? "no commit"} · {deployment.environment} ·{" "}
                    {clock(deployment.occurredAt)}
                    {deployment.deployedBy && ` · ${deployment.deployedBy}`}
                  </p>
                </li>
              ))}
            </ul>
          )}
        </Panel>
      </div>

      <div className="grid gap-4 lg:grid-cols-[1fr_1fr]">
        <Panel title={`Timeline · ${incident.timeline.length} entries`}>
          <Timeline entries={incident.timeline} />
        </Panel>

        <div className="space-y-4">
          <Panel title="Blast radius" flush>
            <div className="overflow-x-auto p-4">
              <DependencyGraph nodes={incident.dependencyGraph} />
            </div>
          </Panel>

          <Panel title={`Correlated signals · ${incident.signals.length}`} flush>
            <div className="max-h-80 overflow-y-auto">
              {incident.signals.map((signal) => (
                <div key={signal.id} className="border-b border-ink-700 px-4 py-2 last:border-b-0">
                  <div className="flex items-baseline justify-between gap-3">
                    <p className="min-w-0 truncate text-sm text-paper">{signal.summary}</p>
                    <span
                      className="shrink-0 font-mono text-[10px] tabular"
                      style={{ color: severityColor[signal.severity] }}
                    >
                      {signal.occurrences > 1 && `×${signal.occurrences} · `}
                      {signal.correlationScore.toFixed(2)}
                    </span>
                  </div>
                  <p className="font-mono text-[10px] uppercase tracking-[0.1em] text-muted">
                    {signal.type} · {signal.serviceKey} · {clock(signal.firstSeenAt)}
                  </p>
                </div>
              ))}
            </div>
          </Panel>
        </div>
      </div>

      {postmortem && (
        <Panel
          title="Postmortem draft"
          actions={
            <Button onClick={() => navigator.clipboard.writeText(postmortem)}>Copy markdown</Button>
          }
        >
          <pre className="max-h-[32rem] overflow-auto whitespace-pre-wrap font-mono text-xs leading-relaxed text-paper">
            {postmortem}
          </pre>
          <p className="hairline mt-3 pt-2 text-[10px] text-muted">
            A draft assembled from the incident record. Edit before publishing — the platform cannot
            supply organisational context or judgement.
          </p>
        </Panel>
      )}
    </div>
  );
}

function Legend({ color, label }: { color: string; label: string }) {
  return (
    <span className="flex items-center gap-1.5">
      <span className="h-2 w-2" style={{ background: color }} />
      {label}
    </span>
  );
}
