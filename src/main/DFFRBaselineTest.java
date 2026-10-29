import java.util.Arrays;
import java.util.List;

import network.Link;
import network.NetworkTopology;
import network.Router;
import routing.DFFRBaseline;
import routing.DeadlineBudget;
import routing.MAPPOEpisodeResult;
import routing.PathDecision;
import traffic.TrafficFlow;

/** Deterministic regression tests for the feasibility-first DFFR baseline. */
public final class DFFRBaselineTest {
    private static final double DEADLINE_MS = 10.0;
    private static final int BUDGET_LEVELS = 16;

    public static void main(String[] args) {
        testLongerFeasibleRouteBeatsInfeasibleTopologicalShortestRoute();
        testNoFeasibleCandidateIsRejected();
        testEqualHopTieIsDeterministic();
        testSelectedBudgetsAreFromDiscreteLadder();
        testRejectedCandidateDoesNotLeakReservations();
        testSharedReservationConstrainsLaterFlow();
        testPacketAccounting();
        System.out.println("DFFRBaselineTest passed: feasibility-first route selection, rejection, "
                + "deterministic ties, discrete budgets, reservation isolation, and packet accounting.");
    }

    private static void testLongerFeasibleRouteBeatsInfeasibleTopologicalShortestRoute() {
        Router a = router(1, "A");
        Router b = router(2, "B");
        Router d = router(4, "D");
        NetworkTopology topology = topology(a, b, d);
        topology.addLink(new Link(a, d, 12.0, 10000.0));
        topology.addLink(new Link(a, b, 2.0, 10000.0));
        topology.addLink(new Link(b, d, 2.0, 10000.0));

        DFFRBaseline.Result result = run(topology, flow(1, a, d, 1.0, 0.0));
        MAPPOEpisodeResult flowResult = result.getFlowResults().get(1);
        check(result.getAdmittedFlows() == 1, "longer feasible alternative must be admitted");
        check(flowResult.isRouteComplete() && flowResult.isRouteQualified(),
                "selected alternative must reach the destination and qualify");
        check(routeNames(flowResult).equals("A -> B -> D"),
                "DFFR must reject the 12ms direct link and select A -> B -> D");
    }

    private static void testNoFeasibleCandidateIsRejected() {
        Router a = router(1, "A");
        Router d = router(2, "D");
        NetworkTopology topology = topology(a, d);
        topology.addLink(new Link(a, d, 12.0, 10000.0));

        DFFRBaseline.Result result = run(topology, flow(2, a, d, 1.0, 0.0));
        MAPPOEpisodeResult rejected = result.getFlowResults().get(2);
        check(result.getAdmittedFlows() == 0, "flow with no feasible discrete route must be rejected");
        check(!rejected.isRouteQualified(), "rejected flow must not be qualified");
        check(rejected.getRejectionReason() != null
                        && rejected.getRejectionReason().contains("NO_FEASIBLE_ROUTE"),
                "rejection must provide a clear no-feasible-route reason");
        check(result.getCommittedAllocations().isEmpty(), "rejected flow must commit no reservations");
    }

    private static void testEqualHopTieIsDeterministic() {
        Router a = router(1, "A");
        Router b = router(2, "B");
        Router c = router(3, "C");
        Router d = router(4, "D");
        NetworkTopology topology = topology(a, b, c, d);
        topology.addLink(new Link(a, c, 1.0, 10000.0));
        topology.addLink(new Link(c, d, 1.0, 10000.0));
        topology.addLink(new Link(a, b, 1.0, 10000.0));
        topology.addLink(new Link(b, d, 1.0, 10000.0));

        String expected = "A -> B -> D";
        for (int repeat = 0; repeat < 3; repeat++) {
            DFFRBaseline.Result result = run(topology, flow(10 + repeat, a, d, 1.0, 0.0));
            check(routeNames(result.getFlowResults().get(10 + repeat)).equals(expected),
                    "equal-hop route tie must use the lexicographically lower router-ID path");
        }
    }

    private static void testSelectedBudgetsAreFromDiscreteLadder() {
        Router a = router(1, "A");
        Router b = router(2, "B");
        Router d = router(3, "D");
        NetworkTopology topology = topology(a, b, d);
        topology.addLink(new Link(a, b, 1.0, 10000.0));
        topology.addLink(new Link(b, d, 1.0, 10000.0));
        TrafficFlow flow = flow(20, a, d, 1.0, 0.0);

        DFFRBaseline.Result result = run(topology, flow);
        List<Double> ladder = new DeadlineBudget(flow.getDeadline(), BUDGET_LEVELS).getPossibleBudgets();
        List<PathDecision> decisions = result.getFlowResults().get(20).getPathDecisions();
        check(result.getAdmittedFlows() == 1 && decisions.size() == 2,
                "two-hop route should be admitted with two selected local budgets");
        double total = flow.getTotalEndpointAccessDelayMs();
        for (PathDecision decision : decisions) {
            check(ladder.contains(decision.getDeadlineBudget()),
                    "each local delta must come from DeadlineBudget's discrete ladder");
            total += decision.getDeadlineBudget();
        }
        check(total <= flow.getDeadline(), "budgets plus endpoint access delays must fit the deadline");
        check(decisions.get(0).getDeadlineBudget() > 1.05
                        && decisions.get(1).getDeadlineBudget() > 1.05,
                "each local delta must exceed processing plus propagation delay");
    }

