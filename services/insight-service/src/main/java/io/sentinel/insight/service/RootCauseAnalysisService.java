package io.sentinel.insight.service;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.sentinel.insight.llm.LlmClient;
import io.sentinel.insight.prompt.PromptBuilder;
import io.sentinel.platform.common.event.IncidentEvent;
import io.sentinel.platform.common.kafka.Topics;
import io.sentinel.platform.domain.model.IncidentInsight;
import io.sentinel.platform.domain.repository.IncidentInsightRepository;

/**
 * Generates root cause analysis for an incident.
 *
 * <p>Three properties matter more than the prompt itself:
 *
 * <ul>
 *   <li><b>Idempotent by content.</b> If the evidence has not changed since the last analysis, the
 *       stored one is returned untouched. Refreshing an incident page must not cost money.
 *   <li><b>Never on the write path.</b> Analysis is requested explicitly or triggered
 *       asynchronously; the correlation pipeline never waits on a model call, so a slow provider
 *       cannot delay incident creation.
 *   <li><b>Advisory, never authoritative.</b> The output is stored as an insight with a confidence
 *       score and rendered as a suggestion. Nothing in the platform acts on it automatically.
 * </ul>
 */
@Service
public class RootCauseAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(RootCauseAnalysisService.class);
    private static final int MAX_PROMPT_CHARACTERS = 60_000;

    private final IncidentContextAssembler contextAssembler;
    private final IncidentInsightRepository insightRepository;
    private final PromptBuilder promptBuilder;
    private final RcaResponseParser parser;
    private final LlmClient llmClient;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final int maxTokens;

    @SuppressWarnings("java:S107")
    public RootCauseAnalysisService(
            IncidentContextAssembler contextAssembler,
            IncidentInsightRepository insightRepository,
            PromptBuilder promptBuilder,
            RcaResponseParser parser,
            LlmClient llmClient,
            KafkaTemplate<String, Object> kafkaTemplate,
            MeterRegistry meterRegistry,
            Clock clock,
            @Value("${sentinel.insight.max-output-tokens:2000}") int maxTokens) {
        this.contextAssembler = contextAssembler;
        this.insightRepository = insightRepository;
        this.promptBuilder = promptBuilder;
        this.parser = parser;
        this.llmClient = llmClient;
        this.kafkaTemplate = kafkaTemplate;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        this.maxTokens = maxTokens;
    }

    @Transactional
    public Analysis analyse(String tenantId, UUID incidentId, boolean force) {
        IncidentContextAssembler.Bundle bundle = contextAssembler.assemble(tenantId, incidentId);

        Optional<IncidentInsight> existing = insightRepository.findFirstByIncidentIdAndKindOrderByCreatedAtDesc(
                incidentId, IncidentInsight.Kind.ROOT_CAUSE_ANALYSIS);

        if (!force
                && existing.filter(insight -> insight.getContextHash().equals(bundle.contextHash()))
                        .isPresent()) {
            Counter.builder("sentinel.insight.cache")
                    .tag("result", "hit")
                    .register(meterRegistry)
                    .increment();
            return Analysis.cached(existing.get());
        }

        String userPrompt = promptBuilder.render(bundle.context());
        if (!promptBuilder.withinBudget(userPrompt, MAX_PROMPT_CHARACTERS)) {
            // Truncating the tail keeps the incident header, deployments and topology, which carry
            // most of the causal signal, and drops the repetitive alert spam at the end.
            log.info("Truncating oversized prompt for {}", bundle.incident().getIncidentKey());
            userPrompt = userPrompt.substring(0, MAX_PROMPT_CHARACTERS) + "\n[context truncated]";
        }

        Timer.Sample sample = Timer.start(meterRegistry);
        LlmClient.Completion completion =
                llmClient.complete(new LlmClient.Prompt(promptBuilder.rcaSystemPrompt(), userPrompt, maxTokens));
        sample.stop(Timer.builder("sentinel.insight.generation")
                .tag("kind", "rca")
                .publishPercentiles(0.5, 0.95)
                .register(meterRegistry));

        RcaResult result = parser.parse(completion.text());

        IncidentInsight insight = new IncidentInsight(
                        incidentId,
                        tenantId,
                        IncidentInsight.Kind.ROOT_CAUSE_ANALYSIS,
                        completion.model(),
                        bundle.contextHash())
                .withContent(result.headline(), result.summary(), result.confidence())
                .withHypotheses(result.hypothesesAsMaps())
                .withUsage(completion.inputTokens(), completion.outputTokens());

        IncidentInsight saved = insightRepository.save(insight);

        Counter.builder("sentinel.insight.cache")
                .tag("result", "miss")
                .register(meterRegistry)
                .increment();
        meterRegistry.counter("sentinel.insight.tokens", "direction", "output").increment(completion.outputTokens());

        announce(bundle, saved);
        log.info(
                "Generated RCA for {} with confidence {} using {}",
                bundle.incident().getIncidentKey(),
                result.confidence(),
                completion.model());

        return new Analysis(saved, result, false);
    }

    /**
     * Publishes ANALYSIS_READY so any browser watching the incident updates without polling. Failure
     * here is logged, not thrown — the analysis is already durable, and losing the nudge is a far
     * smaller problem than rolling back the work that produced it.
     */
    private void announce(IncidentContextAssembler.Bundle bundle, IncidentInsight insight) {
        try {
            IncidentEvent event = new IncidentEvent(
                    bundle.incident().getId(),
                    bundle.incident().getTenantId(),
                    IncidentEvent.Change.ANALYSIS_READY,
                    bundle.incident().getStatus(),
                    bundle.incident().getSeverity(),
                    insight.getHeadline(),
                    bundle.incident().getPrimaryServiceKey(),
                    List.copyOf(bundle.incident().affectedServiceKeySet()),
                    "insight-service",
                    clock.instant());

            kafkaTemplate.send(Topics.INCIDENT_EVENTS, event.tenantId() + ":" + event.incidentId(), event);
        } catch (RuntimeException e) {
            log.warn("Could not announce analysis for {}: {}", bundle.incident().getIncidentKey(), e.getMessage());
        }
    }

    /** @param cached true when the stored analysis was reused because the evidence had not changed */
    public record Analysis(IncidentInsight insight, RcaResult result, boolean cached) {

        static Analysis cached(IncidentInsight insight) {
            return new Analysis(
                    insight,
                    new RcaResult(
                            insight.getHeadline(),
                            insight.getBody(),
                            insight.getConfidence(),
                            insight.getHypotheses().stream()
                                    .map(map -> new RcaResult.Hypothesis(
                                            String.valueOf(map.getOrDefault("cause", "")),
                                            String.valueOf(map.getOrDefault("reasoning", "")),
                                            map.get("likelihood") instanceof Number n ? n.doubleValue() : 0.0,
                                            String.valueOf(map.getOrDefault("nextStep", ""))))
                                    .toList(),
                            List.of()),
                    true);
        }
    }
}
