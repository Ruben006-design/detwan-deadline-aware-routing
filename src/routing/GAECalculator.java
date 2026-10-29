package routing;

import java.util.function.ToDoubleFunction;

public class GAECalculator {

    private double gamma;
    private double lambda;

    public GAECalculator(
            double gamma,
            double lambda) {

        this.gamma = gamma;
        this.lambda = lambda;
    }

    public double[] calculate(
            TrajectoryBuffer trajectory,
            MAPPOCritic critic) {

        return calculate(trajectory, critic::getValue);
    }

    /** GAE using each transition's own next state and explicit episode boundary. */
    public double[] calculate(
            TrajectoryBuffer trajectory,
            ToDoubleFunction<double[]> valueFunction) {

        int size = trajectory.size();
        double[] advantages = new double[size];

        double nextAdvantage = 0.0;

        for (int t = size - 1; t >= 0; t--) {

            TrajectoryStep step =
                    trajectory.get(t);

            double currentValue = valueFunction.applyAsDouble(step.getGlobalState());
            double nextValue = step.isTerminal()
                    ? 0.0
                    : valueFunction.applyAsDouble(step.getNextGlobalState());
            // A truncation bootstraps its own next state but ends the sampled
            // advantage recursion. A true terminal has zero next value.
            double followingAdvantage = step.getBoundaryType() == TrajectoryStep.BoundaryType.NONE
                    ? nextAdvantage : 0.0;

            // Eq. (25) - TD residual and lambda-weighted backward advantage recursion.
            double delta =
                    step.getReward()
                    + gamma * nextValue
                    - currentValue;

            advantages[t] =
                    delta
                    + gamma * lambda * followingAdvantage;

            nextAdvantage =
                    advantages[t];
        }

        return advantages;
    }

    /**
     * Calculates the discounted-return critic targets from paper Eq. (27).
     * A truncated transition bootstraps its next state and ends that rollout's
     * recursion; a true terminal transition has zero continuation. A final
     * unmarked row is treated as a finite collection boundary for compatibility.
     */
    public double[] calculateDiscountedReturns(
            TrajectoryBuffer trajectory,
            MAPPOCritic critic) {

        return calculateDiscountedReturns(trajectory, critic::getValue);
    }

    /** Value-function overload supports deterministic calculation and tests. */
    public double[] calculateDiscountedReturns(
            TrajectoryBuffer trajectory,
            ToDoubleFunction<double[]> valueFunction) {

        int size = trajectory.size();
        double[] returns = new double[size];
        if (size == 0) {
            return returns;
        }

        for (int t = size - 1; t >= 0; t--) {
            TrajectoryStep step = trajectory.get(t);
            double continuation;
            if (step.isTerminal()) {
                continuation = 0.0;
            } else if (step.isTruncated() || t == size - 1) {
                // Eq. (27) includes V(s_T) at a rollout horizon.
                continuation = valueFunction.applyAsDouble(step.getNextGlobalState());
            } else {
                continuation = returns[t + 1];
            }
            // Eq. (27) - Discounted rewards plus the terminal or rollout-horizon critic bootstrap.
            returns[t] = step.getReward() + gamma * continuation;
        }

        return returns;
    }
}
