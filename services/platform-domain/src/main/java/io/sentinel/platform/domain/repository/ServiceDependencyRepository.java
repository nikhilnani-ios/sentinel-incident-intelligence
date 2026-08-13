package io.sentinel.platform.domain.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import io.sentinel.platform.domain.model.ServiceDependency;

public interface ServiceDependencyRepository extends JpaRepository<ServiceDependency, UUID> {

    /**
     * The whole edge set for a tenant. Small enough to load and cache in full (thousands of edges at
     * most), and traversing an in-memory graph beats issuing one query per BFS hop.
     */
    List<ServiceDependency> findByTenantId(String tenantId);

    boolean existsByTenantIdAndSourceKeyAndTargetKey(String tenantId, String sourceKey, String targetKey);
}
