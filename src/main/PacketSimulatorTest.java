import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import network.Link;
import network.NetworkTopology;
import network.Router;
import routing.MAPPOEpisodeResult;
import routing.PathDecision;
import routing.TrajectoryBuffer;
import simulation.PacketSimulationResult;
import simulation.PacketSimulator;
import traffic.Packet;
import traffic.TrafficFlow;

/** Multi-flow shared-egress and bandwidth test for the event-driven simulator. */
public class PacketSimulatorTest {
    private static final int BOTTLENECK_MBPS = 1;
    private static final double PROCESSING_DELAY_MS = 0.05;
    private static final double WINDOW_MS = 1.0;
    private static final int QUEUE_CAPACITY = 4;

    public static void main(String[] args) {
        testBurstUnitsAndPacketRounding();
        testMultihopCumulativeRankControlsLaterEgressOrder();
        PacketSimulationResult oneMbps = runScenario(BOTTLENECK_MBPS);
        PacketSimulationResult twoMbps = runScenario(2);

        assertEquals(9, oneMbps.getTotalPackets(), "generated packet count");
        assertEquals(oneMbps.getTotalPackets(),
                oneMbps.getDeliveredPackets() + oneMbps.getDroppedPackets(),
                "each generated packet is delivered or queue-dropped");
        assertTrue(oneMbps.getDroppedPackets() > 0,
                "finite shared egress queue should be able to drop when full");
        assertTrue(oneMbps.getMaxQueueSizeByLink().get("B->D") == QUEUE_CAPACITY,
                "both admitted flows should fill the same B->D egress queue");

        Set<Integer> flowsOnBottleneck = new HashSet<>();
        List<PacketSimulationResult.PacketTransmission> bottleneckTransmissions =
                transmissionsOn(oneMbps, "B->D");
        for (PacketSimulationResult.PacketTransmission transmission : bottleneckTransmissions) {
            flowsOnBottleneck.add(transmission.getFlowId());
            assertNear(8.0, transmission.getTransmissionDelay(), 1.0e-12,
                    "1000-byte transmission at 1 Mbps");
        }
        assertTrue(flowsOnBottleneck.contains(1) && flowsOnBottleneck.contains(2),
                "both competing flows must use the same B->D link resource");
        assertNonOverlapping(bottleneckTransmissions);

        for (PacketSimulationResult.PacketTransmission transmission
                : transmissionsOn(twoMbps, "B->D")) {
            assertNear(4.0, transmission.getTransmissionDelay(), 1.0e-12,
                    "doubling bandwidth halves transmission delay");
        }

        assertTrue(oneMbps.getTransmittedPacketsByLink().containsKey("E->F"),
                "separate flow should use its independent directed link");
        assertTrue(transmissionsOverlap(
                        transmissionsOn(oneMbps, "E->F"),
                        bottleneckTransmissions),
                "separate egress can transmit while B->D is busy");
        double earliestSeparateDelivery = oneMbps.getPacketDeliveries().stream()
                .filter(delivery -> delivery.getFlowId() == 3)
                .mapToDouble(PacketSimulationResult.PacketDelivery::getDeliveryTime)
                .min().orElseThrow(() -> new AssertionError("separate flow was not delivered"));
        assertNear(PROCESSING_DELAY_MS + 0.8 + 0.3, earliestSeparateDelivery,
                1.0e-12, "processing + bandwidth-derived transmission + link propagation delay");

        PacketSimulationResult.FlowMetrics flowOne = oneMbps.getPerFlowMetrics().get(1);
        PacketSimulationResult.FlowMetrics flowTwo = oneMbps.getPerFlowMetrics().get(2);
        PacketSimulationResult.FlowMetrics flowSeparate = oneMbps.getPerFlowMetrics().get(3);
        assertEquals(3, flowOne.getGeneratedPackets(), "flow 1 burst packet count");
        assertEquals(3, flowTwo.getGeneratedPackets(), "flow 2 burst packet count");
        assertEquals(3, flowSeparate.getGeneratedPackets(),
                "flow 3 burst plus one rate-generated packet in the window");
        assertEquals(0, oneMbps.getPerFlowMetrics().get(4).getGeneratedPackets(),
                "unqualified flow must not generate packets");
        assertNear(flowOne.getTimelyPackets() / 3.0,
                flowOne.getTimelyDeliveryRatio(), 1.0e-12, "per-flow TDR");

        printResult(oneMbps);
        System.out.println("PacketSimulatorTest passed.");
    }

    private static void testBurstUnitsAndPacketRounding() {
        PacketSimulationResult exact = runBurstOnly(50.0);
        int exactBurstPackets = exact.getPerFlowMetrics().get(1).getGeneratedPackets();
        assertEquals(50, exactBurstPackets,
                "50 decimal KB should generate exactly fifty 1000-byte packets");
        assertTrue(exactBurstPackets * 1000.0 <= 50.0 * 1000.0,
                "generated 50 KB burst must not exceed its configured envelope");

        PacketSimulationResult fractional = runBurstOnly(2.5);
        int fractionalBurstPackets =
                fractional.getPerFlowMetrics().get(1).getGeneratedPackets();
        assertEquals(2, fractionalBurstPackets,
                "a 2.5 KB burst should generate only whole 1000-byte packets that fit");
        assertTrue(fractionalBurstPackets * 1000.0 <= 2.5 * 1000.0,
                "packet rounding must not make generated burst bytes exceed 2.5 KB");
    }

