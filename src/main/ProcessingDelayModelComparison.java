import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import network.Link;
import network.NetworkTopology;
import network.Router;
import routing.MAPPOEpisodeResult;
import routing.PathDecision;
import routing.TrajectoryBuffer;
import simulation.PacketSimulationResult;
import simulation.PacketSimulator;
import traffic.TrafficFlow;

/**
 * Deterministic comparison of serialized processing and fixed-latency processing.
 * This is a standalone experiment; it does not change PacketSimulator.
 */
public final class ProcessingDelayModelComparison {
    private static final int PACKET_BYTES = 1000;
    private static final int PACKET_COUNT = 3;
    private static final double PROCESSING_MS = 0.5;
    private static final double BANDWIDTH_MBPS = 8.0;
    private static final double PROPAGATION_MS = 0.25;
    private static final double DEADLINE_MS = 3.0;

    private ProcessingDelayModelComparison() { }

    public static void main(String[] args) {
        double transmissionMs = PACKET_BYTES * 8.0 / (BANDWIDTH_MBPS * 1000.0);
        PacketSimulationResult serialized = runExistingSimulator();
        ModelResult independent = runFixedLatencyModel(transmissionMs);

        System.out.println("Deterministic processing-delay model comparison");
        System.out.printf("Inputs: packets=%d, size=%d bytes, arrivals=%s ms, "
                        + "processing=%.3f ms, bandwidth=%.3f Mbps, "
                        + "transmission=%.3f ms, propagation=%.3f ms, deadline=%.3f ms%n",
                PACKET_COUNT, PACKET_BYTES, Arrays.toString(new double[]{0.0, 0.0, 0.0}),
                PROCESSING_MS, BANDWIDTH_MBPS, transmissionMs, PROPAGATION_MS, DEADLINE_MS);

        printExistingModel(serialized);
        printAlternativeModel(independent);

        assertEquals(PACKET_COUNT, serialized.getDeliveredPackets(),
                "existing model delivered packet count");
        assertEquals(PACKET_COUNT, independent.packetRows.size(),
                "alternative model packet count");
        assertNear(4.75, serialized.getPacketDeliveries().get(2).getDeliveryTime(),
                "existing model completion time");
        assertNear(3.75, independent.completionTimeMs,
                "alternative model completion time");
        assertNear(3.25, serialized.getAverageEndToEndDelay(),
                "existing model average delay");
        assertNear(2.75, independent.averageDelayMs,
                "alternative model average delay");
        assertEquals(1, serialized.getPacketsDeliveredBeforeDeadline(),
                "existing model timely packet count");
        assertEquals(2, independent.timelyPackets,
                "alternative model timely packet count");

        System.out.println("Comparison assertions passed.");
    }

    private static PacketSimulationResult runExistingSimulator() {
        NetworkTopology topology = new NetworkTopology();
        Router source = new Router(1, "Source");
        Router destination = new Router(2, "Destination");
        topology.addRouter(source);
        topology.addRouter(destination);
        topology.addLink(new Link(source, destination, PROPAGATION_MS, BANDWIDTH_MBPS));

        // A 3 KB time-zero burst gives exactly three 1000-byte packets.
        TrafficFlow flow = new TrafficFlow(1, source, destination,
                0.0, PACKET_COUNT, DEADLINE_MS);
        MAPPOEpisodeResult admitted = new MAPPOEpisodeResult(true, true,
                Arrays.asList(new PathDecision(source, destination, DEADLINE_MS)),
                new TrajectoryBuffer(), 0.0);
        PacketSimulator simulator = new PacketSimulator(topology,
                Arrays.asList(new PacketSimulator.FlowInput(flow, admitted)),
                PROCESSING_MS, 0.0, 10);
        PacketSimulationResult result = simulator.simulate();
        assertEquals(PACKET_COUNT, result.getTotalPackets(),
                "existing simulator generated count");
        return result;
    }

    /**
     * Processing is modeled as an independent fixed latency for every packet:
     * all packets arriving at time zero finish processing at 0.5 ms, while the
     * single egress link serializes only their transmissions.
     */
    private static ModelResult runFixedLatencyModel(double transmissionMs) {
        ModelResult result = new ModelResult();
        double nextLinkFreeMs = 0.0;
        double totalDelay = 0.0;
        // CEDFScheduler inserts equal-rank arrivals ahead of existing equals,
        // so this deterministic all-equal workload is served in reverse ID order.
        for (int packetId = PACKET_COUNT; packetId >= 1; packetId--) {
            double creation = 0.0;
            double processingStart = creation;
            double processingEnd = processingStart + PROCESSING_MS;
            double txStart = Math.max(processingEnd, nextLinkFreeMs);
            double txEnd = txStart + transmissionMs;
            double propagationEnd = txEnd + PROPAGATION_MS;
            double endToEnd = propagationEnd - creation;
            boolean timely = endToEnd <= DEADLINE_MS;
            result.packetRows.add(new PacketRow(packetId, creation,
                    processingStart, processingEnd, txStart, txEnd,
                    txEnd, propagationEnd, endToEnd, timely));
            nextLinkFreeMs = txEnd;
            totalDelay += endToEnd;
            if (timely) result.timelyPackets++;
        }
        result.completionTimeMs = result.packetRows.get(PACKET_COUNT - 1).deliveryTimeMs;
        result.averageDelayMs = totalDelay / PACKET_COUNT;
        return result;
    }

