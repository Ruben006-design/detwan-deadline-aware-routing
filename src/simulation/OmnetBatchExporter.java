package simulation;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import network.Link;
import network.NetworkTopology;
import routing.MAPPOEpisodeResult;
import routing.PathDecision;
import traffic.TrafficFlow;

/** Writes MAPPO/RouteCascade outcomes in the versioned, dependency-free OMNeT batch format. */
public final class OmnetBatchExporter {
    public static final int FORMAT_VERSION = 1;
    public static final int FORMAT_VERSION_WITH_TOPOLOGY = 2;
    public static final int FORMAT_VERSION_WITH_ENDPOINT_ACCESS = 3;
    public static final double MAX_ENDPOINT_ACCESS_DELAY_MS = 1.0;
    public static final int PACKET_SIZE_BYTES = 1000;
    private static final int DECIMAL_KB_BYTES = 1000;

    private OmnetBatchExporter() { }

    /**
     * Writes a new file and refuses to overwrite an existing experiment artifact.
     * Values in the contract use Mbps, decimal bytes, and milliseconds.
     */
    public static void write(Path output, String topologyId,
                             NetworkTopology topology,
                             List<TrafficFlow> flows,
                             Map<Integer, MAPPOEpisodeResult> outcomes,
                             double processingDelayMs, double generationWindowMs)
            throws IOException {
        writeInternal(output, topologyId, topology, flows, outcomes,
                processingDelayMs, generationWindowMs, FORMAT_VERSION);
    }

    /** Version 2 includes the full node inventory and every directed topology link. */
    public static void writeV2(Path output, String topologyId,
                               NetworkTopology topology,
                               List<TrafficFlow> flows,
                               Map<Integer, MAPPOEpisodeResult> outcomes,
                               double processingDelayMs, double generationWindowMs)
            throws IOException {
        writeInternal(output, topologyId, topology, flows, outcomes,
                processingDelayMs, generationWindowMs, FORMAT_VERSION_WITH_TOPOLOGY);
    }

    /** Version 3 retains the full V2 topology and adds per-flow endpoint delays. */
    public static void writeV3(Path output, String topologyId,
                               NetworkTopology topology,
                               List<TrafficFlow> flows,
                               Map<Integer, MAPPOEpisodeResult> outcomes,
                               double processingDelayMs, double generationWindowMs)
            throws IOException {
        writeInternal(output, topologyId, topology, flows, outcomes,
                processingDelayMs, generationWindowMs, FORMAT_VERSION_WITH_ENDPOINT_ACCESS);
    }

    private static void writeInternal(Path output, String topologyId,
                             NetworkTopology topology,
                             List<TrafficFlow> flows,
                             Map<Integer, MAPPOEpisodeResult> outcomes,
                             double processingDelayMs, double generationWindowMs,
                             int formatVersion)
            throws IOException {
        if (output == null || topologyId == null || topologyId.trim().isEmpty()
                || topology == null || flows == null || outcomes == null
                || !Double.isFinite(processingDelayMs) || processingDelayMs < 0
                || !Double.isFinite(generationWindowMs) || generationWindowMs < 0) {
            throw new IllegalArgumentException("Valid output, topology, outcomes, and timing values are required");
        }
        boolean includeFullTopology = formatVersion >= FORMAT_VERSION_WITH_TOPOLOGY;
        boolean includeEndpointAccess = formatVersion >= FORMAT_VERSION_WITH_ENDPOINT_ACCESS;
        for (TrafficFlow flow : flows) {
            if (flow == null) {
                throw new IllegalArgumentException("Flow list cannot contain null");
            }
            double sourceAccessMs = flow.getSourceAccessDelayMs();
            double destinationAccessMs = flow.getDestinationAccessDelayMs();
            if (!includeEndpointAccess && (sourceAccessMs != 0.0 || destinationAccessMs != 0.0)) {
                throw new IllegalArgumentException("OMNeT batch versions 1 and 2 do not carry endpoint access delays;"
                        + " use version 3");
            }
            if (includeEndpointAccess
                    && (sourceAccessMs < 0 || sourceAccessMs > MAX_ENDPOINT_ACCESS_DELAY_MS
                        || destinationAccessMs < 0
                        || destinationAccessMs > MAX_ENDPOINT_ACCESS_DELAY_MS)) {
                throw new IllegalArgumentException("Version 3 endpoint access delays must be in [0, "
                        + MAX_ENDPOINT_ACCESS_DELAY_MS + "] ms");
            }
        }
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);

