package simulation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Aggregate and per-flow metrics from one packet-level batch simulation. */
public class PacketSimulationResult {
    private final int totalPackets;
    private final int deliveredPackets;
    private final int packetsDeliveredBeforeDeadline;
    private final int packetsMissingDeadline;
    private final int droppedPackets;
    private final double timelyDeliveryRatio;
    private final double averageEndToEndDelay;
    private final List<PacketDelivery> packetDeliveries;
    private final Map<Integer, FlowMetrics> perFlowMetrics;
    private final List<PacketTransmission> transmissions;
    private final Map<String, Integer> dequeuedPacketsByRouter;
    private final Map<String, Integer> transmittedPacketsByLink;
    private final Map<String, Integer> maxQueueSizeByLink;
    private final int totalHopTransmissions;
    private final int hopTransmissionsMeetingRank;

    public PacketSimulationResult(
            int totalPackets,
            int deliveredPackets,
            int packetsDeliveredBeforeDeadline,
            int packetsMissingDeadline,
            int droppedPackets,
            double timelyDeliveryRatio,
            double averageEndToEndDelay,
            List<PacketDelivery> packetDeliveries,
            Map<Integer, FlowMetrics> perFlowMetrics,
            List<PacketTransmission> transmissions,
            Map<String, Integer> dequeuedPacketsByRouter,
            Map<String, Integer> transmittedPacketsByLink,
            Map<String, Integer> maxQueueSizeByLink) {
        this(totalPackets, deliveredPackets, packetsDeliveredBeforeDeadline,
                packetsMissingDeadline, droppedPackets, timelyDeliveryRatio,
                averageEndToEndDelay, packetDeliveries, perFlowMetrics,
                transmissions, dequeuedPacketsByRouter, transmittedPacketsByLink,
                maxQueueSizeByLink,
                transmissions != null ? transmissions.size() : 0,
                transmissions != null ? (int) transmissions.stream().filter(PacketTransmission::isMetHopRank).count() : 0);
    }

    public PacketSimulationResult(
            int totalPackets,
            int deliveredPackets,
            int packetsDeliveredBeforeDeadline,
            int packetsMissingDeadline,
            int droppedPackets,
            double timelyDeliveryRatio,
            double averageEndToEndDelay,
            List<PacketDelivery> packetDeliveries,
            Map<Integer, FlowMetrics> perFlowMetrics,
            List<PacketTransmission> transmissions,
            Map<String, Integer> dequeuedPacketsByRouter,
            Map<String, Integer> transmittedPacketsByLink,
            Map<String, Integer> maxQueueSizeByLink,
            int totalHopTransmissions,
            int hopTransmissionsMeetingRank) {
        this.totalPackets = totalPackets;
        this.deliveredPackets = deliveredPackets;
        this.packetsDeliveredBeforeDeadline = packetsDeliveredBeforeDeadline;
        this.packetsMissingDeadline = packetsMissingDeadline;
        this.droppedPackets = droppedPackets;
        this.timelyDeliveryRatio = timelyDeliveryRatio;
        this.averageEndToEndDelay = averageEndToEndDelay;
        this.packetDeliveries = new ArrayList<>(packetDeliveries);
        this.perFlowMetrics = new LinkedHashMap<>(perFlowMetrics);
        this.transmissions = new ArrayList<>(transmissions);
        this.dequeuedPacketsByRouter = new LinkedHashMap<>(dequeuedPacketsByRouter);
        this.transmittedPacketsByLink = new LinkedHashMap<>(transmittedPacketsByLink);
        this.maxQueueSizeByLink = new LinkedHashMap<>(maxQueueSizeByLink);
        this.totalHopTransmissions = totalHopTransmissions;
        this.hopTransmissionsMeetingRank = hopTransmissionsMeetingRank;
    }

