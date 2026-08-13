package io.sentinel.platform.domain.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import io.sentinel.platform.domain.model.ServiceNode;

public interface ServiceNodeRepository extends JpaRepository<ServiceNode, UUID> {

    Optional<ServiceNode> findByTenantIdAndServiceKey(String tenantId, String serviceKey);

    List<ServiceNode> findByTenantIdOrderByServiceKeyAsc(String tenantId);

    List<ServiceNode> findByTenantIdAndServiceKeyIn(String tenantId, List<String> serviceKeys);

    boolean existsByTenantIdAndServiceKey(String tenantId, String serviceKey);
}
