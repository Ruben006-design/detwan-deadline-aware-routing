import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import network.Link;
import network.NetworkTopology;
import network.Router;
import routing.GAECalculator;
import routing.GlobalStateEncoder;
import routing.MAPPOActor;
import routing.MAPPORequestEpisodeDriver;
import routing.MAPPOTrainer;
import routing.NetworkState;
import routing.RouteCascade;
import routing.TrajectoryBuffer;
import routing.TrajectoryStep;
import traffic.TrafficFlow;

public class MAPPOTrainerMinibatchTest {
    public static void main(String[] args) {
        testConfiguredMinibatchAndUpdateCounts();
        testSeededSamplingAndOldProbabilityPreservation();
        testMinibatchValidation();
        testEpisodeBuffersAndResourceReset();
        testTruncationBootstrapStillApplies();
        System.out.println("MAPPOTrainerMinibatchTest passed: sizes/counts, deterministic sampling, old probabilities, inactive agents, episode reset, and truncation bootstrap.");
    }

    private static void testConfiguredMinibatchAndUpdateCounts() {
        Fixture fixture = new Fixture(901L);
        TrajectoryBuffer rollout = fixture.rollout(4);
        MAPPOTrainer.TrainingStats stats = fixture.trainer.train(rollout, 2, 3);
        check(stats.getMinibatchesSampled() == 6,
                "two agents over three rounds must produce six agent minibatches");
        check(stats.getCriticUpdates() == 6,
                "each of six agent minibatches must perform one critic optimizer update");
        check(stats.getActorUpdates() == 3,
                "the active agent must perform one actor optimizer update per round");
        check(stats.getInactiveActorSamplesSkipped() == 6,
                "inactive sentinel samples must be excluded from actor minibatch gradients");
        check(stats.getSampledTransitions().size() == 12,
                "sampling trace must retain every selected sample");
        for (int round = 0; round < 3; round++) {
            boolean[] activeIndices = new boolean[4];
            boolean[] inactiveIndices = new boolean[4];
            for (MAPPOTrainer.SampledTransition sample : stats.getSampledTransitions()) {
                if (sample.getUpdateRound() != round) continue;
                boolean[] seen = sample.getAgentId() == fixture.a.getId()
                        ? activeIndices : inactiveIndices;
                check(!seen[sample.getTransitionIndex()],
                        "a minibatch must not contain the same transition twice");
                seen[sample.getTransitionIndex()] = true;
            }
            check(count(activeIndices) == 2 && count(inactiveIndices) == 2,
                    "each agent minibatch must contain the configured sample count");
        }
    }

    private static void testSeededSamplingAndOldProbabilityPreservation() {
        Fixture first = new Fixture(777L);
        Fixture second = new Fixture(777L);
        TrajectoryBuffer firstRollout = first.rollout(4);
        TrajectoryBuffer secondRollout = second.rollout(4);
        double[] savedOldProbabilities = new double[firstRollout.size()];
        for (int i = 0; i < firstRollout.size(); i++) {
            savedOldProbabilities[i] = firstRollout.get(i).getActionProbability();
        }

        MAPPOTrainer.TrainingStats firstStats = first.trainer.train(firstRollout, 2, 3);
        MAPPOTrainer.TrainingStats secondStats = second.trainer.train(secondRollout, 2, 3);
        check(sameTrace(firstStats.getSampledTransitions(), secondStats.getSampledTransitions()),
                "fixed trainer seeds must produce the same minibatch sequence");
        for (int i = 0; i < firstRollout.size(); i++) {
            check(firstRollout.get(i).getActionProbability() == savedOldProbabilities[i],
                    "old rollout action probabilities must remain immutable across PPO rounds");
        }
        double[] firstAfter = first.trainer.getActor(first.a)
                .getProbabilitiesFromObservation(first.observation, first.activeMask);
        double[] secondAfter = second.trainer.getActor(second.a)
                .getProbabilitiesFromObservation(second.observation, second.activeMask);
        check(Arrays.equals(firstAfter, secondAfter),
                "identically seeded training must produce identical actor probabilities");
    }

    private static void testMinibatchValidation() {
        Fixture fixture = new Fixture(12L);
        TrajectoryBuffer rollout = fixture.rollout(4);
        expectIllegal(() -> fixture.trainer.train(rollout, 5, 1),
                "a minibatch larger than an agent's trajectory must fail clearly");
        expectIllegal(() -> fixture.trainer.train(rollout, 1, 0),
                "zero update rounds must fail clearly");
    }

