package io.sentinel.insight.service;

import java.util.List;
import java.util.Map;

/** Parsed model output for a root cause analysis. */
public record RcaResult(
        String headline,
        String summary,
        double confidence,
        List<Hypothesis> hypotheses,
        List<String> immediateActions) {

    public record Hypothesis(String cause, String reasoning, double likelihood, String nextStep) {

        public Map<String, Object> asMap() {
            return Map.of(
                    "cause", cause,
                    "reasoning", reasoning,
                    "likelihood", likelihood,
                    "nextStep", nextStep);
        }
    }

    public List<Map<String, Object>> hypothesesAsMaps() {
        return hypotheses.stream().map(Hypothesis::asMap).toList();
    }
}
