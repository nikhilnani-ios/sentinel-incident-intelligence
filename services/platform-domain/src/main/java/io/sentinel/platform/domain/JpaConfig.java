package io.sentinel.platform.domain;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Single import point for the persistence layer.
 *
 * <p>Services get entities, repositories and auditing by depending on this module — none of them
 * repeat the scan configuration, so a new entity package is added in exactly one place.
 */
@Configuration
@EnableJpaAuditing
@EnableTransactionManagement
@EntityScan(basePackages = "io.sentinel.platform.domain.model")
@EnableJpaRepositories(basePackages = "io.sentinel.platform.domain.repository")
public class JpaConfig {}