    private static void testEpisodeBuffersAndResourceReset() {
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
                0.99, 0.95, 0.2, 0.001, 1.0, 314L);
        List<TrafficFlow> requests = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            requests.add(new TrafficFlow(100 + i, a, c, 1.0, 0.0, 10.0));
        }

        MAPPORequestEpisodeDriver.TrainingRunResult run =
                new MAPPORequestEpisodeDriver(topology, state, cascade, trainer, 5)
                        .train(requests, 2, 8, 4, 2);
        check(run.getEpisodeSummaries().size() == 2,
                "driver must produce the requested episode count");
        for (MAPPORequestEpisodeDriver.EpisodeSummary summary : run.getEpisodeSummaries()) {
            check(summary.getRequestsProcessed() == 8,
                    "each episode must process exactly the configured horizon");
            check(summary.getTransitionsCollected() == 24,
                    "each episode buffer must contain 8 requests times 3 routers, not prior episodes");
            check(summary.getTrainingStats().getMinibatchesSampled() == 6,
                    "three agents times two rounds gives six minibatches per episode");
            check(summary.getTrainingStats().getCriticUpdates() == 6,
                    "each agent minibatch performs one critic optimizer update");
            check(summary.getTrainingStats().getActorUpdates() == 4,
                    "the two routers on the fixed route each update once per round");
            check(summary.getTrainingStats().getInactiveActorSamplesSkipped() == 8,
                    "the destination router's sampled inactive rows never enter actor gradients");
        }
        check(run.getFinalEpisodeResults().size() == 8,
                "driver should return results for the final episode only");
        check(cascade.getAdmittedAllocations().size() <= 16,
                "reservation list must represent only the final episode after explicit reset");
    }

    private static void testTruncationBootstrapStillApplies() {
        TrajectoryBuffer rollout = new TrajectoryBuffer();
        rollout.add(new TrajectoryStep(1, new double[] {1.0}, new double[] {0.0},
                0, 1.0, 2.0, new double[] {2.0}, new boolean[] {true},
                TrajectoryStep.BoundaryType.TRUNCATED, true));
        GAECalculator calculator = new GAECalculator(0.5, 0.95);
        double[] target = calculator.calculateDiscountedReturns(rollout,
                state -> state[0] * 3.0);
        check(Math.abs(target[0] - 5.0) < 1.0e-12,
                "truncated rollout target must include gamma times V(next state)");
    }

    private static boolean sameTrace(List<MAPPOTrainer.SampledTransition> left,
                                     List<MAPPOTrainer.SampledTransition> right) {
        if (left.size() != right.size()) return false;
        for (int i = 0; i < left.size(); i++) {
            MAPPOTrainer.SampledTransition a = left.get(i);
            MAPPOTrainer.SampledTransition b = right.get(i);
            if (a.getAgentId() != b.getAgentId()
                    || a.getUpdateRound() != b.getUpdateRound()
                    || a.getTransitionIndex() != b.getTransitionIndex()) return false;
        }
        return true;
    }

    private static int count(boolean[] values) {
        int count = 0;
        for (boolean value : values) if (value) count++;
        return count;
    }

    private static void expectIllegal(Runnable action, String message) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Fixture {
        final Router a = new Router(1, "A");
        final Router b = new Router(2, "B");
        final NetworkTopology topology = new NetworkTopology();
        final MAPPOTrainer trainer;
        final double[] observation;
        final boolean[] activeMask;

        Fixture(long seed) {
            topology.addRouter(a); topology.addRouter(b);
            topology.addLink(new Link(a, b, 0.1, 10000.0));
            trainer = new MAPPOTrainer(topology, 2, 1,
                    0.99, 0.95, 0.1, 0.0001, 1.0, seed);
            observation = new double[trainer.getActor(a).getObservationInputSize()];
            activeMask = new boolean[trainer.getActor(a).getActionCount()];
            activeMask[0] = true;
        }

        TrajectoryBuffer rollout(int count) {
            TrajectoryBuffer result = new TrajectoryBuffer();
            double oldProbability = trainer.getActor(a)
                    .getProbabilitiesFromObservation(observation, activeMask)[0];
            for (int i = 0; i < count; i++) {
                TrajectoryStep.BoundaryType boundary = i == count - 1
                        ? TrajectoryStep.BoundaryType.TRUNCATED
                        : TrajectoryStep.BoundaryType.NONE;
                result.add(new TrajectoryStep(a.getId(), new double[] {i}, observation,
                        0, oldProbability, 1.0, new double[] {i + 1.0}, activeMask,
                        boundary, true));
                result.add(new TrajectoryStep(b.getId(), new double[] {i},
                        new double[trainer.getActor(b).getObservationInputSize()],
                        -1, 0.0, 1.0, new double[] {i + 1.0},
                        new boolean[trainer.getActor(b).getActionCount()], boundary, false));
            }
            return result;
        }
    }
}
