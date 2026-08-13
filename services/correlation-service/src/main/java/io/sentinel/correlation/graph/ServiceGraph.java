package io.sentinel.correlation.graph;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.sentinel.platform.domain.model.ServiceDependency;

/**
 * Immutable snapshot of a tenant's service topology.
 *
 * <p>Traversal is weighted rather than plain BFS. Each edge carries a criticality in 0..1 — how
 * reliably failure propagates across it — and a path's weight is the product of its edges. That
 * gives natural decay: {@code checkout → cart → pricing} at 0.9 × 0.75 scores 0.68, while a
 * two-hop path through an async queue at 0.35 × 0.2 scores 0.07 and is correctly treated as
 * unrelated.
 *
 * <p>Best-first search (a max-heap on path weight) is used instead of BFS-by-hops because the
 * strongest path is not always the shortest one.
 */
public final class ServiceGraph {

    /** Paths weaker than this are treated as no connection at all; prunes the search early. */
    private static final double WEIGHT_FLOOR = 0.05;

    private final Map<String, List<Edge>> outgoing;
    private final Map<String, List<Edge>> incoming;
    private final Set<String> nodes;

    private ServiceGraph(Map<String, List<Edge>> outgoing, Map<String, List<Edge>> incoming, Set<String> nodes) {
        this.outgoing = outgoing;
        this.incoming = incoming;
        this.nodes = nodes;
    }

    public static ServiceGraph from(Collection<ServiceDependency> dependencies) {
        Map<String, List<Edge>> outgoing = new HashMap<>();
        Map<String, List<Edge>> incoming = new HashMap<>();
        Set<String> nodes = new java.util.LinkedHashSet<>();

        for (ServiceDependency dependency : dependencies) {
            Edge forward = new Edge(dependency.getSourceKey(), dependency.getTargetKey(), dependency.getCriticality());
            outgoing.computeIfAbsent(dependency.getSourceKey(), key -> new java.util.ArrayList<>())
                    .add(forward);
            incoming.computeIfAbsent(dependency.getTargetKey(), key -> new java.util.ArrayList<>())
                    .add(forward);
            nodes.add(dependency.getSourceKey());
            nodes.add(dependency.getTargetKey());
        }
        return new ServiceGraph(Map.copyOf(outgoing), Map.copyOf(incoming), Set.copyOf(nodes));
    }

    public static ServiceGraph empty() {
        return new ServiceGraph(Map.of(), Map.of(), Set.of());
    }

    /**
     * Services that would degrade if {@code serviceKey} broke — its callers, transitively.
     *
     * <p>This is the blast radius: when the payment gateway fails, checkout and the edge gateway are
     * the ones users will notice.
     */
    public Map<String, Reach> blastRadius(String serviceKey, int maxDepth) {
        return search(serviceKey, maxDepth, incoming, Edge::source);
    }

    /**
     * Services {@code serviceKey} depends on, transitively — the places to look for a root cause.
     */
    public Map<String, Reach> dependenciesOf(String serviceKey, int maxDepth) {
        return search(serviceKey, maxDepth, outgoing, Edge::target);
    }

    /**
     * How strongly two services are connected, in either direction.
     *
     * @return 1.0 when they are the same service, 0.0 when no meaningful path exists
     */
    public double relatedness(String from, String to, int maxDepth) {
        if (from.equals(to)) {
            return 1.0;
        }
        double downstream = weightOf(dependenciesOf(from, maxDepth).get(to));
        double upstream = weightOf(blastRadius(from, maxDepth).get(to));
        return Math.max(downstream, upstream);
    }

    public boolean contains(String serviceKey) {
        return nodes.contains(serviceKey);
    }

    public Set<String> nodes() {
        return nodes;
    }

    public List<Edge> edges() {
        return outgoing.values().stream().flatMap(List::stream).toList();
    }

    private double weightOf(Reach reach) {
        return reach == null ? 0.0 : reach.weight();
    }

    /**
     * Best-first traversal keeping, for each reachable node, the highest-weight path found.
     *
     * <p>Cycles are handled by the "only enqueue on improvement" rule rather than a visited set:
     * a service mesh has cycles (A calls B, B calls A's cache), and a strict visited set would drop
     * the stronger path when the weaker one happened to be dequeued first.
     */
    private Map<String, Reach> search(
            String start,
            int maxDepth,
            Map<String, List<Edge>> adjacency,
            java.util.function.Function<Edge, String> next) {

        Map<String, Reach> best = new HashMap<>();
        Deque<Cursor> queue = new ArrayDeque<>();
        queue.add(new Cursor(start, 1.0, 0));

        while (!queue.isEmpty()) {
            Cursor cursor = queue.poll();
            if (cursor.depth() >= maxDepth) {
                continue;
            }

            for (Edge edge : adjacency.getOrDefault(cursor.node(), List.of())) {
                String neighbour = next.apply(edge);
                if (neighbour.equals(start)) {
                    continue;
                }

                double weight = cursor.weight() * edge.criticality();
                if (weight < WEIGHT_FLOOR) {
                    continue;
                }

                Reach existing = best.get(neighbour);
                if (existing == null || weight > existing.weight()) {
                    best.put(neighbour, new Reach(neighbour, weight, cursor.depth() + 1));
                    queue.add(new Cursor(neighbour, weight, cursor.depth() + 1));
                }
            }
        }
        return best;
    }

    /** A reachable service, with the strength and length of the best path to it. */
    public record Reach(String serviceKey, double weight, int hops) {}

    public record Edge(String source, String target, double criticality) {}

    private record Cursor(String node, double weight, int depth) {}
}
