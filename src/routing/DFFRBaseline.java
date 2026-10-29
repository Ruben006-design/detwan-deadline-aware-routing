package routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import network.NetworkTopology;
import network.Router;
import simulation.PacketSimulationResult;
import simulation.PacketSimulator;
import traffic.TrafficFlow;

/**
 * Deterministic feasibility-first routing baseline (DFFR).
 *
 * Searches simple route/budget candidates by increasing hop count. The search
 * is exhaustive within each hop count: a simple route has at most V-1 hops,
 * and only actions retained by ActionSpace's existing deadline/lookahead mask
 * are explored. This gives the fewest-hop feasible candidate, with router-ID
 * and then local-budget tie-breaking. The number of candidates can still grow
 * exponentially on dense topologies; there is deliberately no silent search
 * cap that would invalidate the fewest-feasible-hop guarantee.
 */
public final class DFFRBaseline {

    private DFFRBaseline() { }

    public static final class Result {
        private final int requestedFlows;
        private final int admittedFlows;
        private final Map<Integer, MAPPOEpisodeResult> flowResults;
        private final PacketSimulationResult simulationResult;
        private final List<FlowHopAllocation> committedAllocations;

        private Result(int requestedFlows, int admittedFlows,
                       Map<Integer, MAPPOEpisodeResult> flowResults,
                       PacketSimulationResult simulationResult,
                       List<FlowHopAllocation> committedAllocations) {
            this.requestedFlows = requestedFlows;
            this.admittedFlows = admittedFlows;
            this.flowResults = Collections.unmodifiableMap(new LinkedHashMap<>(flowResults));
            this.simulationResult = simulationResult;
            this.committedAllocations = Collections.unmodifiableList(
                    new ArrayList<>(committedAllocations));
        }

        public int getRequestedFlows() { return requestedFlows; }
        public int getAdmittedFlows() { return admittedFlows; }
        public Map<Integer, MAPPOEpisodeResult> getFlowResults() { return flowResults; }
        public PacketSimulationResult getSimulationResult() { return simulationResult; }
        /** Snapshot used to verify that only selected routes committed reservations. */
        public List<FlowHopAllocation> getCommittedAllocations() { return committedAllocations; }
        public int getGeneratedPackets() { return simulationResult.getTotalPackets(); }
        public int getDeliveredPackets() { return simulationResult.getDeliveredPackets(); }
        public int getTimelyPackets() { return simulationResult.getPacketsDeliveredBeforeDeadline(); }
        public int getLatePackets() { return simulationResult.getPacketsMissingDeadline(); }
        public int getDroppedPackets() { return simulationResult.getDroppedPackets(); }
        public double getTimelyDeliveryRatio() { return simulationResult.getTimelyDeliveryRatio(); }
        public double getAverageEndToEndDelay() { return simulationResult.getAverageEndToEndDelay(); }
    }

    /**
     * Routes flows in input order so each accepted flow's RouteCascade
     * reservations constrain subsequent requests, like the existing baselines.
     */
    public static Result run(NetworkTopology topology,
                             List<TrafficFlow> flows,
                             double processingDelayMs,
                             double simulationWindowMs,
                             int queueCapacity,
                             double maxBlockingPacketBytes,
                             int budgetLevels) {
        if (topology == null || flows == null) {
            throw new IllegalArgumentException("Topology and flows are required");
        }
        if (budgetLevels <= 0) {
            throw new IllegalArgumentException("Budget levels must be positive");
        }

        RouteCascade cascade = new RouteCascade(
                topology, processingDelayMs, maxBlockingPacketBytes);
        Map<Integer, MAPPOEpisodeResult> flowResults = new LinkedHashMap<>();
        List<PacketSimulator.FlowInput> admittedInputs = new ArrayList<>();
        int admittedCount = 0;

        for (TrafficFlow flow : flows) {
            SearchState search = new SearchState(flow, topology, cascade,
                    processingDelayMs, budgetLevels);
            List<PathDecision> selected = null;
            String failureReason = null;

            if (flow.getSource() == null || flow.getDestination() == null
                    || flow.getSource().equals(flow.getDestination())) {
                failureReason = "INVALID_SOURCE_OR_DESTINATION";
            } else if (ActionSpace.getMinimumRemainingHops(
                    flow.getSource(), flow.getDestination(), topology,
                    Collections.singleton(flow.getSource())) == Integer.MAX_VALUE) {
                failureReason = "NO_PATH_FOUND";
            } else {
                // A loop-free path cannot visit more than V routers, so V-1 is
                // a complete finite bound on hop count, not a heuristic cap.
                for (int hopCount = 1; hopCount < topology.getRouterCount(); hopCount++) {
                    search.bestFeasible = null;
                    List<PathDecision> prefix = new ArrayList<>();
                    Set<Router> visited = new HashSet<>();
                    visited.add(flow.getSource());
                    search.enumerate(flow.getSource(), visited, prefix,
                            0.0, hopCount);
                    if (search.bestFeasible != null) {
                        selected = search.bestFeasible;
                        break;
                    }
                }
                if (selected == null) {
                    failureReason = search.bestFailureReason != null
                            ? search.bestFailureReason
                            : "NO_FEASIBLE_ROUTE: no complete route with a feasible discrete budget allocation";
                }
            }

            if (selected != null) {
                // Qualification above is non-mutating. Commit exactly once for
                // the chosen candidate; RouteCascade revalidates atomically.
                if (cascade.admitRoute(flow, selected)) {
                    admittedCount++;
                    MAPPOEpisodeResult result = new MAPPOEpisodeResult(
                            true, true, selected, new TrajectoryBuffer(), 1.0,
                            "DESTINATION_REACHED", null);
                    flowResults.put(flow.getFlowId(), result);
                    admittedInputs.add(new PacketSimulator.FlowInput(flow, result));
                    continue;
                }
                failureReason = "RESERVATION_COMMIT_FAILED: "
                        + cascade.getQualificationFailureReason(flow, selected);
            }

            List<PathDecision> rejectedCandidate = search.bestFailedCandidate;
            boolean reached = rejectedCandidate != null;
            flowResults.put(flow.getFlowId(), new MAPPOEpisodeResult(
                    reached, false,
                    reached ? rejectedCandidate : Collections.emptyList(),
                    new TrajectoryBuffer(), 0.0,
                    reached ? "DESTINATION_REACHED" : "NO_FEASIBLE_ROUTE",
                    failureReason == null ? "NO_FEASIBLE_ROUTE" : failureReason));
        }

        PacketSimulator simulator = new PacketSimulator(
                topology, admittedInputs, processingDelayMs,
                simulationWindowMs, queueCapacity);
        PacketSimulationResult simulationResult = simulator.simulate();
        return new Result(flows.size(), admittedCount, flowResults,
                simulationResult, cascade.getAdmittedAllocations());
    }

