import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import network.Link;
import network.NetworkTopology;
import network.Router;
import routing.GlobalStateEncoder;
import routing.MAPPOActor;
import routing.MAPPOEpisodeResult;
import routing.MAPPORequestEpisodeDriver;
import routing.MAPPOTrainer;
import routing.NetworkState;
import routing.RouteCascade;
import simulation.PacketSimulationResult;
import traffic.TrafficFlow;

/**
 * Focused verification for per-training-episode TDR checkpoint evaluation,
 * reservation resets, metrics accounting, and parameter isolation.
 */
public class MAPPOEpisodeTDREvaluationTest {

    public static void main(String[] args) {
        testPerEpisodeTDREvaluationAndAccounting();
        testEvaluationParameterIsolationAndReservationReset();
        testOptionalCheckpointEvaluationToggle();
        System.out.println("MAPPOEpisodeTDREvaluationTest passed: per-episode TDR evaluation, "
                + "packet accounting, parameter isolation, and reservation resets verified.");
    }

    private static void testPerEpisodeTDREvaluationAndAccounting() {
        Router a = new Router(1, "A");
        Router b = new Router(2, "B");
        Router c = new Router(3, "C");
        NetworkTopology topology = new NetworkTopology();
        topology.addRouter(a); topology.addRouter(b); topology.addRouter(c);
        topology.addLink(new Link(a, b, 0.1, 10000.0));
        topology.addLink(new Link(b, c, 0.1, 10000.0));

        NetworkState state = new NetworkState(topology, 0.05, 0.0);
        RouteCascade cascade = new RouteCascade(topology, 0.05, 0.0);
        int criticSize = new GlobalStateEncoder(topology, state, cascade).getStateSize();
        MAPPOTrainer trainer = new MAPPOTrainer(topology, 5, criticSize,
                0.99, 0.95, 0.2, 0.001, 1.0, 777L);

        List<TrafficFlow> requests = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            requests.add(new TrafficFlow(10 + i, a, c, 20.0, 0.0, 10.0));
        }

        MAPPORequestEpisodeDriver driver = new MAPPORequestEpisodeDriver(
                topology, state, cascade, trainer, 5, 0.05, 10.0, 256, 12345L, true);

        MAPPORequestEpisodeDriver.TrainingRunResult runResult =
                driver.train(requests, 3, 4, 2, 1);

        check(runResult.getEpisodeSummaries().size() == 3,
                "driver should record exactly 3 episode summaries");

        for (MAPPORequestEpisodeDriver.EpisodeSummary summary : runResult.getEpisodeSummaries()) {
            PacketSimulationResult sim = summary.getEvaluationSimulationResult();
            check(sim != null, "checkpoint evaluation simulation result must not be null");

            int generated = summary.getGeneratedPackets();
            int delivered = summary.getDeliveredPackets();
            int timely = summary.getTimelyPackets();
            int late = summary.getLatePackets();
            int dropped = summary.getDroppedPackets();
            double tdr = summary.getTimelyDeliveryRatio();
            double avgDelay = summary.getAverageEndToEndDelay();

            check(generated > 0, "generated packets must be positive for admitted flows");
            check(delivered + dropped == generated,
                    "generated packets (" + generated + ") must equal delivered ("
                    + delivered + ") + dropped (" + dropped + ")");
            check(timely + late == delivered,
                    "delivered packets (" + delivered + ") must equal timely ("
                    + timely + ") + late (" + late + ")");
            check(tdr >= 0.0 && tdr <= 1.0,
                    "TDR must be bounded in [0, 1], found: " + tdr);
            check(Math.abs(tdr - (timely / (double) generated)) < 1.0e-9,
                    "TDR must equal timely / generated");
            check(avgDelay >= 0.0, "average end-to-end delay must be non-negative");
        }