    public int getTotalPackets() { return totalPackets; }
    public int getDeliveredPackets() { return deliveredPackets; }
    public int getPacketsDeliveredBeforeDeadline() { return packetsDeliveredBeforeDeadline; }
    /** Delivered packets whose end-to-end delay exceeded the flow deadline. */
    public int getPacketsMissingDeadline() { return packetsMissingDeadline; }
    public int getDroppedPackets() { return droppedPackets; }
    public double getTimelyDeliveryRatio() { return timelyDeliveryRatio; }
    public double getAverageEndToEndDelay() { return averageEndToEndDelay; }
    public List<PacketDelivery> getPacketDeliveries() { return new ArrayList<>(packetDeliveries); }
    public Map<Integer, FlowMetrics> getPerFlowMetrics() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(perFlowMetrics));
    }
    public List<PacketTransmission> getTransmissions() { return new ArrayList<>(transmissions); }
    public Map<String, Integer> getDequeuedPacketsByRouter() {
        return new LinkedHashMap<>(dequeuedPacketsByRouter);
    }
    public Map<String, Integer> getTransmittedPacketsByLink() {
        return new LinkedHashMap<>(transmittedPacketsByLink);
    }
    public Map<String, Integer> getMaxQueueSizeByLink() {
        return new LinkedHashMap<>(maxQueueSizeByLink);
    }
    /** Total hop-level packet transmissions evaluated for Eq. (6). */
    public int getTotalHopTransmissions() { return totalHopTransmissions; }
    /** Hop-level transmissions that departed at or before single-hop deadline rank (Eq. 6). */
    public int getHopTransmissionsMeetingRank() { return hopTransmissionsMeetingRank; }
    /** Fraction of hop-level packet departures meeting Eq. (6): s_{f,i}^{(k)} <= d_{f,i}^{(k)}. */
    public double getHopDeadlineSatisfactionRatio() {
        return totalHopTransmissions == 0 ? 1.0 : (double) hopTransmissionsMeetingRank / totalHopTransmissions;
    }

    public static final class FlowMetrics {
        private final int generatedPackets;
        private final int deliveredPackets;
        private final int timelyPackets;
        private final int missedDeadlinePackets;
        private final int droppedPackets;
        private final double timelyDeliveryRatio;

        public FlowMetrics(int generatedPackets, int deliveredPackets,
                           int timelyPackets, int missedDeadlinePackets,
                           int droppedPackets, double timelyDeliveryRatio) {
            this.generatedPackets = generatedPackets;
            this.deliveredPackets = deliveredPackets;
            this.timelyPackets = timelyPackets;
            this.missedDeadlinePackets = missedDeadlinePackets;
            this.droppedPackets = droppedPackets;
            this.timelyDeliveryRatio = timelyDeliveryRatio;
        }

        public int getGeneratedPackets() { return generatedPackets; }
        public int getDeliveredPackets() { return deliveredPackets; }
        public int getTimelyPackets() { return timelyPackets; }
        public int getMissedDeadlinePackets() { return missedDeadlinePackets; }
        public int getDroppedPackets() { return droppedPackets; }
        public double getTimelyDeliveryRatio() { return timelyDeliveryRatio; }
    }

    public static final class PacketDelivery {
        private final int packetId;
        private final int flowId;
        private final double creationTime;
        private final double deliveryTime;
        private final double endToEndDelay;
        private final boolean metDeadline;

        public PacketDelivery(int packetId, int flowId, double creationTime,
                              double deliveryTime, double endToEndDelay,
                              boolean metDeadline) {
            this.packetId = packetId;
            this.flowId = flowId;
            this.creationTime = creationTime;
            this.deliveryTime = deliveryTime;
            this.endToEndDelay = endToEndDelay;
            this.metDeadline = metDeadline;
        }

        public int getPacketId() { return packetId; }
        public int getFlowId() { return flowId; }
        public double getCreationTime() { return creationTime; }
        public double getDeliveryTime() { return deliveryTime; }
        public double getEndToEndDelay() { return endToEndDelay; }
        public boolean isMetDeadline() { return metDeadline; }
    }

    /** Transmission audit record, including the rate-derived transmission time and Eq. (6) check. */
    public static final class PacketTransmission {
        private final int packetId;
        private final int flowId;
        private final String directedLink;
        private final double transmissionStartTime;
        private final double transmissionEndTime;
        private final double transmissionDelay;
        private final double deadlineRank;
        private final boolean metHopRank;

        public PacketTransmission(int packetId, int flowId, String directedLink,
                                  double transmissionStartTime,
                                  double transmissionEndTime,
                                  double transmissionDelay) {
            this(packetId, flowId, directedLink, transmissionStartTime,
                    transmissionEndTime, transmissionDelay, Double.POSITIVE_INFINITY, true);
        }

        public PacketTransmission(int packetId, int flowId, String directedLink,
                                  double transmissionStartTime,
                                  double transmissionEndTime,
                                  double transmissionDelay,
                                  double deadlineRank,
                                  boolean metHopRank) {
            this.packetId = packetId;
            this.flowId = flowId;
            this.directedLink = directedLink;
            this.transmissionStartTime = transmissionStartTime;
            this.transmissionEndTime = transmissionEndTime;
            this.transmissionDelay = transmissionDelay;
            this.deadlineRank = deadlineRank;
            this.metHopRank = metHopRank;
        }

        public int getPacketId() { return packetId; }
        public int getFlowId() { return flowId; }
        public String getDirectedLink() { return directedLink; }
        public double getTransmissionStartTime() { return transmissionStartTime; }
        public double getTransmissionEndTime() { return transmissionEndTime; }
        public double getTransmissionDelay() { return transmissionDelay; }
        public double getDeadlineRank() { return deadlineRank; }
        /** Eq. (6) - True if packet departure s_{f,i}^{(k)} <= single-hop deadline d_{f,i}^{(k)}. */
        public boolean isMetHopRank() { return metHopRank; }
    }
}
