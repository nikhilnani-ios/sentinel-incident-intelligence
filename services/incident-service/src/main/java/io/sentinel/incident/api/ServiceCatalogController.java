package io.sentinel.incident.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.sentinel.incident.service.ServiceCatalogService;
import io.sentinel.platform.common.security.CurrentUser;
import io.sentinel.platform.domain.model.ServiceDependency;
import io.sentinel.platform.domain.model.ServiceNode;

/** Service catalog and dependency graph. Reads are open to viewers; edits are admin-only. */
@RestController
@RequestMapping("/v1/catalog")
public class ServiceCatalogController {

    private final ServiceCatalogService catalogService;

    public ServiceCatalogController(ServiceCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping("/topology")
    @PreAuthorize("hasRole('VIEWER')")
    public ServiceCatalogService.Topology topology() {
        return catalogService.topology(CurrentUser.requireTenantId());
    }

    @PostMapping("/services")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public ServiceCatalogService.Node registerService(@Valid @RequestBody RegisterService request) {
        ServiceNode node = catalogService.register(
                CurrentUser.requireTenantId(),
                request.serviceKey(),
                request.displayName(),
                request.tier(),
                request.ownerTeam());
        return new ServiceCatalogService.Node(
                node.getServiceKey(),
                node.getDisplayName(),
                node.getTier().name(),
                node.getOwnerTeam(),
                node.getRunbookUrl(),
                0);
    }

    @PostMapping("/dependencies")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public ServiceCatalogService.Edge addDependency(@Valid @RequestBody AddDependency request) {
        ServiceDependency dependency = catalogService.addDependency(
                CurrentUser.requireTenantId(),
                request.source(),
                request.target(),
                request.kind(),
                request.criticality());
        return new ServiceCatalogService.Edge(
                dependency.getSourceKey(),
                dependency.getTargetKey(),
                dependency.getKind().name(),
                dependency.getCriticality());
    }

    public record RegisterService(
            @NotBlank String serviceKey,
            @NotBlank String displayName,
            @NotNull ServiceNode.Tier tier,
            String ownerTeam) {}

    public record AddDependency(
            @NotBlank String source,
            @NotBlank String target,
            @NotNull ServiceDependency.Kind kind,
            @DecimalMin("0.0") @DecimalMax("1.0") Double criticality) {}
}
