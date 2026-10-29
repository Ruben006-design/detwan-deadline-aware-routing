package routing;

import java.util.Random;

public class NeuralNetwork {

    private int inputSize;
    private int hiddenSize = 128;
    private int outputSize;

    private double[][] w1;
    private double[][] w2;
    private double[][] w3;

    private double[] b1;
    private double[] b2;
    private double[] b3;

    private double[][] mw1, vw1;
    private double[][] mw2, vw2;
    private double[][] mw3, vw3;

    private double[] mb1, vb1;
    private double[] mb2, vb2;
    private double[] mb3, vb3;

    private long timestep = 0;

    private double beta1 = 0.9;
    private double beta2 = 0.999;
    private double epsilon = 1e-8;

    private final Random random;

    public NeuralNetwork(int inputSize, int outputSize) {
        this(inputSize, outputSize, 42L);
    }

    public NeuralNetwork(int inputSize, int outputSize, long randomSeed) {
        this.inputSize = inputSize;
        this.outputSize = outputSize;
        this.random = new Random(randomSeed);

        w1 = new double[inputSize][hiddenSize];
        w2 = new double[hiddenSize][hiddenSize];
        w3 = new double[hiddenSize][outputSize];

        b1 = new double[hiddenSize];
        b2 = new double[hiddenSize];
        b3 = new double[outputSize];

        mw1 = new double[inputSize][hiddenSize];
        vw1 = new double[inputSize][hiddenSize];
        mw2 = new double[hiddenSize][hiddenSize];
        vw2 = new double[hiddenSize][hiddenSize];
        mw3 = new double[hiddenSize][outputSize];
        vw3 = new double[hiddenSize][outputSize];

        mb1 = new double[hiddenSize];
        vb1 = new double[hiddenSize];
        mb2 = new double[hiddenSize];
        vb2 = new double[hiddenSize];
        mb3 = new double[outputSize];
        vb3 = new double[outputSize];

        initialize(w1);
        initialize(w2);
        initialize(w3);
    }

    private void initialize(double[][] weights) {
        for (int i = 0; i < weights.length; i++) {
            for (int j = 0; j < weights[i].length; j++) {
                weights[i][j] =
                        (random.nextDouble() - 0.5) * 0.02;
            }
        }
    }

    public double[] forward(double[] input) {

        double[] h1 = new double[hiddenSize];
        double[] h2 = new double[hiddenSize];
        double[] output = new double[outputSize];

        for (int j = 0; j < hiddenSize; j++) {
            double sum = b1[j];

            for (int i = 0; i < inputSize; i++) {
                sum += input[i] * w1[i][j];
            }

            h1[j] = relu(sum);
        }

        for (int j = 0; j < hiddenSize; j++) {
            double sum = b2[j];

            for (int i = 0; i < hiddenSize; i++) {
                sum += h1[i] * w2[i][j];
            }

            h2[j] = relu(sum);
        }

        for (int j = 0; j < outputSize; j++) {
            double sum = b3[j];

            for (int i = 0; i < hiddenSize; i++) {
                sum += h2[i] * w3[i][j];
            }

            output[j] = sum;
        }

        return output;
    }

    public void train(
            double[] input,
            double[] target,
            double learningRate) {

        double[] h1 = new double[hiddenSize];
        double[] h2 = new double[hiddenSize];
        double[] output = new double[outputSize];

        for (int j = 0; j < hiddenSize; j++) {
            double sum = b1[j];

            for (int i = 0; i < inputSize; i++) {
                sum += input[i] * w1[i][j];
            }

            h1[j] = relu(sum);
        }

        for (int j = 0; j < hiddenSize; j++) {
            double sum = b2[j];

            for (int i = 0; i < hiddenSize; i++) {
                sum += h1[i] * w2[i][j];
            }

            h2[j] = relu(sum);
        }

        for (int j = 0; j < outputSize; j++) {
            double sum = b3[j];

            for (int i = 0; i < hiddenSize; i++) {
                sum += h2[i] * w3[i][j];
            }

            output[j] = sum;
        }

        double[] dOutput = new double[outputSize];

        for (int j = 0; j < outputSize; j++) {
            // Eq. (28) - The regression gradient is proportional to V(s)-G_hat (half-MSE scaling).
            dOutput[j] = output[j] - target[j];
        }

        double[] dH2 = new double[hiddenSize];

        for (int i = 0; i < hiddenSize; i++) {
            double sum = 0.0;

            for (int j = 0; j < outputSize; j++) {
                sum += dOutput[j] * w3[i][j];
            }

            dH2[i] = h2[i] > 0 ? sum : 0.0;
        }

        double[] dH1 = new double[hiddenSize];

        for (int i = 0; i < hiddenSize; i++) {
            double sum = 0.0;

            for (int j = 0; j < hiddenSize; j++) {
                sum += dH2[j] * w2[i][j];
            }

            dH1[i] = h1[i] > 0 ? sum : 0.0;
        }

        double[][] gw3 =
                new double[hiddenSize][outputSize];

        double[][] gw2 =
                new double[hiddenSize][hiddenSize];

        double[][] gw1 =
                new double[inputSize][hiddenSize];

        double[] gb3 = new double[outputSize];
        double[] gb2 = new double[hiddenSize];
        double[] gb1 = new double[hiddenSize];

        for (int i = 0; i < hiddenSize; i++) {
            for (int j = 0; j < outputSize; j++) {
                gw3[i][j] =
                        dOutput[j] * h2[i];
            }
        }

        for (int j = 0; j < outputSize; j++) {
            gb3[j] = dOutput[j];
        }

        for (int i = 0; i < hiddenSize; i++) {
            for (int j = 0; j < hiddenSize; j++) {
                gw2[i][j] =
                        dH2[j] * h1[i];
            }
        }

        for (int j = 0; j < hiddenSize; j++) {
            gb2[j] = dH2[j];
        }

        for (int i = 0; i < inputSize; i++) {
            for (int j = 0; j < hiddenSize; j++) {
                gw1[i][j] =
                        dH1[j] * input[i];
            }
        }

        for (int j = 0; j < hiddenSize; j++) {
            gb1[j] = dH1[j];
        }

        timestep++;

        updateWeights(
                w1, mw1, vw1, gw1,
                learningRate);

        updateWeights(
                w2, mw2, vw2, gw2,
                learningRate);

        updateWeights(
                w3, mw3, vw3, gw3,
                learningRate);

        updateBias(
                b1, mb1, vb1, gb1,
                learningRate);

        updateBias(
                b2, mb2, vb2, gb2,
                learningRate);

        updateBias(
                b3, mb3, vb3, gb3,
                learningRate);
    }

