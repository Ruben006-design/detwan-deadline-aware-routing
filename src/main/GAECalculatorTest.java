import routing.GAECalculator;
import routing.TrajectoryBuffer;
import routing.TrajectoryStep;
import java.util.Map;

public class GAECalculatorTest {
    public static void main(String[] args) {
        testOwnNextStateAndTerminalBoundary();
        testInterleavedAgentsHaveIndependentRecursions();
        testInactiveRowsPreservePerAgentRequestSequence();
        testEq27DiscountedReturnTargets();
        testTruncationAndTerminationBootstrapping();
        System.out.println("GAECalculatorTest passed: GAE isolation/terminal, inactive rows, and Eq. (27) return targets.");
    }

    private static void testOwnNextStateAndTerminalBoundary() {
        TrajectoryBuffer trajectory = new TrajectoryBuffer();
        trajectory.add(step(1, 1, 1.0, 2, false));
        trajectory.add(step(2, 2, 2.0, 3, true));

        double[] advantages = new GAECalculator(1.0, 1.0).calculate(
                trajectory, state -> 10.0 * state[0]);
        near(-7.0, advantages[0], "transition must use its own nextGlobalState [2]");
        near(-18.0, advantages[1], "terminal transition must not bootstrap from [3]");
    }

    private static void testInterleavedAgentsHaveIndependentRecursions() {
        TrajectoryBuffer mixed = new TrajectoryBuffer();
        mixed.add(step(1, 10, 0.0, 11, false));
        mixed.add(step(2, 20, 100.0, 21, false));
        mixed.add(step(1, 12, 1.0, 13, true));

        Map<Integer, TrajectoryBuffer> perAgent = mixed.getAgentTrajectories();
        check(perAgent.size() == 2 && perAgent.get(1).size() == 2
                        && perAgent.get(2).size() == 1,
                "interleaved transitions must be grouped in per-agent order");

        GAECalculator calculator = new GAECalculator(1.0, 1.0);
        java.util.function.ToDoubleFunction<double[]> value = state -> state[0];
        double[] agentOne = calculator.calculate(perAgent.get(1), value);
        double[] agentTwo = calculator.calculate(perAgent.get(2), value);
        near(-11.0, agentOne[1], "agent 1 terminal transition value");
        near(101.0, agentTwo[0], "agent 2 must not inherit agent 1's following advantage");
        near(-10.0, agentOne[0], "agent 1 recursion must use agent 1's next step only");

        double[] terminal = calculator.calculate(
                singleton(step(3, 2, 5.0, 100, true)), value);
        near(3.0, terminal[0], "terminal transition must ignore its next-state value");
    }

    private static void testInactiveRowsPreservePerAgentRequestSequence() {
        TrajectoryBuffer rollout = new TrajectoryBuffer();
        rollout.add(new TrajectoryStep(7, new double[] {1}, new double[] {0},
                -1, 0.0, 0.0, new double[] {2}, new boolean[0], false, false));
        rollout.add(new TrajectoryStep(7, new double[] {2}, new double[] {0},
                0, 1.0, 1.0, new double[] {3}, new boolean[] {true}, true, true));
        TrajectoryBuffer agent = rollout.getAgentTrajectories().get(7);
        check(agent.size() == 2 && !agent.get(0).isActive() && agent.get(1).isActive(),
                "inactive request must remain in the agent's time sequence");
        double[] advantages = new GAECalculator(1.0, 1.0).calculate(agent,
                state -> state[0]);
        near(0.0, advantages[0],
                "GAE must recurse through the inactive request's shared reward/state transition");
        near(-1.0, advantages[1], "active terminal request advantage");
    }

