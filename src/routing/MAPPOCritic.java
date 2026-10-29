package routing;

public class MAPPOCritic {

    private NeuralNetwork network;
    private final int inputSize;

    public MAPPOCritic(int inputSize) {
        this(inputSize, 42L);
    }

    public MAPPOCritic(int inputSize, long randomSeed) {
        this.inputSize = inputSize;
        network = new NeuralNetwork(inputSize, 1, randomSeed);
    }

    public int getInputSize() { return inputSize; }

    public double getValue(double[] globalState) {
        if (globalState == null || globalState.length != inputSize) {
            throw new IllegalArgumentException("Critic global-state size must be " + inputSize);
        }
        double[] output =
                network.forward(globalState);

        return output[0];
    }

    public void train(
            double[] globalState,
            double targetValue,
            double learningRate) {

        if (globalState == null || globalState.length != inputSize) {
            throw new IllegalArgumentException("Critic global-state size must be " + inputSize);
        }

        double[] target =
                new double[]{targetValue};

        network.train(
                globalState,
                target,
                learningRate);
    }

    /** One Adam step minimizing the minibatch mean critic regression loss. */
    public void trainBatch(double[][] globalStates, double[] targetValues,
                           double learningRate) {
        if (globalStates == null || targetValues == null || globalStates.length == 0
                || globalStates.length != targetValues.length) {
            throw new IllegalArgumentException("Critic minibatch states and targets must have equal non-zero length");
        }
        double[][] targets = new double[targetValues.length][1];
        for (int i = 0; i < globalStates.length; i++) {
            if (globalStates[i] == null || globalStates[i].length != inputSize) {
                throw new IllegalArgumentException("Critic global-state size must be " + inputSize);
            }
            targets[i][0] = targetValues[i];
        }
        // Eq. (28) - Regress the shared centralized critic toward fixed rollout return targets.
        network.trainBatch(globalStates, targets, learningRate);
    }
}
