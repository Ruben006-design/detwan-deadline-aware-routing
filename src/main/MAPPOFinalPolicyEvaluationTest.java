import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

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
import traffic.TrafficFlow;

/** Regression checks that final-policy evaluation is separate and read-only. */
public class MAPPOFinalPolicyEvaluationTest {
    public static void main(String[] args) {
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
                0.99, 0.95, 0.2, 0.001, 1.0, 998L);
        List<TrafficFlow> requests = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            requests.add(new TrafficFlow(30 + i, a, c, 1.0, 0.0, 10.0));
        }
        MAPPORequestEpisodeDriver driver = new MAPPORequestEpisodeDriver(
                topology, state, cascade, trainer, 5);
        MAPPORequestEpisodeDriver.TrainingRunResult training =
                driver.train(requests, 1, 4, 1, 1);
        check(training.getEpisodeSummaries().get(0).getTrainingStats().getActorUpdates() > 0,
                "training pass should update at least one actor");

        boolean[] maskA = new boolean[trainer.getActor(a).getActionCount()];
        Arrays.fill(maskA, true);
        double[] actorObservation = new double[trainer.getActor(a).getObservationInputSize()];
        for (int i = 0; i < actorObservation.length; i++) actorObservation[i] = (i % 7) / 7.0;
        double[] actorBefore = trainer.getActor(a).getProbabilitiesFromObservation(
                actorObservation, maskA);
        double[] criticState = new double[trainer.getCriticInputSize()];
        for (int i = 0; i < criticState.length; i++) criticState[i] = (i % 11 + 1) / 11.0;
        double criticBefore = trainer.getCritic().getValue(criticState);

        MAPPORequestEpisodeDriver.EvaluationRunResult eval1 =
                driver.evaluate(requests, 4, 123456L);
        double[] actorAfter = trainer.getActor(a).getProbabilitiesFromObservation(
                actorObservation, maskA);
        double criticAfter = trainer.getCritic().getValue(criticState);
        check(Arrays.equals(actorBefore, actorAfter),
                "evaluation must leave final actor parameters unchanged");
        check(criticBefore == criticAfter,
                "evaluation must leave critic parameters unchanged");
        check(eval1.getEvaluationSeed() == 123456L,
                "evaluation result must record the exact dedicated seed");
        check(eval1.getResults().size() == 4,
                "evaluation must return fresh results for the complete configured horizon");

        MAPPORequestEpisodeDriver.EvaluationRunResult eval2 =
                driver.evaluate(requests, 4, 123456L);
        check(sameDecisions(eval1.getResults(), eval2.getResults()),
                "same evaluation seed and final model must reproduce sampled route/budget decisions");
        System.out.println("MAPPOFinalPolicyEvaluationTest passed: post-update evaluation, separate seeded sampling, "+
                "reservation reset, repeatability, and no actor/critic parameter updates.");
    }

    private static boolean sameDecisions(Map<Integer, MAPPOEpisodeResult> left,
                                         Map<Integer, MAPPOEpisodeResult> right) {
        if (!left.keySet().equals(right.keySet())) return false;
        for (Integer id : left.keySet()) {
            List<routing.PathDecision> a = left.get(id).getPathDecisions();
            List<routing.PathDecision> b = right.get(id).getPathDecisions();
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++) {
                if (!a.get(i).getRouter().equals(b.get(i).getRouter())
                        || !a.get(i).getNextHop().equals(b.get(i).getNextHop())
                        || a.get(i).getDeadlineBudget() != b.get(i).getDeadlineBudget()) return false;
            }
        }
        return true;
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
