package routing;

import java.util.*;

import network.Link;
import network.NetworkTopology;
import network.Router;

public class NetworkState {

    private NetworkTopology topology;
    private double processingDelay;
    private DeadlineSatisfiability deadlineSatisfiability;

    public NetworkState(NetworkTopology topology, double processingDelay,
                        double maxBlockingPacketBytes) {
        this.topology = topology;
        this.processingDelay = processingDelay;
        this.deadlineSatisfiability =
                new DeadlineSatisfiability(maxBlockingPacketBytes);
    }

    public double getProcessingDelay() {
        return processingDelay;
    }

    public List<RouterObservation.NeighborState> getNeighborStates(
            Router current,
            Router destination,
            List<FlowHopAllocation> admittedAllocations) {

        List<RouterObservation.NeighborState> states = new ArrayList<>();

        for (Link link : topology.getLinks(current)) {
            Router neighbor = link.getDestination();

            int hopDistance = calculateHopDistance(neighbor, destination);

            double phi = calculateRemainingResource(
                    link,
                    admittedAllocations);

            RouterObservation.NeighborState state =
                    new RouterObservation.NeighborState(
                            neighbor,
                            link.getPropagationDelay(),
                            processingDelay,
                            hopDistance,
                            phi);

            states.add(state);
        }

        return states;
    }

    private int calculateHopDistance(
            Router source,
            Router destination) {

        if (source.equals(destination)) {
            return 0;
        }

        Queue<Router> queue = new LinkedList<>();
        Map<Router, Integer> distance = new HashMap<>();

        queue.add(source);
        distance.put(source, 0);

        while (!queue.isEmpty()) {
            Router current = queue.poll();

            for (Link link : topology.getLinks(current)) {
                Router next = link.getDestination();

                if (distance.containsKey(next)) {
                    continue;
                }

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

    private double calculateRemainingResource(
            Link link, List<FlowHopAllocation> admittedAllocations) {
        // Eq. (20) is evaluated only over reservations using this directed hop.
        List<FlowHopAllocation> sharingFlows = new ArrayList<>();
        if (admittedAllocations != null) {
            for (FlowHopAllocation allocation : admittedAllocations) {
                if (allocation.usesLink(link.getSource(), link.getDestination())) {
                    sharingFlows.add(allocation);
                }
            }
        }
        return deadlineSatisfiability.calculateRemainingResource(
                sharingFlows, link.getBandwidth());
    }
}
