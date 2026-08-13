package io.sentinel.insight.llm;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Cost-free, deterministic portfolio analysis.
 *
 * <p>This client exercises the same parsing, persistence and rendering path as a hosted model but
 * never makes a network request. Its model name is deliberately explicit so the UI cannot mistake
 * curated demo evidence for live AI output.
 */
@Component
@ConditionalOnProperty(name = "sentinel.insight.mode", havingValue = "demo", matchIfMissing = true)
public class DemoLlmClient implements LlmClient {

    static final String MODEL_NAME = "sentinel-demo-v1";

    @Override
    public Completion complete(Prompt prompt) {
        return prompt.system().contains("blameless postmortem") ? postmortem() : analysis(prompt.user());
    }

    @Override
    public String modelName() {
        return MODEL_NAME;
    }

    private Completion analysis(String context) {
        String primaryService = extract(context, "Primary service:", "payment-gateway");
        String suspect = extract(context, "Most suspicious deployment:", "payment-gateway v2.4.1");

        String json =
                """
                {
                  "headline": "Payment gateway deployment likely triggered checkout failures",
                  "summary": "Errors began on %s shortly after %s. Failures then propagated to checkout-api and edge-gateway in dependency order, making the recent deployment the strongest shared explanation.",
                  "confidence": 0.84,
                  "hypotheses": [
                    {
                      "cause": "Regression introduced by %s",
                      "reasoning": "The deployment precedes detection, the first critical signals originate at the primary service, and upstream checkout failures follow one minute later.",
                      "likelihood": 0.84,
                      "nextStep": "Compare v2.4.1 with the prior release and roll back while watching payment error rate"
                    },
                    {
                      "cause": "Database connection-pool exhaustion",
                      "reasoning": "ConnectionPoolTimeout errors could explain the payment failures, but the evidence does not yet establish whether saturation is a cause or a symptom of the deployment.",
                      "likelihood": 0.46,
                      "nextStep": "Check pool utilization, wait time and orders-postgres saturation around detection"
                    }
                  ],
                  "immediateActions": [
                    "Roll back payment-gateway v2.4.1 or shift traffic to the previous healthy version",
                    "Track payment 5xx rate and checkout success rate during recovery",
                    "Inspect connection-pool and orders-postgres saturation before closing the incident"
                  ]
                }
                """
                        .formatted(primaryService, suspect, suspect);
        return new Completion(json, modelName(), 0, 0);
    }

    private Completion postmortem() {
        String markdown =
                """
                ## Summary
                A payment-gateway regression caused elevated errors that propagated through checkout-api to edge-gateway. Rolling back the recent deployment restored the checkout path.

                ## Customer impact
                Customers experienced failed or delayed checkout attempts while payment requests returned errors.

                ## Timeline
                - payment-gateway v2.4.1 was deployed shortly before detection.
                - Payment error rate and latency crossed alert thresholds.
                - checkout-api and edge-gateway reported downstream failures.
                - Responders mitigated the incident by reverting the suspect change.

                ## Contributing factors
                - The deployment reached production before a checkout-path regression was detected.
                - Upstream services amplified the customer-visible impact of the payment failure.

                ## What went well
                - Dependency-aware correlation grouped nine signals into one incident.
                - Deployment correlation identified the most relevant recent change.

                ## What could have gone better
                - A payment canary and automated rollback could have reduced detection and recovery time.

                ## Action items
                - Payments team: add a checkout success-rate canary for payment-gateway releases.
                - Platform team: trigger automated rollback when canary error budgets are exhausted.
                - Reliability team: add connection-pool saturation to the payment runbook.
                """;
        return new Completion(markdown, modelName(), 0, 0);
    }

    private String extract(String context, String label, String fallback) {
        int start = context.indexOf(label);
        if (start < 0) {
            return fallback;
        }
        int from = start + label.length();
        int end = context.indexOf('\n', from);
        String value = context.substring(from, end < 0 ? context.length() : end).trim();
        return value.isBlank() || "none".equals(value) ? fallback : value;
    }
}
