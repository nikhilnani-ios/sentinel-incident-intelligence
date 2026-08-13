package io.sentinel.platform.domain.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import io.sentinel.platform.domain.model.EscalationPolicy;

public interface EscalationPolicyRepository extends JpaRepository<EscalationPolicy, UUID> {

    Optional<EscalationPolicy> findByTenantIdAndPolicyKey(String tenantId, String policyKey);

    List<EscalationPolicy> findByTenantId(String tenantId);
}