        List<TrafficFlow> orderedFlows = new ArrayList<>(flows);
        Map<String, Link> usedLinks = new LinkedHashMap<>();
        for (TrafficFlow flow : orderedFlows) {
            MAPPOEpisodeResult result = outcomes.get(flow.getFlowId());
            if (result == null) throw new IllegalArgumentException("Missing outcome for flow " + flow.getFlowId());
            for (PathDecision decision : result.getPathDecisions()) {
                Link link = findLink(topology, decision);
                if (link == null) {
                    throw new IllegalArgumentException("MAPPO route references absent link "
                            + decision.getRouter().getName() + "->" + decision.getNextHop().getName());
                }
                usedLinks.putIfAbsent(linkKey(link), link);
            }
        }
        if (includeFullTopology) {
            for (Link link : allLinks(topology))
                usedLinks.putIfAbsent(linkKey(link), link);
        }

        try (BufferedWriter writer = Files.newBufferedWriter(output,
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            writer.write("FORMAT,DET-WAN-OMNET-BATCH," + formatVersion); writer.newLine();
            writer.write("TOPOLOGY_ID," + csv(topologyId)); writer.newLine();
            writer.write("TOPOLOGY_FINGERPRINT_SHA256," + topologyFingerprint(topology)); writer.newLine();
            writer.write("PROCESSING_DELAY_MS," + number(processingDelayMs)); writer.newLine();
            writer.write("GENERATION_WINDOW_MS," + number(generationWindowMs)); writer.newLine();
            writer.write("PACKET_SIZE_BYTES," + PACKET_SIZE_BYTES); writer.newLine();
            writer.write("UNITS," + (includeEndpointAccess
                    ? "rate=Mbps;burst=decimal_bytes;deadline=ms;budget=local_delta_ms;arrival=ms_from_sim_start;source_access_delay=ms;destination_access_delay=ms"
                    : "rate=Mbps;burst=decimal_bytes;deadline=ms;budget=local_delta_ms;arrival=ms_from_sim_start")); writer.newLine();
            if (includeFullTopology) {
                writer.write("NODE,node_id"); writer.newLine();
                List<String> nodes = new ArrayList<>();
                topology.getRouters().forEach(router -> nodes.add(router.getName()));
                nodes.sort(String::compareTo);
                for (String node : nodes) {
                    writer.write("NODE," + csv(node));
                    writer.newLine();
                }
            }
            writer.write("LINK,source,destination,bandwidth_mbps,propagation_delay_ms"); writer.newLine();
            List<Link> links = new ArrayList<>(usedLinks.values());
            links.sort(Comparator.comparing(OmnetBatchExporter::linkKey));
            for (Link link : links) {
                writer.write("LINK," + csv(link.getSource().getName()) + ","
                        + csv(link.getDestination().getName()) + ","
                        + number(link.getBandwidth()) + "," + number(link.getPropagationDelay()));
                writer.newLine();
            }
            writer.write("FLOW,flow_id,source,destination,rate_mbps,burst_bytes,deadline_ms,admission_status,rejection_reason,route_nodes,local_hop_budgets_ms,packet_arrivals_ms,packet_size_bytes"
                    + (includeEndpointAccess ? ",source_access_delay_ms,destination_access_delay_ms" : ""));
            writer.newLine();
            for (TrafficFlow flow : orderedFlows) {
                MAPPOEpisodeResult result = outcomes.get(flow.getFlowId());
                boolean admitted = result.isRouteComplete() && result.isRouteQualified();
                List<String> route = new ArrayList<>();
                route.add(flow.getSource().getName());
                List<String> budgets = new ArrayList<>();
                for (PathDecision decision : result.getPathDecisions()) {
                    route.add(decision.getNextHop().getName());
                    budgets.add(number(decision.getDeadlineBudget()));
                }
                List<String> arrivals = admitted
                        ? packetArrivalTimes(flow, generationWindowMs) : new ArrayList<>();
                String reason = admitted ? "" : firstNonEmpty(
                        result.getRejectionReason(), result.getTerminationReason(), "REJECTED");
                writer.write("FLOW," + flow.getFlowId() + ","
                        + csv(flow.getSource().getName()) + ","
                        + csv(flow.getDestination().getName()) + ","
                        + number(flow.getRateMbps()) + ","
                        + number(flow.getBurstKB() * DECIMAL_KB_BYTES) + ","
                        + number(flow.getDeadline()) + ","
                        + (admitted ? "ADMITTED" : "REJECTED") + ","
                        + csv(reason) + "," + csv(String.join(";", route)) + ","
                        + csv(String.join(";", budgets)) + ","
                        + csv(String.join(";", arrivals)) + "," + PACKET_SIZE_BYTES
                        + (includeEndpointAccess ? "," + number(flow.getSourceAccessDelayMs())
                                + "," + number(flow.getDestinationAccessDelayMs()) : ""));
                writer.newLine();
            }
        }
    }

