package routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import network.Link;
import network.NetworkTopology;
import network.Router;
import traffic.TrafficFlow;

public class RouteCascade {

    private NetworkTopology topology;
    private double processingDelay;
    private DeadlineSatisfiability deadlineSatisfiability;
    private List<FlowHopAllocation> admittedAllocations = new ArrayList<>();

    public RouteCascade(NetworkTopology topology, double processingDelay) {
        this(topology, processingDelay,
                DeadlineSatisfiability.DEFAULT_MAX_BLOCKING_PACKET_BYTES);
    }

    /** The packet-size argument supplies the paper's maximum blocking BE packet length, in bytes. */
    public RouteCascade(NetworkTopology topology, double processingDelay,
                        double maxBlockingPacketBytes) {
        this.topology = topology;
        this.processingDelay = processingDelay;
        this.deadlineSatisfiability = new DeadlineSatisfiability(maxBlockingPacketBytes);
    }

    public List<PathDecision> buildRoute(
            TrafficFlow flow,
            List<BudgetAction> actions) {

        List<PathDecision> decisions = new ArrayList<>();
        Set<Router> visited = new HashSet<>();

        Router current = flow.getSource();

        visited.add(current);

        for (BudgetAction action : actions) {

            Router nextHop = action.getNextHop();

            if (visited.contains(nextHop)) {
                break;
            }

            if (!isNeighbor(current, nextHop)) {
                break;
            }

            PathDecision decision =
                    new PathDecision(
                            current,
                            nextHop,
                            action.getDeadlineBudget());

            decisions.add(decision);
            visited.add(nextHop);

            current = nextHop;

            if (current.equals(flow.getDestination())) {
                break;
            }
        }

        return decisions;
    }

    public boolean isComplete(
            TrafficFlow flow,
            List<PathDecision> decisions) {

        if (decisions.isEmpty()) {
            return false;
        }

        PathDecision last =
                decisions.get(decisions.size() - 1);

        return last.getNextHop().equals(
                flow.getDestination());
    }

    public boolean checkRouteQualification(
            TrafficFlow flow,
            List<PathDecision> decisions) {

        return getQualificationFailureReason(flow, decisions) == null;
    }

    /** Returns the first admission constraint that rejects this route, or null when it passes. */
    public String getQualificationFailureReason(
            TrafficFlow flow,
            List<PathDecision> decisions) {

        // Eqs. (19b)-(19d) - Require a complete, continuous, loop-free source-to-destination route.
        if (!isComplete(flow, decisions)) {
            return "ROUTE_INCOMPLETE: selected hops do not reach the destination";
        }

        Set<Router> visited = new HashSet<>();

        visited.add(flow.getSource());
        Router expectedCurrent = flow.getSource();

        double totalBudget = 0.0;

        for (PathDecision decision : decisions) {

            Router current = decision.getRouter();
            Router nextHop = decision.getNextHop();
            double budget = decision.getDeadlineBudget();

            if (!current.equals(expectedCurrent) || !visited.contains(current)) {
                return "INVALID_ROUTE_CONTINUITY at " + current.getName();
            }

            if (visited.contains(nextHop)) {
                return "ROUTE_LOOP: next hop " + nextHop.getName() + " was already visited";
            }

            if (!isNeighbor(current, nextHop)) {
                return "INVALID_NEXT_HOP: " + current.getName() + " -> " + nextHop.getName();
            }

            double hopDelay =
                    getHopDelay(current, nextHop);

            // Eq. (19e) - A local delta must strictly exceed processing plus propagation delay.
            if (budget <= hopDelay) {
                return String.format(
                        "HOP_DEADLINE_TOO_SMALL on %s -> %s: budget %.6f ms <= processing+propagation %.6f ms",
                        current.getName(), nextHop.getName(), budget, hopDelay);
            }

            totalBudget += budget;
            visited.add(nextHop);
            expectedCurrent = nextHop;

            List<FlowHopAllocation> allocationsOnLink =
                    allocationsForLink(current, nextHop, flow.getFlowId());
            allocationsOnLink.add(new FlowHopAllocation(
                    flow, current, nextHop, budget, totalBudget));
            Link link = getLink(current, nextHop);
            // Eq. (17) - Check the candidate and existing reservations on this directed link.
            if (!deadlineSatisfiability.isSatisfied(
                    allocationsOnLink, link.getBandwidth())) {
                return String.format(
                        "EQ17_DEADLINE_SATISFIABILITY_FAILED on %s -> %s",
                        current.getName(), nextHop.getName());
            }
        }

        // Eq. (19f) - Sum local deltas and both endpoint access contributions within D_f.
        double endpointAccessDelay = flow.getTotalEndpointAccessDelayMs();
        double endToEndRequirement = totalBudget + endpointAccessDelay;
        if (endToEndRequirement > flow.getDeadline()) {
            return String.format(
                    "END_TO_END_BUDGET_EXCEEDED: hop budgets %.6f ms + source access %.6f ms"
                            + " + destination access %.6f ms = %.6f ms > deadline %.6f ms",
                    totalBudget, flow.getSourceAccessDelayMs(),
                    flow.getDestinationAccessDelayMs(), endToEndRequirement,
                    flow.getDeadline());
        }
        return null;
    }

    /** Check the paper-based condition and reserve this flow on its selected links if admitted. */
    public boolean admitRoute(TrafficFlow flow, List<PathDecision> decisions) {
        if (!checkRouteQualification(flow, decisions)) {
            return false;
        }
        admittedAllocations.removeIf(a -> a.getFlow().getFlowId() == flow.getFlowId());
        double cumulativeBudget = 0.0;
        for (PathDecision decision : decisions) {
            cumulativeBudget += decision.getDeadlineBudget();
            admittedAllocations.add(new FlowHopAllocation(
                    flow, decision.getRouter(), decision.getNextHop(),
                    decision.getDeadlineBudget(), cumulativeBudget));
        }
        return true;
    }

    public List<FlowHopAllocation> getAdmittedAllocations() {
        return Collections.unmodifiableList(new ArrayList<>(admittedAllocations));
    }

    public void clearAdmittedAllocations() {
        admittedAllocations.clear();
    }

    private List<FlowHopAllocation> allocationsForLink(
            Router source, Router destination, int candidateFlowId) {
        List<FlowHopAllocation> result = new ArrayList<>();
        for (FlowHopAllocation allocation : admittedAllocations) {
            if (allocation.getFlow().getFlowId() != candidateFlowId
                    && allocation.usesLink(source, destination)) {
                result.add(allocation);
            }
        }
        return result;
    }

    private Link getLink(Router source, Router destination) {
        for (Link link : topology.getLinks(source)) {
            if (link.getDestination().equals(destination)) {
                return link;
            }
        }
        return null;
    }

    private boolean isNeighbor(
            Router current,
            Router nextHop) {

        for (Link link : topology.getLinks(current)) {
            if (link.getDestination().equals(nextHop)) {
                return true;
            }
        }

        return false;
    }

    private double getHopDelay(
            Router current,
            Router nextHop) {

        for (Link link : topology.getLinks(current)) {
            if (link.getDestination().equals(nextHop)) {
                return processingDelay +
                        link.getPropagationDelay();
            }
        }

        return Double.POSITIVE_INFINITY;
    }
}
