package routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import network.Link;
import network.NetworkTopology;
import network.Router;
import simulation.PacketSimulationResult;
import simulation.PacketSimulator;
import traffic.TrafficFlow;

/**
 * Deterministic Shortest-Path baseline for DetWAN comparison.
 * Finds minimum-propagation-delay paths and uniformly partitions
 * available deadline budget across hops, enforcing all RouteCascade
 * and CEDF admission checks.
 */
public class ShortestPathBaseline {

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

    public static List<Router> findShortestPath(NetworkTopology topology, Router source, Router destination) {
        if (topology == null || source == null || destination == null) {
            return Collections.emptyList();
        }
        if (source.equals(destination)) {
            return Collections.singletonList(source);
        }

        Map<Router, Double> dist = new HashMap<>();
        Map<Router, Router> prev = new HashMap<>();
        PriorityQueue<Router> pq = new PriorityQueue<>(Comparator.comparingDouble(r -> dist.getOrDefault(r, Double.MAX_VALUE)));

        for (Router r : topology.getRouters()) {
            dist.put(r, Double.MAX_VALUE);
        }
        dist.put(source, 0.0);
        pq.add(source);

        while (!pq.isEmpty()) {
            Router u = pq.poll();
            if (u.equals(destination)) break;
            double d = dist.get(u);
            List<Link> links = topology.getLinks(u);
            if (links == null) continue;
            for (Link link : links) {
                Router v = link.getDestination();
                double newDist = d + link.getPropagationDelay();
                if (newDist < dist.getOrDefault(v, Double.MAX_VALUE)) {
                    dist.put(v, newDist);
                    prev.put(v, u);
                    pq.remove(v);
                    pq.add(v);
                }
            }
        }

        if (!prev.containsKey(destination)) {
            return Collections.emptyList();
        }

        List<Router> path = new ArrayList<>();
        for (Router curr = destination; curr != null; curr = prev.get(curr)) {
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
            List<Router> path = findShortestPath(topology, flow.getSource(), flow.getDestination());
            if (path.size() < 2) {
                flowResults.put(flow.getFlowId(), new MAPPOEpisodeResult(
                        false, false, Collections.emptyList(), new TrajectoryBuffer(), 0.0,
                        "NO_PATH_FOUND", "No physical path between endpoints"));
                continue;
            }

            int hops = path.size() - 1;
            double netDeadline = flow.getDeadline() - flow.getTotalEndpointAccessDelayMs();
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
