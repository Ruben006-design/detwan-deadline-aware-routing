package simulation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import network.Link;
import network.NetworkTopology;
import network.Router;
import routing.MAPPOEpisodeResult;
import routing.PathDecision;
import scheduling.CEDFScheduler;
import traffic.Packet;
import traffic.TrafficFlow;

/** Event-driven packet simulation over routes already selected and admitted by MAPPO. */
public class PacketSimulator {
    private static final int PACKET_SIZE_BYTES = 1000;
    private static final int BYTES_PER_KB = 1000;

    private final NetworkTopology topology;
    private final List<FlowInput> inputs;
    private final double processingDelayMs;
    private final double simulationWindowMs;
    private final int queueCapacity;
    private final Double legacyPropagationDelayMs;

    /**
     * Simulate a batch. Link propagation delays come from each directed Link.
     * Queue capacity applies independently to every directed egress link.
     */
    public PacketSimulator(NetworkTopology topology, List<FlowInput> inputs,
                           double processingDelayMs, double simulationWindowMs,
                           int queueCapacity) {
        this(topology, inputs, processingDelayMs, simulationWindowMs,
                queueCapacity, null);
    }

    /**
     * Backward-compatible single-flow entry point. The flow deadline remains
     * the rate-generation window, and the supplied propagation delay overrides
     * link propagation for this legacy call. New batch simulations use Link data.
     */
    public PacketSimulator(NetworkTopology topology, TrafficFlow flow,
                           MAPPOEpisodeResult episodeResult,
                           double processingDelay, double propagationDelay) {
        this(topology, singletonInput(flow, episodeResult), processingDelay,
                flow == null ? 0.0 : flow.getDeadline(), Integer.MAX_VALUE,
                propagationDelay);
    }

    private PacketSimulator(NetworkTopology topology, List<FlowInput> inputs,
                            double processingDelayMs, double simulationWindowMs,
                            int queueCapacity, Double legacyPropagationDelayMs) {
        if (topology == null || inputs == null) {
            throw new IllegalArgumentException("Topology and flow inputs are required");
        }
        if (!Double.isFinite(processingDelayMs) || processingDelayMs < 0.0
                || !Double.isFinite(simulationWindowMs) || simulationWindowMs < 0.0
                || queueCapacity <= 0) {
            throw new IllegalArgumentException(
                    "Processing delay and simulation window must be non-negative; queue capacity must be positive");
        }
        if (legacyPropagationDelayMs != null
                && (!Double.isFinite(legacyPropagationDelayMs)
                    || legacyPropagationDelayMs < 0.0)) {
            throw new IllegalArgumentException(
                    "Propagation delay must be finite and non-negative");
        }
        this.topology = topology;
        this.inputs = new ArrayList<>(inputs);
        this.processingDelayMs = processingDelayMs;
        this.simulationWindowMs = simulationWindowMs;
        this.queueCapacity = queueCapacity;
        this.legacyPropagationDelayMs = legacyPropagationDelayMs;
        for (FlowInput input : this.inputs) {
            if (input == null) {
                throw new IllegalArgumentException("Flow inputs cannot contain null");
            }
        }
    }

    private static List<FlowInput> singletonInput(
            TrafficFlow flow, MAPPOEpisodeResult result) {
        List<FlowInput> inputs = new ArrayList<>();
        inputs.add(new FlowInput(flow, result));
        return inputs;
    }

