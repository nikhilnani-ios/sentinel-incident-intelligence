package io.sentinel.correlation.graph;

import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Listens for catalog changes published by the incident service and drops the cached graph.
 *
 * <p>Redis pub/sub is fire-and-forget, which is the right trade here: a missed message costs at most
 * one TTL of staleness, and paying for guaranteed delivery on a cache invalidation would be
 * over-engineering.
 */
@Configuration
public class GraphInvalidationListener {

    public static final String CHANNEL = "sentinel:graph:invalidate";

    private static final Logger log = LoggerFactory.getLogger(GraphInvalidationListener.class);

    @Bean
    public RedisMessageListenerContainer graphInvalidationContainer(
            RedisConnectionFactory connectionFactory, ServiceGraphProvider graphProvider) {

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(
                (message, pattern) -> {
                    String tenantId = new String(message.getBody(), StandardCharsets.UTF_8).replace("\"", "");
                    log.debug("Received graph invalidation for tenant {}", tenantId);
                    graphProvider.invalidate(tenantId);
                },
                new ChannelTopic(CHANNEL));
        return container;
    }
}
