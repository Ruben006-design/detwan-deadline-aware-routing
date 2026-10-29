package routing;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import network.Link;
import network.NetworkTopology;
import network.Router;
import traffic.TrafficFlow;

public class ActionSpace {

    public static List<BudgetAction> getActions(Router current, TrafficFlow flow, NetworkTopology topology, int budgetLevels) {
        List<BudgetAction> actions = new ArrayList<>();

        if (budgetLevels <= 0) {
            return actions;
        }

        // Eq. (21) pairs a neighbor with a budget; Eq. (22) supplies discrete budget levels.
        double step = flow.getDeadline() / budgetLevels;

        for (Link link : topology.getLinks(current)) {
            Router nextHop = link.getDestination();

            for (int m = 1; m <= budgetLevels; m++) {
                double budget = step * m;
                actions.add(new BudgetAction(nextHop, budget));
            }
        }

        return actions;
    }

    /**
     * Creates an index-aligned mask for {@link #getActions}. A true entry is
     * usable at this step; false entries remain in the action vector so the
     * actor and PPO retain a stable output-index mapping.
     */
    public static boolean[] getActionMask(
            Router current,
            TrafficFlow flow,
            NetworkTopology topology,
            int budgetLevels,
            Set<Router> visited,
            double accumulatedDeadlineBudgetMs,
            double processingDelayMs) {

        List<BudgetAction> actions = getActions(current, flow, topology, budgetLevels);
        return getActionMask(actions, current, flow, topology, budgetLevels,
                visited, accumulatedDeadlineBudgetMs, processingDelayMs);
    }

    /** Builds a mask against the exact ordered action list used for sampling. */
    public static boolean[] getActionMask(
            List<BudgetAction> actions,
            Router current,
            TrafficFlow flow,
            NetworkTopology topology,
            int budgetLevels,
            Set<Router> visited,
            double accumulatedDeadlineBudgetMs,
            double processingDelayMs) {

        if (actions == null) {
            throw new IllegalArgumentException("Action list is required");
        }
        boolean[] mask = new boolean[actions.size()];
        // Project feasibility mask: keep paper action indices while excluding illegal choices.
        double remainingDeadlineMs = flow.getDeadline()
                - flow.getTotalEndpointAccessDelayMs()
                - accumulatedDeadlineBudgetMs;

        for (int i = 0; i < actions.size(); i++) {
            BudgetAction action = actions.get(i);
            Link link = findLink(current, action.getNextHop(), topology);
            if (link == null) {
                continue;
            }

            boolean unvisited = visited == null || !visited.contains(action.getNextHop());
            boolean hopDelaySatisfied = action.getDeadlineBudget()
                    > processingDelayMs + link.getPropagationDelay();
            boolean withinRemainingDeadline = action.getDeadlineBudget()
                    <= remainingDeadlineMs;
            if (!unvisited || !hopDelaySatisfied || !withinRemainingDeadline) {
                continue;
            }
            if (action.getNextHop().equals(flow.getDestination())) {
                mask[i] = true;
                continue;
            }

            Set<Router> blockedRouters = visited == null
                    ? new HashSet<>() : new HashSet<>(visited);
            blockedRouters.add(current);

            int minimumRemainingHops = getMinimumRemainingHops(
                    action.getNextHop(), flow.getDestination(), topology, blockedRouters);
            if (minimumRemainingHops == Integer.MAX_VALUE) {
                continue;
            }

            // This hop-count lower bound cheaply rejects actions that cannot
            // possibly fit, even if every remaining hop used the cheapest
            // feasible discrete budget available anywhere in the topology.
            double minimumPerHopBudget = getMinimumFeasibleBudgetPerHop(
                    flow, topology, budgetLevels, processingDelayMs, blockedRouters);
            if (!Double.isFinite(minimumPerHopBudget)
                    || action.getDeadlineBudget()
                            + minimumRemainingHops * minimumPerHopBudget
                            > remainingDeadlineMs) {
                continue;
            }

            // Also test an actual feasible continuation. The cost of an edge
            // is its smallest action-space budget above processing+propagation.
            // This preserves non-shortest alternatives when they fit.
            double minimumRemainingBudget = getMinimumRemainingBudgetMs(
                    action.getNextHop(), flow.getDestination(), topology, flow,
                    budgetLevels, processingDelayMs, blockedRouters);
            mask[i] = Double.isFinite(minimumRemainingBudget)
                    && action.getDeadlineBudget() + minimumRemainingBudget
                            <= remainingDeadlineMs;
        }
        return mask;
    }

