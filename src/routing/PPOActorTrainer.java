package routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PPOActorTrainer {

    private final double clipEpsilon;

    public PPOActorTrainer(double clipEpsilon) {
        this.clipEpsilon = clipEpsilon;
    }

    /** Single-sample compatibility wrapper. */
    public boolean update(MAPPOActor actor, TrajectoryStep step,
                          double advantage, double learningRate) {
        return updateBatch(actor, Collections.singletonList(step),
                new double[] {advantage}, 1, learningRate);
    }

    // Eqs. (24) and (26) - The advantage-weighted score-function update is optimized through the PPO clipped surrogate.
    /**
     * Applies one actor optimizer step for a sampled agent minibatch. Inactive
     * records contribute zero to Eq. (26) and are never passed to the actor;
     * gradients are normalized by the full minibatch size, not active count.
     */
    public boolean updateBatch(MAPPOActor actor, List<TrajectoryStep> steps,
                               double[] advantages, int minibatchSize,
                               double learningRate) {
        if (actor == null || steps == null || advantages == null
                || steps.isEmpty() || steps.size() != advantages.length
                || minibatchSize < steps.size()) {
            throw new IllegalArgumentException("Invalid actor minibatch");
        }

        List<double[]> observations = new ArrayList<>();
        List<double[]> outputGradients = new ArrayList<>();
        for (int index = 0; index < steps.size(); index++) {
            TrajectoryStep step = steps.get(index);
            if (!step.isActive()) continue;

            double oldProbability = step.getActionProbability();
            if (oldProbability <= 0.0) continue;

            double[] observation = step.getObservation();
            boolean[] actionMask = step.getActionMask();
            double[] probabilities = actor.getProbabilitiesFromObservation(
                    observation, actionMask);
            int actionIndex = step.getActionIndex();
            if (actionIndex < 0 || actionIndex >= probabilities.length
                    || (actionMask != null
                            && (actionIndex >= actionMask.length || !actionMask[actionIndex]))) {
                continue;
            }

            // Eq. (26) - Form the PPO importance ratio from the current and rollout-stored probabilities.
            double ratio = probabilities[actionIndex] / oldProbability;
            double advantage = advantages[index];
            // Eq. (26) - Select the active derivative branch of min(ratio*A, clip(ratio)*A).
            boolean clipped = (advantage >= 0.0 && ratio > 1.0 + clipEpsilon)
                    || (advantage < 0.0 && ratio < 1.0 - clipEpsilon);
            double coefficient = clipped ? 0.0 : ratio * advantage;

            double[] outputGradient = new double[probabilities.length];
            for (int action = 0; action < probabilities.length; action++) {
                if (actionMask != null
                        && (action >= actionMask.length || !actionMask[action])) {
                    continue;
                }
                double logProbabilityGradient = action == actionIndex
                        ? 1.0 - probabilities[action]
                        : -probabilities[action];
                // Gradient descent on the negative of Eq. (26).
                outputGradient[action] = -coefficient * logProbabilityGradient;
            }
            observations.add(observation);
            outputGradients.add(outputGradient);
        }

        if (observations.isEmpty()) return false;
        actor.trainBatch(observations.toArray(new double[observations.size()][]),
                outputGradients.toArray(new double[outputGradients.size()][]),
                minibatchSize, learningRate);
        return true;
    }
}
