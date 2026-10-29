package routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import network.NetworkTopology;
import network.Router;
import simulation.PacketSimulationResult;
import simulation.PacketSimulator;
import traffic.TrafficFlow;

/**
 * Reproducible Random-Feasible baseline with CEDF scheduling.
 * At each routing hop, samples uniformly at random strictly among the
 * feasible action candidates admitted by ActionSpace action masking.
 * Candidate choices must satisfy loop-free constraints, per-hop delay bounds,
 * and end-to-end deadline feasibility.
 * Admitted routes are validated against RouteCascade and CEDF schedulability.
 */
public class RandomFeasibleBaseline {

    public static final class Result {
        private final int requestedFlows;
        private final int admittedFlows;
        private final Map<Integer, MAPPOEpisodeResult> flowResults;
        private final PacketSimulationResult simulationResult;
        private final long seed;

        public Result(int requestedFlows, int admittedFlows,
                      Map<Integer, MAPPOEpisodeResult> flowResults,
                      PacketSimulationResult simulationResult,
                      long seed) {
            this.requestedFlows = requestedFlows;
            this.admittedFlows = admittedFlows;
            this.flowResults = Collections.unmodifiableMap(new LinkedHashMap<>(flowResults));
            this.simulationResult = simulationResult;
            this.seed = seed;
        }

        public int getRequestedFlows() { return requestedFlows; }
        public int getAdmittedFlows() { return admittedFlows; }
        public Map<Integer, MAPPOEpisodeResult> getFlowResults() { return flowResults; }
        public PacketSimulationResult getSimulationResult() { return simulationResult; }
        public long getSeed() { return seed; }

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

    public static Result run(NetworkTopology topology,
                             List<TrafficFlow> flows,
                             double processingDelayMs,
                             double flowDeadlineMs,
                             int queueCapacity,
                             double maxBlockingPacketBytes,
                             int budgetLevels,
                             long seed) {
        if (topology == null || flows == null) {
            throw new IllegalArgumentException("Topology and flows are required");
        }
        if (budgetLevels <= 0) {
            throw new IllegalArgumentException("Budget levels must be positive");
        }

        Random rng = new Random(seed);
        RouteCascade cascade = new RouteCascade(topology, processingDelayMs, maxBlockingPacketBytes);
        Map<Integer, MAPPOEpisodeResult> flowResults = new LinkedHashMap<>();
        List<PacketSimulator.FlowInput> admittedInputs = new ArrayList<>();
        int admittedCount = 0;

        for (TrafficFlow flow : flows) {
            Router current = flow.getSource();
            Router destination = flow.getDestination();
            Set<Router> visited = new HashSet<>();
            visited.add(current);
            List<PathDecision> decisions = new ArrayList<>();
            double accumulatedBudget = 0.0;
            boolean reached = false;

            int maxHops = topology.getRouterCount();
            for (int hop = 0; hop < maxHops; hop++) {
                if (current.equals(destination)) {
                    reached = true;
                    break;
                }

                List<BudgetAction> actions = ActionSpace.getActions(
                        current, flow, topology, budgetLevels);
                boolean[] mask = ActionSpace.getActionMask(
                        actions, current, flow, topology, budgetLevels,
                        visited, accumulatedBudget, processingDelayMs);

                List<Integer> feasibleIndices = new ArrayList<>();
                for (int i = 0; i < mask.length; i++) {
                    if (mask[i]) {
                        feasibleIndices.add(i);
                    }
                }

                if (feasibleIndices.isEmpty()) {
                    // No feasible masked action can continue toward the destination
                    break;
                }

                // Uniformly sample strictly among feasible candidates
                int chosenIndex = feasibleIndices.get(rng.nextInt(feasibleIndices.size()));
                BudgetAction chosenAction = actions.get(chosenIndex);

                decisions.add(new PathDecision(
                        current, chosenAction.getNextHop(), chosenAction.getDeadlineBudget()));
                visited.add(chosenAction.getNextHop());
                accumulatedBudget += chosenAction.getDeadlineBudget();
                current = chosenAction.getNextHop();

                if (current.equals(destination)) {
                    reached = true;
                    break;
                }
            }

            if (reached && !decisions.isEmpty()) {
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
            } else {
                MAPPOEpisodeResult episodeResult = new MAPPOEpisodeResult(
                        false, false, decisions, new TrajectoryBuffer(), 0.0,
                        "NO_FEASIBLE_ROUTE", "No feasible masked action reached destination");
                flowResults.put(flow.getFlowId(), episodeResult);
            }
        }

        PacketSimulator simulator = new PacketSimulator(
                topology, admittedInputs, processingDelayMs,
                flowDeadlineMs > 0.0 ? flowDeadlineMs : 10.0,
                queueCapacity > 0 ? queueCapacity : 256);
        PacketSimulationResult simulationResult = simulator.simulate();

        return new Result(flows.size(), admittedCount, flowResults, simulationResult, seed);
    }
}