    private static void testMultihopCumulativeRankControlsLaterEgressOrder() {
        // Directly verify that advancing a packet adds the next local delta to
        // its existing absolute cumulative rank.
        Packet rankProbe = new Packet(99, 99, 20.0, 0.25,
                1000, 0, 1.0);
        assertNear(1.25, rankProbe.getDeadlineRank(), 1.0e-12,
                "first-hop absolute rank is creation time plus local delta");
        rankProbe.moveToNextHop(10.0);
        assertEquals(1, rankProbe.getHopIndex(), "hop index advances once");
        assertNear(11.25, rankProbe.getDeadlineRank(), 1.0e-12,
                "next local delta is added to the cumulative rank");

        NetworkTopology topology = new NetworkTopology();
        Router sourceA = router(topology, 10, "RankSourceA");
        Router sourceB = router(topology, 11, "RankSourceB");
        Router merge = router(topology, 12, "RankMerge");
        Router destination = router(topology, 13, "RankDestination");
        topology.addLink(new Link(sourceA, merge, 0.1, 10000.0));
        topology.addLink(new Link(sourceB, merge, 0.1, 10000.0));
        topology.addLink(new Link(merge, destination, 0.1, 10000.0));

        // Both packets arrive at the merge router at the same time. Correct
        // cumulative ranks are: flow 1 = 1+10 = 11 ms; flow 2 = 5+1 = 6 ms.
        // If the second local delta were not accumulated, flow 1 would wrongly
        // rank first (1 ms versus 5 ms), so the expected order is discriminating.
        TrafficFlow flow1 = new TrafficFlow(101, sourceA, destination,
                0.0, 1.0, 20.0);
        TrafficFlow flow2 = new TrafficFlow(102, sourceB, destination,
                0.0, 1.0, 20.0);
        MAPPOEpisodeResult route1 = admitted(
                new PathDecision(sourceA, merge, 1.0),
                new PathDecision(merge, destination, 10.0));
        MAPPOEpisodeResult route2 = admitted(
                new PathDecision(sourceB, merge, 5.0),
                new PathDecision(merge, destination, 1.0));
        PacketSimulator simulator = new PacketSimulator(topology,
                Arrays.asList(new PacketSimulator.FlowInput(flow1, route1),
                        new PacketSimulator.FlowInput(flow2, route2)),
                PROCESSING_DELAY_MS, WINDOW_MS, 4);
        PacketSimulationResult result = simulator.simulate();

        List<PacketSimulationResult.PacketTransmission> mergeEgress = new ArrayList<>();
        for (PacketSimulationResult.PacketTransmission transmission : result.getTransmissions()) {
            if ("RankMerge->RankDestination".equals(transmission.getDirectedLink())) {
                mergeEgress.add(transmission);
            }
        }
        assertEquals(2, mergeEgress.size(), "both multihop packets use the shared later egress");
        assertEquals(102, mergeEgress.get(0).getFlowId(),
                "lower cumulative rank (5+1 ms) dispatches first");
        assertEquals(101, mergeEgress.get(1).getFlowId(),
                "higher cumulative rank (1+10 ms) dispatches second");
        assertEquals(2, result.getDeliveredPackets(), "both packets reach their destination");
        assertEquals(0, result.getDroppedPackets(), "the test queue does not overflow");
        assertEquals(2, result.getPacketsDeliveredBeforeDeadline(),
                "both packets meet the 20 ms E2E deadline");
        assertNear(0.2008, mergeEgress.get(0).getTransmissionStartTime(), 1.0e-9,
                "first later-egress dispatch follows upstream processing, serialization, propagation, and processing");
        assertNear(0.2516, mergeEgress.get(1).getTransmissionStartTime(), 1.0e-9,
                "second later-egress dispatch waits for first packet service");
    }

    private static PacketSimulationResult runBurstOnly(double burstKB) {
        NetworkTopology topology = new NetworkTopology();
        Router source = router(topology, 1, "BurstSource");
        Router destination = router(topology, 2, "BurstDestination");
        topology.addLink(new Link(source, destination, 0.0, 10000.0));
        TrafficFlow flow = new TrafficFlow(1, source, destination,
                0.0, burstKB, 100.0);
        List<PacketSimulator.FlowInput> inputs = Collections.singletonList(
                new PacketSimulator.FlowInput(flow,
                        admitted(new PathDecision(source, destination, 1.0))));
        return new PacketSimulator(topology, inputs, 0.0,
                WINDOW_MS, 100).simulate();
    }

