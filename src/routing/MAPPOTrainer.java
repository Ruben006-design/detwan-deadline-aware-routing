package routing;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

import network.NetworkTopology;
import network.Router;

public class MAPPOTrainer {

    private Map<Router, MAPPOActor> actors;
    private MAPPOCritic critic;
    private GAECalculator gaeCalculator;
    private PPOActorTrainer actorTrainer;

    private double gamma;
    private double learningRate;
    private double admissionReward;
    private final Random actionRandom;
    private final Random minibatchRandom;

    public MAPPOTrainer(
            NetworkTopology topology,
            int budgetLevels,
            int criticInputSize,
            double gamma,
            double lambda,
            double clipEpsilon,
            double learningRate,
            double admissionReward) {

        this(topology, budgetLevels, criticInputSize, gamma, lambda,
                clipEpsilon, learningRate, admissionReward, 42L);
    }

    public MAPPOTrainer(
            NetworkTopology topology,
            int budgetLevels,
            int criticInputSize,
            double gamma,
            double lambda,
            double clipEpsilon,
            double learningRate,
            double admissionReward,
            long randomSeed) {

        actors = new HashMap<>();
        actionRandom = new Random(randomSeed);
        minibatchRandom = new Random(randomSeed ^ 0xBB67AE8584CAA73BL);

        for (Router router : topology.getRouters()) {
            actors.put(
                    router,
                    new MAPPOActor(
                            topology,
                            router,
                            budgetLevels,
                            randomSeed + router.getId()));
        }

        // Keep critic initialization reproducible for each experiment seed,
        // while using a distinct deterministic stream from the router actors.
        critic = new MAPPOCritic(criticInputSize, randomSeed ^ 0x6A09E667F3BCC909L);

        gaeCalculator =
                new GAECalculator(
                        gamma,
                        lambda);

        actorTrainer =
                new PPOActorTrainer(
                        clipEpsilon);

        this.gamma = gamma;
        this.learningRate = learningRate;
        this.admissionReward = admissionReward;
    }

    public MAPPOActor getActor(Router router) {
        return actors.get(router);
    }

    /** Samples an action index from an already mask-normalized distribution. */
    public int sampleActionIndex(double[] probabilities) {
        return sampleActionIndex(probabilities, actionRandom);
    }

    /** Samples using a caller-owned random stream, e.g. isolated evaluation RNG. */
    public int sampleActionIndex(double[] probabilities, Random random) {
        if (probabilities == null || probabilities.length == 0) {
            return -1;
        }
        if (random == null) throw new IllegalArgumentException("Random stream is required");
        double draw = random.nextDouble();
        double cumulative = 0.0;
        for (int i = 0; i < probabilities.length; i++) {
            if (!Double.isFinite(probabilities[i]) || probabilities[i] < 0.0) {
                throw new IllegalArgumentException("Action probabilities must be finite and non-negative");
            }
            cumulative += probabilities[i];
            if (probabilities[i] > 0.0 && draw < cumulative) {
                return i;
            }
        }
        for (int i = probabilities.length - 1; i >= 0; i--) {
            if (probabilities[i] > 0.0) {
                return i;
            }
        }
        return -1;
    }

    public MAPPOCritic getCritic() {
        return critic;
    }

    public int getCriticInputSize() {
        return critic.getInputSize();
    }

    /**
     * Compatibility entry point: one random minibatch update round, using up
     * to four samples per agent. Experiment code should pass its explicit
     * schedule to {@link #train(TrajectoryBuffer, int, int)}.
     */
    public TrainingStats train(TrajectoryBuffer trajectory) {
        if (trajectory == null || trajectory.size() == 0) {
            return TrainingStats.empty();
        }
        int smallestAgentTrajectory = Integer.MAX_VALUE;
        for (TrajectoryBuffer agentTrajectory : trajectory.getAgentTrajectories().values()) {
            smallestAgentTrajectory = Math.min(smallestAgentTrajectory, agentTrajectory.size());
        }
        return train(trajectory, Math.min(4, smallestAgentTrajectory), 1);
    }

