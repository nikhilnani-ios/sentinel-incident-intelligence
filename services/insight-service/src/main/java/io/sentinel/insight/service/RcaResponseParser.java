package io.sentinel.insight.service;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Turns raw model text into a typed {@link RcaResult}.
 *
 * <p>Models are asked for bare JSON and usually comply, but "usually" is not a contract. This parser
 * strips markdown fences, isolates the outermost JSON object, and clamps every numeric field into
 * range. Anything it cannot parse becomes a clearly-labelled degraded result rather than an
 * exception — during an incident, a partial answer beats a 500.
 */
@Component
public class RcaResponseParser {

    private static final Logger log = LoggerFactory.getLogger(RcaResponseParser.class);
    private static final int MAX_HYPOTHESES = 5;

    private final ObjectMapper objectMapper;

    public RcaResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public RcaResult parse(String rawResponse) {
        String json = isolateJson(rawResponse);
        if (json == null) {
            log.warn("Model response contained no JSON object; falling back to raw text");
            return degraded(rawResponse);
        }

        try {
            JsonNode root = objectMapper.readTree(json);
            return new RcaResult(
                    text(root, "headline", "Analysis produced no headline"),
                    text(root, "summary", ""),
                    clamp(root.path("confidence").asDouble(0.0)),
                    parseHypotheses(root.path("hypotheses")),
                    parseStrings(root.path("immediateActions")));
        } catch (JsonProcessingException e) {
            log.warn("Could not parse model response as JSON: {}", e.getOriginalMessage());
            return degraded(rawResponse);
        }
    }

    private List<RcaResult.Hypothesis> parseHypotheses(JsonNode node) {
        List<RcaResult.Hypothesis> hypotheses = new ArrayList<>();
        if (!node.isArray()) {
            return hypotheses;
        }

        for (JsonNode item : node) {
            hypotheses.add(new RcaResult.Hypothesis(
                    text(item, "cause", "Unspecified"),
                    text(item, "reasoning", ""),
                    clamp(item.path("likelihood").asDouble(0.0)),
                    text(item, "nextStep", "")));
            if (hypotheses.size() == MAX_HYPOTHESES) {
                break;
            }
        }
        // Most likely first, so the UI never has to sort and the top entry is the headline suspect.
        hypotheses.sort((a, b) -> Double.compare(b.likelihood(), a.likelihood()));
        return hypotheses;
    }

    private List<String> parseStrings(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(item -> values.add(item.asText()));
        }
        return values;
    }

    /**
     * Extracts the outermost {@code { ... }} block, tolerating markdown fences and any preamble the
     * model decided to add despite instructions.
     */
    private String isolateJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String cleaned = raw.replace("```json", "").replace("```", "").trim();

        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        return start >= 0 && end > start ? cleaned.substring(start, end + 1) : null;
    }

    private RcaResult degraded(String rawResponse) {
        String excerpt =
                rawResponse == null ? "No response" : rawResponse.substring(0, Math.min(rawResponse.length(), 2000));
        return new RcaResult("Analysis returned an unstructured response", excerpt, 0.0, List.of(), List.of());
    }

    private String text(JsonNode node, String field, String fallback) {
        String value = node.path(field).asText("");
        return value.isBlank() ? fallback : value;
    }

    private double clamp(double value) {
        return Math.clamp(value, 0.0, 1.0);
    }
}