        check(cascade.getAdmittedAllocations().isEmpty(),
                "route reservations must be clean after training finishes");
    }

    private static void testEvaluationParameterIsolationAndReservationReset() {
        Router a = new Router(1, "A");
        Router b = new Router(2, "B");
        Router c = new Router(3, "C");
        NetworkTopology topology = new NetworkTopology();
        topology.addRouter(a); topology.addRouter(b); topology.addRouter(c);
        topology.addLink(new Link(a, b, 0.1, 10000.0));
        topology.addLink(new Link(b, c, 0.1, 10000.0));

        NetworkState state = new NetworkState(topology, 0.05, 0.0);
        RouteCascade cascade = new RouteCascade(topology, 0.05, 0.0);
        int criticSize = new GlobalStateEncoder(topology, state, cascade).getStateSize();
        MAPPOTrainer trainer = new MAPPOTrainer(topology, 5, criticSize,
                0.99, 0.95, 0.2, 0.001, 1.0, 999L);

        List<TrafficFlow> requests = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            requests.add(new TrafficFlow(20 + i, a, c, 10.0, 0.0, 10.0));
        }

        MAPPORequestEpisodeDriver driver = new MAPPORequestEpisodeDriver(
                topology, state, cascade, trainer, 5);

        // Train 1 episode to obtain initial post-update parameters
        driver.train(requests, 1, 4, 1, 1);

        MAPPOActor actorA = trainer.getActor(a);
        boolean[] mask = new boolean[actorA.getActionCount()];
        Arrays.fill(mask, true);
        double[] probeObs = new double[actorA.getObservationInputSize()];
        for (int i = 0; i < probeObs.length; i++) probeObs[i] = 0.25;

        double[] probsBeforeEval = actorA.getProbabilitiesFromObservation(probeObs, mask);
        double criticValBefore = trainer.getCritic().getValue(new double[trainer.getCriticInputSize()]);

        // Run evaluation pass directly
        MAPPORequestEpisodeDriver.EvaluationRunResult evalResult = driver.evaluate(requests, 4, 42L);
        check(evalResult.getResults().size() == 4, "evaluation should produce 4 results");

        double[] probsAfterEval = actorA.getProbabilitiesFromObservation(probeObs, mask);
        double criticValAfter = trainer.getCritic().getValue(new double[trainer.getCriticInputSize()]);

        for (int i = 0; i < probsBeforeEval.length; i++) {
            check(Double.compare(probsBeforeEval[i], probsAfterEval[i]) == 0,
                    "actor probabilities must not change during evaluation");
        }
        check(Double.compare(criticValBefore, criticValAfter) == 0,
                "critic evaluation must not change during evaluation");
    }

    private static void testOptionalCheckpointEvaluationToggle() {
        Router a = new Router(1, "A");
        Router b = new Router(2, "B");
        NetworkTopology topology = new NetworkTopology();
        topology.addRouter(a); topology.addRouter(b);
        topology.addLink(new Link(a, b, 0.1, 10000.0));

        NetworkState state = new NetworkState(topology, 0.05, 0.0);
        RouteCascade cascade = new RouteCascade(topology, 0.05, 0.0);
        int criticSize = new GlobalStateEncoder(topology, state, cascade).getStateSize();
        MAPPOTrainer trainer = new MAPPOTrainer(topology, 5, criticSize,
                0.99, 0.95, 0.2, 0.001, 1.0, 555L);

        List<TrafficFlow> requests = new ArrayList<>();
        requests.add(new TrafficFlow(50, a, b, 10.0, 0.0, 10.0));

        MAPPORequestEpisodeDriver driverWithoutCheckpoints = new MAPPORequestEpisodeDriver(
                topology, state, cascade, trainer, 5, 0.05, 10.0, 256, 42L, false);

        MAPPORequestEpisodeDriver.TrainingRunResult result =
                driverWithoutCheckpoints.train(requests, 1, 1, 1, 1);

        MAPPORequestEpisodeDriver.EpisodeSummary summary = result.getEpisodeSummaries().get(0);
        check(summary.getEvaluationSimulationResult() == null,
                "when disabled, checkpoint simulation result must be null");
        check(summary.getTimelyDeliveryRatio() == 0.0,
                "when disabled, TDR should report 0.0");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