    private static void printExistingModel(PacketSimulationResult result) {
        Map<Integer, PacketSimulationResult.PacketTransmission> byPacket = new LinkedHashMap<>();
        for (PacketSimulationResult.PacketTransmission tx : result.getTransmissions()) {
            byPacket.put(tx.getPacketId(), tx);
        }
        System.out.println("\nModel A — current: processing + transmission serialized at egress");
        System.out.println("packet | process interval | transmit interval | propagation interval | E2E ms | timely");
        List<PacketSimulationResult.PacketDelivery> deliveries = result.getPacketDeliveries();
        deliveries.sort((a, b) -> Integer.compare(a.getPacketId(), b.getPacketId()));
        for (PacketSimulationResult.PacketDelivery delivery : deliveries) {
            PacketSimulationResult.PacketTransmission tx = byPacket.get(delivery.getPacketId());
            double processStart = tx.getTransmissionStartTime() - PROCESSING_MS;
            double propStart = tx.getTransmissionEndTime();
            System.out.printf("%d | [%.2f, %.2f] (%.2f) | [%.2f, %.2f] (%.2f) | "
                            + "[%.2f, %.2f] (%.2f) | %.2f | %s%n",
                    delivery.getPacketId(), processStart, tx.getTransmissionStartTime(),
                    PROCESSING_MS, tx.getTransmissionStartTime(), tx.getTransmissionEndTime(),
                    tx.getTransmissionDelay(), propStart, delivery.getDeliveryTime(),
                    PROPAGATION_MS, delivery.getEndToEndDelay(), delivery.isMetDeadline());
        }
        System.out.printf("completion=%.2f ms, average E2E=%.2f ms, timely=%d/%d%n",
                result.getPacketDeliveries().stream()
                        .mapToDouble(PacketSimulationResult.PacketDelivery::getDeliveryTime)
                        .max().orElse(0.0), result.getAverageEndToEndDelay(),
                result.getPacketsDeliveredBeforeDeadline(), result.getTotalPackets());
    }

    private static void printAlternativeModel(ModelResult result) {
        System.out.println("\nModel B — alternative: fixed processing latency; link serializes transmission only");
        System.out.println("packet | process interval | transmit interval | propagation interval | E2E ms | timely");
        for (PacketRow row : result.packetRows) {
            System.out.printf("%d | [%.2f, %.2f] (%.2f) | [%.2f, %.2f] (%.2f) | "
                            + "[%.2f, %.2f] (%.2f) | %.2f | %s%n",
                    row.packetId, row.processingStartMs, row.processingEndMs,
                    PROCESSING_MS, row.transmissionStartMs, row.transmissionEndMs,
                    row.transmissionEndMs - row.transmissionStartMs,
                    row.propagationStartMs, row.deliveryTimeMs, PROPAGATION_MS,
                    row.endToEndDelayMs, row.timely);
        }
        System.out.printf("completion=%.2f ms, average E2E=%.2f ms, timely=%d/%d%n",
                result.completionTimeMs, result.averageDelayMs,
                result.timelyPackets, PACKET_COUNT);
    }

    private static void assertEquals(int expected, int actual, String label) {
        if (expected != actual) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertNear(double expected, double actual, String label) {
        if (Math.abs(expected - actual) > 1.0e-9) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static final class ModelResult {
        final List<PacketRow> packetRows = new java.util.ArrayList<>();
        int timelyPackets;
        double completionTimeMs;
        double averageDelayMs;
    }

    private static final class PacketRow {
        final int packetId;
        final double creationTimeMs;
        final double processingStartMs;
        final double processingEndMs;
        final double transmissionStartMs;
        final double transmissionEndMs;
        final double propagationStartMs;
        final double deliveryTimeMs;
        final double endToEndDelayMs;
        final boolean timely;

        PacketRow(int packetId, double creationTimeMs,
                  double processingStartMs, double processingEndMs,
                  double transmissionStartMs, double transmissionEndMs,
                  double propagationStartMs, double deliveryTimeMs,
                  double endToEndDelayMs, boolean timely) {
            this.packetId = packetId;
            this.creationTimeMs = creationTimeMs;
            this.processingStartMs = processingStartMs;
            this.processingEndMs = processingEndMs;
            this.transmissionStartMs = transmissionStartMs;
            this.transmissionEndMs = transmissionEndMs;
            this.propagationStartMs = propagationStartMs;
            this.deliveryTimeMs = deliveryTimeMs;
            this.endToEndDelayMs = endToEndDelayMs;
            this.timely = timely;
        }
    }
}