    private static void testEq27DiscountedReturnTargets() {
        TrajectoryBuffer rollout = new TrajectoryBuffer();
        rollout.add(step(1, 0.0, 1.0, 1.0, false));
        rollout.add(step(1, 1.0, 2.0, 2.0, false));
        // Main marks the fixed rollout horizon terminal; Eq. (27) still
        // bootstraps from its final state s_T.
        rollout.add(step(1, 2.0, 3.0, 4.0, TrajectoryStep.BoundaryType.TRUNCATED));

        GAECalculator calculator = new GAECalculator(0.5, 0.95);
        double[] returns = calculator.calculateDiscountedReturns(
                rollout, state -> state[0]);
        near(3.25, returns[0], "Eq. (27) first return includes all rewards and horizon bootstrap");
        near(4.5, returns[1], "Eq. (27) second return includes horizon bootstrap");
        near(5.0, returns[2], "Eq. (27) final return bootstraps V(s_T)");

        double[] advantages = calculator.calculate(rollout, state -> state[0]);
        double oldLambdaReturn = rollout.get(0).getGlobalState()[0] + advantages[0];
        near(3.126875, oldLambdaReturn,
                "old V(s)+GAE(lambda) target for the same sequence");
        check(Math.abs(returns[0] - oldLambdaReturn) > 0.1,
                "test sequence must distinguish Eq. (27) from V(s)+GAE(lambda)");

        TrajectoryBuffer absorbingTerminal = new TrajectoryBuffer();
        absorbingTerminal.add(step(1, 0.0, 1.0, 99.0,
                TrajectoryStep.BoundaryType.TERMINATED));
        absorbingTerminal.add(step(1, 1.0, 2.0, 2.0, false));
        absorbingTerminal.add(step(1, 2.0, 3.0, 4.0,
                TrajectoryStep.BoundaryType.TRUNCATED));
        double[] terminalReturns = calculator.calculateDiscountedReturns(
                absorbingTerminal, state -> state[0]);
        near(1.0, terminalReturns[0],
                "an earlier absorbing terminal transition must not bootstrap or cross into later rows");
    }

    private static void testTruncationAndTerminationBootstrapping() {
        GAECalculator calculator = new GAECalculator(0.5, 0.95);
        java.util.function.ToDoubleFunction<double[]> value = state -> state[0];

        TrajectoryBuffer rollout = new TrajectoryBuffer();
        rollout.add(step(1, 1.0, 2.0, 3.0, TrajectoryStep.BoundaryType.NONE));
        rollout.add(step(1, 3.0, 4.0, 5.0, TrajectoryStep.BoundaryType.TRUNCATED));
        double[] actorAdvantages = calculator.calculate(rollout, value);
        double[] criticReturns = calculator.calculateDiscountedReturns(rollout, value);
        near(3.5, actorAdvantages[1], "truncated final GAE delta bootstraps V(s_T)");
        near(6.5, criticReturns[1], "truncated final critic target bootstraps V(s_T)");
        near(6.5, value.applyAsDouble(rollout.get(1).getGlobalState()) + actorAdvantages[1],
                "actor GAE and critic return agree at the final rollout transition");
        near(4.1625, actorAdvantages[0],
                "nonterminal GAE transition recurses into the following advantage");
        near(5.25, criticReturns[0],
                "nonterminal critic return includes later rewards and horizon bootstrap");

        TrajectoryBuffer terminated = new TrajectoryBuffer();
        terminated.add(step(1, 1.0, 2.0, 99.0,
                TrajectoryStep.BoundaryType.TERMINATED));
        double[] terminalAdvantage = calculator.calculate(terminated, value);
        double[] terminalReturn = calculator.calculateDiscountedReturns(terminated, value);
        near(1.0, terminalAdvantage[0], "true terminal GAE uses zero next-state value");
        near(2.0, terminalReturn[0], "true terminal critic return does not bootstrap");
    }

    private static TrajectoryStep step(int agent, double state, double reward,
                                       double next, boolean terminal) {
        return new TrajectoryStep(agent, new double[] {state}, new double[] {0},
                0, 1.0, reward, new double[] {next}, null, terminal);
    }

    private static TrajectoryStep step(int agent, double state, double reward,
                                       double next, TrajectoryStep.BoundaryType boundary) {
        return new TrajectoryStep(agent, new double[] {state}, new double[] {0},
                0, 1.0, reward, new double[] {next}, null, boundary, true);
    }

    private static TrajectoryBuffer singleton(TrajectoryStep step) {
        TrajectoryBuffer buffer = new TrajectoryBuffer();
        buffer.add(step);
        return buffer;
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