    private static PacketSimulationResult runScenario(double bottleneckMbps) {
        NetworkTopology topology = new NetworkTopology();
        Router a = router(topology, 1, "A");
        Router b = router(topology, 2, "B");
        Router c = router(topology, 3, "C");
        Router d = router(topology, 4, "D");
        Router e = router(topology, 5, "E");
        Router f = router(topology, 6, "F");
        topology.addLink(new Link(a, b, 0.1, 1000.0));
        topology.addLink(new Link(c, b, 0.1, 1000.0));
        topology.addLink(new Link(b, d, 0.2, bottleneckMbps));
        topology.addLink(new Link(e, f, 0.3, 10.0));

        TrafficFlow flow1 = new TrafficFlow(1, a, d, 0.0, 3.0, 100.0);
        TrafficFlow flow2 = new TrafficFlow(2, c, d, 0.0, 3.0, 100.0);
        // 8 Mbps for a 1 ms window contributes one full rate-generated packet.
        TrafficFlow separateFlow = new TrafficFlow(3, e, f, 8.0, 2.0, 100.0);
        TrafficFlow rejectedFlow = new TrafficFlow(4, a, d, 0.0, 100.0, 100.0);
        MAPPOEpisodeResult rejected = new MAPPOEpisodeResult(
                false, false, Collections.emptyList(), new TrajectoryBuffer(), 0.0);

        List<PacketSimulator.FlowInput> inputs = Arrays.asList(
                new PacketSimulator.FlowInput(flow1, admitted(
                        new PathDecision(a, b, 1.0),
                        new PathDecision(b, d, 1.0))),
                new PacketSimulator.FlowInput(flow2, admitted(
                        new PathDecision(c, b, 1.0),
                        new PathDecision(b, d, 0.5))),
                new PacketSimulator.FlowInput(separateFlow, admitted(
                        new PathDecision(e, f, 1.0))),
                new PacketSimulator.FlowInput(rejectedFlow, rejected));

        return new PacketSimulator(topology, inputs, PROCESSING_DELAY_MS,
                WINDOW_MS, QUEUE_CAPACITY).simulate();
    }

    private static MAPPOEpisodeResult admitted(PathDecision... decisions) {
        return new MAPPOEpisodeResult(true, true,
                Arrays.asList(decisions), new TrajectoryBuffer(), 1.0);
    }

    private static Router router(NetworkTopology topology, int id, String name) {
        Router router = new Router(id, name);
        topology.addRouter(router);
        return router;
    }

    private static List<PacketSimulationResult.PacketTransmission> transmissionsOn(
            PacketSimulationResult result, String link) {
        List<PacketSimulationResult.PacketTransmission> found = new ArrayList<>();
        for (PacketSimulationResult.PacketTransmission transmission : result.getTransmissions()) {
            if (link.equals(transmission.getDirectedLink())) found.add(transmission);
        }
        found.sort(Comparator.comparingDouble(
                PacketSimulationResult.PacketTransmission::getTransmissionStartTime));
        return found;
    }

    private static void assertNonOverlapping(
            List<PacketSimulationResult.PacketTransmission> transmissions) {
        for (int i = 1; i < transmissions.size(); i++) {
            assertTrue(transmissions.get(i).getTransmissionStartTime()
                            >= transmissions.get(i - 1).getTransmissionEndTime() - 1.0e-12,
                    "transmissions on one directed link must be serialized");
        }
    }

    private static boolean transmissionsOverlap(
            List<PacketSimulationResult.PacketTransmission> left,
            List<PacketSimulationResult.PacketTransmission> right) {
        for (PacketSimulationResult.PacketTransmission a : left) {
            for (PacketSimulationResult.PacketTransmission b : right) {
                if (a.getTransmissionStartTime() < b.getTransmissionEndTime()
                        && b.getTransmissionStartTime() < a.getTransmissionEndTime()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void printResult(PacketSimulationResult result) {
        System.out.println("Packets generated: " + result.getTotalPackets());
        System.out.println("Packets delivered: " + result.getDeliveredPackets());
        System.out.println("Packets delivered before deadline: "
                + result.getPacketsDeliveredBeforeDeadline());
        System.out.println("Delivered packets missing deadline: "
                + result.getPacketsMissingDeadline());
        System.out.println("Packets dropped: " + result.getDroppedPackets());
        System.out.println("TDR: " + result.getTimelyDeliveryRatio());
        System.out.println("Average E2E delay (ms): " + result.getAverageEndToEndDelay());
        System.out.println("Transmitted packets by link: " + result.getTransmittedPacketsByLink());
        System.out.println("Maximum queue size by link: " + result.getMaxQueueSizeByLink());
        for (Map.Entry<Integer, PacketSimulationResult.FlowMetrics> entry
                : result.getPerFlowMetrics().entrySet()) {
            System.out.println("Flow " + entry.getKey()
                    + " | delivered=" + entry.getValue().getDeliveredPackets()
                    + " | TDR=" + entry.getValue().getTimelyDeliveryRatio());
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void assertEquals(int expected, int actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertNear(double expected, double actual,
                                   double tolerance, String message) {
        if (Math.abs(expected - actual) > tolerance) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }
}
