import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import network.Link;
import network.NetworkTopology;
import network.Router;
import routing.MAPPOEpisodeResult;
import routing.PathDecision;
import routing.RandomFeasibleBaseline;
import traffic.TrafficFlow;

/**
 * Tests for RandomFeasibleBaseline: feasibility masking, seed reproducibility,
 * deadline rejection, and packet accounting identities.
 */
public class RandomFeasibleBaselineTest {

    public static void main(String[] args) {
        testSeedRepeatability();
        testFeasibilityEnforcement();
        testDeadlineRejectionAndZeroAccounting();
        testPacketAccountingIdentities();
        System.out.println("RandomFeasibleBaselineTest passed: seed repeatability, feasibility, "
                + "deadline rejection, and packet accounting identities verified.");
    }

    private static void testSeedRepeatability() {
        Router a = new Router(1, "A");
        Router b = new Router(2, "B");
        Router c = new Router(3, "C");
        Router d = new Router(4, "D");
        NetworkTopology topology = new NetworkTopology();
        topology.addRouter(a); topology.addRouter(b); topology.addRouter(c); topology.addRouter(d);
        topology.addLink(new Link(a, b, 1.0, 10000.0));
        topology.addLink(new Link(a, c, 1.0, 10000.0));
        topology.addLink(new Link(b, d, 1.0, 10000.0));
        topology.addLink(new Link(c, d, 1.0, 10000.0));

        List<TrafficFlow> flows = Arrays.asList(
                new TrafficFlow(1, a, d, 15.0, 0.0, 10.0),
                new TrafficFlow(2, a, d, 10.0, 0.0, 8.0)
        );

        long seed = 12345L;
        RandomFeasibleBaseline.Result r1 = RandomFeasibleBaseline.run(
                topology, flows, 0.05, 10.0, 256, 0.0, 16, seed);
        RandomFeasibleBaseline.Result r2 = RandomFeasibleBaseline.run(
                topology, flows, 0.05, 10.0, 256, 0.0, 16, seed);

        check(r1.getAdmittedFlows() == r2.getAdmittedFlows(), "admitted flows must match across runs with same seed");
        check(r1.getGeneratedPackets() == r2.getGeneratedPackets(), "generated packets must match");
        check(r1.getDeliveredPackets() == r2.getDeliveredPackets(), "delivered packets must match");
        check(r1.getTimelyPackets() == r2.getTimelyPackets(), "timely packets must match");
        check(r1.getDroppedPackets() == r2.getDroppedPackets(), "dropped packets must match");
        check(Double.compare(r1.getTimelyDeliveryRatio(), r2.getTimelyDeliveryRatio()) == 0, "TDR must match");

        // Verify route decisions match step-by-step
        for (TrafficFlow flow : flows) {
            MAPPOEpisodeResult ep1 = r1.getFlowResults().get(flow.getFlowId());
            MAPPOEpisodeResult ep2 = r2.getFlowResults().get(flow.getFlowId());
            check(ep1.getPathDecisions().size() == ep2.getPathDecisions().size(), "route lengths must match");
            for (int i = 0; i < ep1.getPathDecisions().size(); i++) {
                PathDecision d1 = ep1.getPathDecisions().get(i);
                PathDecision d2 = ep2.getPathDecisions().get(i);
                check(d1.getRouter().equals(d2.getRouter()) && d1.getNextHop().equals(d2.getNextHop()),
                        "path decisions must match under identical seeds");
                check(Double.compare(d1.getDeadlineBudget(), d2.getDeadlineBudget()) == 0,
                        "budget allocations must match under identical seeds");
            }
        }
    }