    /** BFS hop distance with already-visited routers excluded. */
    public static int getMinimumRemainingHops(
            Router source,
            Router destination,
            NetworkTopology topology,
            Set<Router> alreadyVisited) {

        if (source == null || destination == null || topology == null) {
            return Integer.MAX_VALUE;
        }
        if (source.equals(destination)) {
            return 0;
        }
        if (alreadyVisited != null && alreadyVisited.contains(destination)) {
            return Integer.MAX_VALUE;
        }

        ArrayDeque<Router> queue = new ArrayDeque<>();
        Map<Router, Integer> distances = new HashMap<>();
        queue.add(source);
        distances.put(source, 0);

        while (!queue.isEmpty()) {
            Router current = queue.removeFirst();
            List<Link> outgoing = topology.getLinks(current);
            if (outgoing == null) {
                continue;
            }
            for (Link link : outgoing) {
                Router next = link.getDestination();
                if (distances.containsKey(next)
                        || (alreadyVisited != null && alreadyVisited.contains(next))) {
                    continue;
                }
                int nextDistance = distances.get(current) + 1;
                if (next.equals(destination)) {
                    return nextDistance;
                }
                distances.put(next, nextDistance);
                queue.addLast(next);
            }
        }
        return Integer.MAX_VALUE;
    }

    /** Minimum total discrete hop-budget needed to reach the destination. */
    public static double getMinimumRemainingBudgetMs(
            Router source,
            Router destination,
            NetworkTopology topology,
            TrafficFlow flow,
            int budgetLevels,
            double processingDelayMs,
            Set<Router> alreadyVisited) {

        if (source == null || destination == null || topology == null || flow == null) {
            return Double.POSITIVE_INFINITY;
        }
        if (source.equals(destination)) {
            return 0.0;
        }
        if (alreadyVisited != null && alreadyVisited.contains(destination)) {
            return Double.POSITIVE_INFINITY;
        }

        Map<Router, Double> costs = new HashMap<>();
        Set<Router> settled = new HashSet<>();
        PriorityQueue<RouterCost> queue = new PriorityQueue<>(
                Comparator.comparingDouble(entry -> entry.cost));
        costs.put(source, 0.0);
        queue.add(new RouterCost(source, 0.0));

        while (!queue.isEmpty()) {
            RouterCost item = queue.remove();
            if (settled.contains(item.router)
                    || item.cost > costs.getOrDefault(item.router, Double.POSITIVE_INFINITY)) {
                continue;
            }
            if (item.router.equals(destination)) {
                return item.cost;
            }
            settled.add(item.router);

            List<Link> outgoing = topology.getLinks(item.router);
            if (outgoing == null) {
                continue;
            }
            for (Link link : outgoing) {
                Router next = link.getDestination();
                if (alreadyVisited != null && alreadyVisited.contains(next)) {
                    continue;
                }
                double edgeBudget = minimumFeasibleBudget(
                        flow, budgetLevels, processingDelayMs, link);
                if (!Double.isFinite(edgeBudget)) {
                    continue;
                }
                double candidate = item.cost + edgeBudget;
                if (candidate < costs.getOrDefault(next, Double.POSITIVE_INFINITY)) {
                    costs.put(next, candidate);
                    queue.add(new RouterCost(next, candidate));
                }
            }
        }
        return Double.POSITIVE_INFINITY;
    }

