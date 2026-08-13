package io.sentinel.correlation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;

import io.sentinel.correlation.config.CorrelationProperties;

@SpringBootApplication
@EnableConfigurationProperties(CorrelationProperties.class)
@ComponentScan(basePackages = {"io.sentinel.correlation", "io.sentinel.platform.common"})
@Import(io.sentinel.platform.domain.JpaConfig.class)
public class CorrelationApplication {

    public static void main(String[] args) {
        SpringApplication.run(CorrelationApplication.class, args);
    }
}
