import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import network.Link;
import network.NetworkTopology;
import network.Router;
import routing.DeadlineSatisfiability;
import routing.GlobalStateEncoder;
import routing.MAPPOEpisode;
import routing.MAPPOEpisodeResult;
import routing.MAPPOTrainer;
import routing.NetworkState;
import routing.RouteCascade;
import simulation.OmnetBatchExporter;
import traffic.TrafficFlow;

/** Small fixed-seed MAPPO fixture matching DetWANNetwork's existing six-node graph. */
public class OmnetBatchIntegrationFixture {
    private static final String TOPOLOGY_ID = "DetWANNetwork-fixed-6node-v1";
    private static final long SEED = 42L;
    private static final double PROCESSING_MS = 0.05;
    private static final double WINDOW_MS = 10.0;

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: OmnetBatchIntegrationFixture <new-output.csv>");
        NetworkTopology topology = topology();
        Router source = topology.getRouter("source");
        Router sink = topology.getRouter("sink");
        List<TrafficFlow> flows = Arrays.asList(
                new TrafficFlow(1, source, sink, 0.1, 1.0, 10.0),
                new TrafficFlow(2, source, sink, 0.2, 1.0, 10.0),
                new TrafficFlow(3, source, sink, 0.1, 0.0, 1.0));
        double maxBlock = DeadlineSatisfiability.DEFAULT_MAX_BLOCKING_PACKET_BYTES;
        NetworkState state = new NetworkState(topology, PROCESSING_MS, maxBlock);
        RouteCascade cascade = new RouteCascade(topology, PROCESSING_MS, maxBlock);
        int criticSize = new GlobalStateEncoder(topology, state, cascade).getStateSize();
        int budgetLevels = 5;
        MAPPOTrainer trainer = new MAPPOTrainer(topology, budgetLevels, criticSize,
                0.99, 0.95, 0.2, 0.001, 1.0, SEED);
        Map<Integer, MAPPOEpisodeResult> outcomes = new LinkedHashMap<>();
        int admitted = 0;
        for (TrafficFlow flow : flows) {
            MAPPOEpisodeResult result = new MAPPOEpisode(topology, state, cascade,
                    trainer, budgetLevels).run(flow, flows, new HashMap<Integer, Double>());
            outcomes.put(flow.getFlowId(), result);
            if (result.isRouteComplete() && result.isRouteQualified()) admitted++;
            trainer.train(result.getTrajectory());
            System.out.println("FIXTURE_FLOW id=" + flow.getFlowId()
                    + " status=" + (result.isRouteComplete() && result.isRouteQualified() ? "ADMITTED" : "REJECTED")
                    + " reason=" + result.getRejectionReason()
                    + " route=" + route(flow, result)
                    + " localDeltaMs=" + result.getPathDecisions().stream()
                            .map(d -> Double.toString(d.getDeadlineBudget()))
                            .reduce((a, b) -> a + ";" + b).orElse(""));
        }
        long distinctAdmittedEgresses = outcomes.values().stream()
                .filter(r -> r.isRouteComplete() && r.isRouteQualified())
                .map(r -> r.getPathDecisions().get(0).getNextHop().getName())
                .distinct().count();
        if (admitted < 2 || distinctAdmittedEgresses < 2)
            throw new AssertionError("Fixed-seed fixture did not produce two admitted distinct egress routes; admitted="
                    + admitted + ", distinct first egresses=" + distinctAdmittedEgresses);
        if (outcomes.get(3).isRouteQualified())
            throw new AssertionError("Flow 3 should be rejected by its infeasible deadline");

        Path output = Paths.get(args[0]);
        OmnetBatchExporter.write(output, TOPOLOGY_ID, topology, flows, outcomes,
                PROCESSING_MS, WINDOW_MS);
        System.out.println("FIXTURE_ASSERTIONS PASS admitted=" + admitted
                + " distinctEgresses=" + distinctAdmittedEgresses
                + " rejectedFlow=3");
        System.out.println("BATCH_FILE " + output.toAbsolutePath());
    }

    private static String route(TrafficFlow flow, MAPPOEpisodeResult result) {
        String route = flow.getSource().getName();
        for (routing.PathDecision decision : result.getPathDecisions()) route += ";" + decision.getNextHop().getName();
        return route;
    }

    private static NetworkTopology topology() {
        NetworkTopology topology = new NetworkTopology();
        Router source = new Router(0, "source");
        Router r1 = new Router(1, "router1");
        Router r2 = new Router(2, "router2");
        Router r3 = new Router(3, "router3");
        Router r4 = new Router(4, "router4");
        Router sink = new Router(5, "sink");
        for (Router router : Arrays.asList(source, r1, r2, r3, r4, sink)) topology.addRouter(router);
        add(topology, source, r1); // source.out[0]
        add(topology, r1, r2);     // router1.out[0]
        add(topology, r2, sink);   // router2.out[0]
        add(topology, source, r3); // source.out[1]
        add(topology, r3, sink);   // router3.out[0]
        add(topology, r1, r3);     // router1.out[1]
        add(topology, source, r4); // source.out[2]
        add(topology, r4, sink);   // router4.out[0]
        return topology;
    }

    private static void add(NetworkTopology topology, Router from, Router to) {
        topology.addDirectedLink(new Link(from, to, 2.0, 10000.0));
    }
}