    private static final class SearchState {
        final TrafficFlow flow;
        final NetworkTopology topology;
        final RouteCascade cascade;
        final double processingDelayMs;
        final int budgetLevels;
        final DeadlineBudget discreteBudget;
        List<PathDecision> bestFeasible;
        List<PathDecision> bestFailedCandidate;
        String bestFailureReason;
        int bestFailureHops = Integer.MAX_VALUE;

        SearchState(TrafficFlow flow, NetworkTopology topology, RouteCascade cascade,
                    double processingDelayMs, int budgetLevels) {
            this.flow = flow;
            this.topology = topology;
            this.cascade = cascade;
            this.processingDelayMs = processingDelayMs;
            this.budgetLevels = budgetLevels;
            this.discreteBudget = new DeadlineBudget(flow.getDeadline(), budgetLevels);
        }

        void enumerate(Router current, Set<Router> visited,
                       List<PathDecision> decisions, double accumulatedBudget,
                       int targetHopCount) {
            if (decisions.size() >= targetHopCount || current.equals(flow.getDestination())) {
                return;
            }

            List<BudgetAction> actions = ActionSpace.getActions(
                    current, flow, topology, budgetLevels);
            boolean[] mask = ActionSpace.getActionMask(
                    actions, current, flow, topology, budgetLevels,
                    visited, accumulatedBudget, processingDelayMs);
            for (int i = 0; i < actions.size(); i++) {
                if (!mask[i]) {
                    continue;
                }
                BudgetAction action = actions.get(i);
                double localBudget = action.getDeadlineBudget();
                if (!discreteBudget.isValidBudget(localBudget)) {
                    throw new IllegalStateException(
                            "ActionSpace produced a budget outside the configured discrete ladder");
                }

                Router next = action.getNextHop();
                decisions.add(new PathDecision(current, next, localBudget));
                visited.add(next);
                double nextAccumulated = accumulatedBudget + localBudget;

                if (next.equals(flow.getDestination())) {
                    if (decisions.size() == targetHopCount) {
                        evaluateCandidate(decisions);
                    }
                } else if (decisions.size() < targetHopCount) {
                    int remainingSlots = targetHopCount - decisions.size();
                    int minimumHops = ActionSpace.getMinimumRemainingHops(
                            next, flow.getDestination(), topology, visited);
                    // This is a safe lower-bound pruning rule: it removes a
                    // prefix only when even its shortest continuation is too long.
                    if (minimumHops != Integer.MAX_VALUE && minimumHops <= remainingSlots) {
                        enumerate(next, visited, decisions, nextAccumulated, targetHopCount);
                    }
                }

                visited.remove(next);
                decisions.remove(decisions.size() - 1);
            }
        }

        void evaluateCandidate(List<PathDecision> decisions) {
            String reason = cascade.getQualificationFailureReason(flow, decisions);
            if (reason == null) {
                if (bestFeasible == null || compareCandidates(decisions, bestFeasible) < 0) {
                    bestFeasible = new ArrayList<>(decisions);
                }
                return;
            }
            if (decisions.size() < bestFailureHops
                    || (decisions.size() == bestFailureHops
                        && (bestFailedCandidate == null
                            || compareCandidates(decisions, bestFailedCandidate) < 0))) {
                bestFailureHops = decisions.size();
                bestFailedCandidate = new ArrayList<>(decisions);
                bestFailureReason = reason;
            }
        }
    }

    /** Compare equal-hop candidates by router-ID path, then local delta sequence. */
    private static int compareCandidates(List<PathDecision> left, List<PathDecision> right) {
        int common = Math.min(left.size(), right.size());
        for (int i = 0; i < common; i++) {
            int compare = Integer.compare(left.get(i).getRouter().getId(),
                    right.get(i).getRouter().getId());
            if (compare != 0) return compare;
            if (i == common - 1) {
                compare = Integer.compare(left.get(i).getNextHop().getId(),
                        right.get(i).getNextHop().getId());
                if (compare != 0) return compare;
            }
        }
        int lengthCompare = Integer.compare(left.size(), right.size());
        if (lengthCompare != 0) return lengthCompare;
        for (int i = 0; i < left.size(); i++) {
            int compare = Double.compare(left.get(i).getDeadlineBudget(),
                    right.get(i).getDeadlineBudget());
            if (compare != 0) return compare;
        }
        return 0;
    }
}