    private static void testFeasibilityEnforcement() {
        Router a = new Router(1, "A");
        Router b = new Router(2, "B");
        Router c = new Router(3, "C");
        NetworkTopology topology = new NetworkTopology();
        topology.addRouter(a); topology.addRouter(b); topology.addRouter(c);
        topology.addLink(new Link(a, b, 2.0, 10000.0));
        topology.addLink(new Link(b, c, 2.0, 10000.0));

        TrafficFlow flow = new TrafficFlow(1, a, c, 10.0, 0.0, 10.0);
        RandomFeasibleBaseline.Result result = RandomFeasibleBaseline.run(
                topology, Arrays.asList(flow), 0.05, 10.0, 256, 0.0, 16, 99L);

        check(result.getAdmittedFlows() == 1, "Flow should be admitted with feasible routing");
        MAPPOEpisodeResult ep = result.getFlowResults().get(1);
        check(ep.isRouteComplete(), "Route must be complete");
        check(ep.isRouteQualified(), "Route must be qualified");

        double totalBudget = 0.0;
        for (PathDecision hop : ep.getPathDecisions()) {
            check(hop.getDeadlineBudget() > 0.05 + 2.0, "Each hop budget must exceed processing + propagation delay");
            totalBudget += hop.getDeadlineBudget();
        }
        check(totalBudget <= 10.0, "Total allocated budget must not exceed flow deadline 10.0 ms");
    }

    private static void testDeadlineRejectionAndZeroAccounting() {
        Router a = new Router(1, "A");
        Router b = new Router(2, "B");
        NetworkTopology topology = new NetworkTopology();
        topology.addRouter(a); topology.addRouter(b);
        topology.addLink(new Link(a, b, 5.0, 10000.0));

        // Flow deadline 2.0 ms is smaller than minimum required link budget (5.05 ms)
        TrafficFlow flow = new TrafficFlow(1, a, b, 10.0, 0.0, 2.0);
        RandomFeasibleBaseline.Result result = RandomFeasibleBaseline.run(
                topology, Arrays.asList(flow), 0.05, 2.0, 256, 0.0, 16, 42L);

        check(result.getRequestedFlows() == 1, "requested flows must be 1");
        check(result.getAdmittedFlows() == 0, "flow must be rejected due to deadline");
        check(result.getGeneratedPackets() == 0, "generated packets must be 0");
        check(result.getDeliveredPackets() == 0, "delivered packets must be 0");
        check(result.getTimelyPackets() == 0, "timely packets must be 0");
        check(result.getDroppedPackets() == 0, "dropped packets must be 0");
        check(Double.compare(result.getTimelyDeliveryRatio(), 0.0) == 0, "TDR must be 0.0");
    }

    private static void testPacketAccountingIdentities() {
        Router a = new Router(1, "A");
        Router b = new Router(2, "B");
        Router c = new Router(3, "C");
        NetworkTopology topology = new NetworkTopology();
        topology.addRouter(a); topology.addRouter(b); topology.addRouter(c);
        topology.addLink(new Link(a, b, 1.0, 10000.0));
        topology.addLink(new Link(b, c, 1.0, 10000.0));

        List<TrafficFlow> flows = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            flows.add(new TrafficFlow(10 + i, a, c, 20.0, 0.0, 10.0));
        }

        RandomFeasibleBaseline.Result result = RandomFeasibleBaseline.run(
                topology, flows, 0.05, 10.0, 256, 0.0, 16, 77L);

        check(result.getRequestedFlows() == 3, "requested flows must be 3");
        check(result.getAdmittedFlows() == 3, "all 3 flows should be admitted");
        check(result.getGeneratedPackets() > 0, "generated packets must be positive");
        check(result.getGeneratedPackets() == result.getDeliveredPackets() + result.getDroppedPackets(),
                "generated packets must equal delivered + dropped packets");
        check(result.getDeliveredPackets() == result.getTimelyPackets() + result.getLatePackets(),
                "delivered packets must equal timely + late packets");
        check(result.getTimelyDeliveryRatio() >= 0.0 && result.getTimelyDeliveryRatio() <= 1.0,
                "TDR must be between 0.0 and 1.0");
        check(result.getAverageEndToEndDelay() >= 0.0, "average delay must be non-negative");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
