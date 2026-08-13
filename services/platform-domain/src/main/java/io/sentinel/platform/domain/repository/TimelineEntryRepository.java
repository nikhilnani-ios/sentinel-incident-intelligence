package io.sentinel.platform.domain.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import io.sentinel.platform.domain.model.TimelineEntry;

public interface TimelineEntryRepository extends JpaRepository<TimelineEntry, UUID> {

    List<TimelineEntry> findByIncidentIdOrderByOccurredAtAsc(UUID incidentId);
}