    private void updateWeights(
            double[][] weights,
            double[][] firstMoment,
            double[][] secondMoment,
            double[][] gradients,
            double learningRate) {

        double biasCorrection1 =
                1.0 - Math.pow(beta1, timestep);

        double biasCorrection2 =
                1.0 - Math.pow(beta2, timestep);

        for (int i = 0; i < weights.length; i++) {
            for (int j = 0; j < weights[i].length; j++) {

                double gradient =
                        gradients[i][j];

                firstMoment[i][j] =
                        beta1 * firstMoment[i][j]
                        + (1.0 - beta1) * gradient;

                secondMoment[i][j] =
                        beta2 * secondMoment[i][j]
                        + (1.0 - beta2)
                        * gradient
                        * gradient;

                double correctedFirst =
                        firstMoment[i][j]
                        / biasCorrection1;

                double correctedSecond =
                        secondMoment[i][j]
                        / biasCorrection2;

                weights[i][j] -=
                        learningRate
                        * correctedFirst
                        / (Math.sqrt(correctedSecond)
                        + epsilon);
            }
        }
    }

    private void updateBias(
            double[] biases,
            double[] firstMoment,
            double[] secondMoment,
            double[] gradients,
            double learningRate) {

        double biasCorrection1 =
                1.0 - Math.pow(beta1, timestep);

        double biasCorrection2 =
                1.0 - Math.pow(beta2, timestep);

        for (int i = 0; i < biases.length; i++) {

            double gradient =
                    gradients[i];

            firstMoment[i] =
                    beta1 * firstMoment[i]
                    + (1.0 - beta1) * gradient;

            secondMoment[i] =
                    beta2 * secondMoment[i]
                    + (1.0 - beta2)
                    * gradient
                    * gradient;

            double correctedFirst =
                    firstMoment[i]
                    / biasCorrection1;

            double correctedSecond =
                    secondMoment[i]
                    / biasCorrection2;

            biases[i] -=
                    learningRate
                    * correctedFirst
                    / (Math.sqrt(correctedSecond)
                    + epsilon);
        }
    }

    private double relu(double x) {
        return Math.max(0.0, x);
    }
    public void trainWithOutputGradient(
        double[] input,
        double[] outputGradient,
        double learningRate) {

        trainWithOutputGradients(new double[][] {input},
                new double[][] {outputGradient}, 1, learningRate);
    }

    /** Applies one Adam update using the mean gradient over the supplied batch. */
    public void trainBatch(double[][] inputs, double[][] targets, double learningRate) {
        if (inputs == null || targets == null || inputs.length == 0
                || inputs.length != targets.length) {
            throw new IllegalArgumentException("Batch inputs and targets must have equal non-zero length");
        }
        double[][] outputGradients = new double[inputs.length][outputSize];
        for (int sample = 0; sample < inputs.length; sample++) {
            if (targets[sample] == null || targets[sample].length != outputSize) {
                throw new IllegalArgumentException("Target dimension must match network output size");
            }
            double[] output = forward(inputs[sample]);
            for (int j = 0; j < outputSize; j++) {
                // Eq. (28) - Batch critic gradient uses the same V(s)-G_hat derivative convention.
                outputGradients[sample][j] = output[j] - targets[sample][j];
            }
        }
        trainWithOutputGradients(inputs, outputGradients, inputs.length, learningRate);
    }