    public PacketSimulationResult simulate() {
        Map<Integer, FlowContext> contexts = new LinkedHashMap<>();
        Map<Link, PortState> ports = new LinkedHashMap<>();
        int nextPacketId = 1;

        for (FlowInput input : inputs) {
            TrafficFlow flow = input.getFlow();
            if (contexts.containsKey(flow.getFlowId())) {
                throw new IllegalArgumentException("Flow IDs must be unique in a batch");
            }
            FlowContext context = new FlowContext(flow, input.getEpisodeResult());
            contexts.put(flow.getFlowId(), context);

            if (!context.isAdmitted()) {
                continue;
            }
            context.route = context.episodeResult.getPathDecisions();
            validateRoute(flow, context.route);
            for (PathDecision decision : context.route) {
                Link link = getLink(decision.getRouter(), decision.getNextHop());
                ports.putIfAbsent(link, new PortState(link, new CEDFScheduler(queueCapacity)));
            }
            context.packets = generatePackets(flow, context.route, nextPacketId);
            nextPacketId += context.packets.size();
            context.generated = context.packets.size();
        }

        PriorityQueue<Event> events = new PriorityQueue<>(
                Comparator.comparingDouble((Event event) -> event.time)
                        .thenComparingLong(event -> event.sequence));
        List<PacketSimulationResult.PacketDelivery> deliveries = new ArrayList<>();
        List<PacketSimulationResult.PacketTransmission> transmissions = new ArrayList<>();
        Map<String, Integer> dequeuedPacketsByRouter = new HashMap<>();
        Map<String, Integer> transmittedPacketsByLink = new HashMap<>();
        Map<String, Integer> maxQueueSizeByLink = new HashMap<>();
        long nextSequence = 0;

        // Packet creation is the user-side release time. Source access delay
        // advances only the router-arrival event; it does not change creationTime.
        for (FlowContext context : contexts.values()) {
            for (Packet packet : context.packets) {
                double sourceRouterArrival = packet.getCreationTime()
                        + context.flow.getSourceAccessDelayMs();
                events.add(Event.arrival(sourceRouterArrival, nextSequence++,
                        packet, context.flow.getSource(), null));
            }
        }

        while (!events.isEmpty()) {
            double currentTime = events.peek().time;
            List<Event> simultaneousEvents = new ArrayList<>();
            while (!events.isEmpty()
                    && Double.compare(events.peek().time, currentTime) == 0) {
                simultaneousEvents.add(events.remove());
            }

            // Process all completions and arrivals at this instant before dispatching
            // any port. This lets CEDF rank all simultaneous arrivals together.
            for (Event event : simultaneousEvents) {
                if (event.type == EventType.SERVICE_COMPLETE) {
                    PortState port = event.port;
                    port.busy = false;
                    FlowContext context = contexts.get(event.packet.getFlowId());
                    PathDecision hop = context.route.get(event.packet.getHopIndex());
                    if (!hop.getRouter().equals(port.link.getSource())
                            || !hop.getNextHop().equals(port.link.getDestination())) {
                        throw new IllegalStateException("Packet transmission does not match its MAPPO route");
                    }

                    double arrivalTime = currentTime + propagationDelay(port.link);
                    if (port.link.getDestination().equals(context.flow.getDestination())) {
                        // Destination access delay is applied after the packet
                        // reaches the destination router and before user delivery.
                        events.add(Event.arrival(arrivalTime
                                        + context.flow.getDestinationAccessDelayMs(),
                                nextSequence++,
                                event.packet, context.flow.getDestination(), null));
                    } else {
                        int nextHopIndex = event.packet.getHopIndex() + 1;
                        if (nextHopIndex >= context.route.size()) {
                            throw new IllegalStateException("Admitted route ended before the destination");
                        }
                        // This is the one rank update for the next hop. Queue insertion
                        // only orders by the resulting rank (CEDFScheduler is unchanged).
                        event.packet.moveToNextHop(
                                context.route.get(nextHopIndex).getDeadlineBudget());
                        events.add(Event.arrival(arrivalTime, nextSequence++, event.packet,
                                port.link.getDestination(), null));
                    }
                } else {
                    FlowContext context = contexts.get(event.packet.getFlowId());
                    if (event.router.equals(context.flow.getDestination())) {
                        deliveries.add(createDelivery(context.flow, event.packet, currentTime));
                        context.delivered++;
                        if (endToEndDelay(event.packet, currentTime)
                                <= context.flow.getDeadline()) {
                            context.timely++;
                        }
                        continue;
                    }

                    PathDecision hop = context.route.get(event.packet.getHopIndex());
                    if (!hop.getRouter().equals(event.router)) {
                        throw new IllegalStateException("Packet arrived at a router outside its MAPPO route");
                    }
                    Link link = getLink(hop.getRouter(), hop.getNextHop());
                    PortState port = ports.get(link);
                    if (!port.scheduler.enqueuePacket(event.packet)) {
                        context.dropped++;
                        continue;
                    }
                    maxQueueSizeByLink.merge(linkName(link), port.scheduler.getQueueSize(), Math::max);
                }
            }

            for (PortState port : ports.values()) {
                if (port.busy || !port.scheduler.hasPackets()) {
                    continue;
                }
                Packet packet = port.scheduler.selectNextPacket();
                port.busy = true;
                double transmissionDelay = transmissionDelayMs(packet, port.link);
                double transmissionStart = currentTime + processingDelayMs;
                double transmissionEnd = transmissionStart + transmissionDelay;
                increment(dequeuedPacketsByRouter, port.link.getSource().getName());
                increment(transmittedPacketsByLink, linkName(port.link));
                // Eq. (6) - Record departure time s_{f,i}^{(k)} and verify against deadline rank d_{f,i}^{(k)}.
                double hopRank = packet.getDeadlineRank();
                boolean metHopRank = transmissionEnd <= hopRank + 1.0e-9;
                transmissions.add(new PacketSimulationResult.PacketTransmission(
                        packet.getPacketId(), packet.getFlowId(), linkName(port.link),
                        transmissionStart, transmissionEnd, transmissionDelay,
                        hopRank, metHopRank));
                events.add(Event.serviceComplete(transmissionEnd, nextSequence++,
                        packet, port.link.getSource(), port));
            }
        }

        Map<Integer, PacketSimulationResult.FlowMetrics> flowMetrics = new LinkedHashMap<>();
        int totalGenerated = 0;
        int dropped = 0;
        for (FlowContext context : contexts.values()) {
            totalGenerated += context.generated;
            dropped += context.dropped;
            int missed = context.delivered - context.timely;
            double tdr = context.generated == 0
                    ? 0.0 : context.timely / (double) context.generated;
            flowMetrics.put(context.flow.getFlowId(),
                    new PacketSimulationResult.FlowMetrics(
                            context.generated, context.delivered, context.timely,
                            missed, context.dropped, tdr));
        }

        int delivered = deliveries.size();
        int timely = 0;
        double totalDelay = 0.0;
        for (PacketSimulationResult.PacketDelivery delivery : deliveries) {
            if (delivery.isMetDeadline()) timely++;
            totalDelay += delivery.getEndToEndDelay();
        }
        int missed = delivered - timely;
        return new PacketSimulationResult(
                totalGenerated, delivered, timely, missed, dropped,
                totalGenerated == 0 ? 0.0 : timely / (double) totalGenerated,
                delivered == 0 ? 0.0 : totalDelay / delivered,
                deliveries, flowMetrics, transmissions,
                dequeuedPacketsByRouter, transmittedPacketsByLink,
                maxQueueSizeByLink);
    }

