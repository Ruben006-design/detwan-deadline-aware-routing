package routing;

import java.util.*;

import network.Link;
import network.NetworkTopology;
import network.Router;
import traffic.TrafficFlow;

public class MAPPOEpisode {

    private NetworkTopology topology;
    private NetworkState networkState;
    private RouteCascade routeCascade;
    private MAPPOTrainer trainer;
    private int budgetLevels;
    private GlobalStateEncoder globalStateEncoder;
    private final Random evaluationRandom;

    public MAPPOEpisode(
            NetworkTopology topology,
            NetworkState networkState,
            RouteCascade routeCascade,
            MAPPOTrainer trainer,
            int budgetLevels) {

        this(topology, networkState, routeCascade, trainer, budgetLevels, null);
    }

    /** Optional isolated RNG is used by evaluation and never touches training RNG state. */
    public MAPPOEpisode(
            NetworkTopology topology,
            NetworkState networkState,
            RouteCascade routeCascade,
            MAPPOTrainer trainer,
            int budgetLevels,
            Random evaluationRandom) {

        this.topology = topology;
        this.networkState = networkState;
        this.routeCascade = routeCascade;
        this.trainer = trainer;
        this.budgetLevels = budgetLevels;
        this.evaluationRandom = evaluationRandom;
        this.globalStateEncoder = new GlobalStateEncoder(topology, networkState, routeCascade);
        if (trainer.getCriticInputSize() != globalStateEncoder.getStateSize()) {
            throw new IllegalArgumentException("Critic input size " + trainer.getCriticInputSize()
                    + " does not match centralized global-state size "
                    + globalStateEncoder.getStateSize());
        }
    }

    public MAPPOEpisodeResult run(
            TrafficFlow flow,
            List<TrafficFlow> activeFlows,
            Map<Integer, Double> allocatedBudgets) {

        // A standalone request is one collected finite-horizon rollout.
        return run(flow, activeFlows, allocatedBudgets,
                TrajectoryStep.BoundaryType.TRUNCATED);
    }

    /**
     * Compatibility overload: true means a genuinely terminated environment
     * transition. Use the boundary-type overload for rollout truncation.
     */
    public MAPPOEpisodeResult run(
            TrafficFlow flow,
            List<TrafficFlow> activeFlows,
            Map<Integer, Double> allocatedBudgets,
            boolean terminal) {

        return run(flow, activeFlows, allocatedBudgets,
                terminal ? TrajectoryStep.BoundaryType.TERMINATED
                        : TrajectoryStep.BoundaryType.NONE);
    }

    /** Collects one flow-request transition with an explicit rollout boundary. */
    public MAPPOEpisodeResult run(
            TrafficFlow flow,
            List<TrafficFlow> activeFlows,
            Map<Integer, Double> allocatedBudgets,
            TrajectoryStep.BoundaryType boundaryType) {

        return run(flow, activeFlows, allocatedBudgets, boundaryType, null);
    }

