import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import network.Link;
import network.NetworkTopology;
import network.Router;
import routing.PathDecision;
import routing.ShortestPathBaseline;
import traffic.TrafficFlow;

/**
 * Focused tests for ShortestPathBaseline: route validity, deadline rejection,
 * packet accounting identities, and deterministic repeatability.
 */
public class ShortestPathBaselineTest {

    public static void main(String[] args) {
        testShortestPathRouteValidity();
        testDeadlineRejectionAndZeroAccounting();
        testPacketAccountingIdentities();
        testBaselineRepeatability();
        System.out.println("ShortestPathBaselineTest passed: route validity, deadline rejection, "
                + "packet accounting identities, and deterministic repeatability verified.");
    }

    private static void testShortestPathRouteValidity() {
        Router a = new Router(1, "A");
        Router b = new Router(2, "B");
        Router c = new Router(3, "C");
        Router d = new Router(4, "D");
        NetworkTopology topology = new NetworkTopology();
        topology.addRouter(a); topology.addRouter(b); topology.addRouter(c); topology.addRouter(d);

        // Path 1: A -> B -> C -> D (total propagation delay = 1.0 + 1.0 + 1.0 = 3.0 ms)
        topology.addLink(new Link(a, b, 1.0, 10000.0));
        topology.addLink(new Link(b, c, 1.0, 10000.0));
        topology.addLink(new Link(c, d, 1.0, 10000.0));
        // Path 2: Direct A -> D (propagation delay = 10.0 ms)
        topology.addLink(new Link(a, d, 10.0, 10000.0));

        List<Router> path = ShortestPathBaseline.findShortestPath(topology, a, d);
        check(path.size() == 4, "shortest path must have 4 nodes");
        check(path.get(0).equals(a) && path.get(1).equals(b) && path.get(2).equals(c) && path.get(3).equals(d),
                "shortest path must be A -> B -> C -> D");
    }

    private static void testDeadlineRejectionAndZeroAccounting() {
        Router a = new Router(1, "A");
        Router b = new Router(2, "B");
        NetworkTopology topology = new NetworkTopology();
        topology.addRouter(a); topology.addRouter(b);
        // Link propagation delay 5.0 ms
        topology.addLink(new Link(a, b, 5.0, 10000.0));

        // Flow deadline 2.0 ms is smaller than link delay (5.0 ms + 0.05 ms)
        TrafficFlow flow = new TrafficFlow(1, a, b, 10.0, 0.0, 2.0);
        ShortestPathBaseline.Result result = ShortestPathBaseline.run(
                topology, Arrays.asList(flow), 0.05, 2.0, 256, 0.0);

        check(result.getRequestedFlows() == 1, "requested flows must be 1");
        check(result.getAdmittedFlows() == 0, "flow must be rejected due to deadline");
        check(result.getGeneratedPackets() == 0, "generated packets must be 0 when no flow is admitted");
        check(result.getDeliveredPackets() == 0, "delivered packets must be 0");
        check(result.getTimelyPackets() == 0, "timely packets must be 0");
        check(result.getDroppedPackets() == 0, "dropped packets must be 0");
        check(Double.compare(result.getTimelyDeliveryRatio(), 0.0) == 0, "TDR must be 0.0 for 0 packets");
        check(Double.compare(result.getAverageEndToEndDelay(), 0.0) == 0, "delay must be 0.0 for 0 packets");
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

        ShortestPathBaseline.Result result = ShortestPathBaseline.run(
                topology, flows, 0.05, 10.0, 256, 0.0);

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

    private static void testBaselineRepeatability() {
        Router a = new Router(1, "A");
        Router b = new Router(2, "B");
        Router c = new Router(3, "C");
        NetworkTopology topology = new NetworkTopology();
        topology.addRouter(a); topology.addRouter(b); topology.addRouter(c);
        topology.addLink(new Link(a, b, 0.5, 10000.0));
        topology.addLink(new Link(b, c, 0.5, 10000.0));

        List<TrafficFlow> flows = Arrays.asList(
                new TrafficFlow(100, a, c, 15.0, 0.0, 10.0),
                new TrafficFlow(101, b, c, 25.0, 0.0, 10.0)
        );

        ShortestPathBaseline.Result r1 = ShortestPathBaseline.run(topology, flows, 0.05, 10.0, 256, 0.0);
        ShortestPathBaseline.Result r2 = ShortestPathBaseline.run(topology, flows, 0.05, 10.0, 256, 0.0);

        check(r1.getAdmittedFlows() == r2.getAdmittedFlows(), "admitted flows must match across runs");
        check(r1.getGeneratedPackets() == r2.getGeneratedPackets(), "generated packets must match");
        check(r1.getDeliveredPackets() == r2.getDeliveredPackets(), "delivered packets must match");
        check(r1.getTimelyPackets() == r2.getTimelyPackets(), "timely packets must match");
        check(r1.getDroppedPackets() == r2.getDroppedPackets(), "dropped packets must match");
        check(Double.compare(r1.getTimelyDeliveryRatio(), r2.getTimelyDeliveryRatio()) == 0, "TDR must match");
        check(Double.compare(r1.getAverageEndToEndDelay(), r2.getAverageEndToEndDelay()) == 0, "delay must match");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
