
package routing;

import java.util.*;
import network.*;
import traffic.TrafficFlow;

public class ObservationEncoder {

    private NetworkTopology topology;
    private int nodeCount;
    private int budgetLevels;

    public ObservationEncoder(NetworkTopology topology, int budgetLevels) {
        this.topology = topology;
        this.nodeCount = topology.getRouterCount();
        this.budgetLevels = budgetLevels;
    }

    public double[] encodeActorObservation(RouterObservation observation) {
        TrafficFlow flow = observation.getFlow();
        List<RouterObservation.NeighborState> neighbors =
                observation.getNeighborStates();

        int neighborCount = neighbors.size();

        int size = getActorInputSize(neighborCount);

        double[] vector = new double[size];
        int index = 0;

        index = encodeTopology(vector, index);
        index = encodeNeighbors(vector, index, neighbors);
        index = encodeFlow(vector, index, flow);
        index = encodePreviousPath(vector, index, observation);
        index = encodeNeighborDelays(vector, index, neighbors);

        if (index != vector.length) {
            throw new IllegalStateException("Actor observation encoding dimension mismatch");
        }

        return vector;
    }

    /** Paper-shaped actor features plus explicit per-neighbor processing/propagation delays. */
    public int getActorInputSize(int neighborCount) {
        return nodeCount * nodeCount
                + 2 * nodeCount * neighborCount
                + 4 * nodeCount
                + 3
                + 2 * neighborCount;
    }

    private int encodeNeighborDelays(
            double[] vector,
            int index,
            List<RouterObservation.NeighborState> neighbors) {
        for (RouterObservation.NeighborState neighbor : neighbors) {
            vector[index++] = normalize(neighbor.getProcessingDelay());
            vector[index++] = normalize(neighbor.getPropagationDelay());
        }
        return index;
    }
    private int encodeTopology(double[] vector, int index) {
    List<Router> routers = getRouters();

    for (Router source : routers) {
        for (Router destination : routers) {
            vector[index++] = hasLink(source, destination) ? 1.0 : 0.0;
        }
    }

    return index;
}
private boolean hasLink(
        Router source,
        Router destination) {

    for (Link link : topology.getLinks(source)) {
        if (link.getDestination().equals(destination)) return true;
    }

    return false;
}
    private int getHopDistance(
        Router source,
        Router destination) {

    if (source.equals(destination)) return 0;

    Queue<Router> queue = new LinkedList<>();
    Map<Router, Integer> distance = new HashMap<>();

    queue.add(source);
    distance.put(source, 0);

    while (!queue.isEmpty()) {
        Router current = queue.poll();

        for (Link link : topology.getLinks(current)) {
            Router next = link.getDestination();

            if (distance.containsKey(next)) continue;

            int nextDistance = distance.get(current) + 1;

            if (next.equals(destination)) {
                return nextDistance;
            }

            distance.put(next, nextDistance);
            queue.add(next);
        }
    }

    return Integer.MAX_VALUE;
}
    private int encodeNeighbors(
        double[] vector,
        int index,
        List<RouterObservation.NeighborState> neighbors) {

    for (RouterObservation.NeighborState state : neighbors) {

        for (Router router : getRouters()) {
            int distance = getHopDistance(
                    state.getNeighbor(),
                    router);

            vector[index++] = distance == Integer.MAX_VALUE ? -1.0 : distance;
        }

        for (Router router : getRouters()) {
            vector[index++] =
                    normalize(state.getRemainingDeadlineResource());
        }
    }

    return index;
}

    private int encodeFlow(
        double[] vector,
        int index,
        TrafficFlow flow) {

    int sourceIndex = getRouterIndex(flow.getSource());
    int destinationIndex = getRouterIndex(flow.getDestination());

    vector[index + sourceIndex] = 1.0;
    index += nodeCount;

    vector[index + destinationIndex] = 1.0;
    index += nodeCount;

    vector[index++] = normalize(flow.getRateMbps());
    vector[index++] = normalize(flow.getBurstKB());
    vector[index++] = normalize(flow.getDeadline());

    return index;
}
    private int encodePreviousPath(
        double[] vector,
        int index,
        RouterObservation observation) {

    for (PathDecision decision : observation.getPreviousDecisions()) {
        int routerIndex = getRouterIndex(decision.getRouter());

        if (routerIndex >= 0) {
            vector[index + routerIndex] = 1.0;
        }
    }

    index += nodeCount;

    for (PathDecision decision : observation.getPreviousDecisions()) {
        int routerIndex = getRouterIndex(decision.getRouter());

        if (routerIndex >= 0) {
            vector[index + routerIndex] =
                    normalize(decision.getDeadlineBudget());
        }
    }

    return index + nodeCount;
}
    private List<Router> getRouters() {
        List<Router> routers = new ArrayList<>();
        routers.addAll(topology.getRouters());
        routers.sort(Comparator.comparingInt(Router::getId));
        return routers;
    }

    private int getRouterIndex(Router router) {
        List<Router> routers = getRouters();

        for (int i = 0; i < routers.size(); i++) {
            if (routers.get(i).equals(router)) {
                return i;
            }
        }

        return -1;
    }

    private double normalize(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }

        return value;
    }
}
