package io.sentinel.platform.domain.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import io.sentinel.platform.domain.model.IncidentInsight;

public interface IncidentInsightRepository extends JpaRepository<IncidentInsight, UUID> {

    Optional<IncidentInsight> findFirstByIncidentIdAndKindOrderByCreatedAtDesc(
            UUID incidentId, IncidentInsight.Kind kind);

    List<IncidentInsight> findByIncidentIdOrderByCreatedAtDesc(UUID incidentId);
}