    /**
     * Project workload assumption: 1000-byte packets; whole burst packets that
     * fit within the decimal-KB burst envelope arrive at time zero; whole
     * rate-generated packets arrive uniformly at the source during the
     * configured simulation window. Creation time is the user-side release
     * reference for the end-to-end deadline. Source access delay is added to
     * the source-router arrival and initial CEDF rank origin; destination
     * access delay is added after destination-router arrival. These are each
     * counted once and do not alter local inter-router CEDF rank increments.
     * This deterministic arrival model is not specified by the paper.
     */
    private List<Packet> generatePackets(TrafficFlow flow,
                                        List<PathDecision> route,
                                        int firstPacketId) {
        double burstBytes = Math.max(0.0, flow.getBurstKB()) * BYTES_PER_KB;
        double bytesPerMillisecond = Math.max(0.0, flow.getRateMbps()) * 125.0;
        double rateWindowBytes = bytesPerMillisecond * simulationWindowMs;
        // Round down to whole packets so packetization never exceeds the
        // configured burst envelope used by DeadlineSatisfiability.
        int burstPacketCount = (int) Math.floor(burstBytes / PACKET_SIZE_BYTES);
        int ratePacketCount = (int) Math.floor(rateWindowBytes / PACKET_SIZE_BYTES);
        double firstHopBudget = route.get(0).getDeadlineBudget();
        double initialRankTime = flow.getSourceAccessDelayMs();

        List<Packet> packets = new ArrayList<>(burstPacketCount + ratePacketCount);
        int packetId = firstPacketId;
        for (int i = 0; i < burstPacketCount; i++) {
            packets.add(new Packet(packetId++, flow.getFlowId(), flow.getDeadline(),
                    0.0, PACKET_SIZE_BYTES, 0, firstHopBudget, initialRankTime));
        }
        if (bytesPerMillisecond > 0.0) {
            for (int i = 1; i <= ratePacketCount; i++) {
                double creationTime = i * (double) PACKET_SIZE_BYTES / bytesPerMillisecond;
                packets.add(new Packet(packetId++, flow.getFlowId(), flow.getDeadline(),
                        creationTime, PACKET_SIZE_BYTES, 0, firstHopBudget,
                        creationTime + initialRankTime));
            }
        }
        return packets;
    }