    /**
     * Collects one request transition. For a continuing transition,
     * nextRequestFlow identifies the actual request that will form the next
     * decision state, after this request's admission reservation is committed.
     * At a truncation/termination boundary, pass null; the next state is an
     * environment-only no-pending-request snapshot.
     */
    public MAPPOEpisodeResult run(
            TrafficFlow flow,
            List<TrafficFlow> activeFlows,
            Map<Integer, Double> allocatedBudgets,
            TrajectoryStep.BoundaryType boundaryType,
            TrafficFlow nextRequestFlow) {

        if (boundaryType == null) {
            throw new IllegalArgumentException("Transition boundary type is required");
        }
        if (boundaryType == TrajectoryStep.BoundaryType.NONE && nextRequestFlow == null) {
            throw new IllegalArgumentException(
                    "A continuing request transition requires the actual next request state");
        }

        TrajectoryBuffer trajectory =
                new TrajectoryBuffer();

        List<PathDecision> decisions =
                new ArrayList<>();

        Map<Integer, AgentAction> actionsByAgent = new LinkedHashMap<>();

        // Algorithm 2 takes one network/request snapshot before any router acts.
        double[] flowRequestState = globalStateEncoder.encode(
                flow, Collections.<PathDecision>emptyList());

        Set<Router> visited =
                new HashSet<>();

        Router current =
                flow.getSource();

        visited.add(current);
        String terminationReason = "ROUTE_TERMINATED";

        while (!current.equals(flow.getDestination())) {

            if (visited.size() > topology.getRouterCount()) {
                terminationReason = "ROUTER_VISIT_LIMIT_EXCEEDED";
                break;
            }

            List<RouterObservation.NeighborState> neighbors =
                    networkState.getNeighborStates(
                            current,
                            flow.getDestination(),
                            routeCascade.getAdmittedAllocations());

            if (!neighborStatesMatchTopologyOrder(current, neighbors)) {
                terminationReason = "ACTOR_NEIGHBOR_ORDER_MISMATCH at " + current.getName();
                break;
            }

            RouterObservation observation =
                    new RouterObservation(
                            flow,
                            decisions,
                            neighbors);

            MAPPOActor actor =
                    trainer.getActor(current);

            if (actor == null) {
                terminationReason = "NO_ACTOR_FOR_ROUTER: " + current.getName();
                break;
            }

            double[] encodedObservation =
                    new ObservationEncoder(
                            topology,
                            budgetLevels)
                            .encodeActorObservation(
                                    observation);

            List<BudgetAction> actions =
                    ActionSpace.getActions(
                            current,
                            flow,
                            topology,
                            budgetLevels);

            if (encodedObservation.length != actor.getObservationInputSize()) {
                terminationReason = "ACTOR_OBSERVATION_DIMENSION_MISMATCH at " + current.getName();
                break;
            }

            if (actions.isEmpty()) {
                terminationReason = "NO_OUTGOING_ACTIONS: " + current.getName();
                break;
            }

            double accumulatedBudgetMs = 0.0;
            for (PathDecision previous : decisions) {
                accumulatedBudgetMs += previous.getDeadlineBudget();
            }
            double processingDelayMs = networkState.getProcessingDelay();
            boolean[] actionMask = ActionSpace.getActionMask(
                    actions,
                    current,
                    flow,
                    topology,
                    budgetLevels,
                    visited,
                    accumulatedBudgetMs,
                    processingDelayMs);

            if (actions.size() != actor.getActionCount()
                    || actionMask.length != actions.size()
                    || !actionsMatchTopologyOrder(current, flow, actions)) {
                terminationReason = "ACTION_MASK_ACTION_ORDER_MISMATCH at " + current.getName();
                break;
            }

            if (!hasValidAction(actionMask)) {
                terminationReason = ActionSpace.describeNoValidActionReason(
                        current, flow, topology, budgetLevels, visited,
                        accumulatedBudgetMs, processingDelayMs);
                break;
            }

            double[] probabilities = actor.getProbabilities(observation, actionMask);

            int actionIndex = evaluationRandom == null
                    ? trainer.sampleActionIndex(probabilities)
                    : trainer.sampleActionIndex(probabilities, evaluationRandom);

            if (actionIndex < 0 || actionIndex >= actions.size()
                    || !actionMask[actionIndex]) {
                terminationReason = "ACTION_SAMPLER_RETURNED_INVALID_INDEX";
                break;
            }

            BudgetAction action =
                    actions.get(actionIndex);

            double actionProbability =
                    probabilities[actionIndex];

            Router nextHop =
                    action.getNextHop();

            if (visited.contains(nextHop)) {
                terminationReason = "INVALID_MASKED_ACTION_LOOP: selected next hop "
                        + nextHop.getName() + " was already visited";
                break;
            }

            if (!isNeighbor(current, nextHop)) {
                terminationReason = "INVALID_MASKED_ACTION_NEXT_HOP: " + current.getName()
                        + " -> " + nextHop.getName();
                break;
            }

            double expectedBudget = flow.getDeadline() * (actionIndex % budgetLevels + 1)
                    / budgetLevels;
            Link expectedLink = nthOutgoingLink(current, actionIndex / budgetLevels);
            if (expectedLink == null || !expectedLink.getDestination().equals(nextHop)
                    || Math.abs(expectedBudget - action.getDeadlineBudget()) > 1.0e-12) {
                terminationReason = "ACTION_INDEX_BUDGET_MAPPING_MISMATCH at " + current.getName();
                break;
            }

            actionsByAgent.put(current.getId(), new AgentAction(
                    encodedObservation, actionIndex, actionProbability, actionMask));

            PathDecision decision =
                    new PathDecision(
                            current,
                            nextHop,
                            action.getDeadlineBudget());

            decisions.add(decision);
            visited.add(nextHop);

            current = nextHop;
        }

        boolean routeComplete =
                current.equals(flow.getDestination());

        if (routeComplete) {
            terminationReason = "DESTINATION_REACHED";
        }

        String admissionFailureReason = routeComplete
                ? routeCascade.getQualificationFailureReason(flow, decisions)
                : null;
        // Eq. (19g) - Admission indicator is one only if route and deadline/resource qualification pass.
        boolean admitted = admissionFailureReason == null && routeComplete
                && routeCascade.admitRoute(flow, decisions);
        if (!admitted && routeComplete && admissionFailureReason == null) {
            admissionFailureReason = routeCascade.getQualificationFailureReason(flow, decisions);
            if (admissionFailureReason == null) {
                admissionFailureReason = "ROUTE_ADMISSION_FAILED";
            }
        }

        // Eq. (23) - All agents receive the same positive admission reward, or zero on rejection.
        double reward =
                admitted
                        ? trainer.getAdmissionReward()
                        : 0.0;

        // Algorithm 2 updates the global state after qualification and before
        // the next request. For a continuing transition, encode that actual
        // next request against the now-updated reservation state so s_(t+1)
        // exactly matches the next transition's s_t. A rollout boundary has
        // no next request, so retain the resulting environment state without
        // carrying the completed flow/route into the critic bootstrap.
        double[] nextFlowRequestState = nextRequestFlow != null
                ? globalStateEncoder.encode(nextRequestFlow,
                        Collections.<PathDecision>emptyList())
                : globalStateEncoder.encodeNoPendingRequest(flow);
        for (Router router : topology.getRouters()) {
            MAPPOActor actor = trainer.getActor(router);
            AgentAction selected = actionsByAgent.get(router.getId());
            if (selected != null) {
                trajectory.add(new TrajectoryStep(
                        router.getId(), flowRequestState, selected.observation,
                        selected.actionIndex, selected.actionProbability, reward,
                        nextFlowRequestState, selected.actionMask, boundaryType, true));
            } else {
                int observationSize = actor == null ? 0 : actor.getObservationInputSize();
                int actionCount = actor == null ? 0 : actor.getActionCount();
                // Inactive fields are sentinels only. Eq. (26)'s route indicator
                // is zero, so this observation/action never trains the actor.
                trajectory.add(new TrajectoryStep(
                        router.getId(), flowRequestState, new double[observationSize],
                        -1, 0.0, reward, nextFlowRequestState,
                        new boolean[actionCount], boundaryType, false));
            }
        }

        return new MAPPOEpisodeResult(
                routeComplete,
                admitted,
                decisions,
                trajectory,
                reward,
                terminationReason,
                admitted ? null : (routeComplete
                        ? admissionFailureReason
                        : terminationReason));
    }

