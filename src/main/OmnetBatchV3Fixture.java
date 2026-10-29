import java.nio.file.Path;
import java.nio.file.Paths;
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
import simulation.PacketSimulationResult;
import simulation.PacketSimulator;
import traffic.TrafficFlow;

/** Writes a deterministic six-node V3 integration fixture and cross-checks Java timing. */
public final class OmnetBatchV3Fixture {
    private OmnetBatchV3Fixture() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: OmnetBatchV3Fixture <new-output.csv>");
        }
        Path output = Paths.get(args[0]);
        NetworkTopology topology = new NetworkTopology();
        Router source = add(topology, 0, "source");
        Router router1 = add(topology, 1, "router1");
        Router router2 = add(topology, 2, "router2");
        Router router3 = add(topology, 3, "router3");
        Router router4 = add(topology, 4, "router4");
        Router sink = add(topology, 5, "sink");
        directed(topology, source, router1);
        directed(topology, router1, router2);
        directed(topology, router2, sink);
        directed(topology, source, router3);
        directed(topology, router3, sink);
        directed(topology, router1, router3);
        directed(topology, source, router4);
        directed(topology, router4, sink);

        TrafficFlow threeHop = new TrafficFlow(1, source, sink,
                0.0, 1.0, 6.66, 0.2, 0.3);
        TrafficFlow twoHop = new TrafficFlow(2, source, sink,
                0.0, 1.0, 4.5, 0.1, 0.15);
        TrafficFlow rejected = new TrafficFlow(3, source, sink,
                0.0, 1.0, 5.0, 0.0, 0.0);
        MAPPOEpisodeResult first = admitted(
                new PathDecision(source, router1, 2.051),
                new PathDecision(router1, router2, 2.051),
                new PathDecision(router2, sink, 2.051));
        MAPPOEpisodeResult second = admitted(
                new PathDecision(source, router3, 2.06),
                new PathDecision(router3, sink, 2.06));
        MAPPOEpisodeResult denied = new MAPPOEpisodeResult(false, false,
                Collections.<PathDecision>emptyList(), new TrajectoryBuffer(), -1.0,
                "NO_FEASIBLE_ROUTE", "NO_FEASIBLE_ROUTE");
        List<TrafficFlow> flows = Arrays.asList(threeHop, twoHop, rejected);
        Map<Integer, MAPPOEpisodeResult> outcomes = new LinkedHashMap<>();
        outcomes.put(1, first);
        outcomes.put(2, second);
        outcomes.put(3, denied);

        // Verify the same routes and per-flow endpoint timings in Java before export.
        PacketSimulationResult javaResult = new PacketSimulator(topology,
                Arrays.asList(new PacketSimulator.FlowInput(threeHop, first),
                        new PacketSimulator.FlowInput(twoHop, second),
                        new PacketSimulator.FlowInput(rejected, denied)),
                0.05, 0.0, 16).simulate();
        require(javaResult.getTotalPackets() == 2 && javaResult.getDeliveredPackets() == 2,
                "Java fixture should generate and deliver two admitted packets");
        double flow1Delay = javaResult.getPacketDeliveries().stream()
                .filter(delivery -> delivery.getFlowId() == 1)
                .mapToDouble(PacketSimulationResult.PacketDelivery::getEndToEndDelay)
                .findFirst().orElseThrow(() -> new AssertionError("Java flow 1 missing"));
        double flow2Delay = javaResult.getPacketDeliveries().stream()
                .filter(delivery -> delivery.getFlowId() == 2)
                .mapToDouble(PacketSimulationResult.PacketDelivery::getEndToEndDelay)
                .findFirst().orElseThrow(() -> new AssertionError("Java flow 2 missing"));
        require(close(flow1Delay, 6.6524) && close(flow2Delay, 4.3516),
                "Java endpoint-aware route delays differ from expected values");
        require(javaResult.getPacketsDeliveredBeforeDeadline() == 2,
                "both Java fixture packets should meet their deadlines");

        OmnetBatchExporter.writeV3(output, "DetWANNetwork-fixed-6node-v1",
                topology, flows, outcomes, 0.05, 0.0);
        System.out.println("V3_FIXTURE_WRITTEN path=" + output.toAbsolutePath());
        System.out.println("V3_FIXTURE_EXPECTED flow1=source>router1>router2>sink"
                + " delta_ms=2.051;2.051;2.051 source_access_ms=0.2 destination_access_ms=0.3"
                + " e2e_ms=6.6524 timely=true");
        System.out.println("V3_FIXTURE_EXPECTED flow2=source>router3>sink"
                + " delta_ms=2.06;2.06 source_access_ms=0.1 destination_access_ms=0.15"
                + " e2e_ms=4.3516 timely=true");
        System.out.println("V3_FIXTURE_EXPECTED flow3=REJECTED:NO_FEASIBLE_ROUTE arrivals=0");
    }

    private static Router add(NetworkTopology topology, int id, String name) {
        Router router = new Router(id, name);
        topology.addRouter(router);
        return router;
    }

    private static void directed(NetworkTopology topology, Router from, Router to) {
        topology.addDirectedLink(new Link(from, to, 2.0, 10000.0));
    }

    private static MAPPOEpisodeResult admitted(PathDecision... decisions) {
        return new MAPPOEpisodeResult(true, true, Arrays.asList(decisions),
                new TrajectoryBuffer(), 1.0);
    }

    private static boolean close(double actual, double expected) {
        return Math.abs(actual - expected) <= 1.0e-9;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
