import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import network.Link;
import network.NetworkTopology;
import network.Router;
import routing.MAPPOEpisodeResult;
import routing.PathDecision;
import routing.TrajectoryBuffer;
import simulation.OmnetBatchExporter;
import traffic.TrafficFlow;

/** Deterministic contract check; no packet simulation or MAPPO sampling is performed. */
public class OmnetBatchExporterTest {
    public static void main(String[] args) throws Exception {
        NetworkTopology topology = new NetworkTopology();
        Router source = new Router(0, "source");
        Router r3 = new Router(1, "router3");
        Router r4 = new Router(2, "router4");
        Router sink = new Router(3, "sink");
        for (Router router : Arrays.asList(source, r3, r4, sink)) topology.addRouter(router);
        topology.addLink(new Link(source, r3, 2.0, 10000.0));
        topology.addLink(new Link(r3, sink, 2.0, 10000.0));
        topology.addLink(new Link(source, r4, 2.0, 10000.0));
        topology.addLink(new Link(r4, sink, 2.0, 10000.0));

        TrafficFlow admitted = new TrafficFlow(1, source, sink, 1.0, 2.0, 10.0);
        TrafficFlow rejected = new TrafficFlow(2, source, sink, 0.5, 1.0, 1.0);
        MAPPOEpisodeResult admittedResult = new MAPPOEpisodeResult(true, true,
                Arrays.asList(new PathDecision(source, r3, 3.0),
                        new PathDecision(r3, sink, 4.0)),
                new TrajectoryBuffer(), 1.0, "DESTINATION_REACHED", null);
        MAPPOEpisodeResult rejectedResult = new MAPPOEpisodeResult(false, false,
                Collections.<PathDecision>emptyList(), new TrajectoryBuffer(), -1.0,
                "NO_FEASIBLE_ROUTE", "NO_FEASIBLE_ROUTE");
        Map<Integer, MAPPOEpisodeResult> outcomes = new LinkedHashMap<>();
        outcomes.put(1, admittedResult);
        outcomes.put(2, rejectedResult);
        Path directory = Files.createTempDirectory("detwan-batch-test-");
        Path output = directory.resolve("fixture.dwbatch.csv");

        OmnetBatchExporter.write(output, "DetWANNetwork-fixed-6node-v1", topology,
                Arrays.asList(admitted, rejected), outcomes, 0.05, 10.0);
        List<String> lines = Files.readAllLines(output, StandardCharsets.UTF_8);
        String admittedRow = lines.stream().filter(line -> line.startsWith("FLOW,1,")).findFirst().orElseThrow();
        String rejectedRow = lines.stream().filter(line -> line.startsWith("FLOW,2,")).findFirst().orElseThrow();
        check(lines.get(0).equals("FORMAT,DET-WAN-OMNET-BATCH,1"), "version header");
        check(admittedRow.contains("\"source;router3;sink\""), "selected ordered route preserved");
        check(admittedRow.contains("\"3.0;4.0\""), "local hop budgets preserved");
        check(admittedRow.contains("\"0;0;8.0\""), "explicit deterministic packet arrivals");
        check(admittedRow.contains(",ADMITTED,"), "admission decision preserved");
        check(rejectedRow.contains(",REJECTED,"), "rejected flow retained");
        check(rejectedRow.contains("NO_FEASIBLE_ROUTE"), "rejection reason retained");
        boolean refusedOverwrite = false;
        try {
            OmnetBatchExporter.write(output, "DetWANNetwork-fixed-6node-v1", topology,
                    Arrays.asList(admitted, rejected), outcomes, 0.05, 10.0);
        } catch (java.nio.file.FileAlreadyExistsException expected) {
            refusedOverwrite = true;
        }
        check(refusedOverwrite, "existing experiment file is never overwritten");

        Path fullTopologyOutput = directory.resolve("fixture-v2.dwbatch.csv");
        OmnetBatchExporter.writeV2(fullTopologyOutput, "fixture-full-topology-v1", topology,
                Arrays.asList(admitted, rejected), outcomes, 0.05, 10.0);
        List<String> v2Lines = Files.readAllLines(fullTopologyOutput, StandardCharsets.UTF_8);
        long nodeRows = v2Lines.stream().filter(line -> line.startsWith("NODE,")
                && !line.equals("NODE,node_id")).count();
        long directedLinkRows = v2Lines.stream().filter(line -> line.startsWith("LINK,")
                && !line.equals("LINK,source,destination,bandwidth_mbps,propagation_delay_ms")).count();
        check(v2Lines.get(0).equals("FORMAT,DET-WAN-OMNET-BATCH,2"), "v2 format header");
        check(nodeRows == 4, "v2 includes full node inventory");
        check(directedLinkRows == topology.getLinkCount(), "v2 includes every directed link, including unused links");

        TrafficFlow endpointDelayFlow = new TrafficFlow(3, source, sink,
                1.0, 1.0, 10.0, 0.2, 0.35);
        Path endpointOutput = directory.resolve("fixture-v3.dwbatch.csv");
        OmnetBatchExporter.writeV3(endpointOutput, "fixture-full-topology-v1", topology,
                Collections.singletonList(endpointDelayFlow),
                Collections.singletonMap(3, admittedResult), 0.05, 10.0);
        List<String> v3Lines = Files.readAllLines(endpointOutput, StandardCharsets.UTF_8);
        check(v3Lines.get(0).equals("FORMAT,DET-WAN-OMNET-BATCH,3"), "v3 format header");
        check(v3Lines.contains("UNITS,rate=Mbps;burst=decimal_bytes;deadline=ms;budget=local_delta_ms;arrival=ms_from_sim_start;source_access_delay=ms;destination_access_delay=ms"),
                "v3 declares endpoint delay units");
        String v3Header = v3Lines.stream().filter(line -> line.startsWith("FLOW,flow_id,"))
                .findFirst().orElseThrow();
        check(v3Header.endsWith(",source_access_delay_ms,destination_access_delay_ms"),
                "v3 appends endpoint columns without changing the v2 prefix");
        String v3Flow = v3Lines.stream().filter(line -> line.startsWith("FLOW,3,"))
                .findFirst().orElseThrow();
        check(v3Flow.endsWith(",0.2,0.35"), "v3 preserves distinct endpoint delays");
        check(v3Lines.stream().filter(line -> line.startsWith("NODE,")
                        && !line.equals("NODE,node_id")).count() == 4,
                "v3 retains the full topology inventory");

        Path unsupportedEndpointOutput = directory.resolve("endpoint-delay-v2-unsupported.dwbatch.csv");
        boolean refusedEndpointDelay = false;
        try {
            OmnetBatchExporter.writeV2(unsupportedEndpointOutput,
                    "DetWANNetwork-fixed-6node-v1", topology,
                    Collections.singletonList(endpointDelayFlow),
                    Collections.singletonMap(3, admittedResult), 0.05, 10.0);
        } catch (IllegalArgumentException expected) {
            refusedEndpointDelay = expected.getMessage().contains("do not carry endpoint access delays");
        }
        check(refusedEndpointDelay, "nonzero endpoint delays rejected by v2");
        check(!Files.exists(unsupportedEndpointOutput), "rejected endpoint configuration writes no batch file");

        boolean rejectedNegativeDelay = false;
        try {
            new TrafficFlow(4, source, sink, 1.0, 1.0, 10.0, -0.01, 0.0);
        } catch (IllegalArgumentException expected) {
            rejectedNegativeDelay = true;
        }
        check(rejectedNegativeDelay, "negative endpoint delay rejected at flow construction");
        TrafficFlow overLimit = new TrafficFlow(5, source, sink,
                1.0, 1.0, 10.0, 1.0001, 0.0);
        boolean rejectedOverLimit = false;
        try {
            OmnetBatchExporter.writeV3(directory.resolve("over-limit.dwbatch.csv"),
                    "fixture-full-topology-v1", topology, Collections.singletonList(overLimit),
                    Collections.singletonMap(5, admittedResult), 0.05, 10.0);
        } catch (IllegalArgumentException expected) {
            rejectedOverLimit = expected.getMessage().contains("must be in [0, 1.0] ms");
        }
        check(rejectedOverLimit, "v3 rejects endpoint delays above supported 1 ms limit");
        check(!Files.exists(directory.resolve("over-limit.dwbatch.csv")),
                "over-limit delay rejection creates no partial file");
        System.out.println("OmnetBatchExporterTest PASS (21 assertions)");
        System.out.println("Test batch: " + output);
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        System.out.println("PASS: " + label);
    }
}
