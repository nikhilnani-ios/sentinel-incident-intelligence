package io.sentinel.insight.llm;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * Deterministic stand-in used whenever no API key is configured.
 *
 * <p>Returns a correctly shaped response built from the context it was handed, so the full pipeline
 * — context assembly, parsing, persistence, UI rendering — is exercised in local development and CI
 * without network access, credentials or spend. It never claims to be a real analysis: the headline
 * says so.
 */
@Component
@ConditionalOnExpression("'${sentinel.insight.anthropic.api-key:}'.length() == 0")
public class StubLlmClient implements LlmClient {

    @Override
    public Completion complete(Prompt prompt) {
        String primaryService = extract(prompt.user(), "Primary service:");
        String suspectDeploy = extract(prompt.user(), "Most suspicious deployment:");

        String json =
                """
                {
                  "headline": "Analysis unavailable — no model configured",
                  "summary": "This is a locally generated placeholder. Configure sentinel.insight.anthropic.api-key to enable real analysis. Primary service: %s.",
                  "confidence": 0.0,
                  "hypotheses": [
                    {
                      "cause": "Recent change to %s",
                      "reasoning": "%s",
                      "likelihood": 0.0,
                      "nextStep": "Review the linked deployment and its changelog"
                    }
                  ],
                  "immediateActions": ["Check the runbook for the affected service"]
                }
                """
                        .formatted(primaryService, primaryService, suspectDeploy);

        return new Completion(json, modelName(), 0, 0);
    }

    @Override
    public String modelName() {
        return "stub";
    }

    private String extract(String context, String label) {
        int start = context.indexOf(label);
        if (start < 0) {
            return "unknown";
        }
        int from = start + label.length();
        int end = context.indexOf('\n', from);
        return context.substring(from, end < 0 ? context.length() : end).trim();
    }
}
