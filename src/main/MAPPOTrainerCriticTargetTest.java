import java.util.Arrays;

import network.Link;
import network.NetworkTopology;
import network.Router;
import routing.GAECalculator;
import routing.MAPPOTrainer;
import routing.TrajectoryBuffer;
import routing.TrajectoryStep;

public class MAPPOTrainerCriticTargetTest {
    public static void main(String[] args) {
        NetworkTopology topology = new NetworkTopology();
        Router a = new Router(1, "A");
        Router b = new Router(2, "B");
        topology.addRouter(a);
        topology.addRouter(b);
        topology.addLink(new Link(a, b, 1.0, 1000.0));

        long seed = 991L;
        double learningRate = 0.001;
        MAPPOTrainer actual = trainer(topology, seed, learningRate);
        MAPPOTrainer expected = trainer(topology, seed, learningRate);
        double[] localObservation = new double[actual.getActor(a).getObservationInputSize()];
        Arrays.fill(localObservation, 0.5);
        boolean[] mask = {true};

        TrajectoryBuffer rollout = new TrajectoryBuffer();
        rollout.add(new TrajectoryStep(a.getId(), new double[] {1.0}, localObservation,
                0, 1.0, 1.0, new double[] {2.0}, mask,
                TrajectoryStep.BoundaryType.NONE, true));
        rollout.add(new TrajectoryStep(a.getId(), new double[] {2.0}, localObservation,
                0, 1.0, 2.0, new double[] {3.0}, mask,
                TrajectoryStep.BoundaryType.NONE, true));
        rollout.add(new TrajectoryStep(a.getId(), new double[] {3.0}, localObservation,
                0, 1.0, 0.0, new double[] {4.0}, mask,
                TrajectoryStep.BoundaryType.TRUNCATED, true));

        GAECalculator calculator = new GAECalculator(0.99, 0.95);
        double rolloutBaseline = expected.getCritic().getValue(
                rollout.get(0).getGlobalState());
        double[] returns = calculator.calculateDiscountedReturns(rollout, expected.getCritic());
        double[] advantages = calculator.calculate(rollout, expected.getCritic());
        double oldLambdaTarget = rolloutBaseline + advantages[0];
        check(Math.abs(returns[0] - oldLambdaTarget) > 1.0e-8,
                "fixture must distinguish Eq. (27) from the old baseline-plus-GAE target");
        MAPPOTrainer.TrainingStats stats = actual.train(rollout, rollout.size(), 1);
        double[][] sampledStates = new double[stats.getSampledTransitions().size()][];
        double[] sampledTargets = new double[sampledStates.length];
        int sampleIndex = 0;
        for (MAPPOTrainer.SampledTransition sample : stats.getSampledTransitions()) {
            int i = sample.getTransitionIndex();
            sampledStates[sampleIndex] = rollout.get(i).getGlobalState();
            sampledTargets[sampleIndex] = returns[i];
            sampleIndex++;
        }
        expected.getCritic().trainBatch(sampledStates, sampledTargets, learningRate);
        double expectedValue = expected.getCritic().getValue(new double[] {1.0});
        double actualValue = actual.getCritic().getValue(new double[] {1.0});
        check(Math.abs(expectedValue - actualValue) < 1.0e-15,
                "critic training must use pre-update Eq. (27) targets despite earlier critic updates");
        System.out.println("MAPPOTrainerCriticTargetTest passed: critic uses fixed rollout-time Eq. (27) targets.");
    }

    private static MAPPOTrainer trainer(NetworkTopology topology, long seed,
                                        double learningRate) {
        return new MAPPOTrainer(topology, 1, 1,
                0.99, 0.95, 0.2, learningRate, 1.0, seed);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
