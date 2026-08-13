package io.sentinel.incident.api;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.sentinel.incident.service.AnalyticsService;
import io.sentinel.platform.common.security.CurrentUser;

/** Reliability dashboards: MTTA, MTTR, frequency and the services that generate the most pages. */
@RestController
@RequestMapping("/v1/analytics")
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping("/overview")
    @PreAuthorize("hasRole('VIEWER')")
    public AnalyticsService.Overview overview(
            @RequestParam(defaultValue = "LAST_7_DAYS") AnalyticsService.Window window) {
        return analyticsService.overview(CurrentUser.requireTenantId(), window);
    }
}
