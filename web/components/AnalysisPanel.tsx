"use client";

import { useState } from "react";
import { Button } from "./ui/Button";
import { Panel } from "./ui/Panel";
import { api, ApiError } from "@/lib/api";
import { can } from "@/lib/auth";
import type { Analysis, EmbeddedInsight } from "@/lib/types";

/**
 * AI analysis, presented as a suggestion and never as a verdict.
 *
 * <p>Every hypothesis shows its likelihood bar and the evidence behind it, the model name is on
 * screen, and low-confidence output is labelled as such rather than styled identically to a
 * high-confidence one. An incident tool that presents a guess with the same authority as a metric
 * teaches people to stop reading it.
 */
export function AnalysisPanel({
  incidentId,
  initial,
}: {
  incidentId: string;
  initial: EmbeddedInsight | null;
}) {
  // The detail response embeds what was persisted; the analysis endpoint returns the fuller shape.
  // Normalising here keeps one render path instead of two near-identical ones.
  const [analysis, setAnalysis] = useState<Analysis | null>(
    initial
      ? {
          headline: initial.headline,
          summary: initial.body,
          confidence: initial.confidence,
          hypotheses: initial.hypotheses ?? [],
          immediateActions: [],
          model: initial.model,
          cached: true,
          generatedAt: initial.generatedAt,
        }
      : null,
  );
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function generate(force: boolean) {
    setLoading(true);
    setError(null);
    try {
      setAnalysis(await api.analyse(incidentId, force));
    } catch (e) {
      setError(e instanceof ApiError ? (e.detail ?? e.message) : "Analysis failed");
    } finally {
      setLoading(false);
    }
  }

  const confidenceLabel = (value: number) =>
    value >= 0.7 ? "well supported" : value >= 0.4 ? "partially supported" : "weak evidence";

  return (
    <Panel
      title="Root cause analysis"
      actions={
        can("RESPONDER") && (
          <Button variant="primary" loading={loading} onClick={() => generate(Boolean(analysis))}>
            {analysis ? "Regenerate" : "Analyse"}
          </Button>
        )
      }
    >
      {error && <p className="mb-3 border border-critical/40 bg-critical/10 px-3 py-2 text-sm text-critical">{error}</p>}

      {!analysis && !loading && (
        <p className="py-6 text-center text-sm text-muted">
          No analysis yet. Generation reads the correlated signals, the timeline and any linked deployments.
        </p>
      )}

      {loading && !analysis && (
        <div className="space-y-2 py-6">
          <div className="h-3 w-2/3 overflow-hidden rounded bg-ink-700">
            <div className="h-full w-1/3 animate-sweep bg-trace/40" />
          </div>
          <p className="font-mono text-[11px] text-muted">Reading the incident record…</p>
        </div>
      )}

      {analysis && (
        <div className="space-y-4">
          <div>
            <h3 className="font-display text-lg tracking-tightest text-paper">{analysis.headline}</h3>
            <p className="mt-1 text-sm leading-relaxed text-muted">{analysis.summary}</p>
          </div>

          <div className="flex items-center gap-3">
            <div className="h-1 flex-1 overflow-hidden rounded-full bg-ink-700">
              <div
                className="h-full rounded-full bg-trace"
                style={{ width: `${Math.max(analysis.confidence * 100, 2)}%` }}
              />
            </div>
            <span className="font-mono text-[10px] uppercase tracking-[0.12em] text-muted">
              {(analysis.confidence * 100).toFixed(0)}% · {confidenceLabel(analysis.confidence)}
            </span>
          </div>

          {analysis.hypotheses.length > 0 && (
            <ul className="space-y-3">
              {analysis.hypotheses.map((hypothesis, index) => (
                <li key={index} className="border-l-2 border-ink-600 pl-3">
                  <div className="flex items-baseline justify-between gap-3">
                    <p className="text-sm font-medium text-paper">{hypothesis.cause}</p>
                    <span className="shrink-0 font-mono text-[10px] text-muted tabular">
                      {(hypothesis.likelihood * 100).toFixed(0)}%
                    </span>
                  </div>
                  <p className="mt-0.5 text-xs leading-relaxed text-muted">{hypothesis.reasoning}</p>
                  {hypothesis.nextStep && (
                    <p className="mt-1 font-mono text-[10px] uppercase tracking-[0.1em] text-trace">
                      → {hypothesis.nextStep}
                    </p>
                  )}
                </li>
              ))}
            </ul>
          )}

          {analysis.immediateActions.length > 0 && (
            <div>
              <p className="label mb-1.5">Immediate actions</p>
              <ol className="space-y-1">
                {analysis.immediateActions.map((action, index) => (
                  <li key={index} className="flex gap-2 text-sm text-paper">
                    <span className="font-mono text-[11px] text-muted tabular">{index + 1}.</span>
                    {action}
                  </li>
                ))}
              </ol>
            </div>
          )}

          <p className="hairline pt-2 font-mono text-[10px] text-muted">
            {analysis.model} · {analysis.cached ? "cached — evidence unchanged" : "generated just now"} · advisory only
          </p>
        </div>
      )}
    </Panel>
  );
}
