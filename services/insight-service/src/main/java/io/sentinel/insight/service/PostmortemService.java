package io.sentinel.insight.service;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.sentinel.insight.llm.LlmClient;
import io.sentinel.insight.prompt.PromptBuilder;
import io.sentinel.platform.common.error.DomainException;
import io.sentinel.platform.common.event.IncidentStatus;
import io.sentinel.platform.domain.model.Incident;
import io.sentinel.platform.domain.model.IncidentInsight;
import io.sentinel.platform.domain.repository.IncidentInsightRepository;

/**
 * Drafts a blameless postmortem from the incident record.
 *
 * <p>Deliberately a <em>draft</em>. The timeline, impact window and deployment links are facts the
 * platform already holds, and reassembling them by hand is an hour of tedium that gets skipped when
 * teams are busy — which is why so many incidents never get written up. What the model cannot supply
 * is organisational judgement, so the output is markdown for a human to edit, not a published
 * document.
 *
 * <p>Only available once the incident is resolved. A postmortem for an ongoing incident would be
 * fiction, and worse, would read as authoritative.
 */
@Service
public class PostmortemService {

    private static final Logger log = LoggerFactory.getLogger(PostmortemService.class);

    private final IncidentContextAssembler contextAssembler;
    private final IncidentInsightRepository insightRepository;
    private final PromptBuilder promptBuilder;
    private final LlmClient llmClient;
    private final MeterRegistry meterRegistry;
    private final int maxTokens;

    public PostmortemService(
            IncidentContextAssembler contextAssembler,
            IncidentInsightRepository insightRepository,
            PromptBuilder promptBuilder,
            LlmClient llmClient,
            MeterRegistry meterRegistry,
            @Value("${sentinel.insight.postmortem-max-output-tokens:4000}") int maxTokens) {
        this.contextAssembler = contextAssembler;
        this.insightRepository = insightRepository;
        this.promptBuilder = promptBuilder;
        this.llmClient = llmClient;
        this.meterRegistry = meterRegistry;
        this.maxTokens = maxTokens;
    }

    @Transactional
    public IncidentInsight draft(String tenantId, UUID incidentId, boolean force) {
        IncidentContextAssembler.Bundle bundle = contextAssembler.assemble(tenantId, incidentId);
        Incident incident = bundle.incident();

        if (incident.getStatus() != IncidentStatus.RESOLVED) {
            throw new IncidentNotResolvedException(incident.getIncidentKey(), incident.getStatus());
        }

        Optional<IncidentInsight> existing = insightRepository.findFirstByIncidentIdAndKindOrderByCreatedAtDesc(
                incidentId, IncidentInsight.Kind.POSTMORTEM);

        if (!force
                && existing.filter(insight -> insight.getContextHash().equals(bundle.contextHash()))
                        .isPresent()) {
            return existing.get();
        }

        Timer.Sample sample = Timer.start(meterRegistry);
        LlmClient.Completion completion = llmClient.complete(new LlmClient.Prompt(
                promptBuilder.postmortemSystemPrompt(), promptBuilder.render(bundle.context()), maxTokens));
        sample.stop(Timer.builder("sentinel.insight.generation")
                .tag("kind", "postmortem")
                .register(meterRegistry));

        String markdown = completion.text();
        IncidentInsight postmortem = new IncidentInsight(
                        incidentId, tenantId, IncidentInsight.Kind.POSTMORTEM, completion.model(), bundle.contextHash())
                .withContent(
                        "Postmortem draft: %s".formatted(incident.getTitle()),
                        markdown,
                        // Postmortems are a summary of facts already held, not a prediction, so a
                        // confidence score would be meaningless. Zero means "not applicable" here.
                        0.0)
                .withUsage(completion.inputTokens(), completion.outputTokens());

        log.info("Drafted postmortem for {} ({} output tokens)", incident.getIncidentKey(), completion.outputTokens());
        return insightRepository.save(postmortem);
    }

    public static class IncidentNotResolvedException extends DomainException {
        public IncidentNotResolvedException(String incidentKey, IncidentStatus status) {
            super(
                    HttpStatus.CONFLICT,
                    "incident_not_resolved",
                    "%s is %s — a postmortem can only be drafted once an incident is resolved"
                            .formatted(incidentKey, status));
        }
    }
}