    /**
     * Applies one Adam update for a batch of output-space gradients. The
     * normalization count may exceed the supplied inputs when omitted rows
     * represent zero-gradient inactive-agent samples in an objective batch.
     */
    public void trainWithOutputGradients(double[][] inputs,
                                         double[][] outputGradients,
                                         int normalizationCount,
                                         double learningRate) {
        if (inputs == null || outputGradients == null || inputs.length == 0
                || inputs.length != outputGradients.length
                || normalizationCount < inputs.length) {
            throw new IllegalArgumentException("Invalid output-gradient minibatch");
        }

        GradientAccumulator gradients = new GradientAccumulator(inputSize, hiddenSize, outputSize);
        for (int sample = 0; sample < inputs.length; sample++) {
            if (outputGradients[sample] == null || outputGradients[sample].length != outputSize) {
                throw new IllegalArgumentException("Output gradient dimension must match network output size");
            }
            accumulateGradients(inputs[sample], outputGradients[sample], gradients);
        }

        double scale = 1.0 / normalizationCount;
        scale(gradients.gw1, scale); scale(gradients.gw2, scale); scale(gradients.gw3, scale);
        scale(gradients.gb1, scale); scale(gradients.gb2, scale); scale(gradients.gb3, scale);
        timestep++;
        updateWeights(w1, mw1, vw1, gradients.gw1, learningRate);
        updateWeights(w2, mw2, vw2, gradients.gw2, learningRate);
        updateWeights(w3, mw3, vw3, gradients.gw3, learningRate);
        updateBias(b1, mb1, vb1, gradients.gb1, learningRate);
        updateBias(b2, mb2, vb2, gradients.gb2, learningRate);
        updateBias(b3, mb3, vb3, gradients.gb3, learningRate);
    }

    private void accumulateGradients(double[] input, double[] outputGradient,
                                     GradientAccumulator gradients) {
        if (input == null || input.length != inputSize) {
            throw new IllegalArgumentException("Input dimension must match network input size");
        }
        double[] h1 = new double[hiddenSize];
        double[] h2 = new double[hiddenSize];
        for (int j = 0; j < hiddenSize; j++) {
            double sum = b1[j];
            for (int i = 0; i < inputSize; i++) sum += input[i] * w1[i][j];
            h1[j] = relu(sum);
        }
        for (int j = 0; j < hiddenSize; j++) {
            double sum = b2[j];
            for (int i = 0; i < hiddenSize; i++) sum += h1[i] * w2[i][j];
            h2[j] = relu(sum);
        }

        double[] dH2 = new double[hiddenSize];
        for (int i = 0; i < hiddenSize; i++) {
            double sum = 0.0;
            for (int j = 0; j < outputSize; j++) sum += outputGradient[j] * w3[i][j];
            dH2[i] = h2[i] > 0.0 ? sum : 0.0;
        }
        double[] dH1 = new double[hiddenSize];
        for (int i = 0; i < hiddenSize; i++) {
            double sum = 0.0;
            for (int j = 0; j < hiddenSize; j++) sum += dH2[j] * w2[i][j];
            dH1[i] = h1[i] > 0.0 ? sum : 0.0;
        }

        for (int i = 0; i < hiddenSize; i++) {
            for (int j = 0; j < outputSize; j++) gradients.gw3[i][j] += h2[i] * outputGradient[j];
        }
        for (int j = 0; j < outputSize; j++) gradients.gb3[j] += outputGradient[j];
        for (int i = 0; i < hiddenSize; i++) {
            for (int j = 0; j < hiddenSize; j++) gradients.gw2[i][j] += h1[i] * dH2[j];
        }
        for (int j = 0; j < hiddenSize; j++) gradients.gb2[j] += dH2[j];
        for (int i = 0; i < inputSize; i++) {
            for (int j = 0; j < hiddenSize; j++) gradients.gw1[i][j] += input[i] * dH1[j];
        }
        for (int j = 0; j < hiddenSize; j++) gradients.gb1[j] += dH1[j];
    }

    private void scale(double[][] values, double factor) {
        for (double[] row : values) scale(row, factor);
    }

    private void scale(double[] values, double factor) {
        for (int i = 0; i < values.length; i++) values[i] *= factor;
    }

    private static final class GradientAccumulator {
        final double[][] gw1, gw2, gw3;
        final double[] gb1, gb2, gb3;

        GradientAccumulator(int inputSize, int hiddenSize, int outputSize) {
            gw1 = new double[inputSize][hiddenSize];
            gw2 = new double[hiddenSize][hiddenSize];
            gw3 = new double[hiddenSize][outputSize];
            gb1 = new double[hiddenSize];
            gb2 = new double[hiddenSize];
            gb3 = new double[outputSize];
        }
    }

}
