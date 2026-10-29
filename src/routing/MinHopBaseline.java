package routing;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import network.Link;
import network.NetworkTopology;
import network.Router;
import simulation.PacketSimulationResult;
import simulation.PacketSimulator;
import traffic.TrafficFlow;

/**
 * Deterministic Minimum-Hop baseline with CEDF scheduling.
 * Finds the route with the fewest hops using breadth-first search (BFS).
 * Uses deterministic tie-breaking (lexicographical router ID order).
 * Equidistributes the end-to-end deadline budget uniformly across hops (like MAPPO-ED),
 * and validates admission through RouteCascade and CEDF schedulability.
 */
public class MinHopBaseline {

    public static final class Result {
        private final int requestedFlows;
        private final int admittedFlows;
        private final Map<Integer, MAPPOEpisodeResult> flowResults;
        private final PacketSimulationResult simulationResult;

        public Result(int requestedFlows, int admittedFlows,
                      Map<Integer, MAPPOEpisodeResult> flowResults,
                      PacketSimulationResult simulationResult) {
            this.requestedFlows = requestedFlows;
            this.admittedFlows = admittedFlows;
            this.flowResults = Collections.unmodifiableMap(new LinkedHashMap<>(flowResults));
            this.simulationResult = simulationResult;
        }

        public int getRequestedFlows() { return requestedFlows; }
        public int getAdmittedFlows() { return admittedFlows; }
        public Map<Integer, MAPPOEpisodeResult> getFlowResults() { return flowResults; }
        public PacketSimulationResult getSimulationResult() { return simulationResult; }

        public int getGeneratedPackets() {
            return simulationResult != null ? simulationResult.getTotalPackets() : 0;
        }
        public int getDeliveredPackets() {
            return simulationResult != null ? simulationResult.getDeliveredPackets() : 0;
        }
        public int getTimelyPackets() {
            return simulationResult != null ? simulationResult.getPacketsDeliveredBeforeDeadline() : 0;
        }
        public int getLatePackets() {
            return simulationResult != null ? simulationResult.getPacketsMissingDeadline() : 0;
        }
        public int getDroppedPackets() {
            return simulationResult != null ? simulationResult.getDroppedPackets() : 0;
        }
        public double getTimelyDeliveryRatio() {
            return simulationResult != null ? simulationResult.getTimelyDeliveryRatio() : 0.0;
        }
        public double getAverageEndToEndDelay() {
            return simulationResult != null ? simulationResult.getAverageEndToEndDelay() : 0.0;
        }
    }

    /**
     * Finds the minimum-hop path from source to destination using BFS.
     * Ties between paths of equal hop count are broken deterministically by
     * ascending router ID.
     */
    public static List<Router> findMinHopPath(NetworkTopology topology, Router source, Router destination) {
        if (topology == null || source == null || destination == null) {
            return Collections.emptyList();
        }
        if (source.equals(destination)) {
            return Collections.singletonList(source);
        }

        Queue<Router> queue = new ArrayDeque<>();
        Map<Router, Integer> hopDist = new HashMap<>();
        Map<Router, Router> predecessor = new HashMap<>();

        hopDist.put(source, 0);
        queue.add(source);

        while (!queue.isEmpty()) {
            Router curr = queue.poll();
            int nextHops = hopDist.get(curr) + 1;

            List<Link> links = topology.getLinks(curr);
            if (links == null) continue;

            // Sort outgoing links deterministically by destination router ID
            List<Link> sortedLinks = new ArrayList<>(links);
            sortedLinks.sort(Comparator.comparingInt(l -> l.getDestination().getId()));

            for (Link link : sortedLinks) {
                Router neighbor = link.getDestination();
                if (!hopDist.containsKey(neighbor)) {
                    hopDist.put(neighbor, nextHops);
                    predecessor.put(neighbor, curr);
                    queue.add(neighbor);
                    if (neighbor.equals(destination)) {
                        break;
                    }
                }
            }
            if (hopDist.containsKey(destination)) {
                break;
            }
        }

        if (!predecessor.containsKey(destination)) {
            return Collections.emptyList();
        }

        List<Router> path = new ArrayList<>();
        for (Router curr = destination; curr != null; curr = predecessor.get(curr)) {
            path.add(0, curr);
            if (curr.equals(source)) break;
        }
        return path;
    }

    public static Result run(NetworkTopology topology,
                             List<TrafficFlow> flows,
                             double processingDelayMs,
                             double flowDeadlineMs,
                             int queueCapacity,
                             double maxBlockingPacketBytes) {
        if (topology == null || flows == null) {
            throw new IllegalArgumentException("Topology and flows are required");
        }

        RouteCascade cascade = new RouteCascade(topology, processingDelayMs, maxBlockingPacketBytes);
        Map<Integer, MAPPOEpisodeResult> flowResults = new LinkedHashMap<>();
        List<PacketSimulator.FlowInput> admittedInputs = new ArrayList<>();
        int admittedCount = 0;

        for (TrafficFlow flow : flows) {
            List<Router> path = findMinHopPath(topology, flow.getSource(), flow.getDestination());
            if (path.size() < 2) {
                flowResults.put(flow.getFlowId(), new MAPPOEpisodeResult(
                        false, false, Collections.emptyList(), new TrajectoryBuffer(), 0.0,
                        "NO_PATH_FOUND", "No physical path between endpoints"));
                continue;
            }

            int hops = path.size() - 1;
            double netDeadline = flow.getDeadline() - flow.getTotalEndpointAccessDelayMs();
            // Equal deadline budget allocation across hops (consistent with MAPPO-ED)
            double hopBudget = netDeadline > 0.0 ? netDeadline / hops : 0.0;

            List<PathDecision> decisions = new ArrayList<>();
            for (int i = 0; i < hops; i++) {
                decisions.add(new PathDecision(path.get(i), path.get(i + 1), hopBudget));
            }

            boolean admitted = cascade.admitRoute(flow, decisions);
            String failReason = admitted ? null : cascade.getQualificationFailureReason(flow, decisions);

            MAPPOEpisodeResult episodeResult = new MAPPOEpisodeResult(
                    true, admitted, decisions, new TrajectoryBuffer(),
                    admitted ? 1.0 : 0.0, "DESTINATION_REACHED", failReason);
            flowResults.put(flow.getFlowId(), episodeResult);

            if (admitted) {
                admittedCount++;
                admittedInputs.add(new PacketSimulator.FlowInput(flow, episodeResult));
            }
        }

        PacketSimulator simulator = new PacketSimulator(
                topology, admittedInputs, processingDelayMs,
                flowDeadlineMs > 0.0 ? flowDeadlineMs : 10.0,
                queueCapacity > 0 ? queueCapacity : 256);
        PacketSimulationResult simulationResult = simulator.simulate();

        return new Result(flows.size(), admittedCount, flowResults, simulationResult);
    }
}