    private boolean hasValidAction(boolean[] actionMask) {
        for (boolean valid : actionMask) {
            if (valid) {
                return true;
            }
        }
        return false;
    }

    private boolean isNeighbor(
            Router current,
            Router nextHop) {

        for (Link link :
                topology.getLinks(current)) {

            if (link.getDestination()
                    .equals(nextHop)) {
                return true;
            }
        }

        return false;
    }

    private static final class AgentAction {
        final double[] observation;
        final int actionIndex;
        final double actionProbability;
        final boolean[] actionMask;

        AgentAction(double[] observation, int actionIndex,
                    double actionProbability, boolean[] actionMask) {
            this.observation = observation.clone();
            this.actionIndex = actionIndex;
            this.actionProbability = actionProbability;
            this.actionMask = actionMask.clone();
        }
    }

    private boolean actionsMatchTopologyOrder(
            Router current, TrafficFlow flow, List<BudgetAction> actions) {
        int expectedIndex = 0;
        if (budgetLevels <= 0) return actions.isEmpty();
        for (Link link : topology.getLinks(current)) {
            for (int level = 1; level <= budgetLevels; level++) {
                if (expectedIndex >= actions.size()) return false;
                BudgetAction actual = actions.get(expectedIndex++);
                double budget = flow.getDeadline() * level / budgetLevels;
                if (!actual.getNextHop().equals(link.getDestination())
                        || Math.abs(actual.getDeadlineBudget() - budget) > 1.0e-12) {
                    return false;
                }
            }
        }
        return expectedIndex == actions.size();
    }

    private boolean neighborStatesMatchTopologyOrder(
            Router current, List<RouterObservation.NeighborState> neighbors) {
        List<Link> links = topology.getLinks(current);
        if (links == null || links.size() != neighbors.size()) return false;
        for (int i = 0; i < links.size(); i++) {
            if (!links.get(i).getDestination().equals(neighbors.get(i).getNeighbor())) {
                return false;
            }
        }
        return true;
    }

    private Link nthOutgoingLink(Router current, int index) {
        List<Link> links = topology.getLinks(current);
        if (links == null || index < 0 || index >= links.size()) return null;
        return links.get(index);
    }
}
