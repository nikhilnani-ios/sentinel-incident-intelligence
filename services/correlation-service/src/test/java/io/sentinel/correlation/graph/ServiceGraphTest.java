package io.sentinel.correlation.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.sentinel.platform.domain.model.ServiceDependency;

class ServiceGraphTest {

    /**
     * edge-gateway → checkout-api → payment-gateway
     *                             → orders-postgres
     *                checkout-api → notification-api (async, weak)
     */
    private final ServiceGraph graph = ServiceGraph.from(List.of(
            edge("edge-gateway", "checkout-api", 0.95),
            edge("checkout-api", "payment-gateway", 0.90),
            edge("checkout-api", "orders-postgres", 0.95),
            edge("checkout-api", "notification-api", 0.20)));

    @Test
    @DisplayName("blast radius finds who breaks when a dependency fails")
    void blastRadiusWalksUpstream() {
        var affected = graph.blastRadius("orders-postgres", 3);

        assertThat(affected).containsKeys("checkout-api", "edge-gateway");
        assertThat(affected.get("checkout-api").hops()).isEqualTo(1);
        assertThat(affected.get("edge-gateway").hops()).isEqualTo(2);
    }

    @Test
    @DisplayName("path weight is the product of edge criticalities, so distance decays")
    void weightDecaysWithDistance() {
        var affected = graph.blastRadius("orders-postgres", 3);

        assertThat(affected.get("checkout-api").weight()).isCloseTo(0.95, within(0.001));
        assertThat(affected.get("edge-gateway").weight()).isCloseTo(0.95 * 0.95, within(0.001));
    }

    @Test
    @DisplayName("weak async edges fall below the floor and are treated as unrelated")
    void prunesWeakPaths() {
        // notification-api is reachable from edge-gateway only via 0.95 * 0.20 = 0.19.
        var downstream = graph.dependenciesOf("edge-gateway", 3);

        assertThat(downstream.get("notification-api").weight()).isLessThan(0.25);
        assertThat(downstream.get("payment-gateway").weight()).isGreaterThan(0.8);
    }

    @Test
    @DisplayName("relatedness is symmetric across the direction of the dependency")
    void relatednessIgnoresDirection() {
        double downstream = graph.relatedness("checkout-api", "payment-gateway", 3);
        double upstream = graph.relatedness("payment-gateway", "checkout-api", 3);

        assertThat(downstream).isCloseTo(upstream, within(0.001));
        assertThat(graph.relatedness("checkout-api", "checkout-api", 3)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("depth limit stops traversal before the far side of the graph")
    void respectsDepthLimit() {
        assertThat(graph.blastRadius("orders-postgres", 1)).containsOnlyKeys("checkout-api");
    }

    @Test
    @DisplayName("cycles terminate and keep the strongest path")
    void handlesCycles() {
        ServiceGraph cyclic = ServiceGraph.from(
                List.of(edge("a", "b", 0.9), edge("b", "c", 0.9), edge("c", "a", 0.9), edge("a", "c", 0.5)));

        var reach = cyclic.dependenciesOf("a", 5);

        // a → b → c (0.81) beats the direct a → c edge (0.5).
        assertThat(reach.get("c").weight()).isCloseTo(0.81, within(0.001));
    }

    @Test
    @DisplayName("an unknown service simply has no neighbours")
    void unknownServiceIsIsolated() {
        assertThat(graph.blastRadius("does-not-exist", 3)).isEmpty();
        assertThat(graph.contains("does-not-exist")).isFalse();
    }

    private ServiceDependency edge(String source, String target, double criticality) {
        ServiceDependency dependency = new ServiceDependency("acme", source, target, ServiceDependency.Kind.SYNC);
        dependency.setCriticality(criticality);
        return dependency;
    }
}
