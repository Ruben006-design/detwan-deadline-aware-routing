package routing;

public class PPOCalculator {

    private double clipEpsilon;

    public PPOCalculator(double clipEpsilon) {
        this.clipEpsilon = clipEpsilon;
    }

    public double calculateObjective(
            double oldProbability,
            double newProbability,
            double advantage) {

        if (oldProbability <= 0.0) {
            return 0.0;
        }

        // Eq. (26) - Evaluate the clipped PPO surrogate for one stored action sample.
        double ratio =
                newProbability / oldProbability;

        double clippedRatio =
                Math.max(
                        1.0 - clipEpsilon,
                        Math.min(
                                1.0 + clipEpsilon,
                                ratio));

        double unclipped =
                ratio * advantage;

        double clipped =
                clippedRatio * advantage;

        return Math.min(
                unclipped,
                clipped);
    }
}