    public static String describeNoValidActionReason(
            Router current,
            TrafficFlow flow,
            NetworkTopology topology,
            int budgetLevels,
            Set<Router> visited,
            double accumulatedDeadlineBudgetMs,
            double processingDelayMs) {

        List<BudgetAction> actions = getActions(current, flow, topology, budgetLevels);
        boolean hasUnvisitedNeighbor = false;
        boolean hasHopFeasibleBudget = false;
        boolean hasBudgetWithinRemainingDeadline = false;
        boolean hasLocallyValidAction = false;
        boolean hasFeasibleContinuation = false;
        double remaining = flow.getDeadline()
                - flow.getTotalEndpointAccessDelayMs()
                - accumulatedDeadlineBudgetMs;

        for (BudgetAction action : actions) {
            Link link = findLink(current, action.getNextHop(), topology);
            if (link == null || (visited != null && visited.contains(action.getNextHop()))) {
                continue;
            }
            hasUnvisitedNeighbor = true;
            boolean hopFeasible = action.getDeadlineBudget()
                    > processingDelayMs + link.getPropagationDelay();
            if (hopFeasible) {
                hasHopFeasibleBudget = true;
                if (action.getDeadlineBudget() <= remaining) {
                    hasBudgetWithinRemainingDeadline = true;
                    hasLocallyValidAction = true;
                    if (action.getNextHop().equals(flow.getDestination())) {
                        hasFeasibleContinuation = true;
                        continue;
                    }
                    Set<Router> blocked = visited == null
                            ? new HashSet<>() : new HashSet<>(visited);
                    blocked.add(current);
                    int hops = getMinimumRemainingHops(
                            action.getNextHop(), flow.getDestination(), topology, blocked);
                    double futureBudget = getMinimumRemainingBudgetMs(
                            action.getNextHop(), flow.getDestination(), topology, flow,
                            budgetLevels, processingDelayMs, blocked);
                    double cheapestHopBudget = getMinimumFeasibleBudgetPerHop(
                            flow, topology, budgetLevels, processingDelayMs, blocked);
                    if (hops != Integer.MAX_VALUE
                            && Double.isFinite(futureBudget)
                            && Double.isFinite(cheapestHopBudget)
                            && action.getDeadlineBudget() + hops * cheapestHopBudget <= remaining
                            && action.getDeadlineBudget() + futureBudget <= remaining) {
                        hasFeasibleContinuation = true;
                    }
                }
            }
        }

        if (!hasUnvisitedNeighbor) {
            return "NO_UNVISITED_NEXT_HOP at " + current.getName();
        }
        if (!hasHopFeasibleBudget) {
            return "NO_BUDGET_EXCEEDS_PROCESSING_PLUS_PROPAGATION at " + current.getName();
        }
        if (!hasBudgetWithinRemainingDeadline) {
            return String.format(
                    "NO_HOP_FEASIBLE_BUDGET_WITHIN_REMAINING_DEADLINE at %s: remaining %.6f ms",
                    current.getName(), remaining);
        }
        if (hasLocallyValidAction && !hasFeasibleContinuation) {
            return "NO_FEASIBLE_ROUTE at " + current.getName()
                    + ": no simple continuation can reach the destination within the remaining deadline";
        }
        return "NO_VALID_ACTION: action mask and action list are inconsistent at "
                + current.getName();
    }

    private static double getMinimumFeasibleBudgetPerHop(
            TrafficFlow flow,
            NetworkTopology topology,
            int budgetLevels,
            double processingDelayMs,
            Set<Router> excludedRouters) {

        double minimum = Double.POSITIVE_INFINITY;
        for (Router router : topology.getRouters()) {
            if (excludedRouters != null && excludedRouters.contains(router)) {
                continue;
            }
            List<Link> outgoing = topology.getLinks(router);
            if (outgoing == null) {
                continue;
            }
            for (Link link : outgoing) {
                if (excludedRouters != null
                        && excludedRouters.contains(link.getDestination())) {
                    continue;
                }
                minimum = Math.min(minimum, minimumFeasibleBudget(
                        flow, budgetLevels, processingDelayMs, link));
            }
        }
        return minimum;
    }

    private static double minimumFeasibleBudget(
            TrafficFlow flow,
            int budgetLevels,
            double processingDelayMs,
            Link link) {

        if (budgetLevels <= 0) {
            return Double.POSITIVE_INFINITY;
        }
        double step = flow.getDeadline() / budgetLevels;
        for (int level = 1; level <= budgetLevels; level++) {
            double budget = step * level;
            if (budget > processingDelayMs + link.getPropagationDelay()) {
                return budget;
            }
        }
        return Double.POSITIVE_INFINITY;
    }

    private static final class RouterCost {
        final Router router;
        final double cost;

        RouterCost(Router router, double cost) {
            this.router = router;
            this.cost = cost;
        }
    }

    private static Link findLink(Router current, Router nextHop, NetworkTopology topology) {
        if (current == null || nextHop == null || topology.getLinks(current) == null) {
            return null;
        }
        for (Link link : topology.getLinks(current)) {
            if (link.getSource().equals(current)
                    && link.getDestination().equals(nextHop)) {
                return link;
            }
        }
        return null;
    }
}
