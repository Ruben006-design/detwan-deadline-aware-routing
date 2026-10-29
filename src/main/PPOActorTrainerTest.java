import java.util.*;

import network.*;
import routing.*;
import traffic.*;

public class PPOActorTrainerTest {

    public static void main(String[] args) {

        testClippedAndActiveSurrogateBranches();
        testInactiveAgentDoesNotUpdateActor();

        NetworkTopology topology =
                new NetworkTopology();

        Router r1 = new Router(1, "R1");
        Router r2 = new Router(2, "R2");
        Router r3 = new Router(3, "R3");

        topology.addRouter(r1);
        topology.addRouter(r2);
        topology.addRouter(r3);

        topology.addLink(
                new Link(r1, r2, 2.0, 10000));

        topology.addLink(
                new Link(r1, r3, 2.0, 10000));

        TrafficFlow flow =
                new TrafficFlow(
                        1,
                        r1,
                        r3,
                        20,
                        50,
                        10);

        List<PathDecision> previous =
                new ArrayList<>();

        List<RouterObservation.NeighborState> neighbors =
                new ArrayList<>();

        neighbors.add(
                new RouterObservation.NeighborState(
                        r2, 2.0, 0.05, 1, 9000));

        neighbors.add(
                new RouterObservation.NeighborState(
                        r3, 2.0, 0.05, 0, 9000));

        RouterObservation observation =
                new RouterObservation(
                        flow,
                        previous,
                        neighbors);

        MAPPOActor actor =
                new MAPPOActor(
                        topology,
                        r1,
                        5);

        boolean[] actionMask = ActionSpace.getActionMask(
                r1, flow, topology, 5,
                new HashSet<>(Collections.singletonList(r1)),
                0.0, 0.05);

        double[] probabilitiesBefore =
                actor.getProbabilities(observation, actionMask);

        int actionIndex = 6;

        double oldProbability =
                probabilitiesBefore[actionIndex];

        double[] encodedObservation =
                new ObservationEncoder(
                        topology,
                        5)
                        .encodeActorObservation(
                                observation);

        TrajectoryStep step =
        new TrajectoryStep(
                1,
                encodedObservation,
                encodedObservation,
                actionIndex,
                oldProbability,
                1.0,
                encodedObservation,
                actionMask);

        PPOActorTrainer trainer =
                new PPOActorTrainer(0.2);

        System.out.println(
                "Probability before update = "
                + oldProbability);

        trainer.update(
                actor,
                step,
                1.0,
                0.001);

        double[] probabilitiesAfter =
                actor.getProbabilities(observation, actionMask);

        System.out.println(
                "Probability after update = "
                + probabilitiesAfter[actionIndex]);

        System.out.println(
                "Number of actions = "
                + probabilitiesAfter.length);
    }

    private static void testClippedAndActiveSurrogateBranches() {
        assertClippedBranch(true);
        assertClippedBranch(false);
        assertActiveBranch(true);
        assertActiveBranch(false);
        System.out.println("PPO clipped-surrogate gradient assertions passed.");
    }

    private static void testInactiveAgentDoesNotUpdateActor() {
        MAPPOActor actor = testActor(7654L);
        double[] observation = testObservation(actor);
        boolean[] mask = new boolean[actor.getActionCount()];
        double[] before = actor.getProbabilitiesFromObservation(observation, allValid(actor));
        TrajectoryStep inactive = new TrajectoryStep(1, observation, observation,
                -1, 0.0, 1.0, observation, mask, false, false);
        new PPOActorTrainer(0.2).update(actor, inactive, 5.0, 0.01);
        double[] after = actor.getProbabilitiesFromObservation(observation, allValid(actor));
        for (int i = 0; i < before.length; i++) {
            near(before[i], after[i], "off-route agent must not update its actor");
        }
    }

    private static boolean[] allValid(MAPPOActor actor) {
        boolean[] mask = new boolean[actor.getActionCount()];
        Arrays.fill(mask, true);
        return mask;
    }

    private static void assertClippedBranch(boolean positiveAdvantage) {
        MAPPOActor actor = testActor(1234L);
        double[] observation = testObservation(actor);
        boolean[] mask = new boolean[actor.getActionCount()];
        Arrays.fill(mask, true);
        double[] before = actor.getProbabilitiesFromObservation(observation, mask);
        int action = maxIndex(before);
        double probability = before[action];
        double oldProbability = positiveAdvantage ? probability / 2.0 : probability * 2.0;
        double advantage = positiveAdvantage ? 1.0 : -1.0;
        TrajectoryStep step = new TrajectoryStep(1, observation, observation,
                action, oldProbability, 0.0, observation, mask, false);

        new PPOActorTrainer(0.2).update(actor, step, advantage, 0.001);
        double after = actor.getProbabilitiesFromObservation(observation, mask)[action];
        near(probability, after,
                positiveAdvantage
                        ? "positive advantage above upper clip must have zero gradient"
                        : "negative advantage below lower clip must have zero gradient");
    }

    private static void assertActiveBranch(boolean positiveAdvantage) {
        MAPPOActor actor = testActor(5678L);
        double[] observation = testObservation(actor);
        boolean[] mask = new boolean[actor.getActionCount()];
        Arrays.fill(mask, true);
        double[] before = actor.getProbabilitiesFromObservation(observation, mask);
        int action = maxIndex(before);
        TrajectoryStep step = new TrajectoryStep(1, observation, observation,
                action, before[action], 0.0, observation, mask, false);

        new PPOActorTrainer(0.2).update(actor, step,
                positiveAdvantage ? 1.0 : -1.0, 0.001);
        double after = actor.getProbabilitiesFromObservation(observation, mask)[action];
        check(positiveAdvantage ? after > before[action] : after < before[action],
                "unclipped surrogate branch must move selected probability in the advantage direction");
    }

    private static MAPPOActor testActor(long seed) {
        NetworkTopology topology = new NetworkTopology();
        Router source = new Router(1, "S");
        Router left = new Router(2, "L");
        Router right = new Router(3, "R");
        topology.addRouter(source);
        topology.addRouter(left);
        topology.addRouter(right);
        topology.addLink(new Link(source, left, 1.0, 1000.0));
        topology.addLink(new Link(source, right, 1.0, 1000.0));
        return new MAPPOActor(topology, source, 4, seed);
    }

    private static double[] testObservation(MAPPOActor actor) {
        double[] observation = new double[actor.getObservationInputSize()];
        Arrays.fill(observation, 1.0);
        return observation;
    }

    private static int maxIndex(double[] values) {
        int index = 0;
        for (int i = 1; i < values.length; i++) {
            if (values[i] > values[index]) index = i;
        }
        return index;
    }

    private static void near(double expected, double actual, String message) {
        if (Math.abs(expected - actual) > 1.0e-12) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
