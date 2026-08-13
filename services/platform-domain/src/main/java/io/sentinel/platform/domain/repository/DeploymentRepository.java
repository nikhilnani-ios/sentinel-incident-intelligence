package io.sentinel.platform.domain.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import io.sentinel.platform.domain.model.Deployment;

public interface DeploymentRepository extends JpaRepository<Deployment, UUID> {

    @Query(
            """
            select d from Deployment d
            where d.tenantId = :tenantId
              and d.serviceKey in :serviceKeys
              and d.occurredAt between :from and :to
            order by d.occurredAt desc
            """)
    List<Deployment> findInWindow(
            @Param("tenantId") String tenantId,
            @Param("serviceKeys") List<String> serviceKeys,
            @Param("from") Instant from,
            @Param("to") Instant to);

    List<Deployment> findTop20ByTenantIdOrderByOccurredAtDesc(String tenantId);
}
