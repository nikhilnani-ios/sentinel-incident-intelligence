package io.sentinel.insight.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.sentinel.insight.llm.AnthropicLlmClient;
import io.sentinel.insight.service.PostmortemService;
import io.sentinel.insight.service.RcaResult;
import io.sentinel.insight.service.RootCauseAnalysisService;
import io.sentinel.platform.common.security.CurrentUser;
import io.sentinel.platform.domain.model.IncidentInsight;

/**
 * AI-assisted analysis endpoints.
 *
 * <p>Generation is POST rather than GET: it has a cost and a side effect, even though it is
 * idempotent for unchanged evidence. RESPONDER is the floor for requesting analysis so a read-only
 * viewer cannot run up a model bill.
 */
@RestController
@RequestMapping("/v1/incidents/{incidentId}")
public class InsightController {

    private final RootCauseAnalysisService rcaService;
    private final PostmortemService postmortemService;

    public InsightController(RootCauseAnalysisService rcaService, PostmortemService postmortemService) {
        this.rcaService = rcaService;
        this.postmortemService = postmortemService;
    }

    @PostMapping("/analysis")
    @PreAuthorize("hasRole('RESPONDER')")
    public AnalysisResponse analyse(
            @PathVariable UUID incidentId, @RequestParam(defaultValue = "false") boolean force) {

        RootCauseAnalysisService.Analysis analysis =
                rcaService.analyse(CurrentUser.requireTenantId(), incidentId, force);

        return AnalysisResponse.from(analysis);
    }

    @PostMapping("/postmortem")
    @PreAuthorize("hasRole('RESPONDER')")
    public PostmortemResponse postmortem(
            @PathVariable UUID incidentId, @RequestParam(defaultValue = "false") boolean force) {

        IncidentInsight insight = postmortemService.draft(CurrentUser.requireTenantId(), incidentId, force);
        return new PostmortemResponse(
                insight.getHeadline(), insight.getBody(), insight.getModel(), insight.getCreatedAt());
    }

    @GetMapping("/analysis")
    @PreAuthorize("hasRole('VIEWER')")
    public AnalysisResponse latestAnalysis(@PathVariable UUID incidentId) {
        // Reading never triggers generation; the UI shows an explicit "analyse" action instead.
        return AnalysisResponse.from(rcaService.analyse(CurrentUser.requireTenantId(), incidentId, false));
    }

    /** A provider outage degrades this endpoint rather than the whole incident view. */
    @ExceptionHandler(AnthropicLlmClient.LlmUnavailableException.class)
    public ProblemDetail handleUnavailable(AnthropicLlmClient.LlmUnavailableException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        problem.setTitle("Analysis unavailable");
        problem.setProperty("retryable", true);
        return problem;
    }

    public record AnalysisResponse(
            String headline,
            String summary,
            double confidence,
            List<Hypothesis> hypotheses,
            List<String> immediateActions,
            String model,
            boolean cached,
            Instant generatedAt) {

        static AnalysisResponse from(RootCauseAnalysisService.Analysis analysis) {
            RcaResult result = analysis.result();
            return new AnalysisResponse(
                    result.headline(),
                    result.summary(),
                    result.confidence(),
                    result.hypotheses().stream()
                            .map(h -> new Hypothesis(h.cause(), h.reasoning(), h.likelihood(), h.nextStep()))
                            .toList(),
                    result.immediateActions(),
                    analysis.insight().getModel(),
                    analysis.cached(),
                    analysis.insight().getCreatedAt());
        }
    }

    public record Hypothesis(String cause, String reasoning, double likelihood, String nextStep) {}

    public record PostmortemResponse(String headline, String markdown, String model, Instant generatedAt) {}
}
