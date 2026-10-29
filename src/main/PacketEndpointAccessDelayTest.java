import java.util.Arrays;
import java.util.Collections;

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

/** Deterministic checks for endpoint-aware packet timing and accounting. */
public class PacketEndpointAccessDelayTest {
    private static final double TOLERANCE = 1.0e-12;

    public static void main(String[] args) {
        testZeroEndpointDelaysPreserveLegacyTiming();
        testSourceAccessDelayCanCauseDeadlineMiss();
        testDestinationAccessDelayCanCauseDeadlineMiss();
        testExactDeadlineIsTimelyAndCountsRemainConsistent();
        System.out.println("PacketEndpointAccessDelayTest passed.");
    }

    private static void testZeroEndpointDelaysPreserveLegacyTiming() {
        PacketSimulationResult legacy = runSinglePacket(false, 1.0, 0.0, 0.0);
        PacketSimulationResult explicitZero = runSinglePacket(true, 1.0, 0.0, 0.0);
        PacketSimulationResult.PacketDelivery oldDelivery = legacy.getPacketDeliveries().get(0);
        PacketSimulationResult.PacketDelivery newDelivery = explicitZero.getPacketDeliveries().get(0);

        assertNear(oldDelivery.getCreationTime(), newDelivery.getCreationTime(),
                "zero-delay creation time");
        assertNear(oldDelivery.getDeliveryTime(), newDelivery.getDeliveryTime(),
                "zero-delay delivery time");
        assertNear(oldDelivery.getEndToEndDelay(), newDelivery.getEndToEndDelay(),
                "zero-delay end-to-end delay");
        assertEquals(legacy.getTotalPackets(), explicitZero.getTotalPackets(),
                "zero-delay generated count");
        assertEquals(legacy.getDeliveredPackets(), explicitZero.getDeliveredPackets(),
                "zero-delay delivered count");
        assertEquals(legacy.getPacketsDeliveredBeforeDeadline(),
                explicitZero.getPacketsDeliveredBeforeDeadline(), "zero-delay timely count");
        assertNear(1.0, oldDelivery.getEndToEndDelay(), "legacy zero-delay baseline");
    }

    private static void testSourceAccessDelayCanCauseDeadlineMiss() {
        Packet rankProbe = new Packet(90, 90, 10.0, 0.0,
                1000, 0, 0.5, 0.25);
        assertNear(0.75, rankProbe.getDeadlineRank(),
                "first CEDF rank starts at source-router arrival");
        rankProbe.updateDeadlineRank();
        assertNear(0.75, rankProbe.getDeadlineRank(),
                "recalculating first-hop rank preserves the source-router time origin");
        rankProbe.moveToNextHop(1.0);
        assertNear(1.75, rankProbe.getDeadlineRank(),
                "next hop adds only its local budget to cumulative rank");

        PacketSimulationResult result = runSinglePacket(true, 1.1, 0.25, 0.0);
        PacketSimulationResult.PacketDelivery delivery = result.getPacketDeliveries().get(0);

        assertNear(0.0, delivery.getCreationTime(), "source-delay user release time");
        assertNear(1.25, delivery.getDeliveryTime(), "source access included in delivery time");
        assertNear(1.25, delivery.getEndToEndDelay(), "source-delay E2E delay");
        assertNear(0.25, result.getTransmissions().get(0).getTransmissionStartTime(),
                "source access delays arrival at the source router");
        assertTrue(!delivery.isMetDeadline(), "source access delay makes the packet late");
        assertCountsConsistent(result);
    }

    private static void testDestinationAccessDelayCanCauseDeadlineMiss() {
        PacketSimulationResult result = runSinglePacket(true, 1.1, 0.0, 0.25);
        PacketSimulationResult.PacketDelivery delivery = result.getPacketDeliveries().get(0);

        assertNear(1.25, delivery.getDeliveryTime(),
                "destination access included before user delivery");
        assertNear(1.25, delivery.getEndToEndDelay(), "destination-delay E2E delay");
        assertTrue(!delivery.isMetDeadline(), "destination access delay makes the packet late");
        assertCountsConsistent(result);
    }