    /**
     * Performs per-agent random minibatch updates. Sampling is without
     * replacement within a minibatch and independently repeated each round;
     * the paper does not specify replacement behavior. The critic remains one
     * shared parameter set, updated sequentially from each agent's batch.
     */
    public TrainingStats train(
            TrajectoryBuffer trajectory,
            int minibatchSize,
            int updateRounds) {

        if (trajectory == null ||
                trajectory.size() == 0) {
            return TrainingStats.empty();
        }
        if (minibatchSize <= 0 || updateRounds <= 0) {
            throw new IllegalArgumentException("Minibatch size and update rounds must be positive");
        }

        Map<Integer, TrajectoryBuffer> agentTrajectories =
                trajectory.getAgentTrajectories();
        for (Map.Entry<Integer, TrajectoryBuffer> entry : agentTrajectories.entrySet()) {
            if (entry.getValue().size() < minibatchSize) {
                throw new IllegalArgumentException("Minibatch size " + minibatchSize
                        + " exceeds trajectory size " + entry.getValue().size()
                        + " for agent " + entry.getKey());
            }
        }

        Map<Integer, double[]> advantagesByAgent = new HashMap<>();
        Map<Integer, double[]> discountedReturnsByAgent = new HashMap<>();
        // Algorithm 2: freeze rollout-time GAE/return targets before any actor or critic optimizer update.
        for (Map.Entry<Integer, TrajectoryBuffer> entry : agentTrajectories.entrySet()) {
            TrajectoryBuffer agentTrajectory = entry.getValue();
            advantagesByAgent.put(entry.getKey(),
                    gaeCalculator.calculate(agentTrajectory, critic));
            // Compute every Eq. (27) target before any critic update changes
            // the rollout-time bootstrap value.
            discountedReturnsByAgent.put(entry.getKey(),
                    gaeCalculator.calculateDiscountedReturns(agentTrajectory, critic));
        }

        TrainingStats stats = new TrainingStats();
        // Algorithm 2: each agent samples its own minibatch and performs one actor/critic step per round.
        for (int round = 0; round < updateRounds; round++) {
            for (Map.Entry<Integer, TrajectoryBuffer> entry : agentTrajectories.entrySet()) {
                int agentId = entry.getKey();
                TrajectoryBuffer agentTrajectory = entry.getValue();
                double[] advantages = advantagesByAgent.get(agentId);
                double[] discountedReturns = discountedReturnsByAgent.get(agentId);
                MAPPOActor actor = actors.get(getRouterForAgentId(agentId));
                int[] sampledIndices = sampleWithoutReplacement(
                        agentTrajectory.size(), minibatchSize);
                stats.minibatchesSampled++;

                List<TrajectoryStep> minibatch = new ArrayList<>(minibatchSize);
                double[] minibatchAdvantages = new double[minibatchSize];
                double[][] globalStates = new double[minibatchSize][];
                double[] criticTargets = new double[minibatchSize];

                for (int sampleIndex = 0; sampleIndex < sampledIndices.length; sampleIndex++) {
                    int transitionIndex = sampledIndices[sampleIndex];
                    TrajectoryStep step = agentTrajectory.get(transitionIndex);
                    minibatch.add(step);
                    minibatchAdvantages[sampleIndex] = advantages[transitionIndex];
                    globalStates[sampleIndex] = step.getGlobalState();
                    criticTargets[sampleIndex] = discountedReturns[transitionIndex];
                    stats.sampledTransitions.add(
                            new SampledTransition(agentId, round, transitionIndex));
                    if (!step.isActive()) stats.inactiveActorSamplesSkipped++;
                }

                // Algorithm 2 applies one critic and one actor optimizer step
                // per sampled minibatch. All targets were computed from the
                // pre-update critic snapshot above.
                critic.trainBatch(globalStates, criticTargets, learningRate);
                stats.criticUpdates++;
                if (actor != null && actorTrainer.updateBatch(actor, minibatch,
                        minibatchAdvantages, minibatchSize, learningRate)) {
                    stats.actorUpdates++;
                }
            }
        }
        return stats.freeze();
    }
    private Router getRouterForAgentId(int agentId) {
        for (Router router : actors.keySet()) {
            if (router.getId() == agentId) return router;
        }
        return null;
    }

    private int[] sampleWithoutReplacement(int populationSize, int sampleSize) {
        int[] indices = new int[populationSize];
        for (int i = 0; i < populationSize; i++) indices[i] = i;
        for (int i = 0; i < sampleSize; i++) {
            int selected = i + minibatchRandom.nextInt(populationSize - i);
            int swap = indices[i];
            indices[i] = indices[selected];
            indices[selected] = swap;
        }
        int[] sample = new int[sampleSize];
        System.arraycopy(indices, 0, sample, 0, sampleSize);
        return sample;
    }

    public static final class TrainingStats {
        private int minibatchesSampled;
        private int criticUpdates;
        private int actorUpdates;
        private int inactiveActorSamplesSkipped;
        private List<SampledTransition> sampledTransitions = new ArrayList<>();

        private static TrainingStats empty() {
            return new TrainingStats().freeze();
        }

        private TrainingStats freeze() {
            sampledTransitions = Collections.unmodifiableList(
                    new ArrayList<>(sampledTransitions));
            return this;
        }

        TrainingStats withoutSamplingTrace() {
            TrainingStats summary = new TrainingStats();
            summary.minibatchesSampled = minibatchesSampled;
            summary.criticUpdates = criticUpdates;
            summary.actorUpdates = actorUpdates;
            summary.inactiveActorSamplesSkipped = inactiveActorSamplesSkipped;
            summary.sampledTransitions = Collections.emptyList();
            return summary;
        }

        public int getMinibatchesSampled() { return minibatchesSampled; }
        public int getCriticUpdates() { return criticUpdates; }
        public int getActorUpdates() { return actorUpdates; }
        public int getInactiveActorSamplesSkipped() { return inactiveActorSamplesSkipped; }
        public List<SampledTransition> getSampledTransitions() { return sampledTransitions; }
    }

    public static final class SampledTransition {
        private final int agentId;
        private final int updateRound;
        private final int transitionIndex;

        private SampledTransition(int agentId, int updateRound, int transitionIndex) {
            this.agentId = agentId;
            this.updateRound = updateRound;
            this.transitionIndex = transitionIndex;
        }

        public int getAgentId() { return agentId; }
        public int getUpdateRound() { return updateRound; }
        public int getTransitionIndex() { return transitionIndex; }
    }
    public double getAdmissionReward() {
        return admissionReward;
    }

    public double getGamma() {
        return gamma;
    }
}
