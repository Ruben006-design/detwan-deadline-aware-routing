package routing;

import java.util.*;
import network.*;
import traffic.TrafficFlow;

public class MAPPOActor {

    private NeuralNetwork network;
    private NetworkTopology topology;
    private Router agent;
    private int budgetLevels;
    private final int inputSize;
    private final int outputSize;
    private final Random standaloneRandom;

    public MAPPOActor(
            NetworkTopology topology,
            Router agent,
            int budgetLevels) {

        this(topology, agent, budgetLevels, 42L);
    }

    public MAPPOActor(
            NetworkTopology topology,
            Router agent,
            int budgetLevels,
            long randomSeed) {

        this.topology = topology;
        this.agent = agent;
        this.budgetLevels = budgetLevels;
        this.standaloneRandom = new Random(randomSeed);

        int V = topology.getRouterCount();
        int Ni = topology.getLinks(agent).size();

        this.inputSize = V * V + 2 * V * Ni + 4 * V + 3 + 2 * Ni;
        this.outputSize = budgetLevels * Ni;

        network = new NeuralNetwork(this.inputSize, this.outputSize, randomSeed);
    }

    public int getObservationInputSize() { return inputSize; }
    public int getActionCount() { return outputSize; }

    public BudgetAction selectAction(
            RouterObservation observation) {

        return selectAction(observation, getObservationActionMask(observation), standaloneRandom);
    }

    /** Selects only among actions enabled by the supplied index-aligned mask. */
    public BudgetAction selectAction(
            RouterObservation observation,
            boolean[] validActionMask,
            Random random) {

        double[] input =
                getObservationInput(observation);

        double[] probabilities =
                getProbabilitiesFromObservation(input, validActionMask);

        List<BudgetAction> actions =
                ActionSpace.getActions(
                        agent,
                        observation.getFlow(),
                        topology,
                        budgetLevels);

        if (actions.isEmpty() || probabilities.length != actions.size()
                || (validActionMask != null && validActionMask.length != actions.size())
                || !hasValidAction(validActionMask)) {
            return null;
        }

        int selected =
                sampleAction(
                        probabilities,
                        random);

        if (selected < 0 || selected >= actions.size()
                || (validActionMask != null && !validActionMask[selected])) {
            return null;
        }

        return actions.get(selected);
    }

    public double[] getProbabilities(
            RouterObservation observation) {

        return getProbabilities(observation, getObservationActionMask(observation));
    }

    public double[] getProbabilities(
            RouterObservation observation,
            boolean[] validActionMask) {

        double[] input =
                getObservationInput(observation);

        return getProbabilitiesFromObservation(input, validActionMask);
    }

    public double[] getProbabilitiesFromObservation(
            double[] observation) {

        if (observation == null || observation.length != inputSize) {
            throw new IllegalArgumentException("Actor observation size must be " + inputSize);
        }
        return softmax(network.forward(observation));
    }

    /** PPO uses the same state-specific mask that was used during sampling. */
    public double[] getProbabilitiesFromObservation(
            double[] observation,
            boolean[] validActionMask) {

        if (observation == null || observation.length != inputSize) {
            throw new IllegalArgumentException("Actor observation size must be " + inputSize);
        }

        double[] logits = network.forward(observation);
        if (validActionMask == null) {
            return softmax(logits);
        }
        if (validActionMask.length != logits.length) {
            throw new IllegalArgumentException("Action mask length must match actor output size");
        }
        return softmax(logits, validActionMask);
    }

    public void train(
            double[] observation,
            double[] outputGradient,
            double learningRate) {

        network.trainWithOutputGradient(
                observation,
                outputGradient,
                learningRate);
    }

    /** One Adam step for the average actor gradient of a sampled minibatch. */
    public void trainBatch(double[][] observations, double[][] outputGradients,
                           int minibatchSize, double learningRate) {
        network.trainWithOutputGradients(observations, outputGradients,
                minibatchSize, learningRate);
    }

    private double[] getObservationInput(
            RouterObservation observation) {

        ObservationEncoder encoder =
                new ObservationEncoder(
                        topology,
                        budgetLevels);

        return encoder.encodeActorObservation(
                observation);
    }

    private boolean[] getObservationActionMask(RouterObservation observation) {
        Set<Router> visited = new HashSet<>();
        visited.add(observation.getFlow().getSource());
        double accumulatedBudget = 0.0;
        for (PathDecision decision : observation.getPreviousDecisions()) {
            visited.add(decision.getRouter());
            visited.add(decision.getNextHop());
            accumulatedBudget += decision.getDeadlineBudget();
        }
        double processingDelay = observation.getNeighborStates().isEmpty()
                ? 0.0 : observation.getNeighborStates().get(0).getProcessingDelay();
        return ActionSpace.getActionMask(
                agent, observation.getFlow(), topology, budgetLevels,
                visited, accumulatedBudget, processingDelay);
    }

    private double[] softmax(double[] logits) {

        boolean[] allValid = new boolean[logits.length];
        Arrays.fill(allValid, true);
        return softmax(logits, allValid);
    }

    private double[] softmax(double[] logits, boolean[] validActionMask) {

        if (validActionMask.length != logits.length) {
            throw new IllegalArgumentException("Action mask length must match actor output size");
        }

        double[] probabilities =
                new double[logits.length];

        double max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < logits.length; i++) {
            if (validActionMask[i]) {
                max = Math.max(max, logits[i]);
            }
        }

        if (max == Double.NEGATIVE_INFINITY) {
            return probabilities;
        }

        double sum = 0.0;

        for (int i = 0; i < logits.length; i++) {
            if (validActionMask[i]) {
                probabilities[i] = Math.exp(logits[i] - max);
                sum += probabilities[i];
            }
        }

        if (sum == 0.0) {
            return probabilities;
        }

        for (int i = 0; i < probabilities.length; i++) {
            probabilities[i] /= sum;
        }

        return probabilities;
    }

    private int sampleAction(double[] probabilities, Random random) {

        if (random == null) {
            throw new IllegalArgumentException("Random source is required");
        }

        double draw = random.nextDouble();
        double cumulative = 0.0;

        for (int i = 0; i < probabilities.length; i++) {
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

    private boolean hasValidAction(boolean[] mask) {
        if (mask == null) {
            return true;
        }
        for (boolean valid : mask) {
            if (valid) {
                return true;
            }
        }
        return false;
    }
}
