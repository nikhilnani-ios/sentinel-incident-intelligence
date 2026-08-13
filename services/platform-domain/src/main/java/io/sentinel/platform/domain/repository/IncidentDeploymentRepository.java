package io.sentinel.platform.domain.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import io.sentinel.platform.domain.model.IncidentDeployment;

public interface IncidentDeploymentRepository extends JpaRepository<IncidentDeployment, UUID> {

    List<IncidentDeployment> findByIncidentIdOrderBySuspicionScoreDesc(UUID incidentId);

    boolean existsByIncidentIdAndDeploymentId(UUID incidentId, UUID deploymentId);
}