    private static List<String> packetArrivalTimes(TrafficFlow flow, double windowMs) {
        // Matches the current Java workload assumption without invoking PacketSimulator:
        // whole 1000-byte burst packets at t=0, then rate-derived packets within the window.
        List<String> times = new ArrayList<>();
        int burstCount = (int) Math.floor(Math.max(0, flow.getBurstKB())
                * DECIMAL_KB_BYTES / PACKET_SIZE_BYTES);
        for (int i = 0; i < burstCount; i++) times.add("0");
        double bytesPerMs = Math.max(0, flow.getRateMbps()) * 125.0;
        int rateCount = (int) Math.floor(bytesPerMs * windowMs / PACKET_SIZE_BYTES);
        if (bytesPerMs > 0) {
            for (int i = 1; i <= rateCount; i++) {
                times.add(number(i * (double) PACKET_SIZE_BYTES / bytesPerMs));
            }
        }
        return times;
    }

    private static Link findLink(NetworkTopology topology, PathDecision decision) {
        List<Link> links = topology.getLinks(decision.getRouter());
        if (links == null) return null;
        for (Link link : links) if (link.getDestination().equals(decision.getNextHop())) return link;
        return null;
    }

    private static String topologyFingerprint(NetworkTopology topology) {
        List<String> rows = new ArrayList<>();
        for (Link link : allLinks(topology)) {
            rows.add(linkKey(link) + "|" + number(link.getBandwidth()) + "|"
                    + number(link.getPropagationDelay()));
        }
        rows.sort(String::compareTo);
        List<String> routers = new ArrayList<>();
        topology.getRouters().forEach(router -> routers.add(router.getName()));
        routers.sort(String::compareTo);
        String canonical = "NODES=" + String.join(",", routers) + "\nLINKS=" + String.join("\n", rows);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Java runtime does not provide SHA-256", impossible);
        }
    }

    private static List<Link> allLinks(NetworkTopology topology) {
        List<Link> links = new ArrayList<>();
        for (network.Router router : topology.getRouters()) {
            List<Link> from = topology.getLinks(router);
            if (from != null) links.addAll(from);
        }
        return links;
    }

    private static String linkKey(Link link) {
        return link.getSource().getName() + "->" + link.getDestination().getName();
    }

    private static String number(double value) { return Double.toString(value); }

    private static String csv(String value) {
        String safe = value == null ? "" : value;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) if (value != null && !value.isEmpty()) return value;
        return "REJECTED";
    }
}