    private static void testRejectedCandidateDoesNotLeakReservations() {
        Router a = router(1, "A");
        Router b = router(2, "B");
        Router c = router(3, "C");
        Router d = router(4, "D");
        NetworkTopology topology = topology(a, b, c, d);
        topology.addLink(new Link(a, b, 1.0, 10000.0)); // First edge passes.
        topology.addLink(new Link(b, d, 1.0, 0.5));     // Later edge fails Eq. (17).
        topology.addLink(new Link(a, c, 1.0, 10000.0));
        topology.addLink(new Link(c, d, 1.0, 10000.0));

        DFFRBaseline.Result result = run(topology, flow(30, a, d, 1.0, 0.0));
        MAPPOEpisodeResult admitted = result.getFlowResults().get(30);
        check(result.getAdmittedFlows() == 1 && routeNames(admitted).equals("A -> C -> D"),
                "feasible equal-hop alternative must be selected after the A-B-D candidate fails");
        check(result.getCommittedAllocations().size() == 2,
                "exactly the two selected route hops should be reserved");
        for (routing.FlowHopAllocation allocation : result.getCommittedAllocations()) {
            check(!(allocation.getSource().equals(a) && allocation.getDestination().equals(b))
                            && !(allocation.getSource().equals(b) && allocation.getDestination().equals(d)),
                    "rejected A-B-D candidate must leave no partial link reservations");
            check(allocation.getFlow().getFlowId() == 30,
                    "reservation snapshot must contain only the admitted flow");
        }
    }

    private static void testSharedReservationConstrainsLaterFlow() {
        Router a = router(1, "A");
        Router d = router(2, "D");
        NetworkTopology topology = topology(a, d);
        topology.addLink(new Link(a, d, 1.0, 10.0));
        List<TrafficFlow> flows = Arrays.asList(
                flow(40, a, d, 6.0, 1.0), flow(41, a, d, 6.0, 1.0));

        DFFRBaseline.Result result = run(topology, flows.toArray(new TrafficFlow[0]));
        check(result.getAdmittedFlows() == 1,
                "existing admitted reservation on a shared directed link must constrain the next flow");
        check(result.getCommittedAllocations().size() == 1
                        && result.getCommittedAllocations().get(0).getFlow().getFlowId() == 40,
                "only the first admitted flow should remain reserved");
        check(!result.getFlowResults().get(41).isRouteQualified(),
                "second flow must be reported rejected after shared-link resource failure");
    }

    private static void testPacketAccounting() {
        Router a = router(1, "A");
        Router b = router(2, "B");
        NetworkTopology topology = topology(a, b);
        topology.addLink(new Link(a, b, 0.1, 10000.0));
        DFFRBaseline.Result result = run(topology, flow(50, a, b, 10.0, 1.0));

        check(result.getGeneratedPackets() > 0, "admitted traffic must generate packets");
        check(result.getGeneratedPackets() == result.getDeliveredPackets() + result.getDroppedPackets(),
                "generated packets must equal delivered plus dropped packets");
        check(result.getDeliveredPackets() == result.getTimelyPackets() + result.getLatePackets(),
                "delivered packets must equal timely plus late packets");
        check(result.getTimelyDeliveryRatio() >= 0.0 && result.getTimelyDeliveryRatio() <= 1.0,
                "TDR must remain within [0,1]");
    }

    private static DFFRBaseline.Result run(NetworkTopology topology, TrafficFlow... flows) {
        return DFFRBaseline.run(topology, Arrays.asList(flows), 0.05,
                DEADLINE_MS, 256, 0.0, BUDGET_LEVELS);
    }

    private static TrafficFlow flow(int id, Router source, Router destination,
                                    double rateMbps, double burstKB) {
        return new TrafficFlow(id, source, destination, rateMbps, burstKB, DEADLINE_MS);
    }

    private static Router router(int id, String name) { return new Router(id, name); }

    private static NetworkTopology topology(Router... routers) {
        NetworkTopology topology = new NetworkTopology();
        for (Router router : routers) topology.addRouter(router);
        return topology;
    }

    private static String routeNames(MAPPOEpisodeResult result) {
        StringBuilder route = new StringBuilder();
        List<PathDecision> decisions = result.getPathDecisions();
        for (PathDecision decision : decisions) {
            if (route.length() == 0) route.append(decision.getRouter().getName());
            route.append(" -> ").append(decision.getNextHop().getName());
        }
        return route.toString();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