    private void validateRoute(TrafficFlow flow, List<PathDecision> route) {
        if (route.isEmpty()) {
            throw new IllegalArgumentException("A qualified episode must contain a route");
        }
        Router current = flow.getSource();
        for (PathDecision decision : route) {
            if (!decision.getRouter().equals(current)) {
                throw new IllegalArgumentException("Episode decisions do not form a contiguous MAPPO route");
            }
            Link link = getLink(current, decision.getNextHop());
            if (link == null) {
                throw new IllegalArgumentException("Episode route contains a missing directed link");
            }
            if (!Double.isFinite(link.getBandwidth()) || link.getBandwidth() <= 0.0) {
                throw new IllegalArgumentException("Route link capacity must be positive and finite");
            }
            if (!Double.isFinite(decision.getDeadlineBudget())
                    || decision.getDeadlineBudget() <= 0.0) {
                throw new IllegalArgumentException("Each route hop needs a positive finite MAPPO budget");
            }
            current = decision.getNextHop();
        }
        if (!current.equals(flow.getDestination())) {
            throw new IllegalArgumentException("Episode route does not end at the flow destination");
        }
    }

    private Link getLink(Router source, Router destination) {
        List<Link> links = topology.getLinks(source);
        if (links != null) {
            for (Link link : links) {
                if (link.getDestination().equals(destination)) return link;
            }
        }
        return null;
    }

    private double transmissionDelayMs(Packet packet, Link link) {
        // Link bandwidth is in Mbps: 1 Mbps = 1000 bits/ms.
        double capacityBitsPerMs = link.getBandwidth() * 1000.0;
        return (packet.getSize() * 8.0) / capacityBitsPerMs;
    }

    private double propagationDelay(Link link) {
        return legacyPropagationDelayMs == null
                ? link.getPropagationDelay() : legacyPropagationDelayMs;
    }

    private PacketSimulationResult.PacketDelivery createDelivery(
            TrafficFlow flow, Packet packet, double deliveryTime) {
        double delay = endToEndDelay(packet, deliveryTime);
        return new PacketSimulationResult.PacketDelivery(
                packet.getPacketId(), packet.getFlowId(), packet.getCreationTime(),
                deliveryTime, delay, delay <= flow.getDeadline());
    }

    private double endToEndDelay(Packet packet, double deliveryTime) {
        return deliveryTime - packet.getCreationTime();
    }

    private static String linkName(Link link) {
        return link.getSource().getName() + "->" + link.getDestination().getName();
    }

    private static void increment(Map<String, Integer> values, String key) {
        values.put(key, values.getOrDefault(key, 0) + 1);
    }

    public static final class FlowInput {
        private final TrafficFlow flow;
        private final MAPPOEpisodeResult episodeResult;

        public FlowInput(TrafficFlow flow, MAPPOEpisodeResult episodeResult) {
            if (flow == null || episodeResult == null) {
                throw new IllegalArgumentException("Flow and MAPPO episode result are required");
            }
            this.flow = flow;
            this.episodeResult = episodeResult;
        }

        public TrafficFlow getFlow() { return flow; }
        public MAPPOEpisodeResult getEpisodeResult() { return episodeResult; }
    }

    private static final class FlowContext {
        final TrafficFlow flow;
        final MAPPOEpisodeResult episodeResult;
        List<PathDecision> route = new ArrayList<>();
        List<Packet> packets = new ArrayList<>();
        int generated;
        int delivered;
        int timely;
        int dropped;

        FlowContext(TrafficFlow flow, MAPPOEpisodeResult episodeResult) {
            this.flow = flow;
            this.episodeResult = episodeResult;
        }

        boolean isAdmitted() {
            return episodeResult.isRouteComplete() && episodeResult.isRouteQualified();
        }
    }

    private static final class PortState {
        final Link link;
        final CEDFScheduler scheduler;
        boolean busy;

        PortState(Link link, CEDFScheduler scheduler) {
            this.link = link;
            this.scheduler = scheduler;
        }
    }

    private enum EventType { ARRIVAL, SERVICE_COMPLETE }

    private static final class Event {
        final double time;
        final long sequence;
        final Packet packet;
        final Router router;
        final PortState port;
        final EventType type;

        private Event(double time, long sequence, Packet packet,
                      Router router, PortState port, EventType type) {
            this.time = time;
            this.sequence = sequence;
            this.packet = packet;
            this.router = router;
            this.port = port;
            this.type = type;
        }

        static Event arrival(double time, long sequence, Packet packet,
                             Router router, PortState port) {
            return new Event(time, sequence, packet, router, port, EventType.ARRIVAL);
        }

        static Event serviceComplete(double time, long sequence, Packet packet,
                                     Router router, PortState port) {
            return new Event(time, sequence, packet, router, port, EventType.SERVICE_COMPLETE);
        }
    }
}