    private static void testExactDeadlineIsTimelyAndCountsRemainConsistent() {
        PacketSimulationResult exact = runSinglePacket(true, 1.0, 0.0, 0.0);
        PacketSimulationResult.PacketDelivery delivery = exact.getPacketDeliveries().get(0);
        assertNear(1.0, delivery.getEndToEndDelay(), "packet arrives exactly at deadline");
        assertTrue(delivery.isMetDeadline(), "deadline equality is timely (delay <= deadline)");

        PacketSimulationResult withDrop = runBurstForAccounting();
        System.out.println("Accounting observed: generated=" + withDrop.getTotalPackets()
                + ", delivered=" + withDrop.getDeliveredPackets()
                + ", dropped=" + withDrop.getDroppedPackets()
                + ", timely=" + withDrop.getPacketsDeliveredBeforeDeadline()
                + ", late=" + withDrop.getPacketsMissingDeadline());
        assertEquals(3, withDrop.getTotalPackets(), "generated packets in accounting case");
        assertEquals(1, withDrop.getDeliveredPackets(), "delivered packets in accounting case");
        assertEquals(2, withDrop.getDroppedPackets(), "queue-dropped packets in accounting case");
        assertEquals(1, withDrop.getPacketsDeliveredBeforeDeadline(),
                "timely delivered packets in accounting case");
        assertEquals(0, withDrop.getPacketsMissingDeadline(),
                "late delivered packets in accounting case");
        assertCountsConsistent(withDrop);
        System.out.println("Endpoint timing assertions: zero baseline preserved; source/destination "
                + "delay misses and exact-deadline success verified.");
        System.out.println("Accounting case: generated=3, delivered=1, dropped=2, timely=1, late=0.");
    }

    private static PacketSimulationResult runSinglePacket(boolean explicitDelays,
                                                          double deadlineMs,
                                                          double sourceDelayMs,
                                                          double destinationDelayMs) {
        NetworkTopology topology = new NetworkTopology();
        Router source = new Router(1, "EndpointSource");
        Router destination = new Router(2, "EndpointDestination");
        topology.addRouter(source);
        topology.addRouter(destination);
        // 1000 bytes = 8000 bits; 8 Mbps = 8000 bits/ms, so serialization is 1 ms.
        topology.addLink(new Link(source, destination, 0.0, 8.0));
        TrafficFlow flow = explicitDelays
                ? new TrafficFlow(1, source, destination, 0.0, 1.0,
                        deadlineMs, sourceDelayMs, destinationDelayMs)
                : new TrafficFlow(1, source, destination, 0.0, 1.0, deadlineMs);
        return simulate(topology, flow, 2);
    }

    private static PacketSimulationResult runBurstForAccounting() {
        NetworkTopology topology = new NetworkTopology();
        Router source = new Router(1, "AccountingSource");
        Router destination = new Router(2, "AccountingDestination");
        topology.addRouter(source);
        topology.addRouter(destination);
        topology.addLink(new Link(source, destination, 0.0, 8.0));
        TrafficFlow flow = new TrafficFlow(7, source, destination,
                0.0, 3.0, 1.0, 0.0, 0.0);
        return simulate(topology, flow, 1);
    }

    private static PacketSimulationResult simulate(NetworkTopology topology,
                                                    TrafficFlow flow,
                                                    int queueCapacity) {
        PathDecision hop = new PathDecision(flow.getSource(), flow.getDestination(), 2.0);
        MAPPOEpisodeResult admitted = new MAPPOEpisodeResult(true, true,
                Collections.singletonList(hop), new TrajectoryBuffer(), 1.0);
        PacketSimulator simulator = new PacketSimulator(topology,
                Arrays.asList(new PacketSimulator.FlowInput(flow, admitted)),
                0.0, 0.0, queueCapacity);
        return simulator.simulate();
    }

    private static void assertCountsConsistent(PacketSimulationResult result) {
        assertEquals(result.getTotalPackets(),
                result.getDeliveredPackets() + result.getDroppedPackets(),
                "every generated packet is delivered or dropped");
        assertEquals(result.getDeliveredPackets(),
                result.getPacketsDeliveredBeforeDeadline() + result.getPacketsMissingDeadline(),
                "delivered packets partition into timely and late");
        PacketSimulationResult.FlowMetrics flow = result.getPerFlowMetrics().values()
                .iterator().next();
        assertEquals(flow.getGeneratedPackets(),
                flow.getDeliveredPackets() + flow.getDroppedPackets(),
                "per-flow generated count is conserved");
        assertEquals(flow.getDeliveredPackets(),
                flow.getTimelyPackets() + flow.getMissedDeadlinePackets(),
                "per-flow delivery classification is conserved");
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void assertEquals(int expected, int actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertNear(double expected, double actual, String message) {
        if (Math.abs(expected - actual) > TOLERANCE) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }
}
