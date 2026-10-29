package routing;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import network.Link;
import network.NetworkTopology;
import network.Router;
import traffic.TrafficFlow;

/**
 * Fixed-size centralized critic state, independent of the acting router.
 * It contains the directed graph, all-pairs hop distances, configured directed
 * link delays/capacities, Eq. (20) remaining-resource observations, processing
 * delays, the current flow, and the path/budgets selected so far.
 *
 * This is an explicit project encoding of the paper's global state, not a claim
 * that its vector layout or dimension reproduces the paper's neural input.
 */
public final class GlobalStateEncoder {
    private final NetworkTopology topology;
    private final NetworkState networkState;
    private final RouteCascade routeCascade;
    private final List<Router> routers;
    private final Map<Router, Integer> indices;
    private final int nodeCount;
    private final int stateSize;

    public GlobalStateEncoder(NetworkTopology topology, NetworkState networkState,
                              RouteCascade routeCascade) {
        if (topology == null || networkState == null || routeCascade == null) {
            throw new IllegalArgumentException("Topology, network state, and route cascade are required");
        }
        this.topology = topology;
        this.networkState = networkState;
        this.routeCascade = routeCascade;
        this.routers = new ArrayList<>(topology.getRouters());
        this.routers.sort((left, right) -> Integer.compare(left.getId(), right.getId()));
        this.nodeCount = routers.size();
        this.indices = new HashMap<>();
        for (int i = 0; i < routers.size(); i++) {
            indices.put(routers.get(i), i);
        }
        // adjacency, distance, propagation, capacity, Phi, Phi-status matrices;
        // per-router processing delay; endpoints and previous route; 3 flow values.
        this.stateSize = 6 * nodeCount * nodeCount + 5 * nodeCount + 3;
    }

    public int getStateSize() {
        return stateSize;
    }

    public double[] encode(TrafficFlow flow, List<PathDecision> previousDecisions) {
        if (flow == null || !indices.containsKey(flow.getSource())
                || !indices.containsKey(flow.getDestination())) {
            throw new IllegalArgumentException("Flow endpoints must belong to the topology");
        }
        List<PathDecision> decisions = previousDecisions == null
                ? java.util.Collections.emptyList() : previousDecisions;
        double[] state = new double[stateSize];
        int index = 0;

        for (Router source : routers) {
            for (Router destination : routers) {
                state[index++] = findLink(source, destination) == null ? 0.0 : 1.0;
            }
        }
        for (Router source : routers) {
            for (Router destination : routers) {
                int distance = hopDistance(source, destination);
                state[index++] = distance == Integer.MAX_VALUE ? -1.0 : distance;
            }
        }
        for (Router source : routers) {
            for (Router destination : routers) {
                Link link = findLink(source, destination);
                state[index++] = link == null ? 0.0 : link.getPropagationDelay();
            }
        }
        for (Router source : routers) {
            for (Router destination : routers) {
                Link link = findLink(source, destination);
                state[index++] = link == null ? 0.0 : link.getBandwidth();
            }
        }

        // Eq. (20) contributes per-directed-link remaining-resource values to this project-defined state vector.
        List<FlowHopAllocation> admitted = routeCascade.getAdmittedAllocations();
        double[][] remainingResources = new double[nodeCount][nodeCount];
        for (double[] row : remainingResources) Arrays.fill(row, Double.POSITIVE_INFINITY);
        for (int sourceIndex = 0; sourceIndex < nodeCount; sourceIndex++) {
            Router source = routers.get(sourceIndex);
            for (RouterObservation.NeighborState neighbor : networkState.getNeighborStates(
                    source, flow.getDestination(), admitted)) {
                Integer destinationIndex = indices.get(neighbor.getNeighbor());
                if (destinationIndex != null) {
                    remainingResources[sourceIndex][destinationIndex] =
                            neighbor.getRemainingDeadlineResource();
                }
            }
        }
        for (Router source : routers) {
            int sourceIndex = indices.get(source);
            for (Router destination : routers) {
                double phi = remainingResources[sourceIndex][indices.get(destination)];
                state[index++] = Double.isFinite(phi) ? phi : 0.0;
            }
        }
        for (Router source : routers) {
            int sourceIndex = indices.get(source);
            for (Router destination : routers) {
                double phi = remainingResources[sourceIndex][indices.get(destination)];
                // 0 = no finite Eq. (20) observation; 1 = finite; -1 = negative infinity.
                state[index++] = Double.isFinite(phi) ? 1.0
                        : (phi < 0.0 ? -1.0 : 0.0);
            }
        }
        for (int i = 0; i < nodeCount; i++) {
            state[index++] = networkState.getProcessingDelay();
        }

        state[index + indices.get(flow.getSource())] = 1.0;
        index += nodeCount;
        state[index + indices.get(flow.getDestination())] = 1.0;
        index += nodeCount;

        int visitedOffset = index;
        int budgetOffset = index + nodeCount;
        state[visitedOffset + indices.get(flow.getSource())] = 1.0;
        for (PathDecision decision : decisions) {
            Integer routerIndex = indices.get(decision.getRouter());
            Integer nextIndex = indices.get(decision.getNextHop());
            if (routerIndex == null || nextIndex == null) {
                throw new IllegalArgumentException("Path decision routers must belong to the topology");
            }
            state[visitedOffset + routerIndex] = 1.0;
            state[visitedOffset + nextIndex] = 1.0;
            state[budgetOffset + routerIndex] = decision.getDeadlineBudget();
        }
        index += 2 * nodeCount;

        state[index++] = flow.getRateMbps();
        state[index++] = flow.getBurstKB();
        state[index++] = flow.getDeadline();
        if (index != state.length) {
            throw new IllegalStateException("Global state encoding dimension mismatch");
        }
        return state;
    }

    /**
     * Encodes the environment after a request has been resolved and before
     * another request has been introduced. The network/resource portion is
     * taken from the current state; request-specific and partial-route fields
     * are zero because no request is pending. This keeps the critic input
     * dimension fixed while providing a meaningful bootstrap state at a
     * truncated episode boundary.
     *
     * The paper does not specify a concrete global-state vector, so the
     * no-pending-request marker is a project representation choice.
     */
    public double[] encodeNoPendingRequest(TrafficFlow referenceFlow) {
        double[] state = encode(referenceFlow, java.util.Collections.<PathDecision>emptyList());
        int requestStateOffset = 6 * nodeCount * nodeCount + nodeCount;
        Arrays.fill(state, requestStateOffset, state.length, 0.0);
        return state;
    }

    private Link findLink(Router source, Router destination) {
        List<Link> outgoing = topology.getLinks(source);
        if (outgoing == null) return null;
        for (Link link : outgoing) {
            if (link.getSource().equals(source)
                    && link.getDestination().equals(destination)) return link;
        }
        return null;
    }

    private int hopDistance(Router source, Router destination) {
        if (source.equals(destination)) return 0;
        ArrayDeque<Router> queue = new ArrayDeque<>();
        Map<Router, Integer> distances = new HashMap<>();
        queue.add(source);
        distances.put(source, 0);
        while (!queue.isEmpty()) {
            Router current = queue.removeFirst();
            List<Link> outgoing = topology.getLinks(current);
            if (outgoing == null) continue;
            for (Link link : outgoing) {
                Router next = link.getDestination();
                if (distances.containsKey(next)) continue;
                int nextDistance = distances.get(current) + 1;
                if (next.equals(destination)) return nextDistance;
                distances.put(next, nextDistance);
                queue.addLast(next);
            }
        }
        return Integer.MAX_VALUE;
    }
}
