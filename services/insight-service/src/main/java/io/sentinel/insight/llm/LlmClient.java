package io.sentinel.insight.llm;

/**
 * The seam between the platform and whichever model happens to be behind it.
 *
 * <p>Everything above this interface deals in incidents and hypotheses, never in HTTP or vendor
 * request shapes. That is what makes it possible to swap providers, run a local model, or drop in
 * {@link StubLlmClient} for tests and offline demos without touching a line of domain code.
 */
public interface LlmClient {

    Completion complete(Prompt prompt);

    String modelName();

    /**
     * @param system instructions that define the model's role and output contract
     * @param user the incident context and the question being asked of it
     * @param maxTokens ceiling on the response, which is also the cost ceiling
     */
    record Prompt(String system, String user, int maxTokens) {}

    /**
     * @param text raw model output
     * @param inputTokens billed input tokens, recorded so spend is attributable per incident
     */
    record Completion(String text, String model, int inputTokens, int outputTokens) {}
}
